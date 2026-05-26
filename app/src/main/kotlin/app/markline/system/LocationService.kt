package app.markline.system

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 定位服务封装
 *
 * 策略：
 *  1. 优先使用 lastKnownLocation（毫秒级返回）
 *  2. 失败则发起一次实时定位请求，超时 5 秒
 *  3. 签到主流程不等待此结果，异步更新 Event
 */
class LocationService(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val address: String?
    )

    /**
     * 获取当前位置。需要已持有 ACCESS_FINE_LOCATION 权限。
     * 返回 null 表示定位失败。
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): LocationData? {
        // 优先使用缓存定位
        val cached = withTimeoutOrNull(2_000L) {
            getLastKnownLocation()
        }
        if (cached != null) {
            val address = resolveAddress(cached.first, cached.second)
            return LocationData(cached.first, cached.second, address)
        }

        // 退而求其次：实时定位，超时 8 秒
        val fresh = withTimeoutOrNull(8_000L) {
            requestFreshLocation()
        } ?: return null

        val address = resolveAddress(fresh.first, fresh.second)
        return LocationData(fresh.first, fresh.second, address)
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocation(): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            fusedClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        cont.resume(Pair(location.latitude, location.longitude))
                    } else {
                        cont.resume(null)
                    }
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocation(): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                1_000L
            ).setMaxUpdates(1).build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    val loc = result.lastLocation
                    fusedClient.removeLocationUpdates(this)
                    if (loc != null) {
                        cont.resume(Pair(loc.latitude, loc.longitude))
                    } else {
                        cont.resume(null)
                    }
                }
            }

            cont.invokeOnCancellation {
                fusedClient.removeLocationUpdates(callback)
            }

            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }

    /**
     * 使用 Android Geocoder 逆地理编码
     * 网络不可用时 Geocoder 可能返回空，正常返回 null 即可
     */
    private suspend fun resolveAddress(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
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
            null
        }
    }

    private fun Address.toDisplayString(): String {
        // 1. 优先使用 getAddressLine(0)，Google Geocoder 返回的中文地址通常最完整
        val line0 = getAddressLine(0)
        if (!line0.isNullOrBlank() && line0.length >= 3) return line0

        // 2. 手拼：区/街道 + 道路名 + 门牌号（适合国内地址 xx路xx号）
        val parts = mutableListOf<String>()
        subLocality?.let { parts.add(it) }          // 望京街道
        thoroughfare?.let { road ->                  // 阜通东大街
            val num = featureName?.trim()            // 6号 / 6
            if (!num.isNullOrBlank()) {
                parts.add("$road$num")
            } else {
                parts.add(road)
            }
        }
        // 3. 如果手拼也是空的，退到城市+区
        if (parts.isEmpty()) {
            locality?.let { parts.add(it) }
            subAdminArea?.let { parts.add(it) }
        }
        return parts.joinToString("")
    }
}
