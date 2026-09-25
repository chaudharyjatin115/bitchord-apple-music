package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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

abstract class MediaWidget : AppWidgetProvider() {
    companion object {
        fun refresh(context: Context) {
            MediaWidgetWide.refresh(context)
            MediaWidgetLarge.refresh(context)
        }
    }
}

/** Wide 4x1 player bar widget */
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
                    val widthDp = maxOf(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)).coerceAtLeast(200)
                    val heightDp = maxOf(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)).coerceAtLeast(56)
                    val density = context.resources.displayMetrics.density
                    
                    val wPx = (widthDp * density).toInt().coerceAtLeast(350)
                    val hPx = (heightDp * density).toInt().coerceAtLeast(110)
                    val radiusPx = 8 * density

                    val thumb = MediaWidgetArt.renderRoundedThumbnail(context, snapshot.artworkUrl, (72 * density).toInt(), 12 * density)
                    val bgArt = MediaWidgetArt.renderAppleMusicBackground(context, thumb, wPx, hPx, radiusPx)

                    val views = RemoteViews(context.packageName, R.layout.widget_media_wide)
                    views.setImageViewBitmap(R.id.widget_bg, bgArt)
                    if (thumb != null) {
                        views.setImageViewBitmap(R.id.widget_thumb, thumb)
                        views.setViewVisibility(R.id.widget_thumb, View.VISIBLE)
                    } else {
                        views.setImageViewResource(R.id.widget_thumb, R.drawable.widget_preview_art)
                    }

                    views.setTextViewText(R.id.widget_title, if (snapshot.hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played))
                    views.setTextViewText(R.id.widget_artist, snapshot.artist)
                    views.setViewVisibility(R.id.widget_artist, if (snapshot.artist.isBlank()) View.GONE else View.VISIBLE)

                    val textColor = 0xFFFFFFFF.toInt()
                    views.setTextColor(R.id.widget_title, textColor)
                    views.setTextColor(R.id.widget_artist, 0xB3FFFFFF.toInt())

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

        internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** Large player widget with recently played list */
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

            val nowPlayingArt = MediaWidgetArt.renderRoundedThumbnail(context, snapshot.artworkUrl, (120 * density).toInt(), 16 * density)

            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val widthDp = maxOf(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)).coerceAtLeast(260)
                val heightDp = maxOf(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)).coerceAtLeast(320)
                val wPx = (widthDp * density).toInt().coerceAtLeast(400)
                val hPx = (heightDp * density).toInt().coerceAtLeast(500)
                val radiusPx = 12 * density

                val views = RemoteViews(context.packageName, R.layout.widget_media_large)

                val bgBitmap = MediaWidgetArt.renderAppleMusicBackground(context, nowPlayingArt, wPx, hPx, radiusPx)
                views.setImageViewBitmap(R.id.widget_bg, bgBitmap)

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

                val listIntent = Intent(context, WidgetRecentlyPlayedService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                views.setRemoteAdapter(R.id.widget_recent_list, listIntent)

                val playPendingIntent = PendingIntent.getBroadcast(
                    context,
                    200,
                    Intent(context, MediaWidgetActions::class.java).setAction(MediaWidgetActions.ACTION_PLAY_TRACK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                views.setPendingIntentTemplate(R.id.widget_recent_list, playPendingIntent)
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_recent_list)

                val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, true)
                val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                
                views.setOnClickPendingIntent(R.id.now_playing_container, open)
                views.setOnClickPendingIntent(R.id.widget_now_playing_art, open)
                views.setOnClickPendingIntent(R.id.widget_toggle, MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_TOGGLE))
                
                manager.updateAppWidget(id, views)
            }
        }
    }
}
