package com.drivediary.talkrec.summary

import org.json.JSONObject

/** Ignore prose/fences and complete reasoning blocks, then take the first strict JSON object.
 * A parseable but invalid object is left for the validators; never search for a nicer answer. */
internal fun firstSummaryJsonObject(raw: String): String {
    if (raw.length > 65_536) throw InvalidSummaryOutput()
    val text = raw.replace(Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    for (start in text.indices) {
        if (text[start] != '{') continue
        var depth = 0
        var quoted = false
        var escaped = false
        for (end in start until text.length) {
            val c = text[end]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    val candidate = text.substring(start, end + 1)
                    try { summaryJsonObject(candidate); return candidate } catch (_: InvalidSummaryOutput) { break }
                }
            }
        }
    }
    throw InvalidSummaryOutput()
}

/** Android's org.json accepts non-JSON syntax (single quotes, unquoted keys, trailing commas).
 * Check JSON syntax first so validation behaves the same on Android and the host JVM. */
internal fun summaryJsonObject(text: String): JSONObject {
    try {
        JsonSyntax(text).checkObject()
        return JSONObject(text)
    } catch (_: Exception) {
        throw InvalidSummaryOutput()
    }
}

private class JsonSyntax(private val text: String) {
    private var at = 0

    fun checkObject() {
        if (text.length > 65_536) invalid()
        space()
        if (peek() != '{') invalid()
        value(0)
        space()
        if (at != text.length) invalid()
    }

    private fun value(depth: Int) {
        if (depth > 32) invalid()
        space()
        when (peek()) {
            '{' -> {
                at++; space()
                val keys = mutableSetOf<String>()
                if (take('}')) return
                do {
                    space()
                    val start = at
                    string()
                    // Decode escapes to reject duplicate keys with different encodings too.
                    val key = JSONObject("{${text.substring(start, at)}:0}").keys().next()
                    if (!keys.add(key)) invalid()
                    space(); expect(':'); value(depth + 1); space()
                    if (take('}')) return
                    expect(',')
                } while (true)
            }
            '[' -> {
                at++; space()
                if (take(']')) return
                do {
                    value(depth + 1); space()
                    if (take(']')) return
                    expect(',')
                } while (true)
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> {
                val match = NUMBER.find(text, at)?.takeIf { it.range.first == at } ?: invalid()
                at = match.range.last + 1
            }
            else -> invalid()
        }
    }

    private fun string() {
        expect('"')
        while (at < text.length) {
            val c = text[at++]
            when {
                c == '"' -> return
                c < ' ' -> invalid()
                c == '\\' -> {
                    if (at == text.length) invalid()
                    when (text[at++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            if (at == text.length || text[at++].digitToIntOrNull(16) == null) invalid()
                        }
                        else -> invalid()
                    }
                }
            }
        }
        invalid()
    }

    private fun literal(value: String) {
        if (!text.startsWith(value, at)) invalid()
        at += value.length
    }

    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun peek(): Char = text.getOrNull(at) ?: '\u0000'
    private fun take(c: Char): Boolean = if (peek() == c) { at++; true } else false
    private fun expect(c: Char) { if (!take(c)) invalid() }
    private fun invalid(): Nothing = throw InvalidSummaryOutput()

    companion object { val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
}
