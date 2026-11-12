package com.drivediary.talkrec.judge

import java.io.InputStream
import org.json.JSONObject

/** Own one instance per capture lifecycle; inference and close are serialized. */
class ConversationJudge internal constructor(
    private val tokenizer: WordPieceTokenizer,
    val threshold: Double,
    private val model: ConversationModel,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    init {
        require(threshold.isFinite() && threshold in 0.0..1.0) { "Invalid M1 threshold" }
    }

    fun judge(lines: List<String>): ConversationJudgment = judgeText(ModelInputText.fromLines(lines))

    /** For already prepared model input; callers with block lines should use judge. */
    fun judgeText(inputText: String): ConversationJudgment = synchronized(lock) {
        check(!closed) { "ConversationJudge is closed" }
        val scores = model.run(tokenizer.encode(inputText))
        check(scores.keepProbability.isFinite() && scores.keepProbability in 0.0..1.0) {
            "Invalid M1 keep probability"
        }
        check(scores.kindLogits.size == 3 && scores.kindLogits.all { it.isFinite() }) {
            "Invalid M1 kind logits"
        }
        // Auxiliary order is none/story/moment. It does not control the keep head.
        var best = 0
        for (i in 1..2) if (scores.kindLogits[i] > scores.kindLogits[best]) best = i
        ConversationJudgment(
            keepProbability = scores.keepProbability,
            keep = scores.keepProbability >= threshold,
            kind = when (best) {
                1 -> ConversationKind.STORY
                2 -> ConversationKind.MOMENT
                else -> null
            },
        )
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            model.close()
        }
    }

    companion object {
        /** Android: ConversationJudge.fromAssets { context.assets.open(it) }.
         * A stream opener keeps Android APIs out of host JVM model tests.
         * Loading failures propagate so the capture coordinator can apply its fallback.
         */
        fun fromAssets(openAsset: (String) -> InputStream): ConversationJudge {
            val tokenizer = openAsset("m1/vocab.txt").bufferedReader(Charsets.UTF_8).use {
                WordPieceTokenizer.fromVocab(it)
            }
            val threshold = openAsset("m1/threshold.json").bufferedReader(Charsets.UTF_8).use {
                JSONObject(it.readText()).getDouble("threshold")
            }
            require(threshold.isFinite() && threshold in 0.0..1.0) { "Invalid M1 threshold" }
            val bytes = openAsset("m1/model.int8.onnx").use { it.readBytes() }
            return ConversationJudge(tokenizer, threshold, OrtConversationModel.create(bytes))
        }
    }
}

enum class ConversationKind { STORY, MOMENT }

data class ConversationJudgment(
    val keepProbability: Double,
    val keep: Boolean,
    /** Null when the independent auxiliary head's argmax is none, even for keep=true. */
    val kind: ConversationKind?,
)

/** All ORT environment/session details stay behind this small inference seam. */
internal interface ConversationModel : AutoCloseable {
    fun run(input: TokenizedInput): ModelScores
}

internal class ModelScores(val keepProbability: Double, val kindLogits: FloatArray)
