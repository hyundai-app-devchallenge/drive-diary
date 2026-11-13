package com.drivediary.talkrec.judge

/** Matches demo.trial.data.input_text, including Python str.strip() whitespace. */
object ModelInputText {
    fun fromLines(lines: List<String>): String = lines.joinToString("\n") { line ->
        line.trim { isPythonWhitespace(it.code) }
    }

    private fun isPythonWhitespace(c: Int): Boolean =
        c in 0x0009..0x000d || c in 0x001c..0x0020 || c == 0x0085 ||
            c == 0x00a0 || c == 0x1680 || c in 0x2000..0x200a ||
            c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000
}
