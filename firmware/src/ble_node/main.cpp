// ble_node: BLE wardrive scanner.
// No SD card, no GPS, no upload logic - this board only scans and streams
// each observation to wifi_node over a wired UART link. wifi_node enriches
// it with its own GPS fix/timestamp and relays it onward to cyd_node over
// its wired link, same as its own WiFi observations - see wifi_node/main.cpp.
//
// No physical button on this board (removed rig-wide - cyd_node's
// touchscreen is now the only start/stop/upload control surface). Scanning
// state is entirely driven by wifi_node relaying cyd_node's broadcast
// SCANSTATE line onward over this same wire - see handleLinkStatusLine()'s
// SCANSTATE case.
//
// See ../../README.md for wiring and config.cfg setup.

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <unordered_set>

// Flip to false once the rig is proven out - see wifi_node/main.cpp for why.
static constexpr bool WARDRIVE_DEBUG = true;
static const uint32_t HEARTBEAT_MS = 2000;

// ---- Pin assignments (see README for wiring diagram) ----
// GPIO4 (formerly the physical button) is free and unused now that the
// button's been removed rig-wide.
// This is NOT a standard crossed RX/TX pair on the same two pins - the
// original design (see README.md's wiring table) is two separate one-way
// lines: GPIO8 carries ble_node's own observations TO wifi_node (wifi_node
// listens on ITS OWN GPIO8), GPIO3 carries wifi_node's status broadcasts TO
// ble_node (wifi_node transmits on ITS OWN GPIO3). Each board only ever
// drives ONE of the two pins and only ever listens on the OTHER - the
// physical wire is genuinely pin8-to-pin8 and pin3-to-pin3 (same numbers
// on both boards), which only works because the two boards assign OPPOSITE
// roles to those same two pin numbers. Bug found via testing (2026-09-27):
// this used to declare RX=8/TX=3, mirroring wifi_node's own role
// assignment instead of the opposite - both boards ended up listening on
// GPIO8 (nobody transmitting) and both transmitting on GPIO3 (contention),
// so nothing was ever received on either wire despite the physical
// connection being correct the whole time.
static const uint8_t PIN_LINK_RX = 3;  // <- wifi_node TX
static const uint8_t PIN_LINK_TX = 8;  // -> wifi_node RX

HardwareSerial LinkSerial(1);
// Must match wifi_node's LINK_BAUD - see its comment for why it's not 115200.
static const uint32_t LINK_BAUD = 460800;
static const size_t LINK_BUFFER_BYTES = 4096;

// Whether wifi_node has a position to tag sightings with (its GPSFIX line,
// several times a second). Without one it drops every sighting - so while
// false, nothing is sent AND nothing is marked as seen, otherwise every
// device seen before the first fix of a run was lost for the whole run.
volatile bool wifiNodeHasFix = false;
volatile uint32_t lastGpsFixLineMs = 0;
static const uint32_t GPSFIX_STALE_MS = 3000; // link gone quiet - treat as no fix

NimBLEScan *pBLEScan;

volatile bool scanningActive = false;
bool wasScanning = false;
uint32_t lastHeartbeatMs = 0;
uint32_t observationsSent = 0;

// Onboard RGB LED feedback: brief non-blocking flicker per click, distinct
// color per click type. RGB_BUILTIN/neopixelWrite come from the Arduino
// core's built-in support for boards with an onboard addressable LED
// (GPIO48 on a standard ESP32-S3-DevKitC-1) - no extra wiring or library.
// Colors below are passed as full-intensity (0-255) hues; flashLed scales
// them down to LED_BRIGHTNESS_PCT before writing, so this one constant
// controls how bright the whole LED is.
static const uint32_t LED_FLICKER_MS = 150;
uint8_t ledBrightnessPct = 3;
// Customizable status-LED colors (0xRRGGBB), relayed from cyd_node's config.cfg.
uint32_t ledColorAp = 0xFF00FF, ledColorBle = 0x00FFFF, ledColorOk = 0x00FF00, ledColorFail = 0xFF0000;
// Map a canonical accent color the code passes to the customized palette color.
static uint32_t remapLedColor(uint8_t r, uint8_t g, uint8_t b) {
	if (r == 255 && g == 0 && b == 255) return ledColorAp;
	if (r == 0 && g == 255 && b == 255) return ledColorBle;
	if (r == 0 && g == 255 && b == 0) return ledColorOk;
	if (r == 255 && g == 0 && b == 0) return ledColorFail;
	return ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
} // 0-100; relayed from cyd_node via wifi_node
uint32_t ledOffAtMs = 0;
bool ledPriority = false; // true while a click/upload-status flash is showing

