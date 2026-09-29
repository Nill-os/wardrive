#include "Uploader.h"
#include <WiFi.h>
#include <HTTPClient.h>
#include <SD.h>
#include <Preferences.h>
#include <vector>
#include <utility>

static const char *PREFS_NS = "wardrive";
static const char *PREFS_KEY_LAST_UPLOAD = "last_upload";

// Streams one multipart/form-data body - a fixed head, the CSV straight off
// the SD card, a fixed tail - instead of building it in RAM first. A single
// drive's CSV easily outgrows the CYD's free heap (~100KB, and TLS needs
// ~40KB of that), so buffering capped uploads at a few hundred rows and
// larger files failed forever.
class MultipartFileStream : public Stream {
public:
	bool open(const String &filePath, const String &fieldName, const String &boundary) {
		_file = SD.open(filePath, FILE_READ);
		if (!_file) return false;
		String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
		_head = "--" + boundary + "\r\n" +
				"Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n" +
				"Content-Type: text/csv\r\n\r\n";
		_tail = "\r\n--" + boundary + "--\r\n";
		_fileSize = _file.size();
		_pos = 0;
		return true;
	}
	void close() {
		if (_file) _file.close();
	}
	size_t totalSize() const { return _head.length() + _fileSize + _tail.length(); }

	int available() override { return (int)(totalSize() - _pos); }
	int read() override {
		uint8_t c;
		return readBytes((char *)&c, 1) == 1 ? c : -1;
	}
	int peek() override { return -1; } // HTTPClient never peeks
	size_t readBytes(char *buf, size_t len) override {
		size_t n = 0;
		while (n < len && _pos < totalSize()) {
			size_t headLen = _head.length();
			if (_pos < headLen) {
				size_t k = min(len - n, headLen - _pos);
				memcpy(buf + n, _head.c_str() + _pos, k);
				n += k;
				_pos += k;
			} else if (_pos < headLen + _fileSize) {
				size_t want = min(len - n, headLen + _fileSize - _pos);
				int got = _file.read((uint8_t *)buf + n, want);
				if (got <= 0) break; // card read failed - HTTPClient sees a short body and the POST fails
				n += got;
				_pos += got;
			} else {
				size_t off = _pos - headLen - _fileSize;
				size_t k = min(len - n, _tail.length() - off);
				memcpy(buf + n, _tail.c_str() + off, k);
				n += k;
				_pos += k;
			}
		}
		return n;
	}
	size_t write(uint8_t) override { return 0; }

private:
	File _file;
	String _head, _tail;
	size_t _fileSize = 0, _pos = 0;
};

static const char *BOUNDARY = "----wardriveESP32Boundary7d1a9c";
// A WigleWifi file with just its two header lines is ~240 bytes; any real row
// takes it well past this.
static const size_t HEADER_ONLY_MAX_BYTES = 260;

