package com.fk2603.wakeup

import android.app.KeyguardManager
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
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fk2603.wakeup.ui.dopa.ClearUi
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
 * - アラーム中は全画面のアラーム画面を出す。ベッドにいる間は、登録したアプリを開いたときだけロック画面を出す
 * - 起きたら（M5Stack の起床記録が届いたら）、アプリを開いていなくても総評のフィードバック画面を出す
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
    /** いまロック画面を出しているアプリ（緊急解除の対象）。スマホ全体のロックなら null */
    private var lockedPkg: String? = null
    /** 起きたあとに出すフィードバック画面（「スマホを解放する」まで出し続ける） */
    private var pendingClear: ClearUi? = null
    private var pendingClearKey = ""
    private var stopping = false
    /** ベッドの上でスマホを触り始めた時刻（ms）。触っていなければ 0（戦績の「ベッドでスマホ」） */
    private var bedPhoneSinceMs = 0L

    // 画面ロックを解除した瞬間にロック画面を出す（1秒ごとの判定を待たない）
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = evaluateLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = LockOverlay(this, onEmergency = ::triggerEmergency, onStop = ::stopAlarm, onClearDone = ::finishClear)
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
        scope.launch { appCheckLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        trackBedPhone(false, System.currentTimeMillis())  // 触っている途中の分を残す
        overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun lockLoop() {
        while (scope.isActive) {
            val s = StatusHolder.status.value
            s?.lastResult?.let {
                saveResult(it)
                prepareClear(it)
            }
            s?.sleepEvent?.let { receiveSleepEvent(it) }
            // 届いたばかりの値だけをグラフに残す（切断中に古い値を足さない）
            if (s != null && System.currentTimeMillis() - M5Link.lastStatusMs < PRESSURE_FRESH_MS) {
                PressureLog.add(this, s.pressureRaw)
            }
            syncAlarmToM5()
            snapshotScreenTimeIfDue()
            delay(LOCK_CHECK_INTERVAL_MS)
        }
    }

    // 登録アプリを開いたらすぐロック画面を出すため、前面のアプリは短い間隔で見る
    private suspend fun appCheckLoop() {
        while (scope.isActive) {
            evaluateLock()
            delay(APP_CHECK_INTERVAL_MS)
        }
    }

    /**
     * 上から順に1つだけ出す
     * 1. アラーム中（M5Stack と切れたまま時刻を過ぎた場合も）: どのアプリの上にも全画面の AlarmScreen。
     *    鳴動中はベッドを出ても止まるまで出し続ける（「アラームを止める」か、M5Stack の起床確認で止まる）
     * 2. 起きたあと: フィードバック画面（ClearScreen）。「スマホを解放する」まで
     * 3. ベッドにいる間: 前面のアプリが登録済みのときだけ LockScreen。登録していないアプリは使える
     *    使用状況へのアクセスがなければ前面のアプリがわからないので、スマホ全体をロックする
     * - 緊急解除したアプリはロックしない。全体の緊急解除中はアラーム画面もロックも出さない（アラームは M5Stack 側で鳴り続ける）
     * - ベッドを出て Config.UNLOCK_AFTER_LEAVE_BED_SEC 秒たったら、在床のロックは M5Stack の起床確認を待たずに外す（戻れば即ロック）
     */
    private fun evaluateLock() {
        val s = StatusHolder.status.value
        val now = System.currentTimeMillis()
        val lastStatusMs = M5Link.lastStatusMs
        val unlockAfterMs = Config.UNLOCK_IF_UNREACHABLE_SEC * 1000
        val fresh = now - lastStatusMs < Config.BLE_STALE_MS
        // 最後に届いた状態に従う。M5Stack と長く通信できなければ解除する
        val lockedByM5 = s?.locked == true && now - lastStatusMs < unlockAfterMs
        // M5Stack と切れたままアラーム時刻を過ぎたら、鳴っているとみなしてロックする
        val alarmMs = (s?.alarmEpoch ?: 0L) * 1000
        val alarmPassedWhileAway = s != null && s.state in ALARM_PENDING_STATES && alarmMs > 0 &&
            lastStatusMs < alarmMs && now >= alarmMs && now - alarmMs < unlockAfterMs
        val alarmLock = (lockedByM5 && s?.state in ALARM_ACTIVE_STATES) || alarmPassedWhileAway
        val bedLock = lockedByM5 && !alarmLock
        val ringing = alarmLock && s?.state == "RINGING"

        if (s?.inBed == true) {
            if (inBedSinceMs == 0L) inBedSinceMs = now
            outOfBedSinceMs = 0L
        } else {
            inBedSinceMs = 0L
            if (s != null && outOfBedSinceMs == 0L) outOfBedSinceMs = now
        }
        trackBedPhone(s?.inBed == true && fresh, now)
        // 新しい状態で「ベッドにいない」が続いている（切断中の古い状態では判断しない）
        val leftBed = s != null && !s.inBed && fresh &&
            outOfBedSinceMs > 0 && now - outOfBedSinceMs >= Config.UNLOCK_AFTER_LEAVE_BED_SEC * 1000

        // アプリ内の画面で閉じられたフィードバックは出さない
        if (pendingClear != null && pendingClearKey == prefs.lastSeenResultKey) pendingClear = null

        val alarmShow = alarmLock && (ringing || !leftBed) && !EmergencyUnlock.isActive(prefs, null, now)
        val appLevel = blocker.hasUsageAccess()
        val blocked = prefs.blockedApps
        val pkg = if (appLevel && bedLock && !alarmShow) blocker.foregroundPackage() else null
        val bedShow = bedLock && !leftBed && (!appLevel || pkg in blocked) && !EmergencyUnlock.isActive(prefs, pkg, now)
        val clear = pendingClear
        if (!Settings.canDrawOverlays(this) || s == null || (!alarmShow && clear == null && !bedShow)) {
            lockedPkg = null
            overlay.hide()
            return
        }
        if (!alarmShow && clear != null) {
            lockedPkg = null
            overlay.showClear(clear)
            return
        }
        val canStop = ringing && !s.inBed && fresh && outOfBedSinceMs > 0 &&
            now - outOfBedSinceMs >= M5Config.STOP_AFTER_LEAVE_SEC * 1000
        val ui = LockUi(
            alarm = alarmShow,
            resleep = s.state == "RESLEEP_ARMED",
            connected = fresh,
            inBedSec = if (inBedSinceMs > 0) (now - inBedSinceMs) / 1000 else 0,
            pressureRaw = s.pressureRaw,
            threshold = s.config?.threshold ?: DEFAULT_THRESHOLD,
            inBed = s.inBed,
            blockedPkgs = blocker.launchableApps().filter { it.pkg in blocked }.take(LOCK_ICON_COUNT).map { it.pkg },
            streak = EmergencyUnlock.streak(this, prefs),
            appOnly = pkg != null,
            canStop = canStop,
            autoStopInSec = s.config?.takeIf { canStop && outOfBedSinceMs > 0 }
                ?.let { (it.wakeConfirmSec - (now - outOfBedSinceMs) / 1000).coerceAtLeast(0) },
        )
        lockedPkg = pkg
        // アプリ単位のロックなら、ホーム画面に戻れば登録していないアプリは使える（アラーム画面には出さない）
        overlay.show(ui, if (!alarmShow && appLevel) ::goHome else null)
    }

    // ベッドにいて、画面がつき、ロック画面が外れている間を「スマホを触った1回」として記録する（通知で画面がついただけは数えない）
    private fun trackBedPhone(inBed: Boolean, now: Long) {
        val using = inBed && getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (using) {
            if (bedPhoneSinceMs == 0L) {
                bedPhoneSinceMs = now
                BedPhoneLog.activeSince.value = now / 1000
            }
            return
        }
        if (bedPhoneSinceMs == 0L) return
        try {
            BedPhoneLog.add(this, BedPhoneSession(bedPhoneSinceMs / 1000, now / 1000))
        } catch (e: Exception) {
            Log.w(TAG, "save bed phone failed: ${e.message}")
        }
        bedPhoneSinceMs = 0L
        BedPhoneLog.activeSince.value = 0L
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

    // 「アラームを止める」: M5Stack に止めてもらい、起床記録が届けばフィードバック画面に切り替わる
    private fun stopAlarm() {
        if (stopping) return
        stopping = true
        scope.launch {
            try {
                M5Link.stopAlarm()
            } catch (e: Exception) {
                Log.w(TAG, "stop alarm failed: ${e.message}")  // 古いファームでも、ベッドの外にいれば自動で止まる
            } finally {
                stopping = false
            }
        }
    }

    // 新しい起床記録が届いたら、フィードバック画面を用意する（初回起動時や古い記録は、見たことにして出さない）
    private fun prepareClear(result: WakeResult) {
        val key = WakeFeedback.key(result)
        val seen = prefs.lastSeenResultKey
        if (key == seen || key == pendingClearKey) return
        if (seen.isEmpty() || System.currentTimeMillis() / 1000 - result.awakeAt > WakeFeedback.MAX_AGE_SEC) {
            prefs.lastSeenResultKey = key
            return
        }
        try {
            val record = Record("", "", prefs.nickname, result.durationSec, result.ringAt, result.resleep)
            val others = WakeStore.records.value
                .filterNot { it.ringAt == result.ringAt && it.awakeAt == result.awakeAt }
                .map { it.toRecord() }
            pendingClear = WakeFeedback.build(this, record, result.awakeAt, others)
            pendingClearKey = key
        } catch (e: Exception) {
            Log.w(TAG, "build feedback failed: ${e.message}")
        }
    }

    private fun finishClear() {
        prefs.lastSeenResultKey = pendingClearKey
        pendingClear = null
        evaluateLock()
    }

    private fun triggerEmergency() {
        EmergencyUnlock.trigger(this, prefs, lockedPkg)
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
        /** 前面のアプリを確認する間隔（登録アプリを開いてからロック画面が出るまでの最大の遅れ） */
        private const val APP_CHECK_INTERVAL_MS = 300L
        /** ロック画面に並べる封印中アプリのアイコン数 */
        private const val LOCK_ICON_COUNT = 5

        /** alarmEpoch が「次に鳴る時刻」を表す状態（WAITING の alarmEpoch は待ちの期限なので除く） */
        private val ALARM_PENDING_STATES = setOf("ARMED", "AWAKE", "RESLEEP_ARMED")

        /** M5Stack のアラームを変えられる状態（WAITING・鳴動中・二度寝検出中は変えない） */
        private val ALARM_EDITABLE_STATES = setOf("IDLE", "ARMED", "AWAKE")

        /** M5Stack へのアラーム送信の間隔（失敗したときに毎秒送り直さない） */
        private const val ALARM_SYNC_RETRY_MS = 10_000L

        /** 鳴動中・二度寝検出中（登録アプリを開くとアラーム画面を出す） */
        private val ALARM_ACTIVE_STATES = setOf("RINGING", "RESLEEP_ARMED")

        private const val PRESSURE_FRESH_MS = 3_000L

        /** 古いファームで設定が届かないときの在床しきい値（wakeup_alarm.ino の IN_BED_THRESHOLD） */
        private const val DEFAULT_THRESHOLD = 300

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }
    }
}
