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
import androidx.compose.material3.HorizontalDivider
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
 * 디버그 빌드에만 들어가는 v3 화면 목록.
 * 프론트가 만든 v3 화면은 아직 앱 흐름에 연결되지 않았으므로, 에뮬레이터에서 모양을 보려고 만든 화면이다.
 * 목록에서 화면을 누르면 그 화면이 시연 데이터로 열리고, 뒤로 가기를 누르면 목록으로 돌아온다.
 */
class V3CatalogActivity : ComponentActivity() {
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
                    LazyColumn(Modifier.fillMaxSize().background(Color.White)) {
                        item {
                            Text(
                                "v3 화면 목록 (디버그용, 시연 데이터)",
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
