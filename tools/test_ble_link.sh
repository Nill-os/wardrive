#!/usr/bin/env bash
# Manual test tool for cyd_node's BLE link (see README's "Bluetooth link"
# section) - stands in for a real wifi_node using this machine's own
# Bluetooth adapter, via gatttool. Built while validating the link during
# initial bring-up, when no wifi_node board was on hand to test against;
# kept around for whenever you need to sanity-check the link again (after
# a CydBleLink.cpp change, a new cyd_node build, etc) without needing a
# second ESP32 on the bench.
#
# Requires: bluetoothctl, gatttool (both part of the standard BlueZ
# package on Debian/Ubuntu - `apt install bluez`). Needs an unblocked,
# powered Bluetooth adapter - this script does NOT touch rfkill/adapter
# power itself, since whether that's safe to change is a host-machine
# decision, not this script's to make. If `rfkill list bluetooth` shows
# "Soft blocked: yes", run `rfkill unblock bluetooth` yourself first (and
# `rfkill block bluetooth` afterward, if you want it back the way it was).
#
# Usage:
#   ./test_ble_link.sh scan                    # confirm WardriveCYD is advertising
#   ./test_ble_link.sh attrs                   # dump the GATT service/characteristics
#   ./test_ble_link.sh notify [seconds]         # subscribe to the TX characteristic, print what arrives
#   ./test_ble_link.sh send "SCANSTATE:0"       # write a line to the RX characteristic (write-without-response, matching the real firmware)
#
# The device address is rediscovered fresh each run rather than hardcoded,
# since it's derived from the ESP32's own MAC and will differ per board.

set -euo pipefail

DEVICE_NAME="WardriveCYD"
SERVICE_UUID="5b60de00-0000-4a6c-9b1a-6364796477b1"
RX_CHAR_UUID="5b60de00-0001-4a6c-9b1a-6364796477b1"
TX_CHAR_UUID="5b60de00-0002-4a6c-9b1a-6364796477b1"

find_address() {
	( echo "scan on"; sleep 5; echo "devices"; echo "scan off"; echo "quit" ) \
		| bluetoothctl 2>&1 | grep "$DEVICE_NAME" | grep -oE '([0-9A-F]{2}:){5}[0-9A-F]{2}' | head -1
}

cmd_scan() {
	echo "Scanning for $DEVICE_NAME (5s)..."
	addr=$(find_address)
	if [ -z "$addr" ]; then
		echo "Not found. Is cyd_node powered on and running current firmware?"
		exit 1
	fi
	echo "Found: $addr"
}

cmd_attrs() {
	addr=$(find_address)
	[ -z "$addr" ] && { echo "Not found"; exit 1; }
	( echo "connect $addr"; sleep 3; echo "menu gatt";
	  echo "list-attributes $addr"; sleep 1;
	  echo "back"; echo "disconnect $addr"; echo "quit" ) | bluetoothctl 2>&1 \
		| grep -A2 "Characteristic\|Primary Service"
}

cmd_notify() {
	duration="${1:-8}"
	addr=$(find_address)
	[ -z "$addr" ] && { echo "Not found"; exit 1; }
	dev_path="/org/bluez/hci0/dev_${addr//:/_}"
	tx_char=$(( echo "connect $addr"; sleep 3; echo "menu gatt";
	  echo "list-attributes $addr"; sleep 1; echo "back"; echo "quit" ) \
	  | bluetoothctl 2>&1 | grep -B2 "$TX_CHAR_UUID" | grep "$dev_path" | head -1 | tr -d '\t ')
	if [ -z "$tx_char" ]; then
		echo "Could not resolve TX characteristic path - is $addr still in range?"
		exit 1
	fi
	echo "Subscribing to $tx_char for ${duration}s..."
	( echo "connect $addr"; sleep 3; echo "menu gatt";
	  echo "select-attribute $tx_char"; echo "notify on";
	  sleep "$duration"; echo "notify off"; echo "back";
	  echo "disconnect $addr"; echo "quit" ) | bluetoothctl 2>&1 \
		| grep -A1 "Value:" | grep -v "Value:" | sed 's/^\s*//'
}

cmd_send() {
	line="${1:-}"
	[ -z "$line" ] && { echo "Usage: $0 send \"LINE TEXT\""; exit 1; }
	addr=$(find_address)
	[ -z "$addr" ] && { echo "Not found"; exit 1; }
	# gatttool resolves handles fresh via its own connect - value handle 0x000f
	# is stable across boots for THIS specific attribute layout (server always
	# creates characteristics in the same order - see beginServer() in
	# CydBleLink.cpp) but re-verify with `attrs` if this firmware ever changes.
	#
	# Chunked into <=18-byte pieces to match CydBleLink.cpp's own
	# CHUNK_SIZE, and sent as separate ATT writes - found the hard way that
	# a single write-without-response longer than the connection's ATT MTU
	# (23 bytes unnegotiated, so ~20 usable) just silently never arrives at
	# all: write-without-response has no long-write/fragmentation mechanism
	# in the BLE spec, unlike write-with-response. Anything longer than a
	# short line (like a real W,/B, observation) WILL hit this if sent
	# unchunked - this bit both a manual gatttool test and, before that fix,
	# this script's own naive single-write implementation.
	chunks=$(python3 -c "
import sys
data = (sys.argv[1] + '\n').encode()
n = 18
for i in range(0, len(data), n):
	print(data[i:i+n].hex())
" "$line")
	echo "Sending ($(echo "$chunks" | wc -l) chunk(s), write-without-response): $line"
	{
		echo "connect $addr"
		sleep 3
		while IFS= read -r c; do
			echo "char-write-cmd 0x000f $c"
			sleep 0.5
		done <<< "$chunks"
		sleep 1
		echo "exit"
	} | timeout 30 gatttool -b "$addr" -I
}

case "${1:-}" in
	scan) cmd_scan ;;
	attrs) cmd_attrs ;;
	notify) cmd_notify "${2:-}" ;;
	send) cmd_send "${2:-}" ;;
	*)
		echo "Usage: $0 {scan|attrs|notify [seconds]|send \"LINE\"}"
		exit 1
		;;
esac
