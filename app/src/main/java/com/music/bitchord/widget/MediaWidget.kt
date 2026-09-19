package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

abstract class MediaWidget : AppWidgetProvider() {
    companion object {
        fun refresh(context: Context) {
            MediaWidgetWide.refresh(context)
        }
    }
}

class MediaWidgetWide : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        renderAsync(context, intArrayOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) lastArt.remove(id)
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
            val key = snapshot.artworkUrl ?: "no-art"
            val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            for (id in ids) {
                withTimeoutOrNull(5000) {
                    val options = manager.getAppWidgetOptions(id)
                    val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                    val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                    val density = context.resources.displayMetrics.density
                    
                    val wPx = (widthDp * density).toInt().coerceIn(200, 800)
                    val hPx = (heightDp * density).toInt().coerceIn(100, 400)
                    val radiusPx = 16 * density

                    val art = MediaWidgetArt.renderComposite(context, snapshot.artworkUrl, wPx, hPx, key, radiusPx, isNight)
                    val thumb = MediaWidgetArt.renderThumbnail(context, snapshot.artworkUrl, (92 * density).toInt(), 12 * density)

                    val views = RemoteViews(context.packageName, R.layout.widget_media_wide)
                    views.setImageViewBitmap(R.id.widget_art, art)
                    if (thumb != null) {
                        views.setImageViewBitmap(R.id.widget_thumb, thumb)
                        views.setViewVisibility(R.id.widget_thumb, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_thumb, View.GONE)
                    }

                    views.setTextViewText(R.id.widget_title, if (snapshot.hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played))
                    views.setTextViewText(R.id.widget_artist, snapshot.artist)
                    views.setViewVisibility(R.id.widget_artist, if (snapshot.artist.isBlank()) View.GONE else View.VISIBLE)

                    val textColor = if (isNight) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
                    views.setTextColor(R.id.widget_title, textColor)
                    views.setTextColor(R.id.widget_artist, if (isNight) 0xA0FFFFFF.toInt() else 0x901C1C1E.toInt())

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
                    views.setInt(R.id.widget_next, "setColorFilter", 0xFFFA243C.toInt())

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

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val lastArt = ConcurrentHashMap<Int, Bitmap>()
    }
}
