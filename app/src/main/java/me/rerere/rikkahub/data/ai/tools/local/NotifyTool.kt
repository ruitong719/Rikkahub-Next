package me.rerere.rikkahub.data.ai.tools.local

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.AI_NOTIFY_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid

internal const val NOTIFY_TOOL_NAME = "notify"

/**
 * notify: 让模型向用户设备发送系统通知（点击深链回发起对话）。
 * 与后台任务完成提醒（BackgroundTaskReminderTransformer，喂给模型）正交：
 * 本工具面向用户本人。
 */
internal fun buildNotifyTool(
    context: Context,
    conversationId: Uuid?,
): Tool = Tool(
    name = NOTIFY_TOOL_NAME,
    description = "Send a notification to the user's device (shows in the system notification bar; tapping it opens this conversation). " +
        "Use it when the user asks you to remind them of something, or when something needs their attention right away.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification body text")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional notification title")
                })
            },
            required = listOf("message"),
        )
    },
    execute = {
        val params = it.jsonObject
        val message = params["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (message.isEmpty()) error("message is required")
        val title = params["title"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)

        // 权限缺失不抛错：结构化返回让模型能转告用户去开通知开关
        val delivered = context.sendNotification(
            channelId = AI_NOTIFY_NOTIFICATION_CHANNEL_ID,
            notificationId = message.hashCode(),
        ) {
            this.title = title
            content = message
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            useBigTextStyle = true
            if (conversationId != null) {
                contentIntent = PendingIntent.getActivity(
                    context,
                    conversationId.hashCode(),
                    Intent(context, RouteActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("conversationId", conversationId.toString())
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("delivered", delivered)
                    if (delivered) {
                        put("message", "Notification sent to the device.")
                    } else {
                        put("error", "Notification permission not granted; ask the user to enable notifications for this app.")
                    }
                }.toString()
            )
        )
    },
)
