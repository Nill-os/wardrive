# firmware

The PlatformIO project for the rig's three boards: `wifi_node` (WiFi + GPS), `ble_node` (BLE) and `cyd_node` (screen, SD card, uploads, phone link).

```
pio run -e wifi_node -t upload
pio run -e ble_node  -t upload
pio run -e cyd_node  -t upload
```

Flash all three from the same commit, because the boards share a wire protocol. The full guides are in [`../docs`](../docs): start with [Hardware](../docs/HARDWARE.md) and [Build and flash](../docs/BUILD_AND_FLASH.md).
