package com.example.markline.util

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

    companion object {
        private const val KEY_AUDIO     = "enable_audio"
        private const val KEY_AUDIO_DUR = "audio_duration_sec"
        private const val KEY_LOCATION  = "enable_location"
        private const val KEY_VIBRATE   = "enable_vibrate"
    }
}
