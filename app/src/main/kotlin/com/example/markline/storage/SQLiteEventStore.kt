package com.example.markline.storage

import android.content.ContentValues
import android.content.Context
import com.example.markline.domain.Event
import com.example.markline.domain.EventStore

/**
 * EventStore 的 SQLite 实现
 * 使用 DBHelper 直接操作 SQLite，不使用 Room 或 ORM
 */
class SQLiteEventStore(context: Context) : EventStore {

    private val dbHelper = DBHelper(context)

    override fun insert(event: Event): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
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
        audioPath: String?,
        audioDuration: Int?,
        status: Int?
    ) {
        val db = dbHelper.writableDatabase
        val values = ContentValues()
        latitude?.let { values.put(DBHelper.COL_LATITUDE, it) }
        longitude?.let { values.put(DBHelper.COL_LONGITUDE, it) }
        address?.let { values.put(DBHelper.COL_ADDRESS, it) }
        audioPath?.let { values.put(DBHelper.COL_AUDIO_PATH, it) }
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

    override fun deleteById(id: Long) {
        val db = dbHelper.writableDatabase
        db.delete(DBHelper.TABLE_EVENT, "${DBHelper.COL_ID} = ?", arrayOf(id.toString()))
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
            createdAt = getLong(colInt(DBHelper.COL_CREATED_AT)),
            latitude = if (isNull(colInt(DBHelper.COL_LATITUDE))) null
                       else getDouble(colInt(DBHelper.COL_LATITUDE)),
            longitude = if (isNull(colInt(DBHelper.COL_LONGITUDE))) null
                        else getDouble(colInt(DBHelper.COL_LONGITUDE)),
            address = getString(colInt(DBHelper.COL_ADDRESS)),
            audioPath = getString(colInt(DBHelper.COL_AUDIO_PATH)),
            audioDuration = if (isNull(colInt(DBHelper.COL_AUDIO_DURATION))) null
                            else getInt(colInt(DBHelper.COL_AUDIO_DURATION)),
            note = getString(colInt(DBHelper.COL_NOTE)),
            status = getInt(colInt(DBHelper.COL_STATUS))
        )
    }
}
