package me.rerere.rikkahub.data.db.migrations

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec

/**
 * 33 -> 34：workspace_export_to_phone 工具随手机存储直连挂载（/mnt/storage）下线，
 * 移除 workspaces 表中已无消费者的导出目标目录列。
 */
@DeleteColumn(tableName = "workspaces", columnName = "export_target_uri")
class Migration_33_34 : AutoMigrationSpec
