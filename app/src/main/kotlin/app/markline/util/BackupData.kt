package app.markline.util

import com.google.gson.annotations.SerializedName
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 导出/导入 JSON 数据结构
 */

/** 备份文件的顶层结构 */
data class BackupFile(
    @SerializedName("version")
    val version: Int = 1,

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

    @SerializedName("audioData")
    val audioData: String?,  // base64 encoded audio file content

    @SerializedName("note")
    val note: String?,

    @SerializedName("label")
    val label: String?,

    @SerializedName("status")
    val status: Int
)

/**
 * 生成导出文件名
 * 格式：MarkLine_backup_2026-05-26_160000.json
 */
fun generateBackupFileName(): String {
    val now = LocalDateTime.now()
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
    return "MarkLine_backup_${now.format(fmt)}.json"
}
