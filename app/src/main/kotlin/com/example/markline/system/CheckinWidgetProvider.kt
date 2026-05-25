package com.example.markline.system

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.markline.R

/**
 * 1x1 快速签到 Widget
 * 点击 → PendingIntent → WidgetCheckinReceiver → CheckinService
 */
class CheckinWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_checkin)

            // 点击按钮 → 发送 Broadcast 给 WidgetCheckinReceiver
            val intent = Intent(context, WidgetCheckinReceiver::class.java).apply {
                action = WidgetCheckinReceiver.ACTION_WIDGET_CHECKIN
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_checkin, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
