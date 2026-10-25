package com.fk2603.wakeup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** ベッドの上でスマホを触った1回（画面がついてロックが外れていた間） */
data class BedPhoneSession(val startAt: Long, val endAt: Long) {
    val sec get() = endAt - startAt
}

/** 期間内の合計 */
data class BedPhoneTotal(val count: Int, val sec: Long)

/**
 * ベッドの上でスマホを触った記録を CSV ファイル bed_phone_log.csv に保存する（CsvFile の形式）
 * - 1行 = 1回。古い順
 *     開始,終了,秒数
 *     2026-10-24 23:40:00,2026-10-24 23:52:30,750
 * - MonitorService が「ベッドにいる・画面がついている・ロック画面が外れている」間を1回として書く
 * - 夜は日付をまたぐので、18時以降の分は翌朝の日付として数える（dayOf）
 */
object BedPhoneLog {
    private const val TAG = "BedPhoneLog"
    /** これより短いもの（通知を見ただけなど）は数えない */
    const val MIN_SEC = 5L
    /** この時刻以降は翌日（翌朝）の分として数える */
    private const val NIGHT_START_HOUR = 18L
    private val csv = CsvFile("bed_phone_log.csv", "開始,終了,秒数")

    private val _sessions = MutableStateFlow<List<BedPhoneSession>>(emptyList())
    /** 新しい順 */
    val sessions: StateFlow<List<BedPhoneSession>> = _sessions

    /** いま触っている回の開始時刻（UNIX秒）。触っていなければ 0（終わるまで保存しないので、画面ではこれを計測中として足す） */
    val activeSince = MutableStateFlow(0L)
    private var loadedModified = -1L

    fun file(context: Context) = csv.file(context)

    /** ファイルを読む（前回読んだ後に変わっていなければ何もしない） */
    @Synchronized
    fun load(context: Context) {
        val modified = csv.lastModified(context)
        if (modified == loadedModified) return
        loadedModified = modified
        val rows = try {
            csv.read(context)
        } catch (e: Exception) {
            Log.w(TAG, "read failed: $e")
            return
        }
        _sessions.value = rows
            .mapNotNull { c ->
                runCatching { BedPhoneSession(CsvFile.parseTime(c[0]), CsvFile.parseTime(c[1])) }.getOrElse {
                    Log.w(TAG, "skip line: $c ($it)")
                    null
                }
            }
            .sortedByDescending { it.startAt }
    }

    /** 1回追加する。短すぎるもの・同じものは入れない */
    @Synchronized
    fun add(context: Context, session: BedPhoneSession): Boolean {
        if (session.sec < MIN_SEC) return false
        load(context)
        if (_sessions.value.any { it.startAt == session.startAt }) return false
        save(context, (_sessions.value + session).sortedByDescending { it.startAt })
        return true
    }

    @Synchronized
    fun clear(context: Context): Int {
        load(context)
        val n = _sessions.value.size
        save(context, emptyList())
        return n
    }

    private fun save(context: Context, list: List<BedPhoneSession>) {
        csv.write(
            context,
            list.sortedBy { it.startAt }.map {
                listOf(CsvFile.formatTime(it.startAt), CsvFile.formatTime(it.endAt), it.sec.toString())
            },
        )
        _sessions.value = list
        loadedModified = csv.lastModified(context)
    }

    /** その回を数える日付（夜は翌朝の日付） */
    fun dayOf(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochSecond(epochSec).atZone(zone).plusHours(24 - NIGHT_START_HOUR).toLocalDate()

    /** 日付 → 合計 */
    fun byDay(sessions: List<BedPhoneSession>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, BedPhoneTotal> =
        sessions.groupBy { dayOf(it.startAt, zone) }
            .mapValues { (_, list) -> BedPhoneTotal(list.size, list.sumOf { it.sec }) }
}
