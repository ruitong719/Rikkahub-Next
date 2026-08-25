package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_28_29"

/**
 * 28 -> 29: 移除记忆功能，删除 MemoryEntity 表。
 */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 28 to 29 (drop memoryentity table)")
        db.execSQL("DROP TABLE IF EXISTS memoryentity")
        Log.i(TAG, "migrate: migrate from 28 to 29 success")
    }
}
