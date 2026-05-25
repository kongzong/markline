package com.example.markline.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
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
import com.example.markline.domain.CheckinService
import com.example.markline.ui.theme.Gray400
import com.example.markline.ui.theme.Gray500
import com.example.markline.ui.theme.Gray50
import com.example.markline.ui.theme.Green500
import com.example.markline.util.SettingsStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    checkinService: CheckinService,
    settingsStore: SettingsStore,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "MarkLine", 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "mark一下，汇聚成line", 
                            fontSize = 11.sp, 
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
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
                        Text(text = if (isProcessing) "处理中" else "Mark一下", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isProcessing) "正在补全增强信息..." else "将自动记录时间、位置和录音",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(40.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
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
                            onClick = { checkinService.completeAudioEarly() },
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Green500)
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = "完成录音", tint = Color.White)
                        }
                        Text(text = "完成录音", style = MaterialTheme.typography.bodySmall, color = Green500)
                    }
                } else {
                    StatusChip(
                        icon = Icons.Outlined.Timer,
                        label = if (checkinStatus.countdown > 0) "${checkinStatus.countdown}s" else "${configuredDuration}s",
                        isActive = checkinStatus.countdown > 0
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
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
