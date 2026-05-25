package com.example.markline.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.markline.domain.Event
import com.example.markline.domain.EventStore
import com.example.markline.ui.theme.*
import com.example.markline.util.TimeUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    eventId:    Long,
    eventStore: EventStore,
    onBack:     () -> Unit,
    onEdit:     (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val event by produceState<Event?>(null, eventId) {
        value = eventStore.findById(eventId)
    }

    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

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
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                title = {
                    Text("记录详情", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                actions = {
                    IconButton(onClick = { onEdit(eventId) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                    }
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
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = TimeUtil.formatDateTime(ev.createdAt), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                StatusBadge(status = ev.status)
            }

            HorizontalDivider(color = Gray100)

            // ── 位置 ──
            DetailSection(icon = Icons.Outlined.LocationOn, title = "位置") {
                if (ev.latitude != null && ev.longitude != null) {
                    Text(text = ev.address ?: "地址解析中…", style = MaterialTheme.typography.bodyLarge)
                    Text(text = "${ev.latitude}, ${ev.longitude}", style = MaterialTheme.typography.bodySmall, color = Gray400)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Gray50)
                            .border(1.dp, Gray100, RoundedCornerShape(12.dp))
                            .clickable {
                                // 针对中国地图偏移修复：添加 coordType=gcj02。对于 Google 地图此参数会被忽略。
                                // 同时构造更通用的 Intent，尝试调起不同的地图 App
                                val label = ev.address ?: "Mark"
                                val uriString = "geo:${ev.latitude},${ev.longitude}?q=${ev.latitude},${ev.longitude}($label)&coordType=gcj02"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
                                
                                // 优先尝试高德地图特定的 URI 协议 (如果安装了)
                                val amapUri = Uri.parse("androidamap://viewMap?sourceApplication=MarkLine&poiname=$label&lat=${ev.latitude}&lon=${ev.longitude}&dev=0")
                                val amapIntent = Intent(Intent.ACTION_VIEW, amapUri).apply { setPackage("com.autonavi.minimap") }
                                
                                try {
                                    if (amapIntent.resolveActivity(context.packageManager) != null) {
                                        context.startActivity(amapIntent)
                                    } else {
                                        context.startActivity(intent)
                                    }
                                } catch (e: Exception) {
                                    context.startActivity(intent)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Map, contentDescription = null, tint = Green500, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("点击查看地图 (已尝试纠偏)", color = Green500, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Text("定位失败", color = Gray400)
                }
            }

            HorizontalDivider(color = Gray100)

            // ── 录音 ──
            DetailSection(icon = Icons.Outlined.Mic, title = "录音") {
                if (!ev.audioPath.isNullOrBlank()) {
                    val dur = ev.audioDuration?.let { "${it / 1000}秒" } ?: "--"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AudioWaveform(modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(12.dp))
                        IconButton(
                            onClick = { togglePlay(ev.audioPath) },
                            modifier = Modifier.size(44.dp).background(Green500, CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                                contentDescription = if (isPlaying) "停止" else "播放",
                                tint = Color.White
                            )
                        }
                    }
                    Text(dur, color = Gray400, fontSize = 12.sp)
                } else {
                    Text("暂无录音", color = Gray400)
                }
            }

            HorizontalDivider(color = Gray100)

            // ── 备注 ──
            DetailSection(icon = Icons.Outlined.Description, title = "备注") {
                Text(ev.note ?: "暂无备注", style = MaterialTheme.typography.bodyLarge)
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
                text = { Text("删除后无法恢复，确认删除吗？") },
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
    Box(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailSection(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Gray400, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, color = Gray500, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
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
