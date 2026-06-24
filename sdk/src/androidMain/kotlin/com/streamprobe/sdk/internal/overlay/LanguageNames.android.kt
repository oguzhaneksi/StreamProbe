package com.streamprobe.sdk.internal.overlay

import java.util.Locale

internal actual fun displayLanguage(languageTag: String): String? =
    Locale.forLanguageTag(languageTag).displayLanguage.takeIf { it.isNotBlank() }
