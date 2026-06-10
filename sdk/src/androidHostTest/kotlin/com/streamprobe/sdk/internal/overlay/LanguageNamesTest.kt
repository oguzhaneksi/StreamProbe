package com.streamprobe.sdk.internal.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the Android `displayLanguage` actual (backed by `java.util.Locale`) to its human-readable
 * output. The shared `OverlayFormattingTest` dogfoods `displayLanguage` for portability, so this is
 * where the concrete "en" -> "English" mapping — the intended divergence from iOS's raw-tag
 * fallback (D7) — is verified.
 */
class LanguageNamesTest {
    @Test
    fun resolvesCommonTagsToDisplayNames() {
        assertEquals("English", displayLanguage("en"))
        assertEquals("Turkish", displayLanguage("tr"))
    }

    @Test
    fun blankTagYieldsNull() {
        assertNull(displayLanguage(""))
    }
}
