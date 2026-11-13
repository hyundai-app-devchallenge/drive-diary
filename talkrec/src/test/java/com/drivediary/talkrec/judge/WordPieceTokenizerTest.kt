package com.drivediary.talkrec.judge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WordPieceTokenizerTest {
    private val vocabulary = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]",
        "a", "##a", "b", "##b", "ab", "가", "##나", "中", "文", "!", "$",
        "é", "e", "##\u0301", "Ａ", "A", "[", "MASK", "]", "😀", "##😀",
        "c", "d", "f", "g", "h", "i",
    )

    @Test
    fun koreanWordPiecesChineseAndPunctuation() {
        assertContent("가나中文!a\$b", 10, 11, 12, 13, 14, 5, 15, 7)
        assertContent("가나z", 1) // Roll back successful prefixes on an unknown suffix.
        assertContent("a\uD840\uDC00b", 5, 1, 7) // Supplementary CJK is isolated.
    }

    @Test
    fun preservesCaseWidthAndComposedOrDecomposedAccents() {
        assertContent("é e\u0301 Ａ A a", 16, 17, 18, 19, 20, 5)
    }

    @Test
    fun bertCleaningRemovesControlsBeforeConvertingWhitespace() {
        for (removed in listOf('\u0000', '\u000b', '\u0085', '\u200b', '\ue000', '\ufffd')) {
            assertContent("a${removed}b", 9)
        }
        for (space in listOf('\t', '\n', '\r', '\u00a0', '\u2028', '\u202f', '\u3000')) {
            assertContent("a${space}b", 5, 7)
        }
        // HF unicode_categories::is_other() does not remove unassigned scalars.
        assertContent("a\u0378b", 1)
        // These scalars gained categories after HF's pinned Unicode tables.
        // Modern Character.getType would incorrectly remove/split them.
        assertContent("a\u0890b", 1)
        assertContent("a\uD80D\uDC30b", 1) // U+13430
        assertContent("a\uD807\uDF43b", 1) // U+11F43
    }

    @Test
    fun addedSpecialTokensAreMatchedBeforeNormalizationAndCanAppearInsideWords() {
        assertContent("a[MASK]b [PAD]", 5, 4, 7, 0)
        // The embedded control prevents AddedToken matching, then is cleaned away.
        assertContent("[MA\u0000SK]", 21, 22, 23)
    }

    @Test
    fun wordLimitCountsUnicodeCodePointsAndIsInclusiveAt100() {
        val tokenizer = WordPieceTokenizer(vocabulary)
        val atLimit = tokenizer.encode("a".repeat(100))
        assertEquals(102, atLimit.attentionMask.sum().toInt())
        assertEquals(5L, atLimit.inputIds[1])
        assertEquals(6L, atLimit.inputIds[100])
        assertContent("a".repeat(101), 1)
        val emojiAtLimit = tokenizer.encode("😀".repeat(100))
        assertEquals(102, emojiAtLimit.attentionMask.sum().toInt())
        assertEquals(24L, emojiAtLimit.inputIds[1])
        assertEquals(25L, emojiAtLimit.inputIds[100])
        assertContent("😀".repeat(101), 1)
    }

    @Test
    fun truncationKeepsCeilingHeadAndFloorTailIncludingLiteralSpecials() {
        val tokenizer = WordPieceTokenizer(vocabulary, maxLength = 7)
        assertArrayEquals(longArrayOf(2, 5, 7, 26, 30, 31, 3),
            tokenizer.encode("a b c d e f g h i").inputIds)
        val literal = tokenizer.encode("a [PAD] [MASK] a b a b [SEP]")
        assertArrayEquals(longArrayOf(2, 5, 0, 4, 7, 3, 3), literal.inputIds)
        assertArrayEquals(LongArray(7) { 1 }, literal.attentionMask)
    }

    @Test
    fun emptyInputAndPaddingKeepOnlyPostprocessorTokensVisible() {
        val tokens = WordPieceTokenizer(vocabulary, maxLength = 6).encode("")
        assertArrayEquals(longArrayOf(2, 3, 0, 0, 0, 0), tokens.inputIds)
        assertArrayEquals(longArrayOf(1, 1, 0, 0, 0, 0), tokens.attentionMask)
    }

    private fun assertContent(text: String, vararg content: Long) {
        val tokens = WordPieceTokenizer(vocabulary).encode(text)
        val expected = longArrayOf(2) + content + longArrayOf(3)
        assertArrayEquals(expected, tokens.inputIds.copyOf(expected.size))
        assertArrayEquals(LongArray(expected.size) { 1 }, tokens.attentionMask.copyOf(expected.size))
        assertEquals(0L, tokens.inputIds.drop(expected.size).sum())
        assertEquals(0L, tokens.attentionMask.drop(expected.size).sum())
    }
}
