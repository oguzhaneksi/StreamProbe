package com.streamprobe.sdk.internal.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the iOS `displayLanguage` actual (backed by `NSLocale`) to its human-readable output,
 * the parity counterpart of the androidHostTest `LanguageNamesTest`. Assumes an English-locale
 * simulator/test host (the CI iosSimulatorArm64 host defaults to `en_US`), the same assumption the
 * Android test makes about an English JVM.
 */
class LanguageNamesTest {
    @Test
    fun resolvesCommonTagsToDisplayNames() {
        assertEquals("English", displayLanguage("en"))
        assertEquals("Turkish", displayLanguage("tr"))
    }

    @Test
    fun stripsRegionToLanguageOnlyName() {
        assertEquals("English", displayLanguage("en-US"))
    }

    @Test
    fun blankTagYieldsNull() {
        assertNull(displayLanguage(""))
    }

    @Test
    fun unresolvableTagFallsBackToRawTag() {
        assertEquals("zz", displayLanguage("zz"))
    }
}
