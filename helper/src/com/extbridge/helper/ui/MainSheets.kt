package com.extbridge.helper.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extbridge.helper.AdbBootstrap
import com.extbridge.helper.BridgeClient
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class WidgetCandidateItem(
    val info: AppWidgetProviderInfo,
    val title: String,
    val subtitle: String,
    val iconBitmap: ImageBitmap?,
)

@Composable
fun DiagnosticsSheet(
    show: Boolean,
    status: BridgeClient.Status,
    onRebuild: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = "连接与渲染管线诊断",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                insideMargin = PaddingValues(16.dp),
            ) {
                DiagRow("核心守护进程", if (status.connected) "在线 (Binder 已连接)" else "未激活")
                DiagDivider()
                DiagRow("当前输出模式", if (status.connected) BridgeClient.mirrorModeLabel(status.mirrorMode) else "--")
                DiagDivider()
                DiagRow(
                    "物理外接显示器",
                    if (status.connected && status.extId > 0) {
                        "Display #${status.extId} · ${status.physW}×${status.physH} @ ${String.format("%.1f", status.physFps)}Hz"
                    } else {
                        "未检测到外接显示器"
                    },
                )
                DiagDivider()
                DiagRow(
                    "虚拟扩展画布",
                    if (status.connected && status.vdId > 0) {
                        "Display #${status.vdId} · ${status.virtW}×${status.virtH} (${BridgeClient.rotationLabel(status.virtRot)}) · ${status.virtDpi}dpi"
                    } else {
                        "预设 ${status.virtW}×${status.virtH} @ ${status.virtDpi}dpi"
                    },
                )
                DiagDivider()
                DiagRow(
                    "合成管线与帧率",
                    if (status.connected) "${status.renderMode} · 目标 ${status.virtFps.toInt()}Hz" else "--",
                )
                DiagDivider()
                DiagRow(
                    "键鼠输入路由",
                    if (status.connected) {
                        "${if (status.inputToExt == 1) "外接扩展屏" else "本机内屏"} (外接设备 ${status.extInputCnt} 个)"
                    } else {
                        "--"
                    },
                )
                DiagDivider()
                val injectBlocked = status.connected && status.injectStatus == -1
                DiagRow(
                    "输入注入权限",
                    if (!status.connected) "--" else if (injectBlocked) "被系统拦截 (-1)" else "正常可用",
                    valueColor = if (injectBlocked) HyperRed else if (status.connected) HyperGreen else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            if (status.connected && status.injectStatus == -1) {
                Card(
                    colors = CardDefaults.defaultColors(color = HyperRed.copy(alpha = 0.12f)),
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Text(
                        text = BridgeClient.getOemInjectHint(),
                        fontSize = 12.5.sp,
                        color = HyperRed,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (status.connected) {
                    TextButton(
                        text = "重建渲染管线",
                        onClick = {
                            onRebuild()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(
                    text = "完成",
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DiagRow(
    label: String,
    value: String,
    valueColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiagDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
fun ActivationSheet(
    show: Boolean,
    initialTab: Int,
    hasSuHint: Boolean,
    autoRootEnabled: Boolean,
    onAutoRootChange: (Boolean) -> Unit,
    shizukuAlive: Boolean,
    shizukuGranted: Boolean,
    shizukuInstalled: Boolean,
    shizukuRationale: Boolean,
    shizukuSummary: String,
    autoShizukuEnabled: Boolean,
    onAutoShizukuChange: (Boolean) -> Unit,
    mdnsHint: String,
    pairHost: String,
    onPairHostChange: (String) -> Unit,
    pairPort: String,
    onPairPortChange: (String) -> Unit,
    pairCode: String,
    onPairCodeChange: (String) -> Unit,
    connPort: String,
    onConnPortChange: (String) -> Unit,
    actStatusText: String,
    actStatusColor: Color,
    working: Boolean,
    pcAdbCommand: String,
    onSelectWirelessTab: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onRunOrAuthShizuku: () -> Unit,
    onOpenShizukuApp: () -> Unit,
    onRunRoot: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onSendPairNotif: () -> Unit,
    onRunWirelessPair: () -> Unit,
    onRunWirelessConnect: () -> Unit,
    onCopyAdbCmd: () -> Unit,
    onSkip: () -> Unit,
) {
    var tabIdx by remember(show, initialTab) { mutableIntStateOf(initialTab) }

    OverlayBottomSheet(
        show = show,
        title = "激活 ExtBridge 核心服务",
        onDismissRequest = onSkip,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "首次安装或重启设备后需拉起一次后台守护进程（连接成功后窗口将自动关闭）。",
                fontSize = 12.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            TabRowWithContour(
                tabs = listOf("无线调试", "Shizuku", "Root", "电脑 ADB"),
                selectedTabIndex = tabIdx,
                onTabSelected = { next ->
                    tabIdx = next
                    if (next == 0) onSelectWirelessTab()
                    if (next == 1) onRefreshShizuku()
                },
                modifier = Modifier.fillMaxWidth(),
            )

            when (tabIdx) {
                0 -> {
                    Card(
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                        ),
                        insideMargin = PaddingValues(12.dp),
                    ) {
                        Text(
                            text = mdnsHint.ifBlank { "正在通过 mDNS 自动扫描本机无线调试配对与连接端口…" },
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = "1. 开启无线调试",
                            onClick = onOpenDevOptions,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "刷新配对通知",
                            onClick = onSendPairNotif,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    ExtTextField(
                        value = pairHost,
                        onValueChange = onPairHostChange,
                        label = "本机 IP 地址 (如 192.168.31.20)",
                        singleLine = true,
                        resetKey = show,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExtTextField(
                            value = pairPort,
                            onValueChange = onPairPortChange,
                            label = "配对端口",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            resetKey = show,
                            modifier = Modifier.weight(1f),
                        )
                        ExtTextField(
                            value = pairCode,
                            onValueChange = onPairCodeChange,
                            label = "6 位配对码",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            resetKey = show,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ExtTextField(
                        value = connPort,
                        onValueChange = onConnPortChange,
                        label = "连接端口 (已配对过仅填此项即可)",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        resetKey = show,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = "配对并激活",
                            enabled = !working,
                            onClick = onRunWirelessPair,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "已配对·直连",
                            enabled = !working,
                            onClick = onRunWirelessConnect,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                1 -> {
                    val bannerColor = when {
                        shizukuAlive && shizukuGranted -> HyperGreen.copy(alpha = 0.12f)
                        shizukuAlive -> MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else -> MiuixTheme.colorScheme.secondaryContainer
                    }
                    val bannerTextColor = when {
                        shizukuAlive && shizukuGranted -> HyperGreen
                        shizukuAlive -> MiuixTheme.colorScheme.primary
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                    Card(
                        colors = CardDefaults.defaultColors(color = bannerColor),
                        insideMargin = PaddingValues(13.dp),
                    ) {
                        Text(
                            text = shizukuSummary,
                            fontSize = 12.5.sp,
                            color = bannerTextColor,
                        )
                    }

                    Card(insideMargin = PaddingValues(14.dp)) {
                        val primaryText = when {
                            working -> "正在通过 Shizuku 激活…"
                            shizukuAlive && shizukuGranted -> "一键 Shizuku 激活服务"
                            shizukuAlive && shizukuRationale -> "前往 Shizuku 手动开启授权"
                            shizukuAlive -> "授权 Shizuku 并一键激活"
                            shizukuInstalled -> "打开 Shizuku 启动服务"
                            else -> "了解 / 下载 Shizuku"
                        }
                        TextButton(
                            text = primaryText,
                            enabled = !working,
                            onClick = {
                                if (shizukuAlive && !shizukuRationale) {
                                    onRunOrAuthShizuku()
                                } else {
                                    onOpenShizukuApp()
                                }
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        if (shizukuAlive) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    text = "打开 Shizuku",
                                    onClick = onOpenShizukuApp,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    text = "刷新状态",
                                    onClick = onRefreshShizuku,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            TextButton(
                                text = "刷新 Shizuku 状态",
                                onClick = onRefreshShizuku,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Card {
                        SwitchPreference(
                            title = "启动软件时自动 Shizuku 激活",
                            summary = "检测到已授权的 Shizuku 服务时静默拉起守护进程",
                            checked = autoShizukuEnabled,
                            onCheckedChange = onAutoShizukuChange,
                        )
                    }
                }

                2 -> {
                    Card(insideMargin = PaddingValues(14.dp)) {
                        Text(
                            text = if (hasSuHint) {
                                "已检测到 su 环境（Magisk / KernelSU / APatch）。一键授权后自动降权至 shell 身份运行，无需开启无线调试。"
                            } else {
                                "适合已 Root 设备（Magisk / KernelSU / APatch）。若隐藏了 su 仍可直接点击下方按钮请求授权。"
                            },
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            text = if (working) "正在请求 Root 授权…" else "一键 Root 注册并激活服务",
                            enabled = !working,
                            onClick = onRunRoot,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Card {
                        SwitchPreference(
                            title = "启动软件时自动 Root 激活",
                            summary = "进入应用发现守护进程未运行时静默拉起",
                            checked = autoRootEnabled,
                            onCheckedChange = onAutoRootChange,
                        )
                    }
                }

                else -> {
                    Text(
                        text = "电脑通过 USB 或无线 ADB 连接本机后，执行下方单行命令即可自动释放并启动守护进程：",
                        fontSize = 12.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Card(
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.secondaryContainer,
                        ),
                        insideMargin = PaddingValues(14.dp),
                    ) {
                        Text(
                            text = pcAdbCommand,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                    }
                    TextButton(
                        text = "复制 ADB 激活命令",
                        onClick = onCopyAdbCmd,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (actStatusText.isNotBlank()) {
                Card(insideMargin = PaddingValues(12.dp)) {
                    Text(
                        text = actStatusText,
                        fontSize = 12.sp,
                        color = actStatusColor,
                    )
                }
            }

            TextButton(
                text = "暂不激活，先浏览控制台",
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun PermissionChecklistDialog(
    show: Boolean,
    isDevGate: Boolean,
    notifOk: Boolean,
    overlayOk: Boolean,
    a11yOk: Boolean,
    onGrantNotif: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantA11y: () -> Unit,
    onOpenRestricted: () -> Unit,
    onSkipOrContinue: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = if (isDevGate) "前往开发者选项前 · 请补齐权限" else "运行权限健康检查",
        summary = if (isDevGate) {
            "为确保无线调试顶部配对通知、外接鼠标准星与全局手势正常生效，建议先开启以下权限："
        } else {
            "外接桌面 Dock、鼠标浮层、全局返回手势及无线配对通知依赖以下三项核心权限："
        },
        onDismissRequest = onSkipOrContinue,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PermItemRow("通知栏常驻与配对框权限", notifOk, onGrantNotif)
            PermItemRow("悬浮窗与外接光标权限", overlayOk, onGrantOverlay)
            PermItemRow("无障碍手势与浮层权限 (必需)", a11yOk, onGrantA11y)

            if (!a11yOk) {
                TextButton(
                    text = "无障碍开关呈灰色？进入应用详情允许受限设置",
                    onClick = onOpenRestricted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(4.dp))
            TextButton(
                text = if (notifOk && overlayOk && a11yOk) {
                    "全部已就绪 · 完成"
                } else if (isDevGate) {
                    "暂不补齐，仍前往开发者选项"
                } else {
                    "稍后在设置页处理"
                },
                colors = if (notifOk && overlayOk && a11yOk) {
                    ButtonDefaults.textButtonColorsPrimary()
                } else {
                    ButtonDefaults.textButtonColors()
                },
                onClick = onSkipOrContinue,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PermItemRow(
    title: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            Text(
                text = if (granted) "已授权" else "未开启",
                fontSize = 11.5.sp,
                color = if (granted) HyperGreen else HyperAmber,
            )
        }
        if (!granted) {
            TextButton(
                text = "去开启",
                onClick = onGrant,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
            )
        } else {
            StatusPillBadge(label = "就绪", dotColor = HyperGreen)
        }
    }
}

@Composable
fun DevOptionsGuideDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "首次前往「开发者选项」指引",
        summary = "已发送无线调试配对通知至状态栏。进入开发者选项后请一并开启：",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
                insideMargin = PaddingValues(14.dp),
            ) {
                Text(
                    text = "1. 无线调试：点击「使用配对码配对设备」，下拉顶部通知即可直接填入 6 位配对码。\n\n" +
                        "2. USB 调试（安全设置）：Xiaomi HyperOS 必开项，否则外接屏鼠标与触控板点击会被系统拦截。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
            }
            TextButton(
                text = "我已知晓，立即打开开发者选项",
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun CustomResolutionDialog(
    show: Boolean,
    initialW: Int,
    initialH: Int,
    onApply: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var wText by remember(show, initialW) { mutableStateOf(initialW.toString()) }
    var hText by remember(show, initialH) { mutableStateOf(initialH.toString()) }

    OverlayDialog(
        show = show,
        title = "自定义渲染分辨率",
        summary = "范围 640×480 ~ 7680×4320，可随时对调宽高适配横竖屏",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExtTextField(
                    value = wText,
                    onValueChange = { wText = it },
                    label = "宽度 (W)",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    resetKey = show,
                    modifier = Modifier.weight(1f),
                )
                Text("×", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                ExtTextField(
                    value = hText,
                    onValueChange = { hText = it },
                    label = "高度 (H)",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    resetKey = show,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                text = "对调宽高 (W ⇄ H)",
                onClick = {
                    val tmp = wText
                    wText = hText
                    hText = tmp
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "应用",
                    onClick = {
                        val cw = wText.trim().toIntOrNull() ?: initialW
                        val ch = hText.trim().toIntOrNull() ?: initialH
                        if (maxOf(cw, ch) in 640..7680 && minOf(cw, ch) in 480..4320) {
                            onApply(cw, ch)
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun EdidAndRefreshSheet(
    show: Boolean,
    selectedFps: Float,
    modesRaw: String,
    selectedPhysModeId: Int,
    onSelectFps: (Float) -> Unit,
    onSelectPhysMode: (Int, Int, Int, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val fpsPresets = listOf(60f, 75f, 90f, 120f, 144f, 165f)
    val parsedModes = remember(modesRaw) {
        if (modesRaw.isBlank()) emptyList()
        else modesRaw.split(";").mapNotNull { item ->
            val kv = item.split(":")
            if (kv.size != 2) return@mapNotNull null
            val id = kv[0].toIntOrNull() ?: return@mapNotNull null
            val rf = kv[1].split("@")
            if (rf.size != 2) return@mapNotNull null
            val wh = rf[0].split("x")
            if (wh.size != 2) return@mapNotNull null
            val pw = wh[0].toIntOrNull() ?: return@mapNotNull null
            val ph = wh[1].toIntOrNull() ?: return@mapNotNull null
            val pf = rf[1].toFloatOrNull() ?: 60f
            QuadMode(id, pw, ph, pf)
        }
    }

    OverlayBottomSheet(
        show = show,
        title = "外接物理信号与目标刷新率",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "注：部分 HyperOS 机型固件会锁定外接物理信号输出上限；向下限帧省电或支持 DP 模式切换的设备可在此指定档位。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            SmallTitle("目标渲染刷新率", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fpsPresets.take(3).forEach { f ->
                    val sel = kotlin.math.abs(f - selectedFps) < 1f && selectedPhysModeId == -1
                    TextButton(
                        text = "${f.toInt()} Hz",
                        onClick = { onSelectFps(f) },
                        colors = if (sel) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fpsPresets.drop(3).forEach { f ->
                    val sel = kotlin.math.abs(f - selectedFps) < 1f && selectedPhysModeId == -1
                    TextButton(
                        text = "${f.toInt()} Hz",
                        onClick = { onSelectFps(f) },
                        colors = if (sel) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SmallTitle("显示器 EDID 硬件报告档位", insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp))
            if (parsedModes.isEmpty()) {
                Card(insideMargin = PaddingValues(14.dp)) {
                    Text(
                        text = "暂无外接显示器 EDID 模式数据（接入 Type-C 显示器后自动读取）",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                Card(insideMargin = PaddingValues(vertical = 6.dp)) {
                    parsedModes.forEachIndexed { idx, m ->
                        if (idx > 0) DiagDivider()
                        val sel = (m.id == selectedPhysModeId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectPhysMode(m.id, m.w, m.h, m.fps)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "${m.w} × ${m.h} @ ${String.format("%.1f", m.fps)} Hz",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainer,
                                )
                                Text(
                                    text = "硬件模式 ID #${m.id}",
                                    fontSize = 11.5.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (sel) {
                                StatusPillBadge("已选", MiuixTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class QuadMode(val id: Int, val w: Int, val h: Int, val fps: Float)

@Composable
fun MirrorPresetManagerSheet(
    show: Boolean,
    slots: List<String>,
    onUpdateSlots: (List<String>) -> Unit,
    onEditMemo: () -> Unit,
    onResetDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingSlotIdx by remember(show) { mutableStateOf<Int?>(null) } // null=list, -1=add, >=0=replace

    OverlayBottomSheet(
        show = show,
        title = if (pickingSlotIdx == null) "边栏信息卡片管理" else if (pickingSlotIdx == -1) "添加边栏信息卡" else "替换信息卡",
        onDismissRequest = {
            if (pickingSlotIdx != null) pickingSlotIdx = null else onDismiss()
        },
    ) {
        if (pickingSlotIdx != null) {
            val targetIdx = pickingSlotIdx ?: -1
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(BridgeClient.MIRROR_PRESET_KEYS.indices.toList()) { i ->
                        val key = BridgeClient.MIRROR_PRESET_KEYS[i]
                        val title = BridgeClient.MIRROR_PRESET_TITLES[i]
                        val desc = BridgeClient.MIRROR_PRESET_DESCS[i]
                        Card(
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                            onClick = {
                                val next = slots.toMutableList()
                                if (targetIdx >= 0 && targetIdx < next.size) {
                                    next[targetIdx] = key
                                } else {
                                    next.add(key)
                                }
                                onUpdateSlots(next)
                                pickingSlotIdx = null
                            },
                        ) {
                            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(desc, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                TextButton(
                    text = "返回已启用列表",
                    onClick = { pickingSlotIdx = null },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "已启用 ${slots.size} 张信息卡（点击名称可替换，右侧按钮调序或移除）：",
                    fontSize = 12.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(slots.indices.toList()) { idx ->
                        val key = slots[idx]
                        Card(
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { pickingSlotIdx = idx },
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${BridgeClient.mirrorPresetTitle(key)}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    val pIdx = BridgeClient.mirrorPresetIndex(key)
                                    if (pIdx >= 0) {
                                        Text(
                                            text = BridgeClient.MIRROR_PRESET_DESCS[pIdx],
                                            fontSize = 11.5.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (key == "memo") {
                                    TextButton(
                                        text = "便签",
                                        onClick = onEditMemo,
                                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                IconButton(
                                    enabled = idx > 0,
                                    onClick = {
                                        val next = slots.toMutableList()
                                        val item = next.removeAt(idx)
                                        next.add(idx - 1, item)
                                        onUpdateSlots(next)
                                    },
                                ) {
                                    Icon(ExtVectorIcons.ArrowUp, "上移", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    enabled = idx < slots.lastIndex,
                                    onClick = {
                                        val next = slots.toMutableList()
                                        val item = next.removeAt(idx)
                                        next.add(idx + 1, item)
                                        onUpdateSlots(next)
                                    },
                                ) {
                                    Icon(ExtVectorIcons.ArrowDown, "下移", modifier = Modifier.size(18.dp))
                                }
                                if (slots.size > 1) {
                                    IconButton(
                                        onClick = {
                                            val next = slots.toMutableList()
                                            next.removeAt(idx)
                                            onUpdateSlots(next)
                                        },
                                    ) {
                                        Icon(
                                            ExtVectorIcons.TrashDelete,
                                            "移除",
                                            tint = HyperRed,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = "添加信息卡",
                        onClick = { pickingSlotIdx = -1 },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "恢复默认",
                        onClick = onResetDefault,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
fun MirrorMemoDialog(
    show: Boolean,
    initialText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var memo by remember(show, initialText) { mutableStateOf(initialText) }
    OverlayDialog(
        show = show,
        title = "编辑边栏快捷便签",
        summary = "保存后即时同步至外接显示器融合边栏",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExtTextField(
                value = memo,
                onValueChange = { memo = it },
                label = "输入待办、会议提醒或备忘文字…",
                minLines = 3,
                maxLines = 6,
                resetKey = show,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "保存并同步",
                    onClick = {
                        onSave(memo.trim())
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun CustomMirrorColorDialog(
    show: Boolean,
    initialColorInt: Int,
    onApplyColor: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hex by remember(show, initialColorInt) {
        mutableStateOf(String.format("#%06X", 0xFFFFFF and initialColorInt))
    }
    val swatches = listOf(
        "午夜藏青" to 0xFF0B132B.toInt(),
        "深海孔雀绿" to 0xFF072227.toInt(),
        "暗夜酒红" to 0xFF2B0B18.toInt(),
        "钛空深灰" to 0xFF1C1E26.toInt(),
        "抹茶雅白" to 0xFFE8F3EA.toInt(),
        "暖杏米白" to 0xFFFBF6EE.toInt(),
    )

    OverlayDialog(
        show = show,
        title = "自定义副屏背景色",
        summary = "输入 #RRGGBB 十六进制色值或点击下方预设色板（自动适配深浅文字）",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ExtTextField(
                value = hex,
                onValueChange = { hex = it },
                label = "#RRGGBB",
                singleLine = true,
                resetKey = show,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                swatches.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { (name, cInt) ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.secondaryContainer)
                                    .clickable { hex = String.format("#%06X", 0xFFFFFF and cInt) }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(cInt))
                                        .border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(name, fontSize = 12.5.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "应用颜色",
                    onClick = {
                        val normalized = if (hex.trim().startsWith("#")) hex.trim() else "#${hex.trim()}"
                        runCatching { android.graphics.Color.parseColor(normalized) }
                            .onSuccess { parsed ->
                                onApplyColor(parsed)
                                onDismiss()
                            }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun MirrorWidgetSlotSheet(
    show: Boolean,
    slotIndex: Int,
    boundWidgetLabel: String?,
    currentRatioIdx: Int,
    candidates: List<WidgetCandidateItem>,
    loading: Boolean,
    onChangeRatio: (Int) -> Unit,
    onClearWidget: () -> Unit,
    onPickCandidate: (AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(show) { mutableStateOf("") }
    val filtered = remember(candidates, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) candidates
        else candidates.filter {
            it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
        }
    }

    OverlayBottomSheet(
        show = show,
        title = "边栏系统小组件槽位 ${slotIndex + 1}",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (boundWidgetLabel != null) {
                Card {
                    OverlayDropdownPreference(
                        title = "当前已绑定：$boundWidgetLabel",
                        summary = "调整小组件长宽比例防止拉伸变形",
                        items = BridgeClient.MIRROR_WIDGET_RATIO_LABELS.toList(),
                        selectedIndex = currentRatioIdx.coerceIn(0, BridgeClient.MIRROR_WIDGET_RATIO_LABELS.lastIndex),
                        onSelectedIndexChange = onChangeRatio,
                    )
                }
                TextButton(
                    text = "清除此槽位小组件",
                    onClick = {
                        onClearWidget()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ExtTextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索系统小组件名称或所属应用…",
                singleLine = true,
                resetKey = show,
                modifier = Modifier.fillMaxWidth(),
            )

            if (loading) {
                Text(
                    text = "正在加载已安装的系统小组件…",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered) { item ->
                        Card(
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            onClick = {
                                onDismiss()
                                onPickCandidate(item.info)
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (item.iconBitmap != null) {
                                    Image(
                                        bitmap = item.iconBitmap,
                                        contentDescription = item.title,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(9.dp)),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        item.subtitle,
                                        fontSize = 11.5.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
