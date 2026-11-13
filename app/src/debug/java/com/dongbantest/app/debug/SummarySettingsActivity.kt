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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme
import com.drivediary.talkrec.summary.ConnectionTestResult
import com.drivediary.talkrec.summary.AgySummaryDefaults
import com.drivediary.talkrec.summary.relayBaseUrl
import com.drivediary.talkrec.summary.setRelayBaseUrl
import com.drivediary.talkrec.summary.SummaryDefaults
import com.drivediary.talkrec.summary.SummaryException
import com.drivediary.talkrec.summary.SummaryProviderType
import com.drivediary.talkrec.summary.SummarySettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * talk-rec 연동(I5b): 대화 요약 설정 화면. 디버그 빌드에만 들어간다(src/debug 매니페스트).
 * 키·모델·중계 주소는 여기서 정하고, 정차 중에만 바꿀 수 있다(팀 앱의 [DrivingSafetyMonitor] 신호 사용).
 * 토큰은 비밀번호 입력칸과 저장된 끝 4자리로만 표시하며 저장 요청 시 입력칸을 비운다.
 */
class SummarySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DongbanApplication).container
        setContent {
            DongbanchaV3Theme {
                DebugToolsLayout(container) { restricted ->
                    SummarySettingsScreen(container.summarySettingsStore, restricted)
                }
            }
        }
    }
}

/** 오류 코드 → 짧은 한글 문구. 원문 응답·예외 메시지는 쓰지 않는다. */
private fun errorText(code: String?, context: String = ""): String = when (code) {
    "invalid_settings" -> when (context) {
        "model" -> "모델 ID는 영문·숫자와 @ / . _ - 만 쓸 수 있어요 (192자 이하)"
        "relay" -> "주소는 https:// 이거나 http://10.0.2.2:포트 여야 해요"
        "account" -> "계정 ID는 32자리 16진수(0~9, a~f)여야 해요"
        "key" -> "토큰에 공백이나 한글이 들어 있어요"
        else -> "설정값이 올바르지 않아요"
    }
    "invalid_output" -> "응답 형식이 올바르지 않아요"
    "key_unavailable" -> "계정 ID와 API 토큰을 저장해 주세요. 저장된 토큰을 읽을 수 없으면 다시 저장해 주세요"
    "auth" -> "계정 ID·토큰과 Workers AI 권한을 확인해 주세요 (인증 실패)"
    "network" -> "네트워크에 연결할 수 없어요"
    "timeout" -> "응답이 너무 늦어요 (시간 초과)"
    "rate_limit" -> "요청이 너무 많아요. 잠시 뒤에 다시 해 보세요"
    "server_error" -> "서버 오류예요"
    "http_error" -> "요청을 처리하지 못했어요 (HTTP 오류)"
    "request_too_large" -> "요청이 너무 커요"
    "storage" -> "저장하지 못했어요"
    "connection_error" -> "연결 시험 중 예기치 않은 오류가 났어요"
    else -> "알 수 없는 오류가 났어요"
}

private fun providerLabel(p: SummaryProviderType) = when (p) {
    SummaryProviderType.CLOUDFLARE -> "Cloudflare"
    SummaryProviderType.AGY -> "agy 중계"
    SummaryProviderType.FAKE -> "Fake"
}

