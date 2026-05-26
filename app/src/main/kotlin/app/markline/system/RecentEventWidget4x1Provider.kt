package app.markline.system

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import app.markline.R
import app.markline.storage.SQLiteEventStore
import app.markline.util.TimeUtil

/**
 * 4x1 最近记录 Widget
 * 展示最近一条签到的时间 + 地址
 */
class RecentEventWidget4x1Provider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val store = SQLiteEventStore(context)
        val latest = store.queryRecent(1).firstOrNull()

        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_recent_4x1)

            if (latest != null) {
                views.setTextViewText(R.id.widget_4x1_time, TimeUtil.formatTime(latest.createdAt))
                views.setTextViewText(
                    R.id.widget_4x1_address,
                    latest.address ?: "位置获取中…"
                )
                views.setTextViewText(
                    R.id.widget_4x1_note,
                    if (!latest.note.isNullOrBlank()) latest.note
                    else latest.audioDuration?.let { "${it / 1000}秒录音" } ?: ""
                )
            }

            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
