#pragma once
#include <stdint.h>

// ESP-NOW transport for scaling the rig with wireless nodes. Extra sniffer
// nodes (WiFi or BLE) that aren't wired into the UART chain broadcast their
// sightings to an aggregator node, which geotags them with its own GPS and
// forwards them to the CYD exactly like a wired sighting. See docs/SCALING.md.
//
// Everything here is gated on the WARDRIVE_ESPNOW build flag, so a rig built
// without it is byte-for-byte the wired rig - nothing below is compiled in.
//
// The one hard constraint this works around: a WiFi radio can't channel-hop to
// sniff and reliably receive ESP-NOW on a fixed channel at the same time. So:
//   - A dedicated aggregator (a wifi_node built with WARDRIVE_ESPNOW_DEDICATED)
//     parks its radio on ESPNOW_CHANNEL and only receives - it doesn't sniff.
//   - A sniffing node that also sends (a WiFi satellite) hops away to
//     ESPNOW_CHANNEL just long enough to transmit, then restores its channel.
//   - A ble_node has its WiFi radio free (it only scans BLE), so it sends with
//     no conflict at all - the low-risk path.

// One sighting on the wire. Packed and kept well under ESP-NOW's 250-byte cap.
struct __attribute__((packed)) EspNowSighting {
	uint8_t magic;    // ESPNOW_MAGIC - ignore anything else on the air
	uint8_t version;  // ESPNOW_PROTO_VERSION
	uint8_t type;     // ESPNOW_TYPE_WIFI or ESPNOW_TYPE_BLE
	uint8_t nodeId;   // sender's node index, for diagnostics
	uint8_t mac[6];   // observed BSSID / device MAC
	int8_t rssi;
	uint8_t channel;  // WiFi channel (0 for BLE)
	char name[33];    // SSID or BLE name, NUL-terminated
	char auth[24];    // WiFi auth string, e.g. "[WPA2-PSK-CCMP][ESS]" (empty for BLE)
	uint8_t mfgLen;   // BLE manufacturer-data length (0..sizeof mfg)
	uint8_t mfg[24];  // BLE manufacturer data, raw bytes
};

static const uint8_t ESPNOW_MAGIC = 0xA7;
static const uint8_t ESPNOW_PROTO_VERSION = 1;
static const uint8_t ESPNOW_TYPE_WIFI = 0;
static const uint8_t ESPNOW_TYPE_BLE = 1;

// The fixed channel every node uses for ESP-NOW. Override with -D ESPNOW_CHANNEL.
#ifndef ESPNOW_CHANNEL
#define ESPNOW_CHANNEL 1
#endif

#if defined(WARDRIVE_ESPNOW)

// Bring up ESP-NOW as a receiver parked on `channel`. `cb` is called for every
// valid sighting received. `bringUpWifi` should be true on a board whose WiFi
// isn't otherwise started (e.g. a ble_node); false on a wifi_node whose radio
// is already up in promiscuous mode. Returns false if init failed.
bool espnowBeginReceiver(uint8_t channel, void (*cb)(const EspNowSighting &), bool bringUpWifi);

// Bring up ESP-NOW as a sender that broadcasts on `channel`. Same `bringUpWifi`
// rule as above. Returns false if init failed.
bool espnowBeginSender(uint8_t channel, bool bringUpWifi);

// Broadcast one sighting. On a node that is also sniffing, this briefly sets the
// radio to the ESP-NOW channel and restores the previous channel afterward, so
// batching sends keeps the sniffing disruption small.
void espnowSend(const EspNowSighting &s);

#endif // WARDRIVE_ESPNOW
