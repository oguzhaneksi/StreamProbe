package com.streamprobe.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamprobe.android.AudioTrackOption
import com.streamprobe.android.SubtitleTrackOption
import com.streamprobe.android.VideoTrackOption

private val ACCENT = Color(0xFF0A84FF)
private val SURFACE = Color(0xFF1C1C1E)
private val ON_SURFACE = Color(0xFFEBEBF5)
private val ON_SURFACE_DIM = Color(0xFF8E8E93)
private val DIVIDER = Color(0xFF38383A)

data class TrackSelectionState(
    val videoOptions: List<VideoTrackOption>,
    val audioOptions: List<AudioTrackOption>,
    val subtitleOptions: List<SubtitleTrackOption>,
    val selectedVideo: VideoTrackOption,
    val selectedAudio: AudioTrackOption?,
    val selectedSubtitle: SubtitleTrackOption,
)

data class TrackSelectionCallbacks(
    val onVideoSelected: (VideoTrackOption) -> Unit,
    val onAudioSelected: (AudioTrackOption) -> Unit,
    val onSubtitleSelected: (SubtitleTrackOption) -> Unit,
    val onDismiss: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSelectionSheet(
    state: TrackSelectionState,
    callbacks: TrackSelectionCallbacks,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val tabs =
        buildList {
            if (state.videoOptions.size > 1) add("Quality")
            if (state.audioOptions.size > 1) add("Audio")
            if (state.subtitleOptions.size > 1) add("Subtitles")
        }

    ModalBottomSheet(
        onDismissRequest = callbacks.onDismiss,
        sheetState = sheetState,
        containerColor = SURFACE,
        contentColor = ON_SURFACE,
        dragHandle = null,
    ) {
        Column {
            SheetHandle()
            Text(
                text = "Tracks",
                color = ON_SURFACE,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            SheetTabRow(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
            )
            HorizontalDivider(color = DIVIDER, thickness = 0.5.dp)
            val activeTab = if (tabs.isEmpty()) "" else tabs[selectedTab.coerceIn(0, tabs.lastIndex)]
            TrackSelectionContent(
                state = state,
                tabs = tabs,
                activeTab = activeTab,
                callbacks = callbacks,
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(
                        color = ON_SURFACE_DIM,
                        shape = RoundedCornerShape(percent = 50),
                    ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetTabRow(
    tabs: List<String>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    if (tabs.size > 1) {
        SecondaryTabRow(
            selectedTabIndex = selectedTab.coerceIn(0, tabs.lastIndex),
            containerColor = SURFACE,
            contentColor = ACCENT,
            divider = { HorizontalDivider(color = DIVIDER) },
        ) {
            tabs.forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { onTabSelected(index) },
                    text = {
                        Text(
                            text = label,
                            color = if (selectedTab == index) ACCENT else ON_SURFACE_DIM,
                            fontSize = 14.sp,
                            fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun TrackSelectionContent(
    state: TrackSelectionState,
    tabs: List<String>,
    activeTab: String,
    callbacks: TrackSelectionCallbacks,
) {
    when {
        activeTab == "Quality" || (tabs.size == 1 && state.videoOptions.size > 1) -> {
            TrackOptionList {
                items(state.videoOptions) { option ->
                    VideoTrackRow(
                        option = option,
                        isSelected = option == state.selectedVideo,
                        onClick = {
                            callbacks.onVideoSelected(option)
                            callbacks.onDismiss()
                        },
                    )
                    HorizontalDivider(color = DIVIDER, thickness = 0.5.dp)
                }
            }
        }
        activeTab == "Audio" || (tabs.size == 1 && state.audioOptions.size > 1) -> {
            TrackOptionList {
                items(state.audioOptions) { option ->
                    AudioTrackRow(
                        option = option,
                        isSelected = option == state.selectedAudio,
                        onClick = {
                            callbacks.onAudioSelected(option)
                            callbacks.onDismiss()
                        },
                    )
                    HorizontalDivider(color = DIVIDER, thickness = 0.5.dp)
                }
            }
        }
        activeTab == "Subtitles" || (tabs.size == 1 && state.subtitleOptions.size > 1) -> {
            TrackOptionList {
                items(state.subtitleOptions) { option ->
                    SubtitleTrackRow(
                        option = option,
                        isSelected = option == state.selectedSubtitle,
                        onClick = {
                            callbacks.onSubtitleSelected(option)
                            callbacks.onDismiss()
                        },
                    )
                    HorizontalDivider(color = DIVIDER, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun TrackOptionList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun VideoTrackRow(
    option: VideoTrackOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val (primary, secondary) =
        when (option) {
            is VideoTrackOption.Auto -> "Auto (ABR)" to "Adaptive bitrate — player chooses quality"
            is VideoTrackOption.Fixed -> {
                val height = if (option.height > 0) "${option.height}p" else "?"
                val mbps = if (option.bitrate > 0) "%.1f Mbps".format(option.bitrate / 1_000_000f) else null
                val label = listOfNotNull(height, mbps).joinToString(" · ")
                val codec = option.codecs?.substringBefore(".")?.uppercase()
                label to codec
            }
        }
    TrackRow(primary = primary, secondary = secondary, isSelected = isSelected, onClick = onClick)
}

@Composable
private fun AudioTrackRow(
    option: AudioTrackOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val displayName =
        option.label
            ?: option.language?.uppercase()
            ?: "Unknown"
    val codec = option.codecs?.substringBefore(".")?.uppercase()
    val channels = if (option.channelCount > 0) "${option.channelCount}ch" else null
    val secondary = listOfNotNull(codec, channels).joinToString(" · ").ifBlank { null }
    TrackRow(primary = displayName, secondary = secondary, isSelected = isSelected, onClick = onClick)
}

@Composable
private fun SubtitleTrackRow(
    option: SubtitleTrackOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val (primary, secondary) =
        when (option) {
            is SubtitleTrackOption.Off -> "Off" to "Disable subtitles"
            is SubtitleTrackOption.Fixed -> {
                val name =
                    option.label
                        ?: option.language?.uppercase()
                        ?: "Unknown"
                val mime = option.mimeType?.substringAfterLast("/")?.uppercase()
                name to mime
            }
        }
    TrackRow(primary = primary, secondary = secondary, isSelected = isSelected, onClick = onClick)
}

@Composable
private fun TrackRow(
    primary: String,
    secondary: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                color = if (isSelected) ACCENT else ON_SURFACE,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!secondary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = secondary,
                    color = ON_SURFACE_DIM,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = ACCENT,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
