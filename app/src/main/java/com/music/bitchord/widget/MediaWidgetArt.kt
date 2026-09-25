package com.music.bitchord.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.model.artworkAt

internal object MediaWidgetArt {

    private val artworkCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Extract adaptive gradient colors from artwork palette */
    private fun extractThemeColors(context: Context, artwork: Bitmap?): IntArray {
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val defaultDarkTop = Color.parseColor("#1C1A24")
        val defaultDarkMid = Color.parseColor("#14121A")
        val defaultDarkBot = Color.parseColor("#0C0B10")

        if (artwork == null) return intArrayOf(defaultDarkTop, defaultDarkMid, defaultDarkBot)

        val palette = Palette.from(artwork).generate()

        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.swatches.maxByOrNull { s -> s.population * (1.0f + s.hsl[1] * 2.0f) }
            ?: palette.dominantSwatch
            ?: palette.mutedSwatch
            ?: return intArrayOf(defaultDarkTop, defaultDarkMid, defaultDarkBot)

        val hsv = FloatArray(3)
        Color.colorToHSV(swatch.rgb, hsv)

        val hue = hsv[0]
        val origSat = hsv[1]
        val origValue = hsv[2]

        if (isDarkMode) {
            if (origSat < 0.12f) {
                return intArrayOf(
                    Color.HSVToColor(floatArrayOf(0f, 0f, 0.24f)),
                    Color.HSVToColor(floatArrayOf(0f, 0f, 0.15f)),
                    Color.HSVToColor(floatArrayOf(0f, 0f, 0.09f))
                )
            }

            var sat = origSat.coerceIn(0.60f, 0.95f)
            var h = hue
            if (h in 15.0f..45.0f && origSat < 0.5f) {
                h = 12.0f
                sat = 0.85f
            }

            val baseVal = origValue.coerceIn(0.15f, 0.28f)
            val topVal = (baseVal + 0.12f).coerceAtMost(0.35f)
            val botVal = (baseVal - 0.12f).coerceAtLeast(0.08f)

            return intArrayOf(
                Color.HSVToColor(floatArrayOf(h, sat, topVal)),
                Color.HSVToColor(floatArrayOf(h, sat, baseVal)),
                Color.HSVToColor(floatArrayOf(h, sat * 0.90f, botVal))
            )
        } else {
            if (origSat < 0.12f) {
                return intArrayOf(
                    Color.HSVToColor(floatArrayOf(0f, 0f, 0.92f)),
                    Color.HSVToColor(floatArrayOf(0f, 0f, 0.85f)),
                    Color.HSVToColor(floatArrayOf(0f, 0f, 0.78f))
                )
            }

            var sat = origSat.coerceIn(0.40f, 0.90f)
            var h = hue
            if (h in 15.0f..45.0f && origSat < 0.5f) {
                h = 12.0f
                sat = 0.80f
            }

            val baseVal = origValue.coerceIn(0.70f, 0.95f)
            val topVal = (baseVal + 0.10f).coerceAtMost(0.98f)
            val botVal = (baseVal - 0.15f).coerceAtLeast(0.60f)

            return intArrayOf(
                Color.HSVToColor(floatArrayOf(h, sat, topVal)),
                Color.HSVToColor(floatArrayOf(h, sat, baseVal)),
                Color.HSVToColor(floatArrayOf(h, sat * 0.90f, botVal))
            )
        }
    }

    /** Render gradient background for widgets */
    fun renderAppleMusicBackground(context: Context, artwork: Bitmap?, wPx: Int, hPx: Int, radiusPx: Float): Bitmap {
        val targetWidth = wPx.coerceIn(100, 360)
        val targetHeight = hPx.coerceIn(100, 480)
        val artHash = artwork?.hashCode() ?: 0
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val cacheKey = "bg|$targetWidth|$targetHeight|$radiusPx|$artHash|$isDarkMode"
        artworkCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val colors = extractThemeColors(context, artwork)
        val gradient = LinearGradient(0f, 0f, 0f, targetHeight.toFloat(), colors, null, Shader.TileMode.CLAMP)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
        val rectF = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())

        canvas.drawRoundRect(rectF, radiusPx, radiusPx, paint)

        val strokePx = 1f * context.resources.displayMetrics.density
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#15FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }
        val rimRect = RectF(strokePx / 2f, strokePx / 2f, targetWidth.toFloat() - strokePx / 2f, targetHeight.toFloat() - strokePx / 2f)
        canvas.drawRoundRect(rimRect, radiusPx, radiusPx, rimPaint)

        artworkCache.put(cacheKey, result)
        return result
    }

    fun renderAppleMusicLargeBackground(context: Context, artwork: Bitmap?, wPx: Int, hPx: Int, topHeightPx: Int, radiusPx: Float): Bitmap {
        return renderAppleMusicBackground(context, artwork, wPx, hPx, radiusPx)
    }

    /** Render album artwork thumbnail with rounded corners */
    suspend fun renderRoundedThumbnail(context: Context, url: String?, sizePx: Int, radiusPx: Float): Bitmap? {
        val cacheKey = "thumb|$url|$sizePx|$radiusPx"
        artworkCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }

        val src = loadArtwork(context, url, 200) ?: return null
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val density = context.resources.displayMetrics.density

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#20000000") }
        val shadowRect = RectF(0f, 1.5f * density, sizePx.toFloat(), sizePx.toFloat() + 1f * density)
        canvas.drawRoundRect(shadowRect, radiusPx, radiusPx, shadowPaint)

        val artRect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val scale = sizePx.toFloat() / minOf(src.width, src.height)
                setLocalMatrix(Matrix().apply { setScale(scale, scale) })
            }
        }
        canvas.drawRoundRect(artRect, radiusPx, radiusPx, artPaint)

        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.parseColor("#1A000000")
        }
        canvas.drawRoundRect(artRect, radiusPx, radiusPx, rimPaint)

        artworkCache.put(cacheKey, out)
        return out
    }

    private suspend fun loadArtwork(context: Context, url: String?, size: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        val request = ImageRequest.Builder(context).data(url.artworkAt(size) ?: url).size(size).allowHardware(false).build()
        val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull()
        return (result as? SuccessResult)?.image?.toBitmap()
    }
}
