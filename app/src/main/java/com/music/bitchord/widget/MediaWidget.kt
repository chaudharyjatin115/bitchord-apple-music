package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

abstract class MediaWidget : AppWidgetProvider() {
    companion object {
        fun refresh(context: Context) {
            MediaWidgetWide.refresh(context)
            
            MediaWidgetLarge.refresh(context)
        }
    }
}

/** Wide 4x2 Player Bar Widget */
class MediaWidgetWide : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        renderAsync(context, intArrayOf(id))
    }

    private fun renderAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                render(context.applicationContext, ids)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {
        fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = AppWidgetManager.getInstance(app) ?: return@launch
                val ids = manager.getAppWidgetIds(ComponentName(app, MediaWidgetWide::class.java))
                if (ids.isNotEmpty()) render(app, ids)
            }
        }

        private suspend fun render(context: Context, ids: IntArray) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val snapshot = MediaWidgetSnapshot.load(context)

            for (id in ids) {
                withTimeoutOrNull(5000) {
                    val options = manager.getAppWidgetOptions(id)
                    val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).coerceAtLeast(200)
                    val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).coerceAtLeast(80)
                    val density = context.resources.displayMetrics.density
                    
                    val wPx = (widthDp * density).toInt().coerceAtLeast(400)
                    val hPx = (heightDp * density).toInt().coerceAtLeast(160)
                    val radiusPx = 20 * density

                    val thumb = MediaWidgetArt.renderThumbnail(context, snapshot.artworkUrl, (80 * density).toInt(), 12 * density)
                    val bgArt = MediaWidgetLarge.renderBlurredBackground(context, thumb, wPx, hPx, radiusPx)

                    val views = RemoteViews(context.packageName, R.layout.widget_media_wide)
                    views.setImageViewBitmap(R.id.widget_art, bgArt)
                    if (thumb != null) {
                        views.setImageViewBitmap(R.id.widget_thumb, thumb)
                        views.setViewVisibility(R.id.widget_thumb, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_thumb, View.GONE)
                    }

                    views.setTextViewText(R.id.widget_title, if (snapshot.hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played))
                    views.setTextViewText(R.id.widget_artist, snapshot.artist)
                    views.setViewVisibility(R.id.widget_artist, if (snapshot.artist.isBlank()) View.GONE else View.VISIBLE)

                    val textColor = 0xFFFFFFFF.toInt()
                    views.setTextColor(R.id.widget_title, textColor)
                    views.setTextColor(R.id.widget_artist, 0xB3FFFFFF.toInt())

                    if (snapshot.hasTrack && snapshot.durationMs > 0) {
                        views.setViewVisibility(R.id.widget_progress, View.VISIBLE)
                        views.setProgressBar(R.id.widget_progress, 1000, (snapshot.positionMs.toFloat() / snapshot.durationMs * 1000).toInt(), false)
                        
                        val elapsed = formatTime(snapshot.positionMs)
                        val remaining = "-" + formatTime(snapshot.durationMs - snapshot.positionMs)
                        views.setTextViewText(R.id.widget_time_elapsed, elapsed)
                        views.setTextViewText(R.id.widget_time_remaining, remaining)
                        views.setViewVisibility(R.id.widget_time_elapsed, View.VISIBLE)
                        views.setViewVisibility(R.id.widget_time_remaining, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_progress, View.GONE)
                        views.setViewVisibility(R.id.widget_time_elapsed, View.GONE)
                        views.setViewVisibility(R.id.widget_time_remaining, View.GONE)
                    }

                    views.setImageViewResource(R.id.widget_toggle, if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                    views.setInt(R.id.widget_toggle, "setColorFilter", textColor)
                    views.setInt(R.id.widget_previous, "setColorFilter", textColor)
                    views.setInt(R.id.widget_next, "setColorFilter", textColor)

                    val open = openPlayer(context)
                    views.setOnClickPendingIntent(R.id.widget_root, open)
                    views.setOnClickPendingIntent(R.id.widget_toggle, MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_TOGGLE))
                    views.setOnClickPendingIntent(R.id.widget_previous, MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_PREVIOUS))
                    views.setOnClickPendingIntent(R.id.widget_next, MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_NEXT))

                    manager.updateAppWidget(id, views)
                }
            }
        }

        private fun openPlayer(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, true)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun formatTime(ms: Long): String {
            if (ms <= 0) return "0:00"
            val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ms)
            val seconds = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            return "%d:%02d".format(java.util.Locale.ROOT, minutes, seconds)
        }

        internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

