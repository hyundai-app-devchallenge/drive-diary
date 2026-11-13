package com.drivediary.talkrec.judge

import java.io.Reader

/** Fixed M1 BERT config: cased, no NFKC/NFD/accent stripping, one segment. */
class WordPieceTokenizer(
    vocabulary: List<String>,
    val maxLength: Int = 256,
    private val maxInputCharsPerWord: Int = 100,
) {
    private val vocab: Map<String, Long> = vocabulary.withIndex().associate { (id, token) ->
        token to id.toLong()
    }

    init {
        require(maxLength >= 4 && maxInputCharsPerWord > 0) { "Invalid tokenizer limits" }
        require(vocab.size == vocabulary.size) { "Duplicate vocabulary tokens" }
        require(SPECIAL_TOKENS.withIndex().all { (id, token) -> vocab[token] == id.toLong() }) {
            "M1 special token ids do not match"
        }
    }

    /** Each call owns its arrays; the tokenizer is immutable and safe to share. */
    fun encode(text: String): TokenizedInput {
        val content = ArrayList<Long>()
        var cursor = 0
        // HF AddedToken(normalized=false, single_word=false) matches before cleaning,
        // even inside a word. Literal special tokens remain content for truncation.
        for (match in SPECIAL_PATTERN.findAll(text)) {
            encodeOrdinary(text.substring(cursor, match.range.first), content)
            content.add(vocab.getValue(match.value))
            cursor = match.range.last + 1
        }
        encodeOrdinary(text.substring(cursor), content)

        val budget = maxLength - 2
        val kept = if (content.size <= budget) content else {
            val left = (budget + 1) / 2
            content.take(left) + content.takeLast(budget - left)
        }
        val ids = LongArray(maxLength) // PAD=0, right padding
        val mask = LongArray(maxLength)
        ids[0] = 2 // CLS
        for ((index, id) in kept.withIndex()) ids[index + 1] = id
        ids[kept.size + 1] = 3 // SEP
        mask.fill(1L, 0, kept.size + 2)
        return TokenizedInput(ids, mask)
    }

    private fun encodeOrdinary(text: String, output: MutableList<Long>) {
        val normalized = buildString {
            var offset = 0
            while (offset < text.length) {
                val c = text.codePointAt(offset)
                offset += Character.charCount(c)
                when {
                    BertUnicode.isRemoved(c) -> Unit
                    isUnicodeWhitespace(c) -> append(' ')
                    isChinese(c) -> { append(' '); appendCodePoint(c); append(' ') }
                    else -> appendCodePoint(c)
                }
            }
        }
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) {
                encodeWord(word.toString(), output)
                word.setLength(0)
            }
        }
        var offset = 0
        while (offset < normalized.length) {
            val c = normalized.codePointAt(offset)
            offset += Character.charCount(c)
            when {
                isUnicodeWhitespace(c) -> flush()
                BertUnicode.isPunctuation(c) -> {
                    flush()
                    encodeWord(String(Character.toChars(c)), output)
                }
                else -> word.appendCodePoint(c)
            }
        }
        flush()
    }

    private fun encodeWord(word: String, output: MutableList<Long>) {
        val count = word.codePointCount(0, word.length)
        if (count > maxInputCharsPerWord) {
            output.add(1L)
            return
        }
        // Work in Unicode scalar values, not UTF-16 code units (emoji count as one).
        val boundaries = IntArray(count + 1)
        for (i in 0 until count) {
            boundaries[i + 1] = boundaries[i] + Character.charCount(word.codePointAt(boundaries[i]))
        }
        val pieces = ArrayList<Long>()
        var start = 0
        while (start < count) {
            var end = count
            var found: Long? = null
            while (end > start) {
                val piece = (if (start == 0) "" else "##") +
                    word.substring(boundaries[start], boundaries[end])
                found = vocab[piece]
                if (found != null) break
                end--
            }
            if (found == null) {
                // An unsegmentable suffix makes the ENTIRE word UNK.
                output.add(1L)
                return
            }
            pieces.add(found)
            start = end
        }
        output.addAll(pieces)
    }

    companion object {
        private val SPECIAL_TOKENS = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")
        private val SPECIAL_PATTERN = Regex(SPECIAL_TOKENS.joinToString("|") { Regex.escape(it) })

        fun fromVocab(reader: Reader): WordPieceTokenizer = WordPieceTokenizer(reader.buffered().readLines())

        // Rust char::is_whitespace (Unicode White_Space), deliberately unlike
        // Python str.strip() and Java Character.isWhitespace().
        private fun isUnicodeWhitespace(c: Int): Boolean =
            c in 0x0009..0x000d || c == 0x0020 || c == 0x0085 || c == 0x00a0 ||
                c == 0x1680 || c in 0x2000..0x200a || c == 0x2028 || c == 0x2029 ||
                c == 0x202f || c == 0x205f || c == 0x3000

        private fun isChinese(c: Int): Boolean =
            c in 0x4e00..0x9fff || c in 0x3400..0x4dbf || c in 0x20000..0x2a6df ||
                c in 0x2a700..0x2b73f || c in 0x2b740..0x2b81f || c in 0x2b920..0x2ceaf ||
                c in 0xf900..0xfaff || c in 0x2f800..0x2fa1f
    }
}

/** int64 tensors for [batch=1, maxLength]; M1 has no token_type_ids input. */
class TokenizedInput(val inputIds: LongArray, val attentionMask: LongArray)
