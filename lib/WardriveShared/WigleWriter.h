#pragma once
#include <Arduino.h>
#include <SD.h>

// Writes a WigleWifi-1.6 CSV session file (the format WiGLE and wdgwars.pl
// both accept). One instance per board; each board keeps its own session
// files on its own SD card.
class WigleWriter {
public:
	// Creates /<dirPath>/<role>_<timestamp>.csv and writes the WigleWifi header.
	bool begin(const String &dirPath, const String &role);

	void logWifi(const String &bssid, const String &ssid, const String &authMode,
				 const String &firstSeenIso, int channel, int frequencyMHz, int rssi,
				 double lat, double lon, double altM, double accM);

	void logBle(const String &mac, const String &name, const String &firstSeenIso,
				int rssi, double lat, double lon, double altM, double accM);

	void flush();
	void close();
	const String &currentFilePath() const { return _path; }

private:
	File _file;
	String _path;
	uint32_t _rowsWritten = 0;

	void writeRow(const String &mac, const String &ssid, const String &authMode,
				  const String &firstSeenIso, const String &channel, const String &freq,
				  int rssi, double lat, double lon, double altM, double accM, const String &type);
};
