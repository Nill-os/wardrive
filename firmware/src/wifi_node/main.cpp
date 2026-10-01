// wifi_node: promiscuous 2.4GHz WiFi wardrive sniffer.
// Owns the only GPS module. No SD card and no upload logic of its own
// anymore - cyd_node (a CYD/ESP32-2432S028 board) owns the only microSD
// card, runs the uploader, and shows a live status display. wifi_node
// forwards its own WiFi observations, plus every BLE observation ble_node
// streams to it over a wired UART link, onward to cyd_node over a second
// wired UART (CydLinkSerial), timestamped/geotagged with this board's own
// GPS fix. This board runs no BLE at all - its radio is WiFi-only.
//
// No phone-facing protocol on this board - the phone talks to cyd_node
// (over BLE, or its USB port as a fallback). This board's own Serial is
// debug-print-only.
//
// No physical button on this board (removed rig-wide - cyd_node's
// touchscreen is now the only start/stop/upload control surface). scanning
// state is entirely driven by whatever cyd_node last broadcast over the
// wired link (see handleCydLinkLine()'s SCANSTATE case) - this board just
// relays that onward to ble_node over the wired link, same as before, and
// still asserts its own idle-auto-stop override when it fires (broadcast
// back to cyd_node too, so both sides agree - see the idle-stop block in
// loop() and cyd_node's own resync logic for why that's safe symmetrically
// in both directions).
//
// See ../../README.md for wiring and config.cfg setup (config.cfg now
// lives on cyd_node's SD card, not this board's).

#include <Arduino.h>
#include <WiFi.h>
#include "esp_wifi.h"
#include <TinyGPS++.h>
#include <cstdarg>
#include <unordered_map>
#include <unordered_set>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>

#include "TimeUtils.h"
#include "WardriveEspNow.h"


// No local scanning-intent persistence on this board anymore - it's no
// longer this board's decision to make (see file header). On boot,
// scanningActive just starts false and waits to hear cyd_node's own
// authoritative state over the wired link (broadcast periodically as well as
// on every change, so this catches up within a couple seconds of
// reconnecting even after a power cycle) - safer than resuming a locally-
// stashed intent that might not match what cyd_node actually decided while
// this board was down. The idle-auto-stop override (see loop()) no longer
// persists its decision locally either, for the same reason - it just sets
// scanningActive=false and broadcasts it; cyd_node's own NVS is what
// actually needs to remember "stopped" past a power cycle now.

// Flip to false once the rig is proven out - this is the only source of
// runtime status over serial (button clicks, GPS fix, upload results);
// nothing else in this file prints during normal operation.
static constexpr bool WARDRIVE_DEBUG = true;
static const uint32_t HEARTBEAT_MS = 2000;

// Status batch to cyd_node (EPOCH/SATS/CH, which also doubles as the link
// heartbeat cyd_node's RIG indicator watches) plus a SCANSTATE relay to
// ble_node, so a board that missed a change self-heals within 2s. Never
// gated behind WARDRIVE_DEBUG - this is a correctness mechanism.
static const uint32_t SCAN_SYNC_BROADCAST_MS = 2000;
uint32_t lastScanSyncBroadcastMs = 0;

// ---- Pin assignments (see README for wiring diagram) ----
// Wired link to cyd_node (its CN1 header: IO27 = its RX, IO22 = its TX),
// crossed like the other two links below, plus a shared GND. Back to a wire
// from BLE (2026-09-28): the BLE hop shared this board's one radio with the
// WiFi sniffer's constant channel-hopping and flapped every 10-20s mid-run;
// a UART has no coexistence cost, and freeing cyd_node's BLE radio lets it
// serve the phone instead.
static const uint8_t PIN_CYD_LINK_TX = 13; // -> cyd_node CN1 IO27
static const uint8_t PIN_CYD_LINK_RX = 14; // <- cyd_node CN1 IO22

// Physical wiring has GPS TX/RX landing straight-through (GPS TX->pin 18,
// GPS RX->pin 17) rather than crossed - matched here in firmware instead of
// re-wiring the board. ESP32 RX must be whichever pin GPS's TX physically
// reaches for anything to ever be received.
static const uint8_t PIN_GPS_RX = 18; // ESP32 RX <- GPS TX
static const uint8_t PIN_GPS_TX = 17; // ESP32 TX -> GPS RX (unused, wired for completeness)

// Wired link into ble_node - crossed (this board's RX to ble_node's TX and
// vice versa), same convention as GPS above.
static const uint8_t PIN_BLE_LINK_RX = 8; // <- ble_node TX
static const uint8_t PIN_BLE_LINK_TX = 3; // -> ble_node RX

// While driving, you're only in range of any given AP for a few seconds
// total - a full channel cycle needs to fit inside that window more than
// once to reliably catch it. AP beacons broadcast roughly every 100ms, so
// 150ms dwell still comfortably catches one on every single visit; going
// any lower risks landing between two beacons and missing the AP
// entirely on that pass, which would hurt "catch everything" rather than
// help it - the real speed win is below, not a shorter dwell.
// Overridable at runtime via cyd_node's CFG broadcast (its config.cfg's
// channel_hop_ms) - this is just the value used until the first CFG line
// arrives (or if that link never comes up).
static uint32_t channelHopMs = 150;
// cyd_node also runs its own independent WiFi sniffer now (see its own
// header). Rather than both boards cycling the SAME full 1-11 range
// (whether in phase or offset - an offset alone still means each board
// only revisits a given channel once per full 11-channel lap), the two
// split the band roughly in half, each looping ONLY its own subset -
// this board covers 1-6, cyd_node covers 6-11 (6 shared deliberately: the
// classic non-overlapping trio 1/6/11 carries a disproportionate share of
// real-world APs, so the busiest of the three getting swept by both
// boards is a feature, not waste). Halving the channels-per-lap roughly
// halves each board's full-cycle time too, at the SAME per-channel dwell
// above - nearly doubling how often either board returns to any given
// one of its own channels, which is the actual lever for catching
// briefly-in-range APs faster, without the beacon-miss risk a shorter
// dwell would carry. Together the two boards still cover the complete
// 1-11 range at every instant, same as the phase-offset approach did,
// but with real revisit-rate gains on top now.
// This board has the external antenna, so it dwells on the three
// non-overlapping channels that carry the large majority of real 2.4GHz
// APs (1, 6, 11). With only three to cover it revisits each about every
// 3*channelHopMs (~450ms at the default), fast enough to catch an AP on a
// single drive-by. cyd_node's own sniffer sweeps the full 1-11 range, so the
// less-common channels (2-5, 7-10) are still covered - just by the board
// without the good antenna, where they belong.
// Modular multi-node channel plan. One sniffer node (the default) covers the
// three popular, non-overlapping channels 1/6/11. To add more sniffer nodes,
// flash each with -D NODE_COUNT=<total> -D NODE_INDEX=<0..total-1> (build_flags
// in platformio.ini, or the desktop flasher): the full 2.4 GHz plan 1-13 is
// then split round-robin across the nodes, so every added board just takes its
// share of the channels and revisits them faster. See docs/DESIGN_NOTES.md.
#ifndef NODE_INDEX
#define NODE_INDEX 0
#endif
#ifndef NODE_COUNT
#define NODE_COUNT 1
#endif
static uint8_t WIFI_CHANNELS[13];
static uint8_t WIFI_CHANNEL_COUNT = 0;
static void buildChannelPlan() {
	WIFI_CHANNEL_COUNT = 0;
	if (NODE_COUNT <= 1) {
		static const uint8_t popular[] = {1, 6, 11};
		for (uint8_t i = 0; i < 3; i++) WIFI_CHANNELS[WIFI_CHANNEL_COUNT++] = popular[i];
	} else {
		for (uint8_t ch = 1; ch <= 13; ch++)
			if ((uint8_t)((ch - 1) % NODE_COUNT) == (uint8_t)(NODE_INDEX % NODE_COUNT))
				WIFI_CHANNELS[WIFI_CHANNEL_COUNT++] = ch;
	}
	if (WIFI_CHANNEL_COUNT == 0) WIFI_CHANNELS[WIFI_CHANNEL_COUNT++] = 1; // never empty
}

