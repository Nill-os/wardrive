#include "CydBleLink.h"

#include <NimBLEDevice.h>
#include <deque>
#include <functional>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>

namespace CydBleLink {

// Arbitrary private 128-bit UUIDs (not registered anywhere). RX = the phone
// writes into it, TX = cyd_node notifies on it - named from the peripheral's
// point of view, same convention Nordic's own UART service uses.
static const char *SERVICE_UUID = "5b60de00-0000-4a6c-9b1a-6364796477b1";
static const char *RX_CHAR_UUID = "5b60de00-0001-4a6c-9b1a-6364796477b1";
static const char *TX_CHAR_UUID = "5b60de00-0002-4a6c-9b1a-6364796477b1";

// Conservative on purpose - see the "why" in the header comment. 18 bytes
// fits inside even the mandatory-minimum 23-byte ATT MTU (20 usable bytes
// after the 3-byte header) with a hair of margin.
static const size_t CHUNK_SIZE = 18;

static SemaphoreHandle_t lineMutex = nullptr;
static std::deque<String> lineQueue;
static const size_t MAX_QUEUED_LINES = 64; // defensive cap - normal operation drains this every loop() well before it fills

static void pushLine(const String &line) {
	if (line.length() == 0) return;
	xSemaphoreTake(lineMutex, portMAX_DELAY);
	if (lineQueue.size() >= MAX_QUEUED_LINES) lineQueue.pop_front(); // drop oldest rather than grow unbounded
	lineQueue.push_back(line);
	xSemaphoreGive(lineMutex);
}

bool hasLine() {
	xSemaphoreTake(lineMutex, portMAX_DELAY);
	bool has = !lineQueue.empty();
	xSemaphoreGive(lineMutex);
	return has;
}

String readLine() {
	xSemaphoreTake(lineMutex, portMAX_DELAY);
	String line;
	if (!lineQueue.empty()) {
		line = lineQueue.front();
		lineQueue.pop_front();
	}
	xSemaphoreGive(lineMutex);
	return line;
}

// Reassembles complete lines out of however many BLE packets they actually
// arrived in, which may not align with line boundaries at all.
//
// Keyed by BLE connection handle so a stray/leftover connection can't corrupt
// the real one's buffer.
struct RxAssembly {
	uint16_t connHandle = 0xFFFF; // 0xFFFF = unused slot
	String buf;
};
static const size_t MAX_RX_ASSEMBLIES = 4; // generous headroom over the single real connection cyd_node ever expects
static RxAssembly rxAssemblies[MAX_RX_ASSEMBLIES];

static RxAssembly &assemblyFor(uint16_t connHandle) {
	for (auto &a : rxAssemblies) {
		if (a.connHandle == connHandle) return a;
	}
	for (auto &a : rxAssemblies) {
		if (a.connHandle == 0xFFFF) {
			a.connHandle = connHandle;
			return a;
		}
	}
	return rxAssemblies[0]; // shouldn't happen with MAX_RX_ASSEMBLIES this generous; degrade rather than crash
}

static void clearAssembly(uint16_t connHandle) {
	for (auto &a : rxAssemblies) {
		if (a.connHandle == connHandle) {
			a.connHandle = 0xFFFF;
			a.buf = "";
			return;
		}
	}
}

static void feedIncomingBytes(uint16_t connHandle, const uint8_t *data, size_t len) {
	String &buf = assemblyFor(connHandle).buf;
	for (size_t i = 0; i < len; i++) {
		char c = (char)data[i];
		if (c == '\n') {
			if (buf.length() > 0) {
				pushLine(buf);
				buf = "";
			}
		} else if (c != '\r') {
			if (buf.length() < 256) buf += c; // real lines here are all well under this
		}
	}
}

static void sendChunked(std::function<void(const uint8_t *, size_t)> transmit, const String &line,
						size_t chunkSize = CHUNK_SIZE) {
	String withNewline = line + "\n";
	size_t total = withNewline.length();
	for (size_t offset = 0; offset < total; offset += chunkSize) {
		size_t n = min(chunkSize, total - offset);
		transmit((const uint8_t *)withNewline.c_str() + offset, n);
	}
}

// ---- Server / peripheral role (cyd_node) ----

static NimBLEServer *pServer = nullptr;
static NimBLECharacteristic *pTxChar = nullptr;
static NimBLECharacteristic *pRxChar = nullptr;

// Tracked explicitly from these callbacks rather than trusting a live
// NimBLEServer::getConnectedCount() query in isConnected() - matches the
// client role's own clientConnected bool below, which has never shown this
// problem. onConnect() deliberately keeps advertising active even while
// already connected (so a stray second central, or this same central
// reconnecting mid-churn, can connect promptly), which means a real
// connect/disconnect race during heavy reconnect churn (wifi_node creates a
// fresh NimBLEClient object on every attempt - see its own comment) can
// leave getConnectedCount() and the actual live link disagreeing: real data
// was seen flowing over an active NOTIFY subscription (confirmed via
// wifi_node's own serial log) while getConnectedCount() reported 0 and the
// phone app's MESH indicator stayed DOWN the whole time (2026-09-28). An
// explicit bool updated directly from the connect/disconnect events isn't
// vulnerable to whatever internal NimBLE bookkeeping produced that mismatch.
//
// A count rather than a bool: with a bool, a second central connecting and
// leaving would mark the link down while the phone was still connected.
// Advertising stops while a phone is connected (NimBLE's default), so a
// second central can only slip in during the moment of a reconnect.
static volatile int serverConnections = 0;

class LinkServerCallbacks : public NimBLEServerCallbacks {
	void onConnect(NimBLEServer *server, ble_gap_conn_desc *desc) override {
		serverConnections++;
	}
	void onDisconnect(NimBLEServer *server, ble_gap_conn_desc *desc) override {
		if (serverConnections > 0) serverConnections--;
		clearAssembly(desc->conn_handle);
		NimBLEDevice::startAdvertising(); // connectable again straight away, so the phone can come back
	}
};

class LinkRxCallbacks : public NimBLECharacteristicCallbacks {
	void onWrite(NimBLECharacteristic *characteristic, ble_gap_conn_desc *desc) override {
		NimBLEAttValue v = characteristic->getValue();
		feedIncomingBytes(desc->conn_handle, v.data(), v.length());
	}
};

static String serverAdvertisedName;

static void startServerStack() {
	NimBLEDevice::init(serverAdvertisedName.c_str());
	NimBLEDevice::setMTU(256); // best-effort - correctness doesn't depend on this succeeding, see header comment

	pServer = NimBLEDevice::createServer();
	pServer->setCallbacks(new LinkServerCallbacks());

	NimBLEService *service = pServer->createService(SERVICE_UUID);
	pTxChar = service->createCharacteristic(TX_CHAR_UUID, NIMBLE_PROPERTY::NOTIFY);
	pRxChar = service->createCharacteristic(RX_CHAR_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
	pRxChar->setCallbacks(new LinkRxCallbacks());
	service->start();

	NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
	advertising->addServiceUUID(SERVICE_UUID);
	advertising->setScanResponse(true);

	// advertising->start() right after a fresh NimBLEDevice::init() (as
	// resumeServer() does, straight after suspendServer()'s full deinit())
	// intermittently fails with "rc=30" (BLE_HS_EDISABLED) - confirmed via
	// live testing (2026-09-27) that the host stack isn't always finished
	// syncing yet at that exact moment, and there's no callback to wait on
	// from here. This was the actual cause of the LINK button's wildly
	// inconsistent recovery time (under a second most of the time, 30s+
	// occasionally) - a first attempt that loses this race left cyd_node
	// silently not advertising at all until whatever happened to retry it
	// next, which had no fixed schedule. A short bounded retry here closes
	// that gap outright instead of leaving it to chance.
	for (int attempt = 0; attempt < 20; attempt++) {
		if (advertising->start()) break;
		vTaskDelay(pdMS_TO_TICKS(50));
	}
}

void beginServer(const char *advertisedName) {
	lineMutex = xSemaphoreCreateMutex();
	serverAdvertisedName = advertisedName;
	startServerStack();
}

// cyd_node's upload path (HTTPS to wdgwars.pl/WiGLE) needs a real chunk of
// contiguous heap for the TLS handshake, and NimBLE's own connection/GATT
// state competes for the same heap - found via a "BIGNUM - Memory
// allocation failed" TLS error on WiGLE's upload specifically (its cert
// apparently needs more scratch space than wdgwars.pl's does). doUpload()
// already blocks the whole main loop for the duration of an upload anyway
// (see its own call site), so the BLE link isn't servicing anything during
// that window regardless of whether the radio itself stays up - tearing it
// down here costs nothing functionally and hands that RAM to the TLS
// handshake instead. The phone app notices the disconnect and reconnects on
// its own once resumeServer() re-advertises, the same self-healing reconnect
// a real out-of-range gap already causes.
void suspendServer() {
	if (!pServer) return; // not running as a server, or already suspended
	// Pointers nulled *before* deinit() (not after) - send() snapshots them
	// into locals before use (see its own comment), so nulling first means
	// any send() call that grabs its snapshot after this point sees null
	// and no-ops cleanly, rather than holding a pointer to an object
	// deinit() is about to free out from under it.
	pServer = nullptr;
	pTxChar = nullptr;
	pRxChar = nullptr;
	serverConnections = 0;
	for (auto &a : rxAssemblies) {
		a.connHandle = 0xFFFF;
		a.buf = "";
	}
	NimBLEDevice::deinit(true);
}

void resumeServer() {
	if (pServer || serverAdvertisedName.length() == 0) return; // already running, or never began as a server
	startServerStack();
}

// ---- Shared entry points ----

void poll() {
	// The real work happens in BLE callbacks - nothing to do here today. Kept
	// as a real call so future housekeeping has an obvious place to live.
}

bool isConnected() {
	return pServer != nullptr && serverConnections > 0;
}

void send(const String &line) {
	// Snapshotted into locals rather than read fresh on every lambda
	// invocation inside sendChunked()'s loop - suspendServer() (called from
	// a different task, around an upload) nulls these static pointers, and
	// re-reading the static mid-loop could see it go null between one
	// chunk and the next, crashing on a null dereference. A local snapshot
	// makes each send() call internally consistent instead.
	NimBLECharacteristic *txChar = pTxChar;
	NimBLEServer *server = pServer;
	if (txChar) { // notify() reaches the connected central (the phone)
		if (!server || serverConnections <= 0) return;
		// Chunk to whatever MTU the central actually negotiated (a phone
		// typically asks for ~247) instead of always the 23-byte floor - a
		// ~130-byte WD:AP line is one notification instead of eight. Still
		// correct at the floor, since the receiver reassembles on '\n'.
		size_t chunk = CHUNK_SIZE;
		std::vector<uint16_t> peers = server->getPeerDevices();
		if (!peers.empty()) {
			uint16_t mtu = server->getPeerMTU(peers[0]);
			if (mtu > 3 + CHUNK_SIZE) chunk = min((size_t)(mtu - 3), (size_t)240);
		}
		sendChunked([txChar](const uint8_t *data, size_t n) {
			txChar->setValue(data, n);
			txChar->notify();
		}, line, chunk);
	}
}

} // namespace CydBleLink
