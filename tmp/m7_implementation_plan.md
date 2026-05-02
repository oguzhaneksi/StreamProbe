# M7 — Audio & Subtitle Tracks

StreamProbe'un track izlemesini `C.TRACK_TYPE_AUDIO` ve `C.TRACK_TYPE_TEXT` ile genişletir. Şu an SDK yalnızca video variant'larını ve aktif video rendition'ını yakalıyor. M7, manifestten tüm audio/subtitle rendition'larını tam olarak çıkarır (HLS muxed sources dahil), aktif audio/subtitle bilgisini overlay'da gerçek zamanlı gösterir, ve track switch event modelini genişleterek **video, audio ve subtitle değişimlerini birleşik bir sealed `TrackSwitchEvent` zaman çizelgesinde** kayıt altına alır.

---

## Background — Current State

### Mevcut yetenekler
- **Video variant'ları** HLS multivariant playlist / DASH MPD'den (`VariantInfo` — bitrate, resolution, codec, frameRate)
- **Aktif video track** `onTracksChanged` / `onVideoInputFormatChanged` → `ActiveTrackInfo`
- **ABR switch'ler** `onDownstreamFormatChanged` ile — yalnızca `C.TRACK_TYPE_VIDEO` filtresi
- **Segment, CDN header, error**'lar — protokolden bağımsız

### Mimari dokunuş noktaları
| Bileşen | Dosya | Rol |
|---|---|---|
| Public API | [StreamProbe.kt] | Giriş noktası — değişmez |
| Yakalama | [PlayerInterceptor.kt] | `AnalyticsListener` + `Player.Listener` |
| State | [SessionStore.kt] | Thread-safe `StateFlow` store |
| Modeller | [model/] | SDK-owned data classes |
| Overlay | [overlay/] | Programmatik view'lar, adapter'lar, formatter'lar |

### M7'nin gidereceği eksiklikler
1. `probeManifest()` — DASH parsing yalnızca `TRACK_TYPE_VIDEO` adaptation set'lerini alıyor; HLS parsing yalnızca `multivariantPlaylist.variants`'ı alıyor (audios/subtitles/closedCaptions/muxedAudio/muxedCaption göz ardı).
2. `probeTracks()` — `fmt.width > 0 || fmt.height > 0` filtresi audio/subtitle'ı atlıyor; ilk video bulununca early `return` ediyor.
3. `ManifestInfo.variants` — düz `List<VariantInfo>`; audio/subtitle alanı yok.
4. `onDownstreamFormatChanged` audio/subtitle değişimlerini loglamıyor.
5. Overlay AUDIO/SUBTITLE satırı ve aktif track ayrımı barındırmıyor.
6. Variants listesi yalnızca video rendition'larını gösteriyor.

---

## Proposed Changes

### Modeller

#### [NEW] [AudioTrackInfo.kt]

```kotlin
data class AudioTrackInfo(
    /** Programmatic match key (BCP 47), e.g. "en", "tr". Null if unspecified. */
    val language: String?,
    /** Display string from the manifest, e.g. "English (Descriptive)". Null if absent. */
    val label: String?,
    val codecs: String?,
    val bitrate: Int,
    val channelCount: Int,
    val sampleRate: Int,
    /** True if this audio is muxed inside a video variant (HLS muxedAudioFormat). */
    val isMuxed: Boolean = false,
)
```

`language` programmatic eşleme (active vs rendition listesi) için, `label` overlay/list görüntüsü için. `sampleRate` formatter'da `kHz` olarak gösterilir. `isMuxed` Tracks tab'ında "muxed" badge'i için kullanılır.

---

#### [NEW] [SubtitleTrackInfo.kt]

```kotlin
enum class SubtitleKind {
    /** Sidecar WebVTT/TTML rendition declared in the manifest. */
    SIDECAR,
    /** CEA-608/708 closed caption explicitly declared at manifest level. */
    CC_DECLARED,
    /** Closed caption embedded inside a video variant (HLS muxedCaptionFormats). */
    CC_MUXED,
}

data class SubtitleTrackInfo(
    val language: String?,
    val label: String?,
    val mimeType: String?,
    val kind: SubtitleKind,
)
```

Manifest'in üç altyazı kaynağı (`subtitles`, `closedCaptions`, `muxedCaptionFormats`) tek bir liste içinde, `kind` ayrımıyla taşınır. Tracks tab'ında `kind != SIDECAR` ise "(CC)" rozeti gösterilir.

