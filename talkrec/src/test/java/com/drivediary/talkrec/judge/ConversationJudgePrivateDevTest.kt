package com.drivediary.talkrec.judge

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with TALKREC_PRIVATE_DIR=<model-create>/.local. Never persists inputs. */
class ConversationJudgePrivateDevTest {
    @Test
    fun all77T58DevDecisionsMatchPythonInt8AtOriginalThreshold() {
        val configured = System.getProperty("TALKREC_PRIVATE_DIR")?.takeIf { it.isNotBlank() }
            ?: System.getenv("TALKREC_PRIVATE_DIR")?.takeIf { it.isNotBlank() }
        assumeTrue("Set TALKREC_PRIVATE_DIR to model-create/.local for private dev parity", configured != null)
        val cases = loadPrivateCases(File(checkNotNull(configured)))
        assertEquals(77, cases.size)
        assertEquals(37, cases.count { it.cast == "child_family" })
        JudgeTestAssets.judge().use { judge ->
            assertEquals(0.9573729634284973, judge.threshold, 0.0)
            for (case in cases) {
                val result = try {
                    judge.judge(case.lines)
                } catch (failure: Exception) {
                    // Suppress causes: native/tokenizer errors must never disclose input.
                    throw AssertionError("id=${case.id} python_int8=${case.probability} inference failed")
                }
                assertEquals(
                    "id=${case.id} python_int8=${case.probability} kotlin=${result.keepProbability}",
                    case.probability >= judge.threshold,
                    result.keep,
                )
            }
        }
    }

    private fun loadPrivateCases(directory: File): List<PrivateCase> = try {
        val local = directory.canonicalFile
        val root = checkNotNull(local.parentFile)
        val devVideos = readTsvColumn(File(root, "data/real-split.tsv"), "split")
            .filterValues { it == "dev" }.keys
        val casts = readTsvColumn(File(root, "data/real-cast.tsv"), "cast")
        val expected = linkedMapOf<String, Double>()
        val predictions = JSONArray(File(local, "export/m1/predictions.json").readText(Charsets.UTF_8))
        for (i in 0 until predictions.length()) {
            val row = predictions.getJSONObject(i)
            if (row.getString("dataset") != "dev") continue
            val id = checkedId(row)
            val probability = row.getDouble("int8") // Neither torch nor fp32.
            check(probability.isFinite() && probability in 0.0..1.0)
            check(expected.put(id, probability) == null)
        }
        check(expected.size == 77)
        check(expected.keys.all { it.substringAfter("yt:").substringBefore('#') in devVideos })

        val run = File(local, "real/v1")
        val labeledIds = mutableSetOf<String>()
        File(run, "judgments/vizatae.jsonl").forEachLine(Charsets.UTF_8) { line ->
            if (line.isNotBlank()) {
                val row = JSONObject(line.removePrefix("\uFEFF"))
                val id = checkedId(row)
                if (id.substringAfter("yt:").substringBefore('#') in devVideos) labeledIds.add(id)
            }
        }
        val cases = linkedMapOf<String, PrivateCase>()
        File(run, "blocks.jsonl").forEachLine(Charsets.UTF_8) { line ->
            if (line.isNotBlank()) {
                val row = JSONObject(line.removePrefix("\uFEFF"))
                val id = checkedId(row)
                val video = id.substringAfter("yt:").substringBefore('#')
                val cast = casts[video] ?: "unknown"
                // Same selection as export.load_private_dev: dev, human-labeled,
                // known cast, including unsure. References join the 37 child_family.
                if (video in devVideos && id in labeledIds && cast != "unknown") {
                    check(row.getString("source") == "yt:$video")
                    val rawLines = row.getJSONArray("lines")
                    val texts = List(rawLines.length()) { index ->
                        when (val value = rawLines.get(index)) {
                            is String -> value
                            is JSONObject -> value.getString("text")
                            else -> error("Invalid private line type")
                        }
                    }
                    check(cases.put(id, PrivateCase(id, texts, expected.getValue(id), cast)) == null)
                }
            }
        }
        check(cases.keys == expected.keys)
        expected.keys.map { cases.getValue(it) }
    } catch (failure: Exception) {
        // org.json exceptions can quote source values. Never attach their causes.
        throw AssertionError("Invalid private dev fixture (${failure.javaClass.simpleName})")
    }

    private fun checkedId(row: JSONObject): String = row.getString("id").also {
        check(Regex("yt:[A-Za-z0-9_-]{11}#[0-9]+").matches(it))
    }

    private fun readTsvColumn(file: File, column: String): Map<String, String> {
        val rows = file.readLines(Charsets.UTF_8)
        val header = rows.first().removePrefix("\uFEFF").split('\t')
        check(header[0] == "video_id" && header[1] == column)
        return rows.drop(1).filter { it.isNotBlank() }.associate { line ->
            val cells = line.split('\t', limit = 3)
            check(Regex("[A-Za-z0-9_-]{11}").matches(cells[0]))
            cells[0] to cells[1]
        }
    }

    // No data-class toString that could accidentally expose transcript lines.
    private class PrivateCase(
        val id: String,
        val lines: List<String>,
        val probability: Double,
        val cast: String,
    )
}
