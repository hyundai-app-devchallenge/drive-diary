package com.drivediary.talkrec.summary

/** 외부 요약 제공자가 반환하는 제목, 고정 분류 하나, 자유 키워드 및 종류이다. */
data class SummaryResult(
    val title: String,
    val category: String,
    val keywords: List<String>,
    val kind: String,
) {
    /** 기존 tags JSON 저장 열과 표시 호출부의 순서를 유지한다. */
    val tags: List<String> get() = listOf(category) + keywords
    override fun toString(): String = "SummaryResult(redacted)"
}
