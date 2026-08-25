package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_30_31"

/**
 * 30 -> 31: workspace 核心工具改名（对齐 opencode，2026-08-26）后的历史数据改写。
 *
 * - message_node.messages / favorites.*：把旧工具调用记录里的 toolName 改写为新名，
 *   保证 UI 渲染器（ToolUIRegistry 按 toolName 精确匹配）能命中专用渲染
 *   （kotlinx.serialization 输出为无空格紧凑 JSON，'"toolName":"old"' 模式可精确替换）
 * - workspaces.tool_approvals / tool_prompts：按工作区的用户覆盖键改名，
 *   锚定 `"old":`（键后跟冒号）避免误伤提示词正文里出现的同名文本
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 30 to 31 (rename legacy workspace tool names)")

        // 工具改名映射；已删除的工具（mount_*）不改名——通用渲染器本就能兜底展示
        val renames = mapOf(
            "workspace_read_file" to "read",
            "workspace_write_file" to "write",
            "workspace_edit_file" to "edit",
            "workspace_shell" to "bash",
        )

        // 消息 JSON 列：逐个旧名做链式 REPLACE
        val messageColumns = listOf(
            "message_node.messages",
            "favorites.snapshot_json",
            "favorites.ref_json",
            "favorites.meta_json",
        )
        for (target in messageColumns) {
            var expr = target
            renames.forEach { (old, new) ->
                expr = "REPLACE($expr, '\"toolName\":\"$old\"', '\"toolName\":\"$new\"')"
            }
            val table = target.substringBefore('.')
            val column = target.substringAfter('.')
            db.execSQL("UPDATE $table SET $column = $expr")
        }

        // 工作区审批/提示词覆盖的 JSON 键改名（锚定冒号，防止误伤值内容）
        for ((old, new) in renames) {
            db.execSQL(
                "UPDATE workspaces SET " +
                    "tool_approvals = REPLACE(tool_approvals, '\"$old\":', '\"$new\":'), " +
                    "tool_prompts = REPLACE(COALESCE(tool_prompts, '{}'), '\"$old\":', '\"$new\":')"
            )
        }

        Log.i(TAG, "migrate: migrate from 30 to 31 success")
    }
}
