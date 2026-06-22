# iOS `displayLanguage` via `NSLocale` — Design (Phase 6, sub-task 6.3)

> **Status:** Approved 2026-06-22. Replaces the deferred iOS raw-BCP-47 fallback (D7) with a
> localized display-name implementation backed by `NSLocale`.

## Goal

Make the iOS `displayLanguage(tag)` actual return a human-readable, device-locale-localized
language name — `"en"` → `"English"`, `"tr"` → `"Turkish"` — at parity with the Android actual
(`java.util.Locale.forLanguageTag(tag).displayLanguage`). Today iOS returns the raw BCP-47 tag,
the honest non-fabricated fallback put in place when the `expect/actual` was introduced one phase
early (Phase 1, sub-task 1.8). This closes that gap.

## Context

`displayLanguage(languageTag: String): String?` is the single genuine platform divergence in the
formatter layer (decision D7). It is declared as an `internal expect fun` in
`commonMain/.../internal/overlay/LanguageNames.kt` and consumed by `OverlayFormatters` when
rendering the active audio/subtitle rows.

- **Android actual** (`LanguageNames.android.kt`): `Locale.forLanguageTag(tag).displayLanguage`,
  taken `null` when blank. Localized to the device's default locale (so `"en"` renders as
  `"İngilizce"` on a Turkish device). Pinned by `LanguageNamesTest` (androidHostTest):
  `"en"→"English"`, `"tr"→"Turkish"`, `""→null`.
- **iOS actual** (`LanguageNames.ios.kt`): currently `languageTag.takeIf { it.isNotBlank() }` —
  the raw-tag fallback to be replaced.

## Implementation

The only file that changes functionally is `LanguageNames.ios.kt`:

```kotlin
internal actual fun displayLanguage(languageTag: String): String? {
    if (languageTag.isBlank()) return null
    val code = NSLocale.componentsFromLocaleIdentifier(languageTag)[NSLocaleLanguageCode] as? String
        ?: languageTag
    return NSLocale.currentLocale.localizedStringForLanguageCode(code)?.takeIf { it.isNotBlank() }
        ?: languageTag
}
```

Three baked-in decisions:

1. **Language-subtag extraction.** `NSLocale.componentsFromLocaleIdentifier(...)` pulls the language
   code out of a full tag — `"en-US"` → `"en"`, `"zh-Hans-CN"` → `"zh"` — so the display name is
   the language only, dropping region/script. This mirrors Android, where
   `Locale.forLanguageTag("en-US").displayLanguage` is `"English"`. Apple's parser tolerates both
   BCP-47 `-` and identifier `_` separators.
2. **Device locale (parity with Android).** Names resolve against `NSLocale.currentLocale`, so they
   follow the device language exactly as Android's default-locale behavior does.
3. **Fallback = raw tag, not null.** When `NSLocale` cannot resolve a code (e.g. a garbage tag like
   `"zz"`), return the original tag rather than `null`. This matches Android's echo-the-code
   behavior for unknown languages *and* preserves the current honest, non-fabricated fallback;
   showing `"zz"` in the overlay beats showing nothing. A blank tag still returns `null`.

### K/N cinterop notes

- `NSLocale.currentLocale` is a class property — accessed as `NSLocale.currentLocale`.
- `NSLocale.componentsFromLocaleIdentifier(...)` is a class method.
- `localizedStringForLanguageCode(...)` is a member method — called on the locale instance.
- `NSLocaleLanguageCode` is a Foundation constant key — imported from `platform.Foundation`.

## Tests

- **New** `iosTest/kotlin/com/streamprobe/sdk/internal/overlay/LanguageNamesTest.kt`, mirroring the
  Android test: `"en"→"English"`, `"tr"→"Turkish"`, `""→null`. KDoc notes the test assumes an
  English-locale simulator/test host — the same assumption the Android test makes about an English
  JVM. (The CI iosSimulatorArm64 host defaults to `en_US`.)
- **Refresh** the two stale comments in `commonTest/.../overlay/OverlayFormattingTest.kt` that
  still describe the "iOS raw-tag fallback: en". Those tests compute their expected value
  dynamically via `displayLanguage(...)`, so they stay green without assertion changes — only the
  comments are outdated.

## Doc hygiene

Rewrite the file-level KDoc in `LanguageNames.ios.kt` (currently "Phase 5 sub-task 5.3 replaces
this …" — stale on both the phase number and the now-implemented behavior) to describe the real
`NSLocale`-backed implementation.

## Verification

- `./gradlew :sdk:iosSimulatorArm64Test` — new `LanguageNamesTest` green; unchanged
  `OverlayFormattingTest` green.
- Full CI gate:
  `:sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeDebugXCFramework :sdk:assembleAndroidMain
  :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain
  :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug` — all green.
- ktlint `iosMain`/`iosTest` rules respected (`no-consecutive-comments`, `class-signature`);
  detekt `maxIssues: 0` holds with no suppressions.

## Out of scope

- Phase 6 sub-tasks 6.1 (FairPlay DRM), 6.2 (signal polish), 6.4 (SPM distribution).
- Any change to the `expect` signature or to the Android actual.
- No public-surface, common, or Android behavior change.
