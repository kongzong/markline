package com.example.markline.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.markline.MainActivity

/**
 * 签到完成后台通知工具
 *
 * 用途：后台异步增强（定位/录音）全部结束后，发送一条系统通知，
 *       用户点击通知 → 打开 MainActivity 并跳转到对应记录详情页。
 */
object CheckinNotifier {

    private const val CHANNEL_ID   = "markline_checkin"
    private const val CHANNEL_NAME = "签到通知"
    private const val CHANNEL_DESC = "Mark 完成后，提醒你检查记录完整性"

    /** 初始化通知渠道（Android 8+必须）；在 Application.onCreate 中调用一次即可 */
    fun createChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESC
            enableVibration(false)   // 通知本身不震动，签到时已震动过
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * 发送签到完成通知
     *
     * @param context     Context
     * @param eventId     对应 Event.id，用于通知 deep-link 到详情页
     * @param timeLabel   格式化好的时间字符串（如 "14:32"）
     * @param address     地址文本，null 时显示占位文案
     * @param hasAudio    是否已有录音
     * @param status      Event.status，决定通知摘要文案
     */
    fun notify(
        context:   Context,
        eventId:   Long,
        timeLabel: String,
        address:   String?,
        hasAudio:  Boolean,
        status:    Int
    ) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 点击通知跳转到详情页
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_EVENT_ID, eventId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            eventId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 根据状态生成摘要
        val summary = buildSummary(address, hasAudio, status)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)   // 替换为自定义图标后效果更好
            .setContentTitle("$timeLabel  已 Mark ✓")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        mgr.notify(eventId.toInt(), notification)
    }

    private fun buildSummary(address: String?, hasAudio: Boolean, status: Int): String {
        val parts = mutableListOf<String>()

        when {
            address != null -> parts.add("📍 $address")
            status == 2     -> parts.add("📍 定位失败")
            else            -> parts.add("📍 位置获取中…")
        }

        if (hasAudio) {
            parts.add("🎤 录音已保存")
        } else if (status == 3) {
            parts.add("🎤 录音失败")
        }

        parts.add("点击检查记录完整性 →")
        return parts.joinToString("  ")
    }
}
