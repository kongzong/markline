package com.example.markline.storage

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
        const val DATABASE_VERSION = 2

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
        const val COL_STATUS = "status"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_EVENT (
                $COL_ID            INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CREATED_AT    INTEGER NOT NULL,
                $COL_LATITUDE      REAL,
                $COL_LONGITUDE     REAL,
                $COL_ADDRESS       TEXT,
                $COL_AUDIO_FILE_NAME    TEXT,
                $COL_AUDIO_DURATION INTEGER,
                $COL_NOTE          TEXT,
                $COL_STATUS        INTEGER DEFAULT 0
            )
            """.trimIndent()
        )

        // 加速时间轴查询
        db.execSQL("CREATE INDEX idx_event_created_at ON $TABLE_EVENT($COL_CREATED_AT DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 → v2: audio_path 重命名为 audio_file_name
            db.execSQL("ALTER TABLE $TABLE_EVENT RENAME COLUMN audio_path TO audio_file_name")
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // 开启 WAL 模式，写入速度更快，读写不互斥
        if (!db.isReadOnly) {
            db.enableWriteAheadLogging()
        }
    }
}
