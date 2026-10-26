package app.markline

import android.app.Application
import app.markline.domain.CheckinService
import app.markline.storage.SQLiteEventStore
import app.markline.system.LocationService
import app.markline.system.SyncWorker
import app.markline.util.CheckinNotifier
import app.markline.util.PhotoStore
import app.markline.util.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 单例
 * 手动实现轻量级依赖管理（不使用 Hilt/Dagger）
 */
class MarkLineApp : Application() {

    // 应用级协程作用域（启动迁移等后台任务用）
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // 照片本地化迁移：把仍指向外部 content Uri 的照片复制进私有目录（幂等，源已删除自动跳过）
        appScope.launch {
            PhotoStore.migrateContentUris(this@MarkLineApp, eventStore)
        }
        // 云同步：已启用则注册周期任务 + 启动补偿同步一次（WorkManager 自身会判网）
        if (settingsStore.syncEnabled) {
            SyncWorker.schedulePeriodic(this)
            SyncWorker.syncNow(this)
        }
    }
}
