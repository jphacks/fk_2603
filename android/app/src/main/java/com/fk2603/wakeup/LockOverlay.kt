package com.fk2603.wakeup

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** ベッドにいる間（とアラーム中）は他のアプリを操作できないようにする全画面オーバーレイ */
class LockOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private lateinit var detail: TextView

    fun show(status: M5Status?) {
        if (root == null) attach()
        val text = when {
            status == null -> ""
            status.state == "RINGING" -> "アラーム鳴動中\nベッドから出てください"
            status.state == "RESLEEP_ARMED" -> "二度寝を検出しました\nベッドから出ないとアラームが鳴ります"
            status.inBed -> "ベッドから出ると解除されます"
            else -> "起床を確認中…"
        }
        if (detail.text.toString() != text) detail.text = text
    }

    fun hide() {
        root?.let { wm.removeView(it) }
        root = null
    }

    private fun attach() {
        val title = TextView(context).apply {
            text = "ベッドの上では\nスマホは使えません"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        detail = TextView(context).apply {
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(20, 20, 40))
            addView(title)
            addView(detail)
            // タッチをすべて吸収して下のアプリに渡さない
            setOnTouchListener { _, _ -> true }
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
    }
}
