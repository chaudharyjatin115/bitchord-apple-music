package com.music.bitchord.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.R
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val POSTER_W = 1080
private const val POSTER_H = 1920

private val posterCache = object : LruCache<String, Bitmap>(8) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

/**
 * Renders an Apple Music-style 9:16 Story card for a song, ideal for Instagram
 * Stories, WhatsApp Status, and social media sharing.
 */
suspend fun renderSongPoster(
    context: Context,
    song: Song,
): Bitmap = coroutineScope {
    val cached = posterCache.get(song.videoId)
    if (cached != null && !cached.isRecycled) {
        return@coroutineScope cached
    }

    val artUrl = song.thumbnailUrl.artworkAt(PLAYER_ART_PX)
    val artBitmap = loadBitmap(context, artUrl)
    val fonts = Fonts(context)

    withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(POSTER_W, POSTER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Apple Music ambient mesh backdrop derived from artwork palette
        drawSongBackdrop(canvas, artBitmap)

        // 2. Header branding with vector logo
        drawHeaderBranding(canvas, context, fonts)

        // 3. Large floating artwork card with rounded corners and soft shadow
        val artSize = 750f
        val artX = (POSTER_W - artSize) / 2f
        val artY = 280f
        drawCardShadow(canvas, artX, artY, artSize, artSize, 56f)
        drawArtworkCard(canvas, artBitmap, song.title, artX, artY, artSize)

        // 4. Song Title & Artist Info using SF Pro Display
        var y = artY + artSize + 85f
        y = drawSongMetadata(canvas, song, fonts, y)

        // 5. Audio Quality Badge
        drawAudioBadge(canvas, fonts, y)

        // 6. BitChord Branding Footer with vector logo
        drawBrandingFooter(canvas, context, fonts)

        posterCache.put(song.videoId, bitmap)
        bitmap
    }
}

private class Fonts(context: Context) {
    val heavy: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.sf_pro_display_heavy) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    val semibold: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.sf_pro_display_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    val regular: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.sf_pro_display_regular) }.getOrNull() ?: Typeface.DEFAULT
}

private suspend fun loadBitmap(context: Context, url: String?): Bitmap? {
    if (url.isNullOrEmpty()) return null
    return runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()
}

/**
 * Renders 4 wide radial gradients off the artwork palette to form an Apple Music
 * ambient mesh backdrop, darkened with a top-to-bottom scrim for crisp text contrast.
 */
private fun drawSongBackdrop(canvas: Canvas, artBitmap: Bitmap?) {
    val colors = paletteOf(artBitmap)
    canvas.drawColor(dimmed(colors.first()))

    val anchors = listOf(
        0.20f to 0.16f,
        0.84f to 0.22f,
        0.76f to 0.66f,
        0.18f to 0.78f,
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    colors.forEachIndexed { index, color ->
        val (fx, fy) = anchors[index]
        val cx = POSTER_W * fx
        val cy = POSTER_H * fy
        val radius = POSTER_W * 1.10f
        paint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(color, 220),
                ColorUtils.setAlphaComponent(color, 0),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }
    paint.shader = null

    // Darkening linear gradient overlay
    paint.shader = LinearGradient(
        0f, 0f, 0f, POSTER_H.toFloat(),
        intArrayOf(0x66000000.toInt(), 0x33000000, 0xAA000000.toInt()),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, POSTER_W.toFloat(), POSTER_H.toFloat(), paint)
    paint.shader = null
}

private fun paletteOf(bitmap: Bitmap?): List<Int> {
    val fallback = listOf(0xFF232526.toInt(), 0xFF414345.toInt(), 0xFF181818.toInt(), 0xFF2C3E50.toInt())
    val source = bitmap ?: return fallback
    val swatches = runCatching {
        Palette.from(source).maximumColorCount(24).generate().swatches
            .sortedByDescending { it.population }
            .map { it.rgb }
    }.getOrNull().orEmpty()
    if (swatches.isEmpty()) return fallback
    return (swatches + fallback).take(4).map(::tuned)
}

private fun tuned(color: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    hsl[1] = (hsl[1] * 1.30f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.25f, 0.55f)
    return ColorUtils.HSLToColor(hsl)
}

private fun dimmed(color: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    hsl[2] = 0.08f
    return ColorUtils.HSLToColor(hsl)
}

private fun drawLogo(canvas: Canvas, context: Context, left: Float, top: Float, size: Float) {
    val logo = runCatching {
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_logo, null)
    }.getOrNull() ?: return
    logo.setTint(Color.WHITE)
    logo.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
    logo.draw(canvas)
}

private fun drawHeaderBranding(canvas: Canvas, context: Context, fonts: Fonts) {
    val margin = 100f
    val y = 120f

    // Draw App Vector Logo
    drawLogo(canvas, context, margin, y, 48f)

    val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        typeface = fonts.heavy
    }

    val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        textSize = 26f
        typeface = fonts.semibold
        textAlign = Paint.Align.RIGHT
    }

    canvas.drawText("BitChord", margin + 62f, y + 38f, brandPaint)
    canvas.drawText("MUSIC", POSTER_W - margin, y + 38f, badgePaint)
}

