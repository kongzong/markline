package app.markline.system

import android.content.Context
import app.markline.domain.Event
import app.markline.domain.EventStore
import app.markline.util.AudioFileUtil
import app.markline.util.PhotoStore
import app.markline.util.SettingsStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WebDAV 云同步引擎。
 *
 * 设计（利用 MarkLine 数据模型的天然优势）：
 * - 记录 append-only + UUID 主键 → 合并 = 按 uuid 对账，字段修改按 updatedAt 新者胜（LWW）
 * - 照片/音频文件名即内容标识、不可变 → 同步 = 文件名集合 diff，缺哪补哪，无文件冲突
 * - 删除用墓碑传播：本地删除写 sync_tombstone，远端 manifest 记录 deleted 集合
 *
 * 远端布局（复用 v3 备份文件约定）：
 *   /MarkLine/manifest.json
 *   /MarkLine/photos/<name>.jpg
 *   /MarkLine/audio/<name>.m4a
 */
object SyncManager {

    private val gson: Gson = GsonBuilder().create()

    const val REMOTE_DIR = "MarkLine"

    // ---- 远端 manifest 模型 ----

    data class Manifest(
        @SerializedName("version") val version: Int = 1,
        @SerializedName("syncedAt") val syncedAt: Long = 0,
        @SerializedName("device") val device: String = "",
        @SerializedName("events") val events: List<SyncEvent> = emptyList(),
        @SerializedName("photos") val photos: List<String> = emptyList(),
        @SerializedName("audios") val audios: List<String> = emptyList(),
        @SerializedName("deleted") val deleted: Map<String, Long> = emptyMap()
    )

    data class SyncEvent(
        @SerializedName("uuid") val uuid: String,
        @SerializedName("createdAt") val createdAt: Long,
        @SerializedName("updatedAt") val updatedAt: Long,
        @SerializedName("latitude") val latitude: Double? = null,
        @SerializedName("longitude") val longitude: Double? = null,
        @SerializedName("address") val address: String? = null,
        @SerializedName("audioFileName") val audioFileName: String? = null,
        @SerializedName("audioDuration") val audioDuration: Int? = null,
        @SerializedName("note") val note: String? = null,
        @SerializedName("label") val label: String? = null,
        @SerializedName("status") val status: Int = 0,
        @SerializedName("photo") val photo: String? = null,
        @SerializedName("mediaSource") val mediaSource: Int = 0,
        @SerializedName("sourceUri") val sourceUri: String? = null
    )

    data class SyncResult(
        val uploadedEvents: Int = 0,
        val downloadedEvents: Int = 0,
        val uploadedFiles: Int = 0,
        val downloadedFiles: Int = 0,
        val appliedDeletions: Int = 0,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null
    }

    // ---- 入口 ----

