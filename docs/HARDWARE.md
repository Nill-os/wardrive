# Hardware: parts, wiring and assembly

![Wiring diagram](wiring.svg)

## Parts list

| Qty | Part | Notes |
|---|---|---|
| 2 | ESP32-S3 dev board (ESP32-S3-DevKitC-1 or a clone) | One becomes `wifi_node`, the other `ble_node`. Any S3 board with the onboard RGB LED on GPIO48 works as-is. Other boards need the pins in `firmware/src/*/main.cpp` and the board ID in `firmware/platformio.ini` changed. |
| 1 | CYD **dual-USB** ESP32-2432S028 ("CYD2USB") | 2.8" **ST7789** screen with resistive touch and a microSD slot. The older single-USB CYD uses an ILI9341 screen and needs different display flags. |
| 1 | GY-GPS6MV2 (u-blox NEO-6M) GPS module plus its antenna | Any 9600-baud NMEA GPS will do. Put the antenna where it can see the sky. |
| 1 | microSD card, FAT32 | 4–64 GB. Holds `config.cfg` and every log. |
| 1 | JST 1.25 mm 4-pin cable | The CYD ships with one for its `CN1` header. |
| ~10 | Dupont jumper wires (female-female) or hookup wire | For the GPS and the board-to-board links. |
| 3 | USB cables | USB-C for the S3 boards. For the CYD, use **USB-A to USB-C** or its micro-USB port: its USB-C port has no CC resistors, so a C-to-C cable won't power it. |
| 1 | USB power source with at least 3 ports | A car USB adapter or powered hub. Budget about 1.5 A for the whole rig. |
| 1 | Android phone (optional) | Android 8.0 or newer with Bluetooth LE, for the Nill OS - Wardriver app. |

Tools: a soldering iron if your S3 boards came without headers. A multimeter helps too.

## Pin map

Pins are named the way they are printed on each board. On every serial link, **TX on one end goes to RX on the other**.

### GPS → wifi_node (9600 baud)

| GPS pin | wifi_node pin |
|---|---|
| VCC | 3V3 |
| GND | GND |
| TX | **GPIO18** (ESP32 RX) |
| RX | GPIO17 (ESP32 TX). Optional, and currently unused - the GPS runs at its default 1 fix a second (an earlier 5-fixes-a-second config was removed because it overran the 9600-baud line). Safe to leave unconnected. |

### ble_node ↔ wifi_node (460800 baud)

| ble_node | wifi_node | Carries |
|---|---|---|
| GPIO8 (TX) | GPIO8 (RX) | BLE sightings: `MAC,RSSI,NAME,MFG_HEX` |
| GPIO3 (RX) | GPIO3 (TX) | Scan state and LED events: `START`, `STOP`, `OK`, `FAIL`, `LOWSTORAGE` and so on |
| GND | GND | Common ground |

### wifi_node ↔ cyd_node (460800 baud)

Use the CYD's 4-pin **CN1** header. It is labelled `GND IO22 IO27 3.3V`.

| wifi_node | CYD CN1 | Carries |
|---|---|---|
| GPIO13 (TX) | IO27 (RX) | Sightings, GPS position, time, channel, scan state |
| GPIO14 (RX) | IO22 (TX) | Config, scan state, upload results |
| GND | GND | Common ground |
| — | 3.3V | **Leave unconnected.** Each board has its own power. |

> **Why not the CYD's bottom 4-pin serial header (`VIN TX RX GND`)?** That header is UART0, and it is shared with the CYD's own USB-serial chip. The chip drives the RX line, so data from wifi_node never arrives. CN1 is on the free UART2 and works in both directions.

### Pins you don't wire

- The CYD's SD card, touch controller and screen are wired on the board itself.
- Both S3 boards use their onboard RGB LED (GPIO48).
- There is no physical button. The CYD's touchscreen is the only control.

## Power

- Power each board from its own USB port (5 V). It's fine to run all three from one hub or car adapter.
- **All grounds must be connected together.** Run the GND wire in each link even if everything shares one hub. Without it the serial links carry garbage.
- In a car, an ignition-switched USB socket is ideal. If the socket is always on, the rig still stops itself after 30 minutes without movement (see [Using the rig](USING_THE_RIG.md#idle-auto-stop)).

## Assembly

1. **Flash first, wire second.** Flash each board before wiring anything (see [Build and flash](BUILD_AND_FLASH.md)), and label the boards `wifi_node` and `ble_node`. The two S3 boards look identical, and flashing the wrong firmware to the wired board is the most common cause of "the link carries nothing".
2. Solder headers or wires to the S3 boards if they need them.
3. Wire the GPS to wifi_node.
4. Wire ble_node to wifi_node: 8↔8, 3↔3 and GND.
5. Wire wifi_node to CYD CN1: 13→IO27, 14←IO22 and GND. With the stock JST cable, cut off one end, then crimp or solder the three wires you need. Tape off the 3.3V wire.
6. Put `config.cfg` on the microSD card and insert it into the CYD (see [Config](CONFIG.md)).
7. Power everything up. Within a few seconds the CYD header should show **RIG:UP**. Outdoors, it should show **GPS:FIX** within a minute or two.

## Mounting tips

- The GPS antenna needs sky. Under a metal roof or deep in the dash it may never get a fix, and no fix means **nothing is logged**.
- Keep the S3 boards' antennas away from metal and away from each other.
- Strain-relieve the jumper wires. Car vibration pulls Dupont connectors loose over time; hot glue or heat-shrink helps.
