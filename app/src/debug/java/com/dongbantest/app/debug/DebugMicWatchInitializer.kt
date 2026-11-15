package com.dongbantest.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** 디버그 빌드에서 앱이 뜰 때 마이크 무음 감시를 자동으로 시작하는 빈 ContentProvider. */
class DebugMicWatchInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        DebugMicWatch.start(context!!.applicationContext)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
