# M8 — DRM Monitoring (Revize)

DRM session lifecycle olayları, lisans yükleme latency'si, Widevine/PlayReady/ClearKey durumu ve DRM'e özgü hatalar — mevcut overlay altyapısı üzerinden gösterilir. **Tespit tamamen reaktiftir**: DRM UI yalnızca gerçek bir DRM session olayı görüldüğünde açılır.

> Bu revizyon, ilk plan üzerindeki inceleme tartışmasının sonuçlarını içerir. Değişen başlıca kararlar:
> 1. **Reactive-only tespit** — proaktif `Format.drmInitData` taraması kaldırıldı (eski "two-layer" tasarım iptal).
> 2. **DRM mantığı ayrı sınıflara çıkarıldı** — `DrmSessionTracker` (ayrı `AnalyticsListener`) + `DrmSchemeDetector` (pure helper). `PlayerInterceptor` 16 fonksiyonda kalır (detekt `TooManyFunctions` payı korunur).
> 3. **`onLoadError` dokunulmadan kalır** — DRM hataları yalnızca `onDrmSessionManagerError`'dan gelir; çift-routing kaynaklı olası duplikasyon ortadan kalkar.
> 4. **Scheme tespiti `eventTime` üzerinden** — `player.currentMediaItem` yerine `eventTime.timeline` (playlist geçişlerinde doğru item).
> 5. **`DrmSessionState.RELEASED` korunur**, `mapDrmState` 5 state'i de eşler; `SessionReleased.sessionDurationMs` ve `KeysRestored`/`KeysRemoved` event'leri kaldırıldı.
> 6. **UI görünürlüğü `drmSessionEvents.isNotEmpty()`'ten türetilir** — ayrı `isDrmDetected` flow yok.

---

## Core Design Decisions

### 1. Reactive-Only DRM Detection

DRM tespiti tek katmanlıdır — yalnızca `AnalyticsListener` DRM callback'leri ile. `Format.drmInitData` taraması yapılmaz.

| Sinyal | Timing | Amaç |
|---|---|---|
| `onDrmSessionAcquired` / `onDrmKeysLoaded` / `onDrmSessionReleased` / `onDrmSessionManagerError` | DRM session açılınca/değişince | DRM event timeline + summary durumu |

DRM UI'ın (chip + summary satırı) görünürlüğü `SessionStore.drmSessionEvents.isNotEmpty()`'ten türetilir. İlk DRM olayı gelince UI açılır.

**Davranış notu (yapışkan görünürlük):** `drmSessionEvents` yalnızca `clear()`/`detach()` ile boşalır (kullanıcıya açık "clear DRM" yok). Dolayısıyla bir DRM oturumu görüldükten sonra chip/summary, player yeniden attach edilene kadar **görünür kalır**; aynı player yaşam döngüsünde DRM'li içerikten DRM'siz içeriğe geçilse bile kaybolmaz. Bu, debug aracı için kabul edilebilir ("bu oturum DRM içerdi" bilgisini korur).

### 2. DRM Errors: Dual Surface (tek kaynak)

DRM hataları **yalnızca `onDrmSessionManagerError`** üzerinden yakalanır ve iki yerde gösterilir:
- **DRM tab** → `DrmSessionEvent.SessionError`
- **Errors view** → `PlaybackErrorEvent(category = DRM_ERROR)`

`onLoadError`'a dokunulmaz; `C.DATA_TYPE_DRM` yükleme hataları eskisi gibi yok sayılır (lisans istekleri MediaSource yükleme pipeline'ından geçmediği için pratikte zaten oradan akmaz). Böylece tek hata aynı anda iki farklı callback'ten gelip duplike olmaz.

### 3. License Latency: Informational Only

Lisans yükleme süresi (`onDrmKeysLoaded` − `onDrmSessionAcquired`) yalnızca bilgilendirme amaçlıdır; hiçbir threshold'da error olarak raporlanmaz. (Sınırlamalar için "Known Limitations".)

### 4. Summary Row: Conditional

