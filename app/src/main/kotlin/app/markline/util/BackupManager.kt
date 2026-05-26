package app.markline.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import app.markline.domain.Event
import app.markline.domain.EventStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 数据导入导出管理器
 *
 * 支持两个导出通道：
 * - 通道 A：导出到文件（MediaStore → Downloads，永久保留）
 * - 通道 B：分享备份（FileProvider → ShareSheet，跨设备迁移）
 */
object BackupManager {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // ──────────────── 公共导出逻辑 ────────────────

    /**
     * 生成备份 JSON 字符串。
     * 返回 null 表示数据库为空。
     */
    suspend fun generateBackupJson(context: Context, eventStore: EventStore): String? =
        withContext(Dispatchers.IO) {
            val events = eventStore.queryAll()
            if (events.isEmpty()) return@withContext null

            val eventJsonList = events.map { event ->
                val audioData = if (event.audioFileName != null) {
                    val file = AudioFileUtil.getAudioFile(context, event.audioFileName)
                    if (file.exists()) {
                        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    } else null
                } else null

                EventJson(
                    uuid = event.uuid.ifEmpty { java.util.UUID.randomUUID().toString() },
                    createdAt = event.createdAt,
                    latitude = event.latitude,
                    longitude = event.longitude,
                    address = event.address,
                    audioFileName = event.audioFileName,
                    audioDuration = event.audioDuration,
                    audioData = audioData,
                    note = event.note,
                    label = event.label,
                    status = event.status
                )
            }

            val backup = BackupFile(
                exportedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                eventCount = eventJsonList.size,
                events = eventJsonList
            )

            gson.toJson(backup)
        }

    // ──────────────── 通道 A：导出到文件 ────────────────

    /**
     * 通过 MediaStore 将备份 JSON 写入 Downloads 目录。
     * 返回保存的文件名用于 Toast 提示。
     */
    suspend fun exportToFile(context: Context, eventStore: EventStore): String? =
        withContext(Dispatchers.IO) {
            val json = generateBackupJson(context, eventStore) ?: return@withContext null
            val fileName = generateBackupFileName()

            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"), values
            ) ?: return@withContext null

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            }

            fileName
        }

    // ──────────────── 通道 B：分享备份 ────────────────

    /**
     * 生成备份 JSON、写入 cache 临时文件，返回分享 Intent。
     * 返回 null 表示数据库为空。
     */
    suspend fun createShareIntent(context: Context, eventStore: EventStore): Intent? =
        withContext(Dispatchers.IO) {
            val json = generateBackupJson(context, eventStore) ?: return@withContext null
            val fileName = generateBackupFileName()

            val cacheFile = File(context.cacheDir, fileName)
            cacheFile.writeText(json, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )

            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

    // ──────────────── 导入 ────────────────

    /** 导入结果 */
    data class ImportResult(
        val success: Int,  // 成功导入条数
        val skipped: Int,  // 跳过（已存在）条数
        val failed: Int,   // 失败条数
        val error: String? = null  // 整体错误信息（JSON 解析失败等）
    )

    /**
     * 从 URI 读取备份文件并导入。
     * 按 uuid 合并跳过重复。
     */
    suspend fun importFromUri(context: Context, uri: Uri, eventStore: EventStore): ImportResult =
        withContext(Dispatchers.IO) {
            // 读取 JSON
            val json: String
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    json = stream.bufferedReader().readText()
                } ?: return@withContext ImportResult(0, 0, 0, "无法读取文件")
            } catch (e: Exception) {
                return@withContext ImportResult(0, 0, 0, "读取文件失败: ${e.message}")
            }

            // 解析 JSON
            val backup: BackupFile
            try {
                backup = gson.fromJson(json, BackupFile::class.java)
            } catch (e: Exception) {
                return@withContext ImportResult(0, 0, 0, "文件格式不正确: ${e.message}")
            }

            // 校验版本
            if (backup.version != 1) {
                return@withContext ImportResult(0, 0, 0, "不支持的备份格式版本: ${backup.version}")
            }

            var success = 0
            var skipped = 0
            var failed = 0

            for (eventJson in backup.events) {
                try {
                    // 校验 uuid
                    val uuid = eventJson.uuid
                    if (uuid.isBlank()) {
                        failed++
                        continue
                    }

                    // 检查是否已存在
                    if (eventStore.findByUuid(uuid) != null) {
                        skipped++
                        continue
                    }

                    // 处理音频数据
                    var audioFileName = eventJson.audioFileName
                    val audioData = eventJson.audioData
                    if (audioData != null) {
                        val audioBytes = Base64.decode(audioData, Base64.DEFAULT)
                        val file = if (audioFileName != null) {
                            AudioFileUtil.getAudioFile(context, audioFileName)
                        } else {
                            // 无原始文件名，用时间戳生成
                            val name = "rec_${eventJson.createdAt}.m4a"
                            audioFileName = name
                            AudioFileUtil.getAudioFile(context, name)
                        }
                        file.parentFile?.mkdirs()
                        file.writeBytes(audioBytes)
                    }

                    // 插入 Event（本地 DB 生成新自增 id）
                    val event = Event(
                        uuid = uuid,
                        createdAt = eventJson.createdAt,
                        latitude = eventJson.latitude,
                        longitude = eventJson.longitude,
                        address = eventJson.address,
                        audioFileName = audioFileName,
                        audioDuration = eventJson.audioDuration,
                        note = eventJson.note,
                        label = eventJson.label,
                        status = eventJson.status
                    )
                    eventStore.insert(event)
                    success++
                } catch (e: Exception) {
                    failed++
                }
            }

            ImportResult(success, skipped, failed)
        }
}
