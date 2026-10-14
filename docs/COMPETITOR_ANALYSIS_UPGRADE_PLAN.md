# Nill OS – Wardriver: competitor analysis & upgrade plan

Reverse-engineered the four shipping baseline apps (decompiled with jadx, read on the local LLM so this
report is grounded in the real code, not guesses). Each conclusion ties back to decompiled evidence.

| App | Package | What it is | Best ideas to take |
|---|---|---|---|
| **WiGLE WiFi 2.111** | `net.wigle.wigleandroid` | The reference wardriver (open-ish). WiFi/BT/BLE/cell → WiGLE DB. | Exact encryption classification, 6/60 GHz channel tables, GNSS Kalman smoothing, WiGLE tile + search layers, M8B offline index, route simplification. |
| **Wardrive Go 3.1.20** | `com.rocketgod.warble` | Phone + SDR (HackRF/RTL-SDR) wardriver, uploads to WiGLE + WDGWars. | WiGLE **search/lookup** API, WiGLE v2 file upload, WDGWars team standings, ADS-B aircraft enrichment, coverage/dense-cell grid, `notable`/`via_monitor` flags. |
| **WDGWars Beta 1.2.1** | `pl.wdgwars.client` | The *game* client for the WDGWars backend your app already uploads to. | The whole game/social layer: territories, bounties, leaderboard, teams, chat, "rabbit" moving-node alerts, decay grid, crowd Flock-confirm, FCM push, proof-of-work login. |
| **Biscuit Manager 1.0.21.1** | `com.example.biscuitmanager` | ESP32 attack-tool controller + "counter-surveillance" wardriver. | Much richer **detection signatures** (Pwnagotchi, Meta Quest, Ray-Ban, Axon, Flock sub-types), BLE service-UUID fingerprints, 25 m cluster dedup, geofence alerts, jam-*detection*. **Attack modules excluded — see §7.** |

Your app (`com.dreknil.wardrivebridge`) already has: WiGLE+WDGWars CSV/JSON upload, phone WiFi(5 GHz)/BLE/cell/GNSS,
rig link, AirTag/Flipper/Flock/skimmer/drone/mesh/glasses/actioncam/policecam/Pineapple detection, map+heatmap,
fox-hunt, antenna check, GPX/KML/Aircrack export, Meshtastic, car screen, widget, tile, home zone, blacklist.
So this plan is **only the deltas** — what these four do that yours doesn't yet.

---

## 1. Detection signatures to ADD (file: `DeviceSignatureDetection.kt` / `TrackerDetection.kt`)

All of these are *detect-and-map* (passive), which is exactly what a wardriver should do. Exact values from Biscuit/WiGLE:

**BLE service-UUID fingerprints** (strongest signal — a device advertising these is almost certainly the named gear):
- `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` — **Nordic UART (NUS)**: Flipper Zero, Pwnagotchi, most ESP32/nRF hack tools, Meshtastic serial.
- `4FAFC201-1FB5-459E-8FCC-C5C9C331914B` and `BEB5483E-36E1-4688-B7F5-EA07361B26A8` — **ESP32 Arduino BLE example UUIDs**: Marauder, ESP32 Flock/wardrive firmware, many DIY tools.
- `00001530-1212-EFDE-1523-785FEABCD123` — **Nordic DFU** (firmware-update mode device).
- `7905F431-B5CE-4E99-A40F-4B1E122D00D0` (ANCS), `89D3502B-0F36-433A-8EF4-C502AD55F8DC` (AMS), `9FBF120D-6301-42D9-8C58-25E699A21DBD` — **Apple Continuity** services → corroborate AirTag/Find-My (you already detect AirTag by company ID; add these as confirmation).