@Composable
private fun SummarySettingsScreen(store: SummarySettingsStore, restricted: Boolean) {
    val settings by store.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var modelText by remember(settings.provider, settings.modelName) { mutableStateOf(settings.modelName) }
    var relayText by remember { mutableStateOf(store.relayBaseUrl()) }
    var accountText by remember(settings.cloudflareAccountId) { mutableStateOf(settings.cloudflareAccountId) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var keyText by remember { mutableStateOf("") }
    var last4 by remember { mutableStateOf<String?>(null) }
    var keyChecked by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var relayError by remember { mutableStateOf<String?>(null) }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    var keyIsError by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }

    val enabled = !restricted && !testing

    // 주행이 시작되면 입력 중이던 키를 바로 지운다.
    LaunchedEffect(restricted) { if (restricted) keyText = "" }

    suspend fun refreshLast4() {
        last4 = try { store.cloudflareTokenLast4() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        keyChecked = true
    }
    LaunchedEffect(settings.provider) {
        keyText = ""
        testResult = null
        if (settings.provider == SummaryProviderType.CLOUDFLARE) refreshLast4()
    }

    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("대화 요약 설정", style = MaterialTheme.typography.titleLarge)
        Text(
            "외부 AI 전송 동의는 여행 설정을 따라요. 제공자·요금제의 데이터 처리 정책을 확인해 주세요. agy 중계는 debug 실험용이에요.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (restricted) {
            Text("정차 중에만 바꿀 수 있어요", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider()

        // 제공자
        Text("제공자", style = MaterialTheme.typography.titleMedium)
        SummaryProviderType.entries.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.provider == p, enabled = enabled, onClick = {
                    scope.launch {
                        try { store.setProvider(p); modelError = null } catch (e: CancellationException) { throw e }
                        catch (e: SummaryException) { modelError = errorText(e.code.name.lowercase()) }
                    }
                })
                Text(providerLabel(p), style = MaterialTheme.typography.bodyLarge)
            }
        }

        // 모델 이름
        OutlinedTextField(
            value = modelText, onValueChange = { modelText = it; modelError = null },
            label = { Text("모델 이름") }, singleLine = true, enabled = enabled,
            isError = modelError != null, supportingText = modelError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = enabled, onClick = {
                scope.launch {
                    try { store.setModelName(modelText); modelError = null } catch (e: CancellationException) { throw e }
                    catch (e: SummaryException) { modelError = errorText(e.code.name.lowercase(), "model") }
                }
            }) { Text("모델 저장") }
            OutlinedButton(enabled = enabled, onClick = {
                scope.launch {
                    try { store.setModelName(""); modelError = null } catch (e: CancellationException) { throw e }
                    catch (e: SummaryException) { modelError = errorText(e.code.name.lowercase()) }
                }
            }) { Text("기본값으로") }
        }
        Text("기본값: ${SummaryDefaults.modelName(settings.provider)}", style = MaterialTheme.typography.bodySmall)

        // Cloudflare 계정 및 암호화 API 토큰
        if (settings.provider == SummaryProviderType.CLOUDFLARE) {
            HorizontalDivider()
            Text("Cloudflare Workers AI", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = accountText, onValueChange = { accountText = it; accountError = null },
                label = { Text("Cloudflare 계정 ID") }, singleLine = true, enabled = enabled,
                isError = accountError != null, supportingText = accountError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = enabled, onClick = {
                scope.launch {
                    try { store.setCloudflareAccountId(accountText); accountError = null }
                    catch (e: CancellationException) { throw e }
                    catch (e: SummaryException) { accountError = errorText(e.code.name.lowercase(), "account") }
                }
            }) { Text("계정 ID 저장") }
            Text("API 토큰", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !keyChecked -> "확인 중…"
                    last4 != null -> "끝 4자리: ••••$last4"
                    else -> "저장된 토큰 없음"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = keyText, onValueChange = { keyText = it; keyMessage = null },
                label = { Text("새 API 토큰") }, singleLine = true, enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            keyMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (keyIsError) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = enabled && keyText.isNotBlank(), onClick = {
                    val value = keyText
                    keyText = "" // 저장 요청과 함께 입력칸을 바로 비운다
                    scope.launch {
                        try {
                            store.setCloudflareToken(value); refreshLast4()
                            keyMessage = "저장했어요"; keyIsError = false
                        } catch (e: CancellationException) { throw e }
                        catch (e: SummaryException) { keyMessage = errorText(e.code.name.lowercase(), "key"); keyIsError = true }
                    }
                }) { Text("토큰 저장") }
                OutlinedButton(enabled = enabled, onClick = {
                    scope.launch {
                        try {
                            store.setCloudflareToken(""); keyText = ""; refreshLast4()
                            keyMessage = "지웠어요"; keyIsError = false
                        } catch (e: CancellationException) { throw e }
                        catch (e: SummaryException) { keyMessage = errorText(e.code.name.lowercase()); keyIsError = true }
                    }
                }) { Text("토큰 지우기") }
            }
        }

        // 중계 서버 주소 (agy 만)
        if (settings.provider == SummaryProviderType.AGY) {
            HorizontalDivider()
            OutlinedTextField(
                value = relayText, onValueChange = { relayText = it; relayError = null },
                label = { Text("중계 서버 주소") }, singleLine = true, enabled = enabled,
                isError = relayError != null, supportingText = { Text(relayError ?: "기본값 ${AgySummaryDefaults.RELAY_BASE_URL}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = enabled, onClick = {
                    scope.launch {
                        try { store.setRelayBaseUrl(relayText); relayText = store.relayBaseUrl(); relayError = null } catch (e: CancellationException) { throw e }
                        catch (e: SummaryException) { relayError = errorText(e.code.name.lowercase(), "relay") }
                    }
                }) { Text("주소 저장") }
                OutlinedButton(enabled = enabled, onClick = {
                    scope.launch {
                        try { store.setRelayBaseUrl(AgySummaryDefaults.RELAY_BASE_URL); relayText = store.relayBaseUrl(); relayError = null } catch (e: CancellationException) { throw e }
                        catch (e: SummaryException) { relayError = errorText(e.code.name.lowercase(), "relay") }
                    }
                }) { Text("기본값으로") }
            }
        }

        HorizontalDivider()

        // 주행 중 제목 보이기
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("주행 중 제목 보이기", style = MaterialTheme.typography.titleMedium)
                Text("끄면 주행 중 여행 화면에서 대화 제목·태그를 숨겨요", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.showTitlesWhileDriving, enabled = enabled, onCheckedChange = { on ->
                scope.launch { try { store.setShowTitlesWhileDriving(on) } catch (e: CancellationException) { throw e } catch (_: SummaryException) {} }
            })
        }

        HorizontalDivider()

        // 연결 시험
        Button(enabled = enabled, onClick = {
            testing = true
            testResult = null
            scope.launch {
                try { testResult = store.testConnection() } finally { testing = false }
            }
        }) { Text("연결 시험") }
        if (testing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text("시험 중…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        testResult?.let { r ->
            if (r.ok) {
                Text("성공 (${r.elapsedMs}ms)", style = MaterialTheme.typography.titleMedium)
                r.modelName?.let { Text("모델: $it", style = MaterialTheme.typography.bodyMedium) }
                r.result?.let {
                    Text("제목: ${it.title}", style = MaterialTheme.typography.bodyMedium)
                    Text("태그: ${it.tags.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                    Text("종류: ${it.kind}", style = MaterialTheme.typography.bodyMedium)
                }
                Text("(지어낸 두 줄 대화로 시험한 결과예요)", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("실패 (${r.elapsedMs}ms)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(errorText(r.errorCode), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