// Both board-to-board links. 460800 (not 115200) because every forwarded
// sighting is a ~120-byte line: at 115200 that's ~10ms of blocking per line,
// which in a busy area stalled this loop, delayed channel hops and dropped
// sightings. Short jumper wires handle 460800 fine. Must match ble_node and
// cyd_node.
static const uint32_t LINK_BAUD = 460800;
static const size_t LINK_BUFFER_BYTES = 4096; // TX/RX headroom so a burst never blocks the loop

// UART0 is free here: ARDUINO_USB_CDC_ON_BOOT routes Serial to native USB.
HardwareSerial CydLinkSerial(0);
uint32_t cydLinkRxBytes = 0; // raw bytes off the wire, for the heartbeat
HardwareSerial GpsSerial(1);
HardwareSerial BleLinkSerial(2);

TinyGPSPlus gps;

volatile bool scanningActive = false;
bool wasScanning = false;

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
} // 0-100; set from cyd_node's CFG:ledBrightness relay
uint32_t ledOffAtMs = 0;
bool ledPriority = false; // true while a click/upload-status flash is showing

static uint8_t scaleBrightness(uint8_t channel) {
	return (uint16_t)channel * ledBrightnessPct / 100;
}

// Write the status LED. A board with an addressable onboard RGB LED
// (esp32-s3-devkitc-1: RGB_BUILTIN on GPIO48) gets full color. A Seeed XIAO
// ESP32 (C3/S3) has no addressable RGB LED, so fall back to its single builtin
// LED (lit whenever any channel is on, active-low as XIAO builtin LEDs are), or
// to nothing if the board exposes no usable LED. Either way the sniffer still
// runs; only the color feedback is reduced.
static void wardriveLedWrite(uint8_t r, uint8_t g, uint8_t b) {
#if defined(RGB_BUILTIN)
	neopixelWrite(RGB_BUILTIN, r, g, b);
#elif defined(LED_BUILTIN)
	static bool inited = false;
	if (!inited) { pinMode(LED_BUILTIN, OUTPUT); inited = true; }
	digitalWrite(LED_BUILTIN, (r || g || b) ? LOW : HIGH);
#else
	(void)r; (void)g; (void)b;
#endif
}

// priority=true (clicks, upload status) always shows and can't be cut short
// by a capture flash. priority=false (per-packet captures) is skipped
// outright while a priority flash is still active, so a burst of AP
// captures can't mask or extend past a result the user actually needs to
// see, and the LED reliably settles back to off afterward instead of
// looking "stuck on" from continuous capture activity.
static void flashLed(uint8_t r, uint8_t g, uint8_t b, uint32_t durationMs = LED_FLICKER_MS, bool priority = false) {
	if (!priority && ledPriority && millis() < ledOffAtMs) return;
	uint32_t c = remapLedColor(r, g, b);
	r = (c >> 16) & 0xFF; g = (c >> 8) & 0xFF; b = c & 0xFF;
	wardriveLedWrite(scaleBrightness(r), scaleBrightness(g), scaleBrightness(b));
	ledOffAtMs = millis() + durationMs;
	ledPriority = priority;
}

static const uint32_t LED_RESULT_MS = 800; // longer, clearly-visible flash for upload outcome

// Mirrors cyd_node's alternating cyan/purple "upload in progress" pattern,
// but always in the OPPOSITE color from whatever cyd_node is currently
// showing - cyd_node's own upload-blink task broadcasts "BLINKPHASE:0/1"
// over the link on every toggle specifically so this board (and, relayed
// onward, ble_node) can react to that instead of running an independent
// timer, which would drift out of sync almost immediately.
static const uint32_t UPLOAD_BLINK_MS = 200;

// A distinct multi-blink pattern (5 short red flashes, then idle) for "can't
// start - cyd_node has no SD to save to", so it reads unmistakably as an
// error rather than blending in with the single result flashes used
// elsewhere. Built on top of flashLed()/the existing off-timer rather than
// raw neopixelWrite - each on-phase is a normal priority flashLed() call,
// and this just times out the gap before re-triggering the next one.
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

uint32_t lastChannelHopMs = 0;

// This rig is meant to run unattended for weeks, plugged into car power
// that may not be ignition-switched - if scanning gets left on after
// parking, both boards' radios keep drawing current indefinitely, which on
// an always-on 12V circuit is a real car-battery-drain risk, not just a
// theoretical one. If GPS shows no meaningful movement for this long while
// scanning, stop automatically, the same as if the button had been pressed.
static const uint32_t IDLE_TIMEOUT_MS = 30UL * 60UL * 1000UL;
static const double IDLE_MOVEMENT_THRESHOLD_M = 50.0;
double idleAnchorLat = 0.0, idleAnchorLon = 0.0;
bool idleAnchorSet = false;
uint32_t idleAnchorSetMs = 0;
uint32_t lastHeartbeatMs = 0;
uint8_t channelIndex = 0;
uint8_t currentChannel = 1; // real value set by buildChannelPlan() in setup()

// cyd_node is the only board left with SD/upload logic, so wifi_node now
// gates its own scanning start on cyd_node's storage health, exactly the
// same relationship ble_node already has with wifi_node's SDOK broadcast
// today - just one hop further down the chain. Optimistic until the first
// real SDOK line arrives, same reasoning as ble_node's own wifiNodeSdOk.
volatile bool cydSdOk = true;

// AUTH_WPA2_PSK/AUTH_WPA_PSK keep their original numeric values (2, 3) so
// nothing that already depends on those two specific numbers breaks; the
// rest are new. Distinguishing Enterprise (802.1X) from Personal (PSK/SAE)
// matters for a wardriving log because it tells you whether an AP could
// even plausibly be attacked with a captured PSK handshake at all - this
// rig never attempts that regardless, it only reads what's already public
// in the beacon/probe-response.
enum : uint8_t {
	AUTH_OPEN = 0,
	AUTH_WEP = 1,
	AUTH_WPA_PSK = 2,
	AUTH_WPA2_PSK = 3,
	AUTH_WPA2_ENTERPRISE = 4,	  // RSN IE, 802.1X-family AKM (1/3/5)
	AUTH_WPA3_SAE = 5,			  // RSN IE, SAE AKM (8/9/24) - WPA3-Personal
	AUTH_WPA3_ENTERPRISE = 6,	  // RSN IE, Suite-B AKM (11/12) - WPA3-Enterprise 192-bit
	AUTH_OWE = 7,				  // RSN IE, OWE AKM (18) - Enhanced Open
	AUTH_WPA23_TRANSITIONAL = 8, // RSN IE advertises both PSK and SAE AKMs at once
};

