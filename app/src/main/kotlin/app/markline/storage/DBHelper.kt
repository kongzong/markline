package app.markline.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLiteOpenHelper — MarkLine 数据库
 * 开启 WAL 模式保证写入 < 50ms
 */
class DBHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "markline.db"
        const val DATABASE_VERSION = 4

        // 表名
        const val TABLE_EVENT = "event"

        // 列名
        const val COL_ID = "id"
        const val COL_CREATED_AT = "created_at"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_ADDRESS = "address"
        const val COL_AUDIO_FILE_NAME = "audio_file_name"
        const val COL_AUDIO_DURATION = "audio_duration"
        const val COL_NOTE = "note"
        const val COL_LABEL = "label"
        const val COL_STATUS = "status"
        const val COL_UUID = "uuid"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_EVENT (
                $COL_ID            INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_UUID          TEXT NOT NULL DEFAULT '',
                $COL_CREATED_AT    INTEGER NOT NULL,
                $COL_LATITUDE      REAL,
                $COL_LONGITUDE     REAL,
                $COL_ADDRESS       TEXT,
                $COL_AUDIO_FILE_NAME    TEXT,
                $COL_AUDIO_DURATION INTEGER,
                $COL_NOTE          TEXT,
                $COL_LABEL         TEXT,
                $COL_STATUS        INTEGER DEFAULT 0
            )
            """.trimIndent()
        )

        // 加速时间轴查询
        db.execSQL("CREATE INDEX idx_event_created_at ON $TABLE_EVENT($COL_CREATED_AT DESC)")
        // 加速 UUID 查找
        db.execSQL("CREATE UNIQUE INDEX idx_event_uuid ON $TABLE_EVENT($COL_UUID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 → v2: audio_path 重命名为 audio_file_name
            db.execSQL("ALTER TABLE $TABLE_EVENT RENAME COLUMN audio_path TO audio_file_name")
        }
        if (oldVersion < 3) {
            // v2 → v3: 新增 label 列
            db.execSQL("ALTER TABLE $TABLE_EVENT ADD COLUMN $COL_LABEL TEXT")
        }
        if (oldVersion < 4) {
            // v3 → v4: 新增 uuid 列 + 唯一索引 + 回填已有数据
            db.execSQL("ALTER TABLE $TABLE_EVENT ADD COLUMN $COL_UUID TEXT NOT NULL DEFAULT ''")
            // 回填已有记录的 uuid
            val cursor = db.rawQuery("SELECT $COL_ID FROM $TABLE_EVENT", null)
            val updates = mutableListOf<Pair<Long, String>>()
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val uuid = java.util.UUID.randomUUID().toString()
                    updates.add(id to uuid)
                }
            }
            db.beginTransaction()
            try {
                for ((id, uuid) in updates) {
                    db.execSQL(
                        "UPDATE $TABLE_EVENT SET $COL_UUID = ? WHERE $COL_ID = ?",
                        arrayOf(uuid, id.toString())
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.execSQL("CREATE UNIQUE INDEX idx_event_uuid ON $TABLE_EVENT($COL_UUID)")
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // 开启 WAL 模式，写入速度更快，读写不互斥
        // 注意：如果已有其他连接打开了 WAL，enableWriteAheadLogging 会尝试先关闭再打开，
        // 此时会报 "database is locked"。所以先检查是否已是 WAL，是则跳过。
        if (!db.isReadOnly) {
            try {
                val cursor = db.rawQuery("PRAGMA journal_mode", null)
                val current = cursor.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (current != null && !current.equals("wal", ignoreCase = true)) {
                    db.enableWriteAheadLogging()
                }
            } catch (_: Exception) {
                // 静默失败，不阻塞数据库打开
            }
        }
    }
}
