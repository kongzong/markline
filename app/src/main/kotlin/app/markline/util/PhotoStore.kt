package app.markline.util

import android.content.Context
import android.net.Uri
import app.markline.domain.EventStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 照片持久化仓库。
 *
 * 设计原则：照片本体一律存入 App 私有目录 `files/photos/`，数据库 photo_uri 记录本地绝对路径；
 * 原始来源 Uri 记录在 source_uri（相册导入去重用）。这样照片不依赖外部相册的存亡，
 * 卸载重装/换机也能通过备份还原。
 *
 * 兼容性：历史数据的 photo_uri 可能是外部 content Uri 或本应用 FileProvider Uri，
 * resolvePrivateFile 会尽可能映射回私有文件，映射不到的（外部相册图）不做破坏性操作。
 */
object PhotoStore {

    fun photosDir(context: Context): File =
        File(context.filesDir, "photos").apply { if (!exists()) mkdirs() }

    /** 是否为本应用私有目录内的本地路径（绝对路径形式） */
    fun isLocalPath(uriStr: String): Boolean = uriStr.startsWith("/")

    /**
     * 将 photoUri 字符串映射回私有目录文件；映射不到返回 null。
     * 支持：绝对路径 / file:// Uri / 本应用 FileProvider 的 photos/ Uri。
     */
    fun resolvePrivateFile(context: Context, uriStr: String): File? {
        if (uriStr.isBlank()) return null
        return try {
            when {
                uriStr.startsWith("/") -> {
                    val f = File(uriStr)
                    if (f.exists() && f.parentFile == photosDir(context)) f else null
                }
                uriStr.startsWith("file:") -> {
                    val f = File(Uri.parse(uriStr).path ?: return null)
                    if (f.exists() && f.parentFile == photosDir(context)) f else null
                }
                uriStr.contains("/photos/") -> {
                    // 本应用 FileProvider Uri（拍照签到旧数据）：files/photos/<name>
                    val name = uriStr.substringAfterLast("/photos/")
                    if (name.isBlank() || name.contains("/")) return null
                    val f = File(photosDir(context), name)
                    if (f.exists()) f else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 把一张照片（content/file Uri）复制进私有目录，返回本地绝对路径。
     * 文件名 imp_<uuid>.jpg；读取失败（源已删除等）返回 null。
     */
    suspend fun importToPrivate(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val dest = File(photosDir(context), "imp_${java.util.UUID.randomUUID()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null
                if (dest.length() == 0L) {
                    dest.delete()
                    return@withContext null
                }
                dest.absolutePath
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 存量迁移：把数据库里仍指向外部 content Uri 的照片复制进私有目录，
     * 并将 photo_uri 改写为本地路径、source_uri 留档原始 Uri。
     * 已是本地路径 / 源已删除的记录自动跳过。App 启动与备份导出前各调用一次。
     */
    suspend fun migrateContentUris(context: Context, eventStore: EventStore) =
        withContext(Dispatchers.IO) {
            for (event in eventStore.queryAll()) {
                val cover = event.photoUri ?: continue
                if (isLocalPath(cover)) continue
                // 本应用 FileProvider Uri（拍照签到旧数据）本体已在私有目录，仅在备份/删除时做路径映射，无需复制
                if (cover.contains("/photos/")) continue
                val newPath = importToPrivate(context, Uri.parse(cover)) ?: continue
                eventStore.replacePhotoUri(cover, newPath, cover)
            }
        }
}
