package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.DEFAULT_WORKSPACE_TOOL_PROMPTS
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.SyncDirection
import me.rerere.rikkahub.data.files.WorkspaceMountConfig
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(inputStream, fileName)
    }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(entry, outputStream)
    }
    // 导出到手机的 SAF 树目录选择器：持久化读写权限，供 workspace_export_to_phone 工具使用
    val exportDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { e ->
            android.util.Log.w("WorkspaceDetailPage", "takePersistableUriPermission failed: ${e.message}")
        }
        vm.setExportTargetUri(uri)
    }
    // 手机目录挂载选择器：先输入挂载名，再从系统选择器选目录
    var pendingMountName by remember { mutableStateOf<String?>(null) }
    var showMountNameDialog by remember { mutableStateOf(false) }
    val mountDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val name = pendingMountName.also { pendingMountName = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { e ->
            android.util.Log.w("WorkspaceDetailPage", "takePersistableUriPermission failed: ${e.message}")
        }
        vm.addMount(name, uri)
    }
    val mounts by vm.mounts.collectAsStateWithLifecycle()
    val mountMessage by vm.mountMessage.collectAsStateWithLifecycle()

    if (showMountNameDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMountNameDialog = false },
            title = { Text(stringResource(R.string.workspace_detail_mount_add)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workspace_detail_mount_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMountNameDialog = false
                        if (name.isNotBlank()) {
                            pendingMountName = name
                            mountDirPicker.launch(null)
                        }
                    }
                ) {
                    Text(stringResource(R.string.workspace_detail_mount_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMountNameDialog = false }) {
                    Text(stringResource(R.string.workspace_detail_mount_cancel))
                }
            },
        )
    }

    BackHandler(enabled = pagerState.currentPage == 1 && state.path.isNotBlank()) {
        vm.goUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (pagerState.currentPage == 1) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    if (state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                    icon = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    installProgress = installProgress,
                    onInstallRootfs = { showInstallDialog = true },
                    onToolApprovalChange = vm::setToolApproval,
                    onToolPromptChange = vm::setToolPrompt,
                    onResetToolPrompt = vm::clearToolPrompt,
                    onChooseExportTarget = { exportDirPicker.launch(null) },
                    onClearExportTarget = vm::clearExportTargetUri,
                    mounts = mounts,
                    mountMessage = mountMessage,
                    onAddMount = { showMountNameDialog = true },
                    onRemoveMount = vm::removeMount,
                    onSyncMount = vm::syncMount,
                )

                1 -> WorkspaceFilesPage(
                    state = state,
                    contentPadding = PaddingValues(),
                    onSelectArea = vm::selectArea,
                    onGoUp = vm::goUp,
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> vm.open(entry)

                            else -> when (entry.detectFileType()) {
                                WorkspaceFileType.TEXT -> navController.navigate(
                                    Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                )

                                WorkspaceFileType.IMAGE -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    // 传绝对路径 (而非 content:// URI): Coil 可直接加载,
                                    // 预览弹窗的保存按钮 saveMessageImage 只认 "/" 开头路径, content URI 会报错
                                    previewImageUri = file.absolutePath
                                }

                                WorkspaceFileType.OTHER -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                        file.extension.lowercase()
                                    ) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(intent, null))
                                    }
                                }
                            }
                        }
                    },
                    onDelete = { deleteTarget = it },
                    onExport = { entry ->
                        exportTarget = entry
                        exportLauncher.launch(entry.name)
                    },
                    onShare = { entry ->
                        vm.exportToCacheFile(entry, context.cacheDir) { file ->
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    },
                )
            }
        }
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }
}

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onToolPromptChange: (String, String) -> Unit,
    onResetToolPrompt: (String) -> Unit,
    onChooseExportTarget: () -> Unit,
    onClearExportTarget: () -> Unit,
    mounts: List<WorkspaceMountConfig>,
    mountMessage: String?,
    onAddMount: () -> Unit,
    onRemoveMount: (String) -> Unit,
    onSyncMount: (String, SyncDirection) -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_workspace_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_name), workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_shell_status), workspace?.shellStatus?.toShellStatusLabel() ?: "-")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Bash, contentDescription = null)
                        Text(
                            text = installButtonText,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    installProgress?.let { progress ->
                        RootfsProgress(progress)
                    }
                }
            }
        }

        item {
            WorkspaceExportTargetCard(
                workspace = workspace,
                onChooseTarget = onChooseExportTarget,
                onClearTarget = onClearExportTarget,
            )
        }

        item {
            WorkspaceMountCard(
                mounts = mounts,
                message = mountMessage,
                onAdd = onAddMount,
                onRemove = onRemoveMount,
                onSync = onSyncMount,
            )
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
                onToolPromptChange = onToolPromptChange,
                onResetToolPrompt = onResetToolPrompt,
            )
        }
    }
}

