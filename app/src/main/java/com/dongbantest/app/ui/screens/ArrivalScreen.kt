package com.dongbantest.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dongbantest.app.domain.model.MemoryType
import com.dongbantest.app.ui.components.BigActionButton
import com.dongbantest.app.ui.components.DiaryBackground
import com.dongbantest.app.ui.components.PaperCard
import com.dongbantest.app.ui.components.SoftButton
import com.dongbantest.app.ui.components.formatDuration
import com.dongbantest.app.ui.components.formatKm
import com.dongbantest.app.ui.theme.Cocoa
import com.dongbantest.app.ui.theme.CocoaSoft
import com.dongbantest.app.ui.theme.DongbantestTheme
import com.dongbantest.app.viewmodel.TripUiState

/**
 * S11 도착 · 여행 종료 (PRD v3.0).
 * 목적지 도착으로 여행이 자동 종료된 뒤 보여준다. 도착이 아니면 "아직 여행 중이에요"로 되돌린다.
 */
@Composable
fun ArrivalScreen(
    uiState: TripUiState,
    destinationName: String,
    onConfirm: () -> Unit,
    onKeepTraveling: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = uiState.data
    val place = destinationName.ifBlank { data?.trip?.destination.orEmpty().ifBlank { "목적지" } }
    val voices = data?.memories?.filter { it.type == MemoryType.VOICE }.orEmpty()
    val voiceMs = voices.sumOf { it.durationMs ?: 0L }

    DiaryBackground(modifier) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            PaperCard(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text("📍 ${place}에 도착했어요", style = MaterialTheme.typography.headlineMedium, color = Cocoa)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (voices.isNotEmpty()) {
                        Text(
                            "🎙️ 목소리 ${voices.size}개 저장됨 · ${formatDuration(voiceMs)}",
                            style = MaterialTheme.typography.titleMedium, color = Cocoa,
                        )
                    }
                    data?.drive?.takeIf { it.pointCount > 0 }?.let {
                        Text("🛣️ 함께 달린 길 ${formatKm(it.distanceMeters)} km", style = MaterialTheme.typography.titleMedium, color = Cocoa)
                    }
                }
                Text(
                    "여행을 마쳤어요. 오늘 함께한 길과 이야기를 다이어리로 정리할게요.",
                    style = MaterialTheme.typography.bodyLarge, color = CocoaSoft,
                )
                BigActionButton("알겠어요", "📖", onClick = onConfirm, modifier = Modifier.fillMaxWidth())
                SoftButton("아직 여행 중이에요", onClick = onKeepTraveling, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun ArrivalScreenPreview() {
    DongbantestTheme {
        ArrivalScreen(TripUiState(data = previewTripWithMemories), "속초 영금정", {}, {})
    }
}
