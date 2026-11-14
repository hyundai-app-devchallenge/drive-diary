package com.dongbantest.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.data.map.MapboxTokenError
import com.dongbantest.app.data.map.MapboxTokenException
import com.dongbantest.app.data.map.MapboxTokenSource
import com.dongbantest.app.data.map.MapboxTokenStore
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 지도 설정: Mapbox 공개 토큰(pk.)을 빌드 없이 넣는다. 디버그 빌드에만 들어간다.
 * 대화 요약 설정의 Cloudflare 토큰과 같은 방식: 비밀번호 입력칸, 끝 4자리 표시, 저장 요청 시 입력칸 비움, 정차 중에만 변경.
 * 직접 넣은 토큰이 local.properties 토큰보다 앞서고, 저장하면 지도에 바로 적용된다.
 */
class MapSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DongbanApplication).container
        setContent {
            DongbanchaV3Theme {
                DebugToolsLayout(container) { restricted ->
                    MapSettingsScreen(container.mapboxTokenStore, restricted)
                }
            }
        }
    }
}

private fun errorText(error: MapboxTokenError): String = when (error) {
    MapboxTokenError.INVALID -> "Mapbox 공개 토큰 형식이 아니에요 (pk. 로 시작, 공백·한글 없이)"
    MapboxTokenError.SECRET_TOKEN -> "sk. 비밀 토큰은 쓸 수 없어요. pk. 공개 토큰을 넣어 주세요"
    MapboxTokenError.STORAGE -> "저장하지 못했어요"
}

private fun sourceText(source: MapboxTokenSource, last4: String?): String = when (source) {
    MapboxTokenSource.MANUAL -> "직접 넣은 토큰을 쓰는 중" + (last4?.let { " (끝 4자리 ••••$it)" } ?: "")
    MapboxTokenSource.BUILD -> "local.properties 토큰을 쓰는 중"
    MapboxTokenSource.NONE -> "토큰 없음: 지도가 보이지 않아요"
}

@Composable
private fun MapSettingsScreen(store: MapboxTokenStore, restricted: Boolean) {
    val source by store.source.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var keyText by remember { mutableStateOf("") }
    var last4 by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val enabled = !restricted

    // 주행이 시작되면 입력 중이던 토큰을 바로 지운다.
    LaunchedEffect(restricted) { if (restricted) keyText = "" }
    LaunchedEffect(source) {
        last4 = try { store.tokenLast4() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    fun save(value: String, done: String) {
        scope.launch {
            try {
                store.setToken(value)
                message = done; isError = false
            } catch (e: CancellationException) { throw e }
            catch (e: MapboxTokenException) { message = errorText(e.error); isError = true }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("지도 설정", style = MaterialTheme.typography.titleLarge)
        Text(
            "기록 지도에 쓰는 Mapbox 공개 토큰(pk.)이에요. 여기 넣은 토큰이 local.properties 보다 앞서고, 저장하면 바로 적용돼요.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (restricted) {
            Text("정차 중에만 바꿀 수 있어요", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider()

        Text("Mapbox 토큰", style = MaterialTheme.typography.titleMedium)
        Text(sourceText(source, last4), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = keyText, onValueChange = { keyText = it; message = null },
            label = { Text("새 공개 토큰 (pk.…)") }, singleLine = true, enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = enabled && keyText.isNotBlank(), onClick = {
                val value = keyText
                keyText = "" // 저장 요청과 함께 입력칸을 바로 비운다
                save(value, "저장했어요")
            }) { Text("토큰 저장") }
            OutlinedButton(enabled = enabled && source == MapboxTokenSource.MANUAL, onClick = {
                keyText = ""
                save("", "지웠어요")
            }) { Text("직접 넣은 토큰 지우기") }
        }
        Text(
            "지우면 local.properties 의 MAPBOX_ACCESS_TOKEN 으로 돌아가요. Mapbox 계정의 Access tokens 에서 공개 토큰을 복사해 넣으세요.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
