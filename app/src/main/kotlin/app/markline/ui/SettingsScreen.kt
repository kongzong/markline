package app.markline.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.markline.domain.Event
import app.markline.domain.EventStore
import app.markline.ui.theme.*
import app.markline.util.SettingsStore
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    eventStore: EventStore, // 增加参数以支持生成数据
    onBack: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var enableAudio   by remember { mutableStateOf(settingsStore.audioEnabled) }
    var audioDuration by remember { mutableStateOf(settingsStore.audioDurationSec) }
    var enableLocation by remember { mutableStateOf(settingsStore.locationEnabled) }
    var enableVibrate  by remember { mutableStateOf(settingsStore.vibrateEnabled) }

    var showDurationDialog by remember { mutableStateOf(false) }

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

            // ── 调试工具 ──────────────────────────────────
            SectionHeader("调试工具")

            ArrowItem(
                icon    = Icons.Outlined.Storage,
                title   = "生成过去 10 天模拟数据",
                trailing = "点击生成",
                onClick = {
                    scope.launch {
                        generateMockData(eventStore)
                    }
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

            ArrowItem(
                icon    = Icons.Outlined.HelpOutline,
                title   = "使用帮助",
                onClick = {}
            )

            ArrowItem(
                icon    = Icons.Outlined.Info,
                title   = "关于我们",
                onClick = {}
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

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
}

private fun generateMockData(eventStore: EventStore) {
    val addresses = listOf("办公大楼", "城市广场", "星巴克咖啡", "健身中心", "家里", "图书馆", "购物中心")
    val notes = listOf("开会中", "正在休息", "买杯咖啡", "锻炼身体", "阅读书籍", "看场电影", "逛街中")

    for (day in 0..10) {
        val count = Random.nextInt(1, 4) // 每天生成 1-3 条
        for (i in 1..count) {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -day)
            calendar.set(Calendar.HOUR_OF_DAY, Random.nextInt(8, 22))
            calendar.set(Calendar.MINUTE, Random.nextInt(0, 59))
            
            val timestamp = calendar.timeInMillis
            val event = Event(
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