static uint8_t scaleBrightness(uint8_t channel) {
	return (uint16_t)channel * ledBrightnessPct / 100;
}

// priority=true (clicks, upload status) always shows and can't be cut short
// by a capture flash. priority=false (per-packet captures) is skipped
// outright while a priority flash is still active - this board keeps
// scanning independently while wifi_node uploads, so without this a busy
// BLE area could mask the OK/FAIL result with cyan forever and the LED
// would never look like it settled back to idle.
static void flashLed(uint8_t r, uint8_t g, uint8_t b, uint32_t durationMs = LED_FLICKER_MS, bool priority = false) {
	if (!priority && ledPriority && millis() < ledOffAtMs) return;
	uint32_t c = remapLedColor(r, g, b);
	r = (c >> 16) & 0xFF; g = (c >> 8) & 0xFF; b = c & 0xFF;
	neopixelWrite(RGB_BUILTIN, scaleBrightness(r), scaleBrightness(g), scaleBrightness(b));
	ledOffAtMs = millis() + durationMs;
	ledPriority = priority;
}

static const uint32_t LED_RESULT_MS = 800; // longer, clearly-visible flash for upload outcome

// Mirrors wifi_node's "can't start - no SD" blink pattern (5 short red
// flashes, then idle), for the case where this board refuses its own click
// because wifi_node has nowhere to save data. Same mechanism: each on-phase
// is a normal priority flashLed() call, timed out by serviceErrorBlink().
static const uint32_t SD_ERROR_BLINK_MS = 200;
static const uint8_t SD_ERROR_BLINK_COUNT = 5;
uint8_t errorBlinksRemaining = 0;
uint32_t nextErrorBlinkMs = 0;

static void startSdErrorBlink() {
	errorBlinksRemaining = SD_ERROR_BLINK_COUNT - 1; // this call fires the first flash itself
	flashLed(255, 0, 0, SD_ERROR_BLINK_MS, true);
	nextErrorBlinkMs = millis() + SD_ERROR_BLINK_MS * 2; // on-time + an equal off-gap
}

static void serviceErrorBlink() {
	if (errorBlinksRemaining == 0) return;
	if (millis() < nextErrorBlinkMs) return;
	flashLed(255, 0, 0, SD_ERROR_BLINK_MS, true);
	errorBlinksRemaining--;
	nextErrorBlinkMs = millis() + SD_ERROR_BLINK_MS * 2;
}

// Mirrors cyd_node's alternating cyan/purple "upload in progress" pattern,
// but always in the OPPOSITE color from whatever cyd_node is currently
// showing - cyd_node's own upload-blink task broadcasts "BLINKPHASE:0/1"
// to wifi_node, which relays it onward over this wire, specifically so this
// board can react to that instead of running its own independent timer,
// which would drift out of sync almost immediately and stop reading as one
// coordinated pattern.
static const uint32_t UPLOAD_BLINK_MS = 200;

// This board has no input of its own to start/stop scanning (see file
// header) - scanningActive only ever changes in response to wifi_node
// relaying cyd_node's broadcast SCANSTATE, so there's no local "resume
// after power loss" decision to make either: it just starts false and picks
// up whatever was last relayed within a couple seconds of the link coming
// up. Assumed true until wifi_node's relayed SDOK says otherwise, so this
// board isn't permanently stuck refusing to start if the link is slow to
// come up.
volatile bool wifiNodeSdOk = true;

