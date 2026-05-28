package app.markline.storage

import android.content.ContentValues
import android.content.Context
import app.markline.domain.Event
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
            // 插入时位置、音频均为 null，等待异步补全
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

    override fun queryAll(): List<Event> = queryRecent(Int.MAX_VALUE)

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
        // 删除前取出音频文件名，用于级联删除文件
        val fileName: String? = queryById(id)?.audioFileName
        val deleted = db.delete(DBHelper.TABLE_EVENT, "${DBHelper.COL_ID} = ?", arrayOf(id.toString()))
        if (deleted > 0 && fileName != null) {
            AudioFileUtil.deleteAudioFile(context, fileName)
        }
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
            status = getInt(colInt(DBHelper.COL_STATUS))
        )
    }
}
