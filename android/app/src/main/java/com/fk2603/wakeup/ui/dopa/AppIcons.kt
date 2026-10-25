package com.fk2603.wakeup.ui.dopa

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import com.fk2603.wakeup.ui.theme.Dopa

/** 端末に入っているアプリの名前とアイコン（アンインストール済みなら icon = null） */
class AppInfo(val label: String, val icon: ImageBitmap?)

@Composable
fun rememberAppInfo(pkg: String, fallbackLabel: String = pkg): AppInfo {
    val pm = LocalContext.current.packageManager
    return remember(pkg) {
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        AppInfo(
            label = info?.loadLabel(pm)?.toString() ?: fallbackLabel,
            icon = info?.let { runCatching { it.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull() },
        )
    }
}

@Composable
fun AppIcon(app: AppInfo, size: Dp, dimmed: Boolean = false) {
    val icon = app.icon
    if (icon != null) {
        Image(icon, app.label, Modifier.size(size).alpha(if (dimmed) 0.4f else 1f))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(Dopa.Surface2))
    }
}

private const val ICON_PX = 96
