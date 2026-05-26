package app.markline.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
import app.markline.domain.Event
import app.markline.domain.EventStore
import app.markline.ui.theme.Green500
import app.markline.util.TimeUtil
import kotlinx.coroutines.delay
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    eventStore: EventStore,
    onEventClick: (Long) -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    // 状态定义：记录哪些日期被折叠
    val collapsedDates = remember { mutableStateMapOf<String, Boolean>() }
    
    // 搜索状态
    var searchQuery by remember { mutableStateOf("") }

    // 获取最近 7 天的记录（最新在上已经在 EventStore.queryRecent 中实现）
    val events by produceState<List<Event>>(emptyList()) {
        // 简单处理：查出 200 条，然后在 Compose 层过滤最近 7 天
        val all = eventStore.queryRecent(200)
        val limit = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.timeInMillis
        value = all.filter { it.createdAt >= limit }
    }

    // 搜索结果（带防抖）
    val searchResults by produceState<List<Event>>(emptyList(), searchQuery) {
        if (searchQuery.isBlank()) {
            value = emptyList()
        } else {
            delay(300) // 300ms 防抖
            value = eventStore.search(searchQuery.trim())
        }
    }

    val isSearching = searchQuery.isNotBlank()
    val displayEvents = if (isSearching) searchResults else events

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Line", // 修改标题为 Line
                        fontWeight = FontWeight.Bold,
                        fontSize   = 20.sp,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        }
    ) { padding ->

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ── 搜索栏 ──
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索备注、地点、主题、日期…") },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Green500,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            if (displayEvents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (isSearching) "未找到匹配「${searchQuery}」的记录" else "最近 7 天暂无记录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }

            // 按日期分组
            val grouped = displayEvents.groupBy { TimeUtil.formatDate(it.createdAt) }
            val today = TimeUtil.formatDate(System.currentTimeMillis())

        LazyColumn(
            modifier            = Modifier.fillMaxSize(),
            contentPadding      = PaddingValues(bottom = 24.dp)
        ) {
            grouped.forEach { (dateLabel, dayEvents) ->
                val isToday = dateLabel == today
                // 搜索模式下全部展开，平时非当天折叠
                val isCollapsed = if (isSearching) false
                                  else collapsedDates.getOrDefault(dateLabel, !isToday)

                // 日期分组 Header
                item(key = "header_$dateLabel") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { collapsedDates[dateLabel] = !isCollapsed }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text     = "$dateLabel (${dayEvents.size})",
                            color    = if (isToday) Green500 else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (isCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 分组下的记录
                items(dayEvents, key = { it.id }) { event ->
                    AnimatedVisibility(visible = !isCollapsed) {
                        TimelineItem(
                            event        = event,
                            isLast       = event == dayEvents.last(),
                            onClick      = { onEventClick(event.id) }
                        )
                    }
                }
            }
        }
        }  // end Column
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
        // 左侧：时间轴线
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier.size(10.dp).background(Green500, CircleShape).offset(y = 8.dp)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier.width(2.dp).height(80.dp).background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 右侧：卡片内容
        Card(
            modifier  = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape     = RoundedCornerShape(12.dp),
            colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = TimeUtil.formatTime(event.createdAt),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (!event.address.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(event.address, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    if (event.audioDuration != null && event.audioDuration > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Mic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${event.audioDuration / 1000}秒", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            WaveformBar()
                        }
                    }

                    if (!event.note.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(event.note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Icon(Icons.Outlined.NavigateNext, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun WaveformBar() {
    val heights = listOf(4, 8, 6, 10, 7, 5, 9, 6, 8, 4)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(12.dp)) {
        heights.forEach { h ->
            Box(modifier = Modifier.width(2.dp).height(h.dp).background(Green500.copy(alpha = 0.6f), RoundedCornerShape(1.dp)))
        }
    }
}
