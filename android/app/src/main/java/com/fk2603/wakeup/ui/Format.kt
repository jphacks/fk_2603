package com.fk2603.wakeup.ui

import com.fk2603.wakeup.SleepRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun formatDuration(sec: Long): String =
    if (sec >= 60) "${sec / 60}分${"%02d".format(sec % 60)}秒" else "${sec}秒"

/** 睡眠時間など長い時間（例: 7時間55分） */
fun formatHours(sec: Long): String = "${sec / 3600}時間${(sec % 3600) / 60}分"

/** 例: 10/24 23:10 → 07:05（7時間55分）二度寝 2回 */
fun formatSleep(r: SleepRecord): String {
    val end = when {
        r.isSleeping -> "記録中"
        r.endAt < 0 -> "起床不明"
        else -> "${formatEpoch(r.endAt, "HH:mm")}（${formatHours(r.endAt - r.startAt)}）"
    }
    return "${formatEpoch(r.startAt)} → $end" + if (r.resleepCount > 0) " 二度寝 ${r.resleepCount}回" else ""
}

fun formatReturns(returnsAt: List<Long>): String? =
    if (returnsAt.isEmpty()) null else "戻り " + returnsAt.joinToString(", ") { formatEpoch(it, "HH:mm") }

fun formatEpoch(epochSec: Long, pattern: String = "M/d HH:mm"): String =
    if (epochSec <= 0) "-"
    else DateTimeFormatter.ofPattern(pattern)
        .format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

fun stateLabel(state: String): String = when (state) {
    "IDLE" -> "待機中"
    "ARMED" -> "アラームセット済み"
    "WAITING" -> "アラーム時刻（ベッドに入ると鳴る）"
    "RINGING" -> "鳴動中（ベッドを出て5秒で止まる）"
    "AWAKE" -> "起床（二度寝監視中）"
    "RESLEEP_ARMED" -> "二度寝検出 → 設定した時間で再アラーム"
    else -> state
}