    suspend fun sync(
        context: Context,
        settingsStore: SettingsStore,
        eventStore: EventStore
    ): SyncResult = withContext(Dispatchers.IO) {
        val rawUrl = settingsStore.webdavUrl.trim()
        val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl" // 用户漏填 scheme 时自动补 https
        }
        val user = settingsStore.webdavUser.trim()
        val pass = settingsStore.webdavPass
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            return@withContext SyncResult(error = "WebDAV 未配置完整（服务器/账号/应用密码）")
        }
        try {
            val client = WebDavClient(url, user, pass)
            client.ensureDirs(REMOTE_DIR, listOf("photos", "audio"))
            val result = doSync(context, client, eventStore, settingsStore)
            android.util.Log.d(
                "MarkLine/Sync",
                "同步完成: 上传${result.uploadedEvents}条/${result.uploadedFiles}文件 " +
                "下载${result.downloadedEvents}条/${result.downloadedFiles}文件 删除${result.appliedDeletions}"
            )
            result
        } catch (e: Exception) {
            android.util.Log.e("MarkLine/Sync", "同步失败", e)
            SyncResult(error = e.message ?: e.javaClass.simpleName)
        }
    }

    // ---- 核心同步 ----

    private fun doSync(
        context: Context,
        client: WebDavClient,
        eventStore: EventStore,
        settingsStore: SettingsStore
    ): SyncResult {
        // 1. 读取远端 manifest（首次同步为 null）
        val remoteBytes = client.get("$REMOTE_DIR/manifest.json")
        val remote: Manifest? = remoteBytes?.let {
            val text = String(it, Charsets.UTF_8).trim()
            if (!text.startsWith("{")) {
                // 拿到的是网页/错误页而非 JSON：多半是地址指向了网盘 Web 界面而非 DAV 服务
                throw SyncException(
                    "远端 manifest 不是 JSON，请检查服务器地址是否为 WebDAV 地址（dav.*）| 原文: ${text.take(120)}"
                )
            }
            try {
                gson.fromJson(text, Manifest::class.java)
            } catch (e: Exception) {
                throw SyncException(
                    "manifest 解析失败: ${e.message} | 原文: ${text.take(120)}"
                )
            }
        }

        val localEvents = eventStore.queryAll()
        val localTombstones = eventStore.getTombstones()
        val remoteEvents = remote?.events?.associateBy { it.uuid } ?: emptyMap()
        val remoteTombstones = remote?.deleted ?: emptyMap()

        // 2. 应用远端删除（远端删除时间 > 本地墓碑时间才执行，防止回环）
        var appliedDeletions = 0
        for ((uuid, deletedAt) in remoteTombstones) {
            if (localTombstones[uuid] ?: 0L < deletedAt) {
                eventStore.deleteByUuidForSync(uuid, deletedAt)
                appliedDeletions++
            }
        }

        // 3. 事件合并（LWW）
        var uploadedEvents = 0
        var downloadedEvents = 0
        val mergedEvents = mutableListOf<SyncEvent>()

        for (local in localEvents) {
            val r = remoteEvents[local.uuid]
            val photoName = local.photoUri?.let { PhotoStore.resolvePrivateFile(context, it)?.name }
            when {
                // 远端没有，或本地较新 → 上传
                r == null || local.updatedAt > r.updatedAt -> {
                    mergedEvents += toSyncEvent(local, photoName)
                    uploadedEvents++
                }
                // 远端较新 → 下载更新本地
                local.updatedAt < r.updatedAt -> {
                    val photoPath = r.photo?.let {
                        downloadPhotoToLocal(context, client, it, remote?.photos ?: emptyList())
                    }
                    eventStore.upsertFromRemote(toLocalEvent(r).copy(photoUri = photoPath))
                    downloadedEvents++
                    mergedEvents += r
                }
                // 两边一致 → 原样并入 manifest
                else -> mergedEvents += r
            }
        }
        for ((uuid, r) in remoteEvents) {
            if (mergedEvents.any { it.uuid == uuid }) continue
            val localTomb = localTombstones[uuid] ?: 0L
            if (localTomb >= r.updatedAt) continue // 本地已删且晚于远端版本 → 保持删除
            // 远端较新或本地没有 → 下载
            val downloaded = toLocalEvent(r)
            val photoPath = r.photo?.let { name ->
                downloadPhotoToLocal(context, client, name, remote?.photos ?: emptyList())
            }
            eventStore.upsertFromRemote(downloaded.copy(photoUri = photoPath))
            downloadedEvents++
            mergedEvents += r
        }

        // 4. 照片对账（文件名即内容标识，双向补齐）
        val remotePhotos = (remote?.photos ?: emptyList()).toSet()
        var uploadedFiles = 0
        var downloadedFiles = 0
        val mergedPhotos = mutableSetOf<String>()
        mergedPhotos.addAll(remotePhotos)

        val localPhotoNames = mutableSetOf<String>()
        for (local in localEvents) {
            val file = local.photoUri?.let { PhotoStore.resolvePrivateFile(context, it) } ?: continue
            localPhotoNames.add(file.name)
            mergedPhotos.add(file.name)
            if (file.name !in remotePhotos) {
                client.put("$REMOTE_DIR/photos/${file.name}", file.readBytes())
                uploadedFiles++
            }
        }
        // 多图表里的追加图也要对账
        for (local in localEvents) {
            for (p in eventStore.getEventPhotos(local.id)) {
                val file = PhotoStore.resolvePrivateFile(context, p.photoUri) ?: continue
                if (localPhotoNames.add(file.name)) {
                    mergedPhotos.add(file.name)
                    if (file.name !in remotePhotos) {
                        client.put("$REMOTE_DIR/photos/${file.name}", file.readBytes())
                        uploadedFiles++
                    }
                }
            }
        }
        for (name in remotePhotos) {
            if (!localPhotoNames.contains(name) && !File(PhotoStore.photosDir(context), name).exists()) {
                val bytes = client.get("$REMOTE_DIR/photos/$name") ?: continue
                File(PhotoStore.photosDir(context), name).writeBytes(bytes)
                downloadedFiles++
            }
        }

        // 5. 音频对账（同照片逻辑）
        val remoteAudios = (remote?.audios ?: emptyList()).toSet()
        val mergedAudios = mutableSetOf<String>()
        mergedAudios.addAll(remoteAudios)
        val localAudioNames = mutableSetOf<String>()
        for (local in localEvents) {
            val name = local.audioFileName ?: continue
            val file = AudioFileUtil.getAudioFile(context, name)
            localAudioNames.add(name)
            mergedAudios.add(name)
            if (!file.exists()) continue
            if (name !in remoteAudios) {
                client.put("$REMOTE_DIR/audio/$name", file.readBytes())
                uploadedFiles++
            }
        }
        for (name in remoteAudios) {
            val file = AudioFileUtil.getAudioFile(context, name)
            if (!file.exists()) {
                val bytes = client.get("$REMOTE_DIR/audio/$name") ?: continue
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                downloadedFiles++
            }
        }

        // 6. 墓碑合并（双向传播删除）
        val mergedTombstones = mutableMapOf<String, Long>()
        mergedTombstones.putAll(remoteTombstones)
        for ((uuid, t) in localTombstones) {
            mergedTombstones[uuid] = maxOf(mergedTombstones[uuid] ?: 0L, t)
        }

        // 7. 写回远端 manifest（含 merged events/photos/audios/deleted）
        // 零变更优化：本轮没有任何上传/下载/删除，且墓碑也无新变化时跳过 PUT，
        // 免去无意义的网络写入（manifest 单份虽小，但周期+变更触发的频率下值得省）
        val tombstonesChanged = mergedTombstones != remoteTombstones
        val anyChange = uploadedEvents + downloadedEvents + uploadedFiles +
                        downloadedFiles + appliedDeletions > 0 || tombstonesChanged
        if (anyChange || remote == null) {
            val manifest = Manifest(
                version = 1,
                syncedAt = System.currentTimeMillis(),
                device = android.os.Build.MODEL ?: "android",
                events = mergedEvents,
                photos = mergedPhotos.toList(),
                audios = mergedAudios.toList(),
                deleted = mergedTombstones
            )
            client.put("$REMOTE_DIR/manifest.json", gson.toJson(manifest).toByteArray(Charsets.UTF_8))
        }

        // 8. 记录同步时间
        settingsStore.lastSyncAt = System.currentTimeMillis()

        return SyncResult(
            uploadedEvents = uploadedEvents,
            downloadedEvents = downloadedEvents,
            uploadedFiles = uploadedFiles,
            downloadedFiles = downloadedFiles,
            appliedDeletions = appliedDeletions
        )
    }

    // ---- 转换 ----

    private fun toSyncEvent(e: Event, photoName: String?): SyncEvent = SyncEvent(
        uuid = e.uuid,
        createdAt = e.createdAt,
        updatedAt = if (e.updatedAt > 0) e.updatedAt else e.createdAt,
        latitude = e.latitude,
        longitude = e.longitude,
        address = e.address,
        audioFileName = e.audioFileName,
        audioDuration = e.audioDuration,
        note = e.note,
        label = e.label,
        status = e.status,
        photo = photoName,
        mediaSource = e.mediaSource,
        sourceUri = e.sourceUri
    )

    private fun toLocalEvent(s: SyncEvent): Event = Event(
        uuid = s.uuid,
        createdAt = s.createdAt,
        latitude = s.latitude,
        longitude = s.longitude,
        address = s.address,
        audioFileName = s.audioFileName,
        audioDuration = s.audioDuration,
        note = s.note,
        label = s.label,
        status = s.status,
        photoUri = null, // 由调用方在照片落盘后填本地路径
        sourceUri = s.sourceUri,
        updatedAt = s.updatedAt,
        mediaSource = s.mediaSource
    )

    /** 下载照片到私有目录，返回本地路径；下载失败返回 null */
    private fun downloadPhotoToLocal(
        context: Context,
        client: WebDavClient,
        name: String,
        remotePhotos: List<String>
    ): String? {
        if (name !in remotePhotos) return null
        val local = File(PhotoStore.photosDir(context), name)
        if (local.exists()) return local.absolutePath
        val bytes = client.get("$REMOTE_DIR/photos/$name") ?: return null
        local.writeBytes(bytes)
        return local.absolutePath
    }
}
