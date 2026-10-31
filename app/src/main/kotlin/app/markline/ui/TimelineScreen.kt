package app.markline.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.markline.domain.Event
import app.markline.system.SyncWorker
import app.markline.domain.EventStore
import app.markline.storage.SQLiteEventStore
import app.markline.ui.theme.Green500
import app.markline.util.SharePosterGenerator
import app.markline.util.TimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface TimelineRow {
    val key: Any
    data class Card(val event: Event) : TimelineRow {
        override val key get() = "event_${event.id}"
    }
}

// 2026-10-31 去掉日期分组标题：卡片本身含完整时间，列表更紧凑（用户要求）
private fun buildRows(events: List<Event>): List<TimelineRow> =
    events.map { TimelineRow.Card(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    eventStore: EventStore,
    onEventClick: (Long) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onImport: () -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // 进入 Line 时补偿同步：距上次同步超过 5 分钟且云同步已启用才触发，不密集打网络
    val syncContext = LocalContext.current
    LaunchedEffect(Unit) {
        val app = syncContext.applicationContext as? app.markline.MarkLineApp ?: return@LaunchedEffect
        val settings = app.settingsStore
        if (settings.syncEnabled &&
            System.currentTimeMillis() - settings.lastSyncAt > 5 * 60_000L
        ) {
            SyncWorker.syncNow(syncContext)
        }
    }

    // ── 响应式刷新：存储层任何增删改（含后台同步下载新记录）都会 bump 全局数据版本，
    //    Line 界面据此自动重载，无需手动返回重进 ──
    val dataVersion by SQLiteEventStore.dataVersion.collectAsState()

    val labelCounts by produceState<List<Pair<String, Int>>>(emptyList(), dataVersion) {
        value = eventStore.queryLabelCounts()
    }
    val totalCount by produceState(0, dataVersion) {
        value = eventStore.countAll()
    }

    val isFiltering = selectedLabel != null
    val isSearching = searchQuery.isNotBlank()
    val defaultMode = !isFiltering && !isSearching

    // ── 默认模式：分页懒加载（每页 30 条）──
    // events 与版本标记用普通 remember：切走/重建后丢失 → 必然触发重载，不会出现空列表假象
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    // 可保存状态：筛选词/加载深度在折叠展开、页面切换后不丢
    var offset by rememberSaveable { mutableStateOf(0) }
    var hasMore by rememberSaveable { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    val pageSize = 30
    // 已应用的数据版本号：dataVersion 未变时不重复重载（也避免与下拉刷新双重加载）。
    // ⚠️ 必须与 events 同为普通 remember：切走 Line（Navigation saveState）或重建后 events 必然丢失，
    //    appliedVersion 若被恢复保存值，会与「restoreState 恢复的旧值」相等而跳过重载 → 空列表假象。
    var appliedVersion by remember { mutableStateOf(-1L) }
    // 首次组合标记：与本页数据态同生命周期，重建/切回后必须至少重载一次（按保存的 offset 恢复已加载深度）
    var initialized by remember { mutableStateOf(false) }

    val gridState = rememberLazyStaggeredGridState()
    // 待恢复的滚动位置（首个可见项 key + 偏移）：响应式重载后跳回原位，不回顶
    var pendingScroll by remember { mutableStateOf<Pair<Any, Int>?>(null) }

    fun reloadFirstPage() {
        // 保留已加载页数（深滚动时不截断回 30 条），并记录首个可见项用于恢复滚动位置
        val keep = maxOf(offset, pageSize)
        val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()
        pendingScroll = if (first != null && first.index > 0) first.key to -first.offset.y else null
        isLoading = true
        val page = eventStore.queryRecentPage(keep, 0)
        events = page
        offset = page.size
        hasMore = page.size == keep
        isLoading = false
        appliedVersion = dataVersion
    }

    // ── 主题过滤 / 搜索：一次性取匹配集（结果集通常较小，不强加分页）；数据版本变化时自动重查 ──
    val filteredEvents by produceState<List<Event>>(emptyList(), selectedLabel, searchQuery, dataVersion) {
        val label = selectedLabel ?: return@produceState
        if (isSearching) return@produceState
        value = eventStore.queryByLabel(label)
    }
    val searchResults by produceState<List<Event>>(emptyList(), searchQuery, selectedLabel, dataVersion) {
        if (searchQuery.isBlank()) {
            value = emptyList()
        } else {
            delay(300)
            val results = eventStore.search(searchQuery.trim())
            value = if (selectedLabel != null) results.filter { it.label == selectedLabel } else results
        }
    }

    // 进入默认模式或数据版本变化（首次进入/折叠屏重建/后台同步下载/其他页面增删改）时重载首屏
    LaunchedEffect(dataVersion, isFiltering, isSearching) {
        if (!isFiltering && !isSearching && (!initialized || dataVersion != appliedVersion)) {
            reloadFirstPage()
            initialized = true
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

    // ── 下拉刷新：重载本地首屏；云同步开启时顺带触发一次后台同步，
    //    同步下载完成后 dataVersion bump 会再次自动刷新 ──
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    // ── 主题分享长图：筛选激活后顶栏出现分享入口，数据 = 当前筛选结果 ──
    var posterBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showPosterDialog by remember { mutableStateOf(false) }
    var generatingPoster by remember { mutableStateOf(false) }

    fun generatePoster() {
        val label = selectedLabel ?: return
        if (generatingPoster) return
        val events = filteredEvents
        if (events.isEmpty()) return
        generatingPoster = true
        scope.launch(Dispatchers.Default) {
            try {
                val sources = events.sortedByDescending { it.createdAt }
                    .map { ev -> SharePosterGenerator.Source(ev, eventStore.getEventPhotos(ev.id).map { p -> p.photoUri }) }
                val bmp = SharePosterGenerator.generate(syncContext, label, sources)
                withContext(Dispatchers.Main) {
                    posterBitmap = bmp
                    showPosterDialog = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(syncContext, "生成失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) { generatingPoster = false }
            }
        }
    }
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
    val rows = remember(displayList) { buildRows(displayList) }

    // 响应式重载后恢复滚动位置：按 key 找回原可见项并瞬时跳转（找不到则保持自然位置，如被删项）
    LaunchedEffect(rows, pendingScroll) {
        val target = pendingScroll ?: return@LaunchedEffect
        pendingScroll = null
        val idx = rows.indexOfFirst { it.key == target.first }
        if (idx >= 0) gridState.scrollToItem(idx, target.second)
    }

    var showFullPhoto by remember { mutableStateOf<Uri?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // fillMaxWidth：双击区铺满标题槽位（到右侧图标前），否则只有「Line」字符可点
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                            // 双击顶栏标题：平滑滚回列表顶部（Material 常规交互）
                            detectTapGestures(
                                onDoubleTap = {
                                    if (displayList.isNotEmpty()) {
                                        scope.launch { gridState.animateScrollToItem(0) }
                                    }
                                }
                            )
                        }
                    ) {
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
                    if (selectedLabel != null) {
                        IconButton(onClick = { generatePoster() }, enabled = !generatingPoster) {
                            if (generatingPoster) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Share, contentDescription = "生成分享长图",
                                    tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
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

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    scope.launch {
                        try {
                            // 最小展示 300ms：reloadFirstPage 是同步快查，若同帧内
                            // true→false，指示器进出场状态机被跳过会卡住不消失
                            delay(300)
                            val app = syncContext.applicationContext as? app.markline.MarkLineApp
                            if (app?.settingsStore?.syncEnabled == true) {
                                SyncWorker.syncNow(syncContext)
                            }
                            reloadFirstPage()
                        } finally {
                            // 兜底：同步/查询抛异常时也必须收回指示器
                            isRefreshing = false
                        }
                    }
                }
            ) {
                if (displayList.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
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
                    // 全屏宽统一单列（宽屏双列实测杂乱，2026-10-31 回归单列，
                    // 改用卡片内「左图右文」横排提升宽屏信息密度）
                    columns = StaggeredGridCells.Fixed(1),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = rows,
                        key = { it.key }
                    ) { row ->
                        when (row) {
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
        }

        if (showFullPhoto != null) {
            FullPhotoDialog(uri = showFullPhoto) { showFullPhoto = null }
        }

        // ── 分享长图：预览 + 分享/保存到相册 ──
        if (showPosterDialog) {
            val bmp = posterBitmap
            if (bmp != null) {
                AlertDialog(
                    onDismissRequest = { showPosterDialog = false; posterBitmap = null },
                    title = { Text("分享长图已生成") },
                    text = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(360.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "长图预览",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            SharePosterGenerator.shareAsImage(syncContext, bmp)
                            showPosterDialog = false
                            posterBitmap = null
                        }) { Text("分享") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                val ok = SharePosterGenerator.saveToAlbum(syncContext, bmp)
                                Toast.makeText(
                                    syncContext,
                                    if (ok) "已保存到相册 Pictures/MarkLine" else "保存失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }) { Text("保存到相册") }
                            TextButton(onClick = { showPosterDialog = false; posterBitmap = null }) {
                                Text("关闭")
                            }
                        }
                    }
                )
            }
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
private fun TimelineCard(
    event: Event,
    onClick: () -> Unit,
    onPhotoClick: (() -> Unit)?
) {
    val hasPhoto = !event.photoUri.isNullOrBlank()
    // 宽屏（折叠屏内屏展开/平板）：左文右图横排，图宽 1/3；窄屏：上文下图竖排。
    // 宽屏文字列均从最左开始，有图/无图天然对齐，无需占位块。
    val wide = isWideScreen()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        // 方案 B：白底+1dp 描边区分卡片边界（方案 A 为浅灰底色块对比）
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        if (wide) {
            Row {
                Column(modifier = Modifier.weight(1f).padding(10.dp)) {
                    CardTexts(event)
                }
                if (hasPhoto) {
                    // 照片与卡片边缘留 10dp 间隔（与文字区一致），不贴合
                    Box(
                        Modifier.padding(top = 10.dp, end = 10.dp, bottom = 10.dp)
                    ) {
                        WideCardPhoto(uri = Uri.parse(event.photoUri), onClick = onPhotoClick)
                    }
                }
            }
        } else {
            Column(modifier = Modifier.padding(10.dp)) {
                CardTexts(event)
            }
            if (hasPhoto) {
                // 照片不贴边：左右下 10dp（顶部由文字区 padding 提供间隔）
                Box(
                    Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                ) {
                    StaggeredPhoto(uri = Uri.parse(event.photoUri), onClick = onPhotoClick)
                }
            }
        }
    }
}

/** 卡片文字区：时间+日期/地点/音频/备注/主题标签（宽窄屏共用；日期随卡片显示） */
@Composable
private fun CardTexts(event: Event) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = TimeUtil.formatTime(event.createdAt),
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alignByBaseline()
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = TimeUtil.formatCardDate(event.createdAt),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
            modifier = Modifier.alignByBaseline()
        )
    }

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

/**
 * 宽屏横排卡片的右侧图片块：宽度 = 卡片宽 1/3，高度按图片宽高比自适应（100~180dp），
 * 裁切填充。点击放大查看（onPhotoClick），与竖排 StaggeredPhoto 行为一致。
 */
@Composable
private fun WideCardPhoto(uri: Uri, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = loadBitmap(context, uri, 700)
    }
    val aspect = remember(bitmap) {
        bitmap?.let {
            (it.width.toFloat() / it.height.toFloat()).coerceIn(0.6f, 1.8f)
        } ?: 0.8f
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth(1f / 3f)
            .let { if (onClick != null) it.clickable { onClick() } else it }
    ) {
        val h = (maxWidth / aspect).coerceIn(100.dp, 180.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(h)
                .clip(RoundedCornerShape(10.dp))
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
                .clip(RoundedCornerShape(10.dp))
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
