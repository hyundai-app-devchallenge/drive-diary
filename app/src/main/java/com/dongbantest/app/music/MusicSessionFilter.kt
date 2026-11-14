package com.dongbantest.app.music

/** Android 의존성이 없는 세션 선택 규칙. 다른 앱 세션은 태그와 관계없이 허용한다. */
object MusicSessionFilter {
    const val DEBUG_SESSION_TAG = "dongban-debug-music"

    fun accepts(ownPackage: String, sessionPackage: String, tag: String?, debug: Boolean): Boolean =
        sessionPackage != ownPackage || (debug && tag == DEBUG_SESSION_TAG)
}
