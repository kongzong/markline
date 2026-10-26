package app.markline.util

import com.google.gson.annotations.SerializedName
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 导出/导入 JSON 数据结构
 */

/** 备份文件的顶层结构（v3：含 photos/ 照片文件与每事件多图清单） */
data class BackupFile(
    @SerializedName("version")
    val version: Int = 3,

    @SerializedName("appName")
    val appName: String = "MarkLine",

    @SerializedName("exportedAt")
    val exportedAt: String,

    @SerializedName("eventCount")
    val eventCount: Int,

    @SerializedName("events")
    val events: List<EventJson>
)

/** JSON 中的单条 Event（不包含自增 id，使用 uuid 作为唯一标识） */
data class EventJson(
    @SerializedName("uuid")
    val uuid: String,

    @SerializedName("createdAt")
    val createdAt: Long,

    @SerializedName("latitude")
    val latitude: Double?,

    @SerializedName("longitude")
    val longitude: Double?,

    @SerializedName("address")
    val address: String?,

    @SerializedName("audioFileName")
    val audioFileName: String?,

    @SerializedName("audioDuration")
    val audioDuration: Int?,

    @SerializedName("note")
    val note: String?,

    @SerializedName("label")
    val label: String?,

    @SerializedName("status")
    val status: Int,

    @SerializedName("photoUri")
    val photoUri: String? = null,

    @SerializedName("sourceUri")
    val sourceUri: String? = null,

    @SerializedName("mediaSource")
    val mediaSource: Int = 0,

    /** 事件关联照片清单（v3 新增；旧备份为空表则保持原行为） */
    @SerializedName("photos")
    val photos: List<PhotoJson> = emptyList()
)

/** JSON 中的单张照片（file 为 ZIP 内 photos/ 下的文件名） */
data class PhotoJson(
    @SerializedName("file")
    val file: String,

    @SerializedName("mediaSource")
    val mediaSource: Int = 0,

    @SerializedName("createdAt")
    val createdAt: Long,

    /** 原始来源 Uri（跨设备去重用），无则为 null */
    @SerializedName("sourceUri")
    val sourceUri: String? = null
)

/**
 * 生成导出文件名
 * 格式：MarkLine_backup_2026-05-26_160000.zip
 */
fun generateBackupFileName(): String {
    val now = LocalDateTime.now()
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
    return "MarkLine_backup_${now.format(fmt)}.zip"
}
