package com.dongbantest.app.ui.v3.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 앱 저장소의 사진 파일을 보여 준다. 화면 스레드 밖에서 [maxPx] 안쪽으로 줄여 읽고,
 * 카메라가 남긴 회전 정보(EXIF)대로 바로 세운다. 읽는 동안·파일이 없을 때는 사진 자리(그림 아이콘)를 보인다.
 * 같은 사진은 한 번 읽으면 기억해 두어 썸네일 ↔ 펼친 목록 ↔ 크게 보기를 오갈 때 다시 읽지 않는다.
 */
@Composable
fun PhotoImage(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxPx: Int = THUMB_PX,
    placeholder: Boolean = true,
) {
    val c = DbTheme.colors
    val bitmap by produceState(PhotoCache.peek(path, maxPx), path, maxPx) {
        if (value == null && path != null) value = withContext(Dispatchers.IO) { PhotoCache.load(path, maxPx) }
    }
    val shown by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(180), label = "Photo fade")
    Box(modifier.then(if (placeholder) Modifier.background(c.paper2) else Modifier), contentAlignment = Alignment.Center) {
        if (placeholder && shown < 1f) DbIcon(DbIcons.Image, 20.dp, c.muted)
        bitmap?.let {
            Image(it, contentDescription, Modifier.fillMaxSize().graphicsLayer { alpha = shown }, contentScale = contentScale)
        }
    }
}

/** 사진의 가로/세로 비율(회전 정보 반영). 파일을 열지 못하면 null. 화면 스레드 밖에서 부른다. */
fun readPhotoAspect(path: String): Float? = PhotoCache.aspect(path)

/** 썸네일·펼친 목록에 쓰는 크기 */
const val THUMB_PX = 480

/** 크게 보기에 쓰는 크기 */
const val FULL_PX = 2048

private object PhotoCache {
    /** 디코딩한 사진(바이트 기준 최대 32MB) */
    private val bitmaps = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val aspects = LruCache<String, Float>(256)

    fun peek(path: String?, maxPx: Int): ImageBitmap? = path?.let { bitmaps.get(key(it, maxPx)) }

    fun load(path: String, maxPx: Int): ImageBitmap? {
        bitmaps.get(key(path, maxPx))?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val degrees = rotation(path)
            val upright = if (degrees == 0) raw else {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            }
            upright.asImageBitmap().also { bitmaps.put(key(path, maxPx), it) }
        }.getOrNull()
    }

    fun aspect(path: String): Float? {
        aspects.get(path)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val turned = rotation(path) % 180 != 0
            val a = if (turned) bounds.outHeight.toFloat() / bounds.outWidth else bounds.outWidth.toFloat() / bounds.outHeight
            a.also { aspects.put(path, it) }
        }.getOrNull()
    }

    private fun rotation(path: String): Int = runCatching {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun key(path: String, maxPx: Int) = "$maxPx:$path"
}
