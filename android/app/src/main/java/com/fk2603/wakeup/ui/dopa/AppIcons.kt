package com.fk2603.wakeup.ui.dopa

import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import com.fk2603.wakeup.ui.theme.Dopa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 端末に入っているアプリの名前とアイコン（アンインストール済み・読み込み中なら icon = null） */
class AppInfo(val label: String, val icon: ImageBitmap?)

// アイコンの読み込み（画像の展開）は重いので、一度読んだものは覚えておく
private val cache = LruCache<String, AppInfo>(256)

/**
 * アプリの名前とアイコン。読み込みは裏で行い、終わるまでは fallbackLabel と空のアイコンを返す
 * （一覧を開いたときに、全アプリのアイコンを画面の処理の中で読んで固まらないように）
 */
@Composable
fun rememberAppInfo(pkg: String, fallbackLabel: String = pkg): AppInfo {
    val pm = LocalContext.current.packageManager
    val info by produceState(cache.get(pkg) ?: AppInfo(fallbackLabel, null), pkg) {
        if (cache.get(pkg) == null) value = withContext(Dispatchers.IO) { load(pm, pkg, fallbackLabel) }
    }
    return info
}

private fun load(pm: PackageManager, pkg: String, fallbackLabel: String): AppInfo {
    val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
    return AppInfo(
        label = info?.loadLabel(pm)?.toString() ?: fallbackLabel,
        icon = info?.let { runCatching { it.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull() },
    ).also { cache.put(pkg, it) }
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
