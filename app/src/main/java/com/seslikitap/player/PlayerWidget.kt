package com.seslikitap.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import com.seslikitap.MainActivity
import com.seslikitap.R

/** Ana ekran widget'ı: kapak, kitap adı, bölüm, oynat/duraklat. */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context)
    }

    companion object {
        private const val PREFS = "widget"

        /** Oynatıcı durumu değişince çağrılır; bilgiler saklanır ki uygulama kapalıyken de gösterilsin. */
        fun update(context: Context, title: String, subtitle: String, coverPath: String?, playing: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("title", title).putString("subtitle", subtitle)
                .putString("cover", coverPath).putBoolean("playing", playing).apply()
            render(context)
        }

        fun render(context: Context) {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setTextViewText(R.id.widget_title, sp.getString("title", null) ?: "Sesli Kitap")
            views.setTextViewText(R.id.widget_subtitle, sp.getString("subtitle", null) ?: "Kaldığın yerden dinle")
            val playing = sp.getBoolean("playing", false)
            views.setImageViewResource(
                R.id.widget_play,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            )
            sp.getString("cover", null)?.let { path ->
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(path, opts)?.let { views.setImageViewBitmap(R.id.widget_cover, it) }
                } catch (_: Throwable) {}
            }
            val toggle = PendingIntent.getForegroundService(
                context, 10,
                Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_play, toggle)
            val open = PendingIntent.getActivity(
                context, 11,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            try {
                AppWidgetManager.getInstance(context).updateAppWidget(ComponentName(context, PlayerWidget::class.java), views)
            } catch (_: Exception) {}
        }
    }
}
