package com.drivediary.talkrec.policy

import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PolicyGateTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/policy/$name")!!.bufferedReader().use { it.readText() }
    private fun gate() = PolicyGate.fromAssets { File(System.getProperty("talkrec.assetsDir"), it).inputStream() }
    @Test fun allInventedT56AndEdgeCasesMatchPythonGateExactly() {
        val root = JSONObject(resource("cases.json"))
        assertTrue(root.getBoolean("invented_only")); assertEquals(2140, root.getInt("t56_count"))
        val gate = gate(); assertEquals(root.getString("rules_sha256"), gate.rulesSha256)
        val cases = root.getJSONArray("cases"); assertEquals(2192, cases.length())
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i); val id = row.getString("id")
            val lines = row.getJSONArray("lines").let { a -> List(a.length()) { a.getString(it) } }
            val signals = row.optJSONObject("signals")?.let { s ->
                fun flag(key: String): Boolean? = if (s.isNull(key)) null else s.getBoolean(key)
                AppSignals(flag("playback_active"), flag("external_audio_dominant"))
            }
            val actual = gate.gate(lines, signals); val expected = row.getJSONObject("expected")
            assertEquals("$id block", expected.getString("block"), actual.block)
            assertEquals("$id retro", expected.getBoolean("retro_delete"), actual.retroDelete)
            assertEquals("$id drops", ints(expected.getJSONArray("drop_lines")), actual.dropLines)
            val reasons = expected.getJSONArray("reasons").let { a -> List(a.length()) { a.getString(it) } }
            assertEquals("$id reasons", reasons, actual.reasons)
        }
    }
    @Test fun sharedNfkcLowercaseUnicodeNormalizationVectors() {
        val vectors = JSONArray(resource("normalize-vectors.json"))
        assertEquals(60, vectors.length())
        for (i in 0 until vectors.length()) {
            val row = vectors.getJSONObject(i)
            assertEquals("vector $i", row.getString("expected").replace(" ", ""), PolicyGate.compact(row.getString("input")))
        }
    }
    private fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }
}
