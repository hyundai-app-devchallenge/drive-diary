package com.dongbantest.app.debug

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

private const val GLEO_VOICE_BUTTON_PORT = 43192
private const val BRIDGE_TIMEOUT_MS = 5_000

internal suspend fun pressGleoVoiceButton(context: Context): String = withContext(Dispatchers.IO) {
    try {
        val tokenFile = File(context.filesDir, "gleo-voice-button.token")
        if (!tokenFile.isFile) {
            return@withContext "PC에서 gleo-voice-button.py를 실행해 주세요."
        }
        val token = tokenFile.readText().trim()
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", GLEO_VOICE_BUTTON_PORT), BRIDGE_TIMEOUT_MS)
            socket.soTimeout = BRIDGE_TIMEOUT_MS
            socket.getOutputStream().write("$token\n".toByteArray(Charsets.US_ASCII))
            if (socket.getInputStream().read() == '1'.code) {
                "차량 음성 버튼 입력을 보냈어요. Gleo에 말해 보세요."
            } else {
                "차량 음성 버튼 입력에 실패했어요. PC의 ADB 연결을 확인해 주세요."
            }
        }
    } catch (_: IOException) {
        "PC 연결을 확인할 수 없어요. gleo-voice-button.py가 실행 중인지 확인해 주세요."
    }
}
