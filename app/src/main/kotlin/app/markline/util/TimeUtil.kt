package app.markline.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeUtil {

    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormatter = SimpleDateFormat("M月d日", Locale.getDefault())
    private val fullFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 仅显示时分，如 "14:32" */
    fun formatTime(millis: Long): String = timeFormatter.format(Date(millis))

    /** 显示月日，如 "5月25日" */
    fun formatDate(millis: Long): String = dateFormatter.format(Date(millis))

    /** 完整时间 */
    fun formatFull(millis: Long): String = fullFormatter.format(Date(millis))

    /** 日期 + 时间，如 "5月25日 14:32" */
    fun formatDateTime(millis: Long): String = "${formatDate(millis)} ${formatTime(millis)}"

    /** 用于分组的日期 key，如 "2026-05-25" */
    fun toDayKey(millis: Long): String = dayFormatter.format(Date(millis))

    /** 友好时间标签，今天/昨天/具体日期 */
    fun friendlyDate(millis: Long): String {
        val todayKey = toDayKey(System.currentTimeMillis())
        val targetKey = toDayKey(millis)
        return when (targetKey) {
            todayKey -> "今天"
            else -> {
                val yesterday = toDayKey(System.currentTimeMillis() - 86400_000L)
                if (targetKey == yesterday) "昨天" else formatDate(millis)
            }
        }
    }
}
