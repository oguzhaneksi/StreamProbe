# iOS `displayLanguage` via `NSLocale` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the iOS `displayLanguage(tag)` raw-BCP-47 fallback with an `NSLocale`-backed implementation that returns localized language names ("en" → "English"), at parity with the Android actual.

**Architecture:** `displayLanguage` is an `internal expect fun` in `commonMain` (the single genuine platform divergence in the formatter layer, decision D7). Only the iOS `actual` changes: it extracts the language subtag from the BCP-47 tag, resolves a device-locale-localized name via `NSLocale.currentLocale.localizedStringForLanguageCode`, and falls back to the raw tag when the code is unresolvable.

**Tech Stack:** Kotlin/Native, `platform.Foundation.NSLocale` cinterop bindings, `kotlin.test` (iosTest).

## Global Constraints

- **No `expect` signature change, no Android-actual change, no public-surface/common/Android behavior change** — iOS-only.
- **commonMain stays free of `java.`/`javax.`/`android.`/`androidx.` imports** (not touched here, but the constraint stands).
- **ktlint `iosMain`/`iosTest` rules:** `no-consecutive-comments` (a KDoc may not be immediately followed by a `//` comment or another KDoc); `class-signature` (single primary-ctor param on its own line — N/A here, top-level fun).
- **detekt `maxIssues: 0`** — fix root causes, never `@Suppress`.
- **`String.format(Locale.ROOT, …)` is JVM-only** — not used here; do not introduce it in iosMain.
- **CI gate (must be green):** `:sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeDebugXCFramework :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug`.
- **iosTest requires** Xcode + the iosSimulatorArm64 toolchain; the CI simulator host defaults to `en_US`.
- **Never commit without explicit user approval** (CLAUDE.md AI Behavioral Rule 4) — the commit steps below are gated on that approval.

---

### Task 1: iOS `displayLanguage` NSLocale implementation + test

**Files:**
- Create: `sdk/src/iosTest/kotlin/com/streamprobe/sdk/internal/overlay/LanguageNamesTest.kt`
- Modify: `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/overlay/LanguageNames.ios.kt`
- Modify (comments only): `sdk/src/commonTest/kotlin/com/streamprobe/sdk/internal/overlay/OverlayFormattingTest.kt:368-369` and `:426`

**Interfaces:**
- Consumes: `internal expect fun displayLanguage(languageTag: String): String?` (commonMain, `LanguageNames.kt`) — signature unchanged.
- Produces: the iOS `actual` of `displayLanguage`, returning a localized language name, the raw tag when unresolvable, or `null` when blank. No new public symbols.

- [ ] **Step 1: Write the failing test**

Create `sdk/src/iosTest/kotlin/com/streamprobe/sdk/internal/overlay/LanguageNamesTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdk:iosSimulatorArm64Test --tests "com.streamprobe.sdk.internal.overlay.LanguageNamesTest"`
Expected: FAIL — `resolvesCommonTagsToDisplayNames` and `stripsRegionToLanguageOnlyName` fail because the current raw-tag fallback returns `"en"`/`"en-US"` instead of `"English"`. (`blankTagYieldsNull` and `unresolvableTagFallsBackToRawTag` already pass against the old fallback.)

- [ ] **Step 3: Write the implementation**

Replace the entire contents of `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/overlay/LanguageNames.ios.kt`:

```kotlin
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
    val code = NSLocale.componentsFromLocaleIdentifier(languageTag)[NSLocaleLanguageCode] as? String
        ?: languageTag
    return NSLocale.currentLocale.localizedStringForLanguageCode(code)?.takeIf { it.isNotBlank() }
        ?: languageTag
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :sdk:iosSimulatorArm64Test --tests "com.streamprobe.sdk.internal.overlay.LanguageNamesTest"`
Expected: PASS — all four tests green.

If the cinterop import names are rejected by the compiler (member vs. category binding differences), resolve by: confirming `currentLocale` and `localizedStringForLanguageCode` resolve as members of `NSLocale` (they may not need the explicit `import` lines — Kotlin/Native imports the symbol, not the member); keep `NSLocaleLanguageCode` and `componentsFromLocaleIdentifier` imports. Do not suppress — fix the import set until it compiles cleanly.

- [ ] **Step 5: Refresh the two stale comments in `OverlayFormattingTest`**

These commonTest assertions compute their expected value dynamically via `displayLanguage(...)`, so they stay green — only the inline comments are now wrong. Edit `sdk/src/commonTest/kotlin/com/streamprobe/sdk/internal/overlay/OverlayFormattingTest.kt`:

At lines 368-369, replace:
```kotlin
        // Platform-neutral: the formatter must surface whatever the platform `displayLanguage`
        // actual resolves for the tag (Android: "English"; iOS raw-tag fallback: "en").
```
with:
```kotlin
        // Platform-neutral: the formatter must surface whatever the platform `displayLanguage`
        // actual resolves for the tag (Android and iOS both: "English").
```

At line 426, replace:
```kotlin
        // Platform-neutral: see formatActiveAudio counterpart. Android resolves "Turkish"; iOS "tr".
```
with:
```kotlin
        // Platform-neutral: see formatActiveAudio counterpart. Android and iOS both resolve "Turkish".
```

- [ ] **Step 6: Run the full CI gate**

Run:
```bash
./gradlew :sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeDebugXCFramework :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug
```
Expected: BUILD SUCCESSFUL — iosSimulatorArm64Test includes the new `LanguageNamesTest` and the unchanged `OverlayFormattingTest`; ktlint passes on `iosMain`/`iosTest`; detekt reports zero issues.

- [ ] **Step 7: Commit** (only after explicit user approval — CLAUDE.md Rule 4)

```bash
git add sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/overlay/LanguageNames.ios.kt \
        sdk/src/iosTest/kotlin/com/streamprobe/sdk/internal/overlay/LanguageNamesTest.kt \
        sdk/src/commonTest/kotlin/com/streamprobe/sdk/internal/overlay/OverlayFormattingTest.kt \
        docs/superpowers/specs/2026-06-22-ios-display-language-design.md \
        docs/superpowers/plans/2026-06-22-ios-display-language.md
git commit -m "feat(kmp/ios): Phase 6.3 — NSLocale-backed displayLanguage actual

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- Implementation (NSLocale, subtag extraction, device locale, raw-tag fallback) → Task 1 Step 3. ✓
- New iosTest `LanguageNamesTest` (`en`/`tr`/blank, plus region-strip and unresolvable-fallback edge cases) → Step 1. ✓
- Refresh stale `OverlayFormattingTest` comments → Step 5. ✓
- Doc hygiene: rewrite `LanguageNames.ios.kt` KDoc → Step 3 (new KDoc in the replacement file). ✓
- Verification: iosSimulatorArm64Test + full CI gate → Steps 4, 6. ✓
- Out of scope (6.1/6.2/6.4, expect signature, Android actual) → honored; no task touches them. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to" — every code step shows complete content. ✓

**3. Type consistency:** `displayLanguage(languageTag: String): String?` is identical across the expect (commonMain), the iOS actual (Step 3), and every test call site (Step 1). No signature drift. ✓
