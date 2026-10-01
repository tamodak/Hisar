package org.tamodak.hisar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.tamodak.hisar.MainActivity
import org.tamodak.hisar.R

/**
 * Chrome's home-screen search bar: tap the box to search, the microphone to speak, the camera to
 * search with an image. Each opens Hisar on a new tab, so everything passes through its filters.
 */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_search).apply {
            setOnClickPendingIntent(android.R.id.background, open(context, MainActivity.ACTION_SEARCH, 0))
            setOnClickPendingIntent(R.id.widget_voice, open(context, MainActivity.ACTION_VOICE_SEARCH, 1))
            setOnClickPendingIntent(R.id.widget_image, open(context, MainActivity.ACTION_IMAGE_SEARCH, 2))
        }
        manager.updateAppWidget(ids, views)
    }

    private fun open(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
