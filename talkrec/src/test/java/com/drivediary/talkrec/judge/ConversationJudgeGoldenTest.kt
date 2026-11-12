package com.drivediary.talkrec.judge

import java.util.Collections
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationJudgeGoldenTest {
    @Test
    fun hostRuntimeHasOneOrtClassDefinition() {
        val resources = javaClass.classLoader!!.getResources("ai/onnxruntime/OnnxTensor.class")
        assertEquals("Host tests must have a single desktop ORT class definition", 1, Collections.list(resources).size)
    }

    @Test
    fun inventedGoldenTokensMasksAndInt8ProbabilitiesMatch() {
        val golden = JudgeTestAssets.golden()
        assertTrue(golden.getBoolean("invented_only"))
        assertEquals(256, golden.getInt("max_length"))
        val examples = golden.getJSONArray("examples")
        assertEquals(20, examples.length())
        val tolerance = golden.getDouble("recommended_probability_abs_tolerance")
        val tokenizer = JudgeTestAssets.tokenizer()
        JudgeTestAssets.judge().use { judge ->
            assertEquals(0.9573729634284973, judge.threshold, 0.0)
            for (i in 0 until examples.length()) {
                val row = examples.getJSONObject(i)
                val id = row.getString("id")
                val lines = row.getJSONArray("lines").strings()
                val text = ModelInputText.fromLines(lines)
                assertEquals(id, row.getString("input_text"), text)
                val tokens = tokenizer.encode(text)
                assertArrayEquals(id, row.getJSONArray("input_ids").longs(), tokens.inputIds)
                assertArrayEquals(id, row.getJSONArray("attention_mask").longs(), tokens.attentionMask)
                val result = judge.judge(lines)
                // golden.keep_probability was produced by the deployed INT8 model.
                val expected = row.getDouble("keep_probability")
                assertEquals(id, expected, result.keepProbability, tolerance)
                assertEquals(id, expected >= judge.threshold, result.keep)
                val logits = row.getJSONArray("kind_logits")
                val best = (0..2).maxByOrNull { logits.getDouble(it) }
                val kind = when (best) {
                    1 -> ConversationKind.STORY
                    2 -> ConversationKind.MOMENT
                    else -> null
                }
                assertEquals(id, kind, result.kind)
            }
        }
    }
}
