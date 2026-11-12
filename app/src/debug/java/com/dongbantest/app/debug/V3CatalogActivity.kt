package com.dongbantest.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.dongbantest.app.DongbanApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.screens.ScreenCatalog
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme

/**
 * 디버그 빌드에만 들어가는 개발 도구 화면 (런처 이름 "동반차 디버그").
 * 맨 위: 실제 도로 모양의 예시 여행 넣기·지우기 (경로 목록은 [DebugGpxImporter.sampleRoutes]).
 * 아래: v3 화면 목록. 누르면 그 화면이 시연 데이터로 열리고, 뒤로 가기를 누르면 목록으로 돌아온다.
 */
class V3CatalogActivity : ComponentActivity() {
    private val repo get() = (application as DongbanApplication).container.tripRepository
    private val importer by lazy { DebugGpxImporter(application, repo) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DongbanchaV3Theme {
                var openId by rememberSaveable { mutableStateOf<String?>(null) }
                val open = ScreenCatalog.firstOrNull { it.id == openId }
                if (open != null) {
                    BackHandler { openId = null }
                    Box(Modifier.fillMaxSize().background(Color.White)) { open.content() }
                } else {
                    val scope = rememberCoroutineScope()
                    var status by remember { mutableStateOf<String?>(null) }
                    var busy by remember { mutableStateOf(false) }
                    LazyColumn(Modifier.fillMaxSize().background(Color.White)) {
                        item {
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
                            HorizontalDivider()
                            Text(
                                "v3 화면 목록 (시연 데이터)",
                                Modifier.padding(24.dp),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        items(ScreenCatalog) { entry ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { openId = entry.id }
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                            ) {
                                Text("${entry.id}  ${entry.name}", style = MaterialTheme.typography.titleMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
