package com.example.markline.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.markline.MainActivity
import com.example.markline.R

/**
 * 2x2 签到 Widget
 * 点击 → 直接触发签到（同 1x1）
 */
class CheckinWidget2x2Provider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_checkin_2x2)

            val intent = Intent(context, WidgetCheckinReceiver::class.java).apply {
                action = WidgetCheckinReceiver.ACTION_WIDGET_CHECKIN
            }
            val pi = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_2x2_root, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
