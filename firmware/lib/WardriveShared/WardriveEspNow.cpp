#include "WardriveEspNow.h"

#if defined(WARDRIVE_ESPNOW)
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <string.h>

static uint8_t s_channel = 1;
static void (*s_cb)(const EspNowSighting &) = nullptr;
static const uint8_t BROADCAST[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

// The ESP-NOW receive-callback signature changed between arduino-esp32 2.x and
// 3.x. Support both so this builds on whatever core the pinned platform ships.
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
static void onRecv(const esp_now_recv_info_t *, const uint8_t *data, int len) {
#else
static void onRecv(const uint8_t *, const uint8_t *data, int len) {
#endif
	if (!s_cb || len != (int)sizeof(EspNowSighting)) return;
	EspNowSighting s;
	memcpy(&s, data, sizeof(s));
	if (s.magic != ESPNOW_MAGIC || s.version != ESPNOW_PROTO_VERSION) return;
	s.name[sizeof(s.name) - 1] = '\0';  // never trust the wire to be NUL-terminated
	s.auth[sizeof(s.auth) - 1] = '\0';
	if (s.mfgLen > sizeof(s.mfg)) s.mfgLen = sizeof(s.mfg);
	s_cb(s);
}

static bool commonInit(uint8_t channel, bool bringUpWifi) {
	s_channel = channel ? channel : 1;
	if (bringUpWifi) {
		// A board whose radio isn't otherwise in use (a ble_node): bring WiFi up
		// in station mode and park it on the ESP-NOW channel.
		WiFi.mode(WIFI_STA);
		WiFi.disconnect();
	}
	esp_wifi_set_promiscuous(false);  // no-op if it wasn't on
	esp_wifi_set_channel(s_channel, WIFI_SECOND_CHAN_NONE);
	if (esp_now_init() != ESP_OK) return false;
	esp_now_peer_info_t peer;
	memset(&peer, 0, sizeof(peer));
	memcpy(peer.peer_addr, BROADCAST, 6);
	peer.channel = s_channel;
	peer.encrypt = false;
	peer.ifidx = WIFI_IF_STA;
	if (esp_now_add_peer(&peer) != ESP_OK) return false;
	return true;
}

bool espnowBeginReceiver(uint8_t channel, void (*cb)(const EspNowSighting &), bool bringUpWifi) {
	s_cb = cb;
	if (!commonInit(channel, bringUpWifi)) return false;
	return esp_now_register_recv_cb(onRecv) == ESP_OK;
}

bool espnowBeginSender(uint8_t channel, bool bringUpWifi) {
	return commonInit(channel, bringUpWifi);
}

void espnowSend(const EspNowSighting &s) {
	// A sniffing sender is on some other channel; hop to the ESP-NOW channel just
	// for the transmit, then restore. A dedicated sender is already on-channel,
	// so this is a no-op there.
	uint8_t prim = s_channel;
	wifi_second_chan_t sec = WIFI_SECOND_CHAN_NONE;
	esp_wifi_get_channel(&prim, &sec);
	bool hopped = (prim != s_channel);
	if (hopped) esp_wifi_set_channel(s_channel, WIFI_SECOND_CHAN_NONE);
	esp_now_send(BROADCAST, (const uint8_t *)&s, sizeof(s));
	if (hopped) esp_wifi_set_channel(prim, sec);
}

#endif // WARDRIVE_ESPNOW
