#pragma once

#include <Arduino.h>

class NimBLEAdvertisedDevice; // forward-declared so includers don't need NimBLEDevice.h just for this

// Wireless replacement for the wired UART link this rig used to need
// between wifi_node and cyd_node - the one inter-board hop that's wireless,
// not the whole rig. ble_node's own link into wifi_node stays a wired UART,
// as before (see each board's own main.cpp header).
//
// cyd_node is the BLE peripheral/server (it's stationary in the dash mount
// with the screen; advertises so it's easy to find). wifi_node is the BLE
// central/client, connecting to cyd_node's peripheral. Both boards still
// speak the same '\n'-terminated line protocol the original wired link
// used (W,/B,/SCANSTATE/EPOCH/GPSPOS/CH one way, SDOK/CFG/START/OK/FAIL/
// BLINKPHASE/LOWSTORAGE the other) - only the transport changed;
// handleIncomingLine()/handleCydLinkLine() are otherwise unaffected by
// which physical link carried a line.
//
// Outgoing lines are chunked conservatively (CHUNK_SIZE) rather than
// relying on BLE MTU negotiation having actually succeeded - a Handle Value
// Notification silently truncates at whatever the CURRENT negotiated MTU
// is, and there's no guarantee both ends agreed to more than the mandatory
// minimum. Chunking below that floor and reassembling on the receiving end
// by scanning for '\n' (not by trusting chunk boundaries) makes this
// correct regardless of what MTU actually negotiated.
//
// A central's connect/reconnect logic runs on its own dedicated FreeRTOS
// task (see .cpp), never inline in the caller's loop() - a board with
// time-sensitive work in its own main loop (wifi_node's WiFi channel-hop
// timing) can't afford to stall on NimBLE's connect() call, which blocks
// for as long as several seconds while it's working.
namespace CydBleLink {

// cyd_node calls this once from setup().
void beginServer(const char *advertisedName);

// cyd_node calls these around an upload attempt, to free NimBLE's own heap
// for the TLS handshake (a "BIGNUM - Memory allocation failed" error on
// WiGLE's upload specifically was traced to this - its cert needs more
// scratch heap than wdgwars.pl's does, and NimBLE's connection/GATT state
// was leaving too little of it free). Safe to call with no active
// connection to suspend; safe to call resumeServer() repeatedly once
// already running (a no-op then). wifi_node notices the disconnect and
// reconnects on its own once advertising resumes, same as any other
// out-of-range gap.
void suspendServer();
void resumeServer();

// Central role setup (wifi_node). Unlike beginServer(), this does NOT call
// NimBLEDevice::init() or set up any scanning itself - the caller must
// already have done both (via its own NimBLEDevice::init() and
// NimBLEDevice::getScan() setup) BEFORE calling this, and must forward
// every discovered advertisement to feedScanResult() below from its own
// NimBLEAdvertisedDeviceCallbacks::onResult(). This module only owns the
// connect-once-found/discover-characteristics/subscribe sequence, not the
// scan itself, so the caller stays free to reuse its own scan handle for
// anything else it might need. targetName must match whatever
// beginServer() was given on cyd_node.
void beginClient(const char *targetName);

// Call for every result from the caller's own scan (see beginClient()'s
// comment) - a no-op for anything that isn't the target peripheral, or if
// already connected/already mid-connect-attempt.
void feedScanResult(NimBLEAdvertisedDevice *device);

// Call every loop() iteration on every board. Cheap when idle/connected -
// all the slow connect work happens on the background task (client side)
// or in BLE callbacks (server side), not here.
void poll();

bool isConnected();

// Queues a line to send (a trailing '\n' is added automatically - don't
// include one). Silently dropped if not currently connected, same as bytes
// sent into an unplugged wire would be lost: a broadcast-style line
// (SCANSTATE/EPOCH/CFG/SDOK/etc) just gets resent on its own next periodic
// tick regardless; a one-shot observation line (W,/B,) is genuinely lost
// for that gap - an accepted trade-off of any wireless link, not a bug.
void send(const String &line);

// Same drain-one-complete-line-at-a-time pattern already used for the
// other serial links in this codebase:
//   while (CydBleLink::hasLine()) { String line = CydBleLink::readLine(); ... }
bool hasLine();
String readLine();

} // namespace CydBleLink
