package com.fk2603.wakeup.ui

import android.app.Application
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fk2603.wakeup.AppBlocker
import com.fk2603.wakeup.BlockCategory
import com.fk2603.wakeup.Gamification
import com.fk2603.wakeup.M5Exception
import com.fk2603.wakeup.M5Link
import com.fk2603.wakeup.MyRecords
import com.fk2603.wakeup.Prefs
import com.fk2603.wakeup.PressureLog
import com.fk2603.wakeup.Record
import com.fk2603.wakeup.RecordRepository
import com.fk2603.wakeup.SleepStore
import com.fk2603.wakeup.StatusHolder
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
 * 画面とバック（M5Link / StatusHolder / SleepStore / MyRecords / PressureLog / Prefs）をつなぐ
 * 画面側は ui/dopa の UI 状態だけを見るので、UI を作り直すときはここと AppShell の組み立てを直せばよい
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    val status = StatusHolder.status
    val statusError = StatusHolder.error
    val sleeps = SleepStore.records
    val pressure = PressureLog.samples
    val myRecords = MyRecords.mine
    val ranking = MyRecords.ranking
    val myUid = MyRecords.uid
    val recordsError = MyRecords.error

    private val _nickname = MutableStateFlow(prefs.nickname)
    val nickname: StateFlow<String> = _nickname

    private val _blocked = MutableStateFlow(prefs.blockedCategories)
    val blocked: StateFlow<Set<BlockCategory>> = _blocked

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
    private val _clear = MutableStateFlow<ClearUi?>(null)
    val clear: StateFlow<ClearUi?> = _clear
    private var clearKey: String? = null

    init {
        SleepStore.load(app)
        PressureLog.load(app)
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
        SleepStore.load(app)  // ファイルが外で書き換えられていれば読み直す
    }

    fun refreshRecords() {
        viewModelScope.launch {
            _loading.value = true
            MyRecords.refresh()
            _loading.value = false
        }
    }

    fun setAlarm(hour: Int, minute: Int) {
        var target = ZonedDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(ZonedDateTime.now())) target = target.plusDays(1)
        call {
            M5Link.setAlarm(target.toEpochSecond())
            "${formatEpoch(target.toEpochSecond())} にセットしました"
        }
    }

    fun cancelAlarm() = call { M5Link.cancelAlarm(); "アラームを解除しました" }

    fun setConfig(threshold: Int, wakeConfirmSec: Int) = call {
        M5Link.setConfig(threshold, wakeConfirmSec)
        "センサー設定を M5Stack に保存しました"
    }

    fun setBlocked(category: BlockCategory, on: Boolean) {
        val next = if (on) _blocked.value + category else _blocked.value - category
        prefs.blockedCategories = next
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
                "${RecordRepository.deleteAllMine()} 件・睡眠記録 $sleepCount 件を削除しました"
            } catch (e: Exception) {
                "削除に失敗しました: ${e.message}"
            }
            MyRecords.refresh()
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

    /** デモ用のクリア画面（記録・XP には反映しない） */
    fun demoClear(seconds: Long): ClearUi = buildClear(
        Record("", "", "", seconds, System.currentTimeMillis() / 1000, false), MyRecords.mine.value,
    ).copy(demo = true)

    private fun onResult(r: WakeResult) {
        val key = "${r.id}-${r.ringAt}"
        val seen = prefs.lastSeenResultKey
        val nowSec = System.currentTimeMillis() / 1000
        // 初回起動時や古い記録は、見たことにして出さない
        if (seen.isEmpty() || nowSec - r.awakeAt > CLEAR_MAX_AGE_SEC) {
            if (seen != key) prefs.lastSeenResultKey = key
            return
        }
        if (key == seen || key == clearKey) return
        clearKey = key
        val record = Record("", "", nickname.value, r.durationSec, r.ringAt, r.resleep)
        // アップロード済みなら同じ記録を二重に数えない
        val others = MyRecords.mine.value.filterNot { it.ringAt == r.ringAt && it.durationSec == r.durationSec }
        _clear.value = buildClear(record, others)
    }

    private fun buildClear(record: Record, others: List<Record>): ClearUi {
        val before = Gamification.progress(others)
        val after = Gamification.progress(others + record)
        return ClearUi(
            seconds = record.durationSec,
            rank = Gamification.rankFor(record.durationSec),
            xpGain = Gamification.xpFor(record),
            streak = after.streak,
            level = before.level,
            levelXpBefore = before.levelXp,
            newBadge = (after.badges - before.badges).firstOrNull(),
            resleep = record.resleep,
        )
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
        private const val CLEAR_MAX_AGE_SEC = 12 * 3600L
    }
}