class MediaWidgetLarge : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        renderAsync(context, intArrayOf(id))
    }

    private fun renderAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        MediaWidgetWide.scope.launch {
            try {
                render(context.applicationContext, ids)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {
        fun refresh(context: Context) {
            val app = context.applicationContext
            MediaWidgetWide.scope.launch {
                val manager = AppWidgetManager.getInstance(app) ?: return@launch
                val ids = manager.getAppWidgetIds(ComponentName(app, MediaWidgetLarge::class.java))
                if (ids.isNotEmpty()) render(app, ids)
            }
        }

        private suspend fun render(context: Context, ids: IntArray) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            val density = context.resources.displayMetrics.density
            val songs = com.music.bitchord.data.stats.ListeningStats.getOnRepeatSongs(3)
            
            val nowPlayingArt = MediaWidgetArt.renderThumbnail(context, snapshot.artworkUrl, (105 * density).toInt(), 14 * density)

            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).coerceAtLeast(260)
                val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).coerceAtLeast(320)
                val wPx = (widthDp * density).toInt().coerceAtLeast(400)
                val hPx = (heightDp * density).toInt().coerceAtLeast(500)

                val views = RemoteViews(context.packageName, R.layout.widget_media_large)

                // Blurred Dynamic Artwork Background
                val bgBitmap = renderBlurredBackground(context, nowPlayingArt, wPx, hPx, 28 * density)
                views.setImageViewBitmap(R.id.widget_bg, bgBitmap)

                // Now Playing
                views.setTextViewText(R.id.widget_now_playing_title, if (snapshot.hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played))
                views.setTextViewText(R.id.widget_now_playing_artist, if (snapshot.artist.isNotBlank()) snapshot.artist else "Apple Music")
                
                if (nowPlayingArt != null) {
                    views.setImageViewBitmap(R.id.widget_now_playing_art, nowPlayingArt)
                } else {
                    views.setImageViewResource(R.id.widget_now_playing_art, R.drawable.widget_preview_art)
                }
                
                if (snapshot.isPlaying) {
                    views.setImageViewResource(R.id.widget_pill_icon, R.drawable.ic_widget_pause)
                    views.setTextViewText(R.id.widget_pill_text, "Pause")
                } else {
                    views.setImageViewResource(R.id.widget_pill_icon, R.drawable.ic_widget_play)
                    views.setTextViewText(R.id.widget_pill_text, "Play")
                }

                // Recently Played Section (3 interactive rows)
                for (i in 0 until 3) {
                    val artId = context.resources.getIdentifier("recent_art_$i", "id", context.packageName)
                    val titleId = context.resources.getIdentifier("recent_title_$i", "id", context.packageName)
                    val artistId = context.resources.getIdentifier("recent_artist_$i", "id", context.packageName)
                    val itemId = context.resources.getIdentifier("recent_item_$i", "id", context.packageName)
                    val playBtnId = context.resources.getIdentifier("recent_play_$i", "id", context.packageName)

                    if (i < songs.size) {
                        val song = songs[i]
                        views.setTextViewText(titleId, song.title)
                        views.setTextViewText(artistId, if (song.artist.isNotBlank()) song.artist else "Apple Music")
                        
                        val art = MediaWidgetArt.renderThumbnail(context, song.thumbnailUrl, (44 * density).toInt(), 8 * density)
                        if (art != null) {
                            views.setImageViewBitmap(artId, art)
                        } else {
                            views.setImageViewResource(artId, R.drawable.widget_preview_art)
                        }

                        // Make play button and row interactive to play song on click
                        val playIntent = Intent(context, MainActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = Uri.parse("https://music.youtube.com/watch?v=${song.videoId}")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        val pendingPlay = PendingIntent.getActivity(
                            context,
                            200 + i,
                            playIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(itemId, pendingPlay)
                        views.setOnClickPendingIntent(playBtnId, pendingPlay)
                    } else {
                        views.setTextViewText(titleId, if (i == 0) "The 100 Best Songs of 2023" else if (i == 1) "Taylor Swift Essentials" else "Chill Mix")
                        views.setTextViewText(artistId, "Apple Music")
                        views.setImageViewResource(artId, R.drawable.widget_preview_art)
                    }
                }
                
                val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, true)
                val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                
                // Clicking on Now Playing specifically opens the music player
                views.setOnClickPendingIntent(R.id.now_playing_container, open)
                views.setOnClickPendingIntent(R.id.widget_now_playing_art, open)
                
                views.setOnClickPendingIntent(R.id.widget_toggle, MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_TOGGLE))
                manager.updateAppWidget(id, views)
            }
        }

        internal fun renderBlurredBackground(context: Context, artwork: Bitmap?, wPx: Int, hPx: Int, radiusPx: Float): Bitmap {
            val targetWidth = wPx.coerceAtLeast(100)
            val targetHeight = hPx.coerceAtLeast(100)
            val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            if (artwork != null) {
                val small = Bitmap.createScaledBitmap(artwork, 40, 40, true)
                val blurredSmall = fastBlur(small, 8)
                
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val srcRect = Rect(0, 0, blurredSmall.width, blurredSmall.height)
                val destRect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
                canvas.drawBitmap(blurredSmall, srcRect, destRect, paint)

                val liquidGlowShader = android.graphics.LinearGradient(
                    0f, 0f, 0f, targetHeight.toFloat(),
                    intArrayOf(
                        Color.parseColor("#38FFFFFF"),
                        Color.parseColor("#0DFFFFFF"),
                        Color.parseColor("#68101012")
                    ),
                    floatArrayOf(0.0f, 0.35f, 1.0f),
                    android.graphics.Shader.TileMode.CLAMP
                )
                val liquidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = liquidGlowShader
                    style = Paint.Style.FILL
                }
                canvas.drawRect(destRect, liquidPaint)

                val strokePx = 1.5f * context.resources.displayMetrics.density
                val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#45FFFFFF")
                    style = Paint.Style.STROKE
                    strokeWidth = strokePx
                }
                val rimRect = RectF(strokePx / 2f, strokePx / 2f, targetWidth.toFloat() - strokePx / 2f, targetHeight.toFloat() - strokePx / 2f)
                canvas.drawRoundRect(rimRect, radiusPx, radiusPx, rimPaint)

                small.recycle()
                blurredSmall.recycle()
            } else {
                val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#2D1A1A")
                    style = Paint.Style.FILL
                }
                canvas.drawRect(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat(), darkPaint)
            }

            val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val clipCanvas = Canvas(output)
            val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rectF = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())

            clipCanvas.drawRoundRect(rectF, radiusPx, radiusPx, clipPaint)
            clipPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            clipCanvas.drawBitmap(result, 0f, 0f, clipPaint)

            result.recycle()
            return output
        }

        private fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
            val w = sentBitmap.width
            val h = sentBitmap.height
            val pixels = IntArray(w * h)
            sentBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val r = IntArray(w * h)
            val g = IntArray(w * h)
            val b = IntArray(w * h)

            for (i in pixels.indices) {
                val p = pixels[i]
                r[i] = (p shr 16) and 0xFF
                g[i] = (p shr 8) and 0xFF
                b[i] = p and 0xFF
            }

            val rBlurred = IntArray(w * h)
            val gBlurred = IntArray(w * h)
            val bBlurred = IntArray(w * h)

            val rad = radius.coerceAtLeast(1)

            for (y in 0 until h) {
                for (x in 0 until w) {
                    var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
                    for (dx in -rad..rad) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val idx = y * w + nx
                        rSum += r[idx]
                        gSum += g[idx]
                        bSum += b[idx]
                        count++
                    }
                    val idx = y * w + x
                    rBlurred[idx] = rSum / count
                    gBlurred[idx] = gSum / count
                    bBlurred[idx] = bSum / count
                }
            }

            val resultPixels = IntArray(w * h)
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
                    for (dy in -rad..rad) {
                        val ny = (y + dy).coerceIn(0, h - 1)
                        val idx = ny * w + x
                        rSum += rBlurred[idx]
                        gSum += gBlurred[idx]
                        bSum += bBlurred[idx]
                        count++
                    }
                    val finalR = rSum / count
                    val finalG = gSum / count
                    val finalB = bSum / count
                    resultPixels[y * w + x] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                }
            }

            val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outBitmap.setPixels(resultPixels, 0, w, 0, 0, w, h)
            return outBitmap
        }
    }
}
