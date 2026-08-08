package me.rerere.rikkahub.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 数据库备份一致性管理器。
 *
 * 背景：WAL 模式下最新事务先写 `-wal` 文件，主 `-db` 文件落后；若直接裸复制
 * db/wal/shm 三个文件（旧实现），复制期间数据库仍在写入时可能拿到撕裂快照，
 * 导致备份内容缺失最新会话或数据库损坏。
 *
 * 这里先执行 `wal_checkpoint(TRUNCATE)` 把 wal 合并进主库，再复制主库 + wal 兜底
 * （checkpoint 后到复制完成之间仍可能有新写入进入 wal，wal 兜底保证不丢）。
 *
 * TODO(requery): 若 io.requery.android.database.sqlite.SQLiteDatabase.backup(File)
 * 可用，可替换为单文件一致快照（更优）。需在 Android Studio 验证 requery 支持性后启用。
 */
class DatabaseBackupManager(
    private val database: AppDatabase,
) {
    /**
     * 生成一致性快照：主库副本 +（通常为空的）wal 兜底。
     *
     * @return 快照主文件（同目录下可能伴随 `-wal` 兜底文件）
     */
    suspend fun createSnapshot(target: File): File = withContext(Dispatchers.IO) {
        val db = database.openHelper.writableDatabase
        val dbFile = File(db.path)
        require(dbFile.exists()) { "Database file not found: ${dbFile.absolutePath}" }

        // 把 wal 合并进主库并截断 wal（TRUNCATE 后 wal 文件长度为 0）
        db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")

        dbFile.copyTo(target, overwrite = true)

        // 兜底：checkpoint 后到复制完成之间若有新写入，wal 里会重新出现数据
        val walFile = File(dbFile.parentFile, "${dbFile.name}-wal")
        val walTarget = File(target.parentFile, "${target.name}-wal")
        if (walFile.exists() && walFile.length() > 0) {
            walFile.copyTo(walTarget, overwrite = true)
        } else {
            walTarget.delete()
        }
        target
    }

    /**
     * 恢复数据库快照。
     *
     * 注意：这里**不关闭** Room 连接——`RoomDatabase.close()` 是终态操作，close 后单例
     * 不可重新打开，任何后续 DB 访问都会抛异常。因此恢复只替换磁盘文件，当前进程仍读旧库，
     * 调用方**必须**提示用户重启 App 生效（BackupPage 已有 BackupDialog 流程）。
     *
     * 恢复时删除旧 wal/shm：残留的 wal 与替换后的新库不匹配，SQLite 重放会得到错误数据；
     * shm 是共享内存索引，应重建而非恢复。
     *
     * @param snapshot 新格式快照主文件（同目录 `-wal` 会自动作为兜底恢复）
     * @param legacyWal 旧格式备份的 wal 文件（兼容旧备份，为 null 时不恢复 wal）
     * @return true 表示恢复完成，需重启 App
     */
    suspend fun restore(
        snapshot: File,
        legacyWal: File? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val dbFile = File(database.openHelper.writableDatabase.path)
        require(snapshot.exists()) { "Snapshot file not found: ${snapshot.absolutePath}" }
        dbFile.parentFile?.mkdirs()

        snapshot.copyTo(dbFile, overwrite = true)

        // 旧 wal/shm 一律删除，避免与新库不匹配
        File(dbFile.parentFile, "${dbFile.name}-wal").delete()
        File(dbFile.parentFile, "${dbFile.name}-shm").delete()

        // 新格式：同目录 -wal 兜底文件
        val snapshotWal = File(snapshot.parentFile, "${snapshot.name}-wal")
        if (snapshotWal.exists() && snapshotWal.length() > 0) {
            snapshotWal.copyTo(File(dbFile.parentFile, "${dbFile.name}-wal"), overwrite = true)
        }

        // 旧格式：显式传入的 wal
        if (legacyWal != null && legacyWal.exists() && legacyWal.length() > 0) {
            legacyWal.copyTo(File(dbFile.parentFile, "${dbFile.name}-wal"), overwrite = true)
        }

        true
    }
}
