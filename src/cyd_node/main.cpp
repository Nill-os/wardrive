// cyd_node: status display + storage/upload node, running on a CYD
// (ESP32-2432S028 Dual USB / "CYD2USB" - 2.8" resistive touch, ST7789
// display driver - NOT the ILI9341 the classic single-USB CYD uses).
// Owns the only microSD card and config.cfg, runs the WigleWifi CSV writer
// and the wdgwars.pl/WiGLE uploader, and shows live rig status on its
// screen. Has no GPS of its own - wifi_node streams its own (already
// GPS-tagged) WiFi observations, plus every BLE observation ble_node
// relays through it, over a wireless BLE link (see CydBleLink.h - this
// board advertises, wifi_node connects to it; no wire needed between the
// two), along with periodic GPS position/epoch/scan-state broadcasts since
// this board has no GPS to derive those from itself.
//
// This board ALSO runs its own independent promiscuous WiFi sniffer now
// (added 2026-09-27, at the user's request for extra coverage alongside
// wifi_node's own) - a second antenna/vantage point sniffing the same
// spectrum, contributing its own catches into the SAME WigleWifi CSV
// wifi_node's relayed "W," lines already write to. Geotagged with the
// SAME cached GPS state (lastKnownLat/lastKnownLon/gpsFixKnown) the old
// dock-mode/home-geofence logic already relies on - this board still has
// no GPS of its own, so its own sniffs are only as fresh as wifi_node's
// last relayed fix, same staleness tradeoff GPSPOS broadcasts already
// accepted. Also means this board's own BLE server (to wifi_node) now
// shares its radio with its own WiFi sniffing, the same WiFi/BT
// coexistence tax wifi_node's own README section already documents and
// accepts - see startScanning()/stopScanning() below.
//
// This board's touchscreen is now the ONLY start/stop/upload control
// surface for the whole rig - the shared physical button was removed
// entirely (it used to be wired in parallel across all three boards).
// cyd_node is therefore the authoritative source of scanning state:
// touching START/STOP here broadcasts SCANSTATE over the BLE link, which
// wifi_node adopts and relays onward to ble_node over its own wired link -
// see CydBleLink.h and wifi_node/main.cpp's own header for the other side
// of this.
//
// This board is also the one the phone plugs into over USB - it's a plain
// ESP32 with a real USB-UART bridge chip, unlike wifi_node's/ble_node's
// ESP32-S3 native USB peripheral, a better fit for a phone-side serial app
// to talk to (see the "wdstream" section below). A phone running WardriveGo
// can remotely start/stop scanning via "scan start"/"scan stop" over that
// same USB port - that path was never touch-button-dependent either way.
//
// START/STOP button : toggle scanning + logging on/off (all three boards)
// UPLOAD button      : immediately upload all not-yet-uploaded session
//                      files to wdgwars.pl + WiGLE
//
// Touch (XPT2046) is bit-banged in software rather than given its own
// hardware SPI peripheral - this board wires touch to GPIO25/32/39/33/36,
// entirely different pins than the display's own SPI (13/14/15), and a
// classic ESP32 only has two usable hardware SPI peripherals (HSPI/VSPI),
// both already spoken for (one by the display, one by the SD card - see
// PIN_SD_* below). Bit-banging sidesteps that shortage entirely; touch
// polling doesn't need real throughput, so the performance cost is
// irrelevant. Calibration constants below (TOUCH_RAW_*) are a starting
// guess, not verified against a real unit - see their own comment.
//
// Pin assignments below are specific to the dual-USB variant's pinout -
// verify against your own board's silkscreen/seller listing before
// flashing regardless, since sub-revisions can still differ.
//
// This board's USB-C port has no CC resistors, so a USB-C-to-USB-C cable
// will NOT power or program it - use USB-A-to-USB-C, or the Micro-USB port
// instead. Not a firmware concern, just a heads-up for bring-up.
//
// See ../../README.md for wiring and config.cfg setup (config.cfg now
// lives on THIS board's SD card).

#include <Arduino.h>
#include <SPI.h>
#include <SD.h>
#include <Preferences.h>
#include <TFT_eSPI.h>

#include <WiFi.h>
#include "esp_wifi.h"
#include <time.h>
#include <unordered_map>
#include <unordered_set>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "WardriveConfig.h"
#include "WigleWriter.h"
#include "Uploader.h"
#include "CydBleLink.h"

// Survives sudden car-power loss: remembers whether you'd told the rig to
// be scanning, so it resumes on its own once power is back. Separate
// namespace from Uploader's own ("wardrive").
static const char *STATE_PREFS_NS = "wdstate";
static const char *STATE_PREFS_KEY = "scanning";
Preferences statePrefs;
bool resumeScanningIntent = false;

static constexpr bool WARDRIVE_DEBUG = true;
static const uint32_t HEARTBEAT_MS = 2000;

// ---- Pin assignments (ESP32-2432S028 Dual USB / "CYD2USB" pinout) ----
// TFT (ST7789) and touch (XPT2046) each have their own dedicated pins wired
// on the board itself (see platformio.ini's [env:cyd_node] build_flags for
// the display config, and the touch section further down for touch) - only
// the pins this file touches directly are listed below. Only GPIO22,
// GPIO27, and GPIO35 are actually free for our own use on this board;
// everything else is already claimed by the display, touch controller, SD
// slot, RGB LED, audio amp, or light sensor - see the wiring section of
// ../../README.md for the full occupied-pin list this was checked against.
//
// GPIO22, GPIO27, and GPIO35 are all free and unused now - the wifi_node
// link moved to BLE (CydBleLink.h) and the physical button (which used
// GPIO35) was removed rig-wide; the touchscreen is the only control surface
// now.

// microSD uses its own SPI bus (separate SPIClass instance below), distinct
// from TFT_eSPI's internal SPI, to avoid the two contending over the bus.
static const uint8_t PIN_SD_CS = 5;
static const uint8_t PIN_SD_MOSI = 23;
static const uint8_t PIN_SD_MISO = 19;
static const uint8_t PIN_SD_SCK = 18;

// Touch controller (XPT2046, resistive) - bit-banged, see file header for
// why. These are fixed pins on this board, not user-choosable.
static const uint8_t PIN_TOUCH_CS = 33;
static const uint8_t PIN_TOUCH_CLK = 25;
static const uint8_t PIN_TOUCH_DIN = 32;  // MOSI - ESP32 drives this into the touch chip
static const uint8_t PIN_TOUCH_DOUT = 39; // MISO - touch chip drives this into the ESP32
static const uint8_t PIN_TOUCH_IRQ = 36;  // active LOW while the panel is being pressed

// The CYD's onboard status LED is a plain 3-pin common-cathode RGB LED
// (active-HIGH to light), not an addressable WS2812 like the ESP32-S3-
// DevKitC boards the other two nodes use - no neopixelWrite()/brightness
// scaling available, just per-channel digitalWrite().
static const uint8_t PIN_LED_R = 4;
static const uint8_t PIN_LED_G = 16;
static const uint8_t PIN_LED_B = 17;

static const char *SESSION_DIR = "/wardrive";
static const uint32_t DOCK_CHECK_MS = 60000;

SPIClass sdSPI(VSPI);
TFT_eSPI tft = TFT_eSPI();

WardriveConfig config;
WigleWriter wigleWifi;
WigleWriter wigleBle;
Uploader *uploader = nullptr;

volatile bool scanningActive = false;
volatile bool uploadRequested = false;
bool wasScanning = false;
bool sdOk = false;
bool configOk = false;

uint32_t wifiCountThisRun = 0;
uint32_t bleCountThisRun = 0;

// Mirrored from wifi_node's periodic broadcasts - this board has no GPS or
// WiFi-sniffing radio of its own, so its view of "what time is it" and "are
// we near home" comes from wifi_node instead of being derived locally.
// Set by setScanning() whenever THIS board makes a genuine local scanning
// decision (touch toggle or a phone "scan start"/"scan stop" command) - see
// its use in handleIncomingLine()'s SCANSTATE case below.
volatile uint32_t lastLocalScanSetMs = 0;
volatile uint32_t lastKnownEpoch = 0;
bool gpsFixKnown = false;

// Whether the phone is actively talking to this board over USB (see the
// wdstream section further down for how this gets set/cleared) - declared
// here, ahead of its own section, so drawHeader() can read it for the
// header's PHONE indicator without needing a separate forward declaration.
bool phoneLinkUp = false;
static const char *phoneLinkLabel() {
	if (!phoneLinkUp) return "PHONE:DOWN";
	return CydBleLink::isConnected() ? "PHONE:BLE" : "PHONE:USB";
}
double lastKnownLat = 0.0, lastKnownLon = 0.0;
uint8_t lastKnownChannel = 0;
int16_t lastKnownSatCount = -1; // -1 = not yet known / no valid GPS fix on wifi_node

// ---- Links ----
// wifi_node: wired UART on the CN1 header (IO22 = TX -> wifi_node GPIO14,
// IO27 = RX <- wifi_node GPIO13, plus GND; leave CN1's 3.3V pin unconnected).
// Its own UART rather than the bottom serial header: that one is UART0, and
// the on-board USB chip drives its RX line too, drowning out wifi_node.
// Phone: BLE via CydBleLink's server role (primary), with the USB port
// (Serial) mirroring every line as a fallback.
static const uint8_t PIN_WIFI_LINK_TX = 22;
static const uint8_t PIN_WIFI_LINK_RX = 27;
HardwareSerial WifiLinkSerial(2);
uint32_t wifiLinkRxBytes = 0; // bytes of wifi_node lines received, for the heartbeat

static void wifiLinkSend(const char *line) {
	char buf[240];
	int n = snprintf(buf, sizeof(buf), "%s\n", line);
	if (n <= 0) return;
	if (n >= (int)sizeof(buf)) n = sizeof(buf) - 1;
	WifiLinkSerial.write((const uint8_t *)buf, n);
}

static void phonePrintf(const char *fmt, ...) {
	char buf[240];
	va_list args;
	va_start(args, fmt);
	vsnprintf(buf, sizeof(buf), fmt, args);
	va_end(args);
	Serial.println(buf);
	CydBleLink::send(buf);
}

// ---- LED (plain RGB, active-HIGH) ----
static void setLed(bool r, bool g, bool b) {
	digitalWrite(PIN_LED_R, r ? HIGH : LOW);
	digitalWrite(PIN_LED_G, g ? HIGH : LOW);
	digitalWrite(PIN_LED_B, b ? HIGH : LOW);
}

static const uint32_t LED_FLICKER_MS = 150;
static const uint32_t LED_RESULT_MS = 800;
uint32_t ledOffAtMs = 0;
bool ledPriority = false;

static void flashLed(bool r, bool g, bool b, uint32_t durationMs = LED_FLICKER_MS, bool priority = false) {
	if (!priority && ledPriority && millis() < ledOffAtMs) return;
	setLed(r, g, b);
	ledOffAtMs = millis() + durationMs;
	ledPriority = priority;
}

static const uint32_t SD_ERROR_BLINK_MS = 200;
static const uint8_t SD_ERROR_BLINK_COUNT = 5;
uint8_t errorBlinksRemaining = 0;
uint32_t nextErrorBlinkMs = 0;

static void startSdErrorBlink() {
	errorBlinksRemaining = SD_ERROR_BLINK_COUNT - 1;
	flashLed(true, false, false, SD_ERROR_BLINK_MS, true);
	nextErrorBlinkMs = millis() + SD_ERROR_BLINK_MS * 2;
}

static void serviceErrorBlink() {
	if (errorBlinksRemaining == 0) return;
	if (millis() < nextErrorBlinkMs) return;
	flashLed(true, false, false, SD_ERROR_BLINK_MS, true);
	errorBlinksRemaining--;
	nextErrorBlinkMs = millis() + SD_ERROR_BLINK_MS * 2;
}

// Alternating cyan/purple "upload in progress" pattern, broadcast to
// wifi_node (which relays it on to ble_node) so all three boards' LEDs stay
// in lockstep instead of drifting apart on independent timers.
static const uint32_t UPLOAD_BLINK_MS = 200;
TaskHandle_t uploadBlinkTaskHandle = nullptr;

static void uploadBlinkTaskFn(void *) {
	bool cyanPhase = true;
	for (;;) {
		if (cyanPhase) {
			setLed(false, true, true);
			wifiLinkSend("BLINKPHASE:0");
		} else {
			setLed(true, false, true);
			wifiLinkSend("BLINKPHASE:1");
		}
		cyanPhase = !cyanPhase;
		vTaskDelay(pdMS_TO_TICKS(UPLOAD_BLINK_MS));
	}
}

static void startUploadBlink() {
	ledPriority = true;
	xTaskCreate(uploadBlinkTaskFn, "uploadBlink", 2048, nullptr, 1, &uploadBlinkTaskHandle);
}

static void stopUploadBlink() {
	if (uploadBlinkTaskHandle) {
		vTaskDelete(uploadBlinkTaskHandle);
		uploadBlinkTaskHandle = nullptr;
	}
}

// ---- Touch (XPT2046, bit-banged) ----
static const uint8_t XPT2046_CMD_X = 0xD0;
static const uint8_t XPT2046_CMD_Y = 0x90;

static uint16_t xptRead(uint8_t cmd) {
	digitalWrite(PIN_TOUCH_CS, LOW);
	for (int i = 7; i >= 0; i--) {
		digitalWrite(PIN_TOUCH_CLK, LOW);
		digitalWrite(PIN_TOUCH_DIN, (cmd >> i) & 1);
		delayMicroseconds(2);
		digitalWrite(PIN_TOUCH_CLK, HIGH);
		delayMicroseconds(2);
	}
	digitalWrite(PIN_TOUCH_CLK, LOW);
	delayMicroseconds(2);
	uint16_t value = 0;
	for (int i = 0; i < 16; i++) {
		digitalWrite(PIN_TOUCH_CLK, HIGH);
		delayMicroseconds(2);
		value <<= 1;
		if (digitalRead(PIN_TOUCH_DOUT)) value |= 1;
		digitalWrite(PIN_TOUCH_CLK, LOW);
		delayMicroseconds(2);
	}
	digitalWrite(PIN_TOUCH_CS, HIGH);
	return (value >> 3) & 0x0FFF; // top 12 bits are the ADC result, bottom 3 are don't-care
}

// Calibrated against this real unit via runTouchCalibrationDebug() (see
// TOUCH_CALIBRATION_DEBUG near that function) - tapped all 4 corners,
// extrapolated the true-edge (x=0/240, y=0/320) raw values from the two
// inset (20px-margin) data points per axis. Raw X decreases as screen X
// increases (confirmed from real taps: left-edge tap -> rawX ~3444,
// right-edge tap -> rawX ~654), so MIN (screen x=0) is deliberately the
// LARGER raw number and MAX (screen x=240) the smaller one - map()'s own
// formula handles a reversed range correctly, no separate
// TOUCH_INVERT_X=true needed on top of that. Raw Y increases normally with
// screen Y, no swap needed either. Re-run the calibration tool (flip
// TOUCH_CALIBRATION_DEBUG on) if this unit's touch panel or display
// hardware is ever replaced.
static int32_t TOUCH_RAW_X_MIN = 3723; // screen x = 0
static int32_t TOUCH_RAW_X_MAX = 375;	// screen x = tft.width()
static int32_t TOUCH_RAW_Y_MIN = 224;	// screen y = 0
static int32_t TOUCH_RAW_Y_MAX = 3796; // screen y = tft.height()
static bool TOUCH_SWAP_XY = false;
static bool TOUCH_INVERT_X = false;
static bool TOUCH_INVERT_Y = false;

