package app.markline.storage

import android.content.ContentValues
import android.content.Context
import app.markline.domain.Event
import app.markline.domain.EventPhoto
import app.markline.domain.EventStore
import app.markline.util.AudioFileUtil
import app.markline.util.PhotoStore

/**
 * EventStore 的 SQLite 实现
 * 使用 DBHelper 直接操作 SQLite，不使用 Room 或 ORM
 */
class SQLiteEventStore(private val context: Context) : EventStore {

    companion object {
        /**
         * 数据变更版本号（进程内全局）：任何增删改都会 +1。
         * UI 层观察它即可实现响应式刷新（如 Line 在后台同步下载新记录后自动更新），
         * 无需引入 Room 的 Flow。
         */
        val dataVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
    }

    /** 数据变更后调用：推进全局版本号 */
    private fun bump() {
        dataVersion.value = dataVersion.value + 1
    }

    private val dbHelper = DBHelper(context)

    override fun insert(event: Event): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DBHelper.COL_UUID, event.uuid)
            put(DBHelper.COL_CREATED_AT, event.createdAt)
            put(DBHelper.COL_STATUS, event.status)
            put(
                DBHelper.COL_UPDATED_AT,
                if (event.updatedAt > 0) event.updatedAt else event.createdAt
            )
            // 同步写入调用方已携带的字段（导入场景：主题/定位/照片/来源等）
            event.label?.let { put(DBHelper.COL_LABEL, it) }
            event.latitude?.let { put(DBHelper.COL_LATITUDE, it) }
            event.longitude?.let { put(DBHelper.COL_LONGITUDE, it) }
            event.address?.let { put(DBHelper.COL_ADDRESS, it) }
            event.audioFileName?.let { put(DBHelper.COL_AUDIO_FILE_NAME, it) }
            event.audioDuration?.let { put(DBHelper.COL_AUDIO_DURATION, it) }
            event.note?.let { put(DBHelper.COL_NOTE, it) }
            event.photoUri?.let { put(DBHelper.COL_PHOTO_URI, it) }
            event.sourceUri?.let { put(DBHelper.COL_SOURCE_URI, it) }
            put(DBHelper.COL_MEDIA_SOURCE, event.mediaSource)
            // 定位/录音仍可能在异步阶段通过 updateEnhancement 补全
        }
        val rowId = db.insert(DBHelper.TABLE_EVENT, null, values)
        bump()
        return rowId
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
            // 字段变更 → 刷新 updated_at（云同步冲突合并依据）
            values.put(DBHelper.COL_UPDATED_AT, System.currentTimeMillis())
            db.update(
                DBHelper.TABLE_EVENT,
                values,
                "${DBHelper.COL_ID} = ?",
                arrayOf(id.toString())
            )
            bump()
        }
    }

    override fun updateNote(id: Long, note: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DBHelper.COL_NOTE, note)
            put(DBHelper.COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(
            DBHelper.TABLE_EVENT,
            values,
            "${DBHelper.COL_ID} = ?",
            arrayOf(id.toString())
        )
        bump()
    }

    override fun updateLabel(id: Long, label: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DBHelper.COL_LABEL, label)
            put(DBHelper.COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(
            DBHelper.TABLE_EVENT,
            values,
            "${DBHelper.COL_ID} = ?",
            arrayOf(id.toString())
        )
        bump()
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
        // 取出需级联删除的文件信息（uuid 需在删除前取出，墓碑用）
        val ev = queryById(id)
        val fileName: String? = ev?.audioFileName
        val uuid: String? = ev?.uuid
        // 事件关联的照片（含拍照签到与已本地化的导入图），删除事件时一并清理私有文件
        val photoFiles = mutableListOf<String>()
        db.query(
            DBHelper.TABLE_EVENT_PHOTO,
            arrayOf(DBHelper.COL_EP_PHOTO_URI),
            "${DBHelper.COL_EP_EVENT_ID} = ?",
            arrayOf(id.toString()),
            null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                photoFiles.add(c.getString(0))
            }
        }
        db.delete(DBHelper.TABLE_EVENT_PHOTO, "${DBHelper.COL_EP_EVENT_ID} = ?", arrayOf(id.toString()))
        val deleted = db.delete(DBHelper.TABLE_EVENT, "${DBHelper.COL_ID} = ?", arrayOf(id.toString()))
        if (deleted > 0) {
            if (fileName != null) AudioFileUtil.deleteAudioFile(context, fileName)
            // 删除 App 私有目录内的照片文件（拍照签到 + 已本地化的导入图；外部 content Uri 不动）
            for (uriStr in photoFiles) {
                PhotoStore.resolvePrivateFile(context, uriStr)?.let { file ->
                    try { file.delete() } catch (_: Exception) { /* 忽略 */ }
                }
            }
            // 云同步墓碑：防止下次同步被远端复活
            uuid?.let { addTombstone(it, System.currentTimeMillis()) }
            bump()
        }
    }

    override fun deleteByUuidForSync(uuid: String, deletedAt: Long) {
        val id = findByUuid(uuid)?.id ?: return
        deleteById(id)
        // deleteById 内部已写入本地时间戳墓碑；这里用远端时间戳覆盖为较新者，避免回环重复删除
        addTombstone(uuid, deletedAt)
    }

    override fun upsertFromRemote(event: Event): Long {
        val existing = findByUuid(event.uuid)
        return if (existing != null) {
            // 整行更新（沿用远端字段与 updated_at）；远端 photoUri 为空时保留本地图引用不清空
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put(DBHelper.COL_CREATED_AT, event.createdAt)
                put(DBHelper.COL_LATITUDE, event.latitude)
                put(DBHelper.COL_LONGITUDE, event.longitude)
                put(DBHelper.COL_ADDRESS, event.address)
                put(DBHelper.COL_AUDIO_FILE_NAME, event.audioFileName)
                put(DBHelper.COL_AUDIO_DURATION, event.audioDuration)
                put(DBHelper.COL_NOTE, event.note)
                put(DBHelper.COL_LABEL, event.label)
                put(DBHelper.COL_STATUS, event.status)
                event.photoUri?.let { put(DBHelper.COL_PHOTO_URI, it) }
                event.sourceUri?.let { put(DBHelper.COL_SOURCE_URI, it) }
                put(DBHelper.COL_UPDATED_AT, if (event.updatedAt > 0) event.updatedAt else existing.updatedAt)
                put(DBHelper.COL_MEDIA_SOURCE, event.mediaSource)
            }
            db.update(DBHelper.TABLE_EVENT, values, "${DBHelper.COL_ID} = ?", arrayOf(existing.id.toString()))
            bump()
            existing.id
        } else {
            insert(event)
        }
    }

    override fun getTombstones(): Map<String, Long> {
        val db = dbHelper.readableDatabase
        val result = mutableMapOf<String, Long>()
        db.query(DBHelper.TABLE_SYNC_TOMBSTONE, null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                result[c.getString(0)] = c.getLong(1)
            }
        }
        return result
    }

    override fun addTombstone(uuid: String, deletedAt: Long) {
        val db = dbHelper.writableDatabase
        db.execSQL(
            "INSERT INTO ${DBHelper.TABLE_SYNC_TOMBSTONE}(${DBHelper.COL_UUID}, ${DBHelper.COL_UPDATED_AT}) " +
            "VALUES(?, ?) " +
            "ON CONFLICT(${DBHelper.COL_UUID}) DO UPDATE SET ${DBHelper.COL_UPDATED_AT} = " +
            "MAX(${DBHelper.COL_UPDATED_AT}, excluded.${DBHelper.COL_UPDATED_AT})",
            arrayOf(uuid, deletedAt)
        )
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

    override fun findBySourceUri(sourceUri: String): Event? {
        val db = dbHelper.readableDatabase
        // 先查多图表（新数据）
        db.query(
            DBHelper.TABLE_EVENT_PHOTO,
            arrayOf(DBHelper.COL_EP_EVENT_ID),
            "${DBHelper.COL_EP_SOURCE_URI} = ?",
            arrayOf(sourceUri),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) {
                val eventId = c.getLong(0)
                return queryById(eventId)
            }
        }
        // 回退到 event 封面
        db.query(
            DBHelper.TABLE_EVENT,
            null,
            "${DBHelper.COL_SOURCE_URI} = ?",
            arrayOf(sourceUri),
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
        createdAt: Long,
        sourceUri: String?
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
            sourceUri?.let { put(DBHelper.COL_EP_SOURCE_URI, it) }
        }
        val rowId = db.insert(DBHelper.TABLE_EVENT_PHOTO, null, values)
        // 若事件尚无封面，则本图设为封面
        val ev = queryById(eventId)
        if (ev != null && ev.photoUri.isNullOrBlank()) {
            setCoverPhoto(eventId, photoUri)
        }
        bump()
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
        val ev = queryById(eventId)
        val cover = ev?.photoUri
        if (!cover.isNullOrBlank()) {
            result.add(
                EventPhoto(
                    eventId = eventId,
                    photoUri = cover,
                    mediaSource = ev.mediaSource,
                    createdAt = ev.createdAt,
                    sourceUri = ev.sourceUri
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
        // 清理私有目录内的照片文件（外部 content Uri 不动）
        deletedUri?.let { uriStr ->
            PhotoStore.resolvePrivateFile(context, uriStr)?.let { file ->
                try { file.delete() } catch (_: Exception) { /* 忽略 */ }
            }
        }
        // 若删除的是封面，更新为剩余第一张或清空
        val ev = queryById(eventId)
        if (ev != null && ev.photoUri == deletedUri) {
            val remaining = getEventPhotos(eventId).firstOrNull()
            setCoverPhoto(eventId, remaining?.photoUri)
        }
        bump()
    }

    override fun replacePhotoUri(oldUri: String, newUri: String, sourceUri: String?) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val evValues = ContentValues().apply {
                put(DBHelper.COL_PHOTO_URI, newUri)
                if (sourceUri != null) put(DBHelper.COL_SOURCE_URI, sourceUri)
            }
            db.update(DBHelper.TABLE_EVENT, evValues, "${DBHelper.COL_PHOTO_URI} = ?", arrayOf(oldUri))
            val epValues = ContentValues().apply {
                put(DBHelper.COL_EP_PHOTO_URI, newUri)
                if (sourceUri != null) put(DBHelper.COL_EP_SOURCE_URI, sourceUri)
            }
            db.update(DBHelper.TABLE_EVENT_PHOTO, epValues, "${DBHelper.COL_EP_PHOTO_URI} = ?", arrayOf(oldUri))
            db.setTransactionSuccessful()
            bump()
        } finally {
            db.endTransaction()
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
            sourceUri = getString(colInt(DBHelper.COL_SOURCE_URI)),
            updatedAt = if (isNull(colInt(DBHelper.COL_UPDATED_AT))) 0L
                        else getLong(colInt(DBHelper.COL_UPDATED_AT)),
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
                        else getLong(colInt(DBHelper.COL_EP_CREATED_AT)),
            sourceUri = getString(colInt(DBHelper.COL_EP_SOURCE_URI))
        )
    }
}
