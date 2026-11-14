package com.drivediary.talkrec.ask

import android.content.Context
import com.drivediary.talkrec.summary.InvalidSummaryOutput
import com.drivediary.talkrec.summary.SummaryErrorCode
import com.drivediary.talkrec.summary.SummaryException
import kotlinx.coroutines.CancellationException
import com.drivediary.talkrec.summary.SummaryHttpClient
import com.drivediary.talkrec.summary.SummarySettingsStore
import com.drivediary.talkrec.summary.UrlConnectionSummaryHttpClient
import com.drivediary.talkrec.summary.cloudflareChat
import org.json.JSONArray
import org.json.JSONObject

/** One question ready to show and speak. [source] is the model name, or "fake". */
class GeneratedQuestion(val question: String, val hint: String, val topic: String, val source: String) {
    override fun toString(): String = "GeneratedQuestion(topic=$topic, source=$source)"
}

interface QuestionGenerator {
    val modelName: String
    /** Throws [SummaryException] or [InvalidQuestion]; the caller falls back to the app's question list. */
    suspend fun generate(context: QuestionContext): GeneratedQuestion
}

class InvalidQuestion(val reason: String) : Exception(reason)

/** Checks one model answer (question-suggest DESIGN §7). */
object QuestionValidator {
    const val MAX_QUESTION = 40
    const val MAX_HINT = 20
    val topics = setOf("destination", "weather", "time", "journey", "relation", "free")
    /** Words that would ask the driver to look or touch, or leak where the car is. */
    private val forbidden = listOf("화면", "눌러", "터치", "클릭", "주소", "운전석", "핸들", "휴대폰", "스마트폰", "녹음")

    fun parse(json: String, previous: List<String>, source: String): GeneratedQuestion {
        val obj = try { JSONObject(json) } catch (_: Exception) { throw InvalidQuestion("json") }
        val question = obj.optString("question").trim().replace(Regex("\\s+"), " ")
        if (question.length < 5) throw InvalidQuestion("empty")
        if (question.length > MAX_QUESTION) throw InvalidQuestion("too_long")
        if (!question.endsWith("?") || question.count { it == '?' } != 1) throw InvalidQuestion("not_one_question")
        if (forbidden.any { it in question }) throw InvalidQuestion("forbidden_word")
        val key = normalized(question)
        if (previous.any { normalized(it) == key }) throw InvalidQuestion("repeat")
        val hint = obj.optString("hint").trim().takeIf { it.length <= MAX_HINT && forbidden.none { w -> w in it } }.orEmpty()
        val topic = obj.optString("topic").takeIf { it in topics } ?: "free"
        return GeneratedQuestion(question, hint, topic, source)
    }

    private fun normalized(s: String) = s.replace(Regex("[\\s\\p{P}]+"), "")
}

internal fun questionSchema(): JSONObject = JSONObject().put("type", "object")
    .put("properties", JSONObject()
        .put("question", JSONObject().put("type", "string"))
        .put("hint", JSONObject().put("type", "string"))
        .put("topic", JSONObject().put("type", "string").put("enum", JSONArray(QuestionValidator.topics.toList()))))
    .put("additionalProperties", false)
    .put("required", JSONArray(listOf("question", "hint", "topic")))

/** Workers AI (same model and key as summaries). One re-request on invalid output. */
class CloudflareQuestionGenerator internal constructor(
    private val prompt: String,
    private val accountId: String,
    private val apiToken: String,
    private val model: String,
    private val http: SummaryHttpClient = UrlConnectionSummaryHttpClient(),
) : QuestionGenerator {
    @Volatile override var modelName: String = model
        private set

    override suspend fun generate(context: QuestionContext): GeneratedQuestion {
        var last: InvalidQuestion? = null
        repeat(2) {
            val answer = try {
                cloudflareChat(http, accountId, apiToken, model, prompt, context.toJson(), questionSchema(),
                    maxTokens = 256, temperature = 0.8, readTimeoutMs = 8_000)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: SummaryException) {
                if (failed.code != SummaryErrorCode.INVALID_OUTPUT) throw failed
                last = InvalidQuestion("format"); return@repeat
            } catch (_: InvalidSummaryOutput) { last = InvalidQuestion("format"); return@repeat }
            modelName = answer.model
            try { return QuestionValidator.parse(answer.text, context.previousQuestions, answer.model) }
            catch (invalid: InvalidQuestion) { last = invalid }
        }
        throw last ?: InvalidQuestion("unknown")
    }
}

/** Offline generator for tests and the "Fake" provider. */
class FakeQuestionGenerator : QuestionGenerator {
    override val modelName = "fake-question-v1"
    override suspend fun generate(context: QuestionContext): GeneratedQuestion {
        val place = context.destination?.takeIf { it.isNotBlank() }
        val text = if (place != null) "${place}에 도착하면 제일 먼저 뭐 하고 싶어?" else "오늘 여행에서 제일 기대되는 건 뭐야?"
        return QuestionValidator.parse(JSONObject().put("question", text).put("hint", "").put("topic", "destination").toString(),
            context.previousQuestions, modelName)
    }
}

object QuestionGenerators {
    /**
     * Follows the summary provider setting: Cloudflare when its key is set, Fake when Fake is chosen,
     * otherwise null (the app then uses its own question list).
     */
    suspend fun create(context: Context, settings: SummarySettingsStore): QuestionGenerator? {
        if (settings.usesFakeProvider()) return FakeQuestionGenerator()
        val credentials = settings.cloudflareCredentials() ?: return null
        val prompt = context.applicationContext.assets.open("prompts/question.md").use { it.readBytes().toString(Charsets.UTF_8) }
        return CloudflareQuestionGenerator(prompt, credentials.accountId, credentials.apiToken, credentials.model)
    }
}
