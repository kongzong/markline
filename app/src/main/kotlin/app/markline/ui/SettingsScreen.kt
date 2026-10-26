package app.markline.ui

import android.widget.Toast
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.markline.domain.Event
import app.markline.domain.EventStore
import app.markline.system.SyncManager
import app.markline.system.SyncWorker
import app.markline.ui.theme.*
import app.markline.util.BackupManager
import app.markline.util.SettingsStore
import app.markline.util.TimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    eventStore: EventStore,
    onBack: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var enableAudio   by remember { mutableStateOf(settingsStore.audioEnabled) }
    var audioDuration by remember { mutableStateOf(settingsStore.audioDurationSec) }
    var enableLocation by remember { mutableStateOf(settingsStore.locationEnabled) }
    var enableVibrate  by remember { mutableStateOf(settingsStore.vibrateEnabled) }
    var backupPhotoLite by remember { mutableStateOf(settingsStore.backupPhotoLite) }

    // 云同步状态
    var syncEnabled by remember { mutableStateOf(settingsStore.syncEnabled) }
    var webdavUrl     by remember { mutableStateOf(settingsStore.webdavUrl) }
    var webdavUser    by remember { mutableStateOf(settingsStore.webdavUser) }
    var webdavPass    by remember { mutableStateOf(settingsStore.webdavPass) }
    var lastSyncAt    by remember { mutableStateOf(settingsStore.lastSyncAt) }
    var isSyncing     by remember { mutableStateOf(false) }
    var showWebdavDialog by remember { mutableStateOf(false) }

    var isBackupProcessing by remember { mutableStateOf(false) }

    var showDurationDialog by remember { mutableStateOf(false) }
    var showQualityDialog  by remember { mutableStateOf(false) }
    var showHelpDialog     by remember { mutableStateOf(false) }
    var showAboutDialog    by remember { mutableStateOf(false) }

    // 调试工具：连续点击"关于我们"7次后显示（仿 Android 开发者选项）
    var debugClickCount by remember { mutableIntStateOf(0) }
    var showDebugTools  by remember { mutableStateOf(false) }

    // 文件选择器（导入用）
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isBackupProcessing = true
            scope.launch(Dispatchers.IO) {
                try {
                    val result = BackupManager.importFromUri(context, uri, eventStore)
                    val msg = if (result.error != null) {
                        result.error
                    } else {
                        "导入完成：成功 ${result.success} 条，跳过 ${result.skipped} 条"
                    }
                    withContext(Dispatchers.Main) {
                        if (result.failed > 0) {
                            Toast.makeText(context, "$msg，失败 ${result.failed} 条", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, msg!!, Toast.LENGTH_LONG).show()
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        isBackupProcessing = false
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {

            // ── 录音设置 ──────────────────────────────────
            SectionHeader("录音设置")

            SwitchItem(
                icon      = Icons.Outlined.Mic,
                title     = "启用录音",
                subtitle  = "签到时自动录音",
                checked   = enableAudio,
                onChecked = {
                    enableAudio = it
                    settingsStore.audioEnabled = it
                }
            )

            if (enableAudio) {
                ArrowItem(
                    icon     = Icons.Outlined.Timer,
                    title    = "录音时长",
                    trailing = "${audioDuration}秒",
                    onClick  = { showDurationDialog = true }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color    = MaterialTheme.colorScheme.outlineVariant
            )

            // ── 定位设置 ──────────────────────────────────
            SectionHeader("定位设置")

            SwitchItem(
                icon      = Icons.Outlined.LocationOn,
                title     = "自动定位",
                subtitle  = "签到时自动获取位置",
                checked   = enableLocation,
                onChecked = {
                    enableLocation = it
                    settingsStore.locationEnabled = it
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color    = MaterialTheme.colorScheme.outlineVariant
            )

            // ── 其他 ──────────────────────────────────────
            SectionHeader("其他")

            SwitchItem(
                icon      = Icons.Outlined.Vibration,
                title     = "震动反馈",
                subtitle  = "签到时震动提示",
                checked   = enableVibrate,
                onChecked = {
                    enableVibrate = it
                    settingsStore.vibrateEnabled = it
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color    = MaterialTheme.colorScheme.outlineVariant
            )

            // ── 云同步（WebDAV）────────────────────────────
            SectionHeader("云同步")

            SwitchItem(
                icon      = Icons.Outlined.CloudSync,
                title     = "自动同步",
                subtitle  = "接入坚果云 WebDAV，数据变化即同步 + 每 6 小时兜底",
                checked   = syncEnabled,
                onChecked = { enabled ->
                    syncEnabled = enabled
                    settingsStore.syncEnabled = enabled
                    if (enabled) {
                        if (webdavUser.isBlank() || webdavPass.isBlank()) {
                            showWebdavDialog = true
                        }
                        SyncWorker.schedulePeriodic(context)
                        SyncWorker.syncNow(context)
                    } else {
                        SyncWorker.cancel(context)
                    }
                }
            )

            ArrowItem(
                icon    = Icons.Outlined.Dns,
                title   = "服务器配置",
                trailing = if (webdavUrl.isBlank()) "坚果云（默认）" else webdavUrl
                    .removePrefix("https://").removePrefix("http://"),
                onClick = { showWebdavDialog = true }
            )

            ArrowItem(
                icon    = Icons.Outlined.Sync,
                title   = if (isSyncing) "正在同步…" else "立即同步",
                trailing = if (lastSyncAt > 0) TimeUtil.formatDate(lastSyncAt) + " " + TimeUtil.formatTime(lastSyncAt) else "从未同步",
                onClick = {
                    if (isSyncing) return@ArrowItem
                    if (webdavUser.isBlank() || webdavPass.isBlank()) {
                        Toast.makeText(context, "请先配置 WebDAV 账号与应用密码", Toast.LENGTH_SHORT).show()
                        showWebdavDialog = true
                        return@ArrowItem
                    }
                    isSyncing = true
                    scope.launch(Dispatchers.IO) {
                        val result = SyncManager.sync(context, settingsStore, eventStore)
                        withContext(Dispatchers.Main) {
                            isSyncing = false
                            lastSyncAt = settingsStore.lastSyncAt
                            val msg = if (result.ok) {
                                "同步完成：上传 ${result.uploadedEvents} 条/${result.uploadedFiles} 文件，" +
                                "下载 ${result.downloadedEvents} 条/${result.downloadedFiles} 文件"
                            } else {
                                "同步失败：${result.error}"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color    = MaterialTheme.colorScheme.outlineVariant
            )

            // ── 数据管理 ──────────────────────────────────
            SectionHeader("数据管理")

            ArrowItem(
                icon    = Icons.Outlined.Photo,
                title   = "备份画质",
                trailing = if (backupPhotoLite) "轻量压缩" else "完整原图",
                onClick = { showQualityDialog = true }
            )

            // 处理中提示
            if (isBackupProcessing) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        "正在处理中，请稍候...",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Green500
                    )
                }
            }

            ArrowItem(
                icon    = Icons.Outlined.SaveAlt,
                title   = "导出到文件",
                trailing = "保存到 Downloads",
                onClick = {
                    if (!isBackupProcessing) {
                        isBackupProcessing = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val fileName = BackupManager.exportToFile(context, eventStore, backupPhotoLite)
                                withContext(Dispatchers.Main) {
                                    if (fileName != null) {
                                        Toast.makeText(context, "已保存到 Downloads/$fileName", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "没有可导出的数据", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } finally {
                                withContext(Dispatchers.Main) { isBackupProcessing = false }
                            }
                        }
                    }
                }
            )

            ArrowItem(
                icon    = Icons.Outlined.IosShare,
                title   = "分享备份",
                trailing = "发送到微信/邮件等",
                onClick = {
                    if (!isBackupProcessing) {
                        isBackupProcessing = true
                        scope.launch(Dispatchers.IO) {
                    try {
                        val intent = BackupManager.createShareIntent(context, eventStore, backupPhotoLite)
                                withContext(Dispatchers.Main) {
                                    if (intent != null) {
                                        context.startActivity(Intent.createChooser(intent, "分享备份"))
                                    } else {
                                        Toast.makeText(context, "没有可导出的数据", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } finally {
                                withContext(Dispatchers.Main) { isBackupProcessing = false }
                            }
                        }
                    }
                }
            )

            ArrowItem(
                icon    = Icons.Outlined.FileOpen,
                title   = "导入数据",
                trailing = "从备份文件恢复",
                onClick = {
                    if (!isBackupProcessing) {
                        pickFile.launch(arrayOf("application/zip", "application/json"))
                    }
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color    = MaterialTheme.colorScheme.outlineVariant
            )

            // ── 帮助 ──────────────────────────────────────
            SectionHeader("帮助")

            ArrowItem(
                icon    = Icons.Outlined.HelpOutline,
                title   = "使用帮助",
                onClick = { showHelpDialog = true }
            )

            ArrowItem(
                icon    = Icons.Outlined.Info,
                title   = "关于我们",
                onClick = { showAboutDialog = true }
            )

            // ── 调试工具（隐藏区，连续点击"关于我们"7次后显示）──
            AnimatedVisibility(
                visible = showDebugTools,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color    = MaterialTheme.colorScheme.outlineVariant
                    )

                    SectionHeader("调试工具")

                    ArrowItem(
                        icon    = Icons.Outlined.Storage,
                        title   = "生成过去 10 天模拟数据",
                        trailing = "点击生成",
                        onClick = {
                            scope.launch {
                                generateMockData(eventStore)
                                Toast.makeText(context, "模拟数据已生成", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    // ── 对话框 ──────────────────────────────────────────

    if (showDurationDialog) {
        DurationSelectionDialog(
            currentDuration = audioDuration,
            onDismiss = { showDurationDialog = false },
            onSelected = {
                audioDuration = it
                settingsStore.audioDurationSec = it
                showDurationDialog = false
            }
        )
    }

    if (showQualityDialog) {
        QualitySelectionDialog(
            currentLite = backupPhotoLite,
            onDismiss = { showQualityDialog = false },
            onSelected = {
                backupPhotoLite = it
                settingsStore.backupPhotoLite = it
                showQualityDialog = false
            }
        )
    }

    if (showWebdavDialog) {
        WebDavConfigDialog(
            initialUrl = webdavUrl,
            initialUser = webdavUser,
            initialPass = webdavPass,
            onDismiss = { showWebdavDialog = false },
            onSave = { url, user, pass ->
                webdavUrl = url; webdavUser = user; webdavPass = pass
                settingsStore.webdavUrl = url
                settingsStore.webdavUser = user
                settingsStore.webdavPass = pass
                showWebdavDialog = false
                Toast.makeText(context, "WebDAV 配置已保存", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false },
            onVersionClick = {
                if (!showDebugTools) {
                    debugClickCount++
                    if (debugClickCount >= 7) {
                        showDebugTools = true
                        Toast.makeText(context, "调试工具已启用", Toast.LENGTH_SHORT).show()
                    } else if (debugClickCount >= 4) {
                        Toast.makeText(context, "再点击 ${7 - debugClickCount} 次启用调试工具", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

// ── 使用帮助对话框 ──────────────────────────────────────

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用帮助", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HelpSection("签到打卡",
                    "点击首页底部中央的圆形按钮即可签到。",
                    "签到时会自动记录当前位置和地址。",
                    "「拍照签到」一键调起相机，自动读取照片时间与定位。",
                    "如果开启了录音功能，签到后将自动开始录音。"
                )
                HelpSection("录音",
                    "在「录音设置」中可开启/关闭自动录音功能。",
                    "录音时长可在 3秒 / 10秒 / 15秒 / 30秒 中选择。",
                    "录音文件会保存在应用私有目录中，导出备份时一同打包。"
                )
                HelpSection("定位",
                    "在「定位设置」中可开启/关闭自动定位。",
                    "开启后签到将自动获取 GPS 位置并解析为地址。",
                    "定位精度为高精度模式，确保门牌号等细节准确。"
                )
                HelpSection("照片导入",
                    "时间线顶栏的「导入」图标可从相册批量导入照片。",
                    "按照片 EXIF 信息自动生成带时间、地点的记录。",
                    "导入的照片会复制到应用私有目录，删原图不影响查看。",
                    "按来源照片自动去重，重复导入不会生成重复记录。"
                )
                HelpSection("时间线",
                    "时间线按日期分组、单列排布，大屏小屏体验一致。",
                    "数据变化（增删改、云同步下载）时自动刷新，无需手动重进。",
                    "下拉可手动刷新；双击顶栏「Line」区域快速回到顶部。",
                    "点击任意记录可查看详情，包括照片、位置、录音、备注等。"
                )
                HelpSection("分享长图",
                    "筛选某个主题后，时间线顶栏会出现「分享」图标。",
                    "一键生成图文并茂的长图海报：统计头 + 日期分组时间轴 + 全部照片。",
                    "预览后可分享到微信等应用，或直接保存到相册（Pictures/MarkLine）。",
                    "单主题最多展示最近 50 条记录，照片自动压缩防止卡顿。"
                )
                HelpSection("数据备份",
                    "「导出到文件」：将数据打包为 ZIP 文件保存到 Downloads。",
                    "备份包含全部照片与录音文件，换机一键完整迁移。",
                    "「备份画质」可选完整原图或轻量压缩，控制备份包体积。",
                    "「导入数据」：选择备份文件恢复数据，支持 ZIP 和旧版 JSON 格式。",
                    "导入时按 UUID 去重，已存在的记录会自动跳过。"
                )
                HelpSection("云同步",
                    "在「数据管理」上方的「云同步」中配置 WebDAV 服务器。",
                    "支持坚果云等服务商，配置后自动双向同步。",
                    "本地有修改会自动触发同步；进入时间线时也会检查更新。",
                    "记录、照片、录音、删除操作均会同步，多设备数据保持一致。"
                )
                HelpSection("注意事项",
                    "首次使用需授予位置和录音权限。",
                    "建议定期导出备份，避免数据丢失。",
                    "更换手机时，云同步或分享备份均可快速迁移数据。"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

@Composable
private fun HelpSection(title: String, vararg items: String) {
    Column {
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        items.forEach { item ->
            Row(modifier = Modifier.padding(start = 4.dp)) {
                Text("· ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    item,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

// ── 关于我们对话框 ──────────────────────────────────────

@Composable
private fun AboutDialog(onDismiss: () -> Unit, onVersionClick: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = Green500,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("关于 MarkLine", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "点点mark，汇聚成line",
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "记录每一天的足迹，让生活有迹可循。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                AboutRow("版本", "1.0.0", onClick = onVersionClick)
                AboutRow("开发者", "Kong & WorkBuddy")
                AboutRow("技术栈", "Kotlin + Jetpack Compose")
                AboutRow("数据存储", "SQLite (Room 风格)")
                AboutRow("坐标转换", "WGS-84 → GCJ-02")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun AboutRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            ),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ── 其他组件 ────────────────────────────────────────────

private fun generateMockData(eventStore: EventStore) {
    val addresses = listOf("办公大楼", "城市广场", "星巴克咖啡", "健身中心", "家里", "图书馆", "购物中心")
    val notes = listOf("开会中", "正在休息", "买杯咖啡", "锻炼身体", "阅读书籍", "看场电影", "逛街中")

    for (day in 0..10) {
        val count = Random.nextInt(1, 4)
        for (i in 1..count) {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -day)
            calendar.set(Calendar.HOUR_OF_DAY, Random.nextInt(8, 22))
            calendar.set(Calendar.MINUTE, Random.nextInt(0, 59))

            val timestamp = calendar.timeInMillis
            val event = Event(
                uuid = java.util.UUID.randomUUID().toString(),
                createdAt = timestamp,
                latitude = 39.9 + (Random.nextDouble() * 0.1),
                longitude = 116.3 + (Random.nextDouble() * 0.1),
                address = addresses.random(),
                audioDuration = if (Random.nextBoolean()) Random.nextInt(5000, 30000) else null,
                note = if (Random.nextBoolean()) notes.random() else null,
                status = Event.STATUS_DONE
            )
            eventStore.insert(event)
        }
    }
}

@Composable
private fun QualitySelectionDialog(
    currentLite: Boolean,
    onDismiss: () -> Unit,
    onSelected: (Boolean) -> Unit
) {
    val options = listOf(
        false to "完整原图（推荐，凭证保真）",
        true to "轻量压缩（长边 1600px，包体小 10 倍+）"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择备份画质") },
        text = {
            Column {
                options.forEach { (lite, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(lite) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = lite == currentLite,
                            onClick = { onSelected(lite) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun WebDavConfigDialog(
    initialUrl: String,
    initialUser: String,
    initialPass: String,
    onDismiss: () -> Unit,
    onSave: (url: String, user: String, pass: String) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl.ifBlank { "https://dav.jianguoyun.com/dav/" }) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV 服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("应用密码") },
                    placeholder = { Text("坚果云：账户信息 → 安全选项 → 添加应用密码") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "记录与照片将同步到该网盘的 MarkLine/ 目录，密码仅保存在本机",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url.trim(), user.trim(), pass) },
                enabled = url.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun DurationSelectionDialog(
    currentDuration: Int,
    onDismiss: () -> Unit,
    onSelected: (Int) -> Unit
) {
    val options = listOf(3, 10, 15, 30)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择录音时长") },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == currentDuration,
                            onClick = { onSelected(option) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "${option}秒")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SwitchItem(
    icon:      ImageVector,
    title:     String,
    subtitle:  String,
    checked:   Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked         = checked,
            onCheckedChange = onChecked,
            colors          = SwitchDefaults.colors(
                checkedThumbColor  = Color.White,
                checkedTrackColor  = Green500,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun ArrowItem(
    icon:     ImageVector,
    title:    String,
    trailing: String = "",
    onClick:  () -> Unit
) {
    Surface(
        onClick = onClick,
        color   = Color.Transparent
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium)
            if (trailing.isNotBlank()) {
                Text(trailing, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(4.dp))
            }
            Icon(Icons.Outlined.NavigateNext, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp))
        }
    }
}