struct TouchPoint {
	int16_t x = 0, y = 0;
	bool valid = false;
};

// Edge-triggered by the caller (loop() only acts on the not-touched ->
// touched transition, see wasTouched below) rather than re-firing a button
// action on every poll while a finger is held down.
// Shared by readTouch() and runTouchCalibrationDebug() - the latter needs
// the true raw ADC values, before any of the swap/invert/scale correction
// below is applied, since its whole job is figuring out what that
// correction should be.
static bool readTouchRaw(int32_t &rawX, int32_t &rawY) {
	if (digitalRead(PIN_TOUCH_IRQ) != LOW) return false; // not pressed
	xptRead(XPT2046_CMD_X); // throwaway - the first conversion right after CS goes low is often noisy on this chip
	rawX = xptRead(XPT2046_CMD_X);
	rawY = xptRead(XPT2046_CMD_Y);
	return rawX > 0 && rawY > 0;
}

static TouchPoint readTouch() {
	TouchPoint p;
	int32_t rawX, rawY;
	if (!readTouchRaw(rawX, rawY)) return p;
	if (WARDRIVE_DEBUG) Serial.printf("[touch] raw x=%ld y=%ld\n", (long)rawX, (long)rawY);

	if (TOUCH_SWAP_XY) {
		int32_t t = rawX;
		rawX = rawY;
		rawY = t;
	}

	int32_t sx = map(rawX, TOUCH_RAW_X_MIN, TOUCH_RAW_X_MAX, 0, tft.width());
	int32_t sy = map(rawY, TOUCH_RAW_Y_MIN, TOUCH_RAW_Y_MAX, 0, tft.height());
	if (TOUCH_INVERT_X) sx = tft.width() - 1 - sx;
	if (TOUCH_INVERT_Y) sy = tft.height() - 1 - sy;

	p.x = (int16_t)constrain(sx, 0, tft.width() - 1);
	p.y = (int16_t)constrain(sy, 0, tft.height() - 1);
	p.valid = true;
	return p;
}

// ---- UI theme: "Nill DECK v2.2" cyberpunk palette ----
// COLOR_CYAN/COLOR_PURPLE are the two brand accents (WiFi-side/BLE-side
// respectively, same split the old ticker already used). GREEN/RED/ORANGE
// are NOT in the strict brand palette but are kept for genuine status
// semantics (upload ok/fail, SD fail) that the 2-accent palette has no
// color for - using cyan/purple for "SD card failed" would be actively
// misleading, so this is a deliberate, narrow exception, not decoration.
static uint16_t COLOR_BG, COLOR_PANEL, COLOR_TEXT, COLOR_TEXT_DIM,
	COLOR_PURPLE, COLOR_CYAN, COLOR_GREEN, COLOR_RED, COLOR_ORANGE;

// This physical panel is wired BGR, not RGB - confirmed via testing
// (2026-09-27): the spec's exact cyan (0x00F0FF) rendered as pure yellow
// and its exact purple (0x9D00FF) rendered as hot pink, precisely what a
// swapped red/blue channel produces for those two values. TFT_eSPI's own
// ST7789 init table doesn't correct this for this specific panel variant
// (same class of per-panel-variant quirk as the TFT_INVERSION_OFF fix
// above), so colors are swapped here at the point they're defined instead
// - every color below is specified as the intended RGB triple, and rgb565()
// does the R/B swap once, centrally, rather than every call site needing
// to remember to pass channels in the wrong order.
static uint16_t rgb565(uint8_t r, uint8_t g, uint8_t b) {
	return tft.color565(b, g, r);
}

static void initTheme() {
	COLOR_BG = rgb565(0x0A, 0x0A, 0x12);	 // deep void near-black
	COLOR_PANEL = rgb565(0x15, 0x15, 0x22); // surface gray-black
	COLOR_TEXT = rgb565(0xE8, 0xE8, 0xF0);
	COLOR_TEXT_DIM = rgb565(0x7A, 0x7A, 0x92);
	COLOR_CYAN = rgb565(0x00, 0xF0, 0xFF);	 // primary accent
	COLOR_PURPLE = rgb565(0x9D, 0x00, 0xFF); // secondary accent
	COLOR_GREEN = rgb565(0x22, 0xC5, 0x5E);
	COLOR_RED = rgb565(0xEF, 0x44, 0x44);
	COLOR_ORANGE = rgb565(0xF5, 0x9E, 0x0B);
	if (WARDRIVE_DEBUG) {
		Serial.printf("[theme] bg=0x%04X panel=0x%04X cyan=0x%04X purple=0x%04X\n",
					  COLOR_BG, COLOR_PANEL, COLOR_CYAN, COLOR_PURPLE);
	}
}

// ---- Touch button hit-testing ----
struct TouchButton {
	int16_t x, y, w, h;
};

static bool inRect(const TouchButton &r, int16_t x, int16_t y) {
	return x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
}

// ---- Tabs (5-screen "Nill DECK" layout) ----
// Only MAIN's controls map 1:1 onto real rig actions (this board only ever
// had START/STOP/UPLOAD/RE-LINK, see onSingleClickHandler() etc below).
// TARGETS/MESH/LOGS/CFG are new views onto data this board already has
// (or can derive) but never displayed before - see each tab's own render
// function for what's real vs. adapted from the original 3-rig-mesh design
// brief (this rig only ever has ONE wifi_node and ONE ble_node, not a
// cluster of independent RIG-01/02/03 peers).
enum class Tab : uint8_t { Main, Targets, Links, Logs, Cfg };
static Tab currentTab = Tab::Main;
static const char *TAB_LABELS[5] = {"1.MAIN", "2.TGTS", "3.LINKS", "4.LOGS", "5.CFG"};
TouchButton tabRects[5];
// Reused per-tab (only one tab's buttons are ever visible/tappable at
// once) rather than named per-tab - up to 3 action buttons per tab.
TouchButton actionRects[3];
uint8_t actionRectCount = 0;

void onSingleClickHandler(); // forward-declared - defined below, called by the touch dispatch in loop()
void onDoubleClickHandler();
void onReconnectHandler();
void onWipeLogsHandler();
void onRebootHandler();
void onClearTargetsHandler();
void onPauseLogHandler();
static void handleWdstreamCommand(String line); // used by onReconnectHandler(), defined in the wdstream section further down
void onFlushSdHandler();

// ---- TARGETS tab: bounded ring buffer of recently-seen APs ----
// Display-only - the real, permanent record is WigleWriter's own CSV on
// SD (already written for every observation regardless of whether this
// buffer has room). This just lets the touchscreen show something without
// re-reading the CSV back off SD every redraw.
struct ApSighting {
	String bssid, ssid, auth;
	int rssi;
};
static const uint8_t MAX_AP_SIGHTINGS = 8;
ApSighting apSightings[MAX_AP_SIGHTINGS];
uint8_t apSightingCount = 0;
uint8_t apSightingHead = 0;
uint32_t apSightingVersion = 0; // bumped on every push/clear - lets the TARGETS tab skip redrawing when nothing changed

static void pushApSighting(const String &bssid, const String &ssid, const String &auth, int rssi) {
	apSightings[apSightingHead] = {bssid, ssid, auth, rssi};
	apSightingHead = (apSightingHead + 1) % MAX_AP_SIGHTINGS;
	if (apSightingCount < MAX_AP_SIGHTINGS) apSightingCount++;
	apSightingVersion++;
}

// ---- LOGS tab: bounded ring buffer mirroring the same events the MAIN
// tab's single-line ticker shows, kept as history instead of overwritten ----
struct LogLine {
	String text;
	uint16_t color;
};
static const uint8_t MAX_LOG_LINES = 11;
LogLine logLines[MAX_LOG_LINES];
uint8_t logLineCount = 0;
uint8_t logLineHead = 0;
bool logPaused = false; // PAUSE LOG - stops new entries; SD logging is unaffected either way
uint32_t logLineVersion = 0; // bumped on every push - lets the LOGS tab skip redrawing when nothing changed

static void pushLogLine(const String &text, uint16_t color) {
	if (logPaused) return;
	logLines[logLineHead] = {text, color};
	logLineHead = (logLineHead + 1) % MAX_LOG_LINES;
	if (logLineCount < MAX_LOG_LINES) logLineCount++;
	logLineVersion++;
}

// ---- Display ----
// Redrawn wholesale on a slow timer rather than per-observation - this is a
// glance-while-driving status screen, not a live radar, and a full redraw
// is cheap enough at this refresh rate not to matter.
static const uint32_t DISPLAY_REFRESH_MS = 500;
uint32_t lastDisplayRefreshMs = 0;
String lastUploadStatusText = "";

// Set after drawUploadingOverlay() has painted over the dashboard, so the
// next drawStatus() call knows to repaint everything - its own dirty-
// tracking has no idea the screen was overwritten out from under it by
// something outside its own state tracking, and would otherwise leave
// most of the dashboard stuck showing stale "UPLOADING" content forever
// (only whatever few fields happened to genuinely change would redraw).
volatile bool forceFullRedraw = false;

// Upload blocks the whole main loop for its entire duration (WiFi connect +
// every file's HTTP round trip) - without this, the screen (and touch/
// button handling, which also only runs in loop()) would appear frozen for
// however long that takes, which is exactly what looked like "the button
// isn't working" before this existed. Drawn once per progress callback
// (on connect, then once per file) - not animated, since there's no way to
// redraw between callbacks during a single blocking HTTP call, but seeing
// the file name/stage advance is enough to show it's alive.
static void drawUploadingOverlay(const char *stage, const char *detail) {
	const int16_t w = tft.width();
	const int16_t h = tft.height();
	tft.fillScreen(COLOR_BG);

	// Little octopus "swimming" across the top of the overlay - upload-
	// screen-only, per user request (the same art used to also sit on the
	// MAIN dashboard next to SYSTEMS, but was mistaken for a rendering
	// artifact there and removed, 2026-09-27). Can't be a smooth animation
	// since each call here happens once per progress callback (connect,
	// then once per file, then once per reconnect-wait tick) and everything
	// in between is a blocking call with no chance to redraw, but its x
	// position is derived from millis() so it visibly drifts to a new spot
	// (a triangle-wave bounce) on every call instead of sitting frozen.
	const char *octo[] = {"  .--.", " /o  o\\", "( '--' )", " \\/\\/\\/"};
	const int16_t octoW = 56;
	uint32_t t = millis() % 6000;
	int16_t range = w - octoW - 20;
	int16_t octoX = 10 + (int16_t)(t < 3000 ? (t * range / 3000) : ((6000 - t) * range / 3000));
	tft.setTextDatum(TL_DATUM);
	tft.setTextSize(1);
	tft.setTextColor(COLOR_CYAN, COLOR_BG);
	for (uint8_t i = 0; i < 4; i++) tft.drawString(octo[i], octoX, 8 + i * 9);

	tft.setTextDatum(MC_DATUM);
	tft.setTextColor(COLOR_PURPLE, COLOR_BG);
	tft.setTextSize(3);
	tft.drawString("UPLOADING", w / 2, h / 2 - 30);
	tft.setTextSize(2);
	tft.setTextColor(COLOR_TEXT, COLOR_BG);
	tft.drawString(stage, w / 2, h / 2 + 5);
	tft.setTextSize(1);
	tft.setTextColor(COLOR_TEXT_DIM, COLOR_BG);
	String detailStr(detail);
	if (detailStr.length() > 40) detailStr = detailStr.substring(0, 40) + "...";
	tft.drawString(detailStr, w / 2, h / 2 + 30);
	tft.setTextDatum(TL_DATUM);
	forceFullRedraw = true;
}

static void onUploadProgress(const char *stage, const char *detail) {
	drawUploadingOverlay(stage, detail);
}

// textSize defaults to 2 for the single full-width buttons (MAIN's 3
// stacked buttons, TARGETS'/MESH's single action button). The two-button
// side-by-side rows (LOGS, CFG) are only ~111px wide each - a size-2 label
// like "> FLUSH TO SD" (144px) doesn't fit and visibly spills into the
// neighboring button (the actual cause of the reported button overlap -
// confirmed via testing, 2026-09-27), so those call sites pass size 1.
static void drawButton(const TouchButton &r, const char *label, uint16_t color, uint8_t textSize = 2) {
	tft.fillRoundRect(r.x, r.y, r.w, r.h, 10, color);
	tft.setTextDatum(MC_DATUM);
	tft.setTextColor(TFT_BLACK, color);
	tft.setTextSize(textSize);
	tft.drawString(label, r.x + r.w / 2, r.y + r.h / 2);
	tft.setTextDatum(TL_DATUM);
}

// Link status for the wired UART to wifi_node. A wire has no connection
// handshake, so "connected" means wifi_node's periodic broadcasts (every
// ~2s: SCANSTATE/EPOCH/GPSPOS/SATS/CH) are still arriving - the timeout
// allows three missed broadcasts, so an ordinary gap between two of them
// never flickers the state. CONNECTING only shows in the window right after
// RE-LINK is touched (see onReconnectHandler()) until a line arrives.
enum class LinkState : uint8_t { Disconnected, Connecting, Connected };

volatile bool linkReconnecting = false;
static const uint32_t WIFI_LINK_TIMEOUT_MS = 6000;
volatile uint32_t lastWifiLinkRxMs = 0; // 0 = nothing received since boot

static LinkState currentLinkState() {
	uint32_t last = lastWifiLinkRxMs;
	if (last != 0 && millis() - last < WIFI_LINK_TIMEOUT_MS) {
		linkReconnecting = false; // a live link supersedes the "still reconnecting" window
		return LinkState::Connected;
	}
	return linkReconnecting ? LinkState::Connecting : LinkState::Disconnected;
}

// Periodic rig-connection indicator on this board's physical LED - unlike
// every other flash here (and unlike wifi_node's/ble_node's LEDs entirely,
// which only ever flash once per event and then go dark), this REPEATS for
// as long as the link stays down, so a glance at the LED alone - without
// looking at the screen - tells you the rig is unreachable right now, not
// just what last happened. Deliberately silent (no periodic blink at all)
// while connected, matching this rig's existing "no LED activity = nothing
// wrong" convention; and deliberately non-priority (won't cut off or delay
// a real click/upload/error flash - see flashLed()'s own comment) since a
// recurring status reminder should never compete with an actual event for
// the viewer's attention. This is a different LED BEHAVIOR entirely from
// either node board's, not just a different color choice - see the
// user's own request for why that distinction mattered (2026-09-27).
static const uint32_t LINK_LED_DOWN_PERIOD_MS = 3000;
static const uint32_t LINK_LED_CONNECTING_PERIOD_MS = 800;
uint32_t lastLinkLedMs = 0;

