package com.example.markline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.markline.domain.Event
import com.example.markline.domain.EventStore
import com.example.markline.ui.theme.Gray100
import com.example.markline.ui.theme.Gray400
import com.example.markline.ui.theme.Gray50
import com.example.markline.ui.theme.Gray500
import com.example.markline.ui.theme.Gray900
import com.example.markline.ui.theme.Green500
import com.example.markline.util.TimeUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    eventStore: EventStore,
    onEventClick: (Long) -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    val events by produceState<List<Event>>(emptyList()) {
        value = eventStore.queryRecent(100)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "历史记录",
                        fontWeight = FontWeight.Bold,
                        fontSize   = 20.sp
                    )
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Outlined.FilterList, contentDescription = "过滤",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->

        if (events.isEmpty()) {
            Box(
                modifier         = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无记录", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("点击首页签到按钮开始记录", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
            return@Scaffold
        }

        // 按日期分组
        val grouped = events.groupBy { TimeUtil.formatDate(it.createdAt) }

        LazyColumn(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding      = PaddingValues(bottom = 24.dp)
        ) {
            grouped.forEach { (dateLabel, dayEvents) ->
                // 日期 header
                item(key = "header_$dateLabel") {
                    Text(
                        text     = dateLabel,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                    )
                }

                items(dayEvents, key = { it.id }) { event ->
                    TimelineItem(
                        event        = event,
                        isLast       = event == dayEvents.last(),
                        onClick      = { onEventClick(event.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineItem(
    event:   Event,
    isLast:  Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
    ) {
        // 左侧：时间轴线 + 圆点
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(Green500, CircleShape)
                    .offset(y = 6.dp)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(80.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 右侧：卡片内容
        Card(
            modifier  = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape     = RoundedCornerShape(12.dp),
            colors    = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment   = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 时间
                    Text(
                        text       = TimeUtil.formatTime(event.createdAt),
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 15.sp,
                        color      = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 地址
                    if (!event.address.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.LocationOn,
                                contentDescription = null,
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text     = event.address,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                    }

                    // 录音
                    if (event.audioDuration != null && event.audioDuration > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Mic,
                                contentDescription = null,
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text     = "${event.audioDuration / 1000}秒",
                                color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                            // 波形占位条
                            Spacer(modifier = Modifier.width(6.dp))
                            WaveformBar()
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                    }

                    // 备注摘要
                    if (!event.note.isNullOrBlank()) {
                        Text(
                            text     = event.note,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Icon(
                    Icons.Outlined.NavigateNext,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 简单波形占位（灰色小竖条） */
@Composable
private fun WaveformBar() {
    val heights = listOf(4, 8, 6, 10, 7, 5, 9, 6, 8, 4)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(12.dp)
    ) {
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(h.dp)
                    .background(Green500.copy(alpha = 0.6f), RoundedCornerShape(1.dp))
            )
        }
    }
}
