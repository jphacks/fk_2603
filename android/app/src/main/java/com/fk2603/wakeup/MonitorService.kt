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
 * - 起床・睡眠などの記録を端末の CSV に保存する
 * - 睡眠のイベントを受け取り、端末内の睡眠記録（SleepStore）に反映する
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var overlay: LockOverlay

    private var acking = false
    private lateinit var blocker: AppBlocker
    private var inBedSinceMs = 0L
    private var outOfBedSinceMs = 0L
    private var alarmSyncing = false
    private var nextAlarmSyncMs = 0L
    private var savedResultKey = ""
    @Volatile private var snapshotting = false
    private lateinit var screenTimeStore: ScreenTimeStore

    // 画面ロックを解除した瞬間にロック画面を出す（1秒ごとの判定を待たない）
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = evaluateLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = LockOverlay(this) { triggerEmergency() }
        blocker = AppBlocker(this)
        screenTimeStore = ScreenTimeStore(this)
        EmergencyUnlock.ensureFirstDay(this, prefs)
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
            s?.lastResult?.let { saveResult(it) }
            s?.sleepEvent?.let { receiveSleepEvent(it) }
            // 届いたばかりの値だけをグラフに残す（切断中に古い値を足さない）
            if (s != null && System.currentTimeMillis() - M5Link.lastStatusMs < PRESSURE_FRESH_MS) {
                PressureLog.add(this, s.pressureRaw)
            }
            evaluateLock()
            syncAlarmToM5()
            snapshotScreenTimeIfDue()
            delay(LOCK_CHECK_INTERVAL_MS)
        }
    }

    /**
     * - アラーム中（M5Stack と切れたまま時刻を過ぎた場合も）: スマホ全体をロック
     * - ベッドにいる間: 前面のアプリが封印する種類のときだけロック（使用状況へのアクセスがなければ全体をロック）
     * - 緊急解除中はロックしない（アラームは M5Stack 側で鳴り続ける）
     * - ベッドを出て Config.UNLOCK_AFTER_LEAVE_BED_SEC 秒たったら、M5Stack の起床確認を待たずに外す（戻れば即ロック）
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
            outOfBedSinceMs = 0L
        } else {
            inBedSinceMs = 0L
            if (s != null && outOfBedSinceMs == 0L) outOfBedSinceMs = now
        }
        // 新しい状態で「ベッドにいない」が続いている（切断中の古い状態では判断しない）
        val leftBed = s != null && !s.inBed && now - lastStatusMs < Config.BLE_STALE_MS &&
            outOfBedSinceMs > 0 && now - outOfBedSinceMs >= Config.UNLOCK_AFTER_LEAVE_BED_SEC * 1000

        val appLevel = blocker.hasUsageAccess()
        val allowed = prefs.allowedApps
        val emergency = EmergencyUnlock.isActive(prefs, now)
        val show = Settings.canDrawOverlays(this) && !emergency && !leftBed && when {
            alarmLock -> true
            bedLock -> !appLevel || blocker.isForegroundBlocked(allowed)
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
            blockedPkgs = blocker.launchableApps().filter { it.pkg !in allowed }.take(LOCK_ICON_COUNT).map { it.pkg },
            streak = EmergencyUnlock.streak(this, prefs),
        )
        // アプリ単位の封印なら、ホーム画面に戻れば他のアプリは使える
        overlay.show(ui, if (bedLock && appLevel) ::goHome else null)
    }

    // ホームのアラーム（複数・曜日つき）のうち一番近い1件を M5Stack に送る（M5Stack は1件しか持てない）
    // 鳴り終わると次の曜日の分を送り直す。アラームが全部オフなら解除する。M5Stack を変えられる状態のときだけ
    private fun syncAlarmToM5() {
        val s = StatusHolder.status.value ?: return
        val now = System.currentTimeMillis()
        if (alarmSyncing || now < nextAlarmSyncMs) return
        if (now - M5Link.lastStatusMs > PRESSURE_FRESH_MS || s.state !in ALARM_EDITABLE_STATES) return
        val target = AlarmSchedule.nextEpoch(this) ?: 0L
        val current = if (s.state == "IDLE") 0L else s.alarmEpoch
        if (target == current) return
        alarmSyncing = true
        nextAlarmSyncMs = now + ALARM_SYNC_RETRY_MS
        scope.launch {
            try {
                if (target > 0) M5Link.setAlarm(target) else M5Link.cancelAlarm()
                Log.i(TAG, "alarm synced to M5Stack: $target")
            } catch (e: Exception) {
                Log.w(TAG, "alarm sync failed: ${e.message}")
            } finally {
                alarmSyncing = false
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        overlay.hide()
    }

    private fun triggerEmergency() {
        EmergencyUnlock.trigger(this, prefs)
        evaluateLock()
    }

    // Android の日ごとの利用記録は数日で消えるので、1時間ごとに端末内へ写しておく（スマホ使用データの比較用）
    private fun snapshotScreenTimeIfDue() {
        if (snapshotting) return
        if (System.currentTimeMillis() - screenTimeStore.lastSnapshotMs < ScreenTime.SNAPSHOT_INTERVAL_MS) return
        snapshotting = true
        scope.launch(Dispatchers.IO) {
            try {
                ScreenTime.snapshot(this@MonitorService, screenTimeStore)
            } catch (e: Exception) {
                Log.w(TAG, "screen time snapshot failed: ${e.message}")
            } finally {
                snapshotting = false
            }
        }
    }

    // 起床記録を端末の CSV に保存する（戦績の計算元）
    private fun saveResult(result: WakeResult) {
        val key = "${result.id}-${result.ringAt}"
        if (key == savedResultKey) return
        try {
            WakeStore.add(this, result)
            savedResultKey = key
        } catch (e: Exception) {
            Log.w(TAG, "save wake result failed: ${e.message}")
        }
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
        /** ロック画面に並べる封印中アプリのアイコン数 */
        private const val LOCK_ICON_COUNT = 5

        /** alarmEpoch が「次に鳴る時刻」を表す状態（WAITING の alarmEpoch は待ちの期限なので除く） */
        private val ALARM_PENDING_STATES = setOf("ARMED", "AWAKE", "RESLEEP_ARMED")

        /** M5Stack のアラームを変えられる状態（WAITING・鳴動中・二度寝検出中は変えない） */
        private val ALARM_EDITABLE_STATES = setOf("IDLE", "ARMED", "AWAKE")

        /** M5Stack へのアラーム送信の間隔（失敗したときに毎秒送り直さない） */
        private const val ALARM_SYNC_RETRY_MS = 10_000L

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