`Widevine · Keys Loaded · 312ms` satırı yalnızca en az bir DRM olayı varken görünür. Session release sonrası `currentDrmState = null` olur → satır `—` gösterir ama chip görünür kalır (timeline'ı incelemek için).

---

## Known Limitations (kod içinde KDoc notu olarak girecek)

- **Lisans latency, key rotation'da şişer:** `onDrmKeysLoaded` birden çok kez tetiklenebilir (key yenileme); her seferinde latency orijinal acquire timestamp'ine göre hesaplandığı için 2.+ ölçümler hatalı büyür. Informational olduğu için kabul edilir.
- **Tek-oturum varsayımı:** `currentDrmScheme` ve `lastDrmAcquireTimestampMs` tek değerlidir. Ayrı eşzamanlı video/audio DRM oturumlarında ikinci acquire ilkinin değerlerini ezer ve latency atfı karışır. Tipik tek-oturum akışları için kabul edilir.

---

## Proposed Changes

### Model Layer

#### [NEW] `model/DrmSessionEvent.kt`

DRM session lifecycle event timeline. `TrackSwitchEvent` sealed interface pattern'ını takip eder. **4 alt tip** (KeysRestored/KeysRemoved yok; SessionReleased duration taşımaz):

```kotlin
sealed interface DrmSessionEvent {
    val timestampMs: Long
    val scheme: DrmScheme

    /** DRM session acquired — session is opening/opened. */
    data class SessionAcquired(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        val state: DrmSessionState,
    ) : DrmSessionEvent

    /** DRM keys successfully loaded — license acquisition complete. */
    data class KeysLoaded(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        val licenseLatencyMs: Long, // SessionAcquired → KeysLoaded; key rotation'da şişebilir (informational)
    ) : DrmSessionEvent

    /** DRM session released. */
    data class SessionReleased(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
    ) : DrmSessionEvent

    /** DRM session manager error — also routed to Errors view. */
    data class SessionError(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        val message: String,
        val detail: String?,
    ) : DrmSessionEvent
}
```

#### [NEW] `model/DrmScheme.kt`

```kotlin
enum class DrmScheme {
    WIDEVINE,
    PLAYREADY,
    CLEARKEY,
    UNKNOWN,
}
```

#### [NEW] `model/DrmSessionState.kt`

`RELEASED` dahil 5 değer (mapDrmState'in `DrmSession.STATE_*` sabitlerinin tamamını eşleyebilmesi için):

```kotlin
enum class DrmSessionState {
    OPENING,
    OPENED,
    OPENED_WITH_KEYS,
    RELEASED,
    ERROR,
}
```

#### [NEW] `model/DrmStatusInfo.kt`

Summary panel DRM satırı için canlı durum:

```kotlin
data class DrmStatusInfo(
    val scheme: DrmScheme,
    val state: DrmSessionState,
    val lastLicenseLatencyMs: Long? = null,
)
```

#### [MODIFY] `model/ErrorCategory.kt`

```diff
 enum class ErrorCategory {
-    /** Segment / manifest load failure (onLoadError). DRM is handled in M8. */
+    /** Segment / manifest load failure (onLoadError). DRM errors are captured via
+        onDrmSessionManagerError, not here. */
     LOAD_ERROR,
     VIDEO_CODEC_ERROR,
     DROPPED_FRAMES,
     AUDIO_SINK_ERROR,
     AUDIO_CODEC_ERROR,
+
+    /** DRM session manager error (onDrmSessionManagerError). */
+    DRM_ERROR,
 }
```

#### [MODIFY] `model/PlaybackErrorEvent.kt`

```diff
 sealed interface ErrorDetail {
     data class DroppedFrames(...) : ErrorDetail
+
+    /** DRM error context — attached to DRM_ERROR events. */
+    data class DrmErrorInfo(
+        val scheme: DrmScheme,
+        val errorClass: String,
+    ) : ErrorDetail
 }
```

---

### Interception Layer

#### [NEW] `internal/DrmSchemeDetector.kt`

Pure, framework-bağımsız (Android UI yok) helper — Robolectric'siz unit-testable. Scheme tespiti + state mapping burada (detekt için `PlayerInterceptor`'dan ayrı):

```kotlin
@UnstableApi
internal object DrmSchemeDetector {
    /** eventTime'ın işaret ettiği window'un MediaItem'ından DRM scheme'i çıkarır. */
    fun detectScheme(eventTime: AnalyticsListener.EventTime): DrmScheme {
        val timeline = eventTime.timeline
        if (timeline.isEmpty || eventTime.windowIndex >= timeline.windowCount) return DrmScheme.UNKNOWN
        val mediaItem = timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        val uuid = mediaItem.localConfiguration?.drmConfiguration?.scheme ?: return DrmScheme.UNKNOWN
        return mapUuidToScheme(uuid) ?: DrmScheme.UNKNOWN
    }

    fun mapUuidToScheme(uuid: UUID): DrmScheme? = when (uuid) {
        C.WIDEVINE_UUID -> DrmScheme.WIDEVINE
        C.PLAYREADY_UUID -> DrmScheme.PLAYREADY
        C.CLEARKEY_UUID -> DrmScheme.CLEARKEY
        else -> null
    }

    fun mapDrmState(@DrmSession.State state: Int): DrmSessionState = when (state) {
        DrmSession.STATE_OPENING -> DrmSessionState.OPENING
        DrmSession.STATE_OPENED -> DrmSessionState.OPENED
        DrmSession.STATE_OPENED_WITH_KEYS -> DrmSessionState.OPENED_WITH_KEYS
        DrmSession.STATE_RELEASED -> DrmSessionState.RELEASED
        DrmSession.STATE_ERROR -> DrmSessionState.ERROR
        else -> DrmSessionState.OPENING
    }
}
```

> Not: `player.currentMediaItem` yerine `eventTime.timeline.getWindow(...)` kullanılır; playlist geçişlerinde olayın ait olduğu window'un MediaItem'ına doğru erişim sağlar. Boş timeline / out-of-range index → `UNKNOWN`.

#### [NEW] `internal/DrmSessionTracker.kt`

Yalnızca DRM callback'lerini implemente eden ayrı bir `AnalyticsListener`. `PlayerInterceptor` tarafından oluşturulup player'a **ek listener** olarak register edilir. Böylece `PlayerInterceptor`'ın fonksiyon sayısı artmaz.

State alanları (tek-oturum varsayımı — bkz. Known Limitations):
```kotlin
private var lastDrmAcquireTimestampMs: Long = 0L  // license latency için
private var currentDrmScheme: DrmScheme = DrmScheme.UNKNOWN
```

Callback'ler:
```kotlin
@UnstableApi
internal class DrmSessionTracker(
    private val sessionStore: SessionStore,
) : AnalyticsListener {

    override fun onDrmSessionAcquired(
        eventTime: AnalyticsListener.EventTime,
        @DrmSession.State state: Int,
    ) {
        val now = System.currentTimeMillis()
        lastDrmAcquireTimestampMs = now
        if (currentDrmScheme == DrmScheme.UNKNOWN) {
            currentDrmScheme = DrmSchemeDetector.detectScheme(eventTime)
        }
        val sessionState = DrmSchemeDetector.mapDrmState(state)
        sessionStore.addDrmSessionEvent(DrmSessionEvent.SessionAcquired(now, currentDrmScheme, sessionState))
        sessionStore.updateDrmState(DrmStatusInfo(currentDrmScheme, sessionState))
        Log.d(TAG, "DRM session acquired: ${currentDrmScheme.name} state=$sessionState")
    }

    override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime) {
        val now = System.currentTimeMillis()
        // NOTE: latency yaklaşıktır — key rotation'da onDrmKeysLoaded tekrar tetiklenir ve
        // her seferinde orijinal acquire timestamp'ine göre hesaplanır (informational).
        val latency = if (lastDrmAcquireTimestampMs > 0) now - lastDrmAcquireTimestampMs else 0L
        if (currentDrmScheme == DrmScheme.UNKNOWN) {
            currentDrmScheme = DrmSchemeDetector.detectScheme(eventTime)
        }
        sessionStore.addDrmSessionEvent(DrmSessionEvent.KeysLoaded(now, currentDrmScheme, latency))
        sessionStore.updateDrmState(DrmStatusInfo(currentDrmScheme, DrmSessionState.OPENED_WITH_KEYS, latency))
        Log.d(TAG, "DRM keys loaded: ${currentDrmScheme.name} latency=${latency}ms")
    }

    override fun onDrmSessionReleased(eventTime: AnalyticsListener.EventTime) {
        val now = System.currentTimeMillis()
        sessionStore.addDrmSessionEvent(DrmSessionEvent.SessionReleased(now, currentDrmScheme))
        sessionStore.updateDrmState(null) // session kapatıldı; chip events.isNotEmpty() ile görünür kalır
        Log.d(TAG, "DRM session released: ${currentDrmScheme.name}")
    }

    override fun onDrmSessionManagerError(
        eventTime: AnalyticsListener.EventTime,
        error: Exception,
    ) {
        val now = System.currentTimeMillis()
        if (currentDrmScheme == DrmScheme.UNKNOWN) {
            currentDrmScheme = DrmSchemeDetector.detectScheme(eventTime)
        }
        // 1. DRM timeline
        sessionStore.addDrmSessionEvent(
            DrmSessionEvent.SessionError(
                now, currentDrmScheme,
                error.message ?: error::class.simpleName ?: "DRM error",
                error.toString(),
            ),
        )
        sessionStore.updateDrmState(DrmStatusInfo(currentDrmScheme, DrmSessionState.ERROR))
        // 2. Errors view (dual surface, tek kaynak)
        sessionStore.addPlaybackError(
            PlaybackErrorEvent(
                timestampMs = now,
                category = ErrorCategory.DRM_ERROR,
                message = error.message ?: error::class.simpleName ?: "DRM session error",
                detail = error.toString(),
                categoryDetail = ErrorDetail.DrmErrorInfo(
                    scheme = currentDrmScheme,
                    errorClass = error::class.simpleName ?: "Exception",
                ),
            ),
        )
        Log.d(TAG, "DRM session error: ${error.message}")
    }

    /** PlayerInterceptor.detach()'ten çağrılır. */
    fun reset() {
        lastDrmAcquireTimestampMs = 0L
        currentDrmScheme = DrmScheme.UNKNOWN
    }

    private companion object {
        const val TAG = "StreamProbe"
    }
}
```

#### [MODIFY] `internal/PlayerInterceptor.kt`

Yalnızca tracker'ı sahiplenip register/unregister eder. **Yeni fonksiyon eklenmez**; `onLoadError` ve `probeTracks` dokunulmadan kalır.

```diff
 internal class PlayerInterceptor(
     private val sessionStore: SessionStore,
 ) : Player.Listener,
     AnalyticsListener {
     private var player: ExoPlayer? = null
+    private val drmTracker = DrmSessionTracker(sessionStore)
     ...

     fun attach(player: ExoPlayer) {
         this.player = player
         player.addListener(this)
         player.addAnalyticsListener(this)
+        player.addAnalyticsListener(drmTracker)
         probeTracks(player)
     }

     fun detach() {
+        player?.removeAnalyticsListener(drmTracker)
         player?.removeAnalyticsListener(this)
         player?.removeListener(this)
         player = null
         ...
+        drmTracker.reset()
     }
```

---

### State Layer

#### [MODIFY] `internal/SessionStore.kt`

İki yeni StateFlow (ayrı `isDrmDetected` **yok** — görünürlük `drmSessionEvents.isNotEmpty()`'ten türetilir):

```kotlin
// DRM session lifecycle events (timeline + görünürlük kaynağı)
private val _drmSessionEvents = MutableStateFlow<List<DrmSessionEvent>>(emptyList())
val drmSessionEvents: StateFlow<List<DrmSessionEvent>> = _drmSessionEvents.asStateFlow()

// Güncel DRM durumu (summary satırı için)
private val _currentDrmState = MutableStateFlow<DrmStatusInfo?>(null)
val currentDrmState: StateFlow<DrmStatusInfo?> = _currentDrmState.asStateFlow()
```

Metodlar:
```kotlin
fun addDrmSessionEvent(event: DrmSessionEvent) {
    _drmSessionEvents.update { current ->
        if (current.size >= MAX_DRM_EVENTS) current.drop(1) + event else current + event
    }
}

fun updateDrmState(info: DrmStatusInfo?) {
    _currentDrmState.value = info
}
```

`clear()`:
```diff
 fun clear() {
     ...
+    _drmSessionEvents.value = emptyList()
+    _currentDrmState.value = null
 }
```

Constant:
```kotlin
private const val MAX_DRM_EVENTS = 200
```
> Test erişimi için `@VisibleForTesting internal` yapılabilir (mevcut `MAX_TRACK_SWITCH_EVENTS` gibi).

---

### Overlay Layer

#### [MODIFY] `internal/overlay/OverlayPanelView.kt`

Yeni view'lar (başlangıçta `GONE`), property adı **`drmSectionLabel`** (label ve summary satırı tek view setidir):

```kotlin
val drmSectionLabel: TextView  // "DRM" section label — GONE
val drmStatusView: TextView    // summary satırı — GONE
val drmChip: OverlayFilterChip // chip row'da, switchesChip'ten sonra — GONE
```

Bunlar **hem `buildPortraitBody` hem `buildLandscapeBody`**'ye eklenir (orijinal planda landscape eksikti):

```kotlin
// Portrait — activeSubtitleView sonrası, latestSegment öncesi:
body.addView(drmSectionLabel.also { it.visibility = GONE }, marginBottom = dp(4f).toInt())
body.addView(drmStatusView.also { it.visibility = GONE }, marginBottom = dp(12f).toInt())

// Landscape — leftCol içinde, SUBTITLE bloğundan sonra (aynı sıra):
leftCol.addView(drmSectionLabel.also { it.visibility = GONE }, marginBottom = dp(4f).toInt())
leftCol.addView(drmStatusView.also { it.visibility = GONE }, marginBottom = dp(8f).toInt())
```

DRM chip (`chipRow`'a, switchesChip'ten sonra; `GONE`):
```kotlin
drmChip = OverlayFilterChip(context).apply {
    text = "DRM"
    isChecked = false
    visibility = GONE
}
chipRow.addView(drmChip, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginStart = dp(6f).toInt() })
```
> Not (#17 — kabul edildi, değiştirilmiyor): portrait 310dp'de 4 chip ("Tracks/Segments/Switches/DRM") darda kalabilir; DRM chip yalnızca DRM içerikte göründüğü için tolere edilir.

#### [NEW] `internal/overlay/DrmTimelineAdapter.kt`

`ListAdapter<DrmSessionEvent, ViewHolder>` — `SwitchTimelineAdapter` pattern'ı. DiffUtil `areItemsTheSame = timestampMs == ... && old::class == new::class`. Auto-scroll-to-end mevcut `attachAutoScrollToEnd` ile.

#### [NEW] `internal/overlay/DrmTimelineItemView.kt`

`SwitchTimelineItemView` stilinde programmatic row: event dot → scheme badge (WV/PR/CK/DRM) → event label → timing (latency varsa) → relative timestamp.

#### [MODIFY] `internal/overlay/OverlayManager.kt`

```diff
-    private enum class ViewMode { TRACKS, SEGMENTS, SWITCHES, ERRORS }
+    private enum class ViewMode { TRACKS, SEGMENTS, SWITCHES, DRM, ERRORS }
```

```kotlin
private var drmAdapter: DrmTimelineAdapter? = null
```

`show()` (diğer adapter'larla birlikte):
```kotlin
drmAdapter = DrmTimelineAdapter()
attachAutoScrollToEnd(overlay.trackList, drmAdapter!!)
```

`setupChips()` — DRM chip wiring (inline):
```kotlin
overlay.drmChip.setOnClickListener {
    viewMode = ViewMode.DRM
    applyViewMode(overlay, viewMode)
}
```

`applyViewMode()`:
```diff
+    overlay.drmChip.isChecked = mode == ViewMode.DRM
     overlay.trackList.adapter =
         when (mode) {
             ViewMode.TRACKS -> renditionAdapter
             ViewMode.SEGMENTS -> segmentAdapter
             ViewMode.SWITCHES -> switchAdapter
+            ViewMode.DRM -> drmAdapter
             ViewMode.ERRORS -> errorAdapter
         }
```

`startObserving()` — yeni `observeDrm(overlay)` çağrısı (LongMethod'dan kaçınmak için ayrı fonksiyon, mevcut `observeTrackListInfo`/`observePlaybackErrors` pattern'ı):

```kotlin
private fun observeDrm(overlay: OverlayPanelView) {
    // Timeline + görünürlük (isNotEmpty) + viewMode guard
    scope?.launch {
        sessionStore.drmSessionEvents.collect { events ->
            drmAdapter?.submitList(events)
            val hasDrm = events.isNotEmpty()
            overlay.drmChip.visibility = if (hasDrm) View.VISIBLE else View.GONE
            overlay.drmSectionLabel.visibility = if (hasDrm) View.VISIBLE else View.GONE
            overlay.drmStatusView.visibility = if (hasDrm) View.VISIBLE else View.GONE
            // DRM kaybolursa (örn. clear) ve DRM görünümündeysek TRACKS'e dön — kilitlenmeyi önler.
            if (!hasDrm && viewMode == ViewMode.DRM) {
                viewMode = ViewMode.TRACKS
                applyViewMode(overlay, viewMode)
            }
        }
    }
    // Summary satırı
    scope?.launch {
        sessionStore.currentDrmState.collect { drmInfo ->
            overlay.drmStatusView.text = OverlayFormatters.formatDrmStatus(drmInfo)
        }
    }
}
```

`hide()`:
```diff
+    drmAdapter = null
```

#### [MODIFY] `internal/overlay/OverlayFormatters.kt`

```kotlin
/** "Widevine · Keys Loaded · 312ms" veya "Widevine · Opening" */
fun formatDrmStatus(info: DrmStatusInfo?): String {
    if (info == null) return "—"
    val parts = mutableListOf(formatDrmScheme(info.scheme), formatDrmSessionState(info.state))
    info.lastLicenseLatencyMs?.let { parts += "${it}ms" }
    return parts.joinToString("  ·  ")
}

fun formatDrmScheme(scheme: DrmScheme): String = when (scheme) {
    DrmScheme.WIDEVINE -> "Widevine"
    DrmScheme.PLAYREADY -> "PlayReady"
    DrmScheme.CLEARKEY -> "ClearKey"
    DrmScheme.UNKNOWN -> "Unknown DRM"
}

fun formatDrmSchemeBadge(scheme: DrmScheme): String = when (scheme) {
    DrmScheme.WIDEVINE -> "WV"
    DrmScheme.PLAYREADY -> "PR"
    DrmScheme.CLEARKEY -> "CK"
    DrmScheme.UNKNOWN -> "DRM"
}

fun formatDrmSessionState(state: DrmSessionState): String = when (state) {
    DrmSessionState.OPENING -> "Opening"
    DrmSessionState.OPENED -> "Opened"
    DrmSessionState.OPENED_WITH_KEYS -> "Keys Loaded"
    DrmSessionState.RELEASED -> "Released"
    DrmSessionState.ERROR -> "Error"
}

fun formatDrmEventLabel(event: DrmSessionEvent): String = when (event) {
    is DrmSessionEvent.SessionAcquired -> "Session Acquired (${formatDrmSessionState(event.state)})"
    is DrmSessionEvent.KeysLoaded -> "Keys Loaded"
    is DrmSessionEvent.SessionReleased -> "Session Released"
    is DrmSessionEvent.SessionError -> "Error: ${event.message}"
}
```

`formatErrorCategory()`:
```diff
+    ErrorCategory.DRM_ERROR -> "DRM"
```

#### [MODIFY] `internal/overlay/OverlayDrawables.kt`

Errors-tab DRM rengi (`errorCategoryDot` `when`'ine yeni branch):
```diff
+    ErrorCategory.DRM_ERROR -> "#64D2FF".toColorInt() // Cyan
```

DRM event dots — **SessionError, errors-tab ile aynı cyan** (#15):
```kotlin
fun drmEventDot(event: DrmSessionEvent): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(
        when (event) {
            is DrmSessionEvent.SessionAcquired -> "#0A84FF".toColorInt()  // Blue
            is DrmSessionEvent.KeysLoaded -> "#30D158".toColorInt()       // Green
            is DrmSessionEvent.SessionReleased -> "#8E8E93".toColorInt()  // Gray
            is DrmSessionEvent.SessionError -> "#64D2FF".toColorInt()     // Cyan (errors-tab ile aynı)
        },
    )
}
```

---

## File Summary

| Action | File | Component |
|---|---|---|
| **NEW** | `model/DrmSessionEvent.kt` | Sealed interface — 4 lifecycle event subtypes |
| **NEW** | `model/DrmScheme.kt` | Enum: WIDEVINE, PLAYREADY, CLEARKEY, UNKNOWN |
| **NEW** | `model/DrmSessionState.kt` | Enum: OPENING, OPENED, OPENED_WITH_KEYS, RELEASED, ERROR |
| **NEW** | `model/DrmStatusInfo.kt` | Summary panel data class |
| **NEW** | `internal/DrmSchemeDetector.kt` | Pure helper — scheme detect (eventTime) + UUID/state mapping |
| **NEW** | `internal/DrmSessionTracker.kt` | Ayrı `AnalyticsListener` — 4 DRM callback + reset |
| **NEW** | `overlay/DrmTimelineAdapter.kt` | ListAdapter for DRM event timeline |
| **NEW** | `overlay/DrmTimelineItemView.kt` | Programmatic item view for DRM events |
| **MODIFY** | `model/ErrorCategory.kt` | + `DRM_ERROR`; LOAD_ERROR yorumu güncellenir |
| **MODIFY** | `model/PlaybackErrorEvent.kt` | + `ErrorDetail.DrmErrorInfo` |
| **MODIFY** | `internal/PlayerInterceptor.kt` | tracker register/unregister + reset (yeni fonksiyon yok) |
| **MODIFY** | `internal/SessionStore.kt` | + 2 StateFlow (`drmSessionEvents`, `currentDrmState`), metodlar, `clear()`, MAX_DRM_EVENTS |
| **MODIFY** | `overlay/OverlayPanelView.kt` | + DRM summary row + chip (portrait & landscape, `GONE`) |
| **MODIFY** | `overlay/OverlayManager.kt` | + `DRM` ViewMode, adapter, `observeDrm()`, chip wiring |
| **MODIFY** | `overlay/OverlayFormatters.kt` | + DRM formatting fonksiyonları |
| **MODIFY** | `overlay/OverlayDrawables.kt` | + DRM error color (cyan), DRM event dot renkleri |

---

## Verification Plan

### Automated Tests

#### [NEW] `DrmSessionTrackerTest.kt`
- `onDrmSessionAcquired emits SessionAcquired with mapped state` (scheme mock'lu eventTime'da UNKNOWN; state mapping doğrulanır)
- `onDrmSessionManagerError emits BOTH DrmSessionEvent AND PlaybackErrorEvent` (deterministik — zaman bağımsız)
- `onDrmSessionReleased emits SessionReleased and clears currentDrmState`
- `reset clears DRM state fields`

> Çıkarılan testler: license-latency-correct, session-duration-correct (zaman enjeksiyonu olmadan anlamsız), detects-Widevine-from-MediaItem, probeTracks-detects-DRM, KeysRestored/KeysRemoved (event tipleri kaldırıldı), proaktif-detection testleri.

#### [NEW] `DrmSchemeDetectorTest.kt`
- `mapUuidToScheme for Widevine / PlayReady / ClearKey / unknown`
- `mapDrmState for all 5 DrmSession states (incl. STATE_RELEASED)`
- `detectScheme returns UNKNOWN for empty timeline`

#### [NEW] `DrmSessionStoreTest.kt`
- `addDrmSessionEvent appends to list`
- `DRM event list is capped at MAX_DRM_EVENTS`
- `updateDrmState emits to currentDrmState flow`
- `clear resets DRM flows`

#### [NEW] `DrmFormattingTest.kt`
- `formatDrmStatus with Widevine keys loaded and latency` / `null returns dash`
- `formatDrmScheme / formatDrmSchemeBadge / formatDrmSessionState for all enum values`
- `formatDrmEventLabel for all 4 event types`
- `formatErrorCategory for DRM_ERROR returns DRM`

#### Değişmeyen test
- `PlaybackErrorTrackingTest.onLoadError ignores DRM data-type errors` → **olduğu gibi kalır** (onLoadError'a dokunulmadı).

#### Build verification
```bash
./gradlew :sdk:test
./gradlew :sdk:lint
./gradlew :sdk:detekt
```

### Manual Verification

DRM'in gerçekten çalıştığını (ExoPlayer'ın callback'i fire ettiğini) doğrulamak için:

- Demo app'e **Widevine test stream**'i ekle → DRM chip + summary satırının yalnızca DRM oturumu başlayınca göründüğünü doğrula.
- DRM tab'da lifecycle timeline + lisans latency'sini doğrula.
- Demo app'e **kasıtlı bozuk/yanlış lisans URL'li** bir varyant ekle → hatanın **hem DRM tab'da `SessionError` hem Errors view'da `DRM_ERROR`** olarak göründüğünü doğrula (tek hata, iki yüzey, duplikasyon yok).
- Sticky görünürlük: DRM oturumu sonrası aynı player'da DRM'siz içeriğe geçildiğinde chip/summary'nin **görünür kaldığını** doğrula (reactive-only beklenen davranış). _Eski plandaki "non-DRM'e geç → chip kaybolur" adımı geçersiz._
