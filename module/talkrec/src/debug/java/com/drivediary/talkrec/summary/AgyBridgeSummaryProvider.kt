package com.drivediary.talkrec.summary

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Lab/testing only; excluded from release. */
object AgySummaryDefaults {
    const val MODEL = "gemini-3.8-flash-medium"
    const val RELAY_BASE_URL = "http://10.0.2.2:8771"
}

internal fun checkedRelayModelName(value: String): String {
    if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(value))
        throw SummaryException(SummaryErrorCode.INVALID_SETTINGS)
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

class AgyBridgeSummaryProvider(
    private val validator: SummaryValidator,
    relayBase: String = AgySummaryDefaults.RELAY_BASE_URL,
    model: String = AgySummaryDefaults.MODEL,
    private val http: SummaryHttpClient = UrlConnectionSummaryHttpClient(),
) : SummaryProvider {
    private val base = checkedRelayBase(relayBase)
    private val requestedModel = checkedRelayModelName(model)
    @Volatile override var modelName: String = requestedModel
        private set

    override suspend fun storySplit(segments: List<String>): StorySplitResult {
        checkedSegments(segments)
        val body = JSONObject().put("segments", JSONArray(segments)).put("model", requestedModel).toString()
        if (body.toByteArray(Charsets.UTF_8).size > 262_144) throw SummaryException(SummaryErrorCode.REQUEST_TOO_LARGE)
        val response = http.post(SummaryHttpRequest("$base/story-split", emptyMap(), body, readTimeoutMs = AGY_READ_TIMEOUT_MS))
        if (response.status == 502 && try { summaryJsonObject(response.body).opt("error") == "invalid_output" } catch (_: Exception) { false })
            throw SummaryException(SummaryErrorCode.INVALID_OUTPUT)
        checkSummaryHttp(response)
        try {
            val envelope = summaryJsonObject(response.body)
            val stories = envelope.getJSONArray("stories")
            // Relay adds compatibility tags and envelope metadata after its strict validation.
            for (i in 0 until stories.length()) stories.getJSONObject(i).remove("tags")
            val result = StorySplitValidator(validator.allowedCategories)
                .parse(JSONObject().put("stories", stories).toString(), segments.size)
            modelName = checkedRelayModelName(envelope.get("model") as? String ?: throw InvalidSummaryOutput())
            return result
        } catch (_: Exception) { throw InvalidSummaryOutput() }
    }

    override suspend fun summarize(transcript: String): SummaryResult {
        val body = JSONObject().put("transcript", transcript).put("model", requestedModel).toString()
        if (body.toByteArray(Charsets.UTF_8).size > 32_768) throw SummaryException(SummaryErrorCode.REQUEST_TOO_LARGE)
        val response = http.post(SummaryHttpRequest("$base/summarize", emptyMap(), body, readTimeoutMs = AGY_READ_TIMEOUT_MS))
        if (response.status == 502) {
            val invalid = try { summaryJsonObject(response.body).opt("error") == "invalid_output" } catch (_: Exception) { false }
            // The relay already made the initial model call and its one validation retry.
            if (invalid) throw SummaryException(SummaryErrorCode.INVALID_OUTPUT)
        }
        checkSummaryHttp(response)
        // Ignore the relay's compatibility tags; derive them from validated category + keywords.
        val result = validator.parse(response.body)
        modelName = try { checkedRelayModelName(summaryJsonObject(response.body).opt("model") as? String ?: throw InvalidSummaryOutput()) }
            catch (_: Exception) { throw InvalidSummaryOutput() }
        return result
    }
}

private const val AGY_READ_TIMEOUT_MS = 360_000
