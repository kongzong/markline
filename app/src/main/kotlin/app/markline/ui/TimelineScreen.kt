package app.markline.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.markline.domain.Event
import app.markline.domain.EventStore
import app.markline.ui.theme.Green500
import app.markline.util.TimeUtil
import kotlinx.coroutines.delay

private sealed interface TimelineRow {
    val key: Any
    data class Header(val dateLabel: String, val isToday: Boolean) : TimelineRow {
        override val key get() = "header_$dateLabel"
    }
    data class Card(val event: Event) : TimelineRow {
        override val key get() = "event_${event.id}"
    }
}

private fun buildRows(events: List<Event>, today: String): List<TimelineRow> {
    val rows = mutableListOf<TimelineRow>()
    var lastDate: String? = null
    for (e in events) {
        val d = TimeUtil.formatDate(e.createdAt)
        if (d != lastDate) {
            rows.add(TimelineRow.Header(d, d == today))
            lastDate = d
        }
        rows.add(TimelineRow.Card(e))
    }
    return rows
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    eventStore: EventStore,
    onEventClick: (Long) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onImport: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val labelCounts by produceState<List<Pair<String, Int>>>(emptyList()) {
        value = eventStore.queryLabelCounts()
    }
    val totalCount by produceState(0) {
        value = eventStore.countAll()
    }

    val isFiltering = selectedLabel != null
    val isSearching = searchQuery.isNotBlank()
    val defaultMode = !isFiltering && !isSearching

    // ── 默认模式：分页懒加载（每页 30 条）──
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    val pageSize = 30

    // ── 主题过滤 / 搜索：一次性取匹配集（结果集通常较小，不强加分页）──
    val filteredEvents by produceState<List<Event>>(emptyList(), selectedLabel, searchQuery) {
        val label = selectedLabel ?: return@produceState
        if (isSearching) return@produceState
        value = eventStore.queryByLabel(label)
    }
    val searchResults by produceState<List<Event>>(emptyList(), searchQuery, selectedLabel) {
        if (searchQuery.isBlank()) {
            value = emptyList()
        } else {
            delay(300)
            val results = eventStore.search(searchQuery.trim())
            value = if (selectedLabel != null) results.filter { it.label == selectedLabel } else results
        }
    }

    // 进入默认模式（或过滤/搜索切换回默认）时重置并加载首屏
    LaunchedEffect(isFiltering, isSearching) {
        if (!isFiltering && !isSearching) {
            isLoading = true
            val page = eventStore.queryRecentPage(pageSize, 0)
            events = page
            offset = page.size
            hasMore = page.size == pageSize
            isLoading = false
        }
    }

    fun loadMore() {
        if (isLoading || !hasMore || !defaultMode) return
        isLoading = true
        val page = eventStore.queryRecentPage(pageSize, offset)
        events = events + page
        offset += page.size
        hasMore = page.size == pageSize
        isLoading = false
    }

    val gridState = rememberLazyStaggeredGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            defaultMode && total > 0 && last >= total - 4 && hasMore && !isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) loadMore()
    }

    val displayList = when {
        isSearching -> searchResults
        isFiltering -> filteredEvents
        else -> events
    }
    val today = TimeUtil.formatDate(System.currentTimeMillis())
    val rows = remember(displayList, today) { buildRows(displayList, today) }

    var showFullPhoto by remember { mutableStateOf<Uri?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Line",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // 筛选回显：顶栏小绿 chip，点它重开筛选弹层，× 清除
                        if (selectedLabel != null) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Green500.copy(alpha = 0.15f),
                                modifier = Modifier.clickable { showFilterSheet = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 8.dp, top = 3.dp, end = 5.dp, bottom = 3.dp)
                                ) {
                                    Text(
                                        selectedLabel ?: "",
                                        fontSize = 12.sp,
                                        color = Green500,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "清除主题筛选",
                                        tint = Green500,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { selectedLabel = null }
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(
                            Icons.Outlined.FilterList,
                            contentDescription = "主题筛选",
                            tint = if (selectedLabel != null) Green500
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onImport) {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "导入照片",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
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

            if (displayList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (defaultMode && isLoading) {
                        CircularProgressIndicator()
                    } else {
                        val emptyText = when {
                            isSearching && isFiltering -> "主题「${selectedLabel}」下未找到匹配「${searchQuery}」的记录"
                            isSearching -> "未找到匹配「${searchQuery}」的记录"
                            isFiltering -> "主题「${selectedLabel}」下暂无记录"
                            else -> "暂无签到记录，去首页点击 Mark 开始第一条"
                        }
                        Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(minSize = 150.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = rows,
                        key = { it.key },
                        span = {
                            if (it is TimelineRow.Header) StaggeredGridItemSpan.FullLine
                            else StaggeredGridItemSpan.SingleLane
                        }
                    ) { row ->
                        when (row) {
                            is TimelineRow.Header -> DateHeader(row)
                            is TimelineRow.Card -> TimelineCard(
                                event = row.event,
                                onClick = { onEventClick(row.event.id) },
                                onPhotoClick = if (!row.event.photoUri.isNullOrBlank())
                                    ({ showFullPhoto = Uri.parse(row.event.photoUri) }) else null
                            )
                        }
                    }

                    item(span = StaggeredGridItemSpan.FullLine, key = "footer") {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isLoading -> CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp), strokeWidth = 2.dp
                                )
                                defaultMode && !hasMore && events.isNotEmpty() ->
                                    Text("没有更多了", fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (showFullPhoto != null) {
            FullPhotoDialog(uri = showFullPhoto) { showFullPhoto = null }
        }

        // ── 主题筛选弹层（漏斗入口）──
        if (showFilterSheet) {
            ModalBottomSheet(
                onDismissRequest = { showFilterSheet = false },
                sheetState = sheetState
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "按主题筛选",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp)
                    )
                    FilterSheetRow(
                        label = "全部",
                        count = totalCount,
                        selected = selectedLabel == null,
                        onClick = {
                            selectedLabel = null
                            showFilterSheet = false
                        }
                    )
                    labelCounts.forEach { (label, count) ->
                        FilterSheetRow(
                            label = label,
                            count = count,
                            selected = selectedLabel == label,
                            onClick = {
                                // 再点一次当前主题 = 取消筛选
                                selectedLabel = if (selectedLabel == label) null else label
                                showFilterSheet = false
                            }
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun FilterSheetRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Green500 else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = Green500,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun DateHeader(row: TimelineRow.Header) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.dateLabel,
            color = if (row.isToday) Green500 else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = if (row.isToday) FontWeight.Bold else FontWeight.Medium
        )
        if (row.isToday) {
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Green500.copy(alpha = 0.15f)
            ) {
                Text(
                    "今天", fontSize = 11.sp, color = Green500,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TimelineCard(
    event: Event,
    onClick: () -> Unit,
    onPhotoClick: (() -> Unit)?
) {
    val hasPhoto = !event.photoUri.isNullOrBlank()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        if (hasPhoto) {
            StaggeredPhoto(uri = Uri.parse(event.photoUri), onClick = onPhotoClick)
        }
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = TimeUtil.formatTime(event.createdAt),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!event.address.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(event.address, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            if (event.audioDuration != null && event.audioDuration > 0) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Mic, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${event.audioDuration / 1000}秒",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 12.sp)
                }
            }

            if (!event.note.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(event.note, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            if (!event.label.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                ) {
                    Text(event.label, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1)
                }
            }
        }
    }
}

/**
 * 瀑布流照片块：按图片宽高比决定高度（120~240dp），形成错落效果。
 * 点击优先放大查看（onPhotoClick），卡片其余区域点击进详情。
 */
@Composable
private fun StaggeredPhoto(uri: Uri, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = loadBitmap(context, uri, 700)
    }
    val aspect = remember(bitmap) {
        bitmap?.let {
            (it.width.toFloat() / it.height.toFloat()).coerceIn(0.6f, 1.8f)
        } ?: 0.8f
    }
    BoxWithConstraints {
        val w = maxWidth
        val h = (w / aspect).coerceIn(120.dp, 240.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(h)
                .let { if (onClick != null) it.clickable { onClick() } else it }
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            if (onClick != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(3.dp)
                ) {
                    Icon(Icons.Outlined.ZoomIn, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
