#pragma once
#include <Arduino.h>
#include <HTTPClient.h>
#include "WardriveConfig.h"

enum class UploadResult {
	Ok,			  // at least one file actually succeeded and was marked
	Skipped,	  // nothing to do (rate-limited, no files, or config invalid)
	WifiFailed,	  // couldn't join wifi_ssid (or the backup) within the timeout
	UploadFailed, // wifi connected and files were attempted, but none succeeded
};

// Fired at each stage of an upload attempt (connecting, then once per file)
// so the caller can show live progress instead of a frozen screen -
// uploadPending() blocks the whole caller for its full duration (WiFi
// connect + every file's HTTP round trip), so without this there's no way
// for a UI to update at all until it returns.
using UploadProgressCallback = void (*)(const char *stage, const char *detail);

// Handles the "double-click" / dock-mode upload flow: join the configured
// WiFi network, POST any not-yet-uploaded *.csv files in dirPath to wdgwars.pl
// and (if configured) WiGLE, mark successes with a "<file>.uploaded" sidecar
// so they're never re-sent, then disconnect.
class Uploader {
public:
	explicit Uploader(const WardriveConfig &cfg);

	// nowEpoch: current UTC unix time from GPS (0 if no fix yet - see TimeUtils.h).
	// force=true (double-click) always attempts, ignoring the rate limiter and
	// proceeding even if nowEpoch is 0. force=false (dock-mode) requires a
	// known nowEpoch and honors cfg.minUploadIntervalSec against the last
	// successful upload's timestamp.
	// onProgress (optional, may be nullptr) is called synchronously from
	// within this same call - not from another task - so it's safe to
	// touch a display directly from it.
	UploadResult uploadPending(const String &dirPath, uint32_t nowEpoch, bool force,
								UploadProgressCallback onProgress = nullptr);

	// Deletes already-uploaded session files (and their .uploaded sidecars)
	// once they're older than cfg.retentionDays, so the SD card doesn't fill
	// up silently over weeks of driving. No-op if retentionDays is 0 or
	// nowEpoch is 0 (no reliable clock yet - never delete on a guess).
	void cleanupOldFiles(const String &dirPath, uint32_t nowEpoch);

	// Cheap pre-checks, so the caller can skip the expensive setup around an
	// upload (dropping the phone's BLE link, the progress overlay) when
	// there's nothing to send or the dock-mode cooldown hasn't passed.
	bool hasPending(const String &dirPath) { return pendingCount(dirPath) > 0; }
	uint16_t pendingCount(const String &dirPath);

	// Deletes header-only session files - runs a power cut ended before their
	// first row (a clean stop deletes these itself). Only call while no
	// session file is open, i.e. at boot.
	void removeEmptySessions(const String &dirPath);

	// Unix time of the last upload that sent at least one file (0 = never,
	// or never with a GPS clock).
	uint32_t lastUploadEpoch();
	// Record an upload time from outside the uploader - used when the phone app
	// uploads a session on the rig's behalf, so the "last upload" age still
	// resets. Takes the phone's wall-clock epoch (seconds).
	void setLastUploadEpoch(uint32_t epoch);
	bool autoUploadDue(uint32_t nowEpoch);
	bool configValid() const { return _cfg.valid; }

	// Test hook: send every upload to this URL instead of wdgwars/WiGLE, so
	// the upload path can be exercised without real (or fake) data reaching
	// either service. Empty = normal endpoints.
	void setEndpointOverride(const String &url) { _endpointOverride = url; }

private:
	const WardriveConfig &_cfg;
	String _endpointOverride;
	String endpoint(const char *real) const;

	bool connectWifi(uint32_t timeoutMs = 15000);
	void disconnectWifi();

	bool uploadToWdgwars(HTTPClient &http, const String &filePath);
	bool uploadToWigle(HTTPClient &http, const String &filePath);

	bool alreadyUploaded(const String &filePath);
	void markUploaded(const String &filePath, uint32_t nowEpoch);
};
