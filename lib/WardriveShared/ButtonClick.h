#pragma once
#include <Arduino.h>

// Non-blocking single-click / double-click detector for one active-low button
// (INPUT_PULLUP, other leg to GND). Call update() every loop() iteration.
//
// Both wardrive nodes wire their button pin to the *same physical button* in
// parallel, so each board independently runs this same algorithm on the same
// electrical signal and reaches the same click decision without needing to
// talk to each other about button state.
class ButtonClick {
public:
	using ClickCallback = void (*)();

	explicit ButtonClick(uint8_t pin, uint16_t debounceMs = 30, uint16_t doubleClickWindowMs = 350);

	void begin();
	void update();

	void onSingleClick(ClickCallback cb) { _onSingle = cb; }
	void onDoubleClick(ClickCallback cb) { _onDouble = cb; }

private:
	uint8_t _pin;
	uint16_t _debounceMs;
	uint16_t _doubleClickWindowMs;

	int _lastRawState;
	int _stableState;
	uint32_t _lastChangeMs = 0;

	bool _waitingSecondClick = false;
	uint32_t _firstClickMs = 0;

	ClickCallback _onSingle = nullptr;
	ClickCallback _onDouble = nullptr;

	void handleRelease();
};
