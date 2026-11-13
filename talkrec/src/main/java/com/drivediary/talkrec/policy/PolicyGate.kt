package com.drivediary.talkrec.policy

import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

data class AppSignals(val playbackActive: Boolean? = null, val externalAudioDominant: Boolean? = null)
data class PolicyResult(
    val block: String, val dropLines: List<Int>, val retroDelete: Boolean, val reasons: List<String>,
) { val allowed: Boolean get() = block == "allow" }

/** T56 policy.gate port. Matching copies are normalized; model/storage text is never rewritten. */
class PolicyGate private constructor(private val rules: JSONObject, val rulesSha256: String, wakeWords: List<String>?) {
    private class Rule(val category: String, val id: String, val search: Boolean, val pattern: Regex)
    private fun patterns(fold: Boolean): List<Rule> = rules.getJSONArray("patterns").let { array ->
        List(array.length()) { i ->
            val r = array.getJSONObject(i)
            val pattern = r.getString("pattern")
            Rule(r.getString("category"), r.getString("id"), r.getString("mode") == "search",
                Regex(if (fold) withoutCoda(pattern) else pattern))
        }
    }
    private val patterns = patterns(false)
    private val codaPatterns = patterns(true)
    private val literals = strings(rules, "optout_literals").map(::compact)
    private val folded = literals.map(::withoutCoda)
    private val maxLiteralChars = literals.maxOf { codepoints(it).size }
    private val words = (wakeWords ?: strings(rules, "wake_words")).map {
        compact(it).also { word -> require(word.isNotEmpty()) { "Invalid wake word configuration" } }
    }.distinct().sortedWith(compareByDescending<String> { codepoints(it).size }.thenBy { it })
    private val wakeTail = Regex(withoutCoda(rules.getString("wake_command_tail")))

    private fun match(text: String): List<Pair<String, String>> {
        val hits = mutableListOf<Pair<String, String>>()
        val foldedText = withoutCoda(text)
        for ((rs, value) in listOf(patterns to text, codaPatterns to foldedText)) {
            for (rule in rs) if (if (rule.search) rule.pattern.containsMatchIn(value) else rule.pattern.matches(value)) {
                hits += rule.category to rule.id
            }
        }
        if (literals.any { it in text }) hits += "optout" to "optout.literal"
        else if (folded.any { it in foldedText }) hits += "optout" to "optout.coda"
        val minimum = rules.getInt("fuzzy_min_chars")
        if (codepoints(text).size in minimum..maxLiteralChars + 1 &&
            folded.any { codepoints(it).size >= minimum && oneEdit(foldedText, it) }) {
            hits += "optout" to "optout.approx"
        }
        for (word in words) {
            val wake = withoutCoda(word)
            if (foldedText == wake) hits += "command" to "command.wake"
            else if (foldedText.startsWith(wake) && wakeTail.matches(foldedText.substring(wake.length))) {
                hits += "command" to "command.addressed"
            }
        }
        return hits
    }

    fun gate(lines: List<String>, signals: AppSignals? = null): PolicyResult {
        val normalized = lines.map(::compact)
        val active = normalized.indices.filterTo(mutableSetOf()) { normalized[it].isNotEmpty() }
        val matched = mutableSetOf<Int>()
        val reasons = sortedSetOf<String>()
        var retro = false
        for (size in 1..rules.getInt("max_join_lines")) {
            for (start in 0..lines.size - size) {
                val indices = (start until start + size).toSet()
                if (!active.containsAll(indices)) continue
                val text = normalized.subList(start, start + size).joinToString("")
                if (size > 1 && codepoints(text).size > rules.getInt("max_join_chars")) continue
                var hits = match(text)
                if (size > 1 && indices.any { it in matched }) hits = hits.filter { it.first == "optout" }
                if (hits.isEmpty()) continue
                matched.addAll(indices)
                reasons.addAll(hits.map { it.second })
                retro = retro || hits.any { it.first == "optout" }
            }
        }
        var blocked = retro || (active.isNotEmpty() && matched.size.toDouble() / active.size > rules.getDouble("majority"))
        if (signals?.externalAudioDominant == true) {
            blocked = true; matched.addAll(active); reasons += "external.app_dominant"
        }
        return PolicyResult(if (blocked) "block" else "allow",
            if (blocked) lines.indices.toList() else matched.sorted(), retro, reasons.toList())
    }

    companion object {
        fun fromAssets(wakeWords: List<String>? = null, openAsset: (String) -> InputStream): PolicyGate {
            val bytes = openAsset("policy/rules.json").use { it.readBytes() }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return PolicyGate(JSONObject(bytes.toString(Charsets.UTF_8)), sha, wakeWords)
        }

        /** Python common.normalize's NFKC -> lowercase -> Unicode L/N -> remove spaces. */
        internal fun compact(text: String): String {
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            return buildString {
                normalized.codePoints().forEach { cp ->
                    val category = Character.getType(cp)
                    if (category in Character.UPPERCASE_LETTER.toInt()..Character.OTHER_LETTER.toInt() ||
                        category in Character.DECIMAL_DIGIT_NUMBER.toInt()..Character.OTHER_NUMBER.toInt()) appendCodePoint(cp)
                }
            }
        }
        private fun strings(json: JSONObject, key: String) = json.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
        private fun withoutCoda(text: String) = buildString {
            text.codePoints().forEach { cp -> appendCodePoint(if (cp in 0xAC00..0xD7A3) cp - (cp - 0xAC00) % 28 else cp) }
        }
        private fun codepoints(text: String) = text.codePoints().toArray()
        private fun oneEdit(left: String, right: String): Boolean {
            var a = codepoints(left); var b = codepoints(right)
            if (kotlin.math.abs(a.size - b.size) > 1) return false
            if (a.size > b.size) { val tmp = a; a = b; b = tmp }
            var i = 0; var j = 0; var errors = 0
            while (i < a.size && j < b.size) {
                if (a[i] == b[j]) { i++; j++ }
                else { if (++errors > 1) return false; if (a.size == b.size) i++; j++ }
            }
            return errors + b.size - j <= 1
        }
    }
}