private fun drawCardShadow(
    canvas: Canvas,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    radius: Float,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000.toInt()
        setShadowLayer(55f, 0f, 30f, 0x90000000.toInt())
    }
    canvas.drawRoundRect(RectF(x, y, x + w, y + h), radius, radius, paint)
}

private fun drawArtworkCard(
    canvas: Canvas,
    bitmap: Bitmap?,
    fallback: String,
    x: Float,
    y: Float,
    size: Float,
) {
    val bounds = RectF(x, y, x + size, y + size)
    val radius = 56f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    if (bitmap != null) {
        val scale = size / minOf(bitmap.width, bitmap.height).toFloat()
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                x - (bitmap.width * scale - size) / 2f,
                y - (bitmap.height * scale - size) / 2f,
            )
        }
        paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(matrix) }
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.shader = null
    } else {
        val hue = (fallback.hashCode().toFloat() % 360f + 360f) % 360f
        paint.color = ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.45f))
        canvas.drawRoundRect(bounds, radius, radius, paint)
    }

    // Subtle 2px white border stroke
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x33FFFFFF
    }
    canvas.drawRoundRect(bounds, radius, radius, strokePaint)
}

private fun drawSongMetadata(
    canvas: Canvas,
    song: Song,
    fonts: Fonts,
    top: Float,
): Float {
    val margin = 100f
    val maxW = POSTER_W - margin * 2f

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 64f
        typeface = fonts.heavy
    }

    val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE0FFFFFF.toInt()
        textSize = 42f
        typeface = fonts.semibold
    }

    val albumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x90FFFFFF.toInt()
        textSize = 34f
        typeface = fonts.regular
    }

    // Draw Title (up to 2 lines)
    val titleText = song.title
    val titleWidth = titlePaint.measureText(titleText)
    var currentY = top

    if (titleWidth <= maxW) {
        canvas.drawText(titleText, margin, currentY, titlePaint)
        currentY += 72f
    } else {
        val words = titleText.split(" ")
        var line1 = ""
        var line2 = ""
        for (word in words) {
            val test = if (line1.isEmpty()) word else "$line1 $word"
            if (titlePaint.measureText(test) <= maxW) {
                line1 = test
            } else {
                line2 = if (line2.isEmpty()) word else "$line2 $word"
            }
        }
        canvas.drawText(line1, margin, currentY, titlePaint)
        currentY += 72f
        if (line2.isNotEmpty()) {
            val truncatedLine2 = ellipsised(line2, titlePaint, maxW)
            canvas.drawText(truncatedLine2, margin, currentY, titlePaint)
            currentY += 72f
        }
    }

    // Draw Artist
    val artistText = song.artist
    if (artistText.isNotEmpty()) {
        canvas.drawText(ellipsised(artistText, artistPaint, maxW), margin, currentY, artistPaint)
        currentY += 56f
    }

    // Draw Album
    val albumText = song.albumName
    if (!albumText.isNullOrEmpty()) {
        canvas.drawText(ellipsised(albumText, albumPaint, maxW), margin, currentY, albumPaint)
        currentY += 48f
    }

    return currentY
}

private fun drawAudioBadge(canvas: Canvas, fonts: Fonts, top: Float): Float {
    val margin = 100f
    val badgeY = top + 10f

    val badgeText = "Hi-Res Lossless · Dolby Atmos"
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textSize = 24f
        typeface = fonts.semibold
    }

    val textW = textPaint.measureText(badgeText)
    val paddingH = 28f
    val badgeH = 48f
    val badgeW = textW + paddingH * 2f

    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x40FFFFFF
    }

    val rect = RectF(margin, badgeY, margin + badgeW, badgeY + badgeH)
    canvas.drawRoundRect(rect, 24f, 24f, pillPaint)
    canvas.drawRoundRect(rect, 24f, 24f, borderPaint)

    canvas.drawText(badgeText, margin + paddingH, badgeY + 33f, textPaint)

    return badgeY + badgeH
}

private fun drawBrandingFooter(canvas: Canvas, context: Context, fonts: Fonts) {
    val margin = 100f
    val footerY = POSTER_H - 120f

    // Draw Vector Logo in Footer
    drawLogo(canvas, context, margin, footerY, 44f)

    val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDFFFFFF.toInt()
        textSize = 32f
        typeface = fonts.heavy
    }

    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        textSize = 22f
        typeface = fonts.regular
    }

    canvas.drawText("BitChord Music", margin + 56f, footerY + 34f, brandPaint)
    canvas.drawText("Shared via BitChord for Android", margin, footerY + 74f, subPaint)
}

private fun ellipsised(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var truncated = text
    while (truncated.isNotEmpty() && paint.measureText("$truncated…") > maxWidth) {
        truncated = truncated.dropLast(1)
    }
    return "$truncated…"
}
