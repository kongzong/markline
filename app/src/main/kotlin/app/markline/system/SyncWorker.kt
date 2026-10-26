package app.markline.system

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.markline.util.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * 云同步后台任务（WorkManager 周期 + 补偿触发）。
 *
 * WorkManager 随 AndroidX 自带、无 GMS 依赖；约束 CONNECTED，仅在联网时执行。
 * 自动策略：每 6 小时周期同步 + App 启动/设置变更时补偿一次。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? app.markline.MarkLineApp ?: return Result.failure()
        val settings = app.settingsStore
        if (!settings.syncEnabled) return Result.success()

        return when {
            SyncManager.sync(applicationContext, settings, app.eventStore).ok -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure() // 静默放弃本轮，下个周期再试
        }
    }

    companion object {

        private const val PERIODIC_WORK = "markline_sync_periodic"
        private const val ONESHOT_WORK = "markline_sync_oneshot"
        private const val DEBOUNCE_WORK = "markline_sync_debounce"

        /**
         * 数据变更触发（签到/导入/编辑/删除后调用）。
         * 延迟 20 秒执行且 REPLACE 去抖——连续多条操作只汇成一次同步，
         * 既让用户「改完就同步走」的实时感，又不密集打网络。
         */
        fun onDataChanged(context: Context) {
            val app = context.applicationContext as? app.markline.MarkLineApp ?: return
            if (!app.settingsStore.syncEnabled) return
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(20, TimeUnit.SECONDS)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                DEBOUNCE_WORK, ExistingWorkPolicy.REPLACE, request
            )
        }

        /** 注册每 6 小时的周期同步（幂等，重复调用安全） */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /** 触发一次立即同步（手动按钮 / 补偿） */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_WORK, ExistingWorkPolicy.REPLACE, request
            )
        }

        /** 取消全部同步任务（用户关闭云同步时调用） */
        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(PERIODIC_WORK)
            wm.cancelUniqueWork(ONESHOT_WORK)
        }
    }
}
