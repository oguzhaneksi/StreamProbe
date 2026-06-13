package com.streamprobe.sdk.internal.overlay

/**
 * iOS fallback (D7 — deferred): returns the raw BCP-47 tag rather than a localized display name.
 * Phase 5 sub-task 5.3 replaces this with an `NSLocale`-based implementation so iOS shows
 * "English" instead of "en". Until then the raw tag is the honest, non-fabricated value.
 */
internal actual fun displayLanguage(languageTag: String): String? = languageTag.takeIf { it.isNotBlank() }
