# firmware

The PlatformIO project for the rig's three boards: `wifi_node` (WiFi + GPS), `ble_node` (BLE) and `cyd_node` (screen, SD card, uploads, phone link).

```
pio run -e wifi_node -t upload
pio run -e ble_node  -t upload
pio run -e cyd_node  -t upload
```

Flash all three from the same commit, because the boards share a wire protocol. The full guides are in [`../docs`](../docs): start with [Hardware](../docs/HARDWARE.md) and [Build and flash](../docs/BUILD_AND_FLASH.md).

## Desktop app

[`tools/`](tools/) holds the desktop app - the go-to tool for the rig. It pulls logs off the SD card, over USB, or over WiFi and builds an interactive field report; **detects** what board is on each port and its node number; **flashes** any board (including bulk "flash all" and the Seeed XIAO BLE variants) over USB or the CYD over the air; and gives a serial console to manage the rig. Run `tools/install-desktop.sh` for a launcher with an icon. See [Scaling the rig](../docs/SCALING.md) for the multi-node workflow.

<p align="center">
  <img src="../docs/screenshots/desktop-uploader.png" width="330" alt="Desktop tool - logs & report">
  <img src="../docs/screenshots/desktop-flash.png" width="330" alt="Desktop tool - flash">
</p>
<p align="center">
  <img src="../docs/screenshots/desktop-field-report.png" width="480" alt="Field report">
</p>
<p align="center"><em>Logs &amp; report, flashing, and the interactive field report. (Map is demo data.)</em></p>
