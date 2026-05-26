package app.markline.ui

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
import app.markline.MarkLineApp
import app.markline.domain.Event
import app.markline.domain.EventStore
import app.markline.system.AudioRecordService
import app.markline.ui.theme.*
import app.markline.util.AudioFileUtil
import app.markline.util.TimeUtil
import app.markline.util.Wgs84ToGcj02
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    
    // 定位状态
    var isLocating by remember { mutableStateOf(false) }

    // 备注编辑状态
    var isEditingNote by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // 主题标签编辑状态
    var isEditingLabel by remember { mutableStateOf(false) }
    var labelText by remember { mutableStateOf("") }
    val existingLabels = remember { mutableStateListOf<String>() }

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
            Text(
                text = TimeUtil.formatDateTime(ev.createdAt),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp)
            )

            HorizontalDivider()

            // ── 主题标签 ──
            DetailSection(icon = Icons.Outlined.Label, title = "主题") {
                if (isEditingLabel) {
                    // 编辑模式：已有标签快捷选择 + 手动输入
                    Column {
                        // 已有标签作为可点击 Chip
                        if (existingLabels.isNotEmpty()) {
                            Text("选择已有主题：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(6.dp))
                            Column {
                                existingLabels.chunked(3).forEach { rowLabels ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(vertical = 3.dp)
                                    ) {
                                        rowLabels.forEach { lbl ->
                                            SuggestionChip(
                                                onClick = {
                                                    scope.launch {
                                                        eventStore.updateLabel(ev.id, lbl)
                                                        refreshTick++
                                                        isEditingLabel = false
                                                    }
                                                },
                                                label = { Text(lbl, fontSize = 13.sp) },
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor = Green500.copy(alpha = 0.1f)
                                                ),
                                                border = SuggestionChipDefaults.suggestionChipBorder(
                                                    borderColor = Green500.copy(alpha = 0.3f),
                                                    enabled = true
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("或输入新主题：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        OutlinedTextField(
                            value = labelText,
                            onValueChange = { labelText = it },
                            placeholder = { Text("输入主题名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    if (labelText.isNotBlank()) {
                                        scope.launch {
                                            eventStore.updateLabel(ev.id, labelText.trim())
                                            refreshTick++
                                            isEditingLabel = false
                                        }
                                    }
                                }) {
                                    Icon(Icons.Outlined.Check, contentDescription = "确认", tint = Green500)
                                }
                            }
                        )
                    }
                    LaunchedEffect(Unit) {
                        // 加载已有标签
                        existingLabels.clear()
                        existingLabels.addAll(eventStore.queryLabels())
                    }
                } else {
                    // 展示模式：显示当前标签或"添加到主题"
                    if (!ev.label.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Green500.copy(alpha = 0.12f))
                                    .clickable {
                                        labelText = ev.label ?: ""
                                        isEditingLabel = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    ev.label,
                                    color = Green500,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "点击修改",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    labelText = ev.label ?: ""
                                    isEditingLabel = true
                                }
                            )
                        }
                    } else {
                        Text(
                            "添加到主题",
                            color = Green500,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                labelText = ""
                                isEditingLabel = true
                            }
                        )
                    }
                }
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
                    if (isRecording) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("录音中 ${recordingCountdown}s...", color = Red400)
                            Text(
                                "点此结束录音",
                                color = Green500,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    // 停止前台录音服务
                                    val stopIntent = Intent(context, AudioRecordService::class.java).apply {
                                        action = AudioRecordService.ACTION_STOP
                                    }
                                    context.startService(stopIntent)
                                    // 取消倒计时协程，立即进入文件轮询
                                    recordingJob?.cancel()
                                }
                            )
                        }
                    } else {
                        Text("点此开始补录", color = Green500, modifier = Modifier.clickable {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                return@clickable
                            }
                            val duration = app.settingsStore.audioDurationSec
                            isRecording = true
                            recordingCountdown = duration
                            recordingJob = scope.launch {
                                val startTime = System.currentTimeMillis()
                                try {
                                    // 1. 启动前台录音服务
                                    AudioRecordService.outputFilePath = null
                                    val intent = Intent(context, AudioRecordService::class.java).apply {
                                        putExtra(AudioRecordService.EXTRA_DURATION_SEC, duration)
                                    }
                                    ContextCompat.startForegroundService(context, intent)

                                    // 2. 倒计时（与录音并行），可被用户提前取消
                                    for (i in duration downTo 1) {
                                        recordingCountdown = i
                                        delay(1000)
                                    }
                                    recordingCountdown = 0
                                } catch (_: CancellationException) {
                                    // 用户提前结束，继续进入文件轮询
                                    recordingCountdown = 0
                                }

                                // 3. 轮询文件（不受取消影响）
                                withContext(NonCancellable) {
                                    val deadline = System.currentTimeMillis() + 5_000L
                                    var outFile: java.io.File? = null
                                    while (System.currentTimeMillis() < deadline) {
                                        val path = AudioRecordService.outputFilePath
                                        if (path != null) {
                                            outFile = java.io.File(path)
                                            if (outFile!!.exists() && outFile!!.length() > 100) break
                                        }
                                        delay(300)
                                    }
                                    if (outFile != null && outFile!!.exists() && outFile!!.length() > 100) {
                                        val actualMs = (System.currentTimeMillis() - startTime).coerceAtMost(duration * 1000L).toInt()
                                        eventStore.updateEnhancement(id = ev.id, audioFileName = outFile!!.name, audioDuration = actualMs, status = Event.STATUS_DONE)
                                        refreshTick++
                                    }
                                }
                                isRecording = false
                            }
                        })
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
