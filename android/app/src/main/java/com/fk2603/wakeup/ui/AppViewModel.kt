package com.fk2603.wakeup.ui

import android.app.Application
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fk2603.wakeup.AppBlocker
import com.fk2603.wakeup.BedPhoneLog
import com.fk2603.wakeup.Config
import com.fk2603.wakeup.DemoSeed
import com.fk2603.wakeup.EmergencyUnlock
import com.fk2603.wakeup.ScreenTime
import com.fk2603.wakeup.ScreenTimeStore
import com.fk2603.wakeup.WakeStore
import com.fk2603.wakeup.ui.dopa.ScreenTimeUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fk2603.wakeup.InstalledApp
import com.fk2603.wakeup.M5Exception
import com.fk2603.wakeup.M5Link
import com.fk2603.wakeup.PairingStore
import com.fk2603.wakeup.Prefs
import com.fk2603.wakeup.PressureLog
import com.fk2603.wakeup.Record
import com.fk2603.wakeup.AlarmSchedule
import com.fk2603.wakeup.RoutineStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import com.fk2603.wakeup.SleepStore
import com.fk2603.wakeup.StatusHolder
import com.fk2603.wakeup.WakeFeedback
import com.fk2603.wakeup.WakeResult
import com.fk2603.wakeup.ui.dopa.ClearUi
import com.fk2603.wakeup.ui.dopa.SetupWarning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * 画面とバック（M5Link / StatusHolder / WakeStore / SleepStore / AlarmSchedule / RoutineStore / PressureLog / Prefs /
 * EmergencyUnlock / ScreenTime）をつなぐ
 * 画面側は ui/dopa の UI 状態だけを見るので、UI を作り直すときはここと AppShell の組み立てを直せばよい
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    val status = StatusHolder.status
    val statusError = StatusHolder.error
    val sleeps = SleepStore.records
    /** 端末に保存した起床記録（戦績の計算元） */
    val wakes = WakeStore.records
    /** ホームのアラーム（複数・曜日つき）。一番近い1件を MonitorService が M5Stack に送る */
    val alarms = AlarmSchedule.alarms
    /** 朝のルーティンの定義と、日ごとの結果（routine_log.csv） */
    val routineTasks = RoutineStore.tasks
    val routineHistory = RoutineStore.history
    /** ベッドの上でスマホを触った記録（bed_phone_log.csv） */
    val bedPhone = BedPhoneLog.sessions
    val bedPhoneActiveSince = BedPhoneLog.activeSince

    private val _nickname = MutableStateFlow(prefs.nickname)
    val nickname: StateFlow<String> = _nickname

    /** 起きるまでロックするアプリ（ユーザーが登録したもの）。ここにないアプリはいつでも使える */
    private val _blocked = MutableStateFlow(prefs.blockedApps)
    val blocked: StateFlow<Set<String>> = _blocked

    /** ロックに登録できるアプリ（ホーム画面に並ぶもの） */
    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps

    private val _warnings = MutableStateFlow<List<SetupWarning>>(emptyList())
    val warnings: StateFlow<List<SetupWarning>> = _warnings

    private val _usageAccess = MutableStateFlow(false)
    val usageAccess: StateFlow<Boolean> = _usageAccess

    /** 操作の結果（画面下に出す） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** 新しい起床記録が届いたときのクリア画面 */
    /** 緊急解除の回数・連続記録（UI チームが使う値） */
    private val _emergency = MutableStateFlow(readEmergency())
    val emergency: StateFlow<EmergencyStats> = _emergency

    /** スマホ使用データの確認画面 */
    private val _screenTime = MutableStateFlow(emptyScreenTime())
    val screenTime: StateFlow<ScreenTimeUi> = _screenTime

    private val _clear = MutableStateFlow<ClearUi?>(null)
    val clear: StateFlow<ClearUi?> = _clear
    private var clearKey: String? = null

    init {
        // デモ用の1週間分を、初回だけ本番と同じ保存処理で取り込む（以後の実記録は同じファイルに追記）
        DemoSeed.importOnce(app, prefs)
        DemoSeed.importRoutinesOnce(app, prefs)
        DemoSeed.importBedPhoneOnce(app, prefs)
        AlarmSchedule.load(app)
        RoutineStore.load(app)
        WakeStore.load(app)
        SleepStore.load(app)
        PressureLog.load(app)
        BedPhoneLog.load(app)
        refreshPermissions()
        refreshRecords()
        viewModelScope.launch {
            status.map { it?.lastResult }.distinctUntilChanged().collect { r -> if (r != null) onResult(r) }
        }
    }

    fun refreshPermissions() {
        val app = getApplication<Application>()
        val power = app.getSystemService(PowerManager::class.java)
        val usage = AppBlocker.hasUsageAccess(app)
        _usageAccess.value = usage
        _warnings.value = buildList {
            if (!Settings.canDrawOverlays(app)) add(SetupWarning.OVERLAY)
            if (!power.isIgnoringBatteryOptimizations(app.packageName)) add(SetupWarning.BATTERY)
            if (!usage) add(SetupWarning.USAGE)
        }
        // ファイルが外で書き換えられていれば読み直す
        SleepStore.load(app)
        WakeStore.load(app)
        BedPhoneLog.load(app)
        AlarmSchedule.load(app)  // 過ぎた1回だけのアラームはここでオフになる
        RoutineStore.load(app)
        RoutineStore.ensureDay(app, routineDate())  // その日のルーティンを記録に書き出す
        _emergency.value = readEmergency()
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) { AppBlocker.loadLaunchableApps(app) }
        }
    }

    fun refreshRecords() {
        viewModelScope.launch {
            _loading.value = true
            _emergency.value = readEmergency()
            _loading.value = false
        }
    }

    /** アラームを追加する（曜日なしなら1回だけ）。M5Stack へはサービスが一番近い1件を送る */
    fun addAlarm(hour: Int, minute: Int, weekdays: Set<Int>) {
        val app = getApplication<Application>()
        AlarmSchedule.add(app, hour, minute, weekdays)
        RoutineStore.ensureDay(app, routineDate())
        _message.value = "%02d:%02d（%s）を追加しました".format(hour, minute, AlarmSchedule.daysLabel(AlarmSchedule.alarms.value.last()))
    }

    /** デバッグ: 10秒後に鳴らす（鳴り方は通常のアラームと同じ。サービスの同期でも消されないよう AlarmSchedule に入れる） */
    fun setDebugAlarm() {
        val epoch = System.currentTimeMillis() / 1000 + DEBUG_ALARM_SEC
        AlarmSchedule.debugEpoch = epoch
        call {
            M5Link.setAlarm(epoch)
            "デバッグ: ${DEBUG_ALARM_SEC}秒後（${formatEpoch(epoch, "HH:mm:ss")}）にアラームをセットしました"
        }
    }

    fun setAlarmEnabled(id: Long, enabled: Boolean) = AlarmSchedule.setEnabled(getApplication(), id, enabled)

    fun removeAlarm(id: Long) = AlarmSchedule.remove(getApplication(), id)

    /**
     * 朝のルーティンの対象日。次のアラームが今日なら今日、夜（18時以降）で次のアラームが明日以降ならその日、
     * それ以外は今日（朝に起きてからも今日のチェックができるように）
     */
    fun routineDate(): LocalDate {
        val today = LocalDate.now()
        val next = AlarmSchedule.nextEpoch(getApplication())
            ?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: return today
        return if (next == today || LocalTime.now().hour >= EVENING_HOUR) next else today
    }

    fun addRoutineTask(title: String, weekdays: Set<Int>, onlyDate: String?) {
        val app = getApplication<Application>()
        RoutineStore.addTask(app, title, weekdays, onlyDate)
        RoutineStore.ensureDay(app, routineDate())
    }

    fun removeRoutineTask(id: Long) = RoutineStore.removeTask(getApplication(), id)

    /** ルーティンのチェックを付ける／外す（routine_log.csv に残る） */
    fun toggleRoutineTask(id: Long) = RoutineStore.toggle(getApplication(), routineDate(), id)

    fun routineDoneIds(date: LocalDate): Set<Long> = RoutineStore.doneIds(getApplication(), date)

    /** QR で M5Stack を登録済みか */
    val paired: StateFlow<Boolean> = PairingStore.paired

    fun pairedDevId(): String? = PairingStore.devId

    /** 読み取った QR で M5Stack を登録する（鍵を交換して、以降の通信を暗号化する） */
    fun pairDevice(qrText: String) {
        viewModelScope.launch {
            _message.value = "M5Stack に接続しています…"
            _message.value = try {
                M5Link.pair(getApplication(), qrText)
                "M5Stack と安全に接続しました（暗号化通信）"
            } catch (e: M5Exception) {
                e.message
            } catch (e: Exception) {
                "M5Stack に接続できませんでした（Bluetooth）"
            }
        }
    }

    fun onScanFailed() {
        _message.value = "QR を読み取れませんでした"
    }

    fun unpairDevice() {
        viewModelScope.launch {
            M5Link.unpair(getApplication())
            _message.value = "M5Stack の登録を解除しました"
        }
    }

    fun setConfig(threshold: Int, wakeConfirmSec: Int, resleepDelayMin: Int?) = call {
        M5Link.setConfig(threshold, wakeConfirmSec, resleepDelayMin)
        "センサー設定を M5Stack に保存しました"
    }

    /** アプリをロックに登録する／外す */
    fun setAppBlocked(pkg: String, blocked: Boolean) =
        saveBlocked(if (blocked) _blocked.value + pkg else _blocked.value - pkg)

    fun blockAllApps() = saveBlocked(_apps.value.map { it.pkg }.toSet())

    fun allowAllApps() = saveBlocked(emptySet())

    private fun saveBlocked(next: Set<String>) {
        prefs.blockedApps = next
        _blocked.value = next
    }

    fun saveNickname(name: String) {
        prefs.nickname = name
        _nickname.value = prefs.nickname
        _message.value = "ニックネームを保存しました（次の記録から反映）"
    }

    fun deleteAll() {
        viewModelScope.launch {
            _message.value = try {
                val sleepCount = SleepStore.clear(getApplication())
                val wakeCount = WakeStore.clear(getApplication())
                val routineDays = RoutineStore.clearLog(getApplication())
                val bedPhoneCount = BedPhoneLog.clear(getApplication())
                "起床記録 $wakeCount 件・睡眠記録 $sleepCount 件・ルーティン $routineDays 日分・ベッドでスマホ $bedPhoneCount 回を削除しました"
            } catch (e: Exception) {
                "削除に失敗しました: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun dismissClear() {
        clearKey?.let { prefs.lastSeenResultKey = it }
        _clear.value = null
        refreshRecords()
    }

    /** 今日のルーティンのタスク名（起きたあとのクリア画面に出す） */
    fun todayRoutine(): List<String> = RoutineStore.tasks.value.filter { it.appliesTo(LocalDate.now()) }.map { it.title }

    /** デモ用のクリア画面（記録・XP には反映しない） */
    fun demoClear(seconds: Long): ClearUi {
        val now = System.currentTimeMillis() / 1000
        return WakeFeedback.build(
            getApplication(), Record("", "", "", seconds, now - seconds, false), now, wakes.value.map { it.toRecord() }, demo = true,
        )
    }

    /** 緊急解除（ロック画面の仮ボタンと同じ処理。回数が増え、連続記録が0に戻る） */
    fun emergencyUnlock() {
        EmergencyUnlock.trigger(getApplication(), prefs, null)
        _emergency.value = readEmergency()
        _message.value = "緊急解除しました（${Config.EMERGENCY_UNLOCK_SEC / 60}分間ロックしません）"
    }

    private fun readEmergency() = EmergencyStats(
        count = EmergencyUnlock.count(getApplication()).toLong(),
        streak = EmergencyUnlock.streak(getApplication(), prefs),
        best = EmergencyUnlock.bestStreak(getApplication(), prefs),
        week = EmergencyUnlock.weekCount(getApplication()),
        byApp = EmergencyUnlock.countByApp(getApplication()),
        activeUntilMs = prefs.emergencyUntilMs,
    )

    /** Android の利用時間の記録を読み、端末にも写す（スマホ使用データの確認画面） */
    fun loadScreenTime() {
        val app = getApplication<Application>()
        _screenTime.value = _screenTime.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _screenTime.value = withContext(Dispatchers.IO) {
                try {
                    val store = ScreenTimeStore(app)
                    val access = ScreenTime.hasAccess(app)
                    if (access) ScreenTime.snapshot(app, store)
                    val retention = if (access) ScreenTime.retention(app) else null
                    val daily = if (access) ScreenTime.readDaily(app, 14) else emptyMap()
                    val baseline = store.baseline
                    val e = readEmergency()
                    ScreenTimeUi(
                        access = access,
                        loading = false,
                        retentionDaily = retention?.dailyDays,
                        retentionWeekly = retention?.weeklyCount,
                        retentionMonthly = retention?.monthlyCount,
                        androidDaily = daily.entries.sortedByDescending { it.key }.map { it.key to it.value },
                        storedDays = store.loadDaily().size,
                        installDay = store.installDay,
                        baselineAvgMs = baseline?.avgMs,
                        baselineSource = baseline?.source,
                        baselineDays = baseline?.days,
                        unlockCount = e.count,
                        noUnlockStreak = e.streak,
                        bestStreak = e.best,
                        error = null,
                    )
                } catch (e: Exception) {
                    _screenTime.value.copy(loading = false, error = "読み込めませんでした: ${e.message}")
                }
            }
        }
    }

    private fun emptyScreenTime() = ScreenTimeUi(
        access = false, loading = false, retentionDaily = null, retentionWeekly = null, retentionMonthly = null,
        androidDaily = emptyList(), storedDays = 0, installDay = null, baselineAvgMs = null, baselineSource = null,
        baselineDays = null, unlockCount = 0, noUnlockStreak = 0, bestStreak = 0, error = null,
    )

    // 普段はサービスがどのアプリの上にもフィードバック画面を出す。重ねて表示が未許可のときだけアプリ内で出す
    private fun onResult(r: WakeResult) {
        if (Settings.canDrawOverlays(getApplication())) return
        val key = WakeFeedback.key(r)
        val seen = prefs.lastSeenResultKey
        val nowSec = System.currentTimeMillis() / 1000
        // 初回起動時や古い記録は、見たことにして出さない
        if (seen.isEmpty() || nowSec - r.awakeAt > WakeFeedback.MAX_AGE_SEC) {
            if (seen != key) prefs.lastSeenResultKey = key
            return
        }
        if (key == seen || key == clearKey) return
        clearKey = key
        val record = Record("", "", nickname.value, r.durationSec, r.ringAt, r.resleep)
        // サービスが先に端末へ保存していても、同じ記録を二重に数えない
        val others = wakes.value.filterNot { it.ringAt == r.ringAt && it.awakeAt == r.awakeAt }.map { it.toRecord() }
        _clear.value = WakeFeedback.build(getApplication(), record, r.awakeAt, others)
    }

    private fun call(block: suspend () -> String) {
        viewModelScope.launch {
            _message.value = try {
                block()
            } catch (e: M5Exception) {
                when (e.message) {
                    "locked" -> "ベッドにいる間・アラーム中は変更できません"
                    "time not set" -> "M5Stack の時刻がまだ合っていません。少し待ってから試してください"
                    "out of range" -> "設定できる範囲を超えています"
                    else -> "エラー: ${e.message}"
                }
            } catch (e: Exception) {
                "M5Stack に接続できません（Bluetooth）"
            }
        }
    }

    companion object {
        /** この時刻を過ぎたら、ホームのルーティンを次のアラームの日（明日の朝）のものにする */
        private const val EVENING_HOUR = 18
        private const val DEBUG_ALARM_SEC = 10L
    }
}

/** 緊急解除の回数と「緊急解除しなかった日」の連続記録 */
data class EmergencyStats(
    val count: Long,
    val streak: Int,
    val best: Int,
    val activeUntilMs: Long,
    /** 直近7日の回数 */
    val week: Int = 0,
    /** アプリ（パッケージ名。空 = 全体）ごとの回数（多い順） */
    val byApp: List<Pair<String, Int>> = emptyList(),
)