**Name/ID patterns** (Biscuit `MetaDevice`, `FlockWardriveEntry`, pwnagotchi/axon classes):
- **Meta Quest / VR headsets**: name (trimmed, lowercased) starts with `quest`, `meta quest`, `oculus quest`.
- **Ray-Ban / Meta smart glasses**: name contains `ray-ban` or `rayban`; Meta company ID `0x00FE` → you have `alertGlasses`, extend it with these.
- **Meta company ID `0xFEB8`** (hex id, `0x` stripped == `FEB8`) → Meta/Facebook device.
- **Pwnagotchi**: BLE device advertising NUS + name pattern; store `pwndCount` if present. (New category — a Pwnagotchi near you is a wardriver/handshake-sniffer.)
- **Axon / police body-cam & taser**: Biscuit labels these `"Axon Equipment Detected"` and `"Flock Like Signature Detected"` — you already have `alertPoliceCam`; add Axon name/OUI matching.
- **Flock camera sub-types** (Biscuit `deviceName` → type): `Penguin`, `Pigvision`, `FS Ext Battery` (Flock battery pack), default `FLOCK` if name contains `flock`. Store the sub-type so the field report distinguishes a camera from its battery/solar unit.
- **Detection method tag** `WPROBE` (Flock detected via WiFi probe request vs BLE) — record *how* you saw it (`radioSource` = WIFI/BLE), useful for confidence.

**Signal-strength buckets** (Biscuit `getSignalStrength`): classify RSSI into `EXCELLENT` ≥ -50, `GOOD` -50..-60, `FAIR` -60..-70, `WEAK` < -70 — show this chip in the live tools instead of raw dBm.

