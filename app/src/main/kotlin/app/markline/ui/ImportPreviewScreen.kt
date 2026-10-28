package app.markline.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.markline.domain.EventStore
import app.markline.ui.theme.Green500
import app.markline.util.ExifImporter
import app.markline.util.TimeUtil
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImportPreviewScreen(
    eventStore: EventStore,
    onBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var label by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExifImporter.ImportResult?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) selectedUris = uris
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                title = {
                    Text("导入照片", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                },
                actions = {
                    if (selectedUris.isNotEmpty() && !importing) {
                        IconButton(onClick = {
                            importing = true
                            scope.launch {
                                val app = context.applicationContext as app.markline.MarkLineApp
                                val r = ExifImporter.importPhotos(
                                    context, eventStore, selectedUris,
                                    label.ifBlank { null },
                                    app.locationService
                                )
                                importing = false
                                result = r
                            }
                        }) {
                            Icon(Icons.Outlined.Check, contentDescription = "确认导入", tint = Green500)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (selectedUris.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "从相册选择照片，自动按拍摄时间/地点生成记录",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { picker.launch(arrayOf("image/*")) }) {
                            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("选择照片")
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                    placeholder = { Text("统一主题标签（可选）") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Label, null) },
                    shape = RoundedCornerShape(12.dp)
                )

                // ── 已有主题点选（无需手打）──
                val existingLabels by produceState<List<String>>(emptyList()) {
                    value = eventStore.queryLabels()
                }
                if (existingLabels.isNotEmpty()) {
                    Text(
                        "选择已有主题：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        existingLabels.forEach { lbl ->
                            val selected = label == lbl
                            SuggestionChip(
                                onClick = { label = if (selected) "" else lbl },
                                label = { Text(lbl, fontSize = 13.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (selected) Green500.copy(alpha = 0.25f)
                                    else Green500.copy(alpha = 0.1f)
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    borderColor = if (selected) Green500
                                    else Green500.copy(alpha = 0.3f),
                                    enabled = true
                                )
                            )
                        }
                    }
                }

                if (importing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Button(
                            onClick = { picker.launch(arrayOf("image/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("继续添加（已选 ${selectedUris.size} 张）")
                        }
                    }
                    items(selectedUris) { uri ->
                        ImportItem(uri = uri)
                    }
                }
            }
        }
    }

    val r = result
    if (r != null) {
        AlertDialog(
            onDismissRequest = { /* 必须二选一，不允许空白关闭 */ },
            title = { Text("导入完成") },
            text = {
                Text(
                    "成功 ${r.imported} 张" +
                        (if (r.skipped > 0) "，跳过 ${r.skipped} 张（已导入）" else "") +
                        (if (r.failed > 0) "，失败 ${r.failed} 张" else "")
                )
            },
            confirmButton = {
                // 进入刚导入的记录详情，方便补充备注 / 地址 / 主题
                if (r.ids.isNotEmpty()) {
                    TextButton(onClick = { onNavigateToDetail(r.ids.first()) }) {
                        Text("查看记录")
                    }
                } else {
                    TextButton(onClick = onBack) { Text("完成") }
                }
            },
            dismissButton = {
                if (r.ids.isNotEmpty()) {
                    TextButton(onClick = onBack) { Text("返回列表") }
                }
            }
        )
    }
}

@Composable
private fun ImportItem(uri: Uri) {
    val context = LocalContext.current
    val meta by produceState<ExifImporter.PhotoMeta?>(null, uri) {
        value = ExifImporter.parsePhotoMeta(context, uri)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PhotoThumbnail(uri = uri, size = 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    meta?.let { TimeUtil.formatDateTime(it.time) } ?: "解析中…",
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (meta?.latitude != null) "已读取定位" else "无定位信息",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