static void serviceLinkStatusBlink() {
	LinkState state = currentLinkState();
	if (state == LinkState::Connected) return;
	uint32_t period = state == LinkState::Connecting ? LINK_LED_CONNECTING_PERIOD_MS : LINK_LED_DOWN_PERIOD_MS;
	if (millis() - lastLinkLedMs > period) {
		lastLinkLedMs = millis();
		flashLed(true, false, false, 150, false); // brief red blip
	}
}

// Redraws only what actually changed since the last call, rather than
// unconditionally repainting every element every DISPLAY_REFRESH_MS - the
// full unconditional repaint (previously including a fillScreen()) was
// visible as a periodic flicker even after fillScreen() alone was removed,
// since every card/button/dot was still being fully repainted on an
// identical timer regardless of whether its content had changed.
// ---- Layout constants shared by the header and every tab's content ----
static const int16_t HDR_GAP = 6;
static const int16_t HDR_ROW_H = 12;
static const int16_t TABBAR_Y = 30;
static const int16_t TABBAR_H = 16;
static const int16_t CONTENT_Y = TABBAR_Y + TABBAR_H + 4; // ~50
static const int16_t CONTENT_BOTTOM = 318;					// leaves a hairline at the very edge

static void drawPanelTitle(int16_t x, int16_t y, int16_t w, const char *label, uint16_t color) {
	tft.drawRoundRect(x, y, w, 14, 4, color);
	tft.setTextDatum(MC_DATUM);
	tft.setTextColor(color, COLOR_BG);
	tft.setTextSize(1);
	tft.drawString(String("> ") + label + " <", x + w / 2, y + 7);
	tft.setTextDatum(TL_DATUM);
}

// ---- Header: status bar (2 lines) + tab nav (always visible, every tab) ----
static void drawHeader(bool firstDraw) {
	const int16_t w = tft.width();
	static bool lastScanningActive = false;
	static bool lastGpsFixKnown = false;
	static uint8_t lastDrawnChannel = 0xFF;
	static LinkState lastLinkState = LinkState::Disconnected;
	static const char *lastPhoneLabel = nullptr;
	static Tab lastDrawnTab = Tab::Main;

	if (firstDraw || gpsFixKnown != lastGpsFixKnown || lastKnownChannel != lastDrawnChannel) {
		tft.fillRect(0, 1, w / 2, HDR_ROW_H, COLOR_BG);
		tft.setTextSize(1);
		tft.setTextColor(gpsFixKnown ? COLOR_GREEN : COLOR_ORANGE, COLOR_BG);
		tft.setCursor(HDR_GAP, 2);
		tft.print(gpsFixKnown ? "GPS:FIX" : "GPS:--");
		tft.setTextColor(COLOR_CYAN, COLOR_BG);
		tft.setCursor(HDR_GAP + 60, 2);
		if (lastKnownChannel > 0) tft.printf("CH:%02u", lastKnownChannel);
		else tft.print("CH:--");
	}
	if (firstDraw || scanningActive != lastScanningActive) {
		tft.fillRect(w / 2, 1, w / 2 - HDR_GAP, HDR_ROW_H, COLOR_BG);
		tft.setTextSize(1);
		tft.setTextColor(scanningActive ? COLOR_GREEN : COLOR_TEXT_DIM, COLOR_BG);
		tft.setTextDatum(TR_DATUM);
		tft.drawString(scanningActive ? "STATUS:SCANNING" : "STATUS:STOPPED", w - HDR_GAP, 2);
		tft.setTextDatum(TL_DATUM);
	}

	LinkState linkState = currentLinkState();
	if (!firstDraw && linkState != lastLinkState && WARDRIVE_DEBUG) {
		const char *stateName = linkState == LinkState::Connected ? "CONNECTED"
								 : linkState == LinkState::Connecting ? "CONNECTING"
																	   : "DISCONNECTED";
		Serial.printf("[link] state -> %s\n", stateName);
	}
	if (firstDraw || linkState != lastLinkState) {
		tft.fillRect(0, 1 + HDR_ROW_H + 2, w / 2, HDR_ROW_H, COLOR_BG);
		uint16_t linkColor = linkState == LinkState::Connected ? COLOR_CYAN
							  : linkState == LinkState::Connecting ? COLOR_PURPLE
																	: COLOR_RED;
		const char *linkLabel = linkState == LinkState::Connected ? "RIG:UP"
								 : linkState == LinkState::Connecting ? "RIG:WAIT"
																	   : "RIG:DOWN";
		tft.fillCircle(HDR_GAP + 3, 1 + HDR_ROW_H + 2 + 4, 5, linkColor); // link has 3 states, drawn directly rather than via a generic ok/warn/error helper
		tft.setTextSize(1);
		tft.setTextColor(linkColor, COLOR_BG);
		tft.setCursor(HDR_GAP + 12, 1 + HDR_ROW_H + 2);
		tft.print(linkLabel);
		// Mirror to the phone app over USB, same "WD:" convention as SCANSTATE - see
		// handleWdstreamCommand()'s own snapshot of this for why that one exists too (this one
		// only fires here on an actual change, not a fresh phone connection's first request).
		if (!firstDraw) phonePrintf("WD:MESHLINK:%d", (int)linkState);
	}
	// Right side of the same row: the phone's link to THIS board, and which transport it's on
	// (BLE is primary, USB the fallback - see the Links section's comments).
	const char *phoneLabel = phoneLinkLabel();
	if (firstDraw || phoneLabel != lastPhoneLabel) {
		static const int16_t PHONE_BLOCK_W = 86; // fits "PHONE:DOWN" plus the dot, at text size 1
		int16_t blockX = w - HDR_GAP - PHONE_BLOCK_W;
		tft.fillRect(blockX, 1 + HDR_ROW_H + 2, PHONE_BLOCK_W, HDR_ROW_H, COLOR_BG);
		uint16_t phoneColor = phoneLinkUp ? COLOR_CYAN : COLOR_RED;
		tft.fillCircle(blockX + 3, 1 + HDR_ROW_H + 2 + 4, 5, phoneColor);
		tft.setTextSize(1);
		tft.setTextColor(phoneColor, COLOR_BG);
		tft.setCursor(blockX + 12, 1 + HDR_ROW_H + 2);
		tft.print(phoneLabel);
	}

	// Tab nav bar - active tab solid cyan w/ bright border, inactive dim
	// purple, matching the spec's active/inactive scheme.
	if (firstDraw || currentTab != lastDrawnTab) {
		tft.fillRect(0, TABBAR_Y, w, TABBAR_H, COLOR_BG);
		int16_t tabW = w / 5;
		for (uint8_t i = 0; i < 5; i++) {
			tabRects[i] = {(int16_t)(i * tabW), TABBAR_Y, tabW, TABBAR_H};
			bool active = (uint8_t)currentTab == i;
			if (active) {
				tft.fillRoundRect(tabRects[i].x + 1, tabRects[i].y, tabW - 2, TABBAR_H, 3, COLOR_CYAN);
				tft.drawRoundRect(tabRects[i].x + 1, tabRects[i].y, tabW - 2, TABBAR_H, 3, COLOR_TEXT);
			}
			tft.setTextDatum(MC_DATUM);
			tft.setTextColor(active ? TFT_BLACK : COLOR_PURPLE, active ? COLOR_CYAN : COLOR_BG);
			tft.setTextSize(1);
			tft.drawString(TAB_LABELS[i], tabRects[i].x + tabW / 2, tabRects[i].y + TABBAR_H / 2);
			tft.setTextDatum(TL_DATUM);
		}
	}

	lastScanningActive = scanningActive;
	lastGpsFixKnown = gpsFixKnown;
	lastDrawnChannel = lastKnownChannel;
	lastLinkState = linkState;
	lastPhoneLabel = phoneLabel;
	lastDrawnTab = currentTab;
}

static void clearContentArea() {
	tft.fillRect(0, CONTENT_Y, tft.width(), CONTENT_BOTTOM - CONTENT_Y, COLOR_BG);
}

static void setActionButton(uint8_t idx, int16_t x, int16_t y, int16_t w, int16_t h) {
	actionRects[idx] = {x, y, w, h};
}

// ---- TAB 1: MAIN - the only tab whose controls map 1:1 onto real rig
// actions (this board only ever had START/STOP/UPLOAD/RE-LINK - see
// onSingleClickHandler()/onDoubleClickHandler()/onReconnectHandler()
// below). SCAN is a single toggle button (not separate START/STOP
// buttons) because the underlying firmware model is a toggle, not two
// independent actions - showing two buttons that both call the same
// toggle would be misleading about what actually happens.
static void drawTabMain(bool redrawAll) {
	const int16_t w = tft.width();
	static bool lastScanningActive2 = !scanningActive; // force first paint to differ
	static uint32_t lastWigleCount = 0xFFFFFFFF;
	static uint32_t lastWdgwCount = 0xFFFFFFFF;
	static uint32_t lastBleCount = 0xFFFFFFFF;
	static bool lastSdOk2 = !sdOk;
	static bool lastConfigOk2 = !configOk;
	static LinkState lastLinkState2 = (LinkState)0xFF;
	static String lastUploadTextDrawn = "\x01"; // sentinel, never equals a real status

	const int16_t sysX = HDR_GAP, sysW = 150;
	const int16_t sysY = CONTENT_Y;

	if (redrawAll) {
		drawPanelTitle(sysX, sysY, sysW, "SYSTEMS", COLOR_CYAN);

		// Branding, right of the SYSTEMS panel - the ASCII-art octopus that
		// used to sit above this was mistaken for a rendering artifact
		// (user report, 2026-09-27) and was removed.
		const int16_t artX = sysX + sysW + 6;
		tft.setTextSize(1);
		tft.setTextColor(COLOR_TEXT_DIM, COLOR_BG);
		tft.drawString("-> Nill DECK v2.2", artX, sysY + 2);
		tft.drawString("-> OPERATOR: Nill", artX, sysY + 12);
	}

	LinkState linkState = currentLinkState();
	if (redrawAll || scanningActive != lastScanningActive2) {
		tft.fillRect(sysX, sysY + 18, sysW, 10, COLOR_BG);
		tft.setTextSize(1);
		tft.setTextColor(COLOR_TEXT, COLOR_BG);
		tft.setCursor(sysX, sysY + 18);
		tft.print("WIFI: ");
		tft.setTextColor(scanningActive ? COLOR_CYAN : COLOR_TEXT_DIM, COLOR_BG);
		tft.print(scanningActive ? "SCANNING" : "IDLE");
	}
	if (redrawAll || sdOk != lastSdOk2 || configOk != lastConfigOk2) {
		tft.fillRect(sysX, sysY + 30, sysW, 10, COLOR_BG);
		tft.setTextSize(1);
		tft.setTextColor(COLOR_TEXT, COLOR_BG);
		tft.setCursor(sysX, sysY + 30);
		tft.print("SD: ");
		tft.setTextColor(sdOk ? COLOR_GREEN : COLOR_RED, COLOR_BG);
		tft.print(sdOk ? "MOUNTED" : "FAIL");
		tft.setTextColor(COLOR_TEXT, COLOR_BG);
		tft.print(" CFG: ");
		tft.setTextColor(configOk ? COLOR_GREEN : COLOR_ORANGE, COLOR_BG);
		tft.print(configOk ? "LOADED" : "FAIL");
	}

	const int16_t opsY = sysY + 64;
	if (redrawAll) drawPanelTitle(sysX, opsY, w - HDR_GAP * 2, "OPERATIONS", COLOR_PURPLE);
	if (redrawAll || lastUploadStatusText != lastUploadTextDrawn) {
		tft.fillRect(sysX, opsY + 17, w - HDR_GAP * 2, 10, COLOR_BG);
		tft.setTextSize(1);
		tft.setTextColor(COLOR_TEXT, COLOR_BG);
		tft.setCursor(sysX, opsY + 17);
		tft.print("Upload: ");
		tft.setTextColor(COLOR_ORANGE, COLOR_BG);
		tft.print(lastUploadStatusText.length() > 0 ? lastUploadStatusText : "NEVER");
		lastUploadTextDrawn = lastUploadStatusText;
	}

	// Buttons flush against the very bottom of the screen (user request,
	// 2026-09-27) - computed backward from CONTENT_BOTTOM instead of
	// forward from opsY, so they always sit at the bottom edge regardless
	// of how much (or little) content is above them.
	const int16_t btnW = w - HDR_GAP * 2, btnH = 26, btnGap = 5;
	const int16_t btnAreaH = btnH * 3 + btnGap * 2;
	const int16_t btnTop = CONTENT_BOTTOM - btnAreaH;
	setActionButton(0, sysX, btnTop, btnW, btnH);
	setActionButton(1, sysX, btnTop + btnH + btnGap, btnW, btnH);
	setActionButton(2, sysX, btnTop + (btnH + btnGap) * 2, btnW, btnH);
	actionRectCount = 3;
	if (redrawAll || scanningActive != lastScanningActive2) {
		drawButton(actionRects[0], scanningActive ? "> STOP SCAN" : "> START SCAN", scanningActive ? COLOR_RED : COLOR_CYAN);
	}
	if (redrawAll) drawButton(actionRects[1], "> UPLOAD DATA", COLOR_PURPLE);
	if (redrawAll || linkState != lastLinkState2) {
		// Label/color still track the mesh link's state specifically (the only one of the two
		// with a real 3-state connect/connecting/disconnect cycle to show) - see
		// onReconnectHandler()'s own comment for why the phone side has no equivalent state
		// machine to reflect here.
		drawButton(actionRects[2], "> RE-LINK ALL",
				   linkState == LinkState::Connected ? COLOR_CYAN : linkState == LinkState::Connecting ? COLOR_PURPLE : COLOR_RED);
	}

	// Collection stat box, filling the gap between the OPERATIONS content
	// above and the button stack flush at the bottom (replaces the old
	// single-line WIFI/BLE counter and, before that, the footer ticker -
	// user asked for bigger numbers on a bordered background, matching
	// the WIGLE/WDGW/BT layout from the Wardrive Bridge phone app,
	// 2026-09-27). No CELL column: none of these boards has cellular radio
	// hardware, so a permanent 0 there would only be misleading, not
	// informative - dropped per user request rather than kept for layout
	// parity with the phone app.
	const int16_t boxX = sysX, boxW = w - HDR_GAP * 2;
	const int16_t boxY = opsY + 30, boxBottom = btnTop - 8, boxH = boxBottom - boxY;
	const int16_t colW = boxW / 3;
	// Both counters are "logged and queued this session", not "confirmed
	// delivered" - the app's own WIGLE/WDGW split describes items prepared
	// for each destination, and on this rig both destinations receive every
	// session CSV together (see Uploader::uploadPending()), so there's no
	// real per-destination split to report. WIGLE tracks WiFi APs only,
	// matching the WigleWifi CSV format that's actually WiFi-specific; WDGW
	// (the "everything" gamified gateway) tracks the full WiFi+BLE take.
	uint32_t wigleCount = wifiCountThisRun;
	uint32_t wdgwCount = wifiCountThisRun + bleCountThisRun;

	if (redrawAll) {
		tft.drawRoundRect(boxX, boxY, boxW, boxH, 6, COLOR_TEXT_DIM);
		for (uint8_t i = 1; i < 3; i++) {
			tft.drawFastVLine(boxX + colW * i, boxY + 6, boxH - 12, COLOR_TEXT_DIM);
		}
	}
	if (redrawAll || wigleCount != lastWigleCount || wdgwCount != lastWdgwCount || bleCountThisRun != lastBleCount) {
		struct StatCol {
			uint32_t value;
			const char *label;
			uint16_t color;
		};
		const StatCol cols[3] = {
			{wigleCount, "WIGLE", COLOR_CYAN},
			{wdgwCount, "WDGW", COLOR_PURPLE},
			{bleCountThisRun, "BT", COLOR_GREEN},
		};
		tft.setTextDatum(MC_DATUM);
		for (uint8_t i = 0; i < 3; i++) {
			int16_t cx = boxX + colW * i + colW / 2;
			tft.fillRect(boxX + colW * i + 2, boxY + 2, colW - 4, boxH - 4, COLOR_BG);
			tft.setTextSize(3);
			tft.setTextColor(cols[i].color, COLOR_BG);
			tft.drawString(String(cols[i].value), cx, boxY + boxH / 2 - 8);
			tft.setTextSize(1);
			tft.setTextColor(COLOR_TEXT_DIM, COLOR_BG);
			tft.drawString(cols[i].label, cx, boxY + boxH - 10);
		}
		tft.setTextDatum(TL_DATUM);
	}

	lastScanningActive2 = scanningActive;
	lastWigleCount = wigleCount;
	lastWdgwCount = wdgwCount;
	lastBleCount = bleCountThisRun;
	lastSdOk2 = sdOk;
	lastConfigOk2 = configOk;
	lastLinkState2 = linkState;
}

