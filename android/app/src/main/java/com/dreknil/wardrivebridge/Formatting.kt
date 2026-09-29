package com.dreknil.wardrivebridge

import java.text.NumberFormat
import java.util.Locale

// Was reimplemented by hand (reverse the string, insert commas every 3
// chars, reverse back) in both MainActivity and AnalyticsActivity for the
// same "1,234"-style count display - java.text.NumberFormat already does
// this correctly (and, unlike the hand-rolled version, doesn't misplace the
// comma relative to a leading minus sign for a negative count).
private val thousandsFormat = NumberFormat.getIntegerInstance(Locale.US)

fun fmtCount(n: Int): String = thousandsFormat.format(n)
