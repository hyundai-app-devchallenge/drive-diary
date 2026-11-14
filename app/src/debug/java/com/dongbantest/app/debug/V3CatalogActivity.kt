package com.dongbantest.app.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 디버그 빌드에만 들어가는 개발 도구 화면 (런처 이름 "동반차 디버그").
 * 요약 설정, 인식 실험실, 예시 여행 넣기·지우기 (경로 목록은 [DebugGpxImporter.sampleRoutes]).
 */
class V3CatalogActivity : ComponentActivity() {
    private val container get() = (application as DongbanApplication).container
    private val repo get() = container.tripRepository
    private val importer by lazy { DebugGpxImporter(application, repo) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DongbanchaV3Theme {
                DebugToolsLayout(container) { CatalogContent() }
            }
        }
    }

    @Composable
    private fun CatalogContent() {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        LazyColumn(Modifier.fillMaxSize().background(Color.White)) {
            item {
                // talk-rec 연동(I5b): 대화 요약 설정 화면으로 가는 버튼
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                    Text("대화 요약 설정", style = MaterialTheme.typography.titleLarge)
                    Text("제공자·모델·API 키·연결 시험 (정차 중에만 변경)", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { startActivity(Intent(this@V3CatalogActivity, SummarySettingsActivity::class.java)) }, modifier = Modifier.padding(top = 8.dp)) { Text("대화 요약 설정 열기") }
                }
                HorizontalDivider(Modifier.padding(top = 16.dp))
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("인식 실험실", style = MaterialTheme.typography.titleLarge)
                    Text("PC 마이크·음성 파일의 인식, 묶음 판정과 제목을 시험해요", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { startActivity(Intent(this@V3CatalogActivity, TalkRecLabActivity::class.java)) }) {
                        Text("인식 실험실 열기")
                    }
                }
                HorizontalDivider()
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("질문 실험실", style = MaterialTheme.typography.titleLarge)
                    Text("지금 위치로 질문 만들기, Pleos TTS 읽기, 조용함 흉내·반응으로 질문 흐름 시험", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { startActivity(Intent(this@V3CatalogActivity, QuestionLabActivity::class.java)) }) {
                        Text("질문 실험실 열기")
                    }
                }
                HorizontalDivider()
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("예시 여행 (실제 도로 경로)", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "서울시립대학교에서 출발해 전국 13곳(부산, 대구, 경주, 여수, 전주, 강릉, 속초 등)으로 가는 여행을 기록에 넣어요. " +
                            "경로: © OpenStreetMap contributors, OSRM",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !busy, onClick = {
                            busy = true
                            status = "넣는 중… (몇 초 걸려요)"
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { importer.importSampleRoutes() }
                                status = if (n > 0) "예시 여행 ${n}개를 넣었어요. 동반차 앱의 기록 탭에서 확인하세요." else "이미 들어 있어요."
                                busy = false
                            }
                        }) { Text("예시 여행 넣기") }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                withContext(Dispatchers.IO) { repo.clearSampleTrips() }
                                status = "예시 여행을 모두 지웠어요."
                                busy = false
                            }
                        }) { Text("예시 여행 지우기") }
                    }
                    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
