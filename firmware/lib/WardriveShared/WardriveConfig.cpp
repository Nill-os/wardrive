#include "WardriveConfig.h"
#include <SD.h>

static uint32_t parseHexColor(const String &v, uint32_t fallback) {
	String h = v;
	h.trim();
	if (h.startsWith("#")) h = h.substring(1);
	if (h.length() != 6) return fallback;
	return (uint32_t)strtoul(h.c_str(), nullptr, 16) & 0xFFFFFF;
}

bool loadWardriveConfig(const char *path, WardriveConfig &outCfg) {
	File f = SD.open(path);
	if (!f) return false;

	while (f.available()) {
		String line = f.readStringUntil('\n');
		line.trim();
		if (line.length() == 0 || line.startsWith("#")) continue;

		int eq = line.indexOf('=');
		if (eq < 0) continue;

		String key = line.substring(0, eq);
		String value = line.substring(eq + 1);
		key.trim();
		value.trim();

		if (key == "wifi_ssid") outCfg.wifiSsid = value;
		else if (key == "wifi_pass") outCfg.wifiPass = value;
		else if (key == "backup_wifi_ssid") outCfg.backupWifiSsid = value;
		else if (key == "backup_wifi_pass") outCfg.backupWifiPass = value;
		else if (key == "wigle_api_token") outCfg.wigleApiToken = value;
		else if (key == "wdgwars_api_key") outCfg.wdgwarsApiKey = value;
		else if (key == "min_upload_interval_sec") outCfg.minUploadIntervalSec = (uint32_t)value.toInt();
		else if (key == "home_lat") outCfg.homeLat = value.toDouble();
		else if (key == "home_lon") outCfg.homeLon = value.toDouble();
		else if (key == "home_radius_m") outCfg.homeRadiusM = value.toDouble();
		else if (key == "exclude_radius_m") outCfg.excludeRadiusM = value.toDouble();
		else if (key == "led_brightness") { int v = value.toInt(); outCfg.ledBrightness = v < 0 ? 0 : v > 100 ? 100 : v; }
		else if (key == "screen_brightness") { int v = value.toInt(); outCfg.screenBrightness = v < 0 ? 0 : v > 100 ? 100 : v; }
		else if (key == "screen_timeout_sec") outCfg.screenTimeoutSec = (uint32_t)value.toInt();
		else if (key == "screen_keep_on_scanning") outCfg.screenKeepOnScanning = (value == "1" || value == "true" || value == "yes");
		else if (key == "led_color_ap") outCfg.ledColorAp = parseHexColor(value, outCfg.ledColorAp);
		else if (key == "led_color_ble") outCfg.ledColorBle = parseHexColor(value, outCfg.ledColorBle);
		else if (key == "led_color_ok") outCfg.ledColorOk = parseHexColor(value, outCfg.ledColorOk);
		else if (key == "led_color_fail") outCfg.ledColorFail = parseHexColor(value, outCfg.ledColorFail);
		else if (key == "retention_days") outCfg.retentionDays = (uint32_t)value.toInt();
		else if (key == "min_free_mb") outCfg.minFreeMB = (uint32_t)value.toInt();
		else if (key == "count_mode") outCfg.countMode = (uint8_t)value.toInt();
		else if (key == "channel_hop_ms") {
			uint32_t parsed = (uint32_t)value.toInt();
			if (parsed >= 20 && parsed <= 5000) outCfg.channelHopMs = parsed; // sane floor/ceiling - outside this is almost certainly a typo, not intent
		}
		else if (key == "service_password") outCfg.servicePassword = value;
		else if (key == "alert_flock") outCfg.alertFlock = (value == "1" || value == "true");
		else if (key == "alert_police") outCfg.alertPolice = (value == "1" || value == "true");
		else if (key == "alert_skimmer") outCfg.alertSkimmer = (value == "1" || value == "true");
		else if (key == "alert_flipper") outCfg.alertFlipper = (value == "1" || value == "true");
		else if (key == "alert_glasses") outCfg.alertGlasses = (value == "1" || value == "true");
		else if (key == "alert_actioncam") outCfg.alertActionCam = (value == "1" || value == "true");
		else if (key == "alert_pineapple") outCfg.alertPineapple = (value == "1" || value == "true");
	}
	f.close();

	outCfg.valid = outCfg.wifiSsid.length() > 0 && outCfg.wdgwarsApiKey.length() > 0;
	return outCfg.valid;
}
