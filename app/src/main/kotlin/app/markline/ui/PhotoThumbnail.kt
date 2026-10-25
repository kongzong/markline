package app.markline.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从 content Uri 加载照片位图（共享加载逻辑）。
 * 优先用系统缩略图（loadThumbnail，速度快、内存友好）；
 * 部分内容提供方不支持时回退到直接解码原流（采样降级，避免大图 OOM）。
 */
suspend fun loadBitmap(context: Context, uri: Uri, targetPx: Int): Bitmap? {
    return withContext(Dispatchers.IO) {
        var bmp: Bitmap? = null
        // 首选：系统缩略图
        try {
            bmp = context.contentResolver.loadThumbnail(uri, Size(targetPx, targetPx), null)
        } catch (_: Exception) {
            bmp = null
        }
        // 回退：直接解码原始流（采样降级）
        if (bmp == null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = if (targetPx >= 1000) 2 else 4 }
                    bmp = BitmapFactory.decodeStream(stream, null, opts)
                }
            } catch (_: Exception) {
                bmp = null
            }
        }
        bmp
    }
}

/**
 * 照片缩略图。可选 onClick 用于点击放大查看。
 */
@Composable
fun PhotoThumbnail(
    uri: Uri,
    size: Dp = 56.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = loadBitmap(context, uri, (size.value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1))
    }

    val boxModifier = modifier
        .size(size)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .let { if (onClick != null) it.clickable { onClick() } else it }

    Box(modifier = boxModifier) {
        bitmap?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size))
        }
        if (onClick != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    .padding(2.dp)
            ) {
                Icon(Icons.Outlined.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * 全屏查看照片对话框。点击空白处或关闭按钮退出。
 */
@Composable
fun FullPhotoDialog(uri: Uri?, onDismiss: () -> Unit) {
    if (uri == null) return
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onDismiss() }
        ) {
            val bitmap by produceState<Bitmap?>(null, uri) {
                value = loadBitmap(context, uri, 1280)
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}
