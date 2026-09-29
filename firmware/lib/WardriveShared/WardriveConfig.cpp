#include "WardriveConfig.h"
#include <SD.h>

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
		else if (key == "retention_days") outCfg.retentionDays = (uint32_t)value.toInt();
		else if (key == "channel_hop_ms") {
			uint32_t parsed = (uint32_t)value.toInt();
			if (parsed >= 20 && parsed <= 5000) outCfg.channelHopMs = parsed; // sane floor/ceiling - outside this is almost certainly a typo, not intent
		}
		else if (key == "pcap_capture") outCfg.pcapCaptureEnabled = (value == "1" || value == "true" || value == "yes");
	}
	f.close();

	outCfg.valid = outCfg.wifiSsid.length() > 0 && outCfg.wdgwarsApiKey.length() > 0;
	return outCfg.valid;
}