// wifi_node relays cyd_node's upload progress, storage health, and scanning
// state onward over this wire so this board's LED and scanning state stay
// in step, even though cyd_node is the only one with SD/config/upload logic.
static void handleLinkStatusLine(const String &line) {
	if (WARDRIVE_DEBUG && !line.startsWith("GPSFIX:")) Serial.printf("[link] rx: %s\n", line.c_str());
	if (line == "START") {
		// No LED action here - cyd_node's blink task sends its first
		// BLINKPHASE line within a blink interval anyway, and reacting to
		// that is what actually keeps the two boards' colors synchronized.
	} else if (line == "OK") {
		flashLed(0, 255, 0, LED_RESULT_MS, true);
	} else if (line == "FAIL") {
		flashLed(255, 0, 0, LED_RESULT_MS, true);
	} else if (line.startsWith("BLINKPHASE:")) {
		// Always the opposite of whatever color cyd_node just reported
		// showing, so the two boards blink back and forth against each
		// other rather than in lockstep with each other.
		bool cydCyan = line.substring(11).toInt() == 0;
		if (cydCyan) flashLed(255, 0, 255, UPLOAD_BLINK_MS, true); // cyd_node is cyan - show purple
		else flashLed(0, 255, 255, UPLOAD_BLINK_MS, true); // cyd_node is purple - show cyan
	} else if (line == "LOWSTORAGE") {
		flashLed(255, 255, 255, LED_FLICKER_MS, true); // white - cyd_node's SD card is running low
	} else if (line.startsWith("SCANSTATE:")) {
		// wifi_node is relaying cyd_node's authoritative scanning state
		// (its touchscreen is the rig's only control surface now - see file
		// header), broadcast every couple seconds regardless of debug
		// settings - this board has no input of its own to disagree with,
		// so this is purely a self-heal for the case where this board
		// missed an update because it was unpowered at the time.
		bool wantScanning = line.substring(10).toInt() != 0;
		if (wantScanning != scanningActive) {
			scanningActive = wantScanning;
			flashLed(0, 255, 0, LED_FLICKER_MS, true);
			if (WARDRIVE_DEBUG) Serial.printf("[link] was out of sync - resynced to scanning=%d\n", scanningActive);
		}
	} else if (line.startsWith("CFG:ledBrightness=")) {
		int v = line.substring(18).toInt();
		ledBrightnessPct = v < 0 ? 0 : v > 100 ? 100 : v;
		if (WARDRIVE_DEBUG) Serial.printf("[cfg] LED brightness -> %u%%\n", ledBrightnessPct);
	} else if (line.startsWith("CFG:ledColors=")) {
		String v = line.substring(14);
		int c1 = v.indexOf(','), c2 = v.indexOf(',', c1 + 1), c3 = v.indexOf(',', c2 + 1);
		if (c1 > 0 && c2 > 0 && c3 > 0) {
			ledColorAp = strtoul(v.substring(0, c1).c_str(), nullptr, 16);
			ledColorBle = strtoul(v.substring(c1 + 1, c2).c_str(), nullptr, 16);
			ledColorOk = strtoul(v.substring(c2 + 1, c3).c_str(), nullptr, 16);
			ledColorFail = strtoul(v.substring(c3 + 1).c_str(), nullptr, 16);
		}
		if (WARDRIVE_DEBUG) Serial.printf("[cfg] LED colors ap=%06lX\n", (unsigned long)ledColorAp);
	} else if (line.startsWith("SDOK:")) {
		bool newVal = line.substring(5).toInt() != 0;
		if (WARDRIVE_DEBUG && newVal != wifiNodeSdOk) {
			Serial.printf("[link] wifiNodeSdOk %d -> %d\n", wifiNodeSdOk, newVal);
		}
		wifiNodeSdOk = newVal;
	} else if (line.startsWith("GPSFIX:")) {
		wifiNodeHasFix = line.substring(7).toInt() != 0;
		lastGpsFixLineMs = millis();
	}
}

// Phones and headphones re-advertise many times a second - sending every
// single one to wifi_node bloats its log for no real benefit. Unlike
// wifi_node's AP dedup, this board has no GPS to judge "have I moved", and
// BLE devices are often mobile themselves anyway (someone's phone), so this
// is a straight per-run seen-set instead of a distance check: each MAC is
// sent once per run, full stop, and the set is cleared fresh at the start
// of every run - duplicates across separate runs are fine, never within one.
//
// bleSeenThisRun itself is ONLY ever touched from shouldSendBle(), which
// only ever runs on the NimBLE host task (via the onResult() callback) -
// never from loop() directly, since std::unordered_set isn't safe to
// mutate from two tasks at once. startScanning() runs on the main loop
// task, so instead of clearing the set itself, it just raises a flag;
// shouldSendBle() checks and consumes that flag before touching the set,
// keeping every actual read/write on the one task that owns it.
std::unordered_set<uint64_t> bleSeenThisRun;
static const size_t BLE_SEEN_MAX_ENTRIES = 4000;
volatile bool bleDedupClearPending = false;

