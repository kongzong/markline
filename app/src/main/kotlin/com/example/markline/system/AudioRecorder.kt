package com.example.markline.system

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
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
        val fileName: String,
        val durationMs: Int
    )

    private var isRecording = AtomicBoolean(false)
    private var currentRecorder: MediaRecorder? = null

    /**
     * 录制音频
     */
    suspend fun record(durationSec: Int): RecordResult? = withContext(Dispatchers.IO) {
        val fileName = "rec_${System.currentTimeMillis()}.m4a"
        val audioDir = File(context.getExternalFilesDir(null), "audio").also { it.mkdirs() }
        val outFile = File(audioDir, fileName)

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
                // 注册到 MediaStore，让其他 App 也能搜到
                registerToMediaStore(context, fileName, outFile)
                RecordResult(fileName, actualDuration)
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

    // ── 私有工具 ──────────────────────────────────────────

    /** 将录音文件注册到 MediaStore，让其他 App 可发现 */
    private fun registerToMediaStore(context: Context, fileName: String, file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MarkLine")
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
            )
        } catch (e: Exception) {
            Log.w("AudioRecorder", "MediaStore register failed", e)
            // 注册失败不影响主流程
        }
    }
}
