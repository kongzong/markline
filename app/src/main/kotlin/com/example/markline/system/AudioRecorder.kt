package com.example.markline.system

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录音封装
 * 格式：AAC / M4A
 */
class AudioRecorder(private val context: Context) {

    data class RecordResult(
        val filePath: String,
        val durationMs: Int
    )

    private var isRecording = AtomicBoolean(false)
    private var currentRecorder: MediaRecorder? = null

    /**
     * 录制音频
     */
    suspend fun record(durationSec: Int): RecordResult? = withContext(Dispatchers.IO) {
        // 使用外部私有目录，确保 MediaStore 能扫描到或文件持久化更清晰
        val audioDir = File(context.getExternalFilesDir(null), "audio").also { it.mkdirs() }
        val outFile = File(audioDir, "rec_${System.currentTimeMillis()}.m4a")

        val recorder: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        currentRecorder = recorder
        isRecording.set(true)

        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(64_000)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }

            val startTime = System.currentTimeMillis()
            // 录制时长，支持外部手动停止
            for (i in 0 until (durationSec * 10)) {
                if (!isRecording.get()) break
                delay(100)
            }

            recorder.stop()
            val actualDuration = (System.currentTimeMillis() - startTime).toInt()
            recorder.release()
            currentRecorder = null

            if (outFile.exists() && outFile.length() > 100) {
                RecordResult(outFile.absolutePath, actualDuration)
            } else {
                outFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Record failed", e)
            try { recorder.stop() } catch(_: Exception) {}
            recorder.release()
            currentRecorder = null
            isRecording.set(false)
            outFile.delete()
            null
        }
    }

    /**
     * 手动停止录音
     */
    fun stopRecording() {
        isRecording.set(false)
    }
}