// ---- TAB 2: TARGETS - recently-seen APs (see ApSighting ring buffer
// above). No "handshake captured" flag or "select target" action - this
// rig does passive WigleWifi-style logging only, it never attempts a
// handshake capture or any per-target action, so those specific spec
// items don't correspond to anything this firmware actually does.
static void drawTabTargets(bool redrawAll) {
	const int16_t w = tft.width();
	static uint32_t lastDrawnVersion = 0xFFFFFFFF;
	if (redrawAll) {
		drawPanelTitle(HDR_GAP, CONTENT_Y, w - HDR_GAP * 2, "DETECTED NETWORKS", COLOR_CYAN);
	}
	if (!redrawAll && apSightingVersion == lastDrawnVersion) return; // nothing new since last paint
	lastDrawnVersion = apSightingVersion;

	const int16_t listY = CONTENT_Y + 18;
	const int16_t rowH = 20;
	tft.fillRect(0, listY, w, rowH * MAX_AP_SIGHTINGS, COLOR_BG);
	tft.setTextSize(1);
	if (apSightingCount == 0) {
		tft.setTextColor(COLOR_TEXT_DIM, COLOR_BG);
		tft.drawString("(no networks seen yet)", HDR_GAP, listY + 4);
	} else {
		for (uint8_t i = 0; i < apSightingCount; i++) {
			// Newest first: apSightingHead points at the next WRITE slot,
			// so the most recent entry is one behind it.
			uint8_t idx = (apSightingHead + MAX_AP_SIGHTINGS - 1 - i) % MAX_AP_SIGHTINGS;
			const ApSighting &s = apSightings[idx];
			int16_t y = listY + i * rowH;
			String name = s.ssid.length() > 0 ? s.ssid : s.bssid;
			if (name.length() > 16) name = name.substring(0, 16);
			tft.setTextColor(COLOR_TEXT, COLOR_BG);
			tft.drawString(name, HDR_GAP, y);
			tft.setTextColor(s.auth.indexOf("OPEN") >= 0 || s.auth == "[ESS]" ? COLOR_ORANGE : COLOR_TEXT_DIM, COLOR_BG);
			tft.drawString(s.auth.length() > 12 ? s.auth.substring(0, 12) : s.auth, HDR_GAP, y + 9);
			tft.setTextColor(COLOR_CYAN, COLOR_BG);
			tft.setTextDatum(TR_DATUM);
			tft.drawString(String(s.rssi) + "dB", w - HDR_GAP, y + 4);
			tft.setTextDatum(TL_DATUM);
		}
	}

	const int16_t btnY = CONTENT_BOTTOM - 26;
	setActionButton(0, HDR_GAP, btnY, w - HDR_GAP * 2, 22);
	actionRectCount = 1;
	if (redrawAll) drawButton(actionRects[0], "> CLEAR", COLOR_PURPLE);
}

// ---- TAB 3: MESH - this rig only ever has ONE wifi_node and ONE
// ble_node, not a cluster of independent RIG-01/02/03 peers (ble_node
// relays through wifi_node's own wired UART link, it has no BLE
// connection of its own to this board - see ble_node/main.cpp's header).
// Shown honestly as what's really there: wifi_node's own BLE link state,
// and ble_node's data flow (via wifi_node) as a second logical row.
static void drawTabLinks(bool redrawAll) {
	const int16_t w = tft.width();
	if (redrawAll) {
		clearContentArea();
		drawPanelTitle(HDR_GAP, CONTENT_Y, w - HDR_GAP * 2, "LINKS", COLOR_PURPLE);
	}
	LinkState linkState = currentLinkState();
	const int16_t rowY = CONTENT_Y + 24;
	tft.fillRect(0, rowY, w, 76, COLOR_BG);
	tft.setTextSize(1);

	tft.setTextColor(COLOR_TEXT, COLOR_BG);
	tft.drawString("RIG  (wired: wifi_node + ble_node)", HDR_GAP, rowY);
	tft.setTextColor(linkState == LinkState::Connected ? COLOR_GREEN : linkState == LinkState::Connecting ? COLOR_ORANGE : COLOR_RED, COLOR_BG);
	tft.drawString(linkState == LinkState::Connected ? "UP" : linkState == LinkState::Connecting ? "WAITING" : "DOWN", HDR_GAP, rowY + 10);
	tft.setTextColor(COLOR_TEXT_DIM, COLOR_BG);
	tft.drawString(String("wifi: ") + wifiCountThisRun + "   ble: " + bleCountThisRun, HDR_GAP, rowY + 20);

	tft.setTextColor(COLOR_TEXT, COLOR_BG);
	tft.drawString("PHONE  (BLE, USB fallback)", HDR_GAP, rowY + 38);
	tft.setTextColor(phoneLinkUp ? COLOR_GREEN : COLOR_RED, COLOR_BG);
	tft.drawString(phoneLinkUp ? (CydBleLink::isConnected() ? "BLE" : "USB") : "DOWN", HDR_GAP, rowY + 48);

	const int16_t btnY = CONTENT_BOTTOM - 26;
	setActionButton(0, HDR_GAP, btnY, w - HDR_GAP * 2, 22);
	actionRectCount = 1;
	if (redrawAll) drawButton(actionRects[0], "> RE-LINK ALL", COLOR_CYAN);
}

// ---- TAB 4: LOGS - scrolling terminal of the same events MAIN's ticker
// shows, kept as history (see LogLine ring buffer above).
static void drawTabLogs(bool redrawAll) {
	const int16_t w = tft.width();
	static uint32_t lastDrawnVersion = 0xFFFFFFFF;
	static bool lastPaused = false;
	if (redrawAll) {
		drawPanelTitle(HDR_GAP, CONTENT_Y, w - HDR_GAP * 2, "LIVE PACKET & EVENT TERMINAL", COLOR_CYAN);
	}
	bool termDirty = redrawAll || logLineVersion != lastDrawnVersion;
	if (termDirty) {
		lastDrawnVersion = logLineVersion;
		const int16_t termY = CONTENT_Y + 18;
		const int16_t lineH = 16;
		tft.fillRect(0, termY, w, lineH * MAX_LOG_LINES, COLOR_BG);
		tft.setTextSize(1);
		if (logLineCount == 0) {
			tft.setTextColor(COLOR_TEXT_DIM, COLOR_BG);
			tft.drawString(logPaused ? "(log paused)" : "(no events yet)", HDR_GAP, termY);
		} else {
			// Oldest-to-newest top-to-bottom, most recent line at the bottom -
			// a real terminal's own scroll direction.
			for (uint8_t i = 0; i < logLineCount; i++) {
				uint8_t idx = (logLineHead + MAX_LOG_LINES - logLineCount + i) % MAX_LOG_LINES;
				tft.setTextColor(logLines[idx].color, COLOR_BG);
				String s = logLines[idx].text;
				if (s.length() > 38) s = s.substring(0, 38);
				tft.drawString(s, HDR_GAP, termY + i * lineH);
			}
		}
	}

	const int16_t btnY = CONTENT_BOTTOM - 26, btnW = (w - HDR_GAP * 3) / 2;
	setActionButton(0, HDR_GAP, btnY, btnW, 22);
	setActionButton(1, HDR_GAP * 2 + btnW, btnY, btnW, 22);
	actionRectCount = 2;
	if (redrawAll || logPaused != lastPaused) {
		drawButton(actionRects[0], logPaused ? "> RESUME LOG" : "> PAUSE LOG", logPaused ? COLOR_TEXT_DIM : COLOR_CYAN, 1);
		lastPaused = logPaused;
	}
	if (redrawAll) drawButton(actionRects[1], "> FLUSH TO SD", COLOR_PURPLE, 1);
}

// ---- TAB 5: CFG - storage/GPS/power/operator info. FORMAT SD is
// implemented as "wipe logged session files" (the closest safe
// equivalent this SD library actually exposes - a real low-level format
// isn't available through Arduino's SD.h); GPS toggle is omitted - GPS
// lives entirely on wifi_node with no on/off control path from here, so
// a button for it here couldn't do anything real.
static void drawTabCfg(bool redrawAll) {
	const int16_t w = tft.width();
	if (redrawAll) {
		clearContentArea();
		drawPanelTitle(HDR_GAP, CONTENT_Y, w - HDR_GAP * 2, "SYSTEM CONFIG & STORAGE", COLOR_PURPLE);
	}
	const int16_t rowY = CONTENT_Y + 20;
	tft.fillRect(0, rowY, w, 104, COLOR_BG);
	tft.setTextSize(1);
	tft.setTextColor(COLOR_TEXT, COLOR_BG);
	tft.setCursor(HDR_GAP, rowY);
	if (sdOk) {
		double usedGB = SD.usedBytes() / 1073741824.0;
		double totalGB = SD.totalBytes() / 1073741824.0;
		tft.printf("SD: %.1f / %.1f GB used", usedGB, totalGB);
	} else {
		tft.print("SD: not mounted");
	}
	tft.setCursor(HDR_GAP, rowY + 14);
	tft.print("GPS: GY-GPS6MV2 @ 9600 baud");
	tft.setCursor(HDR_GAP, rowY + 28);
	if (lastKnownSatCount >= 0) tft.printf("SATS: %d visible (via wifi_node)", lastKnownSatCount);
	else tft.print("SATS: -- (no fix yet)");
	tft.setCursor(HDR_GAP, rowY + 42);
	tft.print("PWR: Hardwired 5V (stable)");
	tft.setCursor(HDR_GAP, rowY + 56);
	tft.print("OPERATOR: Nill");
	tft.setCursor(HDR_GAP, rowY + 70);
	tft.print("FIRMWARE: Nill DECK v2.2");

	const int16_t btnY = CONTENT_BOTTOM - 26, btnW = (w - HDR_GAP * 3) / 2;
	setActionButton(0, HDR_GAP, btnY, btnW, 22);
	setActionButton(1, HDR_GAP * 2 + btnW, btnY, btnW, 22);
	actionRectCount = 2;
	if (redrawAll) {
		drawButton(actionRects[0], "> WIPE LOGS", COLOR_ORANGE, 1);
		drawButton(actionRects[1], "> REBOOT", COLOR_RED);
	}
}

static void drawStatus() {
	static bool firstDraw = true;
	if (forceFullRedraw) {
		firstDraw = true;
		forceFullRedraw = false;
	}
	static Tab lastDrawnContentTab = Tab::Main;
	bool tabChanged = currentTab != lastDrawnContentTab;
	bool redrawAll = firstDraw || tabChanged;

	drawHeader(firstDraw);
	if (redrawAll) clearContentArea();

	switch (currentTab) {
		case Tab::Main: drawTabMain(redrawAll); break;
		case Tab::Targets: drawTabTargets(redrawAll); break;
		case Tab::Links: drawTabLinks(redrawAll); break;
		case Tab::Logs: drawTabLogs(redrawAll); break;
		case Tab::Cfg: drawTabCfg(redrawAll); break;
	}

	lastDrawnContentTab = currentTab;
	firstDraw = false;
}

static const char *uploadResultStr(UploadResult r) {
	switch (r) {
		case UploadResult::Ok: return "Ok";
		case UploadResult::WifiFailed: return "WifiFailed";
		case UploadResult::UploadFailed: return "UploadFailed";
		default: return "Skipped";
	}
}

static void checkStorage() {
	if (!sdOk) return;
	uint64_t freeBytes = SD.totalBytes() - SD.usedBytes();
	static const uint64_t LOW_STORAGE_THRESHOLD_BYTES = 100UL * 1024 * 1024; // 100MB
	if (freeBytes < LOW_STORAGE_THRESHOLD_BYTES) {
		flashLed(true, true, true, LED_FLICKER_MS, true);
		wifiLinkSend("LOWSTORAGE");
		if (WARDRIVE_DEBUG) Serial.printf("[storage] low: %llu bytes free\n", (unsigned long long)freeBytes);
	}
}

// Gates dock-mode auto-upload on GPS position (relayed from wifi_node, see
// GPSPOS handling below), not just WiFi visibility. Returns true (no
// gating) if the geofence isn't configured.
static bool nearHome() {
	if (config.homeRadiusM <= 0.0) return true;
	if (!gpsFixKnown) return false;

	// TinyGPSPlus::distanceBetween is a static helper with no GPS-instance
	// dependency, safe to call without a TinyGPSPlus object of our own -
	// reimplemented here via the haversine formula directly instead of
	// pulling in the whole TinyGPS++ library just for this one function.
	static const double R_EARTH_M = 6371000.0;
	double dLat = radians(config.homeLat - lastKnownLat);
	double dLon = radians(config.homeLon - lastKnownLon);
	double a = sin(dLat / 2) * sin(dLat / 2) +
			   cos(radians(lastKnownLat)) * cos(radians(config.homeLat)) * sin(dLon / 2) * sin(dLon / 2);
	double distanceM = R_EARTH_M * 2 * atan2(sqrt(a), sqrt(1 - a));
	return distanceM <= config.homeRadiusM;
}

static bool tryRecoverSd() {
	SD.end();
	bool ok = SD.begin(PIN_SD_CS, sdSPI);
	if (ok && SD.cardType() == CARD_NONE) ok = false;
	return ok;
}

volatile bool sdTaskDone = false;

static void sdInitTask(void *) {
	sdSPI.begin(PIN_SD_SCK, PIN_SD_MISO, PIN_SD_MOSI, PIN_SD_CS);
	sdOk = SD.begin(PIN_SD_CS, sdSPI);
	if (sdOk && SD.cardType() == CARD_NONE) sdOk = false;

	configOk = sdOk && loadWardriveConfig("/config.cfg", config);
	uploader = new Uploader(config);

	sdTaskDone = true;
	vTaskDelete(nullptr);
}

