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
                Priority.PRIORITY_HIGH_ACCURACY,
                2_000L
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
        // 1. 获取所有 address line，找出最完整的一条
        val lines = (0..<maxAddressLineIndex).mapNotNull { getAddressLine(it) }
            .filter { !it.isNullOrBlank() }

        // 首选：找包含门牌号特征（数字结尾或含 "号/栋/座/层/室"）的行
        val bestLine = lines.firstOrNull { line ->
            line.any { it.isDigit() } || line.any { it in "号栋座层室楼单元幢" }
        } ?: lines.firstOrNull()

        // 如果 bestLine 已经包含门牌号级别的信息，直接返回
        if (!bestLine.isNullOrBlank()) {
            val hasHouseNumber = bestLine.any { it.isDigit() } || 
                                 bestLine.any { it in "号栋座层室楼单元幢" }
            if (hasHouseNumber && bestLine.length >= 5) return bestLine
            // 即使没有明确门牌号但也足够长，也先保留
            if (bestLine.length >= 8) return bestLine
        }

        // 2. 手拼：区/街道 + 道路名 + 门牌号
        val parts = mutableListOf<String>()
        subLocality?.let { parts.add(it) }          // 望京街道
        thoroughfare?.let { road ->                  // 阜通东大街
            val num = extractHouseNumber()
            if (!num.isNullOrBlank()) {
                parts.add("$road$num")
            } else {
                parts.add(road)
            }
        }
        // 3. 如果 hand-pick 也空，尝试 premise + featureName
        if (parts.isEmpty()) {
            val extra = buildString {
                premises?.let { append(it) }         // 夏都盈座
                featureName?.let { append(it) }      // 3号楼
            }
            if (extra.isNotBlank()) parts.add(extra)
        }
        // 4. 最后退到城市+区
        if (parts.isEmpty()) {
            locality?.let { parts.add(it) }
            subAdminArea?.let { parts.add(it) }
        }

        val result = parts.joinToString("")

        // 5. 如果手拼结果还是偏短且有 bestLine，把 bestLine 用作前缀
        return if (result.length < 5 && !bestLine.isNullOrBlank()) {
            if (bestLine.contains(result)) bestLine
            else "$bestLine$result"
        } else result.ifEmpty { bestLine ?: "" }
    }

    /**
     * 从 Address 字段中提取门牌号。
     * 优先从 featureName 获取，其次尝试从 getAddressLine(0) 尾部数字提取。
     */
    private fun Address.extractHouseNumber(): String? {
        // featureName 在中文地址中通常就是门牌号（如 "6号"、"3号楼"）
        featureName?.trim()?.let { if (it.isNotBlank()) return it }

        // 尝试从 address line 提取尾部数字
        val line0 = getAddressLine(0)
        if (!line0.isNullOrBlank()) {
            val match = Regex("""(\d+[号栋座层室楼]?)""").find(line0)
            if (match != null) return match.value
        }
        return null
    }
}
