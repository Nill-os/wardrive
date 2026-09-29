#include "WigleWriter.h"

// A WiFi SSID is an arbitrary byte string (up to 32 bytes) with no rule
// against a literal comma or newline - rare, but legal - and a BLE device
// name is just as uncontrolled. Sanitizing here, at the one place that
// actually writes a CSV row, protects both logWifi() and logBle() uniformly
// regardless of whether a caller already cleaned its input.
static String sanitizeField(const String &s) {
	String out = s;
	out.replace(",", " ");
	out.replace("\n", " ");
	out.replace("\r", " ");
	return out;
}

bool WigleWriter::begin(const String &dirPath, const String &role) {
	if (!SD.exists(dirPath)) SD.mkdir(dirPath);

	// Named by millis(), which restarts at 0 every boot - and a power-loss
	// resume happens at almost the same millis() every time, so the name alone
	// can collide with an earlier session. FILE_WRITE truncates, and an old
	// ".uploaded" marker would stop the new data ever uploading, so step past
	// any name that's been used before.
	uint32_t stamp = millis();
	do {
		_path = dirPath + "/" + role + "_" + String(stamp++) + ".csv";
	} while (SD.exists(_path) || SD.exists(_path + ".uploaded"));
	_rowsWritten = 0;
	_file = SD.open(_path, FILE_WRITE);
	if (!_file) return false;

	_file.println("WigleWifi-1.6,appRelease=1.0,model=ESP32S3,release=1.0,device=ESP32S3-Wardriver,display=none,board=ESP32S3,brand=DIY");
	_file.println("MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type");
	_file.flush();

	// SD.open() can return a "successful" handle even when the card was
	// pulled after boot or a mount is half-broken - some failure modes only
	// show up once you actually try to persist bytes. Confirming the header
	// really landed on disk (not just that a File object came back) is what
	// makes this catch a missing/failed card at start-of-scan time, not just
	// at boot.
	if (_file.size() == 0) {
		_file.close();
		SD.remove(_path);
		return false;
	}
	return true;
}

void WigleWriter::writeRow(const String &mac, const String &ssid, const String &authMode,
							const String &firstSeenIso, const String &channel, const String &freq,
							int rssi, double lat, double lon, double altM, double accM, const String &type) {
	if (!_file) return;
	String safeSsid = sanitizeField(ssid);
	_file.printf("%s,%s,%s,%s,%s,%s,%d,%.6f,%.6f,%.1f,%.1f,%s\n",
				 mac.c_str(), safeSsid.c_str(), authMode.c_str(), firstSeenIso.c_str(),
				 channel.c_str(), freq.c_str(), rssi, lat, lon, altM, accM, type.c_str());
	_rowsWritten++;
}

void WigleWriter::logWifi(const String &bssid, const String &ssid, const String &authMode,
						   const String &firstSeenIso, int channel, int frequencyMHz, int rssi,
						   double lat, double lon, double altM, double accM) {
	writeRow(bssid, ssid, authMode, firstSeenIso, String(channel), String(frequencyMHz),
			 rssi, lat, lon, altM, accM, "WIFI");
}

void WigleWriter::logBle(const String &mac, const String &name, const String &firstSeenIso,
						  int rssi, double lat, double lon, double altM, double accM) {
	writeRow(mac, name, "[BLE]", firstSeenIso, "", "", rssi, lat, lon, altM, accM, "BLE");
}

void WigleWriter::flush() {
	if (_file) _file.flush();
}

// A session that starts and stops (button double-tap, a brief USB/phone
// reconnect glitch, idle auto-stop right after a false-start) before ever
// seeing a real detection left a permanent header-only file behind on every
// single occurrence - confirmed from a real drive's SD card, where over 100
// of ~130 session files were empty like this. Deleting it here instead of
// just closing it costs nothing (the file never had anything worth keeping)
// and stops the SD card from silently filling up with clutter over time.
void WigleWriter::close() {
	if (_file) _file.close();
	if (_rowsWritten == 0 && _path.length() > 0) SD.remove(_path);
}