---

#### [MODIFY] [ManifestInfo.kt]

```diff
 sealed interface ManifestInfo {
     val variants: List<VariantInfo>
+    val audioTracks: List<AudioTrackInfo>
+    val subtitleTracks: List<SubtitleTrackInfo>
 }
```

`HlsManifestInfo` ve `DashManifestInfo` her ikisi de yeni alanları override eder (default `emptyList()`).

```diff
 data class HlsManifestInfo(
     override val variants: List<VariantInfo>,
+    override val audioTracks: List<AudioTrackInfo> = emptyList(),
+    override val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
 ) : ManifestInfo
```

```diff
 data class DashManifestInfo(
     override val variants: List<VariantInfo>,
+    override val audioTracks: List<AudioTrackInfo> = emptyList(),
+    override val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
 ) : ManifestInfo
```

---

#### [REPLACEMENT] `AbrSwitchEvent` → sealed `TrackSwitchEvent`

`AbrSwitchEvent` data class'ı kaldırılıp `TrackSwitchEvent` sealed interface'i ile değiştirilir. Bu, video/audio/subtitle değişimlerini tek koherent zaman çizelgesinde toplar.

```kotlin
sealed interface TrackSwitchEvent {
    val timestampMs: Long
    val bufferDurationMs: Long
    val reason: SwitchReason

    data class VideoSwitch(
        override val timestampMs: Long,
        override val bufferDurationMs: Long,
        override val reason: SwitchReason,
        val previousTrack: ActiveTrackInfo?,
        val newTrack: ActiveTrackInfo,
    ) : TrackSwitchEvent

    data class AudioSwitch(
        override val timestampMs: Long,
        override val bufferDurationMs: Long,
        override val reason: SwitchReason,
        val previousTrack: AudioTrackInfo?,
        val newTrack: AudioTrackInfo,
    ) : TrackSwitchEvent

    data class SubtitleSwitch(
        override val timestampMs: Long,
        override val bufferDurationMs: Long,
        override val reason: SwitchReason,
        val previousTrack: SubtitleTrackInfo?,
        /** null = subtitle disabled by the user. */
        val newTrack: SubtitleTrackInfo?,
    ) : TrackSwitchEvent
}
```

- `bufferDurationMs` üç alt tip için de tutulur (debug için faydalı).
- `reason` audio/subtitle değişimlerinde çoğunlukla `MANUAL`; multi-bitrate audio'da `ADAPTIVE` da görülür.
- Subtitle disable da bir event'tir → `SubtitleSwitch.newTrack` nullable.

> **Migration:** `AbrSwitchEvent` referans veren tüm kodlar (`SessionStore`, `AbrTimelineAdapter`, `AbrTimelineItemView`, ABR testleri) güncellenir.

---

### Interception — PlayerInterceptor

#### `probeManifest` — audio + subtitle çıkarımı

**HLS dalı:**
- `multivariantPlaylist.variants` → `VariantInfo` (mevcut)
- `multivariantPlaylist.audios` → `AudioTrackInfo(isMuxed = false)`
- `multivariantPlaylist.muxedAudioFormat` (varsa) → `AudioTrackInfo(isMuxed = true)`
- `multivariantPlaylist.subtitles` → `SubtitleTrackInfo(kind = SIDECAR)`
- `multivariantPlaylist.closedCaptions` → `SubtitleTrackInfo(kind = CC_DECLARED)`
- `multivariantPlaylist.muxedCaptionFormats` (varsa) → `SubtitleTrackInfo(kind = CC_MUXED)`

