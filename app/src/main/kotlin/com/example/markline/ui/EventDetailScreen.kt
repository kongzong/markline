package com.example.markline.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.markline.MarkLineApp
import com.example.markline.domain.Event
import com.example.markline.domain.EventStore
import com.example.markline.ui.theme.*
import com.example.markline.util.AudioFileUtil
import com.example.markline.util.TimeUtil
import com.example.markline.util.Wgs84ToGcj02
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    eventId:    Long,
    eventStore: EventStore,
    onBack:     () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as MarkLineApp
    
    // 监听数据库变化，实时刷新 UI
    var refreshTick by remember { mutableIntStateOf(0) }
    val event by produceState<Event?>(null, eventId, refreshTick) {
        value = eventStore.findById(eventId)
    }

    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    // 录音状态
    var isRecording by remember { mutableStateOf(false) }
    var recordingCountdown by remember { mutableIntStateOf(0) }
    
    // 定位状态
    var isLocating by remember { mutableStateOf(false) }

    // 备注编辑状态
    var isEditingNote by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // 权限申请
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    // 播放逻辑
    fun togglePlay(path: String) {
        if (isPlaying) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isPlaying = false
        } else {
            try {
                val player = MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    start()
                    setOnCompletionListener {
                        isPlaying = false
                        release()
                        mediaPlayer = null
                    }
                }
                mediaPlayer = player
                isPlaying = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(), // 增加 imePadding 确保脚手架内容避开键盘
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Outlined.ArrowBack, 
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                title = {
                    Text(
                        "记录详情", 
                        fontWeight = FontWeight.Bold, 
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            )
        }
    ) { padding ->

        if (event == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Green500)
            }
            return@Scaffold
        }

        val ev = event!!

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState) // 使用外部定义的 scrollState
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = TimeUtil.formatDateTime(ev.createdAt), 
                    fontWeight = FontWeight.Bold, 
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                StatusBadge(status = ev.status)
            }

            HorizontalDivider()

            // ── 位置 ──
            DetailSection(icon = Icons.Outlined.LocationOn, title = "位置") {
                if (ev.latitude != null && ev.longitude != null) {
                    Text(text = ev.address ?: "地址解析中…", style = MaterialTheme.typography.bodyLarge)
                    Text(text = "${ev.latitude}, ${ev.longitude}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    val (gcjLat, gcjLon) = Wgs84ToGcj02.transform(ev.latitude, ev.longitude)
                    val label = ev.address ?: "Mark"

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Green500.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable {
                                val amapUri = Uri.parse("androidamap://viewMap?sourceApplication=MarkLine&poiname=$label&lat=$gcjLat&lon=$gcjLon&dev=0")
                                val amapIntent = Intent(Intent.ACTION_VIEW, amapUri).apply { setPackage("com.autonavi.minimap") }
                                val geoUri = "geo:$gcjLat,$gcjLon?q=$gcjLat,$gcjLon($label)"
                                val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
                                try {
                                    if (amapIntent.resolveActivity(context.packageManager) != null) context.startActivity(amapIntent)
                                    else context.startActivity(geoIntent)
                                } catch (_: Exception) {
                                    try { context.startActivity(geoIntent) } catch (_: Exception) {}
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Map, contentDescription = null, tint = Green500, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("在地图中查看", color = Green500, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("定位失败 ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isLocating) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Green500)
                        } else {
                            Text(
                                "点此重新获取", 
                                color = Green500, 
                                modifier = Modifier.clickable {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                        permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                        return@clickable
                                    }
                                    isLocating = true
                                    scope.launch {
                                        val loc = app.locationService.getCurrentLocation()
                                        if (loc != null) {
                                            eventStore.updateEnhancement(id = ev.id, latitude = loc.latitude, longitude = loc.longitude, address = loc.address, status = Event.STATUS_DONE)
                                            refreshTick++
                                        }
                                        isLocating = false
                                    }
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── 录音 ──
            DetailSection(icon = Icons.Outlined.Mic, title = "录音") {
                if (!ev.audioFileName.isNullOrBlank()) {
                    val dur = ev.audioDuration?.let { "${it / 1000}秒" } ?: "--"
                    val fullPath = AudioFileUtil.getAudioFile(context, ev.audioFileName).absolutePath
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AudioWaveform(modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(12.dp))
                        IconButton(
                            onClick = { togglePlay(fullPath) },
                            modifier = Modifier.size(44.dp).background(Green500, CircleShape)
                        ) {
                            Icon(if (isPlaying) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White)
                        }
                    }
                    Text(dur, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("无录音 ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isRecording) {
                            Text("录音中 ${recordingCountdown}s...", color = Red400)
                        } else {
                            Text("点此开始补录", color = Green500, modifier = Modifier.clickable {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                    return@clickable
                                }
                                val duration = app.settingsStore.audioDurationSec
                                isRecording = true
                                recordingCountdown = duration
                                scope.launch {
                                    val timerJob = launch {
                                        for (i in duration downTo 1) {
                                            recordingCountdown = i
                                            delay(1000)
                                        }
                                    }
                                    val result = app.audioRecorder.record(duration)
                                    timerJob.cancel()
                                    if (result != null) {
                                        eventStore.updateEnhancement(id = ev.id, audioFileName = result.fileName, audioDuration = result.durationMs, status = Event.STATUS_DONE)
                                        refreshTick++
                                    }
                                    isRecording = false
                                }
                            })
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── 备注 ──
            DetailSection(icon = Icons.Outlined.Description, title = "备注") {
                if (isEditingNote) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        trailingIcon = {
                            IconButton(onClick = {
                                scope.launch {
                                    eventStore.updateNote(ev.id, noteText)
                                    isEditingNote = false
                                    refreshTick++
                                }
                            }) {
                                Icon(Icons.Outlined.Check, contentDescription = "完成", tint = Green500)
                            }
                        }
                    )
                    // 自动聚焦并滚动
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                        // 延迟一点确保键盘弹出后再滚动
                        delay(200)
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                } else {
                    Text(
                        ev.note ?: "暂无备注，点此添加",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                noteText = ev.note ?: ""
                                isEditingNote = true
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red400)
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("删除记录", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("确认删除") },
                confirmButton = {
                    TextButton(onClick = {
                        eventStore.deleteById(eventId)
                        onBack()
                    }) { Text("删除", color = Red400) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun StatusBadge(status: Int) {
    val (label, color) = when (status) {
        1    -> "已完成" to Green500
        2    -> "定位失败" to Amber400
        3    -> "录音失败" to Amber400
        else -> "已创建" to Blue400
    }
    Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailSection(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            content()
        }
    }
}

@Composable
private fun AudioWaveform(modifier: Modifier = Modifier) {
    val bars = listOf(6, 12, 8, 16, 10, 18, 12, 8, 14, 10, 16, 8, 12, 6, 10, 14, 8, 16, 10, 12)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier.height(24.dp)) {
        bars.forEach { h ->
            Box(modifier = Modifier.width(3.dp).height(h.dp).background(Green500.copy(alpha = 0.7f), RoundedCornerShape(2.dp)))
        }
    }
}