@Composable
private fun WorkspaceExportTargetCard(
    workspace: WorkspaceEntity?,
    onChooseTarget: () -> Unit,
    onClearTarget: () -> Unit,
) {
    val context = LocalContext.current
    val exportUriString = workspace?.exportTargetUri
    val configured = !exportUriString.isNullOrBlank()
    val displayName by produceState(initialValue = null as String?, key1 = exportUriString) {
        value = exportUriString?.takeIf { it.isNotBlank() }?.let { raw ->
            withContext(Dispatchers.IO) {
                runCatching { resolveTreeDisplayName(context, android.net.Uri.parse(raw)) }.getOrNull()
            }
        }
    }
    val statusText: String = when {
        !configured -> stringResource(R.string.workspace_detail_export_not_set)
        else -> displayName?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.workspace_detail_export_permission_lost)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_export_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_export_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onChooseTarget, enabled = workspace != null) {
                    Icon(HugeIcons.Folder01, contentDescription = null)
                    Text(
                        text = stringResource(R.string.workspace_detail_export_choose),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (configured) {
                    TextButton(onClick = onClearTarget, enabled = workspace != null) {
                        Text(stringResource(R.string.workspace_detail_export_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceMountCard(
    mounts: List<WorkspaceMountConfig>,
    message: String?,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onSync: (String, SyncDirection) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_mount_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_mount_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (mounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.workspace_detail_mount_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            mounts.forEach { mount ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "/mnt/${mount.name}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = mount.lastSyncAt?.let { formatMountSyncTime(it) } ?: stringResource(
                                R.string.workspace_detail_export_not_set
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onSync(mount.id, SyncDirection.PULL) }) {
                        Text("Pull")
                    }
                    TextButton(onClick = { onSync(mount.id, SyncDirection.PUSH) }) {
                        Text("Push")
                    }
                    TextButton(onClick = { onRemove(mount.id) }) {
                        Text(stringResource(R.string.workspace_detail_mount_remove))
                    }
                }
            }

            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(HugeIcons.Folder01, contentDescription = null)
                Text(
                    text = stringResource(R.string.workspace_detail_mount_add),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun formatMountSyncTime(timestamp: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))

/** 解析 SAF 树目录的显示名（DocumentsContract），失败返回 null（可能权限失效） */
private fun resolveTreeDisplayName(context: Context, treeUri: android.net.Uri): String? {
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    return context.contentResolver.query(docUri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        } else {
            null
        }
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onToolPromptChange: (String, String) -> Unit,
    onResetToolPrompt: (String) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()
    val promptOverrides = workspace?.toolPromptOverrides().orEmpty()
    var editingTool by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_tool_prompt_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = workspace != null) { editingTool = toolName },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }

    val editing = editingTool
    if (editing != null) {
        val label = workspaceToolApprovalItems()
            .firstOrNull { it.first == editing }?.second ?: editing
        ToolPromptEditDialog(
            toolName = editing,
            label = label,
            currentPrompt = promptOverrides[editing]
                ?: DEFAULT_WORKSPACE_TOOL_PROMPTS[editing].orEmpty(),
            isDefault = editing !in promptOverrides,
            onSave = { prompt ->
                onToolPromptChange(editing, prompt)
                editingTool = null
            },
            onReset = {
                onResetToolPrompt(editing)
                editingTool = null
            },
            onDismiss = { editingTool = null },
        )
    }
}

@Composable
private fun ToolPromptEditDialog(
    toolName: String,
    label: String,
    currentPrompt: String,
    isDefault: Boolean,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentPrompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    label = { Text(stringResource(R.string.workspace_detail_tool_prompt_label)) },
                )
                Text(
                    text = if (isDefault) {
                        stringResource(R.string.workspace_detail_tool_prompt_using_default)
                    } else {
                        stringResource(R.string.workspace_detail_tool_prompt_customized)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isDefault) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.workspace_detail_tool_prompt_reset))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}

@Composable
private fun workspaceToolApprovalItems() = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
    "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
    "workspace_export_to_phone" to stringResource(R.string.workspace_detail_tool_export),
    "workspace_mount_list" to stringResource(R.string.workspace_detail_tool_mount_list),
    "workspace_mount_sync" to stringResource(R.string.workspace_detail_tool_mount_sync),
    "workspace_bg_start" to stringResource(R.string.workspace_detail_tool_bg_start),
    "workspace_bg_status" to stringResource(R.string.workspace_detail_tool_bg_status),
    "workspace_bg_output" to stringResource(R.string.workspace_detail_tool_bg_output),
    "workspace_bg_kill" to stringResource(R.string.workspace_detail_tool_bg_kill),
    "workspace_bg_list" to stringResource(R.string.workspace_detail_tool_bg_list),
    "workspace_create_backup" to stringResource(R.string.workspace_detail_tool_create_backup),
)

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(DEFAULT_ROOTFS_URL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    contentPadding: PaddingValues,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            WorkspaceAreaSelector(
                selected = state.area,
                onSelected = onSelectArea,
            )
        }

        item {
            WorkspacePathBar(
                path = state.path,
                canGoUp = state.path.isNotBlank(),
                onGoUp = onGoUp,
            )
        }

        state.error?.let { error ->
            item {
                ErrorCard(error)
            }
        }

        if (!state.loading && state.entries.isEmpty() && state.error == null) {
            item {
                EmptyDirectoryState()
            }
        }

        items(state.entries, key = { "${state.area.name}:${it.path}" }) { entry ->
            WorkspaceFileCard(
                entry = entry,
                onOpen = { onOpen(entry) },
                onDelete = { onDelete(entry) },
                onExport = { onExport(entry) },
                onShare = { onShare(entry) },
            )
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val areas = listOf(
        WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
        WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            enabled = canGoUp,
            onClick = onGoUp,
        ) {
            Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
        }
        Text(
            text = path.ifBlank { "/" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) entry.path else "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_export)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.FileImport,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Share08,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_detail_empty_directory),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
    else -> lowercase()
}

private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