**DASH dalı:** Mevcut period/adaptationSet döngüsünde `when (adaptationSet.type)` switch'i:
- `TRACK_TYPE_VIDEO` → `VariantInfo` (mevcut)
- `TRACK_TYPE_AUDIO` → `AudioTrackInfo` (DASH'te muxed konsepti yok; `isMuxed = false`)
- `TRACK_TYPE_TEXT` → `SubtitleTrackInfo(kind = SIDECAR)`

`Format` → model dönüşümü için `Format.toAudioTrackInfo(isMuxed: Boolean)` ve `Format.toSubtitleTrackInfo(kind: SubtitleKind)` extension'ları eklenir.

#### `probeTracks` — yeniden yapılandırma

Erken `return` kaldırılır; `Tracks.Group.type` switch'i ile dispatch eder. Subtitle disable → `null` emit eder:

```kotlin
private fun probeTracks(player: Player) {
    var foundVideo: Format? = null
    var foundAudio: AudioTrackInfo? = null
    var foundSubtitle: SubtitleTrackInfo? = null

    for (group in player.currentTracks.groups) {
        if (!group.isSelected) continue
        val format = (0 until group.length)
            .firstOrNull { group.isTrackSelected(it) }
            ?.let { group.getTrackFormat(it) } ?: continue

        when (group.type) {
            C.TRACK_TYPE_VIDEO -> foundVideo = format
            C.TRACK_TYPE_AUDIO -> foundAudio = format.toAudioTrackInfo(isMuxed = false)
            C.TRACK_TYPE_TEXT  -> foundSubtitle = format.toSubtitleTrackInfo(SubtitleKind.SIDECAR)
        }
    }

    foundVideo?.let { updateActiveTrack(it) }
    sessionStore.updateActiveAudioTrack(foundAudio)         // null = audio not yet probed
    sessionStore.updateActiveSubtitleTrack(foundSubtitle)   // null = subtitle disabled

    // SubtitleSwitch(null) emit if subtitle was previously selected and is now off.
    if (lastSubtitleTrack != null && foundSubtitle == null) {
        sessionStore.addTrackSwitchEvent(
            TrackSwitchEvent.SubtitleSwitch(
                timestampMs = System.currentTimeMillis(),
                bufferDurationMs = player.totalBufferedDuration ?: 0L,
                reason = SwitchReason.MANUAL,
                previousTrack = lastSubtitleTrack,
                newTrack = null,
            )
        )
        lastSubtitleTrack = null
    }
}
```

#### `onDownstreamFormatChanged` — track type dispatch

Mevcut video-only filter kaldırılır. Her tip için ayrı `last*Track` dedup değişkeni:

```kotlin
private var lastVideoTrack: ActiveTrackInfo? = null
private var lastAudioTrack: AudioTrackInfo? = null
private var lastSubtitleTrack: SubtitleTrackInfo? = null

override fun onDownstreamFormatChanged(
    eventTime: AnalyticsListener.EventTime,
    mediaLoadData: MediaLoadData,
) {
    val format = mediaLoadData.trackFormat ?: return
    val timestamp = System.currentTimeMillis()
    val buffer = player?.totalBufferedDuration ?: 0L
    val reason = mapSelectionReason(mediaLoadData.trackSelectionReason)

    when (mediaLoadData.trackType) {
        C.TRACK_TYPE_VIDEO,
        C.TRACK_TYPE_DEFAULT -> {
            // DEFAULT only counts as video if dimensions are present.
            if (mediaLoadData.trackType == C.TRACK_TYPE_DEFAULT &&
                format.width <= 0 && format.height <= 0) return
            val newTrack = format.toActiveTrackInfo()
            if (lastVideoTrack == newTrack) return
            sessionStore.addTrackSwitchEvent(
                TrackSwitchEvent.VideoSwitch(timestamp, buffer, reason, lastVideoTrack, newTrack)
            )
            lastVideoTrack = newTrack
        }
        C.TRACK_TYPE_AUDIO -> {
            val newTrack = format.toAudioTrackInfo(isMuxed = false)
            if (lastAudioTrack == newTrack) return
            sessionStore.addTrackSwitchEvent(
                TrackSwitchEvent.AudioSwitch(timestamp, buffer, reason, lastAudioTrack, newTrack)
            )
            lastAudioTrack = newTrack
        }
        C.TRACK_TYPE_TEXT -> {
            val newTrack = format.toSubtitleTrackInfo(SubtitleKind.SIDECAR)
            if (lastSubtitleTrack == newTrack) return
            sessionStore.addTrackSwitchEvent(
                TrackSwitchEvent.SubtitleSwitch(timestamp, buffer, reason, lastSubtitleTrack, newTrack)
            )
            lastSubtitleTrack = newTrack
        }
    }
}
```

`detach()` üç `last*Track` değişkenini de `null`'a sıfırlar.

> Subtitle "off" geçişi `onDownstreamFormatChanged`'i tetiklemez; bu yüzden disable event'i `probeTracks` içinde `lastSubtitleTrack` non-null + probe sonucu null kontrolüyle üretilir (yukarıda).

---

### State — SessionStore

```diff
- private val _abrSwitchEvents = MutableStateFlow<List<AbrSwitchEvent>>(emptyList())
- val abrSwitchEvents: StateFlow<List<AbrSwitchEvent>> = _abrSwitchEvents.asStateFlow()
+ private val _trackSwitchEvents = MutableStateFlow<List<TrackSwitchEvent>>(emptyList())
+ val trackSwitchEvents: StateFlow<List<TrackSwitchEvent>> = _trackSwitchEvents.asStateFlow()

+ private val _activeAudioTrack = MutableStateFlow<AudioTrackInfo?>(null)
+ val activeAudioTrack: StateFlow<AudioTrackInfo?> = _activeAudioTrack.asStateFlow()

+ private val _activeSubtitleTrack = MutableStateFlow<SubtitleTrackInfo?>(null)
+ val activeSubtitleTrack: StateFlow<SubtitleTrackInfo?> = _activeSubtitleTrack.asStateFlow()
```

- `addAbrSwitchEvent(event)` → `addTrackSwitchEvent(event: TrackSwitchEvent)` (cap aynı: 200; sabit adı `MAX_TRACK_SWITCH_EVENTS`'e güncellenir).
- `updateActiveAudioTrack(info: AudioTrackInfo?)` ve `updateActiveSubtitleTrack(info: SubtitleTrackInfo?)` — nullable parametreler ile (subtitle off / audio not yet).
- `clear()` üçünü de `null`/`emptyList()`'e set eder.

---

### Overlay UI

#### [MODIFY] [OverlayPanelView.kt]

Yeni public `TextView` alanları:
```kotlin
val activeAudioView: TextView      // default text "Loading…"
val activeSubtitleView: TextView   // default text "Loading…"
```

`buildPortraitBody` ve `buildLandscapeBody`'de ACTIVE TRACK ile LATEST SEGMENT arasına iki yeni section eklenir:
```
ACTIVE TRACK
  1920×1080  ·  5.8 Mbps
AUDIO
  English  ·  AAC stereo  ·  128 kbps  ·  48 kHz
SUBTITLE
  Turkish (CC)
LATEST SEGMENT
  …
```

Default text üç view için de `"Loading…"` (mevcut `activeTrackView` ile tutarlı).

**Chip etiketleri yeniden adlandırılır:**
- "Variants" → "Tracks" (audio + subtitle de gösterdiği için)
- "ABR" → "Switches" (artık tek tip ABR değil, sealed `TrackSwitchEvent`)

`variantsChip.text = "Tracks"` ve `abrChip.text = "Switches"`. Property isimleri kozmetik — şimdilik korunabilir.

---

#### [MODIFY] [OverlayManager.kt]

`startObserving()` içine iki yeni `collect` bloğu ve mevcut ABR collect'in adaptasyonu:

```kotlin
scope?.launch {
    sessionStore.activeAudioTrack.collect { audio ->
        overlay.activeAudioView.text = OverlayFormatters.formatActiveAudio(audio)
        renditionAdapter?.activeAudio = audio
    }
}

scope?.launch {
    sessionStore.activeSubtitleTrack.collect { subtitle ->
        overlay.activeSubtitleView.text = OverlayFormatters.formatActiveSubtitle(subtitle)
        renditionAdapter?.activeSubtitle = subtitle
    }
}

scope?.launch {
    sessionStore.trackSwitchEvents.collect { events ->
        switchAdapter?.submitList(events)
    }
}
```

`AbrTimelineAdapter` → `SwitchTimelineAdapter` (sealed `TrackSwitchEvent`'e bind eder, türe göre renk/ikon).

---

#### [MODIFY] [OverlayFormatters.kt]

Üç state ayrı: probe öncesi → "Loading…" (default text); format eksik → "Unknown"; subtitle disable → "Off"; tam veri → formatlanmış string.

```kotlin
fun formatActiveAudio(audio: AudioTrackInfo?): String {
    if (audio == null) return "Loading…"
    val parts = mutableListOf<String>()
    val lang = audio.label
        ?: audio.language?.let { Locale.forLanguageTag(it).displayLanguage }
    if (!lang.isNullOrBlank()) parts += lang
    val codec = audio.codecs?.split(".")?.firstOrNull()?.uppercase(Locale.ROOT)
    val channels = when (audio.channelCount) {
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> if (audio.channelCount > 0) "${audio.channelCount}ch" else null
    }
    listOfNotNull(codec, channels).joinToString(" ")
        .takeIf { it.isNotBlank() }
        ?.let { parts += it }
    if (audio.bitrate > 0) parts += formatBitrate(audio.bitrate)
    if (audio.sampleRate > 0) parts += "${audio.sampleRate / 1000} kHz"
    return parts.joinToString("  ·  ").ifBlank { "Unknown" }
}

fun formatActiveSubtitle(subtitle: SubtitleTrackInfo?): String {
    if (subtitle == null) return "Off"
    val parts = mutableListOf<String>()
    val lang = subtitle.label
        ?: subtitle.language?.let { Locale.forLanguageTag(it).displayLanguage }
    if (!lang.isNullOrBlank()) parts += lang
    if (subtitle.kind != SubtitleKind.SIDECAR) parts += "(CC)"
    val mimeShort = when (subtitle.mimeType) {
        "text/vtt", "application/x-media3-webvtt" -> "WebVTT"
        "application/ttml+xml" -> "TTML"
        "application/x-subrip" -> "SRT"
        "text/x-ssa" -> "SSA"
        "application/cea-608", "application/cea-708" -> null  // already implied by (CC)
        else -> subtitle.mimeType?.substringAfterLast("/")
    }
    if (!mimeShort.isNullOrBlank()) parts += mimeShort
    return parts.joinToString("  ·  ").ifBlank { "Unknown" }
}
```

> `Locale.forLanguageTag(...)` BCP 47 uyumlu doğru çağrı; eski `Locale("", lang)` formu language alanını boş bırakıyordu.
> Subtitle StateFlow `null` değeri "kapalı" anlamına gelir; "loading" ayrımı yalnızca panel ilk açıldığında TextView'ın default text'i ile sağlanır.

---

#### [REPLACEMENT] [VariantListAdapter.kt] → `RenditionListAdapter`

Multi-type sealed wrapper:
```kotlin
sealed interface RenditionListItem {
    data class SectionHeader(val title: String) : RenditionListItem
    data class Video(val info: VariantInfo) : RenditionListItem
    data class Audio(val info: AudioTrackInfo) : RenditionListItem
    data class Subtitle(val info: SubtitleTrackInfo) : RenditionListItem
}

internal class RenditionListAdapter
    : ListAdapter<RenditionListItem, RecyclerView.ViewHolder>(DIFF) {

    var activeVideo: ActiveTrackInfo? = null
    var activeAudio: AudioTrackInfo? = null
    var activeSubtitle: SubtitleTrackInfo? = null
    // Each setter recomputes prev/new positions and calls notifyItemChanged.

    fun findPositionForVideo(track: ActiveTrackInfo?): Int    // bitrate + width + height
    fun findPositionForAudio(track: AudioTrackInfo?): Int     // language + codecs + isMuxed
    fun findPositionForSubtitle(track: SubtitleTrackInfo?): Int  // language + kind + mimeType
}
```

DIFF callback `RenditionListItem` üstünde polymorphic; her subtype için stable identity (örn. SectionHeader.title; Video bitrate+w+h; Audio language+codec+isMuxed; Subtitle language+kind+mimeType).

`OverlayManager` `manifestInfo`'yu `[VIDEO header] + videos + [AUDIO header] + audios + [SUBTITLES header] + subtitles` olarak düzleştirip `submitList`'e verir (boş section'ları header'la birlikte atlar).

---

#### [REPLACEMENT] [VariantItemView.kt] → `RenditionItemView`

Mevcut `VariantItemView` mimarisi (dot + üst satır + alt satır) tek view'da korunur; `bind(item: RenditionListItem, ...)` polymorphic. Section header için ayrı küçük `RenditionSectionView` (sade başlık).

| Tip | Üst satır | Alt satır | Aktif eşleme |
|---|---|---|---|
| Video | `1920×1080` + bitrate | codec | `activeVideo` |
| Audio | label/dil + (`stereo` vb.) + bitrate | codec + (isMuxed ise `muxed` pill) | `activeAudio` |
| Subtitle | label/dil + (kind != SIDECAR ise `(CC)`) | mimeType (varsa) | `activeSubtitle` |

---

#### [REPLACEMENT] `AbrTimelineAdapter` → `SwitchTimelineAdapter` ve `AbrTimelineItemView` adaptasyonu

`SwitchTimelineAdapter` sealed `TrackSwitchEvent`'e bind eder. Her alt tip için renk/etiket farklılaştırılır:
- VideoSwitch → mevcut görünüm
- AudioSwitch → farklı renk + dil etiketi
- SubtitleSwitch → "Off" durumu için özel render

---

### Demo App

[Stream.kt] çoklu audio/subtitle test stream'i kullanıcı tarafından eklenecek (M7 implementation kapsamı dışında).

---

### Documentation

#### [MODIFY] [README.md] satır 201

`*(Planned)*` etiketi `✅` ile değiştirilir + bir cümle özet:
```
- **M7 — Audio & Subtitle Tracks** ✅: Audio/subtitle rendition enumeration (HLS muxed sources dahil) + active audio/subtitle overlay; ABR switch event'leri sealed TrackSwitchEvent altında video/audio/subtitle'a genişledi.
```

#### [MODIFY] [SPEC.md]

Yeni `§3.9 Audio & Subtitle Track Monitoring` section'ı:
- Yakalanan veri (HLS audios + muxedAudioFormat + subtitles + closedCaptions + muxedCaptionFormats; DASH AUDIO/TEXT adaptation sets)
- Active tracking (`activeAudioTrack`, `activeSubtitleTrack`)
- Sealed `TrackSwitchEvent` modeli (Video/Audio/Subtitle)
- Overlay temsili

(SPEC §3.7/§3.8 mevcut sırası değiştirilmiyor.)

---

## Verification Plan

### Automated Tests

Mevcut üç test dosyası Robolectric kullanmaya devam eder; yeni case'ler **mevcut dosyalara eklenir**.

| Dosya | Eklenecek case'ler |
|---|---|
| [PlayerInterceptorTest.kt] | HLS `muxedAudioFormat` → `AudioTrackInfo(isMuxed = true)`; HLS `closedCaptions` + `muxedCaptionFormats` → `SubtitleTrackInfo(kind = CC_*)`; DASH AUDIO/TEXT adaptation set → `audioTracks`/`subtitleTracks`; `probeTracks` audio + subtitle dispatch; subtitle disable → `activeSubtitleTrack` null + `SubtitleSwitch(newTrack = null)`; `onDownstreamFormatChanged` audio + subtitle dedup; mevcut ABR testleri `TrackSwitchEvent.VideoSwitch` filtresine adapte |
| [SessionStoreTest.kt] | `updateActiveAudioTrack` / `updateActiveSubtitleTrack` emit + null reset; `addTrackSwitchEvent` her subtype için round-trip; `clear()` yeni alanları sıfırlar; `MAX_TRACK_SWITCH_EVENTS` cap |
| [OverlayFormattingTest.kt] | `formatActiveAudio`: null → "Loading…", partial → "Unknown", full + sampleRate; `formatActiveSubtitle`: null → "Off", `kind != SIDECAR` → "(CC)" suffix, WebVTT/TTML/SRT mime kısaltma; `Locale.forLanguageTag` doğru dönüşüm |

#### Test komutları

```bash
./gradlew :sdk:test --tests "com.streamprobe.sdk.internal.PlayerInterceptorTest"
./gradlew :sdk:test --tests "com.streamprobe.sdk.internal.SessionStoreTest"
./gradlew :sdk:test --tests "com.streamprobe.sdk.internal.overlay.OverlayFormattingTest"
```

### Manual Verification

1. Demo app + çoklu audio/subtitle stream (kullanıcı sağlayacak).
2. AUDIO ve SUBTITLE satırlarının doğru render edildiğini gözlemle (Loading → format / Off).
3. Track selector'dan audio dilini değiştir → Switches timeline'da `AudioSwitch` event görmeli; Tracks tab'ında active dot taşınmalı.
4. Subtitle aç/kapat → "Off" ↔ format text geçişi; `SubtitleSwitch(newTrack = null)` event'i kapatma için.
5. HLS Bipbop ve DASH (kullanıcı tarafından doğrulanan) ile her iki yol test edilir.
6. Orientation değişimi + attach/detach/reattach → state tutarlılığı.

---

## Out of Scope (M7)

- Çoklu audio/subtitle test stream'i `Stream.kt`'ye eklenmesi (kullanıcı yapacak).
- SPEC §3.7/§3.8 sıralama düzeltmesi (mevcut sıraya dokunulmaz).
- `StreamProbe.kt` public API'sinde yeni getter (mevcut StateFlow'lar zaten `internal`).
