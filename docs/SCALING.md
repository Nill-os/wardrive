# Scaling the rig with more nodes

The rig starts as three boards, but the sniffing layers are built to grow: you
can run **up to 20 WiFi sniffer nodes** and **up to 20 BLE nodes**. This page
explains how the work is divided, how to flash and identify each node, and how
they'd be wired together.

![Scaling diagram](scaling.svg)

## Two ways the work divides

**WiFi — by channel.** A single `wifi_node` covers the three popular,
non-overlapping channels **1 / 6 / 11**. With more nodes, the firmware splits
the full 2.4 GHz plan **1–13** round-robin across them, so each node dwells on
fewer channels and revisits them faster. It's set by two build flags,
`NODE_COUNT` (how many sniffers) and `NODE_INDEX` (which one this board is).

| Nodes | Channels per node |
|---|---|
| 1 | 1, 6, 11 |
| 2 | #0 → 1,3,5,7,9,11,13  ·  #1 → 2,4,6,8,10,12 |
| 3 | #0 → 1,4,7,10,13  ·  #1 → 2,5,8,11  ·  #2 → 3,6,9,12 |

**BLE — by MAC.** BLE advertising uses only three fixed channels (37/38/39) and
every node's radio already hears all of them, so there's nothing to split by
channel. Instead multiple `ble_node` boards divide the **reporting** load: each
owns a slice of the MAC-address space (`mac[5] % BLE_NODE_COUNT == BLE_NODE_INDEX`)
and forwards only that slice, so no single node's link queue is swamped in a
dense area — and a full queue is what actually drops BLE sightings. Devices
advertise repeatedly, so the owning node still catches one on a later
advertisement even if it misses it, and only the owner forwards it, so the
aggregator sees no extra duplicates.

See [Design notes → Scaling to more sniffer nodes](DESIGN_NOTES.md#scaling-to-more-sniffer-nodes)
and [→ Scaling BLE nodes](DESIGN_NOTES.md#scaling-ble-nodes) for the internals.

## Scaling statistics

At the default 150 ms channel dwell, more WiFi nodes buy you two things at once:
**full channel coverage** (not just the popular 1/6/11) and a **faster revisit**
of every channel, because the nodes hop in parallel. Each node also adds its own
antenna, so weak APs are more likely to be caught on some pass.

| Sniffer nodes | 2.4 GHz channels covered | Max channels on one node | Each channel revisited every |
|---|---|---|---|
| 1 | 1 / 6 / 11 (popular only) | 3 | ~0.45 s |
| 2 | all of 1–13 | 7 | ~1.05 s |
| 3 | all of 1–13 | 5 | ~0.75 s |
| 4 | all of 1–13 | 4 | ~0.60 s |
| 5 | all of 1–13 | 3 | ~0.45 s |
| 13 | all of 1–13 | 1 | ~0.15 s (no hopping - each node camps a channel) |

So **5 nodes cover all 13 channels at the same revisit rate a single node gives
just 1/6/11**, and by **13 nodes nothing is missed to channel-hopping** - every
channel has a radio parked on it full-time. In practice unique-AP throughput
rises roughly linearly with node count until you reach that channel-camping
point. (Lower `channel_hop_ms` to trade dwell time for faster revisit on any
node count.)

**BLE** scales differently: every node already hears all three advertising
channels, so adding `ble_node` boards multiplies the **reporting** capacity
rather than the coverage - *N* nodes carry roughly *N×* the forwarding
throughput, which is what keeps a dense area from overflowing one node's link
and dropping sightings.

## Flashing and identifying nodes (desktop app)

The desktop app's **Flash** tab does the assignment for you — no `platformio.ini`
edits:

1. **DETECT** — plug boards in and hit *DETECT*. Each board announces its role
   and number over serial (a `WD:ID` line), so the tool reports, e.g.
   `⁠/dev/ttyACM0  ->  WiFi sniffer, node 2 of 4`. A board not yet running the
   firmware is shown with its USB chip so you can still tell what it is.
2. **Pick the type and number.** Set *Sniffer nodes* (for WiFi) or *BLE nodes*
   (for BLE) to the total, choose *this board is #N*, and — for the BLE scanner —
   pick the board in the *BLE board* menu (ESP32-S3 DevKitC, or a Seeed XIAO
   ESP32-C3/S3).
3. **Flash.** The number you chose is baked in, and from then on the board
   reports itself as that node — so re-running DETECT always tells you which is
   which.

## Wiring

**The base three boards** are wired exactly as in the
[hardware guide](HARDWARE.md) — `ble_node → wifi_node → cyd_node` over UART, GPS
on `wifi_node`. Nothing about that changes.

**Adding nodes is not a wiring change — it's a transport change.** `wifi_node`
has three hardware UARTs and all three are already used (GPS, `ble_node`,
`cyd_node`), so there's no spare wire to hang another board off. That's why
scaling past the base rig is designed around **ESP-NOW**: each extra node
broadcasts its sightings over the air to the aggregator (`wifi_node #0` or the
CYD), which funnels everything to the one board with the SD card and uploader.
No wires run between nodes — each one just needs power.

> **Status:** the per-node channel split, MAC split, numbering and DETECT are
> implemented, and the **ESP-NOW transport is now implemented too**, behind the
> `WARDRIVE_ESPNOW` build flag (off by default, so a normal rig is byte-for-byte
> the wired three-board chain). Node 0 acts as the aggregator (GPS + ESP-NOW
> receiver + wired uplink to the CYD); higher-numbered `wifi_node`/`ble_node`
> boards are satellites that broadcast to it. The clean path — extra **BLE**
> nodes, whose WiFi radio is otherwise free — has no radio conflict. The WiFi
> satellite path works around the sniff-vs-receive conflict by having satellites
> hop to the ESP-NOW channel only to transmit; for the most reliable reception
> build the aggregator with `-D WARDRIVE_ESPNOW_DEDICATED` so it stops sniffing
> and dedicates its radio to receiving.
>
> This transport is **compile-verified but not yet hardware-validated on a
> multi-node rig** — treat it as experimental until you've run it on real extra
> boards. A single-node rig built without the flag is unaffected.

## Power

Every extra ESP32 is another ~100–250 mA off the car supply. A handful of Seeed
XIAO boards are a good fit here: tiny, cheap, low-draw, and the `ble_node`
firmware already builds for them.
