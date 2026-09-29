#include "ButtonClick.h"

ButtonClick::ButtonClick(uint8_t pin, uint16_t debounceMs, uint16_t doubleClickWindowMs)
	: _pin(pin), _debounceMs(debounceMs), _doubleClickWindowMs(doubleClickWindowMs),
	  _lastRawState(HIGH), _stableState(HIGH) {}

void ButtonClick::begin() {
	pinMode(_pin, INPUT_PULLUP);
	_lastRawState = digitalRead(_pin);
	_stableState = _lastRawState;
}

void ButtonClick::update() {
	int raw = digitalRead(_pin);
	uint32_t now = millis();

	if (raw != _lastRawState) {
		_lastChangeMs = now;
		_lastRawState = raw;
	}

	if ((now - _lastChangeMs) > _debounceMs && raw != _stableState) {
		_stableState = raw;
		if (_stableState == LOW) {
			// press edge - decision happens on release
		} else {
			handleRelease();
		}
	}

	if (_waitingSecondClick && (now - _firstClickMs) > _doubleClickWindowMs) {
		_waitingSecondClick = false;
		if (_onSingle) _onSingle();
	}
}

void ButtonClick::handleRelease() {
	uint32_t now = millis();
	if (_waitingSecondClick) {
		_waitingSecondClick = false;
		if (_onDouble) _onDouble();
	} else {
		_waitingSecondClick = true;
		_firstClickMs = now;
	}
}
