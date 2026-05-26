package app.markline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─── 设计稿颜色规范 ───────────────────────────────
// 主色
val Green500    = Color(0xFF22C55E)   // 主色
val Green400    = Color(0xFF4ADE80)   // 悬停/浅绿
val Green700    = Color(0xFF15803D)   // 深绿/深色模式主色

// 辅助色
val Blue400     = Color(0xFF3B82F6)
val Amber400    = Color(0xFFF59E0B)
val Red400      = Color(0xFFEF4444)

// 文字色阶
val Gray900     = Color(0xFF1F2937)   // 主文字
val Gray500     = Color(0xFF6B7280)   // 次级文字
val Gray400     = Color(0xFF9CA3AF)   // 占位/禁用文字
val Gray100     = Color(0xFFE5E7EB)   // 分割线

// 背景
val White       = Color(0xFFFFFFFF)
val Gray50      = Color(0xFFF8FAFC)

// 绿色点（时间轴）
val TimelineDot = Color(0xFF22C55E)

private val LightColorScheme = lightColorScheme(
    primary          = Green500,
    onPrimary        = White,
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Green700,

    secondary        = Blue400,
    tertiary         = Amber400,
    error            = Red400,

    background       = White,
    onBackground     = Gray900,
    surface          = White,
    onSurface        = Gray900,
    surfaceVariant   = Gray50,
    onSurfaceVariant = Gray500,
    outline          = Gray100,
    outlineVariant   = Gray100,
)

private val DarkColorScheme = darkColorScheme(
    primary          = Green400,
    onPrimary        = Color(0xFF003910),
    primaryContainer = Green700,
    onPrimaryContainer = Color(0xFFB7F5C8),

    background       = Color(0xFF111827),
    onBackground     = Color(0xFFF9FAFB),
    surface          = Color(0xFF1F2937),
    onSurface        = Color(0xFFF3F4F6),
    surfaceVariant   = Color(0xFF374151),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline          = Color(0xFF374151),
)

@Composable
fun MarkLineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}
