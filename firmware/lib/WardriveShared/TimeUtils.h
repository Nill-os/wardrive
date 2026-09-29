#pragma once
#include <TinyGPS++.h>
#include <time.h>

// Best-effort Unix epoch (UTC) derived from a GPS fix's date/time. Returns 0
// if the GPS has never resolved a valid date+time yet, which callers treat
// as "clock unknown" (e.g. the auto-upload rate limiter refuses to run on a
// value of 0, but a manual double-click upload proceeds anyway).
inline uint32_t gpsEpoch(TinyGPSPlus &gps) {
	if (!gps.date.isValid() || !gps.time.isValid()) return 0;

	struct tm t = {};
	t.tm_year = gps.date.year() - 1900;
	t.tm_mon = gps.date.month() - 1;
	t.tm_mday = gps.date.day();
	t.tm_hour = gps.time.hour();
	t.tm_min = gps.time.minute();
	t.tm_sec = gps.time.second();

	time_t tt = mktime(&t);
	if (tt < 0) return 0;
	return (uint32_t)tt;
}
