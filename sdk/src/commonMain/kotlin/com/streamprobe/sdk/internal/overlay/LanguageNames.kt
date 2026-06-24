package com.streamprobe.sdk.internal.overlay

/**
 * Resolves a human-readable display language (e.g. "English" for "en") from a BCP-47 tag.
 * The only genuine platform divergence in the formatter layer: Android uses `java.util.Locale`;
 * other platforms supply their own actual. Returns null when the tag yields no usable name.
 */
internal expect fun displayLanguage(languageTag: String): String?
