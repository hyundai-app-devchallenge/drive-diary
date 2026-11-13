package com.drivediary.talkrec.judge

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelInputTextTest {
    @Test
    fun stripsPythonWhitespacePreservesInteriorAndEmptyLines() {
        assertEquals("A  가\n\nＡ Café\n", ModelInputText.fromLines(listOf(
            "\u001c\u0085\u00a0 A  가\u3000", " \t\r\n", " Ａ Café ", "",
        )))
        assertEquals("", ModelInputText.fromLines(emptyList()))
        assertEquals("\n", ModelInputText.fromLines(listOf("", "")))
        assertEquals("\u200ba\u200b", ModelInputText.fromLines(listOf("\u200ba\u200b")))
        assertEquals("a\r\nb", ModelInputText.fromLines(listOf("a\r\nb")))
    }
}
