package com.streamprobe.sdk.util

import kotlin.math.abs
import kotlin.math.floor

// Locale-independent one-decimal formatting (HALF_UP), replacing String.format("%.1f", …).
internal fun oneDecimal(value: Double): String {
    val sign = if (value < 0.0) "-" else ""
    val scaled = floor(abs(value) * 10.0 + 0.5).toLong()
    return "$sign${scaled / 10}.${scaled % 10}"
}
