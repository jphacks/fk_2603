package com.fk2603.wakeup

import android.content.Context
import android.graphics.PixelFormat
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.fk2603.wakeup.ui.dopa.AlarmScreen
import com.fk2603.wakeup.ui.dopa.LockScreen
import com.fk2603.wakeup.ui.dopa.LockUi
import com.fk2603.wakeup.ui.theme.DopaTheme
import kotlinx.coroutines.delay

/**
 * ロック中に他のアプリの上に出す全画面オーバーレイ（Compose で描く）
 * - アラーム中: AlarmScreen
 * - ベッドにいる間: LockScreen（onHome があれば「ホームに戻る」を出す）
 * どちらにも「緊急解除」ボタン（2回押し）を出し、押されたら onEmergency を呼ぶ
 */
class LockOverlay(private val context: Context, private val onEmergency: () -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var ui by mutableStateOf<LockUi?>(null)
    private var onHome by mutableStateOf<(() -> Unit)?>(null)

    fun show(state: LockUi, home: (() -> Unit)?) {
        ui = state
        onHome = home
        if (root == null) attach()
    }

    fun hide() {
        root?.let { wm.removeView(it) }
        owner?.destroy()
        root = null
        owner = null
    }

    private fun attach() {
        val o = OverlayOwner()
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                DopaTheme {
                    val nowSec by produceState(System.currentTimeMillis() / 1000) {
                        while (true) {
                            delay(1000)
                            value = System.currentTimeMillis() / 1000
                        }
                    }
                    val s = ui ?: return@DopaTheme
                    if (s.alarm) AlarmScreen(s, nowSec, onEmergency = onEmergency)
                    else LockScreen(s, onHome = onHome, onEmergency = onEmergency)
                }
            }
        }
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE,
        )
        wm.addView(view, params)
        root = view
        owner = o
    }

    /** Activity の外で Compose を動かすための Lifecycle / SavedState */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        init {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