// ---- This board's own WiFi sniffer (see file header for why) ----
// Copied from wifi_node/main.cpp's own implementation - same MCU family,
// same esp_wifi APIs, same parsing needs. Kept as its own copy rather than
// factored into WardriveShared since the two boards' surrounding structure
// (queue draining, dedup, GPS handling) differs enough that a shared
// helper would need as many board-specific hooks as it'd save.
enum : uint8_t {
	CYD_AUTH_OPEN = 0,
	CYD_AUTH_WEP = 1,
	CYD_AUTH_WPA_PSK = 2,
	CYD_AUTH_WPA2_PSK = 3,
	CYD_AUTH_WPA2_ENTERPRISE = 4,
	CYD_AUTH_WPA3_SAE = 5,
	CYD_AUTH_WPA3_ENTERPRISE = 6,
	CYD_AUTH_OWE = 7,
	CYD_AUTH_WPA23_TRANSITIONAL = 8,
};

struct CydWifiObservation {
	uint8_t bssid[6];
	char ssid[33];
	uint8_t authMode;
	bool pmfCapable;
	bool pmfRequired;
	int8_t rssi;
	uint8_t channel;
};

static QueueHandle_t cydObsQueue;
// Channel split with wifi_node (see its own header for the full
// reasoning): wifi_node sweeps 1-6, this board sweeps 6-11 (6 shared on
// purpose - it's one of the three classic non-overlapping channels along
// with 1 and 11, and carries a disproportionate share of real-world APs).
// Splitting the 11-channel band in half rather than both boards cycling
// the full range (even with a phase offset) roughly halves each board's
// own full-cycle time at the same per-channel dwell, nearly doubling how
// often either one revisits any given one of its own channels - the
// actual lever for catching briefly-in-range APs faster. Together the two
// boards still cover the complete 1-11 range at every instant.
static const uint8_t CYD_CHANNEL_MIN = 6;
static const uint8_t CYD_CHANNEL_MAX = 11;
uint8_t cydCurrentChannel = CYD_CHANNEL_MIN;
uint32_t cydLastChannelHopMs = 0;

static const char *cydAuthModeBaseStr(uint8_t mode) {
	switch (mode) {
		case CYD_AUTH_WEP: return "[WEP]";
		case CYD_AUTH_WPA_PSK: return "[WPA-PSK]";
		case CYD_AUTH_WPA2_PSK: return "[WPA2-PSK]";
		case CYD_AUTH_WPA2_ENTERPRISE: return "[WPA2-EAP]";
		case CYD_AUTH_WPA3_SAE: return "[WPA3-SAE]";
		case CYD_AUTH_WPA3_ENTERPRISE: return "[WPA3-EAP]";
		case CYD_AUTH_OWE: return "[OWE]";
		case CYD_AUTH_WPA23_TRANSITIONAL: return "[WPA2-PSK][WPA3-SAE]";
		default: return "";
	}
}

static String cydAuthModeStr(uint8_t mode, bool pmfCapable, bool pmfRequired) {
	String s = cydAuthModeBaseStr(mode);
	if (pmfRequired) s += "[MFPR]";
	else if (pmfCapable) s += "[MFPC]";
	s += "[ESS]";
	return s;
}

struct CydRsnInfo {
	bool akmEnterprise = false;
	bool akmPsk = false;
	bool akmSae = false;
	bool akmSuiteB = false;
	bool akmOwe = false;
	bool mfpCapable = false;
	bool mfpRequired = false;
};

static void cydClassifyAkmSuite(uint8_t suiteType, CydRsnInfo &info) {
	switch (suiteType) {
		case 1: case 3: case 5: info.akmEnterprise = true; break;
		case 2: case 4: case 6: info.akmPsk = true; break;
		case 8: case 9: case 24: info.akmSae = true; break;
		case 11: case 12: info.akmSuiteB = true; break;
		case 18: info.akmOwe = true; break;
		default: break;
	}
}

static CydRsnInfo cydParseRsnIe(const uint8_t *data, uint8_t len) {
	CydRsnInfo info;
	int idx = 2;
	if (idx + 4 > len) return info;
	idx += 4;
	if (idx + 2 > len) return info;
	uint16_t pairwiseCount = data[idx] | (data[idx + 1] << 8);
	idx += 2 + 4 * pairwiseCount;
	if (idx + 2 > len) return info;
	uint16_t akmCount = data[idx] | (data[idx + 1] << 8);
	idx += 2;
	for (uint16_t i = 0; i < akmCount && idx + 4 <= len; i++) {
		cydClassifyAkmSuite(data[idx + 3], info);
		idx += 4;
	}
	if (idx + 2 <= len) {
		uint16_t rsnCap = data[idx] | (data[idx + 1] << 8);
		info.mfpRequired = (rsnCap >> 6) & 1;
		info.mfpCapable = (rsnCap >> 7) & 1;
	}
	return info;
}

static uint8_t cydClassifyAuthMode(const CydRsnInfo &rsn, bool hasWpaVendor, bool privacy) {
	if (rsn.akmOwe) return CYD_AUTH_OWE;
	if (rsn.akmSae && rsn.akmPsk) return CYD_AUTH_WPA23_TRANSITIONAL;
	if (rsn.akmSae) return CYD_AUTH_WPA3_SAE;
	if (rsn.akmSuiteB) return CYD_AUTH_WPA3_ENTERPRISE;
	if (rsn.akmEnterprise) return CYD_AUTH_WPA2_ENTERPRISE;
	if (rsn.akmPsk) return CYD_AUTH_WPA2_PSK;
	if (hasWpaVendor) return CYD_AUTH_WPA_PSK;
	if (privacy) return CYD_AUTH_WEP;
	return CYD_AUTH_OPEN;
}

void IRAM_ATTR cydWifiSnifferCallback(void *buf, wifi_promiscuous_pkt_type_t type) {
	if (type != WIFI_PKT_MGMT) return;

	wifi_promiscuous_pkt_t *pkt = (wifi_promiscuous_pkt_t *)buf;
	const uint8_t *payload = pkt->payload;
	int len = pkt->rx_ctrl.sig_len;
	if (len < 36) return;

	uint8_t frameType = (payload[0] >> 2) & 0x3;
	uint8_t frameSubtype = (payload[0] >> 4) & 0xF;
	if (frameType != 0) return;
	if (frameSubtype != 8 && frameSubtype != 5) return;

	CydWifiObservation obs = {};
	memcpy(obs.bssid, payload + 16, 6);
	obs.rssi = pkt->rx_ctrl.rssi;
	obs.channel = pkt->rx_ctrl.channel;

	uint16_t capInfo = payload[24 + 8] | (payload[24 + 9] << 8);
	bool privacy = capInfo & 0x0010;

	int idx = 24 + 12;
	bool hasWpaVendor = false;
	CydRsnInfo rsn;
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
			rsn = cydParseRsnIe(payload + dataStart, tagLen);
		} else if (tagNum == 221 && tagLen >= 4 &&
				   payload[dataStart] == 0x00 && payload[dataStart + 1] == 0x50 &&
				   payload[dataStart + 2] == 0xF2 && payload[dataStart + 3] == 0x01) {
			hasWpaVendor = true;
		}

		idx = dataStart + tagLen;
	}

	obs.authMode = cydClassifyAuthMode(rsn, hasWpaVendor, privacy);
	obs.pmfCapable = rsn.mfpCapable;
	obs.pmfRequired = rsn.mfpRequired;

	xQueueSend(cydObsQueue, &obs, 0);
}

static int cydChannelToFreqMHz(uint8_t channel) {
	if (channel >= 1 && channel <= 13) return 2407 + channel * 5;
	if (channel == 14) return 2484;
	return 0;
}

