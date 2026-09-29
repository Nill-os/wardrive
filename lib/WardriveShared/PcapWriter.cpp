#include "PcapWriter.h"

#pragma pack(push, 1)
struct PcapGlobalHeader {
	uint32_t magicNumber = 0xa1b2c3d4;
	uint16_t versionMajor = 2;
	uint16_t versionMinor = 4;
	int32_t thiszone = 0;
	uint32_t sigfigs = 0;
	uint32_t snaplen = PcapWriter::SNAPLEN;
	uint32_t network = 127; // LINKTYPE_IEEE802_11_RADIOTAP
};

struct PcapRecordHeader {
	uint32_t tsSec;
	uint32_t tsUsec;
	uint32_t inclLen; // captured length, on disk
	uint32_t origLen; // true on-air length, may exceed inclLen if snaplen-truncated
};

// Minimal radiotap header: present-flags for Flags (bit1), Channel (bit3),
// and Antenna Signal in dBm (bit5) only - the fields Wireshark needs to
// label a frame's channel/frequency and signal strength, without carrying
// fields this rig has no real data for (rate, TSFT, etc - better to omit a
// field entirely than to synthesize a fake value for it). Field ordering
// and each field's own alignment (relative to the start of this struct,
// per the radiotap spec) is fixed by the spec, not by struct declaration
// order convenience - do not reorder these members.
struct RadiotapHeader {
	uint8_t itVersion = 0;
	uint8_t itPad = 0;
	uint16_t itLen = sizeof(RadiotapHeader);
	uint32_t itPresent = 0x2A; // bit1 (Flags) | bit3 (Channel) | bit5 (Antenna Signal)

	uint8_t flags = 0;			  // no FCS-at-end, no other flags - ESP32 promiscuous capture strips the FCS
	uint8_t padToChannel = 0;	  // radiotap requires the 4-byte Channel field to start on a 2-byte boundary
	uint16_t chanFreqMHz = 0;
	uint16_t chanFlags = 0x0010; // 2 GHz spectrum
	int8_t antennaSignalDbm = 0;
};
#pragma pack(pop)

static uint16_t channelToFreqMHz(uint8_t channel) {
	if (channel >= 1 && channel <= 13) return 2407 + channel * 5;
	if (channel == 14) return 2484;
	return 0;
}

bool PcapWriter::begin(const String &dirPath, const String &role) {
	if (!SD.exists(dirPath)) SD.mkdir(dirPath);

	_path = dirPath + "/" + role + "_pcap_" + String((uint32_t)millis()) + ".pcap";
	_framesWritten = 0;
	_file = SD.open(_path, FILE_WRITE);
	if (!_file) return false;

	PcapGlobalHeader hdr;
	size_t written = _file.write((const uint8_t *)&hdr, sizeof(hdr));
	_file.flush();

	if (written != sizeof(hdr)) {
		_file.close();
		SD.remove(_path);
		return false;
	}
	return true;
}

void PcapWriter::writeFrame(uint32_t tsSec, uint32_t tsUsec, uint8_t channel, int8_t rssi,
							 const uint8_t *data, uint16_t capturedLen, uint16_t origLen) {
	if (!_file) return;

	RadiotapHeader radiotap;
	radiotap.chanFreqMHz = channelToFreqMHz(channel);
	radiotap.antennaSignalDbm = rssi;

	PcapRecordHeader rec;
	rec.tsSec = tsSec;
	rec.tsUsec = tsUsec;
	rec.inclLen = sizeof(RadiotapHeader) + capturedLen;
	rec.origLen = sizeof(RadiotapHeader) + origLen;

	_file.write((const uint8_t *)&rec, sizeof(rec));
	_file.write((const uint8_t *)&radiotap, sizeof(radiotap));
	_file.write(data, capturedLen);
	_framesWritten++;
}

void PcapWriter::flush() {
	if (_file) _file.flush();
}

// Unlike WigleWriter::close(), an empty (header-only) pcap file is left in
// place rather than deleted - a session with zero management frames seen at
// all (SD swapped mid-drive, antenna unplugged, etc) is itself diagnostically
// useful to notice, and pcap files are opt-in via config in the first place
// so they don't carry WigleWriter's "over 100 empty files from normal use"
// clutter problem.
void PcapWriter::close() {
	if (_file) _file.close();
}
