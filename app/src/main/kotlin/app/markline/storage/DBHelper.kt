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
        const val DATABASE_VERSION = 9

        // 表名
        const val TABLE_EVENT = "event"
        const val TABLE_EVENT_PHOTO = "event_photo"
        const val TABLE_SYNC_TOMBSTONE = "sync_tombstone"

        // 列名
        const val COL_ID = "id"
        const val COL_CREATED_AT = "created_at"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_ADDRESS = "address"
        const val COL_ADDRESS_MANUAL = "address_manual"
        const val COL_AUDIO_FILE_NAME = "audio_file_name"
        const val COL_AUDIO_DURATION = "audio_duration"
        const val COL_NOTE = "note"
        const val COL_LABEL = "label"
        const val COL_STATUS = "status"
        const val COL_UUID = "uuid"
        const val COL_PHOTO_URI = "photo_uri"
        const val COL_MEDIA_SOURCE = "media_source"
        const val COL_SOURCE_URI = "source_uri"
        const val COL_UPDATED_AT = "updated_at"

        // event_photo 列名
        const val COL_EP_ID = "_id"
        const val COL_EP_EVENT_ID = "event_id"
        const val COL_EP_PHOTO_URI = "photo_uri"
        const val COL_EP_MEDIA_SOURCE = "media_source"
        const val COL_EP_CREATED_AT = "created_at"
        const val COL_EP_SORT = "sort_order"
        const val COL_EP_SOURCE_URI = "source_uri"
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
                $COL_ADDRESS_MANUAL INTEGER NOT NULL DEFAULT 0,
                $COL_AUDIO_FILE_NAME    TEXT,
                $COL_AUDIO_DURATION INTEGER,
                $COL_NOTE          TEXT,
                $COL_LABEL         TEXT,
                $COL_STATUS        INTEGER DEFAULT 0,
                $COL_PHOTO_URI     TEXT,
                $COL_MEDIA_SOURCE  INTEGER DEFAULT 0,
                $COL_SOURCE_URI    TEXT,
                $COL_UPDATED_AT    INTEGER
            )
            """.trimIndent()
        )

        // 加速时间轴查询
        db.execSQL("CREATE INDEX idx_event_created_at ON $TABLE_EVENT($COL_CREATED_AT DESC)")
        // 加速 UUID 查找
        db.execSQL("CREATE UNIQUE INDEX idx_event_uuid ON $TABLE_EVENT($COL_UUID)")

        createEventPhotoTable(db)
        createSyncTombstoneTable(db)
    }

    private fun createEventPhotoTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_EVENT_PHOTO (
                ${COL_EP_ID}            INTEGER PRIMARY KEY AUTOINCREMENT,
                ${COL_EP_EVENT_ID}      INTEGER NOT NULL,
                ${COL_EP_PHOTO_URI}     TEXT NOT NULL,
                ${COL_EP_MEDIA_SOURCE}  INTEGER DEFAULT 0,
                ${COL_EP_CREATED_AT}    INTEGER,
                ${COL_EP_SORT}          INTEGER DEFAULT 0,
                ${COL_EP_SOURCE_URI}    TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ep_event_id ON $TABLE_EVENT_PHOTO($COL_EP_EVENT_ID)")
    }

    private fun createSyncTombstoneTable(db: SQLiteDatabase) {
        // 已删除记录的墓碑：云同步时向其他设备传播删除操作，防止被远端复活
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SYNC_TOMBSTONE (
                $COL_UUID      TEXT PRIMARY KEY,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
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
        if (oldVersion < 5) {
            // v4 → v5: 新增 photo_uri、media_source 列（相册导入）
            db.execSQL("ALTER TABLE $TABLE_EVENT ADD COLUMN $COL_PHOTO_URI TEXT")
            db.execSQL("ALTER TABLE $TABLE_EVENT ADD COLUMN $COL_MEDIA_SOURCE INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            // v5 → v6: 新增 event_photo 多图表（事件详情可追加现场照片）
            createEventPhotoTable(db)
        }
        if (oldVersion < 7) {
            // v6 → v7: source_uri 留档照片原始来源 Uri（去重用），
            // 存量导入数据的 photo_uri 即原始 content Uri，回填一份
            db.execSQL("ALTER TABLE $TABLE_EVENT ADD COLUMN $COL_SOURCE_URI TEXT")
            db.execSQL("ALTER TABLE $TABLE_EVENT_PHOTO ADD COLUMN $COL_EP_SOURCE_URI TEXT")
            db.execSQL(
                "UPDATE $TABLE_EVENT SET $COL_SOURCE_URI = $COL_PHOTO_URI " +
                "WHERE $COL_MEDIA_SOURCE = 1 AND $COL_PHOTO_URI LIKE 'content:%'"
            )
            db.execSQL(
                "UPDATE $TABLE_EVENT_PHOTO SET $COL_EP_SOURCE_URI = $COL_EP_PHOTO_URI " +
                "WHERE $COL_EP_MEDIA_SOURCE = 1 AND $COL_EP_PHOTO_URI LIKE 'content:%'"
            )
        }
        if (oldVersion < 8) {
            // v7 → v8: updated_at（云同步字段级冲突合并依据）+ 墓碑表
            db.execSQL("ALTER TABLE $TABLE_EVENT ADD COLUMN $COL_UPDATED_AT INTEGER")
            db.execSQL("UPDATE $TABLE_EVENT SET $COL_UPDATED_AT = $COL_CREATED_AT WHERE $COL_UPDATED_AT IS NULL")
            createSyncTombstoneTable(db)
        }
        if (oldVersion < 9) {
            // v8 → v9: address_manual（地址手动编辑标记，落库后永久阻断自动重解析覆盖）
            db.execSQL("ALTER TABLE $TABLE_EVENT ADD COLUMN $COL_ADDRESS_MANUAL INTEGER NOT NULL DEFAULT 0")
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
