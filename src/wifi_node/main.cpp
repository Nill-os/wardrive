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

// wifi_node remains the authoritative source of truth for "should we be
// scanning" - it broadcasts its own scanningActive over both status links
// on this cadence, unconditionally (never gated behind WARDRIVE_DEBUG,
// since this is a correctness mechanism, not just diagnostics). The button
// is a shared physical signal so all three boards normally see the same
// click at the same instant and already agree - this broadcast exists
// purely to self-heal the case where a board missed a click because it was
// unpowered at the time, regardless of which board came up first or how
// long another was down.
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
static const uint8_t CHANNEL_MIN = 1;
static const uint8_t CHANNEL_MAX = 6; // cyd_node picks up 6-11 - see comment above

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
static const uint8_t LED_BRIGHTNESS_PCT = 3;
uint32_t ledOffAtMs = 0;
bool ledPriority = false; // true while a click/upload-status flash is showing

static uint8_t scaleBrightness(uint8_t channel) {
	return (uint16_t)channel * LED_BRIGHTNESS_PCT / 100;
}

// priority=true (clicks, upload status) always shows and can't be cut short
// by a capture flash. priority=false (per-packet captures) is skipped
// outright while a priority flash is still active, so a burst of AP
// captures can't mask or extend past a result the user actually needs to
// see, and the LED reliably settles back to off afterward instead of
// looking "stuck on" from continuous capture activity.
static void flashLed(uint8_t r, uint8_t g, uint8_t b, uint32_t durationMs = LED_FLICKER_MS, bool priority = false) {
	if (!priority && ledPriority && millis() < ledOffAtMs) return;
	neopixelWrite(RGB_BUILTIN, scaleBrightness(r), scaleBrightness(g), scaleBrightness(b));
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
uint8_t currentChannel = CHANNEL_MIN;

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

static void broadcastScanState() {
	// If this change originated here (idle-auto-stop) rather than being
	// adopted FROM cyd_node, cyd_node still needs to hear about it; if it
	// originated at cyd_node, this is a harmless echo its own resync check
	// will just no-op on. Relayed onward to ble_node over the wired link
	// too, same reasoning.
	cydLinkSendf("SCANSTATE:%d", scanningActive);
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

static void rememberGpsFix() {
	if (!gps.location.isValid()) return;
	lastKnownLat = gps.location.lat();
	lastKnownLon = gps.location.lng();
	lastKnownAlt = gps.altitude.isValid() ? gps.altitude.meters() : 0.0;
	lastKnownAcc = gps.hdop.isValid() ? gps.hdop.hdop() * 5.0 : 10.0;
	lastKnownFixMs = millis();
}

static bool getLoggablePosition(double &lat, double &lon, double &alt, double &acc) {
	if (gps.location.isValid()) {
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
static const uint32_t AP_DEDUP_FORGET_AFTER_MS = 2UL * 3600UL * 1000UL; // 2 hours
static const uint32_t AP_DEDUP_PRUNE_SWEEP_MS = 300000; // how often to check for entries to forget

struct ApDedupEntry {
	uint32_t lastLoggedMs;
	double lat;
	double lon;
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
static bool shouldLogAp(const uint8_t *mac, double lat, double lon) {
	uint32_t now = millis();

	if (now - lastApPruneMs > AP_DEDUP_PRUNE_SWEEP_MS) {
		lastApPruneMs = now;
		for (auto it = apDedupState.begin(); it != apDedupState.end();) {
			if (now - it->second.lastLoggedMs > AP_DEDUP_FORGET_AFTER_MS) it = apDedupState.erase(it);
			else ++it;
		}
	}

	uint64_t key = macToKey(mac);
	auto it = apDedupState.find(key);
	if (it != apDedupState.end()) {
		double movedM = TinyGPSPlus::distanceBetween(lat, lon, it->second.lat, it->second.lon);
		if (movedM < AP_DEDUP_MOVEMENT_THRESHOLD_M) return false; // haven't moved - still the same sighting
	}

	apDedupState[key] = {now, lat, lon};
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

	xQueueSend(obsQueue, &obs, 0);
}

static void startScanning() {
	apDedupState.clear(); // fresh run, fresh dedup - duplicates across separate runs are fine, never within one
	apSeenThisRunForLed.clear();
	esp_wifi_set_promiscuous(true);
	currentChannel = CHANNEL_MIN;
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

	if (WARDRIVE_DEBUG) Serial.printf("[ble-link] %s rssi=%d name=%s\n", mac.c_str(), rssi, name.c_str());

	double lat, lon, alt, acc;
	if (!getLoggablePosition(lat, lon, alt, acc)) return; // no usable position, live or recent

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
	} else if (line.startsWith("SCANSTATE:")) {
		// cyd_node's touchscreen (and its own phone-facing wdstream link) is
		// the primary control surface now that the physical button is gone -
		// this adopts whatever it says and relays it onward to ble_node, the
		// same as a local change (this board's own idle-auto-stop) already
		// does via broadcastScanState(). Comparing first avoids re-relaying
		// (and re-flashing the LED for) a line that just confirms what this
		// board already has - only a genuine change does either.
		bool v = line.substring(10).toInt() != 0;
		if (v != scanningActive) {
			scanningActive = v;
			flashLed(0, 255, 0, LED_FLICKER_MS, true);
			broadcastScanState();
			if (WARDRIVE_DEBUG) Serial.printf("[cyd-link] resynced to scanning=%d from cyd_node\n", scanningActive);
		}
	}
}

void setup() {
	Serial.begin(115200);

	neopixelWrite(RGB_BUILTIN, 0, 0, 0);

	GpsSerial.begin(9600, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);
	BleLinkSerial.begin(115200, SERIAL_8N1, PIN_BLE_LINK_RX, PIN_BLE_LINK_TX);
	CydLinkSerial.begin(115200, SERIAL_8N1, PIN_CYD_LINK_RX, PIN_CYD_LINK_TX);
	// readStringUntil() blocks for up to this long on a partial line - keep it
	// far below channelHopMs so a split line can't stall the hop timing.
	CydLinkSerial.setTimeout(20);

	obsQueue = xQueueCreate(64, sizeof(WifiObservation));

	WiFi.mode(WIFI_MODE_STA);
	WiFi.disconnect();
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

	// scanningActive starts false and stays that way until cyd_node's own
	// authoritative state arrives over the wired link (see handleCydLinkLine())
	// - no local button/NVS-driven resume anymore, see this file's header.
	Serial.println("wifi_node ready");
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
		Serial.printf("[heartbeat] up=%lus scanning=%d cydSdOk=%d gpsFix=%d lat=%.6f lon=%.6f sats=%d "
					  "gpsChars=%u gpsSentWithFix=%u gpsFailedCk=%u gpsPassedCk=%u cydrx=%lu\n",
					  (unsigned long)(millis() / 1000), scanningActive, cydSdOk,
					  gps.location.isValid(),
					  gps.location.isValid() ? gps.location.lat() : 0.0,
					  gps.location.isValid() ? gps.location.lng() : 0.0,
					  gps.satellites.isValid() ? gps.satellites.value() : -1,
					  (unsigned)gps.charsProcessed(), (unsigned)gps.sentencesWithFix(),
					  (unsigned)gps.failedChecksum(), (unsigned)gps.passedChecksum(), (unsigned long)cydLinkRxBytes);
	}

	if (millis() - lastScanSyncBroadcastMs > SCAN_SYNC_BROADCAST_MS) {
		lastScanSyncBroadcastMs = millis();
		broadcastScanState();

		// cyd_node has no GPS of its own - these keep its upload
		// rate-limiter/cleanup (EPOCH) and dock-mode home geofence
		// (GPSPOS) working without needing its own fix.
		cydLinkSendf("EPOCH:%lu", (unsigned long)gpsEpoch(gps));
		if (gps.location.isValid()) {
			cydLinkSendf("GPSPOS:1,%.6f,%.6f", gps.location.lat(), gps.location.lng());
		} else {
			cydLinkSendf("GPSPOS:0,0,0");
		}
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
	}
	rememberGpsFix();

	while (CydLinkSerial.available()) {
		String line = CydLinkSerial.readStringUntil('\n');
		cydLinkRxBytes += line.length() + 1;
		line.trim();
		if (line.length() > 0) handleCydLinkLine(line);
	}

	while (BleLinkSerial.available()) {
		String line = BleLinkSerial.readStringUntil('\n');
		line.trim();
		if (line.length() > 0) handleBleLinkLine(line);
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
			currentChannel++;
			if (currentChannel > CHANNEL_MAX) currentChannel = CHANNEL_MIN;
			esp_wifi_set_channel(currentChannel, WIFI_SECOND_CHAN_NONE);
		}

		WifiObservation obs;
		while (xQueueReceive(obsQueue, &obs, 0) == pdTRUE) {
			if (apSeenThisRunForLed.insert(macToKey(obs.bssid)).second) {
				flashLed(255, 0, 255); // purple - only for a genuinely new AP this run, independent of whether it can be logged
				if (WARDRIVE_DEBUG) Serial.printf("[led] new AP this run: %s\n", macToString(obs.bssid).c_str());
			}

			double lat, lon, alt, acc;
			if (!getLoggablePosition(lat, lon, alt, acc)) continue; // no usable position, live or recent

			if (!shouldLogAp(obs.bssid, lat, lon)) continue; // seen this BSSID recently nearby, skip the duplicate row

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
		if (gps.location.isValid()) {
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
				broadcastScanState();
				if (WARDRIVE_DEBUG) Serial.println("[idle] no movement for 30 minutes - auto-stopping to save power");
			}
		}
	}
}
