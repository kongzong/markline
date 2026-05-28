package app.markline.system

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 定位服务封装（纯原生 LocationManager，不依赖 Google Play Services）
 *
 * 策略：
 *  1. 优先请求实时位置（GPS → Network 降级）
 *  2. 实时定位失败 → 回退到 120 秒内的缓存位置
 *  3. 20 秒超时（覆盖 GPS 冷启动）
 */
class LocationService(private val context: Context) {

    companion object {
        private const val TAG = "MarkLine/Loc"
        private const val FRESH_TIMEOUT_MS  = 20_000L  // 实时定位超时（GPS 冷启动需 12-30s）
        private const val CACHE_MAX_AGE_MS  = 60_000L  // 缓存有效期（1 分钟）
    }

    private val locationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val address: String?
    )

    /**
     * 获取当前位置。
     * 需要已持有 ACCESS_FINE_LOCATION 权限。
     * 返回 null 表示定位失败。
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): LocationData? {
        // 1) 请求实时位置（优先 GPS，超时后降级 Network）
        val fresh = withTimeoutOrNull(FRESH_TIMEOUT_MS) {
            requestFreshLocation()
        }
        if (fresh != null) {
            Log.d(TAG, "实时定位成功 lat=${fresh.first} lon=${fresh.second}")
            val address = resolveAddress(fresh.first, fresh.second)
            return LocationData(fresh.first, fresh.second, address)
        }

        Log.w(TAG, "实时定位超时/失败，尝试缓存")

        // 2) 回退缓存
        val cached = getLastKnownLocation()
        if (cached != null) {
            val age = System.currentTimeMillis() - cached.third
            if (age < CACHE_MAX_AGE_MS) {
                Log.d(TAG, "使用缓存定位 (${age / 1000}s 前) lat=${cached.first} lon=${cached.second}")
                val address = resolveAddress(cached.first, cached.second)
                return LocationData(cached.first, cached.second, address)
            }
            Log.w(TAG, "缓存过期 (${age / 1000}s > ${CACHE_MAX_AGE_MS / 1000}s)")
        } else {
            Log.w(TAG, "无可用缓存")
        }

        return null
    }

    // ──────────── 缓存定位 ────────────

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Triple<Double, Double, Long>? {
        val lm = locationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: android.location.Location? = null
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null && (best == null || loc.time > best!!.time)) {
                    best = loc
                }
            } catch (e: Exception) {
                Log.w(TAG, "getLastKnownLocation($provider): ${e.message}")
            }
        }
        return best?.let { Triple(it.latitude, it.longitude, it.time) }
    }

    // ──────────── 实时定位 ────────────

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocation(): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            val lm = locationManager
            if (lm == null) {
                Log.e(TAG, "LocationManager 不可用")
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }

            if (providers.isEmpty()) {
                Log.e(TAG, "无可用定位提供者（GPS 和网络定位均未开启）")
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            Log.d(TAG, "可用提供者: $providers")

            var resolved = false

            for (provider in providers) {
                if (resolved) break

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+：使用 getCurrentLocation（专为单次定位设计）
                    Log.d(TAG, "请求 $provider 实时定位...")
                    lm.getCurrentLocation(
                        provider,
                        null,
                        context.mainExecutor
                    ) { location ->
                        if (!resolved) {
                            if (location != null) {
                                resolved = true
                                Log.d(TAG, "$provider 返回定位 lat=${location.latitude} lon=${location.longitude}")
                                cont.resume(Pair(location.latitude, location.longitude))
                            } else {
                                // 修复：GPS 超时返回 null 时，也要推进逻辑
                                Log.d(TAG, "$provider 返回空（无信号或超时）")
                            }
                        }
                    }
                } else {
                    // Android 10
                    @Suppress("DEPRECATION")
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            if (!resolved) {
                                resolved = true
                                Log.d(TAG, "$provider requestSingleUpdate 返回定位")
                                cont.resume(Pair(location.latitude, location.longitude))
                            }
                            lm.removeUpdates(this)
                        }
                        override fun onProviderDisabled(provider: String) {}
                        override fun onProviderEnabled(provider: String) {}
                    }
                    Log.d(TAG, "请求 $provider requestSingleUpdate...")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }

            cont.invokeOnCancellation {
                resolved = true
            }
        }

    // ──────────── 逆地理编码 ────────────

    private suspend fun resolveAddress(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder 不可用")
            return null
        }
        return try {
            withTimeoutOrNull(3_000L) {
                suspendCancellableCoroutine { cont ->
                    val geocoder = Geocoder(context, Locale.getDefault())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        geocoder.getFromLocation(lat, lon, 1) { addresses ->
                            cont.resume(addresses.firstOrNull()?.toDisplayString())
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(lat, lon, 1)
                        cont.resume(addresses?.firstOrNull()?.toDisplayString())
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "逆地理编码失败: ${e.message}")
            null
        }
    }

    private fun Address.toDisplayString(): String {
        val lines = (0..<maxAddressLineIndex).mapNotNull { getAddressLine(it) }
            .filter { !it.isNullOrBlank() }

        val bestLine = lines.firstOrNull { line ->
            line.any { it.isDigit() } || line.any { it in "号栋座层室楼单元幢" }
        } ?: lines.firstOrNull()

        if (!bestLine.isNullOrBlank()) {
            val hasHouseNumber = bestLine.any { it.isDigit() } ||
                                 bestLine.any { it in "号栋座层室楼单元幢" }
            if (hasHouseNumber && bestLine.length >= 5) return bestLine
            if (bestLine.length >= 8) return bestLine
        }

        val parts = mutableListOf<String>()
        subLocality?.let { parts.add(it) }
        thoroughfare?.let { road ->
            val num = extractHouseNumber()
            if (!num.isNullOrBlank()) {
                parts.add("$road$num")
            } else {
                parts.add(road)
            }
        }
        if (parts.isEmpty()) {
            val extra = buildString {
                premises?.let { append(it) }
                featureName?.let { append(it) }
            }
            if (extra.isNotBlank()) parts.add(extra)
        }
        if (parts.isEmpty()) {
            locality?.let { parts.add(it) }
            subAdminArea?.let { parts.add(it) }
        }

        val result = parts.joinToString("")
        return if (result.length < 5 && !bestLine.isNullOrBlank()) {
            if (bestLine.contains(result)) bestLine
            else "$bestLine$result"
        } else result.ifEmpty { bestLine ?: "" }
    }

    private fun Address.extractHouseNumber(): String? {
        featureName?.trim()?.let { if (it.isNotBlank()) return it }
        val line0 = getAddressLine(0)
        if (!line0.isNullOrBlank()) {
            val match = Regex("""(\d+[号栋座层室楼]?)""").find(line0)
            if (match != null) return match.value
        }
        return null
    }
}