struct WifiObservation {
	uint8_t bssid[6];
	char ssid[33];
	uint8_t authMode;
	bool pmfCapable;  // RSN capabilities bit 7 - AP supports 802.11w management frame protection
	bool pmfRequired; // RSN capabilities bit 6 - AP requires it (WPA3 mandates this)
	int8_t rssi;
	uint8_t channel;
};

static QueueHandle_t obsQueue;

static const char *authModeBaseStr(uint8_t mode) {
	switch (mode) {
		case AUTH_WEP: return "[WEP]";
		case AUTH_WPA_PSK: return "[WPA-PSK]";
		case AUTH_WPA2_PSK: return "[WPA2-PSK]";
		case AUTH_WPA2_ENTERPRISE: return "[WPA2-EAP]";
		case AUTH_WPA3_SAE: return "[WPA3-SAE]";
		case AUTH_WPA3_ENTERPRISE: return "[WPA3-EAP]";
		case AUTH_OWE: return "[OWE]";
		case AUTH_WPA23_TRANSITIONAL: return "[WPA2-PSK][WPA3-SAE]";
		default: return "";
	}
}

// WigleWifi's auth column is free-form bracketed tokens (WiGLE and Kismet
// both just display them as-is), so MFPC/MFPR ride along as extra tokens
// rather than needing their own CSV column.
static String authModeStr(uint8_t mode, bool pmfCapable, bool pmfRequired) {
	String s = authModeBaseStr(mode);
	if (pmfRequired) s += "[MFPR]";
	else if (pmfCapable) s += "[MFPC]";
	s += "[ESS]";
	return s;
}

static void cydLinkSendf(const char *fmt, ...) {
	char buf[220]; // generous - the longest line we send (a "W," observation) is well under this
	va_list args;
	va_start(args, fmt);
	vsnprintf(buf, sizeof(buf), fmt, args);
	va_end(args);
	CydLinkSerial.println(buf);
}

// cyd_node geotags its own sniffer's catches with the last position it heard
// from here, so this goes out several times a second (not with the 2s status
// batch) to keep that position fresh at driving speed. Also tells ble_node
// whether there's a fix - see its handling of GPSFIX.
static const uint32_t GPSPOS_BROADCAST_MS = 500;
static bool gpsLive(); // defined with the GPS gap-tolerance helpers below
static bool getLoggablePosition(double &lat, double &lon, double &alt, double &acc);
uint32_t lastGpsPosBroadcastMs = 0;

static void sendGpsPos() {
	lastGpsPosBroadcastMs = millis();
	if (gpsLive()) cydLinkSendf("GPSPOS:1,%.6f,%.6f", gps.location.lat(), gps.location.lng());
	else cydLinkSendf("GPSPOS:0,0,0");
	double lat, lon, alt, acc;
	BleLinkSerial.printf("GPSFIX:%d\n", getLoggablePosition(lat, lon, alt, acc) ? 1 : 0);
}

// cyd_node owns the rig's scan state (it's the one that persists it across
// power loss), so this board never tells it what the state is - it only
// relays cyd_node's word on to ble_node. The one exception is idle
// auto-stop, which goes to cyd_node as its own IDLESTOP event (see loop()).
// Sending our own SCANSTATE back used to race cyd_node's power-loss resume:
// this board boots stopped, and a "SCANSTATE:0" landing just after cyd_node
// resumed could switch the whole rig off again.
static void relayScanStateToBleNode() {
	BleLinkSerial.printf("SCANSTATE:%d\n", scanningActive);
}

