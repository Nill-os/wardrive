# Troubleshooting

Always start with the serial monitor: `pio device monitor -p <port> -b 115200`. Every board prints a `[heartbeat]` every 2 s. If the heartbeat is ticking, the board isn't hung.

## Counts stay at 0 while scanning

**Almost always: no GPS fix.** wifi_node only forwards a sighting when it has a position. The fix can be live, or up to 15 s old.

- The header shows `GPS:--`, and wifi_node's heartbeat shows `gpsFix=0`.
- If `gpsChars` climbs, the GPS is wired correctly and just can't see the sky. Go outside and wait a few minutes; a cold start can take 5 minutes or more.
- If `gpsChars` stays at 0, check the wiring: GPS **TX** must go to wifi_node **GPIO18**. Also check that VCC and GND are connected.

## RIG:DOWN / the wired link carries nothing

1. **The boards are swapped.** This is the number-one cause. The two S3 boards look identical. Unplug the board that's wired to the CYD and see which serial port disappears, then flash `wifi_node` onto that board.
2. **TX and RX are crossed wrong.** wifi_node GPIO13 must go to CYD **IO27**, and GPIO14 to CYD **IO22**. If in doubt, swap the two data wires; it can't damage anything.
3. **No common ground.** Run the GND wire.
4. **You used the CYD's bottom `VIN TX RX GND` header.** That UART is shared with the USB chip, and it won't receive. Use **CN1**.
5. Check the counters. `wlrx=` in the CYD heartbeat counts bytes from wifi_node, and `cydrx=` in the wifi_node heartbeat counts bytes from the CYD. If one side's counter is stuck at 0, the fault is in the wire going **to** that board.

## No BLE counts

- ble_node only sends once wifi_node has a GPS fix (`fix=1` in its heartbeat). After that, `sent=` should climb, and wifi_node should flicker purple for WiFi. If ble_node sends but nothing arrives, check the 8↔8 and 3↔3 wires and the GND.
- ble_node only logs each device once per run, so in a quiet place the count levels off quickly.

## The phone app shows CYD: PAIR

The phone isn't paired with a rig yet. On the rig's **4.CFG** tab, tap **PAIR PHONE**, then keep the app open until Android asks for the code. If the rig was reset with **FORGET PHONES**, the app notices after a few failed connections and goes back to `CYD: PAIR` on its own. If it doesn't, use **Settings → FORGET THIS RIG** in the app, and remove *WardriveCYD* from Android's Bluetooth settings.

## The phone app shows CYD: DOWN

- Make sure Bluetooth and Location are on, and that the app has the **Nearby devices** permission.
- Tap **RE-LINK ALL** on the CYD. That restarts the CYD's Bluetooth.
- Only one phone can connect at a time. Close any other BLE app (nRF Connect and similar) that might hold the connection.
- Check that the CYD is advertising, using `tools/test_ble_link.sh scan` from a Linux PC or nRF Connect. Look for `WardriveCYD`.
- The link drops during an upload by design, and comes back afterwards.

## Uploads fail (red flash)

- Check `wifi_ssid` and `wifi_pass`. The CYD needs 2.4 GHz WiFi.
- Check the `[upload]` lines in the CYD serial log: they show the HTTP response code for each service.
- wdgwars is required for a file to count as uploaded; WiGLE is best-effort.
- The WiGLE token must be the **"Encoded for use"** value, not the raw API token.

## The touchscreen hits the wrong spot

The touch calibration constants (`TOUCH_RAW_*`, `TOUCH_SWAP_XY` and `TOUCH_INVERT_X/Y` in `src/cyd_node/main.cpp`) were calibrated on the author's unit and should suit most boards of this model. If yours is off, watch the `[touch] raw x=… y=…` serial lines while you tap each corner **briefly and separately**, then adjust the constants.

## The screen is inverted, rotated or blank

See [Design notes → Display quirks](DESIGN_NOTES.md#display-quirks). You probably have a different CYD revision. Check `ST7789_DRIVER` vs `ILI9341_DRIVER`, `TFT_INVERSION_OFF` and the rotation.

## The CYD won't flash

- Disconnect anything on its bottom serial header.
- Use a USB-A to USB-C cable, or the micro-USB port.
- On Windows, install the CH340 driver.

## The ESP32-S3 won't flash, or shows no serial output

- Hold **BOOT**, tap **RESET**, release **BOOT**, then upload again.
- Don't remove `ARDUINO_USB_MODE` and `ARDUINO_USB_CDC_ON_BOOT` from `platformio.ini`. Without them, `Serial` prints go nowhere.
