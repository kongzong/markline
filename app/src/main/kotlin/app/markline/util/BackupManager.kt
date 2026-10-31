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
import app.markline.util.PhotoStore
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
     * 生成备份 JSON 字符串（v3：音频分离文件 + photos/ 照片分离文件 + 每事件多图清单）。
     * 返回 null 表示数据库为空。
     */
    suspend fun generateBackupJson(context: Context, eventStore: EventStore): String? {
        return withContext(Dispatchers.IO) {
            // 照片本地化迁移：确保能找到的照片都进私有目录（幂等，已是本地路径的跳过）
            PhotoStore.migrateContentUris(context, eventStore)
            val events = eventStore.queryAll()
            if (events.isEmpty()) return@withContext null

            val eventJsonList = events.map { event ->
                val photos = eventStore.getEventPhotos(event.id).mapNotNull { p ->
                    val file = PhotoStore.resolvePrivateFile(context, p.photoUri)
                        ?: return@mapNotNull null  // 源已删除且从未本地化：跳过该图，记录保留
                    PhotoJson(
                        file = file.name,
                        mediaSource = p.mediaSource,
                        createdAt = p.createdAt,
                        sourceUri = p.sourceUri
                    )
                }
                EventJson(
                    uuid = event.uuid.ifEmpty { java.util.UUID.randomUUID().toString() },
                    createdAt = event.createdAt,
                    latitude = event.latitude,
                    longitude = event.longitude,
                    address = event.address,
                    addressManual = event.addressManual,
                    audioFileName = event.audioFileName,
                    audioDuration = event.audioDuration,
                    note = event.note,
                    label = event.label,
                    status = event.status,
                    photoUri = event.photoUri,
                    sourceUri = event.sourceUri,
                    mediaSource = event.mediaSource,
                    photos = photos
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
     * ZIP 结构：backup.json + audio/ 子目录 + photos/ 子目录
     * @param litePhotos true=照片轻量压缩（长边 1600px、质量 80，包体小 10 倍+）；false=原图
     * 返回临时 File 对象，调用方负责使用后清理。
     */
    suspend fun createBackupZip(
        context: Context,
        eventStore: EventStore,
        litePhotos: Boolean = false
    ): File? {
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

                    // 3) 写入照片文件（去重后打包，跨设备迁移/还原的核心）
                    val packedNames = mutableSetOf<String>()
                    for (event in events) {
                        for (p in eventStore.getEventPhotos(event.id)) {
                            val file = PhotoStore.resolvePrivateFile(context, p.photoUri) ?: continue
                            if (!packedNames.add(file.name)) continue
                            zos.putNextEntry(ZipEntry("photos/" + file.name))
                            if (litePhotos) {
                                val compressed = compressForBackup(context, file)
                                if (compressed != null) {
                                    FileInputStream(compressed).use { it.copyTo(zos) }
                                    compressed.delete()
                                } else {
                                    // 压缩失败回退原图，保证不丢图
                                    FileInputStream(file).use { it.copyTo(zos) }
                                }
                            } else {
                                FileInputStream(file).use { it.copyTo(zos) }
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }

            zipFile
        }
    }

    /**
     * 轻量备份：照片重编码为长边 ≤1600px、质量 80 的 JPEG。
     * 返回 cache 临时文件（调用方用完删除）；解码失败返回 null（由调用方回退原图）。
     */
    private fun compressForBackup(context: Context, src: File): File? {
        return try {
            val bmp = android.graphics.BitmapFactory.decodeFile(src.absolutePath)
                ?: return null
            val longEdge = maxOf(bmp.width, bmp.height)
            val scaled = if (longEdge > 1600) {
                val scale = 1600f / longEdge
                android.graphics.Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bmp
            val out = File(context.cacheDir, "bk_${src.nameWithoutExtension}.jpg")
            FileOutputStream(out).use { stream ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
            }
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 通过 MediaStore 将备份 ZIP 写入 Downloads 目录。
     * 返回保存的文件名用于 Toast 提示。
     */
    suspend fun exportToFile(
        context: Context,
        eventStore: EventStore,
        litePhotos: Boolean = false
    ): String? {
        return withContext(Dispatchers.IO) {
            val zipFile = createBackupZip(context, eventStore, litePhotos) ?: return@withContext null
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
    suspend fun createShareIntent(
        context: Context,
        eventStore: EventStore,
        litePhotos: Boolean = false
    ): Intent? {
        return withContext(Dispatchers.IO) {
            val zipFile = createBackupZip(context, eventStore, litePhotos) ?: return@withContext null

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
                val photoBuffers = mutableMapOf<String, ByteArray>()

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
                        entry.name.startsWith("photos/") && !entry.isDirectory -> {
                            // 仅接受纯文件名，防路径穿越
                            val photoName = entry.name.removePrefix("photos/")
                            if (photoName.isNotBlank() && !photoName.contains("/")) {
                                photoBuffers[photoName] = zis.readBytes()
                            }
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

                        // v3：落盘照片文件，photoUri 重写为本地路径
                        val localPaths = ej.photos.mapNotNull { pj ->
                            val bytes = photoBuffers[pj.file] ?: return@mapNotNull null
                            val file = java.io.File(PhotoStore.photosDir(context), pj.file)
                            file.writeBytes(bytes)
                            file.absolutePath
                        }
                        val coverUri = localPaths.firstOrNull() ?: ej.photoUri

                        val eventId = eventStore.insert(Event(
                            uuid = uuid,
                            createdAt = ej.createdAt,
                            latitude = ej.latitude,
                            longitude = ej.longitude,
                            address = ej.address,
                            addressManual = ej.addressManual,
                            audioFileName = ej.audioFileName,
                            audioDuration = ej.audioDuration,
                            note = ej.note,
                            label = ej.label,
                            status = ej.status,
                            photoUri = coverUri,
                            sourceUri = ej.sourceUri,
                            mediaSource = ej.mediaSource
                        ))

                        // v3：重建多图清单（insertEventPhoto 对已有封面不重设，需显式逐张写入）
                        ej.photos.forEachIndexed { idx, pj ->
                            val localPath = localPaths.getOrNull(idx) ?: return@forEachIndexed
                            eventStore.insertEventPhoto(
                                eventId = eventId,
                                photoUri = localPath,
                                mediaSource = pj.mediaSource,
                                createdAt = pj.createdAt,
                                sourceUri = pj.sourceUri
                            )
                        }
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
                        addressManual = ej.addressManual,
                        audioFileName = audioFileName,
                        audioDuration = ej.audioDuration,
                        note = ej.note,
                        label = ej.label,
                        status = ej.status,
                        photoUri = ej.photoUri,
                        mediaSource = ej.mediaSource
                    ))
                    success++
                } catch (_: Exception) { failed++ }
            }

            ImportResult(success, skipped, failed)
        }
    }
}
