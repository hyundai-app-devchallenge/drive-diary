package com.dongbantest.app.data.map

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dongbantest.app.R
import com.mapbox.common.MapboxOptions
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 지금 지도에 쓰는 토큰의 출처. 직접 넣은 토큰이 빌드 토큰(local.properties)보다 우선한다. */
enum class MapboxTokenSource { MANUAL, BUILD, NONE }

enum class MapboxTokenError { INVALID, SECRET_TOKEN, STORAGE }

/** 메시지에 토큰 값을 절대 넣지 않는다. */
class MapboxTokenException(val error: MapboxTokenError) : Exception(error.name)

/** 순수 검증. 통과하면 null, 아니면 오류. 공개 토큰(pk.)만 허용한다. */
internal fun validateMapboxToken(value: String): MapboxTokenError? = when {
    value.startsWith("sk.") -> MapboxTokenError.SECRET_TOKEN
    !value.startsWith("pk.") -> MapboxTokenError.INVALID
    value.length < MIN_LENGTH || value.length > MAX_LENGTH -> MapboxTokenError.INVALID
    value.any { it <= ' ' || it >= '\u007f' } -> MapboxTokenError.INVALID
    else -> null
}

private const val MIN_LENGTH = 10
private const val MAX_LENGTH = 512

/**
 * Mapbox 공개 토큰 저장소. 직접 넣은 토큰은 AndroidKeyStore 키로 암호화해 저장하고
 * (SummarySettingsStore 의 Cloudflare 토큰과 같은 방식), 복원돼 키가 없으면 쓰지 않는다.
 * 유효 토큰이 바뀔 때마다 [MapboxOptions.accessToken] 에 반영한다. 로그·예외에 토큰을 남기지 않는다.
 */
class MapboxTokenStore internal constructor(
    private val prefs: SharedPreferences,
    private val buildToken: String,
    private val applyToken: (String) -> Unit = { MapboxOptions.accessToken = it },
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("dongban_mapbox", Context.MODE_PRIVATE),
        context.getString(R.string.mapbox_access_token).trim(),
    )

    private val mutex = Mutex()
    private val sourceState = MutableStateFlow(MapboxTokenSource.NONE)
    private val revisionState = MutableStateFlow(0)

    /** MANUAL / BUILD / NONE. */
    val source: StateFlow<MapboxTokenSource> = sourceState.asStateFlow()

    /** 유효 토큰이 바뀔 때마다 늘어난다. 지도를 다시 만들 때 key 로 쓴다. */
    val revision: StateFlow<Int> = revisionState.asStateFlow()

    init { refresh() }

    /** 빈 값이면 직접 넣은 토큰을 지운다. 실패하면 [MapboxTokenException]. */
    suspend fun setToken(value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val token = value.trim()
            if (token.isNotEmpty()) validateMapboxToken(token)?.let { throw MapboxTokenException(it) }
            try {
                val editor = prefs.edit()
                if (token.isEmpty()) {
                    editor.remove(K_CIPHER).remove(K_IV)
                } else {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
                    cipher.updateAAD(AAD)
                    editor.putString(K_CIPHER, Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
                    editor.putString(K_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                }
                if (!editor.commit()) throw MapboxTokenException(MapboxTokenError.STORAGE)
            } catch (safe: MapboxTokenException) {
                throw safe
            } catch (_: Exception) {
                throw MapboxTokenException(MapboxTokenError.STORAGE)
            }
            refresh()
        }
    }

    /** 직접 넣은 토큰의 끝 4자리. 없거나 읽을 수 없으면 null. */
    suspend fun tokenLast4(): String? = withContext(Dispatchers.IO) {
        mutex.withLock { readManual()?.takeLast(4) }
    }

    private fun effective(): Pair<MapboxTokenSource, String> {
        readManual()?.let { return MapboxTokenSource.MANUAL to it }
        if (buildToken.isNotBlank()) return MapboxTokenSource.BUILD to buildToken
        return MapboxTokenSource.NONE to ""
    }

    private fun refresh() {
        val (src, token) = effective()
        // 빈 값도 넣어 이전 토큰이 남지 않게 한다.
        applyToken(token)
        sourceState.value = src
        revisionState.value = revisionState.value + 1
    }

    /** 복호화에 실패하면(키 없음 등) 쓰지 않는다. */
    private fun readManual(): String? {
        val ciphertext = prefs.getString(K_CIPHER, null) ?: return null
        val iv = prefs.getString(K_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(create = false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.updateAAD(AAD)
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
                .takeIf { validateMapboxToken(it) == null }
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(create: Boolean = true): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw IllegalStateException("key")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "dongban.mapbox.token.v1"
        const val K_CIPHER = "token_ciphertext"
        const val K_IV = "token_iv"
        val AAD = "dongban_mapbox/token/v1".toByteArray(Charsets.UTF_8)
    }
}
