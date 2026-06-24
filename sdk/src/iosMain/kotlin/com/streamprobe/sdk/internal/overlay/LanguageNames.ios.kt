package com.streamprobe.sdk.internal.overlay

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode
import platform.Foundation.componentsFromLocaleIdentifier
import platform.Foundation.currentLocale
import platform.Foundation.localizedStringForLanguageCode

/**
 * iOS `displayLanguage` actual (Phase 6, sub-task 6.3): resolves a device-locale-localized language
 * name from a BCP-47 tag via `NSLocale`, at parity with the Android `java.util.Locale` actual.
 * The language subtag is extracted first (so "en-US" -> "English", dropping the region), then
 * localized against `NSLocale.currentLocale`. Falls back to the raw tag when the code is
 * unresolvable (mirrors Android echoing an unknown code) and returns null only for a blank tag.
 */
internal actual fun displayLanguage(languageTag: String): String? {
    if (languageTag.isBlank()) return null
    val code =
        NSLocale.componentsFromLocaleIdentifier(languageTag)[NSLocaleLanguageCode] as? String
            ?: languageTag
    val name = NSLocale.currentLocale.localizedStringForLanguageCode(code)?.takeIf { it.isNotBlank() }
    return name ?: languageTag
}
