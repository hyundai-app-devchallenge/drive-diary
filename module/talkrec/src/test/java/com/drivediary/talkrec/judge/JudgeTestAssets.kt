package com.drivediary.talkrec.judge

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object JudgeTestAssets {
    val directory: File
        get() = File(System.getProperty("talkrec.assetsDir", "src/main/assets"))

    fun tokenizer(): WordPieceTokenizer = File(directory, "m1/vocab.txt").reader(Charsets.UTF_8).use {
        WordPieceTokenizer.fromVocab(it)
    }

    fun judge(): ConversationJudge = ConversationJudge.fromAssets { path -> File(directory, path).inputStream() }

    fun golden(): JSONObject = checkNotNull(javaClass.getResourceAsStream("/m1/golden.json")).use {
        JSONObject(it.bufferedReader(Charsets.UTF_8).readText())
    }
}

internal fun JSONArray.longs(): LongArray = LongArray(length()) { getLong(it) }
internal fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
