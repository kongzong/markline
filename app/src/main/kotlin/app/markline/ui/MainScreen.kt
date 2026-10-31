package app.markline.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.markline.domain.CheckinService
import app.markline.ui.theme.Gray400
import app.markline.ui.theme.Gray500
import app.markline.ui.theme.Gray50
import app.markline.ui.theme.Green500
import app.markline.util.SettingsStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    checkinService: CheckinService,
    settingsStore: SettingsStore,
    triggerCheckin: Boolean = false,
    onTriggerCheckinConsumed: () -> Unit = {},
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDetail: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checkinStatus by checkinService.status.collectAsState()

    val isProcessing = checkinStatus.isProcessing
    
    // 实时获取设置中的录音时长
    val configuredDuration = settingsStore.audioDurationSec

    // 按钮脉冲动画
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // 点击动画
    val clickScale = remember { Animatable(1f) }

    // 权限申请
    val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    )

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            triggerCheckin(context, checkinService, settingsStore, scope, clickScale)
        }
    }

    // Widget 触发的签到：如果已有权限直接签到，否则走权限申请流程
    LaunchedEffect(triggerCheckin) {
        if (!triggerCheckin || isProcessing) return@LaunchedEffect
        // 消费标记：重置 MainActivity 中的 flag，防止切 Tab 后重复触发
        onTriggerCheckinConsumed()
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            triggerCheckin(context, checkinService, settingsStore, scope, clickScale)
        } else {
            launcher.launch(permissions)
        }
    }

    // ── 拍照签到：相机 ──
    val pendingCapture = remember { mutableStateOf<Pair<Uri, File>?>(null) }

    val cameraResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val (uri, file) = pendingCapture.value ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK && file.exists() && file.length() > 0) {
            scope.launch {
                try {
                    val id = checkinService.photoCheckin(context, uri, file)
                    onNavigateToDetail(id)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            // 取消或失败：清理空文件
            file.delete()
        }
        pendingCapture.value = null
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraCapture(context, pendingCapture, cameraResultLauncher)
    }

    fun requestPhotoCheckin() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraCapture(context, pendingCapture, cameraResultLauncher)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "点点mark，汇聚成line",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Outlined.Settings, 
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
                if (!isProcessing) {
                    Box(modifier = Modifier.size(220.dp).scale(pulseScale).background(Green500.copy(alpha = 0.08f), CircleShape))
                    Box(modifier = Modifier.size(190.dp).scale(pulseScale * 0.96f).background(Green500.copy(alpha = 0.14f), CircleShape))
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(160.dp)
                        .scale(if (isProcessing) 0.95f else clickScale.value)
                        .background(if (isProcessing) MaterialTheme.colorScheme.surfaceVariant else Green500, CircleShape)
                        .clickable(
                            enabled = !isProcessing,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            val missing = permissions.filter {
                                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (missing.isEmpty()) {
                                triggerCheckin(context, checkinService, settingsStore, scope, clickScale)
                            } else {
                                launcher.launch(permissions)
                            }
                        }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = if (isProcessing) "..." else "✓", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                        val textColor = if (isProcessing) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
                        Text(text = if (isProcessing) "处理中" else "Mark一下", color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isProcessing) "正在补全增强信息..." else "将自动记录时间、位置和录音",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusChip(
                    icon = Icons.Outlined.LocationOn,
                    label = checkinStatus.locationStatus,
                    isActive = checkinStatus.locationStatus == "定位中"
                )
                StatusChip(
                    icon = Icons.Outlined.Mic,
                    label = checkinStatus.audioStatus,
                    isActive = checkinStatus.audioStatus.startsWith("录音中")
                )
                
                if (checkinStatus.canCompleteAudio) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { checkinService.completeAudioEarly(context) },
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Green500)
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = "完成录音", tint = Color.White)
                        }
                        Text(text = "结束录音", style = MaterialTheme.typography.bodySmall, color = Green500)
                    }
                } else {
                    StatusChip(
                        icon = Icons.Outlined.Timer,
                        label = if (checkinStatus.countdown > 0) "${checkinStatus.countdown}s" else "${configuredDuration}s",
                        isActive = checkinStatus.countdown > 0
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 次级入口：拍照签到（置于状态行下方，与主按钮/状态构成一个整体）
            OutlinedButton(
                onClick = { requestPhotoCheckin() },
                enabled = !isProcessing,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 64.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Green500)
                ),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Green500)
            ) {
                Icon(Icons.Outlined.Photo, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("拍照签到", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 启动系统相机拍摄，并将照片直接存入 App 私有目录 files/photos。
 * 使用 FileProvider 暴露 Uri，并显式授予相机写权限。
 */
private fun startCameraCapture(
    context: Context,
    pendingCapture: androidx.compose.runtime.MutableState<Pair<Uri, File>?>,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val photosDir = File(context.filesDir, "photos").apply { if (!exists()) mkdirs() }
    val file = File(photosDir, "mark_${UUID.randomUUID()}.jpg")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    pendingCapture.value = uri to file
    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT, uri)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    launcher.launch(intent)
}

private fun triggerCheckin(
    context: Context,
    service: CheckinService,
    settingsStore: SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope,
    clickScale: Animatable<Float, AnimationVector1D>
) {
    // 只有开启震动设置时才震动
    if (settingsStore.vibrateEnabled) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    scope.launch {
        clickScale.animateTo(0.9f, tween(100))
        clickScale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy))
    }

    service.checkin(context)
}

@Composable
private fun StatusChip(icon: ImageVector, label: String, isActive: Boolean = false) {
    val color = if (isActive) Green500 else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).background(if (isActive) Green500.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        }
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