// http is a long-lived, per-destination client (setReuse(true), kept open
// across every file in a batch by the caller) - a fresh HTTPClient per file
// meant a full TCP+TLS handshake per file, which dominated upload time far
// more than the read/build work ever did. Reusing the connection lets every
// file after the first skip that handshake entirely as long as the server
// keeps it alive.
static bool postMultipartFile(HTTPClient &http, const String &url, const String &filePath,
							  const std::vector<std::pair<String, String>> &headers) {
	MultipartFileStream body;
	if (!body.open(filePath, "file", BOUNDARY)) return false;

	// Without these, a network that associates fine but has no real route to
	// the internet (bad DNS, dead upstream) can leave the POST blocked for a
	// very long time with no way out - this bounds the worst case instead of
	// freezing the whole board's main loop indefinitely.
	http.setConnectTimeout(8000);
	http.setTimeout(10000);
	http.begin(url);
	http.addHeader("Content-Type", String("multipart/form-data; boundary=") + BOUNDARY);
	for (auto &h : headers) http.addHeader(h.first, h.second);

	Serial.printf("[upload] free heap before POST %s: %u bytes (largest block %u)\n",
				  url.c_str(), (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getMaxAllocHeap());
	uint32_t start = millis();
	size_t size = body.totalSize();
	int code = http.sendRequest("POST", &body, size);
	body.close();
	Serial.printf("[upload] POST %s -> %d (%lums, %u bytes)\n",
				  url.c_str(), code, (unsigned long)(millis() - start), (unsigned)size);

	// wdgwars.pl treats 409 (server-side dedup) as success too.
	bool ok = code == 200 || code == 201 || code == 202 || code == 409;
	if (!ok) {
		// The response body usually says exactly what the server didn't
		// like (bad field name, auth, format) - print it so a failure like
		// this is diagnosable from the serial log instead of just a bare
		// status code.
		String resp = http.getString();
		Serial.printf("[upload]   response: %s\n", resp.substring(0, 300).c_str());
	}
	http.end();
	return ok;
}

Uploader::Uploader(const WardriveConfig &cfg) : _cfg(cfg) {}

static bool joinWifi(const String &ssid, const String &pass, uint32_t timeoutMs) {
	if (ssid.length() == 0) return false;
	uint32_t start = millis();
	WiFi.begin(ssid.c_str(), pass.c_str());
	while (WiFi.status() != WL_CONNECTED && (millis() - start) < timeoutMs) {
		delay(200);
	}
	bool ok = WiFi.status() == WL_CONNECTED;
	Serial.printf("[upload] %s %s after %lums\n",
				  ssid.c_str(), ok ? "joined" : "failed to join", (unsigned long)(millis() - start));
	return ok;
}

bool Uploader::connectWifi(uint32_t timeoutMs) {
	if (WiFi.status() == WL_CONNECTED) return true;

	WiFi.mode(WIFI_STA);
	if (joinWifi(_cfg.wifiSsid, _cfg.wifiPass, timeoutMs)) return true;

	// wifi_ssid (usually home WiFi) wasn't reachable - fall back to a second
	// network, e.g. a phone hotspot, if one's configured. WiFi.begin() on a
	// fresh SSID after a failed attempt cleanly supersedes it, no disconnect
	// needed first.
	if (_cfg.backupWifiSsid.length() > 0) {
		return joinWifi(_cfg.backupWifiSsid, _cfg.backupWifiPass, timeoutMs);
	}
	return false;
}

void Uploader::disconnectWifi() {
	WiFi.disconnect(true);
	WiFi.mode(WIFI_OFF);
}

bool Uploader::alreadyUploaded(const String &filePath) {
	return SD.exists(filePath + ".uploaded");
}

void Uploader::markUploaded(const String &filePath, uint32_t nowEpoch) {
	File f = SD.open(filePath + ".uploaded", FILE_WRITE);
	if (f) {
		f.println(nowEpoch); // so cleanupOldFiles() can tell how old this is later
		f.close();
	}
}

uint32_t Uploader::lastUploadEpoch() {
	Preferences p;
	p.begin(PREFS_NS, true);
	uint32_t v = p.getUInt(PREFS_KEY_LAST_UPLOAD, 0);
	p.end();
	return v;
}

void Uploader::setLastUploadEpoch(uint32_t epoch) {
	Preferences p;
	p.begin(PREFS_NS, false);
	p.putUInt(PREFS_KEY_LAST_UPLOAD, epoch);
	p.end();
}

String Uploader::endpoint(const char *real) const {
	return _endpointOverride.length() > 0 ? _endpointOverride : String(real);
}

bool Uploader::uploadToWdgwars(HTTPClient &http, const String &filePath) {
	// NOTE: wdgwars.pl's exact auth header/field name for /api/upload-csv is
	// documented on its own site behind a login (Help section after signing
	// in at wdgwars.pl). This uses the most common convention (X-Api-Key
	// header, "file" multipart field) - confirm against your account's docs
	// and adjust here if the key isn't accepted.
	std::vector<std::pair<String, String>> headers;
	headers.push_back({"X-Api-Key", _cfg.wdgwarsApiKey});
	return postMultipartFile(http, endpoint("https://wdgwars.pl/api/upload-csv"), filePath, headers);
}

bool Uploader::uploadToWigle(HTTPClient &http, const String &filePath) {
	if (_cfg.wigleApiToken.length() == 0) return true; // WiGLE upload optional
	std::vector<std::pair<String, String>> headers;
	headers.push_back({"Authorization", "Basic " + _cfg.wigleApiToken});
	return postMultipartFile(http, endpoint("https://api.wigle.net/api/v2/file/upload"), filePath, headers);
}

UploadResult Uploader::uploadPending(const String &dirPath, uint32_t nowEpoch, bool force,
									  UploadProgressCallback onProgress) {
	if (!_cfg.valid) return UploadResult::Skipped;

	if (!force && !autoUploadDue(nowEpoch)) return UploadResult::Skipped;

	File dir = SD.open(dirPath);
	if (!dir) return UploadResult::Skipped;

	bool anyAttempted = false;
	bool anySucceeded = false;
	bool wifiOk = true;

	// One HTTPClient per destination, created once and reused for every file
	// in this batch (setReuse keeps the TCP+TLS session alive between
	// requests to the same host) - a fresh client per file meant a full
	// handshake per file, which was the dominant cost when there were many
	// pending files.
	HTTPClient httpWdg, httpWigle;
	httpWdg.setReuse(true);
	httpWigle.setReuse(true);

	File entry = dir.openNextFile();
	while (entry) {
		String name = String(entry.name());
		bool isCsv = name.endsWith(".csv");
		size_t size = entry.size();
		entry.close();

		if (isCsv && size <= HEADER_ONLY_MAX_BYTES) {
			// Header only - a run that was cut off by a power loss before its
			// first row (a clean stop deletes these itself). Nothing to send.
			String fullPath = dirPath + "/" + name;
			if (!alreadyUploaded(fullPath)) markUploaded(fullPath, nowEpoch);
		} else if (isCsv) {
			String fullPath = dirPath + "/" + name;
			if (!alreadyUploaded(fullPath)) {
				if (!anyAttempted) {
					if (onProgress) onProgress("connecting", _cfg.wifiSsid.c_str());
					wifiOk = connectWifi();
					if (!wifiOk) {
						dir.close();
						return UploadResult::WifiFailed;
					}
				}
				anyAttempted = true;

				if (onProgress) onProgress("uploading", name.c_str());
				bool wdgOk = uploadToWdgwars(httpWdg, fullPath);
				bool wigleOk = uploadToWigle(httpWigle, fullPath);
				// wdgwars.pl is the required destination; WiGLE is optional
				// best-effort (see config.cfg.example). Requiring wigleOk
				// too meant one WiGLE-side failure - a bad request format,
				// a rejected file, anything - permanently blocked every
				// file from ever being marked done, so the whole backlog
				// got resent in full on every single upload from then on
				// forever. A file that reached wdgwars successfully counts
				// as delivered even if WiGLE's copy failed.
				if (wdgOk) {
					markUploaded(fullPath, nowEpoch);
					anySucceeded = true;
					if (!wigleOk) Serial.printf("[upload] %s: WiGLE upload failed (see response above), marked uploaded anyway\n", name.c_str());
				}
			}
		}

		entry = dir.openNextFile();
	}
	dir.close();

	if (anyAttempted) {
		disconnectWifi();
		// Only push the cooldown timestamp forward on a real success - if
		// everything failed (bad keys, server down, WiFi dropped mid-batch),
		// dock-mode should be free to retry sooner than minUploadIntervalSec,
		// not wait out a cooldown for an upload that never actually happened.
		if (anySucceeded) {
			if (nowEpoch != 0) setLastUploadEpoch(nowEpoch);
			return UploadResult::Ok;
		}
		return UploadResult::UploadFailed;
	}
	return UploadResult::Skipped;
}

bool Uploader::autoUploadDue(uint32_t nowEpoch) {
	if (nowEpoch == 0) return false; // no reliable clock yet
	uint32_t last = lastUploadEpoch();
	return !(last != 0 && nowEpoch > last && (nowEpoch - last) < _cfg.minUploadIntervalSec);
}

uint16_t Uploader::pendingCount(const String &dirPath) {
	File dir = SD.open(dirPath);
	if (!dir) return 0;
	uint16_t count = 0;
	File entry = dir.openNextFile();
	while (entry) {
		String name = String(entry.name());
		size_t size = entry.size();
		entry.close();
		if (name.endsWith(".csv") && size > HEADER_ONLY_MAX_BYTES && !alreadyUploaded(dirPath + "/" + name)) count++;
		entry = dir.openNextFile();
	}
	dir.close();
	return count;
}

void Uploader::removeEmptySessions(const String &dirPath) {
	// Names first, deletes after - never delete while iterating the folder.
	std::vector<String> empty;
	File dir = SD.open(dirPath);
	if (!dir) return;
	File entry = dir.openNextFile();
	while (entry) {
		String name = String(entry.name());
		if (name.endsWith(".csv") && entry.size() <= HEADER_ONLY_MAX_BYTES) empty.push_back(dirPath + "/" + name);
		entry.close();
		entry = dir.openNextFile();
	}
	dir.close();
	for (auto &path : empty) {
		SD.remove(path);
		SD.remove(path + ".uploaded");
	}
	if (!empty.empty()) Serial.printf("[boot] removed %u empty session files\n", (unsigned)empty.size());
}

void Uploader::cleanupOldFiles(const String &dirPath, uint32_t nowEpoch) {
	if (_cfg.retentionDays == 0 || nowEpoch == 0) return; // 0 retention = keep forever; 0 epoch = no reliable clock, never guess

	uint32_t retentionSec = _cfg.retentionDays * 24UL * 3600UL;

	File dir = SD.open(dirPath);
	if (!dir) return;

	File entry = dir.openNextFile();
	while (entry) {
		String name = String(entry.name());
		entry.close();

		if (name.endsWith(".uploaded")) {
			String sidecarPath = dirPath + "/" + name;
			File f = SD.open(sidecarPath, FILE_READ);
			uint32_t uploadedEpoch = 0;
			if (f) {
				uploadedEpoch = (uint32_t)f.parseInt();
				f.close();
			}

			// uploadedEpoch of 0 means it was marked before this board ever
			// had a GPS fix - unknown age, so leave it alone rather than
			// guess it's infinitely old and delete it immediately.
			if (uploadedEpoch != 0 && nowEpoch > uploadedEpoch &&
				(nowEpoch - uploadedEpoch) > retentionSec) {
				String csvPath = sidecarPath.substring(0, sidecarPath.length() - 9); // strip ".uploaded"
				SD.remove(sidecarPath);
				SD.remove(csvPath);
			}
		}

		entry = dir.openNextFile();
	}
	dir.close();
}