static uint64_t macToKey(const uint8_t *mac) {
	uint64_t key = 0;
	for (int i = 0; i < 6; i++) key = (key << 8) | mac[i];
	return key;
}

static bool shouldSendBle(const uint8_t *mac) {
	if (bleDedupClearPending) {
		bleDedupClearPending = false;
		bleSeenThisRun.clear();
	}

	// Phones rotate their BLE address every ~15 minutes, so a long city run can
	// see tens of thousands of "new" MACs - bounded so the set can't eat the
	// heap. Clearing it just means a device may be sent twice in one run.
	if (bleSeenThisRun.size() >= BLE_SEEN_MAX_ENTRIES) bleSeenThisRun.clear();

	uint64_t key = macToKey(mac);
	if (bleSeenThisRun.count(key)) return false; // already sent this one, this run
	bleSeenThisRun.insert(key);
	return true;
}

// Raw manufacturer-data AD structure, hex-encoded so it survives the wire's
// plain-text line protocol - only meaningful to the phone app's own
// AirTag/SmartTag heuristics (fed via wifi_node's wdstream relay), not used
// in the WigleWifi CSV path at all.
static void hexEncode(const uint8_t *data, size_t len, char *out, size_t outSize) {
	static const char *hexDigits = "0123456789ABCDEF";
	size_t n = 0;
	for (size_t i = 0; i < len && n + 2 < outSize; i++) {
		out[n++] = hexDigits[(data[i] >> 4) & 0xF];
		out[n++] = hexDigits[data[i] & 0xF];
	}
	out[n] = '\0';
}

// "MAC,RSSI,NAME,MFG_HEX\n" - wifi_node enriches this with its own GPS
// fix/timestamp before relaying it onward to cyd_node (see
// wifi_node/main.cpp's own header/comments).
static void sendObservation(const uint8_t *mac, int rssi, const std::string &rawName, const std::string &mfgData) {
	char macStr[18];
	snprintf(macStr, sizeof(macStr), "%02X:%02X:%02X:%02X:%02X:%02X",
			 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);

	String name(rawName.c_str());
	name.replace(",", " ");
	name.replace("\n", " ");
	name.replace("\r", " ");

	char mfgHex[64];
	hexEncode(reinterpret_cast<const uint8_t *>(mfgData.data()), mfgData.size(), mfgHex, sizeof(mfgHex));

	char line[160]; // generous margin over the worst case (17-char mac + 4-char rssi + a ~32-char BLE name + 62-char mfg hex)
	snprintf(line, sizeof(line), "%s,%d,%s,%s", macStr, rssi, name.c_str(), mfgHex);
	LinkSerial.println(line);
	observationsSent++;
}

class WardriveScanCallbacks : public NimBLEAdvertisedDeviceCallbacks {
	void onResult(NimBLEAdvertisedDevice *device) override {
		if (!scanningActive) return;
		if (!wifiNodeHasFix || millis() - lastGpsFixLineMs > GPSFIX_STALE_MS) return; // see wifiNodeHasFix

		// getAddress() returns a temporary NimBLEAddress by value, and
		// getNative() points into that temporary's own storage - it's
		// destroyed at the end of this statement, so the pointer must be
		// copied out now, not held onto and used a few lines down (that was
		// a real dangling-pointer bug: reading freed stack memory on every
		// single BLE detection).
		uint8_t mac[6];
		memcpy(mac, device->getAddress().getNative(), 6);

		if (!shouldSendBle(mac)) return; // seen this device recently, skip the duplicate

		flashLed(0, 255, 255); // cyan - only for a genuinely new device this run (resets each run with the dedup set)
		std::string name = device->haveName() ? device->getName() : "";
		std::string mfgData = device->haveManufacturerData() ? device->getManufacturerData() : "";
		sendObservation(mac, device->getRSSI(), name, mfgData);
	}
};

static WardriveScanCallbacks scanCallbacks;

static void startScanning() {
	bleDedupClearPending = true; // consumed by shouldSendBle() on the NimBLE task - see comment above bleSeenThisRun
	pBLEScan->start(0, nullptr, false); // continuous scan
}