static String cydMacToString(const uint8_t *mac) {
	char buf[18];
	snprintf(buf, sizeof(buf), "%02X:%02X:%02X:%02X:%02X:%02X",
			 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
	return String(buf);
}

static String cydIsoTimestampFromEpoch(uint32_t epoch) {
	if (epoch == 0) return "1970-01-01 00:00:00";
	time_t t = (time_t)epoch;
	struct tm tmVal;
	gmtime_r(&t, &tmVal);
	char buf[24];
	snprintf(buf, sizeof(buf), "%04d-%02d-%02d %02d:%02d:%02d",
			 tmVal.tm_year + 1900, tmVal.tm_mon + 1, tmVal.tm_mday,
			 tmVal.tm_hour, tmVal.tm_min, tmVal.tm_sec);
	return String(buf);
}

// SHARED dedup across BOTH WiFi sources that land in this board's one
// WigleWifi CSV: this board's own sniffer AND wifi_node's relayed "W,"
// lines. Originally this only covered this board's own sniffer, on the
// assumption that the same BSSID seen by both boards was two legitimately
// separate vantage points - but both sources write into the SAME session
// file, and in practice the two boards are a few inches apart in the same
// vehicle, so "two vantage points" was really just "the same AP logged
// twice" (found via testing, 2026-09-27, when asked to make sure
// duplicates were being filtered). One shared map, keyed by BSSID
// regardless of which board reported it, fixes this.
static const double CYD_AP_DEDUP_MOVEMENT_THRESHOLD_M = 40.0;
struct CydApDedupEntry {
	uint32_t lastLoggedMs;
	double lat, lon;
};
std::unordered_map<uint64_t, CydApDedupEntry> cydApDedupState;

static uint64_t cydMacToKey(const uint8_t *mac) {
	uint64_t key = 0;
	for (int i = 0; i < 6; i++) key = (key << 8) | mac[i];
	return key;
}

// wifi_node's relayed "W," lines carry the BSSID as "AA:BB:CC:DD:EE:FF"
// text, not raw bytes - this parses it into the SAME key space cydMacToKey()
// produces from a byte array, so both sources dedupe against one map.
static uint64_t cydMacKeyFromString(const String &mac) {
	uint64_t key = 0;
	int idx = 0;
	for (int i = 0; i < 6 && idx + 1 < (int)mac.length(); i++) {
		key = (key << 8) | (uint8_t)strtoul(mac.substring(idx, idx + 2).c_str(), nullptr, 16);
		idx += 3; // 2 hex chars + the ':' separator
	}
	return key;
}

static bool cydShouldLogApByKey(uint64_t key, double lat, double lon) {
	auto it = cydApDedupState.find(key);
	if (it != cydApDedupState.end()) {
		double dLat = (lat - it->second.lat) * 111320.0;
		double dLon = (lon - it->second.lon) * 111320.0 * cos(lat * PI / 180.0);
		double movedM = sqrt(dLat * dLat + dLon * dLon);
		if (movedM < CYD_AP_DEDUP_MOVEMENT_THRESHOLD_M) return false;
	}
	cydApDedupState[key] = {millis(), lat, lon};
	return true;
}

static bool cydShouldLogAp(const uint8_t *mac, double lat, double lon) {
	return cydShouldLogApByKey(cydMacToKey(mac), lat, lon);
}

static bool startScanning() {
	wifiCountThisRun = 0;
	bleCountThisRun = 0;
	bool wifiFileOk = wigleWifi.begin(SESSION_DIR, "wifi");
	bool bleFileOk = wigleBle.begin(SESSION_DIR, "ble");
	cydApDedupState.clear();
	esp_wifi_set_promiscuous(true);
	cydCurrentChannel = CYD_CHANNEL_MIN;
	esp_wifi_set_channel(cydCurrentChannel, WIFI_SECOND_CHAN_NONE);
	cydLastChannelHopMs = millis();
	return wifiFileOk && bleFileOk;
}

static void stopScanning() {
	esp_wifi_set_promiscuous(false);
	wigleWifi.flush();
	wigleWifi.close();
	wigleBle.flush();
	wigleBle.close();
}

static UploadResult doUpload(bool force) {
	if (!uploader) {
		if (WARDRIVE_DEBUG) Serial.println("[upload] skipped - SD/config not ready yet");
		if (force) {
			flashLed(true, false, false, LED_RESULT_MS, true);
			wifiLinkSend("FAIL");
		}
		return UploadResult::Skipped;
	}

	bool restartScanning = scanningActive;
	if (restartScanning) {
		stopScanning();
		scanningActive = false;
	}

	if (force) {
		startUploadBlink();
		wifiLinkSend("START");
	}

	// Frees NimBLE's own heap for the TLS handshake below - see
	// CydBleLink::suspendServer()'s own comment for why this was needed
	// (a real "BIGNUM - Memory allocation failed" error uploading to
	// WiGLE, traced to heap pressure). The wired wifi_node link is
	// unaffected; a phone connected over BLE drops for the upload and its
	// app reconnects on its own once resumeServer() re-advertises (a
	// USB-connected phone never notices).
	CydBleLink::suspendServer();
	UploadResult result = uploader->uploadPending(SESSION_DIR, lastKnownEpoch, force, onUploadProgress);
	CydBleLink::resumeServer();

	if (WARDRIVE_DEBUG) {
		Serial.printf("[upload] force=%d nowEpoch=%lu result=%s\n",
					  force, (unsigned long)lastKnownEpoch, uploadResultStr(result));
	}
	lastUploadStatusText = uploadResultStr(result);

	if (force) {
		stopUploadBlink();
		if (result == UploadResult::Ok) {
			flashLed(false, true, false, LED_RESULT_MS, true);
			wifiLinkSend("OK");
		} else {
			flashLed(true, false, false, LED_RESULT_MS, true);
			wifiLinkSend("FAIL");
		}
	}

	if (restartScanning) {
		scanningActive = true;
		startScanning();
	}
	return result;
}

// Shared by the touch START/STOP button (toggle) and the phone's own
// "scan start"/"scan stop" wdstream commands (absolute set) - both are a
// genuine local decision (as opposed to adopting an incoming BLE resync),
// so both need this board's one broadcast-immediately path, not just the
// next periodic tick. Without this, changing scanningActive only ever
// updated this board's own screen - wifi_node (and, relayed through it,
// ble_node) never learned about it at all until this board's own next
// periodic broadcast happened to exist.
static void setScanning(bool want) {
	scanningActive = want;
	lastLocalScanSetMs = millis();
	statePrefs.putBool(STATE_PREFS_KEY, scanningActive);
	flashLed(false, true, false, LED_FLICKER_MS, true);
	char line[16];
	snprintf(line, sizeof(line), "SCANSTATE:%d", scanningActive ? 1 : 0);
	wifiLinkSend(line);
	phonePrintf("WD:SCANSTATE:%d", scanningActive ? 1 : 0); // phone app mirror
}

void onSingleClickHandler() {
	setScanning(!scanningActive);
	if (WARDRIVE_DEBUG) Serial.printf("[touch] toggle -> scanning=%d (saved, broadcast)\n", scanningActive);
}

void onDoubleClickHandler() {
	uploadRequested = true;
	flashLed(false, false, true, LED_FLICKER_MS, true);
	if (WARDRIVE_DEBUG) Serial.println("[touch] upload requested");
}

extern uint32_t lastSdHealthBroadcastMs;
extern uint32_t lastCfgBroadcastMs;
extern uint32_t lastScanStateBroadcastMs;

// RE-LINK kicks every link this board has:
//
// 1. The wired link to wifi_node - a wire can't be "reconnected", so this
//    re-sends SDOK/CFG/SCANSTATE on the very next loop() instead of waiting
//    out their periodic timers. CONNECTING only shows if the link is
//    actually down right now; it clears the moment wifi_node's next line
//    arrives.
//
// 2. The phone's BLE link - restarts the BLE stack (same suspend/resume
//    doUpload() uses, see CydBleLink.h), dropping any stuck connection so
//    the phone app reconnects fresh.
//
// 3. Re-announces the wdstream handshake over BLE and USB, for a phone that
//    missed the original. It doesn't set the PHONE indicator itself - that
//    only goes UP once the phone is really there.
void onReconnectHandler() {
	flashLed(true, false, true, LED_FLICKER_MS, true); // purple - distinct from the other two actions' colors
	if (WARDRIVE_DEBUG) Serial.println("[touch] link reconnect requested (wifi_node + phone)");
	if (currentLinkState() != LinkState::Connected) linkReconnecting = true;
	lastSdHealthBroadcastMs = 0;
	lastCfgBroadcastMs = 0;
	lastScanStateBroadcastMs = 0;
	CydBleLink::suspendServer();
	CydBleLink::resumeServer();
	handleWdstreamCommand("wdstream start");
}

// TARGETS tab's CLEAR button - only clears this board's in-RAM display
// buffer, never touches the permanent WigleWifi CSV already on SD.
void onClearTargetsHandler() {
	apSightingCount = 0;
	apSightingHead = 0;
	forceFullRedraw = true;
	if (WARDRIVE_DEBUG) Serial.println("[touch] targets list cleared");
}

// LOGS tab's PAUSE/RESUME button - only stops new entries from being
// appended to the in-RAM ring buffer; SD logging is completely unaffected
// either way (see LogLine's own comment above).
void onPauseLogHandler() {
	logPaused = !logPaused;
	forceFullRedraw = true;
	if (WARDRIVE_DEBUG) Serial.printf("[touch] log %s\n", logPaused ? "paused" : "resumed");
}

// LOGS tab's FLUSH TO SD button - WigleWriter already flushes on every
// observation (see its own header), so this is mostly a reassurance
// action, but it's a real no-op-if-nothing-pending flush, not fake.
void onFlushSdHandler() {
	wigleWifi.flush();
	wigleBle.flush();
	flashLed(false, true, false, LED_FLICKER_MS, true);
	if (WARDRIVE_DEBUG) Serial.println("[touch] forced flush to SD");
}

// CFG tab's WIPE LOGS button - deletes every file in SESSION_DIR. Gated
// on !scanningActive so it can never delete a file WigleWriter currently
// has open (which could otherwise corrupt the in-progress session) - see
// this tab's own header comment for why this isn't a real SD format.
static void wipeAllLogs() {
	// Two passes on purpose - removing a file from the directory WHILE
	// still iterating it with the same openNextFile() handle hung this
	// board indefinitely in testing (2026-09-27): FAT directory entries
	// shift on delete, and openNextFile() has no way to know its own
	// position needs adjusting, so it can spin re-visiting/never advancing
	// past a shifted entry. Names are collected into a fixed-size array
	// first, the directory handle is fully closed, and only then are the
	// files actually removed by name - no iterator is ever alive at the
	// same time as a delete.
	static const uint8_t MAX_WIPE_FILES = 64; // generous - this session dir realistically never holds more
	String names[MAX_WIPE_FILES];
	uint8_t count = 0;

	File dir = SD.open(SESSION_DIR);
	if (!dir) return;
	File entry = dir.openNextFile();
	while (entry && count < MAX_WIPE_FILES) {
		if (!entry.isDirectory()) names[count++] = String(entry.name());
		entry.close();
		entry = dir.openNextFile();
	}
	if (entry) entry.close();
	dir.close();

	for (uint8_t i = 0; i < count; i++) {
		SD.remove(String(SESSION_DIR) + "/" + names[i]);
	}
}

void onWipeLogsHandler() {
	if (scanningActive) {
		if (WARDRIVE_DEBUG) Serial.println("[touch] wipe logs refused - scanning is active");
		flashLed(true, false, false, LED_RESULT_MS, true);
		return;
	}
	wipeAllLogs();
	flashLed(false, true, false, LED_RESULT_MS, true);
	if (WARDRIVE_DEBUG) Serial.println("[touch] all session logs wiped");
}

void onRebootHandler() {
	if (WARDRIVE_DEBUG) Serial.println("[touch] reboot requested");
	delay(200); // let the debug line actually reach the serial monitor before the reset
	ESP.restart();
}

// ---- WardriveGo "wdstream" compatibility ----
// WardriveGo's "Cerberus" mode (custom rigs) reads this exact line protocol
// over USB serial - reverse-engineered from GhostESP's own firmware source
// (main/core/commands/cmd_wdstream.c), since that's the actual format the
// phone-side app parses. This runs alongside the normal WigleWifi CSV/
// upload pipeline, never replacing it - "wdstream start" from the phone
// just turns on a live mirror of scan activity over the same USB port
// already used for debug prints; WardriveGo (like GhostESP's own console)
// is expected to pick out only the "WD:"-prefixed lines and ignore
// everything else. This board is the one the phone plugs into (see file
// header for why), so this mirrors data that arrives here already parsed
// out of wifi_node's "W,"/"B," lines, rather than raw radio hits - a couple
// hundred ms of extra latency versus sniffing at the source, not otherwise
// user-visible.
bool wdstreamActive = false;
uint32_t wdstreamApCount = 0;
uint32_t wdstreamBleCount = 0;
uint32_t wdstreamStartMs = 0;
uint32_t lastWdstreamStatusMs = 0;
static const uint32_t WDSTREAM_STATUS_INTERVAL_MS = 2000;
String wdstreamLineBuf;

// Over BLE the connection itself says whether the phone is there (see
// checkPhoneLinkTimeout()). Over USB there's no such signal, so the app sends
// "wdstream status" every few seconds as a keep-alive, and any complete line
// counts as proof a phone is present. Only a connect flash, not
// a distinct disconnect one - this board's non-addressable LED only has 8
// on/off combinations, and every other one is already claimed by an
// existing signal (see the color table in README.md); yellow (R+G) is the
// only one left.
static const uint32_t PHONE_LINK_TIMEOUT_MS = 12000; // a bit over 2x the phone's 5s keep-alive
uint32_t lastPhoneCommandMs = 0;
// phoneLinkUp itself is declared up near the other status globals (see its comment there) so
// drawHeader()'s PHONE indicator can read it.

static void notePhoneCommandReceived() {
	lastPhoneCommandMs = millis();
	if (phoneLinkUp) return;
	phoneLinkUp = true;
	flashLed(true, true, false, LED_RESULT_MS, true);
	if (WARDRIVE_DEBUG) Serial.println("[phone-link] connected");
}

static void checkPhoneLinkTimeout() {
	// A live BLE connection is proof on its own - the app only sends commands
	// when it has something to say, so command recency alone reads a quiet
	// but healthy link as DOWN.
	if (CydBleLink::isConnected()) {
		notePhoneCommandReceived();
		return;
	}
	if (!phoneLinkUp) return;
	if (millis() - lastPhoneCommandMs <= PHONE_LINK_TIMEOUT_MS) return;
	phoneLinkUp = false;
	if (WARDRIVE_DEBUG) Serial.println("[phone-link] disconnected (no commands in 12s)");
}

static String hexEncode(const uint8_t *data, size_t len) {
	static const char hexChars[] = "0123456789abcdef";
	String out;
	out.reserve(len * 2);
	for (size_t i = 0; i < len; i++) {
		out += hexChars[(data[i] >> 4) & 0x0F];
		out += hexChars[data[i] & 0x0F];
	}
	return out;
}

// WardriveGo/GhostESP's reverse-engineered wdstream protocol only ever
// defined these four tokens. Classified from the already-formatted
// bracketed authMode string wifi_node sends (e.g. "[WPA2-PSK][MFPC][ESS]")
// rather than a numeric enum - this board never sees wifi_node's internal
// AUTH_* classification, only its string rendering (see wifi_node's
// authModeStr()) - so WPA2-family variants (PSK/Enterprise/transitional)
// all still collapse to plain "WPA2" here for compatibility, same as
// wifi_node's own old mapping did.
static const char *wdstreamAuthToken(const String &authMode) {
	if (authMode.indexOf("WPA3") >= 0) return "WPA3";
	if (authMode.indexOf("OWE") >= 0) return "OWE";
	if (authMode.indexOf("WPA2") >= 0) return "WPA2";
	if (authMode.indexOf("WPA-PSK") >= 0) return "WPA";
	if (authMode.indexOf("WEP") >= 0) return "WEP";
	return "OPEN";
}

static void wdstreamEmitStatus() {
	uint32_t uptimeS = (millis() - wdstreamStartMs) / 1000;
	phonePrintf("WD:STATUS aps=%lu bles=%lu ch=%u uptime=%lum%02lus",
				  (unsigned long)wdstreamApCount, (unsigned long)wdstreamBleCount,
				  (unsigned)lastKnownChannel, (unsigned long)(uptimeS / 60), (unsigned long)(uptimeS % 60));
}

static void handleWdstreamCommand(String line) {
	line.trim();
	if (line == "wdstream start" || line.startsWith("wdstream start ")) {
		// Only reset on a genuine fresh start, not a repeat while already
		// streaming - some wdstream clients (the comment above this function
		// mentions WardriveGo/GhostESP) resend "wdstream start" periodically
		// as a keepalive rather than a strictly one-shot handshake. Treating
		// every repeat as a hard reset wiped this board's own aps/bles
		// counters and uptime every time one arrived - confirmed for real:
		// our own phone app used to do exactly that every 5s, and this
		// board's uptime never climbed past ~4s for the whole run as a
		// result (fixed phone-side too, 2026-09-28). Still re-announcing
		// WD:BEGIN below costs nothing and satisfies a client that's
		// re-establishing after missing the original one.
		if (!wdstreamActive) {
			wdstreamActive = true;
			wdstreamApCount = 0;
			wdstreamBleCount = 0;
			wdstreamStartMs = millis();
			lastWdstreamStatusMs = millis();
		}
		phonePrintf("WD:BEGIN type=wifi_ble interval=2000 channel=auto");
		// Snapshot of this board's own mesh link to wifi_node, for the phone app's "is the rig
		// actually linked together" indicator - see drawHeader()'s matching mirror for why a
		// snapshot here too (not just on-change there) matters: a phone that just (re)connected
		// wouldn't otherwise learn the current state until the mesh link happens to change next,
		// which could be a long time on an otherwise-stable link.
		phonePrintf("WD:MESHLINK:%d", (int)currentLinkState());
	} else if (line == "wdstream stop") {
		wdstreamActive = false;
		phonePrintf("WD:END reason=stop");
	} else if (line == "wdstream status") {
		wdstreamEmitStatus();
	} else if (line == "scan start") {
		// Lets the phone app's own Start button drive the rig's actual
		// scanning, not just subscribe to whatever it's already doing -
		// same effect as touching START/STOP here, just triggered remotely.
		setScanning(true);
		if (WARDRIVE_DEBUG) Serial.println("[wdstream] remote start requested");
	} else if (line == "scan stop") {
		setScanning(false);
		if (WARDRIVE_DEBUG) Serial.println("[wdstream] remote stop requested");
	} else if (line == "test:upload") {
		// Dev/test hook - exercises the exact same path a real UPLOAD tap
		// does, so the whole button can be tested over USB Serial without
		// physically touching the screen.
		onDoubleClickHandler();
		if (WARDRIVE_DEBUG) Serial.println("[test] upload button simulated");
	} else if (line == "test:link") {
		onReconnectHandler();
		if (WARDRIVE_DEBUG) Serial.println("[test] link button simulated");
	} else if (line == "test:clear") {
		onClearTargetsHandler();
		if (WARDRIVE_DEBUG) Serial.println("[test] targets clear simulated");
	} else if (line == "test:pause") {
		onPauseLogHandler();
		if (WARDRIVE_DEBUG) Serial.println("[test] log pause toggle simulated");
	} else if (line == "test:flush") {
		onFlushSdHandler();
		if (WARDRIVE_DEBUG) Serial.println("[test] flush-to-SD simulated");
	} else if (line == "test:wipe") {
		onWipeLogsHandler();
		if (WARDRIVE_DEBUG) Serial.println("[test] wipe-logs simulated");
	} else if (line.startsWith("test:tab ")) {
		// "test:tab N" (0-4) switches tabs, exercising the same path a tap
		// on the tab nav bar does - lets the whole 5-tab UI be tested over
		// USB Serial too, not just MAIN's 3 original buttons.
		int n = line.substring(9).toInt();
		if (n >= 0 && n <= 4) {
			currentTab = (Tab)n;
			forceFullRedraw = true;
			Serial.printf("[test] switched to tab %d (%s)\n", n, TAB_LABELS[n]);
		}
	}
}

// This board has a single BLE connection, to wifi_node (see CydBleLink.h
// and wifi_node/main.cpp's own header) - wifi_node sends its own (already
// GPS-tagged) WiFi observations, plus every BLE observation ble_node
// relayed to it over their own wired UART link (also already GPS-tagged by
// wifi_node before being relayed onward here), plus periodic SCANSTATE/
// EPOCH/GPSPOS/CH broadcasts (this board has no GPS radio of its own to
// derive any of those from directly).
//
// Line shapes (comma-separated, matching WigleWriter's own field order):
//   W,<bssid>,<ssid>,<authMode>,<isoTimestamp>,<channel>,<freqMHz>,<rssi>,<lat>,<lon>,<alt>,<acc>
//   B,<mac>,<name>,<isoTimestamp>,<rssi>,<lat>,<lon>,<alt>,<acc>
//   SCANSTATE:<0|1>
//   EPOCH:<unix seconds>
//   GPSPOS:<0|1>,<lat>,<lon>
//   CH:<wifi channel 1-11>
static void handleIncomingLine(const String &line) {

	if (line.startsWith("CH:")) {
		lastKnownChannel = (uint8_t)line.substring(3).toInt();
		return;
	}
	if (line.startsWith("SCANSTATE:")) {
		bool v = line.substring(10).toInt() != 0;
		// Bug found via live BLE testing (2026-09-26): this used to gate the
		// scanningActive resync behind "did the last-seen wifi_node state
		// change from the previous SCANSTATE line", not "does scanningActive
		// actually disagree with v" - if the first SCANSTATE line ever
		// received happened to match that cached default (false) while
		// scanningActive was already true (e.g. resumed from a power-loss
		// save), the resync never ran at all, silently leaving the two
		// permanently out of sync. The comparison below is now purely
		// v-vs-scanningActive, independent of any cached previous state.
		// Ignore a disagreeing echo for a short window after OUR OWN local
		// setScanning() call (touch toggle or phone "scan start"/"scan
		// stop") - wifi_node's SCAN_SYNC_BROADCAST_MS periodic broadcast
		// (2s) can have already queued a STALE state moments before it
		// actually processes the change we just sent it, and that stale
		// echo arriving here would otherwise silently stomp the local
		// change back before wifi_node's own fresh confirmation catches up
		// (found via testing 2026-09-27: "scan start" right after a LINK
		// reconnect reliably got reverted to scanning=0 within one loop
		// iteration, before the next heartbeat could even show it as ever
		// having been 1). A resync we did NOT just cause locally - e.g. the
		// legitimate case of adopting wifi_node's true state right after
		// this board's own reboot or a fresh reconnect - still applies
		// immediately, since lastLocalScanSetMs is stale/zero then.
		static const uint32_t LOCAL_SCAN_SET_GRACE_MS = 2500;
		if (millis() - lastLocalScanSetMs < LOCAL_SCAN_SET_GRACE_MS) return;
		if (v != scanningActive) {
			scanningActive = v;
			statePrefs.putBool(STATE_PREFS_KEY, scanningActive);
			flashLed(false, true, false, LED_FLICKER_MS, true);
			phonePrintf("WD:SCANSTATE:%d", scanningActive ? 1 : 0); // phone app mirror
			if (WARDRIVE_DEBUG) Serial.printf("[link] resynced to scanning=%d from wifi_node\n", scanningActive);
		}
		return;
	}
	if (line.startsWith("EPOCH:")) {
		lastKnownEpoch = (uint32_t)line.substring(6).toInt();
		return;
	}
	if (line.startsWith("GPSPOS:")) {
		String rest = line.substring(7);
		int c1 = rest.indexOf(',');
		int c2 = rest.indexOf(',', c1 + 1);
		if (c1 < 0 || c2 < 0) return;
		gpsFixKnown = rest.substring(0, c1).toInt() != 0;
		if (gpsFixKnown) {
			lastKnownLat = rest.substring(c1 + 1, c2).toDouble();
			lastKnownLon = rest.substring(c2 + 1).toDouble();
		}
		return;
	}
	if (line.startsWith("SATS:")) {
		lastKnownSatCount = line.substring(5).toInt(); // -1 = wifi_node has no valid satellite count yet
		return;
	}

	if (!scanningActive) return; // discard capture data outside a session, same as before

	if (line.startsWith("W,")) {
		String rest = line.substring(2);
		// bssid,ssid,authMode,isoTimestamp,channel,freqMHz,rssi,lat,lon,alt,acc
		// - 11 fields, every one guaranteed comma-free at the source (ssid is
		// sanitized before sending, authMode/isoTimestamp are fixed formats,
		// the rest are numeric), so a plain sequential comma-split is safe.
		int c1 = rest.indexOf(',');
		int c2 = rest.indexOf(',', c1 + 1);
		int c3 = rest.indexOf(',', c2 + 1);
		int c4 = rest.indexOf(',', c3 + 1);
		int c5 = rest.indexOf(',', c4 + 1);
		int c6 = rest.indexOf(',', c5 + 1);
		int c7 = rest.indexOf(',', c6 + 1);
		int c8 = rest.indexOf(',', c7 + 1);
		int c9 = rest.indexOf(',', c8 + 1);
		int c10 = rest.indexOf(',', c9 + 1);
		if (c1 < 0 || c2 < 0 || c3 < 0 || c4 < 0 || c5 < 0 || c6 < 0 || c7 < 0 || c8 < 0 || c9 < 0 || c10 < 0) {
			return; // malformed line - drop it rather than log garbage
		}
		String bssid = rest.substring(0, c1);
		String ssid = rest.substring(c1 + 1, c2);
		String authMode = rest.substring(c2 + 1, c3);
		String iso = rest.substring(c3 + 1, c4);
		int channel = rest.substring(c4 + 1, c5).toInt();
		int freqMHz = rest.substring(c5 + 1, c6).toInt();
		int rssi = rest.substring(c6 + 1, c7).toInt();
		double lat = rest.substring(c7 + 1, c8).toDouble();
		double lon = rest.substring(c8 + 1, c9).toDouble();
		double alt = rest.substring(c9 + 1, c10).toDouble();
		double acc = rest.substring(c10 + 1).toDouble();

		// Shared dedup with this board's own sniffer (see cydApDedupState's
		// own comment) - without this, an AP wifi_node just relayed and an
		// AP this board's own sniffer separately caught would both write a
		// row for the same real network.
		if (!cydShouldLogApByKey(cydMacKeyFromString(bssid), lat, lon)) return;

		wigleWifi.logWifi(bssid, ssid, authMode, iso, channel, freqMHz, rssi, lat, lon, alt, acc);
		wifiCountThisRun++;
		pushApSighting(bssid, ssid, authMode, rssi);
		pushLogLine(String("[AP] ") + (ssid.length() > 0 ? ssid : bssid) + " " + String(rssi) + "dB", COLOR_CYAN);

		if (wdstreamActive) {
			// Not raw scan activity (this board never sees the radio hit
			// itself - see the wdstream section's own header comment), but
			// close enough in practice: relayed within a couple hundred ms
			// of the real sighting, same field shape GhostESP's own stream
			// produces.
			phonePrintf("WD:AP ts=%lu bssid=%s ssid_hex=%s rssi=%d ch=%u auth=%s hidden=%u",
						  (unsigned long)millis(), bssid.c_str(),
						  hexEncode((const uint8_t *)ssid.c_str(), ssid.length()).c_str(),
						  rssi, (unsigned)channel, wdstreamAuthToken(authMode),
						  ssid.length() == 0 ? 1U : 0U);
			wdstreamApCount++;
		}
	} else if (line.startsWith("B,")) {
		String rest = line.substring(2);
		int c1 = rest.indexOf(',');
		int c2 = rest.indexOf(',', c1 + 1);
		int c3 = rest.indexOf(',', c2 + 1);
		int c4 = rest.indexOf(',', c3 + 1);
		int c5 = rest.indexOf(',', c4 + 1);
		int c6 = rest.indexOf(',', c5 + 1);
		if (c1 < 0 || c2 < 0 || c3 < 0 || c4 < 0 || c5 < 0 || c6 < 0) return;
		String mac = rest.substring(0, c1);
		String name = rest.substring(c1 + 1, c2);
		String iso = rest.substring(c2 + 1, c3);
		int rssi = rest.substring(c3 + 1, c4).toInt();
		double lat = rest.substring(c4 + 1, c5).toDouble();
		double lon = rest.substring(c5 + 1, c6).toDouble();
		int c7 = rest.indexOf(',', c6 + 1);
		if (c7 < 0) return;
		double alt = rest.substring(c6 + 1, c7).toDouble();
		int c8 = rest.indexOf(',', c7 + 1);
		double acc = c8 < 0 ? rest.substring(c7 + 1).toDouble() : rest.substring(c7 + 1, c8).toDouble();
		String mfgHex = c8 < 0 ? "" : rest.substring(c8 + 1); // trailing field - only present now that wifi_node forwards ble_node's raw manufacturer data (see its handleBleLinkLine())
		wigleBle.logBle(mac, name, iso, rssi, lat, lon, alt, acc);
		bleCountThisRun++;
		pushLogLine(String("[BLE] ") + (name.length() > 0 ? name : mac) + " " + String(rssi) + "dB", COLOR_PURPLE);

		if (wdstreamActive) {
			phonePrintf("WD:BLE ts=%lu mac=%s name_hex=%s rssi=%d mfg_hex=%s",
						  (unsigned long)millis(), mac.c_str(),
						  hexEncode((const uint8_t *)name.c_str(), name.length()).c_str(),
						  rssi, mfgHex.c_str());
			wdstreamBleCount++;
		}
	}
}

// Bring-up aid, not normally needed: cycles through all 4 TFT_eSPI rotation
// values at boot, each labeled with a distinct color/letter per corner plus
// its rotation number in the center, so the physically-correct value can be
// read directly off the screen instead of guessing one flash per value.
// Already used once on this board: this panel turned out to be mounted
// landscape-native (rotation 0 was correct, not 1 or 3 as you'd expect on
// the more common portrait-native CYD variant) - see TFT_WIDTH/TFT_HEIGHT's
// own comment in platformio.ini, which had to be swapped to match. Flip
// ROTATION_DEBUG back on if you ever change display hardware and need to
// re-derive this.
static const bool ROTATION_DEBUG = false;
static const uint8_t ROTATION_VALUE = 0;

static void runRotationDebug() {
	static const uint32_t STEP_MS = 4000;
	for (uint8_t r = 0; r < 4; r++) {
		tft.setRotation(r);
		tft.fillScreen(TFT_BLACK);
		int16_t w = tft.width();
		int16_t h = tft.height();

		tft.setTextSize(2);
		tft.setTextColor(TFT_RED, TFT_BLACK);
		tft.setTextDatum(TL_DATUM);
		tft.drawString("A", 4, 4);
		tft.setTextColor(TFT_GREEN, TFT_BLACK);
		tft.setTextDatum(TR_DATUM);
		tft.drawString("B", w - 4, 4);
		tft.setTextColor(TFT_CYAN, TFT_BLACK);
		tft.setTextDatum(BL_DATUM);
		tft.drawString("C", 4, h - 4);
		tft.setTextColor(TFT_YELLOW, TFT_BLACK);
		tft.setTextDatum(BR_DATUM);
		tft.drawString("D", w - 4, h - 4);

		tft.setTextDatum(MC_DATUM);
		tft.setTextColor(TFT_WHITE, TFT_BLACK);
		tft.setTextSize(4);
		char buf[8];
		snprintf(buf, sizeof(buf), "%u", r);
		tft.drawString(buf, w / 2, h / 2);
		tft.setTextSize(2);
		tft.drawString(w > h ? "WIDE" : "TALL", w / 2, h / 2 + 30);

		tft.setTextDatum(TL_DATUM); // restore default
		if (WARDRIVE_DEBUG) Serial.printf("[rotation-debug] r=%u w=%d h=%d\n", r, w, h);
		delay(STEP_MS);
	}
}

// Set true for one flash to run the calibration pass below, then back to
// false once TOUCH_RAW_*/TOUCH_SWAP_XY/TOUCH_INVERT_X/Y (near readTouch(),
// earlier in this file) have been updated from its serial output.
static const bool TOUCH_CALIBRATION_DEBUG = false;

// Bring-up aid, not normally needed: shows a labeled crosshair at each of
// the 4 screen corners in turn and prints the true raw ADC reading for
// each - the exact correspondence needed to compute TOUCH_RAW_X_MIN/MAX,
// TOUCH_RAW_Y_MIN/MAX, TOUCH_SWAP_XY, and TOUCH_INVERT_X/Y correctly in one
// pass, rather than guessing at button behavior after the fact (which is
// how the previous placeholder values were arrived at, and why on-screen
// buttons were landing on the wrong action). Blocks (this is a deliberate
// one-shot bring-up tool, not part of normal operation) until all 4
// corners are tapped in order, waiting for a genuine press-then-release
// between each so a lingering touch from the previous target can't bleed
// into the next reading. Flip TOUCH_CALIBRATION_DEBUG back on any time the
// touch mapping needs re-deriving (e.g. after a display/touch hardware
// change).
static void runTouchCalibrationDebug() {
	struct Target {
		int16_t x, y;
		const char *label;
	};
	// Computed from the actual tft.width()/height() at call time, not
	// hardcoded - an earlier version hardcoded 300/220 assuming a 320x240
	// canvas, which put two of the four targets outside the real bounds
	// (off screen / too high) on a run where the canvas turned out to be
	// a different shape than that guess. Logged below so this is visible
	// in the serial output too, not just inferred from where the targets
	// land.
	int16_t w = tft.width(), h = tft.height();
	Serial.printf("[touch-cal] tft.width()=%d tft.height()=%d\n", w, h);
	Target targets[] = {
		{20, 20, "TOP-LEFT"},
		{(int16_t)(w - 20), 20, "TOP-RIGHT"},
		{20, (int16_t)(h - 20), "BOTTOM-LEFT"},
		{(int16_t)(w - 20), (int16_t)(h - 20), "BOTTOM-RIGHT"},
	};

	for (auto &t : targets) {
		tft.fillScreen(TFT_BLACK);
		tft.setTextDatum(MC_DATUM);
		tft.setTextColor(TFT_WHITE, TFT_BLACK);
		tft.setTextSize(2);
		String msg = String("TAP ") + t.label;
		tft.drawString(msg, tft.width() / 2, tft.height() / 2);
		tft.setTextDatum(TL_DATUM);

		tft.drawLine(t.x - 10, t.y, t.x + 10, t.y, TFT_YELLOW);
		tft.drawLine(t.x, t.y - 10, t.x, t.y + 10, TFT_YELLOW);
		tft.drawCircle(t.x, t.y, 6, TFT_YELLOW);

		while (digitalRead(PIN_TOUCH_IRQ) == LOW) delay(10); // don't react to a press already in progress from the previous target
		int32_t rawX = 0, rawY = 0;
		while (!readTouchRaw(rawX, rawY)) delay(10);
		Serial.printf("[touch-cal] target=%-14s rawX=%ld rawY=%ld\n", t.label, (long)rawX, (long)rawY);

		tft.fillCircle(t.x, t.y, 8, TFT_GREEN); // visual confirmation the tap registered
		delay(400);
		while (digitalRead(PIN_TOUCH_IRQ) == LOW) delay(10); // wait for release before showing the next target
	}

	tft.fillScreen(TFT_BLACK);
	tft.setTextDatum(MC_DATUM);
	tft.setTextColor(TFT_GREEN, TFT_BLACK);
	tft.setTextSize(2);
	tft.drawString("Calibration done", tft.width() / 2, tft.height() / 2 - 10);
	tft.setTextColor(TFT_WHITE, TFT_BLACK);
	tft.setTextSize(1);
	tft.drawString("check serial output", tft.width() / 2, tft.height() / 2 + 15);
	tft.setTextDatum(TL_DATUM);
	delay(2500);
}

void setup() {
	Serial.begin(115200);

	pinMode(PIN_LED_R, OUTPUT);
	pinMode(PIN_LED_G, OUTPUT);
	pinMode(PIN_LED_B, OUTPUT);
	setLed(false, false, false);

	pinMode(PIN_TOUCH_CS, OUTPUT);
	pinMode(PIN_TOUCH_CLK, OUTPUT);
	pinMode(PIN_TOUCH_DIN, OUTPUT);
	pinMode(PIN_TOUCH_DOUT, INPUT);
	pinMode(PIN_TOUCH_IRQ, INPUT); // no pull needed - the touch chip actively drives this pin
	digitalWrite(PIN_TOUCH_CS, HIGH);
	digitalWrite(PIN_TOUCH_CLK, LOW);

	pinMode(TFT_BL, OUTPUT);
	digitalWrite(TFT_BL, HIGH);
	tft.init();
	// invertDisplay(true) was tried here based on an unverified assumption
	// about this panel and turned out wrong - it was inverting every color
	// (dark gray background rendering as near-white, text/accents flipped
	// to their complements too). Left off; this panel doesn't need it.
	initTheme();

	if (ROTATION_DEBUG) runRotationDebug();

	// Both nominal "landscape" values (1 and 3) were reported sideways on
	// this unit's physical panel mounting during bring-up - see
	// runRotationDebug() above, which was used to find the actual correct
	// value empirically rather than keep guessing blind.
	tft.setRotation(ROTATION_VALUE);
	tft.fillScreen(COLOR_BG);

	if (TOUCH_CALIBRATION_DEBUG) runTouchCalibrationDebug();

	statePrefs.begin(STATE_PREFS_NS, false);
	resumeScanningIntent = statePrefs.getBool(STATE_PREFS_KEY, false);

	xTaskCreatePinnedToCore(sdInitTask, "sdInit", 8192, nullptr, 1, nullptr, 0);

	CydBleLink::beginServer("WardriveCYD");
	WifiLinkSerial.begin(115200, SERIAL_8N1, PIN_WIFI_LINK_RX, PIN_WIFI_LINK_TX);

	cydObsQueue = xQueueCreate(64, sizeof(CydWifiObservation));
	WiFi.mode(WIFI_MODE_STA);
	WiFi.disconnect();
	esp_wifi_set_promiscuous_rx_cb(&cydWifiSnifferCallback);
	// See wifi_node's identical call for why - this board's own sniffer has
	// the same software-only type filter in cydWifiSnifferCallback(), and
	// gets the same driver-level cut of non-management traffic before it
	// ever reaches that callback (2026-09-27 speed optimization pass).
	wifi_promiscuous_filter_t cydPromFilter = {.filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT};
	esp_wifi_set_promiscuous_filter(&cydPromFilter);

	Serial.println("cyd_node ready");
}

uint32_t lastHeartbeatMs = 0;
uint32_t lastDockCheckMs = 0;
// Dock-mode upload is now triggered by the ARRIVAL transition (leaving-home
// -> near-home), not purely by config.cfg's minUploadIntervalSec - see the
// dock-check block's own comment for why (the user explicitly wants an
// upload attempt every time they get home, not throttled to once per
// interval if they made several short trips in one day).
bool wasNearHomeForDock = false;
bool dockUploadDoneThisArrival = false;
uint32_t lastStorageCheckMs = 0;
static const uint32_t STORAGE_CHECK_INTERVAL_MS = 30000; // how often to retry a dead card / recheck free space
uint32_t lastSdHealthBroadcastMs = 0;
// Fast and independent of the recheck interval above - wifi_node (and,
// relayed onward, ble_node) needs to learn about a bad card quickly so they
// can refuse to start *before* the next touch, not react a moment after
// already starting. Mirrors wifi_node's own SD_HEALTH_BROADCAST_MS from
// before this board took over SD duties.
static const uint32_t SD_HEALTH_BROADCAST_MS = 300;
uint32_t lastCfgBroadcastMs = 0;
static const uint32_t CFG_BROADCAST_MS = 2000;
// Self-heal for the case wifi_node missed onSingleClickHandler()'s
// immediate broadcast (e.g. it was briefly out of BLE range right at that
// moment) - same cadence and reasoning as wifi_node's own old periodic
// broadcastScanState(), just now running in the other direction.
uint32_t lastScanStateBroadcastMs = 0;
static const uint32_t SCANSTATE_BROADCAST_MS = 2000;
uint32_t lastCleanupMs = 0;
static const uint32_t CLEANUP_INTERVAL_MS = 24UL * 3600UL * 1000UL;
bool wasTouched = false;
uint32_t lastTouchDispatchMs = 0;
// Resistive touch readings can be noisy right at the moment of contact -
// the raw ADC reading occasionally reads back invalid for a poll or two
// even while a finger is genuinely still down, which made wasTouched's
// plain edge-detection see that as a release-then-repress and fire twice
// for one real physical tap (toggle, then toggle back - net no visible
// change, even though flashLed() still fired both times, which is exactly
// what looked like "the LED lights up but nothing happens"). A minimum
// gap between dispatches closes this without needing to majority-vote
// several readings before trusting a press.
static const uint32_t TOUCH_DISPATCH_COOLDOWN_MS = 400;

void loop() {
	// Edge-triggered on press (not release) so a touch reacts the instant
	// you tap it, same feel as the old physical button's press-to-act -
	// wasTouched gates this to fire once per press rather than once per
	// poll while held down.
	TouchPoint touch = readTouch();
	if (touch.valid && !wasTouched && millis() - lastTouchDispatchMs > TOUCH_DISPATCH_COOLDOWN_MS) {
		lastTouchDispatchMs = millis();
		bool hit = false;
		for (uint8_t i = 0; i < 5 && !hit; i++) {
			if (inRect(tabRects[i], touch.x, touch.y)) {
				currentTab = (Tab)i;
				hit = true;
			}
		}
		// Action buttons are tab-specific (see each drawTab*() function for
		// what actionRects[] means on that tab) - actionRectCount bounds how
		// many of the reused slots are actually live for the current tab.
		for (uint8_t i = 0; i < actionRectCount && !hit; i++) {
			if (!inRect(actionRects[i], touch.x, touch.y)) continue;
			hit = true;
			switch (currentTab) {
				case Tab::Main:
					if (i == 0) onSingleClickHandler();
					else if (i == 1) onDoubleClickHandler();
					else if (i == 2) onReconnectHandler();
					break;
				case Tab::Targets:
					if (i == 0) onClearTargetsHandler();
					break;
				case Tab::Links:
					if (i == 0) onReconnectHandler(); // same RE-LINK ALL as the main tab
					break;
				case Tab::Logs:
					if (i == 0) onPauseLogHandler();
					else if (i == 1) onFlushSdHandler();
					break;
				case Tab::Cfg:
					if (i == 0) onWipeLogsHandler();
					else if (i == 1) onRebootHandler();
					break;
			}
		}
	}
	wasTouched = touch.valid;

	if (ledOffAtMs != 0 && millis() >= ledOffAtMs) {
		setLed(false, false, false);
		ledOffAtMs = 0;
		ledPriority = false;
	}

	serviceErrorBlink();
	serviceLinkStatusBlink();

	static bool sdTaskReported = false;
	if (sdTaskDone && !sdTaskReported) {
		sdTaskReported = true;
		if (WARDRIVE_DEBUG) {
			Serial.printf("[boot] sdOk=%d configOk=%d wifi_ssid=%s\n", sdOk, configOk, config.wifiSsid.c_str());
		}
		if (resumeScanningIntent) {
			// Just sets the flag - the scanningActive != wasScanning edge
			// detector later in this same loop() iteration is what actually
			// calls startScanning() (opens this run's SD log files, resets
			// counters/dedup state, starts the radio). Confirmed by inspection
			// (2026-09-28) that nothing between here and there can skip that
			// check on this same tick, so setting the flag here is sufficient -
			// verified this isn't the double-initialization it looks like at a
			// glance.
			scanningActive = true;
			if (WARDRIVE_DEBUG) Serial.println("[boot] resuming scan from before power loss");
		}
	}

	if (WARDRIVE_DEBUG && millis() - lastHeartbeatMs > HEARTBEAT_MS) {
		lastHeartbeatMs = millis();
		Serial.printf("[heartbeat] up=%lus scanning=%d sdOk=%d wifi=%lu ble=%lu heap=%lu wlrx=%lu\n",
					  (unsigned long)(millis() / 1000), scanningActive, sdOk,
					  (unsigned long)wifiCountThisRun, (unsigned long)bleCountThisRun,
					  (unsigned long)ESP.getFreeHeap(), (unsigned long)wifiLinkRxBytes);
	}

	if (millis() - lastStorageCheckMs > STORAGE_CHECK_INTERVAL_MS) {
		lastStorageCheckMs = millis();
		if (!sdOk) {
			sdOk = tryRecoverSd();
			if (sdOk && !configOk) configOk = loadWardriveConfig("/config.cfg", config);
		} else {
			checkStorage();
		}
	}

	CydBleLink::poll();

	if (millis() - lastSdHealthBroadcastMs > SD_HEALTH_BROADCAST_MS) {
		lastSdHealthBroadcastMs = millis();
		wifiLinkSend(sdOk ? "SDOK:1" : "SDOK:0");
	}

	if (configOk && millis() - lastCfgBroadcastMs > CFG_BROADCAST_MS) {
		lastCfgBroadcastMs = millis();
		char cfgLine[48];
		snprintf(cfgLine, sizeof(cfgLine), "CFG:channelHopMs=%lu", (unsigned long)config.channelHopMs);
		wifiLinkSend(cfgLine);
	}

	if (millis() - lastScanStateBroadcastMs > SCANSTATE_BROADCAST_MS) {
		lastScanStateBroadcastMs = millis();
		char stateLine[16];
		snprintf(stateLine, sizeof(stateLine), "SCANSTATE:%d", scanningActive ? 1 : 0);
		wifiLinkSend(stateLine);
	}

	// Phone commands over BLE (primary) - same command set as the USB path below.
	while (CydBleLink::hasLine()) {
		String line = CydBleLink::readLine();
		if (line.length() > 0) {
			notePhoneCommandReceived();
			handleWdstreamCommand(line);
		}
	}

	// Wired link from wifi_node. Accumulated byte-by-byte (never blocks, unlike
	// readStringUntil()) so a line split across loop() iterations can't stall
	// the touch/display loop waiting for its tail.
	static String wifiLinkLineBuf;
	while (WifiLinkSerial.available()) {
		char c = (char)WifiLinkSerial.read();
		if (c == '\n') {
			wifiLinkLineBuf.trim();
			if (wifiLinkLineBuf.length() > 0) {
				wifiLinkRxBytes += wifiLinkLineBuf.length() + 1;
				lastWifiLinkRxMs = millis();
				handleIncomingLine(wifiLinkLineBuf);
			}
			wifiLinkLineBuf = "";
		} else if (c != '\r' && wifiLinkLineBuf.length() < 256) {
			wifiLinkLineBuf += c;
		}
	}

	// Phone commands over this board's USB Serial port (fallback) - plain-text
	// "wdstream start"/"stop"/"status", "scan start"/"scan stop".
	while (Serial.available()) {
		char c = (char)Serial.read();
		if (c == '\n') {
			if (wdstreamLineBuf.length() > 0) {
				notePhoneCommandReceived();
				handleWdstreamCommand(wdstreamLineBuf);
			}
			wdstreamLineBuf = "";
		} else if (c != '\r') {
			if (wdstreamLineBuf.length() < 200) wdstreamLineBuf += c; // real commands are short - cap against line noise
		}
	}
	checkPhoneLinkTimeout();

	if (wdstreamActive && millis() - lastWdstreamStatusMs > WDSTREAM_STATUS_INTERVAL_MS) {
		lastWdstreamStatusMs = millis();
		wdstreamEmitStatus();
	}

	if (uploadRequested) {
		uploadRequested = false;
		doUpload(true);
	}

	if (scanningActive != wasScanning) {
		wasScanning = scanningActive;
		if (scanningActive) {
			if (!sdOk) {
				sdOk = tryRecoverSd();
				if (sdOk && !configOk) configOk = loadWardriveConfig("/config.cfg", config);
			}
			bool canSave = startScanning();
			if (!canSave) {
				stopScanning();
				scanningActive = false;
				wasScanning = false;
				statePrefs.putBool(STATE_PREFS_KEY, false);
				sdOk = false;
				startSdErrorBlink();
				if (WARDRIVE_DEBUG) Serial.println("[start] SD can't save data - check card, aborting start");
			}
		} else {
			stopScanning();
		}
	}

	if (scanningActive) {
		if (millis() - cydLastChannelHopMs > config.channelHopMs) {
			cydLastChannelHopMs = millis();
			cydCurrentChannel++;
			if (cydCurrentChannel > CYD_CHANNEL_MAX) cydCurrentChannel = CYD_CHANNEL_MIN;
			esp_wifi_set_channel(cydCurrentChannel, WIFI_SECOND_CHAN_NONE);
		}

		CydWifiObservation obs;
		while (xQueueReceive(cydObsQueue, &obs, 0) == pdTRUE) {
			// No dedicated LED flash for this - every other on/off
			// combination this board's plain RGB LED can show is already
			// claimed by an existing signal (see README.md's color table),
			// so a new AP found by THIS board's own sniffer only shows up
			// in the ticker/TARGETS/LOGS views, not a distinct blink.

			if (!gpsFixKnown) continue; // no usable position - see file header on staleness
			if (!cydShouldLogAp(obs.bssid, lastKnownLat, lastKnownLon)) continue;

			String ssid(obs.ssid);
			ssid.replace(",", " ");
			ssid.replace("\n", " ");
			ssid.replace("\r", " ");
			String bssid = cydMacToString(obs.bssid);
			String authMode = cydAuthModeStr(obs.authMode, obs.pmfCapable, obs.pmfRequired);
			String iso = cydIsoTimestampFromEpoch(lastKnownEpoch);
			wigleWifi.logWifi(bssid, ssid, authMode, iso, obs.channel, cydChannelToFreqMHz(obs.channel),
							  obs.rssi, lastKnownLat, lastKnownLon, 0.0, 30.0);
			wifiCountThisRun++;
			pushApSighting(bssid, ssid, authMode, obs.rssi);
			pushLogLine(String("[AP] ") + (ssid.length() > 0 ? ssid : bssid) + " " + String(obs.rssi) + "dB (cyd)", COLOR_CYAN);
		}
	}

	if (!scanningActive) {
		if (millis() - lastDockCheckMs > DOCK_CHECK_MS) {
			lastDockCheckMs = millis();
			if (config.homeRadiusM > 0.0) {
				// A real geofence is configured, so "arrival" is a
				// meaningful, detectable event (the away->near-home
				// transition) - upload once per ARRIVAL rather than once
				// per config.cfg's minUploadIntervalSec. That interval
				// exists to stop re-uploading every 60s while sitting
				// parked at home for hours, it was never meant to also
				// skip a genuinely new arrival just because a previous
				// trip happened to upload less than min_upload_interval_sec
				// ago. force=true bypasses that interval check inside
				// uploadPending() - the arrival itself is now the "should I
				// upload" decision, not elapsed time.
				bool home = nearHome();
				if (home && !wasNearHomeForDock) dockUploadDoneThisArrival = false;
				wasNearHomeForDock = home;
				if (home && !dockUploadDoneThisArrival) {
					UploadResult r = doUpload(true);
					// Ok (something uploaded) or Skipped (nothing was
					// pending - also a legitimate "done" state, not a
					// failure) both mark this arrival handled;
					// WifiFailed/UploadFailed leave the flag false so the
					// next DOCK_CHECK_MS tick retries, in case the failure
					// was transient (WiFi still joining, etc.).
					if (r == UploadResult::Ok || r == UploadResult::Skipped) dockUploadDoneThisArrival = true;
				}
			} else {
				// No geofence configured - nearHome() always returns true,
				// so there's no real "arrival" transition to detect (you're
				// always considered "home"). Fall back to the original
				// time-based cooldown instead: force=false lets
				// uploadPending() gate on minUploadIntervalSec itself,
				// which is the only meaningful throttle available without
				// a position to key an arrival off of.
				doUpload(false);
			}
		}
		if (uploader && millis() - lastCleanupMs > CLEANUP_INTERVAL_MS) {
			lastCleanupMs = millis();
			uploader->cleanupOldFiles(SESSION_DIR, lastKnownEpoch);
		}
	}

	if (millis() - lastDisplayRefreshMs > DISPLAY_REFRESH_MS) {
		lastDisplayRefreshMs = millis();
		drawStatus();
	}
}