static String macToString(const uint8_t *mac) {
	char buf[18];
	snprintf(buf, sizeof(buf), "%02X:%02X:%02X:%02X:%02X:%02X",
			 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
	return String(buf);
}

static String isoTimestamp() {
	char buf[24];
	if (gps.date.isValid() && gps.time.isValid()) {
		snprintf(buf, sizeof(buf), "%04d-%02d-%02d %02d:%02d:%02d",
				 gps.date.year(), gps.date.month(), gps.date.day(),
				 gps.time.hour(), gps.time.minute(), gps.time.second());
	} else {
		snprintf(buf, sizeof(buf), "1970-01-01 00:00:00");
	}
	return String(buf);
}

static int channelToFreqMHz(uint8_t channel) {
	if (channel >= 1 && channel <= 13) return 2407 + channel * 5;
	if (channel == 14) return 2484;
	return 0;
}

// A tunnel, parking garage, or tree cover can drop the GPS fix for a few
// seconds mid-drive. Rather than discarding every capture until the fix
// comes back, fall back to the last known-good position for a short window
// - close enough for wardriving purposes, and far better than losing that
// stretch of road entirely. Falls through to "no usable position" (false)
// once the gap runs longer than that, rather than reporting a stale fix as
// if it were current.
static const uint32_t GPS_GAP_TOLERANCE_MS = 15000;
double lastKnownLat = 0.0, lastKnownLon = 0.0, lastKnownAlt = 0.0, lastKnownAcc = 10.0;
uint32_t lastKnownFixMs = 0;

// TinyGPS++'s location.isValid() only means "a fix was seen at some point" -
// it stays true forever after the first one, even once the module has lost
// the sky. A live fix is one that's still being refreshed (the NEO-6M sends
// one a second), so staleness is judged by age instead.
static const uint32_t GPS_LIVE_MAX_AGE_MS = 3000;
static bool gpsLive() {
	return gps.location.isValid() && gps.location.age() < GPS_LIVE_MAX_AGE_MS;
}

static void rememberGpsFix() {
	if (!gpsLive()) return;
	lastKnownLat = gps.location.lat();
	lastKnownLon = gps.location.lng();
	lastKnownAlt = gps.altitude.isValid() ? gps.altitude.meters() : 0.0;
	lastKnownAcc = gps.hdop.isValid() ? gps.hdop.hdop() * 5.0 : 10.0;
	lastKnownFixMs = millis();
}

static bool getLoggablePosition(double &lat, double &lon, double &alt, double &acc) {
	if (gpsLive()) {
		lat = gps.location.lat();
		lon = gps.location.lng();
		alt = gps.altitude.isValid() ? gps.altitude.meters() : 0.0;
		acc = gps.hdop.isValid() ? gps.hdop.hdop() * 5.0 : 10.0;
		return true;
	}
	if (lastKnownFixMs != 0 && millis() - lastKnownFixMs < GPS_GAP_TOLERANCE_MS) {
		lat = lastKnownLat;
		lon = lastKnownLon;
		alt = lastKnownAlt;
		acc = lastKnownAcc + 20.0; // inflate the accuracy estimate - this position is stale, not live
		return true;
	}
	return false;
}

// Per-run dedup: as long as you haven't moved, the same AP never gets
// logged twice in one run, no matter how long you sit there - there's no
// time-based re-trigger here on purpose, only distance. Move far enough
// away and come back later in the same run (looped back a block away,
// drove past again an hour later) and it logs fresh from the new fix.
// Entries are only forgotten after a long stretch of real time
// (AP_DEDUP_FORGET_AFTER_MS) purely to bound memory on a very long session
// - that's a memory-management detail, not a "duplicates are OK after a
// while" policy.
static const double AP_DEDUP_MOVEMENT_THRESHOLD_M = 40.0;
static const uint32_t AP_NOFIX_RELOG_MS = 5000; // no-GPS re-forward interval, keeps the phone's live stream alive without flooding it
static const uint32_t AP_DEDUP_FORGET_AFTER_MS = 2UL * 3600UL * 1000UL; // 2 hours
static const uint32_t AP_DEDUP_PRUNE_SWEEP_MS = 300000; // how often to check for entries to forget
static const size_t AP_DEDUP_MAX_ENTRIES = 3000;
static const double AP_DEDUP_EVICT_BEYOND_M = 1000.0;
static const size_t AP_LED_SEEN_MAX_ENTRIES = 3000;

struct ApDedupEntry {
	uint32_t lastLoggedMs;
	double lat;
	double lon;
	bool named; // true once a row WITH an SSID has gone out for this BSSID
};
std::unordered_map<uint64_t, ApDedupEntry> apDedupState;
uint32_t lastApPruneMs = 0;

// The LED's "new this run" signal is deliberately independent of
// apDedupState/shouldLogAp() below - those require a GPS position (no
// position means nothing to log), but the LED should still flash for a
// newly-seen BSSID even with no GPS fix at all (e.g. bench testing indoors),
// since "have I seen this AP yet this run" doesn't actually depend on
// knowing where it is. Cleared alongside apDedupState in startScanning().
std::unordered_set<uint64_t> apSeenThisRunForLed;

static uint64_t macToKey(const uint8_t *mac) {
	uint64_t key = 0;
	for (int i = 0; i < 6; i++) key = (key << 8) | mac[i];
	return key;
}

// Only ever called from loop() (the main task) when dequeuing observations,
// never from wifiSnifferCallback - keeps the ISR-ish WiFi driver callback
// itself minimal, and avoids touching this map from two tasks at once.
//
// named = this sighting carries a non-empty SSID. A hidden (cloaked) network
// beacons a blank SSID but reveals its name in probe responses, so an AP first
// logged blank is let through exactly once more when its name turns up -
// otherwise the movement dedup below keeps the blank row and the name is lost.
static bool shouldLogAp(const uint8_t *mac, double lat, double lon, bool named) {
	uint32_t now = millis();

	if (now - lastApPruneMs > AP_DEDUP_PRUNE_SWEEP_MS) {
		lastApPruneMs = now;
		for (auto it = apDedupState.begin(); it != apDedupState.end();) {
			if (now - it->second.lastLoggedMs > AP_DEDUP_FORGET_AFTER_MS) it = apDedupState.erase(it);
			else ++it;
		}
	}

	// A dense city drive sees many thousands of distinct BSSIDs, and each
	// entry costs ~60 bytes of a heap this board also needs for WiFi - so
	// past a cap, forget everything logged far from here (a duplicate is only
	// possible within AP_DEDUP_MOVEMENT_THRESHOLD_M of the old spot anyway),
	// and as a last resort forget everything.
	if (apDedupState.size() >= AP_DEDUP_MAX_ENTRIES) {
		for (auto it = apDedupState.begin(); it != apDedupState.end();) {
			if (TinyGPSPlus::distanceBetween(lat, lon, it->second.lat, it->second.lon) > AP_DEDUP_EVICT_BEYOND_M) it = apDedupState.erase(it);
			else ++it;
		}
		if (apDedupState.size() >= AP_DEDUP_MAX_ENTRIES * 3 / 4) apDedupState.clear();
		if (WARDRIVE_DEBUG) Serial.printf("[dedup] pruned to %u entries\n", (unsigned)apDedupState.size());
	}

	uint64_t key = macToKey(mac);
	auto it = apDedupState.find(key);
	bool wasNamed = false;
	if (it != apDedupState.end()) {
		wasNamed = it->second.named;
		if (named && !wasNamed) {
			apDedupState[key] = {now, lat, lon, true}; // name just revealed - log it
			return true;
		}
		if (lat == 0.0 && lon == 0.0) {
			// No GPS fix: position never changes, so movement-based de-dup would
			// suppress this BSSID forever after the first forward and the phone's
			// live stream would go dead. Re-forward on a short timer instead so
			// the live tools stay populated at a stationary desk (the phone
			// de-dups by MAC, so this just refreshes the entry's RSSI).
			if (now - it->second.lastLoggedMs < AP_NOFIX_RELOG_MS) return false;
		} else {
			double movedM = TinyGPSPlus::distanceBetween(lat, lon, it->second.lat, it->second.lon);
			if (movedM < AP_DEDUP_MOVEMENT_THRESHOLD_M) return false; // haven't moved - still the same sighting
		}
	}

	apDedupState[key] = {now, lat, lon, named || wasNamed};
	return true;
}

// Parsed out of the RSN information element (tag 48) in a beacon/probe
// response - this is the same public capability advertisement any client
// reads before it decides how to associate, not anything requiring a
// handshake or key material.
struct RsnInfo {
	bool akmEnterprise = false; // 802.1X-family AKM present (1, 3, 5)
	bool akmPsk = false;		 // PSK-family AKM present (2, 4, 6)
	bool akmSae = false;		 // SAE AKM present (8, 9, 24) - WPA3-Personal
	bool akmSuiteB = false;	 // Suite-B AKM present (11, 12) - WPA3-Enterprise 192-bit
	bool akmOwe = false;		 // OWE AKM present (18) - Enhanced Open
	bool mfpCapable = false;
	bool mfpRequired = false;
};

static void classifyAkmSuite(uint8_t suiteType, RsnInfo &info) {
	switch (suiteType) {
		case 1: case 3: case 5: info.akmEnterprise = true; break;
		case 2: case 4: case 6: info.akmPsk = true; break;
		case 8: case 9: case 24: info.akmSae = true; break;
		case 11: case 12: info.akmSuiteB = true; break;
		case 18: info.akmOwe = true; break;
		default: break; // vendor-specific or not-yet-assigned AKM - ignored, not misclassified
	}
}

// data/len is the RSN IE's own body (i.e. payload+dataStart, tagLen from the
// caller's tag-walk), not the whole frame. Every step re-checks idx against
// len before reading, so a truncated or malformed IE (or one carrying a
// suspiciously large pairwise/AKM count) just yields whatever was parsed so
// far instead of reading past the IE's own bounds.
static RsnInfo parseRsnIe(const uint8_t *data, uint8_t len) {
	RsnInfo info;
	int idx = 2; // skip the 2-byte version field
	if (idx + 4 > len) return info; // no group cipher suite present
	idx += 4;						 // group cipher suite (OUI + type)
	if (idx + 2 > len) return info;
	uint16_t pairwiseCount = data[idx] | (data[idx + 1] << 8);
	idx += 2 + 4 * pairwiseCount; // skip the pairwise cipher suite list
	if (idx + 2 > len) return info;
	uint16_t akmCount = data[idx] | (data[idx + 1] << 8);
	idx += 2;
	for (uint16_t i = 0; i < akmCount && idx + 4 <= len; i++) {
		classifyAkmSuite(data[idx + 3], info); // OUI (3 bytes) + suite type (1 byte)
		idx += 4;
	}
	if (idx + 2 <= len) {
		uint16_t rsnCap = data[idx] | (data[idx + 1] << 8);
		info.mfpRequired = (rsnCap >> 6) & 1;
		info.mfpCapable = (rsnCap >> 7) & 1;
	}
	return info;
}

static uint8_t classifyAuthMode(const RsnInfo &rsn, bool hasWpaVendor, bool privacy) {
	if (rsn.akmOwe) return AUTH_OWE;
	if (rsn.akmSae && rsn.akmPsk) return AUTH_WPA23_TRANSITIONAL;
	if (rsn.akmSae) return AUTH_WPA3_SAE;
	if (rsn.akmSuiteB) return AUTH_WPA3_ENTERPRISE;
	if (rsn.akmEnterprise) return AUTH_WPA2_ENTERPRISE;
	if (rsn.akmPsk) return AUTH_WPA2_PSK;
	if (hasWpaVendor) return AUTH_WPA_PSK;
	if (privacy) return AUTH_WEP;
	return AUTH_OPEN;
}

// Every AP beacons ~10 times a second, and without this each one was queued -
// in a busy area the 64-slot queue filled with repeats and genuinely new APs
// were dropped at xQueueSend(). A tiny direct-mapped cache drops a BSSID heard
// in the last SNIFF_REPEAT_MS right here in the callback. Collisions only
// ever let an extra frame through, never drop a new AP.
static const uint32_t SNIFF_REPEAT_MS = 500;
static const size_t SNIFF_CACHE_SLOTS = 512;
struct SniffCacheSlot {
	uint32_t tag;
	uint32_t ms;
};
static SniffCacheSlot sniffCache[SNIFF_CACHE_SLOTS];
volatile uint32_t sniffFrames = 0, sniffQueued = 0, sniffDropped = 0; // for the heartbeat

static inline bool sniffSeenRecently(const uint8_t *bssid) {
	uint32_t tag = (bssid[2] << 24 | bssid[3] << 16 | bssid[4] << 8 | bssid[5]) ^ (bssid[0] << 8 | bssid[1]);
	SniffCacheSlot &slot = sniffCache[(tag ^ (tag >> 9)) % SNIFF_CACHE_SLOTS];
	uint32_t now = millis();
	if (slot.tag == tag && now - slot.ms < SNIFF_REPEAT_MS) return true;
	slot.tag = tag;
	slot.ms = now;
	return false;
}

void IRAM_ATTR wifiSnifferCallback(void *buf, wifi_promiscuous_pkt_type_t type) {
	if (type != WIFI_PKT_MGMT) return;

	wifi_promiscuous_pkt_t *pkt = (wifi_promiscuous_pkt_t *)buf;
	const uint8_t *payload = pkt->payload;
	int len = pkt->rx_ctrl.sig_len;
	if (len < 36) return;

	uint8_t frameType = (payload[0] >> 2) & 0x3;
	uint8_t frameSubtype = (payload[0] >> 4) & 0xF;
	if (frameType != 0) return; // management frames only

	if (frameSubtype != 8 && frameSubtype != 5) return; // beacon(8) / probe response(5) only

	sniffFrames++;
	if (sniffSeenRecently(payload + 16)) return;

	WifiObservation obs = {};
	memcpy(obs.bssid, payload + 16, 6); // addr3 = BSSID
	obs.rssi = pkt->rx_ctrl.rssi;
	obs.channel = pkt->rx_ctrl.channel;

	uint16_t capInfo = payload[24 + 8] | (payload[24 + 9] << 8);
	bool privacy = capInfo & 0x0010;

	int idx = 24 + 12; // MAC header (24) + timestamp/interval/capinfo (12)
	bool hasWpaVendor = false;
	RsnInfo rsn;
	obs.ssid[0] = '\0';

	while (idx + 2 <= len) {
		uint8_t tagNum = payload[idx];
		uint8_t tagLen = payload[idx + 1];
		int dataStart = idx + 2;
		if (dataStart + tagLen > len) break;

		if (tagNum == 0) {
			int copyLen = tagLen < 32 ? tagLen : 32;
			memcpy(obs.ssid, payload + dataStart, copyLen);
			obs.ssid[copyLen] = '\0';
		} else if (tagNum == 48) {
			rsn = parseRsnIe(payload + dataStart, tagLen);
		} else if (tagNum == 221 && tagLen >= 4 &&
				   payload[dataStart] == 0x00 && payload[dataStart + 1] == 0x50 &&
				   payload[dataStart + 2] == 0xF2 && payload[dataStart + 3] == 0x01) {
			hasWpaVendor = true;
		}

		idx = dataStart + tagLen;
	}

	obs.authMode = classifyAuthMode(rsn, hasWpaVendor, privacy);
	obs.pmfCapable = rsn.mfpCapable;
	obs.pmfRequired = rsn.mfpRequired;

	if (xQueueSend(obsQueue, &obs, 0) == pdTRUE) sniffQueued++;
	else sniffDropped++;
}

static void startScanning() {
	apDedupState.clear(); // fresh run, fresh dedup - duplicates across separate runs are fine, never within one
	apSeenThisRunForLed.clear();
	esp_wifi_set_promiscuous(true);
	channelIndex = 0;
	currentChannel = WIFI_CHANNELS[0];
	esp_wifi_set_channel(currentChannel, WIFI_SECOND_CHAN_NONE);
	lastChannelHopMs = millis();
}

static void stopScanning() {
	esp_wifi_set_promiscuous(false);
}

// ble_node sends one line per BLE observation: "MAC,RSSI,NAME,MFG_HEX\n"
// (MFG_HEX is the raw manufacturer-data AD structure - only meaningful to
// the phone app's own AirTag/SmartTag heuristics, not used in the WigleWifi
// CSV path - passed through unchanged as the "B," line's trailing field so
// cyd_node's own phone-facing wdstream mirror can still use it, even though
// this board no longer talks to the phone at all itself). Enriched with
// this board's own GPS fix/timestamp, exactly like the "W," WiFi path, and
// relayed onward to cyd_node as a fully-formed "B," line - ble_node has no
// GPS or clock of its own, so this is the only place that enrichment can
// happen.
static void handleBleLinkLine(const String &line) {
	int c1 = line.indexOf(',');
	int c2 = line.indexOf(',', c1 + 1);
	int c3 = line.indexOf(',', c2 + 1);
	if (c1 < 0 || c2 < 0 || c3 < 0) return; // malformed line - drop it rather than log garbage

	String mac = line.substring(0, c1);
	int rssi = line.substring(c1 + 1, c2).toInt();
	String name = line.substring(c2 + 1, c3);
	String mfgHex = line.substring(c3 + 1);


	double lat, lon, alt, acc;
	// No GPS fix: forward at 0,0 anyway so the phone's live tools (Live BLE,
	// detection, fox-hunt, antenna check) still see it. cyd_node keeps 0,0
	// sightings out of the SD log/upload, so nothing location-less is wardriven.
	if (!getLoggablePosition(lat, lon, alt, acc)) { lat = lon = alt = acc = 0.0; }

	cydLinkSendf("B,%s,%s,%s,%d,%.6f,%.6f,%.1f,%.1f,%s",
				 mac.c_str(), name.c_str(), isoTimestamp().c_str(), rssi, lat, lon, alt, acc, mfgHex.c_str());
}

// cyd_node relays its upload progress, storage health, and its loaded
// config's channel_hop_ms here so this board's LED and channel-hop timing
// stay in step, even though cyd_node is the only one with SD/config/upload
// logic now.
static void handleCydLinkLine(const String &line) {
	if (WARDRIVE_DEBUG) Serial.printf("[cyd-link] rx: %s\n", line.c_str());
	if (line == "START") {
		// No LED action here - cyd_node's blink task sends its first
		// BLINKPHASE line within a blink interval anyway.
	} else if (line == "OK") {
		flashLed(0, 255, 0, LED_RESULT_MS, true);
		BleLinkSerial.println("OK"); // relay onward so ble_node's LED matches too
	} else if (line == "FAIL") {
		flashLed(255, 0, 0, LED_RESULT_MS, true);
		BleLinkSerial.println("FAIL");
	} else if (line.startsWith("BLINKPHASE:")) {
		bool cydCyan = line.substring(11).toInt() == 0;
		if (cydCyan) flashLed(255, 0, 255, UPLOAD_BLINK_MS, true); // cyd_node is cyan - show purple
		else flashLed(0, 255, 255, UPLOAD_BLINK_MS, true);		 // cyd_node is purple - show cyan
		BleLinkSerial.println(line); // ble_node picks its own opposite color from this same line
	} else if (line == "LOWSTORAGE") {
		flashLed(255, 255, 255, LED_FLICKER_MS, true); // white - cyd_node's SD card is running low
		BleLinkSerial.println("LOWSTORAGE");
	} else if (line.startsWith("SDOK:")) {
		bool newVal = line.substring(5).toInt() != 0;
		if (WARDRIVE_DEBUG && newVal != cydSdOk) {
			Serial.printf("[cyd-link] cydSdOk %d -> %d\n", cydSdOk, newVal);
		}
		cydSdOk = newVal;
		BleLinkSerial.printf("SDOK:%d\n", cydSdOk ? 1 : 0); // relay onward - ble_node's gate is really "can the chain save data", which now means cyd_node's SD
	} else if (line.startsWith("CFG:channelHopMs=")) {
		uint32_t v = line.substring(18).toInt();
		if (v > 0) channelHopMs = v;
	} else if (line.startsWith("CFG:ledBrightness=")) {
		int v = line.substring(18).toInt();
		ledBrightnessPct = v < 0 ? 0 : v > 100 ? 100 : v;
		BleLinkSerial.printf("CFG:ledBrightness=%u\n", ledBrightnessPct); // relay onward to ble_node
		if (WARDRIVE_DEBUG) Serial.printf("[cfg] LED brightness -> %u%%, relayed to ble_node\n", ledBrightnessPct);
	} else if (line.startsWith("CFG:ledColors=")) {
		// ap,ble,ok,fail as hex, comma-separated
		String v = line.substring(14);
		int c1 = v.indexOf(','), c2 = v.indexOf(',', c1 + 1), c3 = v.indexOf(',', c2 + 1);
		if (c1 > 0 && c2 > 0 && c3 > 0) {
			ledColorAp = strtoul(v.substring(0, c1).c_str(), nullptr, 16);
			ledColorBle = strtoul(v.substring(c1 + 1, c2).c_str(), nullptr, 16);
			ledColorOk = strtoul(v.substring(c2 + 1, c3).c_str(), nullptr, 16);
			ledColorFail = strtoul(v.substring(c3 + 1).c_str(), nullptr, 16);
		}
		BleLinkSerial.println(line); // relay onward to ble_node
		if (WARDRIVE_DEBUG) Serial.printf("[cfg] LED colors ap=%06lX relayed\n", (unsigned long)ledColorAp);
	} else if (line.startsWith("SCANSTATE:")) {
		// cyd_node's touchscreen (and its own phone-facing wdstream link) is
		// the primary control surface now that the physical button is gone -
		// this adopts whatever it says and relays it onward to ble_node, the
		// same as a local change (this board's own idle-auto-stop) already
		// does via relayScanStateToBleNode(). Comparing first avoids re-relaying
		// (and re-flashing the LED for) a line that just confirms what this
		// board already has - only a genuine change does either.
		// Never echoed back to cyd_node - see relayScanStateToBleNode().
		bool v = line.substring(10).toInt() != 0;
		if (v != scanningActive) {
			scanningActive = v;
			flashLed(0, 255, 0, LED_FLICKER_MS, true);
			relayScanStateToBleNode();
			if (WARDRIVE_DEBUG) Serial.printf("[cyd-link] resynced to scanning=%d from cyd_node\n", scanningActive);
		}
	}
}

// GGA sentences seen, for the heartbeat - about 1/s from a healthy module.
TinyGPSCustom ggaFixQuality(gps, "GPGGA", 6);
uint32_t ggaCount = 0;

#if defined(WARDRIVE_ESPNOW)
// This board is the aggregator when it's node 0, otherwise a wireless satellite.
static const bool ESPNOW_IS_AGGREGATOR = (NODE_INDEX == 0);

// Sightings arrive in the ESP-NOW receive callback (kept short); they're parked
// in this ring and drained in loop() where touching the CYD link and GPS is safe.
static volatile int espnowHead = 0, espnowTail = 0;
static EspNowSighting espnowRing[32];

static void onEspNowSighting(const EspNowSighting &s) {
	int next = (espnowHead + 1) % 32;
	if (next == espnowTail) return; // ring full - drop rather than block the WiFi task
	espnowRing[espnowHead] = s;
	espnowHead = next;
}

static void drainEspNow() {
	while (espnowTail != espnowHead) {
		EspNowSighting s = espnowRing[espnowTail];
		espnowTail = (espnowTail + 1) % 32;
		s.name[sizeof(s.name) - 1] = '\0';
		if (s.type == ESPNOW_TYPE_BLE) {
			// Rebuild the same line a wired ble_node sends and reuse the geotag path.
			static const char *hexDigits = "0123456789ABCDEF";
			char mfgHex[2 * sizeof(s.mfg) + 1];
			size_t n = 0;
			for (uint8_t i = 0; i < s.mfgLen && i < sizeof(s.mfg); i++) {
				mfgHex[n++] = hexDigits[(s.mfg[i] >> 4) & 0xF];
				mfgHex[n++] = hexDigits[s.mfg[i] & 0xF];
			}
			mfgHex[n] = '\0';
			String line = macToString(s.mac) + "," + String((int)s.rssi) + "," + String(s.name) + "," + String(mfgHex);
			handleBleLinkLine(line);
		} else { // ESPNOW_TYPE_WIFI
			double lat, lon, alt, acc;
			if (!getLoggablePosition(lat, lon, alt, acc)) { lat = lon = alt = acc = 0.0; } // forward location-less for live tools; cyd_node keeps 0,0 off the SD log
			if (!shouldLogAp(s.mac, lat, lon, s.name[0] != '\0')) continue;
			String ssid(s.name);
			ssid.replace(",", " ");
			cydLinkSendf("W,%s,%s,%s,%s,%u,%d,%d,%.6f,%.6f,%.1f,%.1f",
						 macToString(s.mac).c_str(), ssid.c_str(), s.auth,
						 isoTimestamp().c_str(), (unsigned)s.channel, channelToFreqMHz(s.channel),
						 (int)s.rssi, lat, lon, alt, acc);
		}
	}
}
#endif // WARDRIVE_ESPNOW

void setup() {
	Serial.begin(115200);
	buildChannelPlan(); // this node's share of the WiFi channels (see NODE_INDEX/NODE_COUNT)
	currentChannel = WIFI_CHANNELS[0];

	wardriveLedWrite(0, 0, 0);

	// Plain factory default: 9600 baud, standard NMEA at 1 fix/second. Sending UBX config
	// commands here was tried and made it worse - it left the module silent or overran the
	// 9600 line. If the GPS gets power but never a fix, it's the antenna/sky or the TX wire,
	// not this - watch gpsChars in the heartbeat (climbing = data arriving) and the module's
	// own fix LED (blinks only once it has a position).
	GpsSerial.begin(9600, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);
	BleLinkSerial.setRxBufferSize(LINK_BUFFER_BYTES);
	BleLinkSerial.setTxBufferSize(LINK_BUFFER_BYTES);
	BleLinkSerial.begin(LINK_BAUD, SERIAL_8N1, PIN_BLE_LINK_RX, PIN_BLE_LINK_TX);
	CydLinkSerial.setRxBufferSize(LINK_BUFFER_BYTES);
	CydLinkSerial.setTxBufferSize(LINK_BUFFER_BYTES);
	CydLinkSerial.begin(LINK_BAUD, SERIAL_8N1, PIN_CYD_LINK_RX, PIN_CYD_LINK_TX);

	obsQueue = xQueueCreate(128, sizeof(WifiObservation));

	WiFi.mode(WIFI_MODE_STA);
	WiFi.disconnect();
	esp_wifi_set_ps(WIFI_PS_NONE); // radio always listening - no modem-sleep gaps
	esp_wifi_set_promiscuous_rx_cb(&wifiSnifferCallback);
	// wifiSnifferCallback() already throws away everything but management
	// frames in software (type != WIFI_PKT_MGMT), but without this, the
	// driver hands it EVERY frame type first - data and control traffic
	// included, which in a dense RF environment (a real wardrive target) can
	// vastly outnumber the beacons/probe-responses actually being kept. This
	// filters at the driver level instead, so the callback only fires for
	// what it was ever going to use - less ISR/callback overhead per unit
	// time on the same radio that also has to hit every channel within its
	// dwell window, which is exactly the resource this rig is trying to
	// spend entirely on catching APs (found during the 2026-09-27 speed
	// optimization pass).
	wifi_promiscuous_filter_t promFilter = {.filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT};
	esp_wifi_set_promiscuous_filter(&promFilter);

#if defined(WARDRIVE_ESPNOW)
	// Node 0 aggregates the wireless nodes; higher indices are satellites that
	// broadcast to it. See docs/SCALING.md. All of this is compiled out unless
	// the rig is built with -D WARDRIVE_ESPNOW.
	if (ESPNOW_IS_AGGREGATOR) {
		if (espnowBeginReceiver(ESPNOW_CHANNEL, onEspNowSighting, /*bringUpWifi=*/false))
			Serial.printf("[espnow] aggregator receiving on channel %d\n", ESPNOW_CHANNEL);
		else
			Serial.println("[espnow] receiver init FAILED");
	} else {
		if (espnowBeginSender(ESPNOW_CHANNEL, /*bringUpWifi=*/false))
			Serial.printf("[espnow] WiFi satellite %d ready on channel %d\n", (int)NODE_INDEX, ESPNOW_CHANNEL);
		else
			Serial.println("[espnow] sender init FAILED");
	}
#endif

	// scanningActive starts false and stays that way until cyd_node's own
	// authoritative state arrives over the wired link (see handleCydLinkLine())
	// - no local button/NVS-driven resume anymore, see this file's header.
	Serial.println("wifi_node ready");
	// Identity line the desktop app reads to recognize this board (role + which
	// node it is). Emitted at boot and again on a timer below, always - not
	// gated by WARDRIVE_DEBUG - so "Detect boards" works even in a quiet build.
	Serial.printf("WD:ID role=wifi idx=%d n=%d\n", (int)NODE_INDEX, (int)NODE_COUNT);
}

void loop() {
	if (ledOffAtMs != 0 && millis() >= ledOffAtMs) {
		wardriveLedWrite(0, 0, 0);
		ledOffAtMs = 0;
		ledPriority = false;
	}

	serviceErrorBlink();

#if defined(WARDRIVE_ESPNOW)
	if (ESPNOW_IS_AGGREGATOR) drainEspNow(); // forward sightings received from satellites
#endif

	static uint32_t lastIdMs = 0;
	if (millis() - lastIdMs > 2000) {
		lastIdMs = millis();
		Serial.printf("WD:ID role=wifi idx=%d n=%d\n", (int)NODE_INDEX, (int)NODE_COUNT);
	}

	// Re-assert the scan state to ble_node every couple seconds. ble_node has no
	// control of its own and self-heals purely from this broadcast (see its
	// SCANSTATE handler) - relaying only on change let the two drift out of sync
	// (ble_node stuck stopped while the rest of the rig scans), so ble_node never
	// scanned and RIG BT stayed 0. This is the periodic broadcast its comment
	// always assumed existed.
	static uint32_t lastScanStateRelayMs = 0;
	if (millis() - lastScanStateRelayMs > 2000) {
		lastScanStateRelayMs = millis();
		relayScanStateToBleNode();
	}

	if (WARDRIVE_DEBUG && millis() - lastHeartbeatMs > HEARTBEAT_MS) {
		lastHeartbeatMs = millis();
		Serial.printf("[heartbeat] up=%lus scanning=%d cydSdOk=%d gpsFix=%d lat=%.6f lon=%.6f sats=%d "
					  "gpsChars=%u gpsSentWithFix=%u gpsFailedCk=%u gpsPassedCk=%u cydrx=%lu gga=%lu frames=%lu queued=%lu qdrop=%lu\n",
					  (unsigned long)(millis() / 1000), scanningActive, cydSdOk,
					  gpsLive(),
					  gpsLive() ? gps.location.lat() : 0.0,
					  gpsLive() ? gps.location.lng() : 0.0,
					  gps.satellites.isValid() ? gps.satellites.value() : -1,
					  (unsigned)gps.charsProcessed(), (unsigned)gps.sentencesWithFix(),
					  (unsigned)gps.failedChecksum(), (unsigned)gps.passedChecksum(), (unsigned long)cydLinkRxBytes,
					  (unsigned long)ggaCount, (unsigned long)sniffFrames, (unsigned long)sniffQueued, (unsigned long)sniffDropped);
	}

	if (millis() - lastScanSyncBroadcastMs > SCAN_SYNC_BROADCAST_MS) {
		lastScanSyncBroadcastMs = millis();
		relayScanStateToBleNode();

		// cyd_node has no GPS of its own - these keep its upload
		// rate-limiter/cleanup (EPOCH) and dock-mode home geofence
		// (GPSPOS) working without needing its own fix.
		// Only with a live fix: a GPS module with no fix still reports a date and time that
		// TinyGPS calls "valid" but that can be days off (seen: 9 days ahead), which made the
		// CYD's "last upload" age nonsense. 0 = "no trustworthy time right now".
		cydLinkSendf("EPOCH:%lu", (unsigned long)(gpsLive() ? gpsEpoch(gps) : 0));
		sendGpsPos();
		// Satellite count - cyd_node has no GPS of its own to derive this
		// from either, and it's a genuinely useful "collect everything
		// possible" data point (fix quality, not just fix/no-fix) - shown
		// on cyd_node's own CFG tab.
		cydLinkSendf("SATS:%d", gps.satellites.isValid() ? gps.satellites.value() : -1);

		// For cyd_node's status bar - a snapshot every 2s is plenty (actual
		// hopping happens every channelHopMs, far too fast to be readable
		// on a status display anyway).
		cydLinkSendf("CH:%u", (unsigned)currentChannel);
	}

	while (GpsSerial.available()) {
		gps.encode(GpsSerial.read());
		if (ggaFixQuality.isUpdated()) {
			ggaFixQuality.value(); // reading it clears isUpdated()
			ggaCount++;
		}
	}
	rememberGpsFix();
	if (millis() - lastGpsPosBroadcastMs > GPSPOS_BROADCAST_MS) sendGpsPos();

	// Byte-at-a-time into a line buffer - readStringUntil() blocks waiting for
	// the rest of a half-arrived line, stalling the channel hop.
	static String cydLineBuf, bleLineBuf;
	while (CydLinkSerial.available()) {
		char c = (char)CydLinkSerial.read();
		cydLinkRxBytes++;
		if (c == '\n') {
			cydLineBuf.trim();
			if (cydLineBuf.length() > 0) handleCydLinkLine(cydLineBuf);
			cydLineBuf = "";
		} else if (c != '\r' && cydLineBuf.length() < 256) {
			cydLineBuf += c;
		}
	}
	while (BleLinkSerial.available()) {
		char c = (char)BleLinkSerial.read();
		if (c == '\n') {
			bleLineBuf.trim();
			if (bleLineBuf.length() > 0) handleBleLinkLine(bleLineBuf);
			bleLineBuf = "";
		} else if (c != '\r' && bleLineBuf.length() < 256) {
			bleLineBuf += c;
		}
	}

	if (scanningActive != wasScanning) {
		wasScanning = scanningActive;
		if (scanningActive) {
			if (!cydSdOk) {
				// cyd_node has nowhere to save data - refuse to start here
				// too, whether this transition came from cyd_node's own
				// touchscreen, a phone command, or a resync, so this board
				// never shows as running while cyd_node has bailed out. In
				// practice cyd_node's own startScanning() check already
				// catches this and self-corrects its broadcast moments
				// later, but this stays as a belt-and-suspenders local check
				// against that same race.
				scanningActive = false;
				wasScanning = false;
				startSdErrorBlink();
				if (WARDRIVE_DEBUG) Serial.println("[start] cyd_node has no SD to save to - refusing to start");
			} else {
				idleAnchorSet = false; // fresh start - don't judge movement against wherever we were last time
				startScanning();
			}
		} else {
			stopScanning();
		}
	}

	if (scanningActive) {
		if (millis() - lastChannelHopMs > channelHopMs) {
			lastChannelHopMs = millis();
			channelIndex = (channelIndex + 1) % WIFI_CHANNEL_COUNT;
			currentChannel = WIFI_CHANNELS[channelIndex];
			esp_wifi_set_channel(currentChannel, WIFI_SECOND_CHAN_NONE);
		}

		WifiObservation obs;
		while (xQueueReceive(obsQueue, &obs, 0) == pdTRUE) {
			if (apSeenThisRunForLed.size() >= AP_LED_SEEN_MAX_ENTRIES) apSeenThisRunForLed.clear(); // bounded - worst case an old AP flashes "new" again
			if (apSeenThisRunForLed.insert(macToKey(obs.bssid)).second) {
				flashLed(255, 0, 255); // purple - only for a genuinely new AP this run, independent of whether it can be logged
			}

#if defined(WARDRIVE_ESPNOW)
			if (!ESPNOW_IS_AGGREGATOR) {
				// A satellite has no GPS of its own - broadcast the raw sighting to
				// the aggregator, which geotags and de-duplicates it there.
				EspNowSighting s;
				memset(&s, 0, sizeof(s));
				s.magic = ESPNOW_MAGIC;
				s.version = ESPNOW_PROTO_VERSION;
				s.type = ESPNOW_TYPE_WIFI;
				s.nodeId = (uint8_t)NODE_INDEX;
				memcpy(s.mac, obs.bssid, 6);
				s.rssi = (int8_t)obs.rssi;
				s.channel = obs.channel;
				strncpy(s.name, obs.ssid, sizeof(s.name) - 1);
				String auth = authModeStr(obs.authMode, obs.pmfCapable, obs.pmfRequired);
				strncpy(s.auth, auth.c_str(), sizeof(s.auth) - 1);
				espnowSend(s);
				continue;
			}
#endif

			double lat, lon, alt, acc;
			// No GPS fix: forward at 0,0 so the phone's live tools still see the
			// AP. shouldLogAp still de-dupes (0,0 is one "cell", so each BSSID is
			// forwarded once per window rather than every beacon); cyd_node keeps
			// 0,0 rows out of the SD log/upload.
			if (!getLoggablePosition(lat, lon, alt, acc)) { lat = lon = alt = acc = 0.0; }

			if (!shouldLogAp(obs.bssid, lat, lon, obs.ssid[0] != '\0')) continue; // seen this BSSID recently nearby, skip the duplicate row

			String ssid(obs.ssid);
			ssid.replace(",", " ");
			ssid.replace("\n", " ");
			ssid.replace("\r", " ");
			cydLinkSendf("W,%s,%s,%s,%s,%u,%d,%d,%.6f,%.6f,%.1f,%.1f",
						 macToString(obs.bssid).c_str(), ssid.c_str(),
						 authModeStr(obs.authMode, obs.pmfCapable, obs.pmfRequired).c_str(),
						 isoTimestamp().c_str(), (unsigned)obs.channel, channelToFreqMHz(obs.channel),
						 obs.rssi, lat, lon, alt, acc);
		}

		// Only ever check this using a live fix, not the gap-tolerance
		// fallback - a real GPS dropout (e.g. underground parking) must
		// never itself look like "parked and idle" and shut the rig off.
		if (gpsLive()) {
			double lat = gps.location.lat(), lon = gps.location.lng();
			if (!idleAnchorSet) {
				idleAnchorLat = lat;
				idleAnchorLon = lon;
				idleAnchorSetMs = millis();
				idleAnchorSet = true;
			} else if (TinyGPSPlus::distanceBetween(lat, lon, idleAnchorLat, idleAnchorLon) > IDLE_MOVEMENT_THRESHOLD_M) {
				idleAnchorLat = lat;
				idleAnchorLon = lon;
				idleAnchorSetMs = millis();
			} else if (millis() - idleAnchorSetMs > IDLE_TIMEOUT_MS) {
				scanningActive = false;
				flashLed(0, 255, 0, LED_FLICKER_MS, true);
				cydLinkSendf("IDLESTOP");
				relayScanStateToBleNode();
				if (WARDRIVE_DEBUG) Serial.println("[idle] no movement for 30 minutes - auto-stopping to save power");
			}
		}
	}
}
