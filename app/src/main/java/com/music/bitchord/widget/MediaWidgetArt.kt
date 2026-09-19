package com.music.bitchord.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.LruCache
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.model.artworkAt
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object MediaWidgetArt {

    private const val BLUR_RADIUS = 30
    private const val BLUR_PASSES = 3
    private const val OPAQUE = 0xFF shl 24

    suspend fun renderComposite(
        context: Context,
        artworkUrl: String?,
        widthPx: Int,
        heightPx: Int,
        key: String?,
        cornerRadiusPx: Float,
        isNight: Boolean,
    ): Bitmap {
        val cacheKey = "$isNight|$key|$widthPx|$heightPx"
        composites[cacheKey]?.takeIf { !it.isRecycled }?.let { return it }

        val cover = if (isCoolingOff(key)) null else loadArtwork(context, artworkUrl, 400)
        
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        if (cover != null) {
            canvas.drawBlurredBackground(cover, isNight)
        } else {
            canvas.drawPlaceholder()
        }

        val rounded = bitmap.withRoundedCorners(cornerRadiusPx, isNight)
        bitmap.recycle()
        
        if (cover != null) composites.put(cacheKey, rounded)
        return rounded
    }

    private fun Canvas.drawBlurredBackground(src: Bitmap, isNight: Boolean) {
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val sw = (width / scale).toInt()
        val sh = (height / scale).toInt()
        val left = (src.width - sw) / 2
        val top = (src.height - sh) / 2
        
        val cropped = Bitmap.createBitmap(src, left, top, sw, sh)
        val scaled = Bitmap.createScaledBitmap(cropped, width / 4, height / 4, true)
        cropped.recycle()

        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val scratch = IntArray(pixels.size)
        
        blurInPlace(pixels, scratch, scaled.width, scaled.height, 12)
        val blurred = Bitmap.createBitmap(pixels, scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        
        drawBitmap(blurred, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        blurred.recycle()
        scaled.recycle()

        // Luminous high-key or deep atmospheric overlay
        drawColor(if (isNight) 0x20000000 else 0x30FFFFFF, PorterDuff.Mode.SRC_ATOP)
    }

    private fun Canvas.drawPlaceholder() {
        drawColor(0xFF1C1C1E.toInt())
    }

    private fun Bitmap.withRoundedCorners(radius: Float, isNight: Boolean): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(this@withRoundedCorners, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        
        // Specular rim
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = if (isNight) 0x25FFFFFF else 0x40FFFFFF
        }
        canvas.drawRoundRect(rect, radius, radius, rimPaint)
        
        return out
    }

    private fun blurInPlace(pixels: IntArray, scratch: IntArray, w: Int, h: Int, radius: Int) {
        repeat(BLUR_PASSES) {
            boxPass(pixels, scratch, h, w, w, 1, radius)
            boxPass(scratch, pixels, w, 1, h, w, radius)
        }
    }

    private fun boxPass(src: IntArray, dst: IntArray, lines: Int, lineStride: Int, span: Int, step: Int, radius: Int) {
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            val base = line * lineStride
            var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val c = src[base + i.coerceIn(0, span - 1) * step]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until span) {
                dst[base + i * step] = OPAQUE or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val gone = src[base + (i - radius).coerceIn(0, span - 1) * step]
                val come = src[base + (i + radius + 1).coerceIn(0, span - 1) * step]
                r += ((come shr 16) and 0xFF) - ((gone shr 16) and 0xFF)
                g += ((come shr 8) and 0xFF) - ((gone shr 8) and 0xFF)
                b += (come and 0xFF) - (gone and 0xFF)
            }
        }
    }

    suspend fun renderThumbnail(context: Context, url: String?, sizePx: Int, radiusPx: Float): Bitmap? {
        val src = loadArtwork(context, url, 200) ?: return null
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val scale = sizePx.toFloat() / minOf(src.width, src.height)
                setLocalMatrix(Matrix().apply { setScale(scale, scale) })
            }
        }
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radiusPx, radiusPx, paint)
        return out
    }

    private suspend fun loadArtwork(context: Context, url: String?, size: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        val request = ImageRequest.Builder(context).data(url.artworkAt(size) ?: url).size(size).allowHardware(false).build()
        val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull()
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    private fun isCoolingOff(key: String?): Boolean {
        val failedAt = key?.let { failures[it] } ?: return false
        if (SystemClock.elapsedRealtime() - failedAt < 30_000L) return true
        failures.remove(key)
        return false
    }

    private val composites = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val failures = ConcurrentHashMap<String, Long>()
}
