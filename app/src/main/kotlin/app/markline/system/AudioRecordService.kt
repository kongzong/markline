package app.markline.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.Service
import app.markline.R
import app.markline.MainActivity
import java.io.File

/**
 * 前台录音服务 —— 保障后台 / 锁屏时录音不被系统杀掉
 *
 * 启动方式：
 *   val intent = Intent(context, AudioRecordService::class.java)
 *   ContextCompat.startForegroundService(context, intent)
 *
 * 停止方式：
 *   val stopIntent = Intent(context, AudioRecordService::class.java).apply { action = ACTION_STOP }
 *   context.startService(stopIntent)
 */
class AudioRecordService : Service() {

    companion object {
        const val CHANNEL_ID   = "markline_audio_recording"
        const val NOTIF_ID     = 10002
        const val ACTION_STOP   = "app.markline.ACTION_STOP_RECORD"
        const val EXTRA_DURATION_SEC = "extra_duration_sec"

        /** 录音输出文件路径（进程内共享，调用方读取后可置 null 重置） */
        @Volatile
        var outputFilePath: String? = null
    }

    private var recorder: MediaRecorder? = null
    private var durationSec = 30

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }

        durationSec = intent?.getIntExtra(EXTRA_DURATION_SEC, 30) ?: 30

        startForeground(NOTIF_ID, buildNotification("正在录音…"))
        startRecording()

        // 超时自动停止
        Thread {
            Thread.sleep(durationSec * 1000L)
            if (recorder != null) {
                stopRecording()
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 录音控制 ─────────────────────────────────────

    private fun startRecording() {
        val outFile = File(filesDir, "audio/rec_${System.currentTimeMillis()}.m4a").also {
            it.parentFile?.mkdirs()
        }
        outputFilePath = outFile.absolutePath

        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder = r
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(outFile.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            Log.e("AudioRecordService", "startRecording failed", e)
            stopSelf()
        }
    }

    private fun stopRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    // ── 通知 ─────────────────────────────────────────

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MarkLine 录音中")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)   // 不发出声音
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "录音服务",
            NotificationManager.IMPORTANCE_LOW     // 不弹出、无声音
        ).apply { description = "MarkLine 后台录音通知" }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
