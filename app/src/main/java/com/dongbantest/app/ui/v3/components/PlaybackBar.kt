package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.dongbantest.app.ui.components.formatDuration
import com.dongbantest.app.ui.v3.theme.DbTheme
import kotlin.math.roundToLong

data class PlaybackUi(
    val id: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

@Composable
fun PlaybackBar(playback: PlaybackUi, onSeek: (Long) -> Unit) {
    if (playback.durationMs <= 0) return
    val colors = DbTheme.colors
    val duration = playback.durationMs
    var scrubPosition by remember(playback.id, duration) { mutableStateOf<Float?>(null) }
    val position = scrubPosition?.let { (it * duration.toDouble()).roundToLong() } ?: playback.positionMs
    val time = "${formatDuration(position)} / ${formatDuration(duration)}"
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = (position.toDouble() / duration).toFloat().coerceIn(0f, 1f),
            onValueChange = { scrubPosition = it },
            onValueChangeFinished = {
                scrubPosition?.let { onSeek((it * duration.toDouble()).roundToLong()) }
                scrubPosition = null
            },
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.rule,
            ),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "재생 위치"
                stateDescription = time
            },
        )
        Text(time, style = DbTheme.type.caption, color = colors.ink2)
    }
}
