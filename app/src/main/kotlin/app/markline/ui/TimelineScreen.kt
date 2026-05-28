package app.markline.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    // 主题过滤状态
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    val allLabels by produceState<List<String>>(emptyList()) {
        value = eventStore.queryLabels()
    }

    val isFiltering = selectedLabel != null
    val isSearching = searchQuery.isNotBlank()

    // ── 数据源：默认模式 — 最近 7 天 ──
    val recentEvents by produceState<List<Event>>(emptyList(), searchQuery, selectedLabel) {
        if (isSearching || isFiltering) return@produceState
        val all = eventStore.queryRecent(200)
        val limit = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.timeInMillis
        value = all.filter { it.createdAt >= limit }
    }

    // ── 数据源：主题过滤模式 — 不限时间 ──
    val filteredEvents by produceState<List<Event>>(emptyList(), selectedLabel, searchQuery) {
        val label = selectedLabel ?: return@produceState
        if (isSearching) return@produceState
        value = eventStore.queryByLabel(label)
    }

    // ── 数据源：搜索模式（带防抖） — 不限时间 ──
    val searchResults by produceState<List<Event>>(emptyList(), searchQuery, selectedLabel) {
        if (searchQuery.isBlank()) {
            value = emptyList()
        } else {
            delay(300) // 300ms 防抖
            val results = eventStore.search(searchQuery.trim())
            // 如果同时有主题过滤，在搜索结果中进一步过滤
            value = if (selectedLabel != null) {
                results.filter { it.label == selectedLabel }
            } else {
                results
            }
        }
    }

    // 决定显示哪组数据
    val displayEvents = when {
        isSearching -> searchResults
        isFiltering -> filteredEvents
        else -> recentEvents
    }

    // 搜索或过滤模式下展开所有分组
    val expandAll = isSearching || isFiltering

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Line",
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

            // ── 主题过滤 Chips（仅在有标签时显示）──
            if (allLabels.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedLabel == null,
                        onClick = { selectedLabel = null },
                        label = { Text("全部") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Green500,
                            selectedLabelColor = Color.White
                        )
                    )
                    allLabels.forEach { label ->
                        FilterChip(
                            selected = selectedLabel == label,
                            onClick = {
                                selectedLabel = if (selectedLabel == label) null else label
                            },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Green500,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            if (displayEvents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val emptyText = when {
                        isSearching && isFiltering -> "主题「${selectedLabel}」下未找到匹配「${searchQuery}」的记录"
                        isSearching -> "未找到匹配「${searchQuery}」的记录"
                        isFiltering -> "主题「${selectedLabel}」下暂无记录"
                        else -> "最近 7 天暂无记录"
                    }
                    Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Scaffold
            }

            // 按日期分组
            val grouped = displayEvents.groupBy { TimeUtil.formatDate(it.createdAt) }
            val today = TimeUtil.formatDate(System.currentTimeMillis())

            LazyColumn(
                modifier       = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                grouped.forEach { (dateLabel, dayEvents) ->
                    val isToday = dateLabel == today
                    val isCollapsed = if (expandAll) false
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
                                event   = event,
                                isLast  = event == dayEvents.last(),
                                onClick = { onEventClick(event.id) }
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

                    // ── 主题小标签 ──
                    if (!event.label.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        ) {
                            Text(
                                event.label,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1
                            )
                        }
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
