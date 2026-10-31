package app.markline.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 宽屏阈值：折叠屏内屏展开 / 平板横屏约 ≥600dp（Material 窗口宽度 Compact/Medium 分界） */
const val WIDE_SCREEN_THRESHOLD_DP = 600

@Composable
fun isWideScreen(): Boolean =
    LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_THRESHOLD_DP

/**
 * 宽屏限宽容器：宽屏（折叠屏内屏/平板）下内容限宽并水平居中，
 * 避免表单、按钮、分隔线在 600dp+ 宽度上无脑铺满；窄屏行为不变。
 */
@Composable
fun AdaptiveScreen(maxWidth: Dp = 640.dp, content: @Composable () -> Unit) {
    val wide = isWideScreen()
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = if (wide) Alignment.TopCenter else Alignment.TopStart
    ) {
        Box(Modifier.widthIn(max = if (wide) maxWidth else Dp.Unspecified)) {
            content()
        }
    }
}
