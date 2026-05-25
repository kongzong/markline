package com.example.markline.domain

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.markline.system.AudioRecorder
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
    private val audioRecorder:   AudioRecorder,
    private val settingsStore:   SettingsStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
     * 手动提前结束录音
     */
    fun completeAudioEarly() {
        audioRecorder.stopRecording()
    }

    /**
     * 执行签到。
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

        scope.launch {
            var finalStatus  = Event.STATUS_DONE
            var finalAddress : String? = null
            var hasAudio     = false

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

            // 2. 录音 (带倒计时)
            if (enableAudio && hasAudioPerm) {
                _status.value = _status.value.copy(audioStatus = "录音中", canCompleteAudio = true)
                
                // 启动异步录音
                val audioJob = async { audioRecorder.record(audioDuration) }
                
                // 模拟倒计时更新 UI
                for (i in audioDuration downTo 1) {
                    if (!audioJob.isActive) break // 如果录音提前结束
                    _status.value = _status.value.copy(audioStatus = "录音中(${i}s)", countdown = i)
                    delay(1000)
                }
                
                val audioResult = audioJob.await()
                _status.value = _status.value.copy(countdown = 0, canCompleteAudio = false)

                if (audioResult != null) {
                    hasAudio = true
                    eventStore.updateEnhancement(
                        id            = eventId,
                        audioFileName = audioResult.fileName,
                        audioDuration = audioResult.durationMs
                    )
                    _status.value = _status.value.copy(audioStatus = "已录音")
                } else {
                    if (finalStatus == Event.STATUS_DONE) finalStatus = Event.STATUS_AUDIO_FAILED
                    eventStore.updateEnhancement(id = eventId, status = Event.STATUS_AUDIO_FAILED)
                    _status.value = _status.value.copy(audioStatus = "录音失败")
                }
            }

            if (finalStatus == Event.STATUS_DONE) {
                eventStore.updateEnhancement(id = eventId, status = Event.STATUS_DONE)
            }

            // 完成
            val timeLabel = TimeUtil.formatTime(now)
            CheckinNotifier.notify(context, eventId, timeLabel, finalAddress, hasAudio, finalStatus)
            
            delay(1000)
            _status.value = CheckinStatus(isProcessing = false)
        }

        return eventId
    }
}
