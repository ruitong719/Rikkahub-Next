package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.PermissionMode

/**
 * 权限模式按钮（plan/build/yolo）：显示当前模式，点击弹出底部选单。
 * BUILD 用低调中性色，PLAN/YOLO 分别用 secondary/error 色提示「非默认状态」。
 */
@Composable
fun PermissionModeButton(
    mode: PermissionMode,
    onUpdate: (PermissionMode) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        PermissionModePicker(
            current = mode,
            onDismissRequest = { showPicker = false },
            onSelect = { option ->
                onUpdate(option)
                showPicker = false
            },
        )
    }

    val containerColor = when (mode) {
        PermissionMode.PLAN -> MaterialTheme.colorScheme.secondaryContainer
        PermissionMode.BUILD -> Color.Transparent
        PermissionMode.YOLO -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (mode) {
        PermissionMode.PLAN -> MaterialTheme.colorScheme.onSecondaryContainer
        PermissionMode.BUILD -> MaterialTheme.colorScheme.onSurfaceVariant
        PermissionMode.YOLO -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        onClick = { showPicker = true },
        shape = MaterialTheme.shapes.small,
        color = containerColor,
    ) {
        // 仅保留图标、去掉 PLAN/BUILD/YOLO 文字以节省底栏空间；用底色区分非默认模式
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = mode.icon(),
                contentDescription = stringResource(mode.labelRes()),
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 权限模式选单：与其他 picker 一致的 ModalBottomSheet 卡片设计语言
 * （选中 primaryContainer + 2dp primary 描边，未选中 surfaceContainerHigh）。
 */
@Composable
private fun PermissionModePicker(
    current: PermissionMode,
    onDismissRequest: () -> Unit,
    onSelect: (PermissionMode) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.permission_mode_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.permission_mode_picker_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listOf(PermissionMode.PLAN, PermissionMode.BUILD, PermissionMode.YOLO).forEach { option ->
                PermissionModeCard(
                    mode = option,
                    selected = option == current,
                    onClick = { onSelect(option) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PermissionModeCard(
    mode: PermissionMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    )
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = mode.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(mode.labelRes()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(mode.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    imageVector = HugeIcons.CheckmarkCircle02,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun PermissionMode.icon(): ImageVector = when (this) {
    PermissionMode.PLAN -> HugeIcons.Eye
    PermissionMode.BUILD -> HugeIcons.Wrench01
    PermissionMode.YOLO -> HugeIcons.Zap
}

private fun PermissionMode.labelRes(): Int = when (this) {
    PermissionMode.PLAN -> R.string.permission_mode_plan
    PermissionMode.BUILD -> R.string.permission_mode_build
    PermissionMode.YOLO -> R.string.permission_mode_yolo
}

private fun PermissionMode.descriptionRes(): Int = when (this) {
    PermissionMode.PLAN -> R.string.permission_mode_plan_desc
    PermissionMode.BUILD -> R.string.permission_mode_build_desc
    PermissionMode.YOLO -> R.string.permission_mode_yolo_desc
}
