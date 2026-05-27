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
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据导入导出管理器
 *
 * 支持两个导出通道：
 * - 通道 A：导出到文件（MediaStore -> Downloads，永久保留）
 * - 通道 B：分享备份（FileProvider -> ShareSheet，跨设备迁移）
 */
object BackupManager {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // ---- 公共导出逻辑 ----

    /**
     * 生成备份 JSON 字符串（v2：不含 audio base64，音频以分离文件形式存入 ZIP）。
     * 返回 null 表示数据库为空。
     */
    suspend fun generateBackupJson(context: Context, eventStore: EventStore): String? {
        return withContext(Dispatchers.IO) {
            val events = eventStore.queryAll()
            if (events.isEmpty()) return@withContext null

            val eventJsonList = events.map { event ->
                EventJson(
                    uuid = event.uuid.ifEmpty { java.util.UUID.randomUUID().toString() },
                    createdAt = event.createdAt,
                    latitude = event.latitude,
                    longitude = event.longitude,
                    address = event.address,
                    audioFileName = event.audioFileName,
                    audioDuration = event.audioDuration,
                    note = event.note,
                    label = event.label,
                    status = event.status
                )
            }

            val backup = BackupFile(
                exportedAt = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                eventCount = eventJsonList.size,
                events = eventJsonList
            )

            gson.toJson(backup)
        }
    }

    // ---- ZIP 打包 ----

    /**
     * 创建备份 ZIP 文件到 cache 临时目录。
     * ZIP 结构：backup.json + audio/ 子目录
     * 返回临时 File 对象，调用方负责使用后清理。
     */
    suspend fun createBackupZip(context: Context, eventStore: EventStore): File? {
        return withContext(Dispatchers.IO) {
            val json = generateBackupJson(context, eventStore) ?: return@withContext null
            val zipFile = File(context.cacheDir, generateBackupFileName())

            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    // 1) 写入 backup.json
                    zos.putNextEntry(ZipEntry("backup.json"))
                    zos.write(json.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // 2) 写入音频文件（仅导出实际存在的文件）
                    val events = eventStore.queryAll()
                    for (event in events) {
                        val name = event.audioFileName ?: continue
                        val audioFile = AudioFileUtil.getAudioFile(context, name)
                        if (!audioFile.exists()) continue

                        zos.putNextEntry(ZipEntry("audio/" + name))
                        FileInputStream(audioFile).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }

            zipFile
        }
    }

    // ---- 通道 A：导出到文件 ----

    /**
     * 通过 MediaStore 将备份 ZIP 写入 Downloads 目录。
     * 返回保存的文件名用于 Toast 提示。
     */
    suspend fun exportToFile(context: Context, eventStore: EventStore): String? {
        return withContext(Dispatchers.IO) {
            val zipFile = createBackupZip(context, eventStore) ?: return@withContext null
            val fileName = zipFile.name

            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/zip")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"), values
            ) ?: run { zipFile.delete(); return@withContext null }

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                zipFile.inputStream().use { it.copyTo(stream) }
            }