**Defensive jam-detection** (Biscuit `JamDetectionSnapshot`, keep this, it's defensive): track `samplesWifi`/`samplesBle` per time window; a sudden collapse in sample count + a spike in **deauth frames seen in the air** = possible jamming/deauth attack nearby → alert. (Detecting deauths ≠ sending them.)

---

## 2. WiFi/radio accuracy fixes (files: `PhoneWifiScanner.kt`, `PhoneCellScanner.kt`, `CsvExporter.kt`, `LocationTracker.kt`)

**Encryption classification** — WiGLE's exact capability→type mapping (use for display + a `security` column). Match against the AP `capabilities` string:
- WPA3: contains `[WPA3`, `EAP_SUITE_B_192`, or `SAE`
- WPA2: contains `[WPA2` or `[RSN`
- WPA: contains `[WPA-`
- WEP: contains `[WEP` (or `WEP` token)
- OPEN/NONE: `[]`, `[ESS]`, or empty

**Channel tables** — add 6 GHz and 60 GHz (WiGLE ships full tables; you have 2.4/5):
- 6 GHz channels: 1,5,9,…,93 (step 4). 60 GHz: 1–6. Map frequency→band so 6E rows export correctly.

**GNSS smoothing** (WiGLE `GnssListener`) — you log raw fixes; WiGLE applies: a "goldilocks" accept radius `3.0 m`, LERP between fixes with max/min thresholds `200 m`/`20 m`, and a speed sanity cap (`Mach 1.3` = `445.9 m/s`) to drop impossible jumps. This cuts GPS jitter on the map and in rows.

**Cell signal sentinel** — WiGLE maps raw ASU `99` → `-113 dBm` (`CELL_MIN_STRENGTH`). You already sanitize `Int.MAX_VALUE`; also treat `99` on GSM as -113 rather than a real value.

**New columns to log** (seen in WiGLE `network` table): `rcois` (Passpoint/Hotspot 2.0 roaming consortium OIs), `mfgrid` (BLE manufacturer id), `service` (BLE service uuid). These make your WiGLE CSV richer and enable OUI/mfg filtering.

---

## 3. Upload & lookup APIs to ADD

**WiGLE search/lookup** (Wardrive Go + Biscuit both use it) — lets a user tap a network and see WiGLE's known data, dedupe against the global DB, and show "new vs already-known":
- `GET https://api.wigle.net/api/v2/network/search` — params `netid`, `ssidlike`, `encryption`, `latrange1/2`, `longrange1/2`, cursor `searchAfter`; auth `Authorization: Basic <b64(apiName:apiToken)>`; page size 100.
- Detail: `api/v3/detail/wifi/{netid}`, `api/v3/detail/bt/{netid}`, `api/v2/bluetooth/detail`, `api/v2/network/detail?netid=`.
- Stats/standings you partly have: `api/v2/stats/user`, `api/v2/stats/site`, `api/v2/stats/standings?sort=`, `api/v2/stats/group`, group/`groupForUser`, `groupMembers`.

**WiGLE map tiles layer** (WiGLE `clientTile`) — overlay WiGLE's known networks on your osmdroid map:
`https://wigle.net/clientTile?zoom=%d&x=%d&y=%d&startTransID=%s&endTransID=%s` (+`&sizeX=512&sizeY=512` hi-dpi, `&onlymine=1`/`&notmine=1`). A tile `TileSource` in `MapManager.kt`.

**WiGLE v2 file upload** (Wardrive Go `WigleApi`) — a second upload route besides your CSV POST: `POST api/v2/file/upload`, multipart, field `file`, field `donate=on`, response `success` + `transId`/`transid`. Store the transId per run (you can then poll `api/v2/file/transactions`).

**WDGWars game endpoints** (WDGWars client — the big one; you only upload to WDGWars today, you don't *play* it). All under `https://wdgwars.pl/api`, auth `X-API-Key`:
- You/stats: `/me`, `/me/aps?limit&since`, `/me/route?from&to`, `/me/sessions`, `/my-decay?bbox&mode=points`, `/user-home`, `/map-state?regionGroup=WORLD`.
- Territories: `/territories`, `/member-territories?grid_lat&grid_lng`, grid `/api/territory-grid?lat&lng&cells`.
- Bounties: `/bounties`, `/bounties/mine`, `/bounties/{id}/accept` (fields `captures_so_far`, `radius_m`, `reward_credits`, `status`).
- Leaderboard/teams: `/leaderboard` (today/week/all_time), `/team/me`, `/team/join`, `/team/invite`, `/team/messages` (GET `since&limit`, POST `channel,body`, DELETE `{id}`).
- Live alerts: `/rabbits?mode=live` (moving nodes), `/rabbits/alerts` POST `enabled`; `/flock-cameras?bbox`, `/flock-confirm` POST `device_id,verdict` (crowd-sourced Flock confirmation — feeds back into your Flock detector!); `/aircraft`, `/meshcore`, `/workshop/bt?lang`.
- Auth/push: `/auth/challenge` + `/auth/login` with **proof-of-work** (fields `salt`,`target`,`range`, solve a hash), `/me/fcm-token` (register for push: territory-lost, bounty, mention, giveaway, chat).

**WDGWars team standings** (Wardrive Go `WdgwStats`): `GET /api/stats/standings`, cache 64 entries / 120 s TTL.

**Wardrift / wardrive.info** (Biscuit third destination) — `https://api.wardrive.info/api/v1/uploads/csv` & `/uploads/file`, `X-API-Key`, envelope `{success,data,error}`, dates `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`. Optional extra upload target.

---

## 4. Map / UX upgrades

- **Clustering** (WiGLE): cluster markers above zoom threshold; `CLUSTER_ALWAYS_AT=30`, grid cells 4–12 by zoom, recluster delay 1000 ms. Your map gets slow with thousands of pins — add this.
- **Route line simplification** (WiGLE + Biscuit): Douglas-Peucker; coarse ε `20 m` above 15 000 pts, fine ε `50 m` above 5 000, polyline cap `5000` points. Keeps the track line cheap to draw.
- **Coverage / dense-cell grid** (Wardrive Go): snap to `0.5°` grid, count per cell, shade "dense" cells — a heat-coverage layer distinct from your signal heatmap.
- **Signal-strength chips** and **security badges** in Live WiFi/BLE lists (from §1/§2).
- **M8B offline index** (WiGLE) — a memory-bounded on-device index (SipHash, `SLICE_BITS=30`, MGRS cells) of "networks I've already seen" so dedup/"is this new?" works offline without the DB scan. Advanced; do last.

---

## 5. Data-model additions (file: `AppDatabase.kt`, new migration)

From Wardrive Go's `observations` table — add columns you lack: `maker`, `category`, `icon`, `times_seen`, `wifi_standard`, `via_monitor`, `notable` (user star), `in_wigle`/`in_wdgw` + `*_sent_at` (per-network upload tracking, not just per-run). From WDGWars local cache: a `route` table (your track points) and per-run `trans_id`. Wardrive Go DB is at v26 with a background reclassify pass (page 2000, sleep 15 ms, thread prio 19) — worth copying the pattern if you add `maker`/`category` backfill.

---

## 6. Prioritized "add this" list

**Tier 1 — cheap, high value, pure wardriving:**
1. Encryption classification + `security` column + security badges (§2).
2. New BLE UUID fingerprints + Meta Quest / Ray-Ban / Pwnagotchi / Axon / Flock-subtype detection (§1).
3. Signal-strength buckets + 6/60 GHz channel tables (§1/§2).
4. GNSS smoothing (§2) — immediate map-quality win.
5. `notable` star flag + `times_seen` + per-network upload flags (§5).

**Tier 2 — bigger features:**
6. WiGLE search/detail lookup + "new vs known" indicator (§3).
7. WiGLE tile overlay layer (§3/§4).
8. Map clustering + route simplification (§4).
9. WDGWars game client: me/stats, territories, leaderboard, teams, push, bounties, rabbit alerts, crowd Flock-confirm (§3). This is the single biggest feature set — it turns your uploader into the full WDGWars app.
10. Coverage grid layer + WDGWars standings (§3/§4).

**Tier 3 — advanced / niche:**
11. ADS-B aircraft enrichment & display (Wardrive Go; needs a 1090 MHz SDR for live, but the enrich/overlay from WDGWars `/aircraft` works without one).
12. WiGLE v2 file-upload route + transaction polling (§3).
13. Wardrift/wardrive.info as a third upload target (§3).
14. M8B offline index (§4).
15. Defensive jam/deauth-attack *detection* alert (§1).

---

## 7. Excluded on purpose (Biscuit's attack modules — will NOT go in your app)

Biscuit bundles an ESP32 **attack** controller: WiFi **deauth/jamming**, **evil portal** (captive-portal credential phishing), **EAPOL/PMKID handshake capture for cracking**, and **HID injection** automation. I'm not building these into your app, and I'd decline regardless of the request:
- They are not wardriving (which is passive observation), they're offensive.
- Shipping them gets the app removed from Play/F-Droid and can be illegal to *use* against networks you don't own — the opposite of your goal of a publishable baseline.
- **Wardrive Go's PMKID + WPA-Sec cracking pipeline** (`wpa-sec.stanev.org` get_key/upload) is the same category — capturing handshakes to crack keys. Excluded.

What you *can* safely do, and is genuinely useful, is the **defensive mirror**: *detect* deauth frames, jamming, evil-portal/Pineapple APs, and Pwnagotchis in the air and alert on them (§1). You already do this for Pineapples/Flippers — the new signatures extend it.

---

*Evidence: decompiled sources under the session scratchpad; per-app local-LLM reports (`re/<app>/REPORT.md`) and
deterministic fact dumps (`re/<app>_facts.md`). Baseline APK versions as listed in the table.*

---

## Build status (October 14, 2026)

Shipped (commits 1bcd3ee, 81df38f, e0bfed1, e56810b):
- WiFi encryption classification: WPA3 from SAE/EAP_SUITE_B_192, WPA2 from RSN, OWE.
- 60 GHz channel mapping.
- Flock sub-types (Pigvision, FS Ext Battery, bare Penguin).
- Meta Quest / Oculus added to the glasses category.
- GPS outlier filter (WiGLE Mach 1.3 speed cap).
- Hack-tool / Pwnagotchi detection: new category (DB v7), field-report bucket, KML, alert toggle.
- Signal-strength labels (excellent/good/fair/weak) in device details.

Not a clean fit for this app (skipped): `times_seen` and the `notable` star conflict with the
deliberate "log once per run per MAC" model; adding them means per-MAC cross-run state.

Remaining, larger (each its own batch):
- WiGLE network search / detail lookup + "new vs already-known" indicator.
- WiGLE map tile overlay (clientTile) and marker clustering (needs osmdroid-bonuspack).
- WDGWars game client: territories, bounties, leaderboard, teams, chat, push, rabbit alerts,
  crowd Flock-confirm. This is the single biggest remaining feature set (many new screens + API layer).

Verify before release: install on a device that already has the app to confirm the DB v6->v7
migration runs cleanly (no device was connected at build time).
