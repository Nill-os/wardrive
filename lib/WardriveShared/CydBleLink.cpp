#include "CydBleLink.h"

#include <NimBLEDevice.h>
#include <deque>
#include <functional>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>

namespace CydBleLink {

// Arbitrary private 128-bit UUIDs (not registered anywhere - fine for two
// boards that only ever talk to each other). RX = central writes into it
// (wifi_node -> cyd_node), TX = peripheral notifies on it (cyd_node ->
// wifi_node) - named from the peripheral's point of view, same convention
// Nordic's own UART service uses.
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
// Server role only: cyd_node only ever expects a single central connection
// (wifi_node - see CydBleLink.h), but keys the assembly by BLE connection
// handle anyway so a stray/leftover connection can't corrupt the real one's
// buffer. The client role only ever has one connection (to cyd_node), so it
// just uses slot 0 unconditionally.
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
static volatile bool serverConnected = false;

class LinkServerCallbacks : public NimBLEServerCallbacks {
	void onConnect(NimBLEServer *server, ble_gap_conn_desc *desc) override {
		serverConnected = true;
		// Keep advertising after a connect (NimBLE stops it by default once
		// a central connects) - harmless if wifi_node is already the one
		// connected, and lets it reconnect promptly if this onConnect is
		// actually a stray/unexpected second central.
		NimBLEDevice::startAdvertising();
	}
	void onDisconnect(NimBLEServer *server, ble_gap_conn_desc *desc) override {
		// cyd_node only ever expects one real central (wifi_node) - no need to
		// re-query for other survivors, same simple model the client role's
		// clientConnected already uses.
		serverConnected = false;
		clearAssembly(desc->conn_handle);
		NimBLEDevice::startAdvertising(); // this board is meant to always be connectable/discoverable while idle
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
// handshake instead. wifi_node's client task notices the disconnect and
// reconnects on its own once resumeServer() re-advertises, the same
// self-healing reconnect a real out-of-range gap already causes.
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
	serverConnected = false;
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

// ---- Client / central role (wifi_node) ----

static NimBLEClient *pClient = nullptr;
static NimBLERemoteCharacteristic *pRemoteRxChar = nullptr; // write into cyd_node
static NimBLERemoteCharacteristic *pRemoteTxChar = nullptr; // subscribe - cyd_node notifies us
static volatile bool clientConnected = false;
static String clientTargetName;
static TaskHandle_t clientTaskHandle = nullptr;

// The client role only ever has one connection (to cyd_node), so it always
// uses assembly slot/handle 0 regardless of the real BLE connection handle
// NimBLE assigns - there's no risk of cross-talk to guard against here
// since there's only ever one peer.
static const uint16_t CLIENT_ASSEMBLY_HANDLE = 0;

class LinkClientCallbacks : public NimBLEClientCallbacks {
	void onDisconnect(NimBLEClient *client) override {
		Serial.println("[cydlink] client disconnected");
		clientConnected = false;
		pRemoteRxChar = nullptr;
		pRemoteTxChar = nullptr;
		clearAssembly(CLIENT_ASSEMBLY_HANDLE);
	}
};

static void onRemoteNotify(NimBLERemoteCharacteristic *, uint8_t *data, size_t length, bool) {
	feedIncomingBytes(CLIENT_ASSEMBLY_HANDLE, data, length);
}

// Scanning is owned by the CALLER (wifi_node), not by this module - lets
// wifi_node reuse the same NimBLEScan handle for anything else it might
// ever need a scan for, without this module fighting it over ownership of
// NimBLE's single scan configuration.
static volatile bool foundTargetPending = false;
static NimBLEAddress foundTargetAddress;

void feedScanResult(NimBLEAdvertisedDevice *device) {
	if (clientConnected || foundTargetPending) return;
	if (!device->haveName() || device->getName() != clientTargetName.c_str()) return;
	foundTargetAddress = device->getAddress();
	foundTargetPending = true; // clientTaskFn() below picks this up
	Serial.printf("[cydlink] found target %s\n", foundTargetAddress.toString().c_str());
}

// Only the actual connect/discover/subscribe sequence happens here now
// (scanning itself is the caller's job, fed in via feedScanResult() above) -
// still deliberately isolated on its own task, since NimBLE's connect()
// call can block for a couple of seconds, and a board with time-sensitive
// work in its own main loop (wifi_node's WiFi channel-hop timing) can't
// afford to stall on that.
static void clientTaskFn(void *) {
	for (;;) {
		if (!clientConnected && foundTargetPending) {
			foundTargetPending = false;
			NimBLEAddress address = foundTargetAddress;

			// A NimBLEClient object left over from a previous connection
			// (whether it ended in a real disconnect or a failed subscribe
			// below) reliably failed to connect() a second time in testing -
			// NimBLE-Arduino 1.4.3's client-side GATT state doesn't fully
			// reset on disconnect, so reusing the same object silently made
			// every reconnect attempt after the very first one fail forever
			// (connect() returning false with nothing left to retry, since
			// the next scan match just tried the same broken object again).
			// A fresh client per attempt is the reliable fix.
			if (pClient) {
				NimBLEDevice::deleteClient(pClient);
				pClient = nullptr;
			}
			pRemoteRxChar = nullptr;
			pRemoteTxChar = nullptr;
			pClient = NimBLEDevice::createClient();
			pClient->setClientCallbacks(new LinkClientCallbacks(), false);

			// NimBLE-Arduino's connection defaults (~7.5-30ms interval, ~few-
			// hundred-ms supervision timeout) assume a radio with nothing else
			// to do. wifi_node's radio is also busy hopping WiFi channel every
			// ~150ms in a tight promiscuous-capture loop the whole time it's
			// scanning (the "WiFi/BT radio-coexistence tax" this file's header
			// already calls out) - under that load, a short supervision
			// timeout can trip on a single missed connection event that's just
			// a WiFi channel-hop stealing the radio for a few ms, not an
			// actual out-of-range drop, forcing a full reconnect+rescan+
			// resubscribe cycle that loses every W,/B, line in the gap. A
			// longer interval (needs the radio less often) and latency (lets
			// several intervals pass with nothing to send before it's even
			// due) plus a generous supervision timeout give normal
			// coexistence hiccups room to resolve on their own within the
			// same connection instead of tearing it down - unverified against
			// a real drive yet, since this needs field testing to confirm,
			// but directly targets the exact mechanism (coexistence-induced
			// drops => lost mesh data => stalled aps/bles counts on cyd_node's
			// own screen) reported from one.
			pClient->setConnectionParams(24, 40, 4, 600);

			bool connected = pClient->connect(address);
			Serial.printf("[cydlink] connect(%s) -> %d\n", address.toString().c_str(), connected);
			if (connected) {
				NimBLERemoteService *service = pClient->getService(SERVICE_UUID);
				if (service) {
					pRemoteRxChar = service->getCharacteristic(RX_CHAR_UUID);
					pRemoteTxChar = service->getCharacteristic(TX_CHAR_UUID);
				}
				bool subscribed = pRemoteRxChar && pRemoteTxChar && pRemoteTxChar->canNotify() &&
								  pRemoteTxChar->subscribe(true, onRemoteNotify);
				if (subscribed) {
					clientConnected = true;
				} else {
					Serial.printf("[cydlink] service=%d rx=%d tx=%d subscribed=0 - disconnecting to retry\n",
								  service != nullptr, pRemoteRxChar != nullptr, pRemoteTxChar != nullptr);
					pClient->disconnect();
				}
			}
		}

		vTaskDelay(pdMS_TO_TICKS(clientConnected ? 1000 : 200));
	}
}

// The caller must already have called NimBLEDevice::init() and set up its
// own NimBLEScan (via NimBLEDevice::getScan()) before this - and must
// forward every scan result to feedScanResult() above from its own
// NimBLEAdvertisedDeviceCallbacks::onResult(). This module only handles the
// connect-once-found part.
void beginClient(const char *targetName) {
	lineMutex = xSemaphoreCreateMutex();
	clientTargetName = targetName;
	xTaskCreatePinnedToCore(clientTaskFn, "cydBleLink", 8192, nullptr, 1, &clientTaskHandle, 1);
}

// ---- Shared entry points ----

void poll() {
	// Both roles do their real work elsewhere (BLE callbacks for the
	// server, the dedicated task for the client) - nothing to do here for
	// either role today. Kept as a real call (not a no-op macro) so future
	// housekeeping has an obvious, already-wired-in place to live.
}

bool isConnected() {
	// Server role: pServer itself is read into a local first since
	// suspendServer() (a different task, see its own comment) can null it
	// out concurrently. serverConnected (not a live getConnectedCount()
	// query) is the source of truth - see its own comment for why.
	NimBLEServer *server = pServer;
	if (server) return serverConnected;
	return clientConnected;
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
	NimBLERemoteCharacteristic *rxChar = pRemoteRxChar;
	if (txChar) { // server role - notify() reaches the connected central (the phone)
		if (!server || !serverConnected) return;
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
	} else if (rxChar) { // client role
		if (!clientConnected) return;
		sendChunked([rxChar](const uint8_t *data, size_t n) {
			rxChar->writeValue(data, n, false);
		}, line);
	}
}

} // namespace CydBleLink
