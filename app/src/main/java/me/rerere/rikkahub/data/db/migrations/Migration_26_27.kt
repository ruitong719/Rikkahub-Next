package me.rerere.rikkahub.data.db.migrations

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec

/**
 * 26 -> 27：删除模式注入/世界书功能后，移除 conversation 表中对应的两列。
 * 数据均为可丢弃的 JSON 绑定列表（模式注入/世界书定义已随功能整体下线）。
 */
@DeleteColumn(tableName = "ConversationEntity", columnName = "mode_injection_ids")
@DeleteColumn(tableName = "ConversationEntity", columnName = "lorebook_ids")
class Migration_26_27 : AutoMigrationSpec
