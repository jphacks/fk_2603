package com.fk2603.wakeup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fk2603.wakeup.ui.dopa.LockUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** サービスと画面で共有する M5Stack の最新状態 */
object StatusHolder {
    val status = MutableStateFlow<M5Status?>(null)
    val error = MutableStateFlow<String?>(null)
}

/**
 * M5Stack と BLE でつながり（M5Link）、
 * - locked の間は全画面オーバーレイでスマホを使えなくする
 * - 新しい起床記録を Firestore に送る
 * - 睡眠のイベントを受け取り、端末内の睡眠記録（SleepStore）に反映する
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var overlay: LockOverlay

    private var uploading = false
    private var nextUploadRetryMs = 0L
    private var uploadBackoffMs = UPLOAD_RETRY_MIN_MS
    private var acking = false
    private lateinit var blocker: AppBlocker
    private var inBedSinceMs = 0L

    // 画面ロックを解除した瞬間にロック画面を出す（1秒ごとの判定を待たない）
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = evaluateLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = LockOverlay(this)
        blocker = AppBlocker(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        M5Link.start(this)
        scope.launch { lockLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun lockLoop() {
        while (scope.isActive) {
            val s = StatusHolder.status.value
            s?.lastResult?.let { uploadIfNew(it) }
            s?.sleepEvent?.let { receiveSleepEvent(it) }
            // 届いたばかりの値だけをグラフに残す（切断中に古い値を足さない）
            if (s != null && System.currentTimeMillis() - M5Link.lastStatusMs < PRESSURE_FRESH_MS) {
                PressureLog.add(this, s.pressureRaw)
            }
            evaluateLock()
            delay(LOCK_CHECK_INTERVAL_MS)
        }
    }

    /**
     * - アラーム中（M5Stack と切れたまま時刻を過ぎた場合も）: スマホ全体をロック
     * - ベッドにいる間: 前面のアプリが封印する種類のときだけロック（使用状況へのアクセスがなければ全体をロック）
     */
    private fun evaluateLock() {
        val s = StatusHolder.status.value
        val now = System.currentTimeMillis()
        val lastStatusMs = M5Link.lastStatusMs
        val unlockAfterMs = Config.UNLOCK_IF_UNREACHABLE_SEC * 1000
        // 最後に届いた状態に従う。M5Stack と長く通信できなければ解除する
        val lockedByM5 = s?.locked == true && now - lastStatusMs < unlockAfterMs
        // M5Stack と切れたままアラーム時刻を過ぎたら、鳴っているとみなしてロックする
        val alarmMs = (s?.alarmEpoch ?: 0L) * 1000
        val alarmPassedWhileAway = s != null && s.state in ALARM_PENDING_STATES && alarmMs > 0 &&
            lastStatusMs < alarmMs && now >= alarmMs && now - alarmMs < unlockAfterMs
        val alarmLock = (lockedByM5 && s?.state in ALARM_ACTIVE_STATES) || alarmPassedWhileAway
        val bedLock = lockedByM5 && !alarmLock

        if (s?.inBed == true) {
            if (inBedSinceMs == 0L) inBedSinceMs = now
        } else {
            inBedSinceMs = 0L
        }

        val appLevel = blocker.hasUsageAccess()
        val blocked = prefs.blockedCategories
        val show = Settings.canDrawOverlays(this) && when {
            alarmLock -> true
            bedLock -> !appLevel || blocker.isForegroundBlocked(blocked)
            else -> false
        }
        if (!show || s == null) {
            overlay.hide()
            return
        }
        val ui = LockUi(
            alarm = alarmLock,
            resleep = s.state == "RESLEEP_ARMED",
            connected = now - lastStatusMs < Config.BLE_STALE_MS,
            inBedSec = if (inBedSinceMs > 0) (now - inBedSinceMs) / 1000 else 0,
            pressureRaw = s.pressureRaw,
            threshold = s.config?.threshold ?: DEFAULT_THRESHOLD,
            inBed = s.inBed,
            blocked = blocked,
        )
        // アプリ単位の封印なら、ホーム画面に戻れば他のアプリは使える
        overlay.show(ui, if (bedLock && appLevel) ::goHome else null)
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        overlay.hide()
    }

    // 睡眠イベントを端末に保存してから M5Stack に受け取ったと知らせる（届かなければ次の状態でもう一度）
    private fun receiveSleepEvent(event: SleepEvent) {
        if (acking) return
        try {
            SleepStore.apply(this, event)
        } catch (e: Exception) {
            Log.w(TAG, "save sleep event failed: ${e.message}")
            return  // 保存できなければ受け取ったことにしない（M5Stack が送り直す）
        }
        acking = true
        scope.launch {
            try {
                M5Link.ackSleepEvent(event.id)
            } catch (e: Exception) {
                Log.w(TAG, "evack failed: ${e.message}")
            } finally {
                acking = false
            }
        }
    }

    // 失敗したら間隔を空けて再送する（毎秒送り直すと重くなる）
    private fun uploadIfNew(result: WakeResult) {
        val key = "${result.id}-${result.ringAt}"
        if (key == prefs.lastUploadedKey || uploading) return
        if (System.currentTimeMillis() < nextUploadRetryMs) return
        uploading = true
        scope.launch {
            try {
                RecordRepository.upload(result, prefs.nickname)
                prefs.lastUploadedKey = key
                uploadBackoffMs = UPLOAD_RETRY_MIN_MS
                Log.i(TAG, "uploaded $key")
                MyRecords.refresh()  // 戦績・ランキングに反映
            } catch (e: Exception) {
                Log.w(TAG, "upload failed, retry in ${uploadBackoffMs / 1000}s: ${e.message}")
                nextUploadRetryMs = System.currentTimeMillis() + uploadBackoffMs
                uploadBackoffMs = (uploadBackoffMs * 2).coerceAtMost(UPLOAD_RETRY_MAX_MS)
            } finally {
                uploading = false
            }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "アラーム監視", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("M5Stack を監視中")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val LOCK_CHECK_INTERVAL_MS = 1_000L
        private const val UPLOAD_RETRY_MIN_MS = 60_000L
        private const val UPLOAD_RETRY_MAX_MS = 30 * 60_000L

        /** alarmEpoch が「次に鳴る時刻」を表す状態（WAITING の alarmEpoch は待ちの期限なので除く） */
        private val ALARM_PENDING_STATES = setOf("ARMED", "AWAKE", "RESLEEP_ARMED")

        /** 鳴動中・二度寝検出中（スマホ全体をロックする） */
        private val ALARM_ACTIVE_STATES = setOf("RINGING", "RESLEEP_ARMED")

        private const val PRESSURE_FRESH_MS = 3_000L

        /** 古いファームで設定が届かないときの在床しきい値（wakeup_alarm.ino の IN_BED_THRESHOLD） */
        private const val DEFAULT_THRESHOLD = 300

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }
    }
}
