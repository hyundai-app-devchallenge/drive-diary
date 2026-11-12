package com.drivediary.talkrec.summary

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

enum class SummaryProviderType { GEMINI, AGY, FAKE }

object SummaryDefaults {
    // Verified in https://ai.google.dev/gemini-api/docs/models on 2026-11-13.
    const val GEMINI_MODEL = "gemini-3.8-flash"
    const val AGY_MODEL = "gemini-3.8-flash-medium"
    const val FAKE_MODEL = "fake-summary-v1"
    const val RELAY_BASE_URL = "http://10.0.2.2:8771"

    fun modelName(provider: SummaryProviderType): String = when (provider) {
        SummaryProviderType.GEMINI -> GEMINI_MODEL
        SummaryProviderType.AGY -> AGY_MODEL
        SummaryProviderType.FAKE -> FAKE_MODEL
    }
}

internal fun checkedModelName(value: String): String {
    if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(value)) {
        throw SummaryException(SummaryErrorCode.INVALID_SETTINGS)
    }
    return value
}

internal fun checkedRelayBase(value: String): String {
    val uri = try { URI(value) } catch (_: Exception) { throw SummaryException(SummaryErrorCode.INVALID_SETTINGS) }
    if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        uri.port !in -1..65535 || uri.port == 0 ||
        !(uri.scheme == "https" || (uri.scheme == "http" && uri.host == "10.0.2.2"))) {
        throw SummaryException(SummaryErrorCode.INVALID_SETTINGS)
    }
    return value.trimEnd('/')
}

class GeminiSummaryProvider(
    private val prompt: SummaryPrompt,
    private val apiKey: String,
    model: String = SummaryDefaults.GEMINI_MODEL,
    private val http: SummaryHttpClient = UrlConnectionSummaryHttpClient(),
) : SummaryProvider {
    private val requestedModel = checkedModelName(model)
    @Volatile override var modelName: String = requestedModel
        private set
    private val validator = SummaryValidator(prompt.tags)

    override suspend fun summarize(transcript: String): SummaryResult {
        if (apiKey.isBlank() || apiKey.any { it <= ' ' || it >= '\u007f' }) {
            throw SummaryException(SummaryErrorCode.KEY_UNAVAILABLE)
        }
        return run {
            val response = http.post(SummaryHttpRequest(
                url = "https://generativelanguage.googleapis.com/v1beta/models/$requestedModel:generateContent",
                headers = mapOf("x-goog-api-key" to apiKey),
                body = requestJson(transcript),
            ))
            checkSummaryHttp(response)
            try {
                val envelope = summaryJsonObject(response.body)
                val candidate = envelope.getJSONArray("candidates").getJSONObject(0)
                val parts = candidate.getJSONObject("content").getJSONArray("parts")
                val text = (0 until parts.length()).map { parts.getJSONObject(it) }
                    .filter { !it.optBoolean("thought", false) }
                    .joinToString("") { it.opt("text") as? String ?: "" }
                val result = validator.parse(text)
                modelName = try { (envelope.opt("modelVersion") as? String)
                    ?.let { checkedModelName(it) } ?: requestedModel }
                    catch (_: SummaryException) { throw InvalidSummaryOutput() }
                result
            } catch (safe: SummaryException) {
                throw safe
            } catch (_: Exception) {
                throw InvalidSummaryOutput()
            }
        }
    }

    internal fun requestJson(transcript: String): String {
        val properties = JSONObject()
            .put("title", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 25))
            .put("tags", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 3)
                .put("items", JSONObject().put("type", "string").put("enum", JSONArray(prompt.tags))))
            .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("story", "moment"))))
        val schema = JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(listOf("title", "tags", "kind")))
        fun textPart(text: String) = JSONArray().put(JSONObject().put("text", text))
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", textPart(prompt.systemInstruction)))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", textPart(transcript))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json")
                .put("responseJsonSchema", schema))
            .toString()
    }
}

/** Debug/demo relay. Release selection is blocked by SummarySettingsStore. */
class AgyBridgeSummaryProvider(
    private val validator: SummaryValidator,
    relayBase: String = SummaryDefaults.RELAY_BASE_URL,
    model: String = SummaryDefaults.AGY_MODEL,
    private val http: SummaryHttpClient = UrlConnectionSummaryHttpClient(),
) : SummaryProvider {
    private val base = checkedRelayBase(relayBase)
    private val requestedModel = checkedModelName(model)
    @Volatile override var modelName: String = requestedModel
        private set

    override suspend fun summarize(transcript: String): SummaryResult {
        val body = JSONObject().put("transcript", transcript).put("model", requestedModel).toString()
        if (body.toByteArray(Charsets.UTF_8).size > 32_768) throw SummaryException(SummaryErrorCode.REQUEST_TOO_LARGE)
        val response = http.post(SummaryHttpRequest("$base/summarize", emptyMap(), body, readTimeoutMs = 120_000))
        if (response.status == 502) {
            val invalid = try { summaryJsonObject(response.body).opt("error") == "invalid_output" } catch (_: Exception) { false }
            // The relay already made the initial model call and its one validation retry.
            if (invalid) throw SummaryException(SummaryErrorCode.INVALID_OUTPUT)
        }
        checkSummaryHttp(response)
        val result = validator.parse(response.body)
        modelName = try { checkedModelName(summaryJsonObject(response.body).opt("model") as? String ?: throw InvalidSummaryOutput()) }
            catch (_: Exception) { throw InvalidSummaryOutput() }
        return result
    }
}

class FakeSummaryProvider(
    private val validator: SummaryValidator,
    override val modelName: String = SummaryDefaults.FAKE_MODEL,
) : SummaryProvider {
    override suspend fun summarize(transcript: String): SummaryResult =
        validator.validate(SummaryResult("테스트 대화", listOf(validator.allowedTags.first()), "story"))
}
