package com.ytmp3

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryRecord(
    val id: String,
    val url: String,
    val title: String,
    val timestamp: Long,
    val status: String
)

class HistoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 1) {

    companion object {
        @Volatile private var inst: HistoryDb? = null
        fun get(ctx: Context) = inst ?: synchronized(this) {
            inst ?: HistoryDb(ctx).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (id TEXT PRIMARY KEY, url TEXT, title TEXT, timestamp INTEGER, status TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS history")
        onCreate(db)
    }

    fun insert(r: HistoryRecord) {
        writableDatabase.insertWithOnConflict(
            "history", null,
            ContentValues().apply {
                put("id", r.id)
                put("url", r.url)
                put("title", r.title)
                put("timestamp", r.timestamp)
                put("status", r.status)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getAll(): List<HistoryRecord> {
        val list = mutableListOf<HistoryRecord>()
        readableDatabase.rawQuery(
            "SELECT id, url, title, timestamp, status FROM history ORDER BY timestamp DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                list += HistoryRecord(
                    id = c.getString(0),
                    url = c.getString(1),
                    title = c.getString(2),
                    timestamp = c.getLong(3),
                    status = c.getString(4)
                )
            }
        }
        return list
    }

    fun clearAll() {
        writableDatabase.delete("history", null, null)
    }

    fun findByUrl(url: String): HistoryRecord? {
        readableDatabase.rawQuery(
            "SELECT id,url,title,timestamp,status FROM history WHERE url=? AND status='DONE' LIMIT 1",
            arrayOf(url)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return HistoryRecord(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4))
        }
    }
}
