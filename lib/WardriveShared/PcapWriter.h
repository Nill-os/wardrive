#pragma once
#include <Arduino.h>
#include <SD.h>

// Writes a standard .pcap file (classic libpcap format, LINKTYPE 127 =
// IEEE802_11_RADIOTAP) so a session's raw management frames can be opened
// directly in Wireshark for forensic analysis - a level of detail the
// summarized WigleWifi CSV output deliberately doesn't carry. Each captured
// frame gets a synthesized minimal radiotap header (channel + dBm signal)
// prepended ahead of the real 802.11 bytes captured off the air, exactly as
// esp_wifi's promiscuous callback delivered them.
//
// Deliberately scoped to management frames only (beacons, probe requests/
// responses, assoc/deauth/etc) - the same WIFI_PKT_MGMT filter the WigleWifi
// capture already uses. WPA's 4-way handshake is carried in EAPOL frames,
// which are DATA-type 802.11 frames, not management-type, so this capture
// path is structurally incapable of ever recording a handshake - it never
// touches WIFI_PKT_DATA at all, by design, not by omission.
class PcapWriter {
public:
	// Creates /<dirPath>/<role>_<timestamp>.pcap and writes the global pcap header.
	bool begin(const String &dirPath, const String &role);

	// data/capturedLen is the raw 802.11 frame exactly as captured (starting
	// at the MAC header, no radiotap prefix yet - writeFrame() synthesizes
	// that); capturedLen must already be <= SNAPLEN, since truncation has to
	// happen at capture time before the frame ever fits in a fixed-size
	// queue item. origLen is the true on-air frame length reported by the
	// driver, which can be larger - recorded as the pcap record's orig_len
	// so a truncated frame is still visibly marked as truncated in
	// Wireshark, the same "capture length vs. on-the-wire length"
	// distinction any pcap tool makes for an oversized frame.
	void writeFrame(uint32_t tsSec, uint32_t tsUsec, uint8_t channel, int8_t rssi,
					 const uint8_t *data, uint16_t capturedLen, uint16_t origLen);

	void flush();
	void close();

	// Generous enough that only the rare beacon stuffed with many vendor IEs
	// (802.11k/v/r, HE capabilities, etc) ever gets truncated - and even
	// then it's still marked as truncated in the pcap (orig_len > incl_len),
	// not silently shortened.
	static const uint16_t SNAPLEN = 1024;

private:
	File _file;
	String _path;
	uint32_t _framesWritten = 0;
};
