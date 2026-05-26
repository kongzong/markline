package app.markline

import android.app.Application
import app.markline.domain.CheckinService
import app.markline.storage.SQLiteEventStore
import app.markline.system.LocationService
import app.markline.util.CheckinNotifier
import app.markline.util.SettingsStore

/**
 * Application 单例
 * 手动实现轻量级依赖管理（不使用 Hilt/Dagger）
 */
class MarkLineApp : Application() {

    // 懒加载单例，应用生命周期内共享
    val settingsStore: SettingsStore  by lazy { SettingsStore(this) }
    val eventStore:    SQLiteEventStore by lazy { SQLiteEventStore(this) }
    val locationService: LocationService by lazy { LocationService(this) }

    val checkinService: CheckinService by lazy {
        CheckinService(
            eventStore    = eventStore,
            locationService = locationService,
            settingsStore  = settingsStore
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 创建通知渠道（Android 8+ 必须，多次调用安全）
        CheckinNotifier.createChannel(this)
    }
}
