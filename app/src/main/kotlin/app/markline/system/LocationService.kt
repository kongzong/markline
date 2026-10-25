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
        private const val GEOCODE_TIMEOUT_MS = 3_000L  // 单次逆地理编码超时
        private const val PROBE_TIMEOUT_MS   = 4_500L  // 漂移探测总预算
        private const val PROBE_STEP_TIMEOUT_MS = 1_200L // 单个探测点超时
        private const val MAX_DRIFT_PROBES   = 14      // 漂移探测次数上限
        private const val PRECISION_ROAD     = 3       // 道路级精度阈值（低于则触发漂移）
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

    /**
     * 反向地理编码（供导入照片等「已有经纬度但缺地址」的场景补全地址）。
     * 无网络/解析失败返回 null，调用方应据此回退展示。
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? = resolveAddress(lat, lon)

    /**
     * 逆地理编码 + 周边漂移：
     * 原始点只有粗粒度（如「贵溪市」）时，在周边 80/200/400 米螺旋探测道路级地址，
     * 命中则地址文本加「附近」及距离。坐标本身永不改动，只借用地址描述。
     * 探测超时/限流/跨区县时静默回退原粗地址，不影响签到主流程。
     */
    private suspend fun resolveAddress(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder 不可用")
            return null
        }
        return try {
            val list = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { geocodeOnce(lat, lon, 3) }
            val best = pickBest(list) ?: run {
                Log.w(TAG, "逆地理编码无结果或超时")
                return null
            }
            val p0 = precisionOf(best)
            Log.d(TAG, "逆地理编码(显示串分级) 精度=$p0 地址=${best.toDisplayString()}")
            Log.d(TAG, "字段: featureName=${best.featureName}, thoroughfare=${best.thoroughfare}, " +
                "subThoroughfare=${best.subThoroughfare}, subLocality=${best.subLocality}, " +
                "locality=${best.locality}, subAdminArea=${best.subAdminArea}, " +
                "premises=${best.premises}, " +
                "lines=${(0 until best.maxAddressLineIndex).map { best.getAddressLine(it) }}")
            if (p0 >= PRECISION_ROAD) {
                return best.toDisplayString()
            }
            // 粗粒度：周边螺旋探测，无命中回退原地址
            val drift = withTimeoutOrNull(PROBE_TIMEOUT_MS) { probeNearby(lat, lon, best) }
            if (drift != null) {
                val display = drift.address.toDisplayString()
                val suffix = if (drift.distanceMeters >= 100) "附近（约${drift.distanceMeters}米）" else "附近"
                Log.d(TAG, "漂移命中: $display$suffix")
                "$display$suffix"
            } else {
                Log.d(TAG, "漂移未命中，回退原地址: ${best.toDisplayString()}")
                best.toDisplayString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "逆地理编码失败: ${e.message}")
            null
        }
    }

    /** 单点逆地理编码（一次网络返回最多 maxResults 个候选） */
    private suspend fun geocodeOnce(lat: Double, lon: Double, maxResults: Int): List<Address>? {
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, maxResults) { addresses ->
                    if (cont.isActive) cont.resume(addresses)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, maxResults)
        }
    }

    /** 从多个候选里挑精度最高的（并列取第一个，通常相关度最高） */
    private fun pickBest(list: List<Address>?): Address? =
        list?.maxByOrNull { precisionOf(it) }

    /**
     * 精度分级（以最终显示串为准，Address 字段只兜底）：
     * 4 门牌 > 3 道路/POI > 2 乡镇/街道 > 1 区县 > 0 无
     * 关键教训：Geocoder 会把行政名塞进 featureName/subThoroughfair
     * （如「贵溪市」），按字段分级会被骗过；只信显示串里的证据字，
     * 用户看到什么就按什么分级——「贵溪市」没有道路/门牌证据就是粗粒度。
     */
    private fun precisionOf(a: Address): Int {
        val display = a.toDisplayString()
        if (display.isEmpty()) return 0
        if (display.any { it in "号栋座层室楼单元幢" }) return 4
        if (hasRoadEvidence(display)) return 3
        if (!a.subLocality.isNullOrBlank()) return 2
        if (!a.locality.isNullOrBlank() || !a.subAdminArea.isNullOrBlank()) return 1
        return 0
    }

    /** 显示串里是否有道路/POI 证据字（防「贵溪市」这类纯行政名滥竽充数） */
    private fun hasRoadEvidence(s: String): Boolean {
        val chars = "路街巷弄道桥号栋座层室楼厦园场站馆院店厂矿村庄镇乡组庄幢学区"
        return s.any { it in chars }
    }

    private data class ProbeResult(val address: Address, val distanceMeters: Int)

    /**
     * 周边螺旋探测：80m×4 → 200m×8 → 500m×8，最多 MAX_DRIFT_PROBES 次。
     * 只接受与原点不冲突（同区县，或任一侧缺失）的道路级地址。
     * 探测异常（限流等）立即放弃，回退原地址。
     */
    private suspend fun probeNearby(lat: Double, lon: Double, origin: Address): ProbeResult? {
        val rings = listOf(
            80 to floatArrayOf(0f, 90f, 180f, 270f),
            200 to floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f),
            500 to floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
        )
        var probes = 0
        for ((radius, bearings) in rings) {
            for (b in bearings) {
                if (probes >= MAX_DRIFT_PROBES) {
                    Log.d(TAG, "漂移探测达上限 $MAX_DRIFT_PROBES 次，放弃")
                    return null
                }
                probes++
                val (plat, plon) = offsetByMeters(lat, lon, radius, b)
                val found = try {
                    withTimeoutOrNull(PROBE_STEP_TIMEOUT_MS) { geocodeOnce(plat, plon, 1) }?.firstOrNull()
                } catch (e: Exception) {
                    Log.w(TAG, "漂移探测中止: ${e.message}")
                    return null
                }
                if (found == null) {
                    Log.d(TAG, "漂移探测 ${radius}m@${b.toInt()}° → 无结果")
                    continue
                }
                val p = precisionOf(found)
                val display = found.toDisplayString()
                Log.d(TAG, "漂移探测 ${radius}m@${b.toInt()}° → 精度=$p $display")
                if (p >= PRECISION_ROAD && hasRoadEvidence(display) && sameRegion(origin, found)) {
                    return ProbeResult(found, radius)
                }
            }
        }
        return null
    }

    /**
     * 区县一致性：仅当两侧都明确到区县且不同才拒绝（防漂到隔壁县）；
     * 任一侧缺失时不阻拦（道路级结果常不带 locality）。
     */
    private fun sameRegion(a: Address, b: Address): Boolean {
        val ra = a.locality ?: a.subAdminArea
        val rb = b.locality ?: b.subAdminArea
        return ra == null || rb == null || ra == rb
    }

    /** 按距离（米）和方位角（度）计算偏移点坐标（球面近似） */
    private fun offsetByMeters(lat: Double, lon: Double, meters: Int, bearingDeg: Float): Pair<Double, Double> {
        val d = meters / 6371000.0
        val br = Math.toRadians(bearingDeg.toDouble())
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(br))
        val lon2 = lon1 + Math.atan2(
            Math.sin(br) * Math.sin(d) * Math.cos(lat1),
            Math.cos(d) - Math.sin(lat1) * Math.sin(lat2)
        )
        return Pair(Math.toDegrees(lat2), Math.toDegrees(lon2))
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
        // 道路来源：thoroughfare 优先；featureName 若是道路名（含大道/路/街/巷等）也可用。
        // 关键修复：部分后端把路名放在 featureName（如「天禄镇龙虎山大道」），
        // thoroughfare 为 null——此前只取 subLocality，路名被丢，只剩「贵溪市」。
        val roadName = thoroughfare?.trim()?.takeIf { it.isNotBlank() }
            ?: featureName?.trim()?.takeIf { !it.isNullOrBlank() && hasRoadEvidence(it) }
        if (roadName != null) {
            val prefix = subLocality?.trim()
                ?.takeIf { it.isNotBlank() && !roadName.contains(it) && !it.contains(roadName) }
            if (prefix != null) parts.add(prefix)
            val num = extractHouseNumber()
            if (!num.isNullOrBlank() && num.any { it.isDigit() } && !roadName.contains(num)) {
                parts.add("$roadName$num")
            } else {
                parts.add(roadName)
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
        // featureName 可能是路名整串（如「天禄镇龙虎山大道」），只有含数字才算门牌
        featureName?.trim()?.let { if (it.isNotBlank() && it.any { c -> c.isDigit() }) return it }
        val line0 = getAddressLine(0)
        if (!line0.isNullOrBlank()) {
            val match = Regex("""(\d+[号栋座层室楼]?)""").find(line0)
            if (match != null) return match.value
        }
        return null
    }
}
