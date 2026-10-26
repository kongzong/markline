package app.markline.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.markline.domain.Event
import app.markline.domain.EventStore
import app.markline.util.PhotoStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册导入核心：从照片 Uri 解析 EXIF（拍摄时间 / 经纬度），生成 Event 写入数据库。
 *
 * 配合系统“水印相机”等 App 使用——用户继续用熟悉的工具拍照，
 * MarkLine 负责把散落的照片按时间 / 地点自动组织成可检索的现场档案。
 */
object ExifImporter {

    data class PhotoMeta(
        val time: Long,
        val latitude: Double?,
        val longitude: Double?
    )

    data class ImportResult(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val ids: List<Long> = emptyList()
    )

    /** 解析单张照片的 EXIF 元数据（时间 + 经纬度），无信息则时间回退到当前时间 */
    suspend fun parsePhotoMeta(context: Context, uri: Uri): PhotoMeta =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            cr.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = FloatArray(2)
                val hasGps = exif.getLatLong(latLong)
                val lat = if (hasGps) latLong[0].toDouble() else null
                val lng = if (hasGps) latLong[1].toDouble() else null
                val time = parseExifTime(exif)
                    ?: queryMediaStoreTime(cr, uri)
                    ?: System.currentTimeMillis()
                return@withContext PhotoMeta(time, lat, lng)
            }
            PhotoMeta(System.currentTimeMillis(), null, null)
        }

    /** 批量导入：自动去重（按原始来源 Uri），照片本体复制进私有目录后本地路径落库 */
    suspend fun importPhotos(
        context: Context,
        eventStore: EventStore,
        uris: List<Uri>,
        label: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        var failed = 0
        val ids = mutableListOf<Long>()

        for (uri in uris) {
            try {
                val uriStr = uri.toString()
                // 去重：同一张照片（原始来源 Uri）不重复导入
                if (eventStore.findBySourceUri(uriStr) != null ||
                    eventStore.findByPhotoUri(uriStr) != null
                ) {
                    skipped++
                    continue
                }

                val meta = parsePhotoMeta(context, uri)
                // 照片本体复制进私有目录，脱离外部相册存亡的依赖
                val localPath = PhotoStore.importToPrivate(context, uri)
                if (localPath == null) {
                    failed++
                    continue
                }

                val event = Event(
                    uuid = UUID.randomUUID().toString(),
                    createdAt = meta.time,
                    latitude = meta.latitude,
                    longitude = meta.longitude,
                    label = label,
                    status = if (meta.latitude != null && meta.longitude != null)
                        Event.STATUS_DONE else Event.STATUS_LOCATION_FAILED,
                    photoUri = localPath,
                    sourceUri = uriStr,
                    mediaSource = Event.MEDIA_SOURCE_IMPORTED
                )
                eventStore.insert(event).also { eid ->
                    ids.add(eid)
                    // 同步写入多图表（保持一图一事件，封面=该图）
                    eventStore.insertEventPhoto(
                        eventId = eid,
                        photoUri = localPath,
                        mediaSource = Event.MEDIA_SOURCE_IMPORTED,
                        createdAt = meta.time,
                        sourceUri = uriStr
                    )
                }
                imported++
            } catch (e: Exception) {
                e.printStackTrace()
                failed++
            }
        }
        if (imported > 0) {
            app.markline.system.SyncWorker.onDataChanged(context)
        }
        ImportResult(imported, skipped, failed, ids)
    }

    private fun parseExifTime(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        if (raw.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
        } catch (_: Exception) { null }
    }

    private fun queryMediaStoreTime(cr: ContentResolver, uri: Uri): Long? {
        return try {
            val proj = arrayOf(MediaStore.Images.Media.DATE_TAKEN)
            cr.query(uri, proj, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
    }
}
