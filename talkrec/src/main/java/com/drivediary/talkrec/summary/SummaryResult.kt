package com.drivediary.talkrec.summary

/** 외부 요약 제공자가 반환하는 제목과 화제 태그 및 종류이다. */
data class SummaryResult(
    val title: String,
    val tags: List<String>,
    val kind: String,
)
