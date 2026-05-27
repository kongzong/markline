package app.markline.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.markline.MainActivity
import app.markline.R

/**
 * 1x1 快速签到 Widget
 * 点击 → PendingIntent.getActivity → MainActivity → 触发签到（同主页 Mark 按钮）
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

            // 点击按钮 → 打开 App 并触发签到（保证可靠送达，同主页 Mark 体验）
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_TRIGGER_CHECKIN, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_checkin, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
