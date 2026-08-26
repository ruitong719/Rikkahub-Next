package me.rerere.rikkahub.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import me.rerere.workspace.WorkspaceBindMount
import java.io.File

/**
 * 手机存储直连挂载管理。
 *
 * 持有 MANAGE_EXTERNAL_STORAGE（所有文件访问）权限后，共享存储根目录是宿主机真实路径，
 * 可直接以 bind mount 挂进 PRoot。与早期「SAF 物化缓存 + push/pull 同步」方案不同：
 * 无缓存副本、无同步循环，沙箱内对 /mnt/storage 的读写、重命名、删除都实时作用于手机文件。
 *
 * 已知限制：Android 11+ 上第三方应用即使持有全部文件权限也无法访问 Android/data 与
 * Android/obb（MediaProvider 额外屏蔽，需 Shizuku/root 才能突破）。
 */
class WorkspaceMountManager(
    private val context: Context,
) {
    /** 手机共享存储根目录（如 /storage/emulated/0） */
    fun storageRoot(): File = Environment.getExternalStorageDirectory()

    /**
     * 全部文件访问权限是否已授予。
     * API < 30 无此权限模型，沿用传统外部存储行为，视为可用。
     */
    fun isStorageAccessGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * 当前生效的 bind mounts：授权后把手机存储根目录直连挂到 [PHONE_MOUNT_TARGET]；
     * 未授权时返回空列表，沙箱内不存在该挂载点。
     */
    fun activeBindMounts(): List<WorkspaceBindMount> {
        if (!isStorageAccessGranted()) return emptyList()
        val root = storageRoot()
        if (!root.isDirectory) return emptyList()
        return listOf(WorkspaceBindMount(source = root, target = PHONE_MOUNT_TARGET))
    }

    /** 打开系统的「所有文件访问」授权页并定位到本应用；部分 ROM 需回退到通用列表页 */
    fun buildAllFilesAccessSettingsIntent(): Intent {
        val uri = Uri.fromParts("package", context.packageName, null)
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        /** 手机存储根目录在 rootfs 内的挂载点 */
        const val PHONE_MOUNT_TARGET = "/mnt/storage"

        /** 通用回退：系统「所有文件访问」应用列表页 */
        fun buildAllFilesAccessListIntent(): Intent =
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
