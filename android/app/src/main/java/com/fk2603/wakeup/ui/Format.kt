package com.fk2603.wakeup.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun formatDuration(sec: Long): String =
    if (sec >= 60) "${sec / 60}分${"%02d".format(sec % 60)}秒" else "${sec}秒"

fun formatEpoch(epochSec: Long, pattern: String = "M/d HH:mm"): String =
    if (epochSec <= 0) "-"
    else DateTimeFormatter.ofPattern(pattern)
        .format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

fun stateLabel(state: String): String = when (state) {
    "IDLE" -> "待機中"
    "ARMED" -> "アラームセット済み"
    "WAITING" -> "アラーム時刻（ベッドに入ると鳴る）"
    "RINGING" -> "鳴動中（ベッドを出ると止まる）"
    "AWAKE" -> "起床（二度寝監視中）"
    "RESLEEP_ARMED" -> "二度寝検出 → 5分後に再アラーム"
    else -> state
}
