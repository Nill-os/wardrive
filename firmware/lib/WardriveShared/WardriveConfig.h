#pragma once
#include <Arduino.h>

// Loaded from /config.cfg on the SD card (same file format/content copied onto
// both boards' cards). Simple `key=value` lines, '#' starts a comment.
//
//   wifi_ssid=YourHomeWifi
//   wifi_pass=yourpassword
//   backup_wifi_ssid=YourPhoneHotspot
//   backup_wifi_pass=yourhotspotpassword
//   wigle_api_token=base64EncodedApiNameColonApiToken
//   wdgwars_api_key=64hexcharacterkeyfromwdgwars.pl/profile
//   min_upload_interval_sec=21600
//   home_lat=37.774900
//   home_lon=-122.419400
//   home_radius_m=150
//   retention_days=0
//   channel_hop_ms=150
//
// backup_wifi_ssid/backup_wifi_pass are optional - a second network (e.g.
// your phone's hotspot) that upload falls back to if wifi_ssid can't be
// joined within the connect timeout. Leave blank to only ever try wifi_ssid.
//
// wigle_api_token is WiGLE's "Encoded for Use" credential from
// https://wigle.net/account (Basic-auth ready, apiName:apiToken already
// base64-encoded). Leave it blank to skip WiGLE uploads and only upload to
// wdgwars.pl.
//
// home_lat/home_lon/home_radius_m gate the automatic "dock mode" upload
// check on GPS position, not just WiFi visibility - the board only attempts
// to join wifi_ssid and upload when its last GPS fix is within
// exclude_radius_m, if set, drops any sighting within that many meters of
// (home_lat, home_lon) before it is written to the SD card or uploaded - the
// rig's own version of the phone app's home exclusion zone (they are separate
// settings; set both). It reuses home_lat/home_lon.
//
// home_radius_m meters of (home_lat, home_lon). Leave home_radius_m at 0 (or
// unset) to disable the geofence and fall back to WiFi-visibility-only
// gating. Double-click always uploads immediately regardless of location.
//
// retention_days: once a session file has been successfully uploaded, it's
// kept locally on the SD card as a backup for this many days before being
// deleted. Defaults to 0 (keep forever, never auto-delete) - at realistic
// driving volumes this never gets close to filling a normal-sized card, so
// there's no real reason not to keep everything as a standing local backup.
// Set to a nonzero value only if you actually want old uploaded files
// cleaned up after that many days.
//
// pcap_capture: 1/true to also write a raw .pcap file (radiotap + full
// 802.11 management frames - beacons, probe requests/responses, assoc/
// deauth/etc) alongside the usual WigleWifi CSV each session, for opening
// directly in Wireshark. Off by default: most people only ever want the
// summarized CSV, and raw captures use meaningfully more SD space per
// session. Scoped to management frames only - it never captures WPA's
// EAPOL handshake, since that rides in DATA frames, a type this rig's
// promiscuous filter never receives in the first place.
//
// channel_hop_ms: how long wifi_node's promiscuous sniffer dwells on each
// 2.4GHz channel before moving to the next one, 1-11 in sequence. Lower
// catches more distinct APs per minute of driving at highway speed (more
// hops = more chances to land on a given AP's channel while it's still in
// range) at the cost of less time listening per channel; higher favors
// catching weaker/slower-advertising devices at the cost of fewer total
// channel visits per minute. Defaults to 150ms (the value this was
// hardcoded to before this became configurable) if unset or invalid.
struct WardriveConfig {
	String wifiSsid;
	String wifiPass;
	String backupWifiSsid;
	String backupWifiPass;
	String wigleApiToken;
	String wdgwarsApiKey;
	uint32_t minUploadIntervalSec = 6UL * 3600UL; // 6 hours default
	double homeLat = 0.0;
	double homeLon = 0.0;
	double homeRadiusM = 0.0; // 0 = geofence disabled
	double excludeRadiusM = 0.0; // drop sightings within this many m of home; 0 = off
	uint32_t retentionDays = 0; // 0 = keep uploaded files forever
	uint32_t channelHopMs = 150;
	bool pcapCaptureEnabled = false;
	bool valid = false;
};

// Returns true if a usable config was loaded (wifi_ssid + wdgwars_api_key present).
bool loadWardriveConfig(const char *path, WardriveConfig &outCfg);