            zipFile.delete()
            fileName
        }
    }

    // ---- 通道 B：分享备份 ----

    /**
     * 创建备份 ZIP 到 cache 临时文件，返回分享 Intent。
     * 返回 null 表示数据库为空。
     */
    suspend fun createShareIntent(context: Context, eventStore: EventStore): Intent? {
        return withContext(Dispatchers.IO) {
            val zipFile = createBackupZip(context, eventStore) ?: return@withContext null

            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                zipFile
            )

            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    // ---- 导入 ----

    /** 导入结果 */
    data class ImportResult(
        val success: Int,
        val skipped: Int,
        val failed: Int,
        val error: String? = null
    )

    /**
     * 从 URI 读取备份文件并导入。自动检测格式：
     * - .zip -> v2 ZIP (JSON + 分离音频)
     * - .json -> v1 JSON (base64 内嵌音频)
     */
    suspend fun importFromUri(context: Context, uri: Uri, eventStore: EventStore): ImportResult {
        return withContext(Dispatchers.IO) {
            // 通过 ContentResolver 查询真实文件名（uri.lastPathSegment 对 content URI 不可靠）
            var fileName = ""
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) fileName = cursor.getString(idx) ?: ""
                }
            }

            when {
                fileName.endsWith(".zip", ignoreCase = true) ->
                    importFromZip(context, uri, eventStore)
                fileName.endsWith(".json", ignoreCase = true) ->
                    importFromJson(context, uri, eventStore)
                else ->
                    ImportResult(0, 0, 0, "不支持的文件格式，请选择 .zip 或 .json 备份文件")
            }
        }
    }

    // -- v2 ZIP 导入 --

    private suspend fun importFromZip(
        context: Context, uri: Uri, eventStore: EventStore
    ): ImportResult {
        return withContext(Dispatchers.IO) {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult(0, 0, 0, "无法读取文件")

            inputStream.use { stream ->
                val zis = ZipInputStream(stream)
                var backup: BackupFile? = null
                val audioBuffers = mutableMapOf<String, ByteArray>()

                var entry = zis.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "backup.json" -> {
                            val json = zis.bufferedReader().readText()
                            try {
                                backup = gson.fromJson(json, BackupFile::class.java)
                            } catch (e: Exception) {
                                return@withContext ImportResult(
                                    0, 0, 0, "backup.json 解析失败: " + e.message
                                )
                            }
                        }
                        entry.name.startsWith("audio/") && !entry.isDirectory -> {
                            val audioName = entry.name.removePrefix("audio/")
                            audioBuffers[audioName] = zis.readBytes()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }

                val b = backup ?: return@withContext ImportResult(
                    0, 0, 0, "ZIP 中未找到 backup.json"
                )

                var success = 0
                var skipped = 0
                var failed = 0
                for (ej in b.events) {
                    try {
                        val uuid = ej.uuid
                        if (uuid.isBlank()) { failed++; continue }
                        if (eventStore.findByUuid(uuid) != null) { skipped++; continue }

                        ej.audioFileName?.let { name ->
                            audioBuffers[name]?.let { bytes ->
                                val file = AudioFileUtil.getAudioFile(context, name)
                                file.parentFile?.mkdirs()
                                file.writeBytes(bytes)
                            }
                        }

                        eventStore.insert(Event(
                            uuid = uuid,
                            createdAt = ej.createdAt,
                            latitude = ej.latitude,
                            longitude = ej.longitude,
                            address = ej.address,
                            audioFileName = ej.audioFileName,
                            audioDuration = ej.audioDuration,
                            note = ej.note,
                            label = ej.label,
                            status = ej.status
                        ))
                        success++
                    } catch (_: Exception) { failed++ }
                }

                ImportResult(success, skipped, failed)
            }
        }
    }

    // -- v1 JSON 导入（向后兼容） --

    private suspend fun importFromJson(
        context: Context, uri: Uri, eventStore: EventStore
    ): ImportResult {
        return withContext(Dispatchers.IO) {
            // 读取 JSON
            val json: String
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    json = stream.bufferedReader().readText()
                } ?: return@withContext ImportResult(0, 0, 0, "无法读取文件")
            } catch (e: Exception) {
                return@withContext ImportResult(0, 0, 0, "读取文件失败: " + e.message)
            }

            // 解析 JSON
            val backup: BackupFile
            try {
                backup = gson.fromJson(json, BackupFile::class.java)
            } catch (e: Exception) {
                return@withContext ImportResult(0, 0, 0, "文件格式不正确: " + e.message)
            }

            // v1 的 audioData 字段在 EventJson 中已移除，需用 JsonObject 回退读取
            val root = com.google.gson.JsonParser.parseString(json).asJsonObject
            val eventsArr = root.getAsJsonArray("events")

            var success = 0
            var skipped = 0
            var failed = 0
            for (i in 0 until backup.events.size) {
                try {
                    val ej = backup.events[i]
                    val uuid = ej.uuid
                    if (uuid.isBlank()) { failed++; continue }
                    if (eventStore.findByUuid(uuid) != null) { skipped++; continue }

                    var audioFileName = ej.audioFileName
                    val rawEntry = eventsArr.get(i).asJsonObject
                    val audioDataB64 = rawEntry.get("audioData")?.asString
                    if (!audioDataB64.isNullOrBlank()) {
                        val audioBytes = Base64.decode(audioDataB64, Base64.DEFAULT)
                        val file = if (audioFileName != null) {
                            AudioFileUtil.getAudioFile(context, audioFileName)
                        } else {
                            val name = "rec_" + ej.createdAt.toString() + ".m4a"
                            audioFileName = name
                            AudioFileUtil.getAudioFile(context, name)
                        }
                        file.parentFile?.mkdirs()
                        file.writeBytes(audioBytes)
                    }

                    eventStore.insert(Event(
                        uuid = uuid,
                        createdAt = ej.createdAt,
                        latitude = ej.latitude,
                        longitude = ej.longitude,
                        address = ej.address,
                        audioFileName = audioFileName,
                        audioDuration = ej.audioDuration,
                        note = ej.note,
                        label = ej.label,
                        status = ej.status
                    ))
                    success++
                } catch (_: Exception) { failed++ }
            }

            ImportResult(success, skipped, failed)
        }
    }
}
