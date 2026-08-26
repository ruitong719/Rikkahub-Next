package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import me.rerere.rikkahub.R

/** Web 服务器通知上的「复制地址」动作：把当前完整地址写入剪贴板 */
class WebServerCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(context, context.getString(R.string.notification_web_server_copied), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
