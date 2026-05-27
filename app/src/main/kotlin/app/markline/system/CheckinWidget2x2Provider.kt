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
 * 2x2 签到 Widget
 * 点击 → PendingIntent.getActivity → MainActivity → 触发签到（同主页 Mark 按钮）
 */
class CheckinWidget2x2Provider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_checkin_2x2)

            // 点击整个 widget → 打开 App 并触发签到
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_TRIGGER_CHECKIN, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_2x2_root, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
