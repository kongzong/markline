package app.markline.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import app.markline.MarkLineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Widget 点击后触发的 BroadcastReceiver
 *
 * 职责：
 *  1. 立即震动反馈
 *  2. 在后台协程执行签到（不启动 Service，避免 ANR）
 *  3. Toast 通知用户
 */
class WidgetCheckinReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WIDGET_CHECKIN = "app.markline.ACTION_WIDGET_CHECKIN"
    }

    // BroadcastReceiver 有 10s 窗口，使用 goAsync 延长到 ~30s
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WIDGET_CHECKIN) return

        // 立即震动反馈
        vibrate(context)

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val app = context.applicationContext as MarkLineApp
                app.checkinService.checkin(context)
            } finally {
                pendingResult.finish()
            }
        }

        // Toast 需要在主线程
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, "已签到 ✓", Toast.LENGTH_SHORT).show()
        }
    }

    private fun vibrate(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as VibratorManager
                manager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(
                    VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } catch (_: Exception) {
            // 震动失败不影响签到
        }
    }
}
