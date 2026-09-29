#include "Uploader.h"
#include <WiFi.h>
#include <HTTPClient.h>
#include <SD.h>
#include <Preferences.h>
#include <vector>
#include <utility>

static const char *PREFS_NS = "wardrive";
static const char *PREFS_KEY_LAST_UPLOAD = "last_upload";

// Builds the whole multipart body in heap before sending. Fine for the
// modest CSV files a rate-limited upload cycle produces; if you let capture
// files grow very large between uploads on a non-PSRAM board, switch this to
// a chunked/streaming implementation instead.
//
// Read in a real buffer, not file.read() one byte at a time - a single-byte
// SD read has enough per-call overhead (locking, block lookup) that even a
// modest CSV could take many seconds to build this way. A 512-byte buffer
// cuts that to a handful of reads per file.
static bool buildMultipartBody(const String &filePath, const String &fieldName,
								String &outBody, String &outBoundary) {
	File file = SD.open(filePath, FILE_READ);
	if (!file) return false;

	outBoundary = "----wardriveESP32Boundary7d1a9c";
	String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);

	String head = "--" + outBoundary + "\r\n" +
				  "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n" +
				  "Content-Type: text/csv\r\n\r\n";
	String tail = "\r\n--" + outBoundary + "--\r\n";

	size_t fileSize = file.size();
	outBody = "";
	outBody.reserve(head.length() + fileSize + tail.length());
	outBody += head;

	uint8_t buf[512];
	while (file.available()) {
		size_t n = file.read(buf, sizeof(buf));
		if (n == 0) break;
		outBody.concat((const char *)buf, n);
	}
	outBody += tail;
	file.close();
	return true;
}

// http is a long-lived, per-destination client (setReuse(true), kept open
// across every file in a batch by the caller) - a fresh HTTPClient per file
// meant a full TCP+TLS handshake per file, which dominated upload time far
// more than the read/build work ever did. Reusing the connection lets every
// file after the first skip that handshake entirely as long as the server
// keeps it alive.
static bool postMultipartBody(HTTPClient &http, const String &url, const String &body, const String &boundary,
							   const std::vector<std::pair<String, String>> &headers) {
	// Without these, a network that associates fine but has no real route to
	// the internet (bad DNS, dead upstream) can leave http.POST() blocked
	// for a very long time with no way out - this bounds the worst case
	// instead of freezing the whole board's main loop indefinitely.
	http.setConnectTimeout(8000);
	http.setTimeout(10000);
	http.begin(url);
	http.addHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
	for (auto &h : headers) http.addHeader(h.first, h.second);

	Serial.printf("[upload] free heap before POST %s: %u bytes (largest block %u)\n",
				  url.c_str(), (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getMaxAllocHeap());
	uint32_t start = millis();
	int code = http.POST((uint8_t *)body.c_str(), body.length());
	Serial.printf("[upload] POST %s -> %d (%lums, %u bytes)\n",
				  url.c_str(), code, (unsigned long)(millis() - start), (unsigned)body.length());

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

bool Uploader::uploadToWdgwars(HTTPClient &http, const String &body, const String &boundary) {
	// NOTE: wdgwars.pl's exact auth header/field name for /api/upload-csv is
	// documented on its own site behind a login (Help section after signing
	// in at wdgwars.pl). This uses the most common convention (X-Api-Key
	// header, "file" multipart field) - confirm against your account's docs
	// and adjust here if the key isn't accepted.
	std::vector<std::pair<String, String>> headers;
	headers.push_back({"X-Api-Key", _cfg.wdgwarsApiKey});
	return postMultipartBody(http, "https://wdgwars.pl/api/upload-csv", body, boundary, headers);
}

bool Uploader::uploadToWigle(HTTPClient &http, const String &body, const String &boundary) {
	if (_cfg.wigleApiToken.length() == 0) return true; // WiGLE upload optional
	std::vector<std::pair<String, String>> headers;
	headers.push_back({"Authorization", "Basic " + _cfg.wigleApiToken});
	return postMultipartBody(http, "https://api.wigle.net/api/v2/file/upload", body, boundary, headers);
}

UploadResult Uploader::uploadPending(const String &dirPath, uint32_t nowEpoch, bool force,
									  UploadProgressCallback onProgress) {
	if (!_cfg.valid) return UploadResult::Skipped;

	if (!force) {
		if (nowEpoch == 0) return UploadResult::Skipped; // no reliable clock yet
		uint32_t last = lastUploadEpoch();
		if (last != 0 && nowEpoch > last && (nowEpoch - last) < _cfg.minUploadIntervalSec) {
			return UploadResult::Skipped;
		}
	}

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
		entry.close();

		if (isCsv) {
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
				String body, boundary;
				bool built = buildMultipartBody(fullPath, "file", body, boundary);
				bool wdgOk = built && uploadToWdgwars(httpWdg, body, boundary);
				bool wigleOk = built && uploadToWigle(httpWigle, body, boundary);
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
