package com.example.markline.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.markline.system.AudioRecordService
import com.example.markline.system.LocationService
import com.example.markline.util.CheckinNotifier
import com.example.markline.util.SettingsStore
import com.example.markline.util.TimeUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 核心签到服务
 */
class CheckinService(
    private val eventStore:      EventStore,
    private val locationService: LocationService,
    private val settingsStore:   SettingsStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentCheckinJob: Job? = null

    // 实时状态
    data class CheckinStatus(
        val isProcessing: Boolean = false,
        val locationStatus: String = "等待定位",
        val audioStatus: String = "等待录音",
        val countdown: Int = 0,
        val canCompleteAudio: Boolean = false
    )

    private val _status = MutableStateFlow(CheckinStatus())
    val status: StateFlow<CheckinStatus> = _status.asStateFlow()

    /**
     * 手动提前结束录音（停止前台服务 + 取消倒计时协程）
     */
    fun completeAudioEarly(context: Context) {
        val stopIntent = Intent(context, AudioRecordService::class.java).apply {
            action = AudioRecordService.ACTION_STOP
        }
        context.startService(stopIntent)
        // 取消当前签到协程，使其立即进入文件轮询阶段
        currentCheckinJob?.cancel()
    }

    /**
     * 执行签到。
     * 录音通过前台服务 AudioRecordService 完成，保障后台/锁屏继续录音。
     */
    fun checkin(context: Context): Long {
        if (_status.value.isProcessing) return -1L

        val now = System.currentTimeMillis()
        val eventId = eventStore.insert(
            Event(createdAt = now, status = Event.STATUS_CREATED)
        )

        val enableLocation = settingsStore.locationEnabled
        val enableAudio    = settingsStore.audioEnabled
        val audioDuration  = settingsStore.audioDurationSec

        val hasLocationPerm = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasAudioPerm = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        _status.value = CheckinStatus(
            isProcessing = true,
            locationStatus = if (enableLocation) "定位中" else "未开启",
            audioStatus = if (enableAudio) "等待录音" else "未开启",
            countdown = if (enableAudio) audioDuration else 0,
            canCompleteAudio = false
        )

        currentCheckinJob = scope.launch {
            try {
            var finalStatus  = Event.STATUS_DONE
            var finalAddress : String? = null
            var hasAudio     = false
            var audioFileName: String? = null
            var audioDurationMs: Int = 0

            // 1. 定位
            if (enableLocation && hasLocationPerm) {
                val locationResult = locationService.getCurrentLocation()
                if (locationResult != null) {
                    finalAddress = locationResult.address
                    eventStore.updateEnhancement(
                        id        = eventId,
                        latitude  = locationResult.latitude,
                        longitude = locationResult.longitude,
                        address   = locationResult.address
                    )
                    _status.value = _status.value.copy(locationStatus = "已获取")
                } else {
                    finalStatus = Event.STATUS_LOCATION_FAILED
                    eventStore.updateEnhancement(id = eventId, status = Event.STATUS_LOCATION_FAILED)
                    _status.value = _status.value.copy(locationStatus = "定位失败")
                }
            }

            // 2. 录音（通过前台服务，后台/锁屏保活）
            if (enableAudio && hasAudioPerm) {
                _status.value = _status.value.copy(audioStatus = "录音中", canCompleteAudio = true)

                // 启动前台录音服务（后台/锁屏保活）
                val audioIntent = Intent(context, AudioRecordService::class.java).apply {
                    putExtra(AudioRecordService.EXTRA_DURATION_SEC, audioDuration)
                }
                ContextCompat.startForegroundService(context, audioIntent)

                // 倒计时更新 UI（可被 completeAudioEarly 提前取消）
                val startTime = System.currentTimeMillis()
                try {
                    for (i in audioDuration downTo 1) {
                        _status.value = _status.value.copy(audioStatus = "录音中(${i}s)", countdown = i)
                        delay(1000)
                    }
                } catch (_: CancellationException) {
                    // 用户提前结束录音，更新状态后继续处理
                    _status.value = _status.value.copy(audioStatus = "提前结束", countdown = 0)
                }

                // 后续操作不受取消影响（文件轮询 + 结果写入）
                withContext(NonCancellable) {
                    // 等待前台服务写入文件（服务在超时或手动停止后自动退出）
                    // 轮询输出文件路径，最多等 3 秒
                    var outFile: java.io.File? = null
                    repeat(30) {
                        val path = AudioRecordService.outputFilePath
                        if (path != null) {
                            outFile = java.io.File(path)
                            if (outFile.exists()) return@repeat
                        }
                        delay(100)
                    }

                    _status.value = _status.value.copy(countdown = 0, canCompleteAudio = false)

                    // 读取录音结果
                    val path = AudioRecordService.outputFilePath
                    if (path != null) {
                        outFile = java.io.File(path)
                        if (outFile.exists() && outFile.length() > 100) {
                            hasAudio      = true
                            audioFileName = outFile.name
                            audioDurationMs = (System.currentTimeMillis() - startTime).toInt()
                            eventStore.updateEnhancement(
                                id            = eventId,
                                audioFileName = audioFileName,
                                audioDuration = audioDurationMs
                            )
                            _status.value = _status.value.copy(audioStatus = "已录音")
                        } else {
                            if (finalStatus == Event.STATUS_DONE) finalStatus = Event.STATUS_AUDIO_FAILED
                            eventStore.updateEnhancement(id = eventId, status = Event.STATUS_AUDIO_FAILED)
                            _status.value = _status.value.copy(audioStatus = "录音失败")
                        }
                    } else {
                        if (finalStatus == Event.STATUS_DONE) finalStatus = Event.STATUS_AUDIO_FAILED
                        eventStore.updateEnhancement(id = eventId, status = Event.STATUS_AUDIO_FAILED)
                        _status.value = _status.value.copy(audioStatus = "录音失败")
                    }

                    // 重置静态变量
                    AudioRecordService.outputFilePath = null
                }
            }

            if (finalStatus == Event.STATUS_DONE) {
                eventStore.updateEnhancement(id = eventId, status = Event.STATUS_DONE)
            }

            // 完成通知
            val timeLabel = TimeUtil.formatTime(now)
            try {
                CheckinNotifier.notify(context, eventId, timeLabel, finalAddress, hasAudio, finalStatus)
            } catch (_: Exception) {
                // 通知失败不影响签到完成
            }

            delay(1000)
            } finally {
                _status.value = CheckinStatus(isProcessing = false)
            }
        }

        return eventId
    }
}
