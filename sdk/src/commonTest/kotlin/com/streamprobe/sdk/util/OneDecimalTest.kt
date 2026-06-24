package com.streamprobe.sdk.util

import kotlin.test.Test
import kotlin.test.assertEquals

class OneDecimalTest {
    @Test
    fun `rounds positive value half-up`() {
        assertEquals("3.8", oneDecimal(3.75))
        assertEquals("3.8", oneDecimal(3.80))
        assertEquals("1.0", oneDecimal(0.95))
        assertEquals("2.0", oneDecimal(1.95))
    }

    @Test
    fun `formats whole numbers with zero decimal`() {
        assertEquals("0.0", oneDecimal(0.0))
        assertEquals("1.0", oneDecimal(1.0))
        assertEquals("48.0", oneDecimal(48.0))
    }

    @Test
    fun `handles negative values correctly`() {
        assertEquals("-1.5", oneDecimal(-1.5))
        assertEquals("-0.1", oneDecimal(-0.05))
        assertEquals("-3.8", oneDecimal(-3.75))
    }

    @Test
    fun `44100 Hz sample rate formats as 44_1`() {
        assertEquals("44.1", oneDecimal(44100 / 1000.0))
    }

    @Test
    fun `22050 Hz sample rate formats as 22_1`() {
        assertEquals("22.1", oneDecimal(22050 / 1000.0))
    }
}
