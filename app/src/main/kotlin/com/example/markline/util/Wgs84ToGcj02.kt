package com.example.markline.util

import kotlin.math.*

/**
 * WGS-84 → GCJ-02（火星坐标系）转换
 *
 * Android FusedLocationProvider 返回的是 WGS-84 原始 GPS 坐标，
 * 但国内地图 App（高德、百度等）使用 GCJ-02 坐标系，
 * 直接传入会产生 300-500 米的偏移。
 *
 * 此工具使用标准火星坐标加密算法，在打开三方地图前转换。
 */
object Wgs84ToGcj02 {

    private const val PI = Math.PI
    private const val A  = 6378245.0
    private const val EE = 0.00669342162296594323

    /**
     * 转换单点，返回 (gcjLat, gcjLon)
     */
    fun transform(lat: Double, lon: Double): Pair<Double, Double> {
        if (isOutOfChina(lat, lon)) return Pair(lat, lon)

        val dLat = transformLat(lon - 105.0, lat - 35.0)
        val dLon = transformLon(lon - 105.0, lat - 35.0)

        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)

        val dLatFinal = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val dLonFinal = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)

        return Pair(lat + dLatFinal, lon + dLonFinal)
    }

    /** 中国大陆境外不转换 */
    private fun isOutOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
