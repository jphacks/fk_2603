package com.fk2603.wakeup.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.fk2603.wakeup.R

/** ドパドパクラッシャーの色（UI プロトタイプ globals.css と同じ） */
object Dopa {
    val Background = Color(0xFF0A0A0A)
    val Foreground = Color(0xFFF5F5F2)
    val Surface = Color(0xFF141414)
    val Surface2 = Color(0xFF1E1E1E)
    val Lime = Color(0xFFC6FF00)
    val LimeForeground = Color(0xFF0A0A0A)
    val LimeShadow = Color(0xFF6F8F00)
    val Alert = Color(0xFFFF4D2E)
    val Muted = Color(0xFF8B8B86)
    val Border = Color(0x17FFFFFF)

    /** 見出し・数字用（Dela Gothic One） */
    val Display = FontFamily(Font(R.font.dela_gothic_one))
}

@Composable
fun DopaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Dopa.Lime,
            onPrimary = Dopa.LimeForeground,
            secondary = Dopa.Surface2,
            onSecondary = Dopa.Foreground,
            background = Dopa.Background,
            onBackground = Dopa.Foreground,
            surface = Dopa.Surface,
            onSurface = Dopa.Foreground,
            surfaceVariant = Dopa.Surface2,
            onSurfaceVariant = Dopa.Muted,
            surfaceContainerHigh = Dopa.Surface,
            outline = Dopa.Border,
            error = Dopa.Alert,
        ),
    ) {
        // 色を指定していない文字・アイコンの既定は黒なので、黒背景で見えるよう白にする
        CompositionLocalProvider(LocalContentColor provides Dopa.Foreground, content = content)
    }
}
