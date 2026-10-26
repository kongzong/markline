package app.markline.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户设置存储（SharedPreferences 同步读写）
 * 避免 Compose 中为简单 bool 引入 collectAsState 复杂性
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("qc_settings", Context.MODE_PRIVATE)

    var audioEnabled: Boolean
        get()      = prefs.getBoolean(KEY_AUDIO, true)
        set(value) { prefs.edit().putBoolean(KEY_AUDIO, value).apply() }

    var audioDurationSec: Int
        get()      = prefs.getInt(KEY_AUDIO_DUR, 30)
        set(value) { prefs.edit().putInt(KEY_AUDIO_DUR, value.coerceIn(5, 300)).apply() }

    var locationEnabled: Boolean
        get()      = prefs.getBoolean(KEY_LOCATION, true)
        set(value) { prefs.edit().putBoolean(KEY_LOCATION, value).apply() }

    var vibrateEnabled: Boolean
        get()      = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) { prefs.edit().putBoolean(KEY_VIBRATE, value).apply() }

    /** 备份画质：false=完整原图（默认，凭证保真），true=轻量压缩（长边 1600px，包体小 10 倍+） */
    var backupPhotoLite: Boolean
        get()      = prefs.getBoolean(KEY_BACKUP_PHOTO_LITE, false)
        set(value) { prefs.edit().putBoolean(KEY_BACKUP_PHOTO_LITE, value).apply() }

    // ---- 云同步（WebDAV）----

    /** 是否启用自动云同步 */
    var syncEnabled: Boolean
        get()      = prefs.getBoolean(KEY_SYNC_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_SYNC_ENABLED, value).apply() }

    /** WebDAV 服务器根地址，如 https://dav.jianguoyun.com/dav/ */
    var webdavUrl: String
        get()      = prefs.getString(KEY_WEBDAV_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_WEBDAV_URL, value).apply() }

    /** WebDAV 账号 */
    var webdavUser: String
        get()      = prefs.getString(KEY_WEBDAV_USER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_WEBDAV_USER, value).apply() }

    /** WebDAV 密码（坚果云为应用密码）。明文本地存储，不出设备 */
    var webdavPass: String
        get()      = prefs.getString(KEY_WEBDAV_PASS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_WEBDAV_PASS, value).apply() }

    /** 最近一次成功同步时间戳；0 = 从未同步 */
    var lastSyncAt: Long
        get()      = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_SYNC_AT, value).apply() }

    companion object {
        private const val KEY_AUDIO     = "enable_audio"
        private const val KEY_AUDIO_DUR = "audio_duration_sec"
        private const val KEY_LOCATION  = "enable_location"
        private const val KEY_VIBRATE   = "enable_vibrate"
        private const val KEY_BACKUP_PHOTO_LITE = "backup_photo_lite"
        private const val KEY_SYNC_ENABLED  = "sync_enabled"
        private const val KEY_WEBDAV_URL    = "webdav_url"
        private const val KEY_WEBDAV_USER   = "webdav_user"
        private const val KEY_WEBDAV_PASS   = "webdav_pass"
        private const val KEY_LAST_SYNC_AT  = "last_sync_at"
    }
}
