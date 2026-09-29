#pragma once

#include <Arduino.h>

// cyd_node's Bluetooth LE link to the phone app. cyd_node is the BLE
// peripheral/server: it advertises as "WardriveCYD" with a Nordic-UART-style
// pair of characteristics, and speaks the same '\n'-terminated wdstream line
// protocol it mirrors over USB serial (see cyd_node/main.cpp).
//
// Outgoing lines are chunked to the MTU the phone actually negotiated, and
// incoming bytes are reassembled on '\n' rather than trusting packet
// boundaries, so this is correct whatever MTU ends up agreed.
namespace CydBleLink {

// cyd_node calls this once from setup().
void beginServer(const char *advertisedName);

// cyd_node calls these around an upload attempt, to free NimBLE's own heap
// for the TLS handshake (a "BIGNUM - Memory allocation failed" error on
// WiGLE's upload specifically was traced to this - its cert needs more
// scratch heap than wdgwars.pl's does, and NimBLE's connection/GATT state
// was leaving too little of it free). Safe to call with no active
// connection to suspend; safe to call resumeServer() repeatedly once
// already running (a no-op then). The phone app notices the disconnect and
// reconnects on its own once advertising resumes.
void suspendServer();
void resumeServer();

// Pairing (see "Pairing" in the .cpp): only bonded phones can use the link.
// openPairing() lets one new phone bond during the next durationMs, using
// the 6-digit pairingCode() - show it on screen while pairingOpen().
void openPairing(uint32_t durationMs);
void closePairing();
bool pairingOpen();
uint32_t pairingSecondsLeft();
uint32_t pairingCode();
bool pairedInLastWindow();
int pairedPhoneCount();
void forgetAllPhones();

// Call every loop() iteration - drops centrals that never secure their link.
void poll();

// True while a paired (bonded, encrypted) phone is connected.
bool isConnected();

// Queues a line to send (a trailing '\n' is added automatically - don't
// include one). Silently dropped if no phone is connected - the phone's own
// log is a live mirror, the SD card is the real record.
void send(const String &line);

// Same drain-one-complete-line-at-a-time pattern already used for the
// other serial links in this codebase:
//   while (CydBleLink::hasLine()) { String line = CydBleLink::readLine(); ... }
bool hasLine();
String readLine();

} // namespace CydBleLink