static void stopScanning() {
	pBLEScan->stop();
}

void setup() {
	Serial.begin(115200);

	neopixelWrite(RGB_BUILTIN, 0, 0, 0);

	LinkSerial.setRxBufferSize(LINK_BUFFER_BYTES);
	LinkSerial.setTxBufferSize(LINK_BUFFER_BYTES); // sends happen on the NimBLE task - never block it
	LinkSerial.begin(LINK_BAUD, SERIAL_8N1, PIN_LINK_RX, PIN_LINK_TX);

	NimBLEDevice::init("");
	pBLEScan = NimBLEDevice::getScan();
	pBLEScan->setAdvertisedDeviceCallbacks(&scanCallbacks, true);
	pBLEScan->setActiveScan(true);
	// The controller's own duplicate filter reports each address once per scan
	// and never again - so a device first heard before there was a GPS fix
	// could never be logged later in the run. shouldSendBle() does the de-dup
	// instead, and only once a sighting can actually be logged.
	pBLEScan->setDuplicateFilter(false);
	// window == interval - continuous scanning, no gap between listening
	// windows. BLE devices advertise on their own independent schedules
	// (anywhere from ~20ms to several seconds apart), so unlike WiFi's
	// single fixed 100ms beacon interval there's no dwell-time floor to
	// stay above here - 100% radio duty cycle just means never missing a
	// moment where an advertisement could arrive, which is strictly better
	// for "catch everything" with no reliability tradeoff the way
	// shortening WiFi's dwell would have (see wifi_node's own comment).
	pBLEScan->setInterval(100);
	pBLEScan->setWindow(100);
	// Default is 0xFF, which tells NimBLE to keep every unique NimBLEAdvertisedDevice it has ever
	// seen heap-allocated in its own internal results vector for the life of the scan (it's only
	// freed once maxResults==0 makes onResult() erase it right after the callback runs) - this
	// board runs one continuous scan for the whole trip and never calls stop()/clearResults() to
	// release that cache. We already do our own dedup (bleSeenThisRun) and have no use for
	// NimBLE's internal cache at all, so a dense urban drive with thousands of unique BLE MACs
	// would otherwise leak one allocation per MAC until the heap is exhausted - a crash, watchdog
	// reset, or BLE stack hang mid-drive, independent of any phone-side wdstream issue.
	pBLEScan->setMaxResults(0);

	Serial.println("ble_node ready");
}

void loop() {
	if (ledOffAtMs != 0 && millis() >= ledOffAtMs) {
		neopixelWrite(RGB_BUILTIN, 0, 0, 0);
		ledOffAtMs = 0;
		ledPriority = false;
	}

	serviceErrorBlink();

	if (WARDRIVE_DEBUG && millis() - lastHeartbeatMs > HEARTBEAT_MS) {
		lastHeartbeatMs = millis();
		Serial.printf("[heartbeat] up=%lus scanning=%d sent=%lu fix=%d\n",
					  (unsigned long)(millis() / 1000), scanningActive,
					  (unsigned long)observationsSent, wifiNodeHasFix && millis() - lastGpsFixLineMs <= GPSFIX_STALE_MS);
	}

	static String lineBuf; // byte-at-a-time - readStringUntil() would block on a half-arrived line
	while (LinkSerial.available()) {
		char c = (char)LinkSerial.read();
		if (c == '\n') {
			lineBuf.trim();
			if (lineBuf.length() > 0) handleLinkStatusLine(lineBuf);
			lineBuf = "";
		} else if (c != '\r' && lineBuf.length() < 256) {
			lineBuf += c;
		}
	}

	if (scanningActive != wasScanning) {
		wasScanning = scanningActive;
		if (scanningActive) {
			if (!wifiNodeSdOk) {
				// wifi_node has nowhere to save data - refuse to start here
				// too, whether this transition came from a resync or wifi_node
				// itself just caught the same thing, so this board never shows
				// as running while wifi_node has bailed out.
				scanningActive = false;
				wasScanning = false;
				startSdErrorBlink();
				if (WARDRIVE_DEBUG) Serial.println("[start] wifi_node has no SD to save to - refusing to start");
			} else {
				startScanning();
			}
		} else {
			stopScanning();
		}
	}
}
