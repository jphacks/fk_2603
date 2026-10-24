package com.fk2603.wakeup

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ベッドにいる間（とアラーム中）は他のアプリを操作できないようにする全画面オーバーレイ。
 * 下の「緊急解除」だけは押せる（2回押しで解除。連続記録が 0 に戻ることを見せて思いとどまらせる）
 */
class LockOverlay(private val context: Context, private val onEmergency: () -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private lateinit var detail: TextView
    private lateinit var streakText: TextView
    private lateinit var emergencyButton: Button
    private var armedAtMs = 0L
    private var streak = 0

    private val emergencyLabel = "緊急解除（${Config.EMERGENCY_UNLOCK_SEC / 60}分）"

    fun show(status: M5Status?, streak: Int) {
        if (root == null) attach()
        this.streak = streak
        val text = when {
            status == null -> ""
            status.state == "RINGING" -> "アラーム鳴動中\nベッドから出てください"
            status.state == "RESLEEP_ARMED" -> "二度寝を検出しました\nベッドから出ないとアラームが鳴ります"
            status.inBed -> "ベッドから出ると解除されます"
            else -> "起床を確認中…"
        }
        if (detail.text.toString() != text) detail.text = text
        val s = if (streak > 0) "🔥 緊急解除なし ${streak}日連続中" else "🔥 連続記録 0日 → 解除せずに朝を迎えれば積み上がります"
        if (streakText.text.toString() != s) streakText.text = s
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        armedAtMs = 0
        root?.let { wm.removeView(it) }
        root = null
    }

    private fun onEmergencyClick() {
        val now = System.currentTimeMillis()
        if (armedAtMs > 0 && now - armedAtMs < Config.EMERGENCY_CONFIRM_MS) {
            armedAtMs = 0
            onEmergency()
            return
        }
        armedAtMs = now
        val warning = if (streak > 0) "（${streak}日の連続記録が0に戻ります）" else "（記録に残ります）"
        emergencyButton.text = "もう一度押すと${Config.EMERGENCY_UNLOCK_SEC / 60}分間解除\n$warning"
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            armedAtMs = 0
            emergencyButton.text = emergencyLabel
        }, Config.EMERGENCY_CONFIRM_MS)
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
        streakText = TextView(context).apply {
            textSize = 20f
            setTextColor(Color.rgb(255, 170, 60))
            gravity = Gravity.CENTER
            setPadding(0, 64, 0, 0)
        }
        emergencyButton = Button(context).apply {
            text = emergencyLabel
            isAllCaps = false
            setOnClickListener { onEmergencyClick() }
        }
        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 96 }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(20, 20, 40))
            addView(title)
            addView(detail)
            addView(streakText)
            addView(emergencyButton, buttonParams)
            // ボタン以外へのタッチはすべて吸収して下のアプリに渡さない
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
