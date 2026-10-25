package com.fk2603.wakeup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime

/**
 * スマホ側で持つアラーム。曜日は 月=1〜日=7、空なら1回だけ。
 * 1回だけのアラームは鳴る時刻（onceAt）を持ち、過ぎたら自動でオフになる
 */
data class LocalAlarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val weekdays: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val onceAt: Long = 0,
)

/** 次に鳴る時刻（UNIX秒）。オフ・過ぎた1回だけのアラームは null */
fun LocalAlarm.nextEpoch(now: ZonedDateTime = ZonedDateTime.now()): Long? {
    if (!enabled) return null
    if (weekdays.isEmpty()) return onceAt.takeIf { it > now.toEpochSecond() }
    return nextOccurrence(hour, minute, weekdays, now)
}

private fun nextOccurrence(hour: Int, minute: Int, weekdays: Set<Int>, now: ZonedDateTime): Long? {
    for (offset in 0..7) {
        val date = now.toLocalDate().plusDays(offset.toLong())
        if (weekdays.isNotEmpty() && date.dayOfWeek.value !in weekdays) continue
        val target = date.atTime(hour, minute).atZone(now.zone)
        if (target.isAfter(now)) return target.toEpochSecond()
    }
    return null
}

/**
 * アラームの一覧（複数・曜日つき）。設定値なので SharedPreferences `dopa_local_alarms` に JSON で持つ
 * M5Stack は1件しか持てないので、MonitorService が「一番近い1件」を送る（nextEpoch）
 */
object AlarmSchedule {
    private const val TAG = "AlarmSchedule"
    private val _alarms = MutableStateFlow<List<LocalAlarm>>(emptyList())
    val alarms: StateFlow<List<LocalAlarm>> = _alarms
    private var loaded = false

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences("dopa_local_alarms", Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context) {
        if (!loaded) {
            loaded = true
            _alarms.value = runCatching {
                val arr = JSONArray(sp(context).getString("alarms", "[]") ?: "[]")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val days = o.optJSONArray("days") ?: JSONArray()
                    LocalAlarm(
                        id = o.getLong("id"),
                        hour = o.getInt("hour"),
                        minute = o.getInt("minute"),
                        weekdays = (0 until days.length()).map { days.getInt(it) }.toSet(),
                        enabled = o.optBoolean("enabled", true),
                        onceAt = o.optLong("onceAt", 0),
                    )
                }
            }.getOrElse {
                Log.w(TAG, "read failed: $it")
                emptyList()
            }
            // 前の版の1回だけのアラームには鳴る時刻がないので、次のその時刻にする
            if (_alarms.value.any { it.weekdays.isEmpty() && it.onceAt == 0L }) {
                save(context, _alarms.value.map { if (it.weekdays.isEmpty() && it.onceAt == 0L) withOnceAt(it) else it })
            }
        }
        disablePassed(context)
    }

    @Synchronized
    fun add(context: Context, hour: Int, minute: Int, weekdays: Set<Int>) {
        load(context)
        save(context, _alarms.value + withOnceAt(LocalAlarm(System.nanoTime(), hour, minute, weekdays)))
    }

    @Synchronized
    fun setEnabled(context: Context, id: Long, enabled: Boolean) {
        load(context)
        // 1回だけのアラームをオンにし直したら、次のその時刻にする
        save(context, _alarms.value.map { if (it.id == id) withOnceAt(it.copy(enabled = enabled)) else it })
    }

    @Synchronized
    fun remove(context: Context, id: Long) {
        load(context)
        save(context, _alarms.value.filter { it.id != id })
    }

    /** 有効なアラームのうち一番近い時刻（UNIX秒） */
    fun nextEpoch(context: Context, now: ZonedDateTime = ZonedDateTime.now()): Long? {
        load(context)
        return _alarms.value.mapNotNull { it.nextEpoch(now) }.minOrNull()
    }

    private fun withOnceAt(a: LocalAlarm): LocalAlarm =
        if (a.weekdays.isEmpty() && a.enabled) a.copy(onceAt = nextOccurrence(a.hour, a.minute, emptySet(), ZonedDateTime.now()) ?: 0) else a

    // 鳴り終わった1回だけのアラームはオフにする
    private fun disablePassed(context: Context) {
        val now = Instant.now().epochSecond
        if (_alarms.value.none { it.enabled && it.weekdays.isEmpty() && it.onceAt in 1..now }) return
        save(context, _alarms.value.map {
            if (it.enabled && it.weekdays.isEmpty() && it.onceAt in 1..now) it.copy(enabled = false) else it
        })
    }

    private fun save(context: Context, list: List<LocalAlarm>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("hour", a.hour)
                put("minute", a.minute)
                put("enabled", a.enabled)
                put("onceAt", a.onceAt)
                put("days", JSONArray().apply { a.weekdays.sorted().forEach { put(it) } })
            })
        }
        sp(context).edit().putString("alarms", arr.toString()).apply()
        _alarms.value = list
    }

    /** 表示用（例: 月・水・金 / 毎日 / 1回のみ） */
    fun daysLabel(a: LocalAlarm): String = when {
        a.weekdays.isEmpty() -> "1回のみ"
        a.weekdays.size == 7 -> "毎日"
        else -> a.weekdays.sorted().joinToString("・") { listOf("月", "火", "水", "木", "金", "土", "日")[it - 1] }
    }
}
