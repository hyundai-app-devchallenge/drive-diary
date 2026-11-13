package com.drivediary.talkrec.judge

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.LongBuffer

/** CPU execution matches T58's sequential, one-thread configuration. */
internal class OrtConversationModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : ConversationModel {
    override fun run(input: TokenizedInput): ModelScores {
        require(input.inputIds.size == 256 && input.attentionMask.size == 256) { "Invalid M1 input shape" }
        val shape = longArrayOf(1, 256)
        OnnxTensor.createTensor(environment, LongBuffer.wrap(input.inputIds), shape).use { ids ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(input.attentionMask), shape).use { mask ->
                session.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { result ->
                    val probability = result.get("keep_probability").get().value as FloatArray
                    val kinds = result.get("kind_logits").get().value as Array<*>
                    check(probability.size == 1 && kinds.size == 1) { "Invalid M1 output shape" }
                    return ModelScores(probability[0].toDouble(), (kinds[0] as FloatArray).copyOf())
                }
            }
        }
    }

    override fun close() {
        session.close()
        // OrtEnvironment is a process singleton shared with other ORT users.
        // Its lifetime belongs to ORT; this judge only owns its session/tensors.
    }

    companion object {
        fun create(modelBytes: ByteArray): OrtConversationModel {
            val environment = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
                environment.createSession(modelBytes, options)
            }
            try {
                check(session.inputNames == setOf("input_ids", "attention_mask")) { "Unexpected M1 inputs" }
                check(session.outputNames == setOf("keep_probability", "kind_logits")) { "Unexpected M1 outputs" }
                for (node in session.inputInfo.values) {
                    val info = node.info as? TensorInfo
                    check(info != null && info.type == OnnxJavaType.INT64 &&
                        info.shape.size == 2 && info.shape[1] == 256L) { "Unexpected M1 input type/shape" }
                }
                for ((name, node) in session.outputInfo) {
                    val info = node.info as? TensorInfo
                    val expectedRank = if (name == "kind_logits") 2 else 1
                    check(info != null && info.type == OnnxJavaType.FLOAT &&
                        info.shape.size == expectedRank &&
                        (expectedRank == 1 || info.shape[1] == 3L)) { "Unexpected M1 output type/shape" }
                }
                return OrtConversationModel(environment, session)
            } catch (failure: Throwable) {
                try { session.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                throw failure
            }
        }
    }
}
