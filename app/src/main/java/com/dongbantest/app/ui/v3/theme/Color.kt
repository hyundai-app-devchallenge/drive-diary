package com.dongbantest.app.ui.v3.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 웹 확정안 tokens.css / styles.css 의 색 토큰. 화면 코드는 이 값만 쓴다. */
@Immutable
data class DbColors(
    val paper: Color = Color(0xFFFFFFFF),
    val paper2: Color = Color(0xFFF7F7F7),
    val paper3: Color = Color(0xFFF2F2F2),
    val ink: Color = Color(0xFF222222),
    val ink2: Color = Color(0xFF3F3F3F),
    /** --color-body (#3f3f3f, ink-2와 같은 값) */
    val body: Color = Color(0xFF3F3F3F),
    val muted: Color = Color(0xFF6A6A6A),
    val rule: Color = Color(0xFFDDDDDD),
    val ruleSoft: Color = Color(0xFFEBEBEB),
    /** Hyundai Blue */
    val accent: Color = Color(0xFF002C5F),
    val accentActive: Color = Color(0xFF00214A),
    /** --color-action: 채운 버튼 바탕 */
    val action: Color = Color(0xFF002C5F),
    val actionPressed: Color = Color(0xFF001B3D),
    val accentSoft: Color = Color(0xFFDCE5F0),
    val accentInk: Color = Color(0xFFFFFFFF),
    val error: Color = Color(0xFFC13515),
    val scrim: Color = Color(0x80000000),
    /** 그림자(--shadow-float)의 색 */
    val shadow: Color = Color(0x1A000000),
    /** 기록별 색(지도 경로·카드 점·리본) */
    val tripGangneung: Color = Color(0xFF002C5F),
    val tripJeju: Color = Color(0xFF0A8F7A),
    val tripYangpyeong: Color = Color(0xFFC26A00),
    /** 들은 노래(노래 핀·노래가 흐른 길·타임라인 노래 막대). 여행 색 세 가지(남색·청록·주황) 어느 것과도 헷갈리지 않는 베리색 */
    val music: Color = Color(0xFFC2387F),
    val musicSoft: Color = Color(0xFFF3C7DC),
    val musicInk: Color = Color(0xFF8A1F55),
    /** 예시 지도 (.map-land, .map-sea, .map-road) */
    val mapLand: Color = Color(0xFFEEF2F6),
    val mapSea: Color = Color(0xFFD9E6F3),
    val mapRoad: Color = Color(0xFFFFFFFF),
)

val LocalDbColors = staticCompositionLocalOf { DbColors() }
