package com.example.markline

import android.app.Application
import com.example.markline.domain.CheckinService
import com.example.markline.storage.SQLiteEventStore
import com.example.markline.system.AudioRecorder
import com.example.markline.system.LocationService
import com.example.markline.util.CheckinNotifier
import com.example.markline.util.SettingsStore

/**
 * Application 单例
 * 手动实现轻量级依赖管理（不使用 Hilt/Dagger）
 */
class MarkLineApp : Application() {

    // 懒加载单例，应用生命周期内共享
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
    val eventStore: SQLiteEventStore by lazy { SQLiteEventStore(this) }
    val locationService: LocationService by lazy { LocationService(this) }
    val audioRecorder: AudioRecorder by lazy { AudioRecorder(this) }

    val checkinService: CheckinService by lazy {
        CheckinService(
            eventStore = eventStore,
            locationService = locationService,
            audioRecorder = audioRecorder,
            settingsStore = settingsStore
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 创建通知渠道（Android 8+ 必须，多次调用安全）
        CheckinNotifier.createChannel(this)
    }
}
