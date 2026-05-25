package com.example.markline.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.markline.domain.Event
import com.example.markline.domain.EventStore
import com.example.markline.system.AudioRecorder
import com.example.markline.system.LocationService
import com.example.markline.ui.theme.*
import com.example.markline.util.AudioFileUtil
import com.example.markline.util.SettingsStore
import com.example.markline.util.TimeUtil
import kotlinx.coroutines.launch

/**
 * 记录编辑页
 *
 * 支持手工补足：
 *   - 位置：重新定位 或 手动输入地址
 *   - 录音：重新录音（替换旧录音）
 *   - 备注：自由文本
 *
 * Append-only 原则：不修改 created_at，不修改原始坐标（除非用户主动重新定位）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventScreen(
    eventId:         Long,
    eventStore:      EventStore,
    locationService: LocationService,
    audioRecorder:   AudioRecorder,
    settingsStore:   SettingsStore,
    onBack:          () -> Unit,
    onSaved:         () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    // 加载原始记录
    val event by produceState<Event?>(null, eventId) {
        value = eventStore.findById(eventId)
    }

    // 编辑状态
    var noteText   by remember { mutableStateOf("") }
    var addrText   by remember { mutableStateOf("") }
    var newLat     by remember { mutableStateOf<Double?>(null) }
    var newLng     by remember { mutableStateOf<Double?>(null) }
    var newAudio   by remember { mutableStateOf<String?>(null) }
    var newAudioDur by remember { mutableStateOf<Int?>(null) }

    // 加载完成后初始化编辑字段
    LaunchedEffect(event) {
        event?.let { ev ->
            noteText = ev.note ?: ""
            addrText = ev.address ?: ""
        }
    }

    // UI 状态
    var isLocating  by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var isSaving    by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                title = {
                    Text(
                        "编辑记录",
                        fontWeight = FontWeight.Bold,
                        fontSize   = 18.sp
                    )
                },
                actions = {
                    // 保存按钮
                    TextButton(
                        onClick = {
                            if (!isSaving) {
                                isSaving = true
                                scope.launch {
                                    saveChanges(
                                        context      = context,
                                        eventId      = eventId,
                                        eventStore   = eventStore,
                                        noteText     = noteText,
                                        addrText     = addrText,
                                        newLat       = newLat,
                                        newLng       = newLng,
                                        newAudio     = newAudio,
                                        newAudioDur  = newAudioDur
                                    )
                                    isSaving = false
                                    onSaved()
                                }
                            }
                        },
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color    = Green500,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("保存", color = Green500, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->

        if (event == null) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Green500)
            }
            return@Scaffold
        }

        val ev = event!!

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {

            Spacer(modifier = Modifier.height(8.dp))

            // ── 记录元信息（只读）────────────────────────────
            Text(
                text  = TimeUtil.formatDateTime(ev.createdAt),
                style = MaterialTheme.typography.titleMedium,
                color = Gray900,
                fontWeight = FontWeight.Bold
            )
            Text(
                text  = "编辑模式 · 补足记录信息",
                style = MaterialTheme.typography.bodySmall,
                color = Gray400
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ━━━━━━━━ 位置 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

            SectionHeader(icon = Icons.Outlined.LocationOn, title = "位置")

            Spacer(modifier = Modifier.height(8.dp))

            // 地址文本输入（手动补填）
            OutlinedTextField(
                value         = addrText,
                onValueChange = { addrText = it },
                label         = { Text("地址（手动输入或重新定位）") },
                placeholder   = { Text("如：北京市朝阳区XX路XX号") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Green500,
                    focusedLabelColor    = Green500,
                    cursorColor          = Green500
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 新定位结果预览
            if (newLat != null && newLng != null) {
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .background(Green500.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.GpsFixed, contentDescription = null,
                        tint = Green500, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text  = "已重新定位：${"%.5f".format(newLat)}, ${"%.5f".format(newLng)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Green500
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else if (ev.latitude != null) {
                // 显示原有坐标
                Text(
                    text  = "当前坐标：${"%.5f".format(ev.latitude)}, ${"%.5f".format(ev.longitude)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray400
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 重新定位按钮
            OutlinedButton(
                onClick = {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasPerm) {
                        scope.launch { snackbarHost.showSnackbar("需要位置权限，请在设置中开启") }
                        return@OutlinedButton
                    }

                    if (!isLocating) {
                        isLocating = true
                        scope.launch {
                            try {
                                val loc = locationService.getCurrentLocation()
                                if (loc != null) {
                                    newLat   = loc.latitude
                                    newLng   = loc.longitude
                                    if (!loc.address.isNullOrBlank()) {
                                        addrText = loc.address
                                    }
                                    snackbarHost.showSnackbar("定位成功")
                                } else {
                                    snackbarHost.showSnackbar("定位失败，请检查 GPS 权限")
                                }
                            } finally {
                                isLocating = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = Green500),
                border   = androidx.compose.foundation.BorderStroke(1.dp, Green500)
            ) {
                if (isLocating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color    = Green500,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("定位中…")
                } else {
                    Icon(Icons.Outlined.GpsFixed, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重新定位")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Gray100)
            Spacer(modifier = Modifier.height(20.dp))

            // ━━━━━━━━ 录音 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

            SectionHeader(icon = Icons.Outlined.Mic, title = "录音")

            Spacer(modifier = Modifier.height(8.dp))

            // 当前录音状态
            val currentAudioFileName = newAudio ?: ev.audioFileName
            if (!currentAudioFileName.isNullOrBlank()) {
                val dur = (newAudioDur ?: ev.audioDuration)?.let { "${it / 1000}秒" } ?: "--"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (newAudio != null) Green500.copy(alpha = 0.06f)
                            else Gray100.copy(alpha = 0.6f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.AudioFile, contentDescription = null,
                        tint = if (newAudio != null) Green500 else Gray400,
                        modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text  = if (newAudio != null) "新录音：$dur" else "已有录音：$dur",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (newAudio != null) Green500 else Gray500
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Text(
                    text  = "暂无录音",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray400
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 录音按钮
            val audioDuration = settingsStore.audioDurationSec
            OutlinedButton(
                onClick = {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasPerm) {
                        scope.launch { snackbarHost.showSnackbar("需要录音权限，请在设置中开启") }
                        return@OutlinedButton
                    }

                    if (!isRecording) {
                        isRecording = true
                        scope.launch {
                            try {
                                val result = audioRecorder.record(audioDuration)
                                if (result != null) {
                                    newAudio    = result.fileName
                                    newAudioDur = result.durationMs
                                    snackbarHost.showSnackbar("录音完成（${result.durationMs / 1000}秒）")
                                } else {
                                    snackbarHost.showSnackbar("录音失败，请检查麦克风权限")
                                }
                            } finally {
                                isRecording = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = Green500),
                border   = androidx.compose.foundation.BorderStroke(1.dp, Green500)
            ) {
                if (isRecording) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color    = Green500,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("录音中（${audioDuration}秒）…")
                } else {
                    Icon(Icons.Outlined.Mic, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (newAudio != null) "重新录音" else "开始录音")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Gray100)
            Spacer(modifier = Modifier.height(20.dp))

            // ━━━━━━━━ 备注 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

            SectionHeader(icon = Icons.Outlined.Description, title = "备注")

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value         = noteText,
                onValueChange = { noteText = it },
                label         = { Text("备注内容") },
                placeholder   = { Text("记录现场关键信息、任务说明等…") },
                modifier      = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                maxLines      = 8,
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Green500,
                    focusedLabelColor  = Green500,
                    cursorColor        = Green500
                )
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 底部保存按钮（大按钮）
            Button(
                onClick = {
                    if (!isSaving) {
                        isSaving = true
                        scope.launch {
                            saveChanges(
                                context     = context,
                                eventId     = eventId,
                                eventStore  = eventStore,
                                noteText    = noteText,
                                addrText    = addrText,
                                newLat      = newLat,
                                newLng      = newLng,
                                newAudio    = newAudio,
                                newAudioDur = newAudioDur
                            )
                            isSaving = false
                            onSaved()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape  = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Green500,
                    contentColor   = Color.White
                ),
                enabled = !isSaving
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存记录", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 执行实际保存逻辑（suspend，在 IO 协程中运行） */
private suspend fun saveChanges(
    context:     Context,
    eventId:     Long,
    eventStore:  EventStore,
    noteText:    String,
    addrText:    String,
    newLat:      Double?,
    newLng:      Double?,
    newAudio:    String?,
    newAudioDur: Int?
) {
    // 更新备注
    if (noteText.isNotBlank()) {
        eventStore.updateNote(eventId, noteText.trim())
    }

    // 更新位置（新定位坐标优先；否则仅更新地址文本）
    if (newLat != null && newLng != null) {
        eventStore.updateEnhancement(
            id        = eventId,
            latitude  = newLat,
            longitude = newLng,
            address   = addrText.ifBlank { null }
        )
    } else if (addrText.isNotBlank()) {
        eventStore.updateEnhancement(
            id      = eventId,
            address = addrText.trim()
        )
    }

    // 更新录音
    if (newAudio != null) {
        eventStore.updateEnhancement(
            id            = eventId,
            audioFileName = newAudio,
            audioDuration = newAudioDur
        )
    }
}

@Composable
private fun SectionHeader(
    icon:  androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .background(Green500.copy(alpha = 0.1f), CircleShape)
        ) {
            Icon(icon, contentDescription = null, tint = Green500, modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Gray900)
    }
}

// TimeUtil.formatDateTime() 已在 TimeUtil.kt 中统一定义
