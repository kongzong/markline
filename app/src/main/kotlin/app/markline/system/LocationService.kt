package app.markline.system

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
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
 *  1. 有 Google Play Services → 使用 FusedLocationProviderClient（高精度 + 省电）
 *  2. 无 Google Play Services（国产手机） → 降级到原生 LocationManager
 *  3. 优先使用 lastKnownLocation（毫秒级返回）
 *  4. 失败则发起实时定位，超时后返回 null
 */
class LocationService(private val context: Context) {

    /** 设备是否安装了 Google Play Services */
    private val hasPlayServices: Boolean by lazy {
        try {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    private val fusedClient: FusedLocationProviderClient? by lazy {
        if (hasPlayServices) LocationServices.getFusedLocationProviderClient(context)
        else null
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
     * 获取当前位置。需要已持有 ACCESS_FINE_LOCATION 权限。
     * 返回 null 表示定位失败。
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): LocationData? {
        if (hasPlayServices) {
            // === 路径 A：Google Play Services ===
            val cached = withTimeoutOrNull(2_000L) {
                getLastKnownLocationGms()
            }
            if (cached != null) {
                val address = resolveAddress(cached.first, cached.second)
                return LocationData(cached.first, cached.second, address)
            }

            val fresh = withTimeoutOrNull(8_000L) {
                requestFreshLocationGms()
            } ?: return null

            val address = resolveAddress(fresh.first, fresh.second)
            return LocationData(fresh.first, fresh.second, address)
        } else {
            // === 路径 B：原生 LocationManager（国产手机） ===
            val cached = withTimeoutOrNull(2_000L) {
                getLastKnownLocationNative()
            }
            if (cached != null) {
                val address = resolveAddress(cached.first, cached.second)
                return LocationData(cached.first, cached.second, address)
            }

            val fresh = withTimeoutOrNull(10_000L) {
                requestFreshLocationNative()
            } ?: return null

            val address = resolveAddress(fresh.first, fresh.second)
            return LocationData(fresh.first, fresh.second, address)
        }
    }

    // ──────────── Google Play Services 定位 ────────────

    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocationGms(): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            fusedClient!!.lastLocation
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
    private suspend fun requestFreshLocationGms(): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2_000L
            ).setMaxUpdates(1).build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    val loc = result.lastLocation
                    fusedClient!!.removeLocationUpdates(this)
                    if (loc != null) {
                        cont.resume(Pair(loc.latitude, loc.longitude))
                    } else {
                        cont.resume(null)
                    }
                }
            }

            cont.invokeOnCancellation {
                fusedClient!!.removeLocationUpdates(callback)
            }

            fusedClient!!.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }

    // ──────────── 原生 LocationManager 定位（无 Google Play Services） ────────────

    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocationNative(): Pair<Double, Double>? {
        val lm = locationManager ?: return null
        // 取 GPS 和 Network 中较新的一个
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: android.location.Location? = null
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null && (best == null || loc.time > best!!.time)) {
                    best = loc
                }
            } catch (_: Exception) { }
        }
        return best?.let { Pair(it.latitude, it.longitude) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocationNative(): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            val lm = locationManager
            if (lm == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            var resolved = false

            // 优先 GPS，其次 Network
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }

            if (providers.isEmpty()) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            for (provider in providers) {
                if (resolved) break

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+：使用 getCurrentLocation（更省电）
                    lm.getCurrentLocation(
                        provider,
                        null,
                        context.mainExecutor
                    ) { location ->
                        if (!resolved && location != null) {
                            resolved = true
                            cont.resume(Pair(location.latitude, location.longitude))
                        }
                    }
                } else {
                    // Android 10：使用 requestSingleUpdate
                    @Suppress("DEPRECATION")
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            if (!resolved) {
                                resolved = true
                                cont.resume(Pair(location.latitude, location.longitude))
                            }
                            lm.removeUpdates(this)
                        }
                        override fun onProviderDisabled(provider: String) {}
                        override fun onProviderEnabled(provider: String) {}
                    }
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }

            cont.invokeOnCancellation {
                resolved = true
            }
        }

    // ──────────── 逆地理编码（两者共用，无 Play Services 依赖） ────────────

    /**
     * 使用 Android Geocoder 逆地理编码
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
