package app.markline.storage

import android.content.ContentValues
import android.content.Context
import app.markline.domain.Event
import app.markline.domain.EventPhoto
import app.markline.domain.EventStore
import app.markline.util.AudioFileUtil

/**
 * EventStore 的 SQLite 实现
 * 使用 DBHelper 直接操作 SQLite，不使用 Room 或 ORM
 */
class SQLiteEventStore(private val context: Context) : EventStore {

    private val dbHelper = DBHelper(context)

    override fun insert(event: Event): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DBHelper.COL_UUID, event.uuid)
            put(DBHelper.COL_CREATED_AT, event.createdAt)
            put(DBHelper.COL_STATUS, event.status)
            // 同步写入调用方已携带的字段（导入场景：主题/定位/照片/来源等）
            event.label?.let { put(DBHelper.COL_LABEL, it) }
            event.latitude?.let { put(DBHelper.COL_LATITUDE, it) }
            event.longitude?.let { put(DBHelper.COL_LONGITUDE, it) }
            event.address?.let { put(DBHelper.COL_ADDRESS, it) }
            event.audioFileName?.let { put(DBHelper.COL_AUDIO_FILE_NAME, it) }
            event.audioDuration?.let { put(DBHelper.COL_AUDIO_DURATION, it) }
            event.note?.let { put(DBHelper.COL_NOTE, it) }
            event.photoUri?.let { put(DBHelper.COL_PHOTO_URI, it) }
            put(DBHelper.COL_MEDIA_SOURCE, event.mediaSource)
            // 定位/录音仍可能在异步阶段通过 updateEnhancement 补全
        }
        return db.insert(DBHelper.TABLE_EVENT, null, values)
    }

    override fun updateEnhancement(
        id: Long,
        latitude: Double?,
        longitude: Double?,
        address: String?,
        audioFileName: String?,
        audioDuration: Int?,
        status: Int?
    ) {
        val db = dbHelper.writableDatabase
        val values = ContentValues()
        latitude?.let { values.put(DBHelper.COL_LATITUDE, it) }
        longitude?.let { values.put(DBHelper.COL_LONGITUDE, it) }
        address?.let { values.put(DBHelper.COL_ADDRESS, it) }
        audioFileName?.let { values.put(DBHelper.COL_AUDIO_FILE_NAME, it) }
        audioDuration?.let { values.put(DBHelper.COL_AUDIO_DURATION, it) }
        status?.let { values.put(DBHelper.COL_STATUS, it) }

        if (values.size() > 0) {
            db.update(
                DBHelper.TABLE_EVENT,
                values,
                "${DBHelper.COL_ID} = ?",
                arrayOf(id.toString())
            )
        }
    }

    override fun updateNote(id: Long, note: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DBHelper.COL_NOTE, note)
        }
        db.update(
            DBHelper.TABLE_EVENT,
            values,
            "${DBHelper.COL_ID} = ?",
            arrayOf(id.toString())
        )
    }

    override fun updateLabel(id: Long, label: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DBHelper.COL_LABEL, label)
        }
        db.update(
            DBHelper.TABLE_EVENT,
            values,
            "${DBHelper.COL_ID} = ?",
            arrayOf(id.toString())
        )
    }

    override fun queryLabels(): List<String> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            true,  // distinct
            DBHelper.TABLE_EVENT,
            arrayOf(DBHelper.COL_LABEL),
            "${DBHelper.COL_LABEL} IS NOT NULL AND ${DBHelper.COL_LABEL} != ''",
            null, null, null,
            "${DBHelper.COL_LABEL} ASC",
            null
        )
        val result = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                it.getString(0)?.let { label -> result.add(label) }
            }
        }
        return result
    }

    override fun queryLabelCounts(): List<Pair<String, Int>> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DBHelper.TABLE_EVENT,
            arrayOf(DBHelper.COL_LABEL, "COUNT(*) AS cnt"),
            "${DBHelper.COL_LABEL} IS NOT NULL AND ${DBHelper.COL_LABEL} != ''",
            null,
            DBHelper.COL_LABEL,
            null,
            "cnt DESC, ${DBHelper.COL_LABEL} ASC",
            null
        )
        val result = mutableListOf<Pair<String, Int>>()
        cursor.use {
            while (it.moveToNext()) {
                val label = it.getString(0) ?: continue
                result.add(label to it.getInt(1))
            }
        }
        return result
    }

    override fun countAll(): Int {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DBHelper.TABLE_EVENT,
            arrayOf("COUNT(*)"),
            null, null, null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    override fun queryByLabel(label: String, limit: Int): List<Event> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DBHelper.TABLE_EVENT,
            null,
            "${DBHelper.COL_LABEL} = ?",
            arrayOf(label),
            null, null,
            "${DBHelper.COL_CREATED_AT} DESC",
            limit.toString()
        )
        val result = mutableListOf<Event>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(cursor.toEvent())
            }
        }
        return result
    }

    override fun search(query: String, limit: Int): List<Event> {
        val db = dbHelper.readableDatabase
        val like = "%$query%"
        val where = """
            ${DBHelper.COL_NOTE} LIKE ? OR 
            ${DBHelper.COL_ADDRESS} LIKE ? OR 
            ${DBHelper.COL_LABEL} LIKE ? OR
            strftime('%Y-%m-%d', ${DBHelper.COL_CREATED_AT} / 1000, 'unixepoch', 'localtime') LIKE ? OR
            strftime('%m', ${DBHelper.COL_CREATED_AT} / 1000, 'unixepoch', 'localtime') || '月' || strftime('%d', ${DBHelper.COL_CREATED_AT} / 1000, 'unixepoch', 'localtime') || '日' LIKE ?
        """.trimIndent()
        val args = arrayOf(like, like, like, like, like)
        val cursor = db.query(
            DBHelper.TABLE_EVENT,
            null, where, args, null, null,
            "${DBHelper.COL_CREATED_AT} DESC",
            limit.toString()
        )
        val result = mutableListOf<Event>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(cursor.toEvent())
            }
        }
        return result
    }

    override fun queryAll(): List<Event> = queryRecentPage(Int.MAX_VALUE, 0)

    override fun queryRecentPage(limit: Int, offset: Int): List<Event> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DBHelper.TABLE_EVENT,
            null, null, null, null, null,
            "${DBHelper.COL_CREATED_AT} DESC",
            "$limit OFFSET $offset"
        )
        val result = mutableListOf<Event>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(cursor.toEvent())
            }
        }
        return result
    }

    override fun queryRecent(limit: Int): List<Event> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DBHelper.TABLE_EVENT,
            null, null, null, null, null,
            "${DBHelper.COL_CREATED_AT} DESC",
            limit.toString()
        )
        val result = mutableListOf<Event>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(cursor.toEvent())
            }
        }
        return result
    }

    override fun findByUuid(uuid: String): Event? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DBHelper.TABLE_EVENT,
            null,
            "${DBHelper.COL_UUID} = ?",
            arrayOf(uuid),
            null,
            null,
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.toEvent() else null
        }
    }

    override fun deleteById(id: Long) {
        val db = dbHelper.writableDatabase
        // 取出需级联删除的文件信息
        val fileName: String? = queryById(id)?.audioFileName
        // 拍照签到产生的私有照片文件（MEDIA_SOURCE_PHOTO），删除事件时一并清理
        val photoFiles = mutableListOf<String>()
        db.query(
            DBHelper.TABLE_EVENT_PHOTO,
            arrayOf(DBHelper.COL_EP_PHOTO_URI, DBHelper.COL_EP_MEDIA_SOURCE),
            "${DBHelper.COL_EP_EVENT_ID} = ?",
            arrayOf(id.toString()),
            null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) == Event.MEDIA_SOURCE_PHOTO) {
                    photoFiles.add(c.getString(0))
                }
            }
        }
        db.delete(DBHelper.TABLE_EVENT_PHOTO, "${DBHelper.COL_EP_EVENT_ID} = ?", arrayOf(id.toString()))
        val deleted = db.delete(DBHelper.TABLE_EVENT, "${DBHelper.COL_ID} = ?", arrayOf(id.toString()))
        if (deleted > 0) {
            if (fileName != null) AudioFileUtil.deleteAudioFile(context, fileName)
            // 删除 App 私有照片文件
            for (uriStr in photoFiles) {
                try {
                    val uri = android.net.Uri.parse(uriStr)
                    if (uri.scheme == "file") {
                        java.io.File(uri.path ?: continue).delete()
                    }
                } catch (_: Exception) { /* 忽略 */ }
            }
        }
    }

    override fun findByPhotoUri(photoUri: String): Event? {
        val db = dbHelper.readableDatabase
        // 先查多图表（新数据）
        db.query(
            DBHelper.TABLE_EVENT_PHOTO,
            arrayOf(DBHelper.COL_EP_EVENT_ID),
            "${DBHelper.COL_EP_PHOTO_URI} = ?",
            arrayOf(photoUri),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) {
                val eventId = c.getLong(0)
                return queryById(eventId)
            }
        }
        // 回退到 event 封面（旧导入数据）
        db.query(
            DBHelper.TABLE_EVENT,
            null,
            "${DBHelper.COL_PHOTO_URI} = ?",
            arrayOf(photoUri),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) return c.toEvent()
        }
        return null
    }

    override fun insertEventPhoto(
        eventId: Long,
        photoUri: String,
        mediaSource: Int,
        createdAt: Long
    ): Long {
        val db = dbHelper.writableDatabase
        // 计算当前最大排序值 + 1
        var maxSort = 0
        db.query(
            DBHelper.TABLE_EVENT_PHOTO,
            arrayOf("MAX(${DBHelper.COL_EP_SORT})"),
            "${DBHelper.COL_EP_EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null, null, null
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) maxSort = c.getInt(0)
        }
        val values = ContentValues().apply {
            put(DBHelper.COL_EP_EVENT_ID, eventId)
            put(DBHelper.COL_EP_PHOTO_URI, photoUri)
            put(DBHelper.COL_EP_MEDIA_SOURCE, mediaSource)
            put(DBHelper.COL_EP_CREATED_AT, createdAt)
            put(DBHelper.COL_EP_SORT, maxSort + 1)
        }
        val rowId = db.insert(DBHelper.TABLE_EVENT_PHOTO, null, values)
        // 若事件尚无封面，则本图设为封面
        val ev = queryById(eventId)
        if (ev != null && ev.photoUri.isNullOrBlank()) {
            setCoverPhoto(eventId, photoUri)
        }
        return rowId
    }

    override fun getEventPhotos(eventId: Long): List<EventPhoto> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DBHelper.TABLE_EVENT_PHOTO,
            null,
            "${DBHelper.COL_EP_EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null, null,
            "${DBHelper.COL_EP_SORT} ASC, ${DBHelper.COL_EP_CREATED_AT} ASC, ${DBHelper.COL_EP_ID} ASC"
        )
        val result = mutableListOf<EventPhoto>()
        cursor.use {
            while (it.moveToNext()) result.add(it.toEventPhoto())
        }
        if (result.isNotEmpty()) return result
        // 兼容旧数据：多图表为空但 event 有封面
        val cover = queryById(eventId)?.photoUri
        if (!cover.isNullOrBlank()) {
            result.add(
                EventPhoto(
                    eventId = eventId,
                    photoUri = cover,
                    mediaSource = Event.MEDIA_SOURCE_IMPORTED,
                    createdAt = queryById(eventId)?.createdAt ?: 0L
                )
            )
        }
        return result
    }

    override fun deleteEventPhoto(photoId: Long) {
        val db = dbHelper.writableDatabase
        // 取出被删照片信息，判断是否需要更新封面
        var deletedUri: String? = null
        var eventId: Long = -1
        db.query(
            DBHelper.TABLE_EVENT_PHOTO,
            arrayOf(DBHelper.COL_EP_EVENT_ID, DBHelper.COL_EP_PHOTO_URI),
            "${DBHelper.COL_EP_ID} = ?",
            arrayOf(photoId.toString()),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) {
                eventId = c.getLong(0)
                deletedUri = c.getString(1)
            }
        }
        db.delete(
            DBHelper.TABLE_EVENT_PHOTO,
            "${DBHelper.COL_EP_ID} = ?",
            arrayOf(photoId.toString())
        )
        // 若删除的是封面，更新为剩余第一张或清空
        val ev = queryById(eventId)
        if (ev != null && ev.photoUri == deletedUri) {
            val remaining = getEventPhotos(eventId).firstOrNull()
            setCoverPhoto(eventId, remaining?.photoUri)
        }
    }

    override fun setCoverPhoto(eventId: Long, photoUri: String?) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DBHelper.COL_PHOTO_URI, photoUri)
        }
        db.update(
            DBHelper.TABLE_EVENT,
            values,
            "${DBHelper.COL_ID} = ?",
            arrayOf(eventId.toString())
        )
    }

    override fun queryById(id: Long): Event? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DBHelper.TABLE_EVENT,
            null,
            "${DBHelper.COL_ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.toEvent() else null
        }
    }

    private fun android.database.Cursor.toEvent(): Event {
        fun colInt(col: String) = getColumnIndexOrThrow(col)
        return Event(
            id = getLong(colInt(DBHelper.COL_ID)),
            uuid = getString(colInt(DBHelper.COL_UUID)) ?: "",
            createdAt = getLong(colInt(DBHelper.COL_CREATED_AT)),
            latitude = if (isNull(colInt(DBHelper.COL_LATITUDE))) null
                       else getDouble(colInt(DBHelper.COL_LATITUDE)),
            longitude = if (isNull(colInt(DBHelper.COL_LONGITUDE))) null
                        else getDouble(colInt(DBHelper.COL_LONGITUDE)),
            address = getString(colInt(DBHelper.COL_ADDRESS)),
            audioFileName = getString(colInt(DBHelper.COL_AUDIO_FILE_NAME)),
            audioDuration = if (isNull(colInt(DBHelper.COL_AUDIO_DURATION))) null
                            else getInt(colInt(DBHelper.COL_AUDIO_DURATION)),
            note = getString(colInt(DBHelper.COL_NOTE)),
            label = getString(colInt(DBHelper.COL_LABEL)),
            status = getInt(colInt(DBHelper.COL_STATUS)),
            photoUri = getString(colInt(DBHelper.COL_PHOTO_URI)),
            mediaSource = if (isNull(colInt(DBHelper.COL_MEDIA_SOURCE))) Event.MEDIA_SOURCE_MARK
                          else getInt(colInt(DBHelper.COL_MEDIA_SOURCE))
        )
    }

    private fun android.database.Cursor.toEventPhoto(): EventPhoto {
        fun colInt(col: String) = getColumnIndexOrThrow(col)
        return EventPhoto(
            id = getLong(colInt(DBHelper.COL_EP_ID)),
            eventId = getLong(colInt(DBHelper.COL_EP_EVENT_ID)),
            photoUri = getString(colInt(DBHelper.COL_EP_PHOTO_URI)) ?: "",
            mediaSource = if (isNull(colInt(DBHelper.COL_EP_MEDIA_SOURCE))) Event.MEDIA_SOURCE_IMPORTED
                          else getInt(colInt(DBHelper.COL_EP_MEDIA_SOURCE)),
            createdAt = if (isNull(colInt(DBHelper.COL_EP_CREATED_AT))) 0L
                        else getLong(colInt(DBHelper.COL_EP_CREATED_AT))
        )
    }
}
