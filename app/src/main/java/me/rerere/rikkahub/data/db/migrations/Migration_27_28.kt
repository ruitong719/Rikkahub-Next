package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_27_28"

/**
 * 27 -> 28: 添加 rolling_context_summary 列用于持久化滚动摘要上下文。
 */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 27 to 28 (add rolling_context_summary column)")
        db.execSQL(
            "ALTER TABLE ConversationEntity ADD COLUMN rolling_context_summary TEXT NOT NULL DEFAULT ''"
        )
        Log.i(TAG, "migrate: migrate from 27 to 28 success")
    }
}
