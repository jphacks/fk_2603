package com.fk2603.wakeup

import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 緊急解除: どうしてもスマホが必要なとき、ベッドの上でも一定時間だけロックを外す。
 * - アラームは止めない（スマホが使えるようになるだけ）
 * - 使った時刻を CSV ファイル emergency_log.csv（CsvFile の形式。列: 時刻）に1行ずつ残す
 * - 回数・最後に使った日・「緊急解除しなかった日」の連続記録は、この記録から計算する
 * 使い始めた日（連続記録の数え始め）と解除が終わる時刻は状態なので Prefs に持つ
 */
object EmergencyUnlock {
    private const val TAG = "EmergencyUnlock"
    private val csv = CsvFile("emergency_log.csv", "時刻")

    fun today(now: Long = System.currentTimeMillis()): LocalDate =
        StreakMath.logicalDay(now, ZoneId.systemDefault(), Config.DAY_START_HOUR)

    /** 初めて使った日を記録し、前の形式（Prefs）の記録があれば CSV に移す */
    @Synchronized
    fun ensureFirstDay(context: Context, prefs: Prefs) {
        if (prefs.firstDay == null) prefs.firstDay = today()
        migrate(context, prefs)
    }

    /** 緊急解除した時刻（UNIX秒、古い順） */
    fun log(context: Context): List<Long> = runCatching {
        csv.read(context).mapNotNull { c -> runCatching { CsvFile.parseTime(c[0]) }.getOrNull() }.sorted()
    }.getOrElse {
        Log.w(TAG, "read failed: $it")
        emptyList()
    }

    fun count(context: Context): Int = log(context).size

    /** 最後に緊急解除した日（1日の区切りは Config.DAY_START_HOUR） */
    fun lastUnlockDay(context: Context): LocalDate? = log(context).lastOrNull()?.let { today(it * 1000) }

    fun isActive(prefs: Prefs, now: Long = System.currentTimeMillis()): Boolean = now < prefs.emergencyUntilMs

    fun remainingSec(prefs: Prefs, now: Long = System.currentTimeMillis()): Long =
        ((prefs.emergencyUntilMs - now) / 1000).coerceAtLeast(0)

    fun streak(context: Context, prefs: Prefs): Int {
        ensureFirstDay(context, prefs)
        return StreakMath.current(prefs.firstDay!!, lastUnlockDay(context), today())
    }

    /** これまでで一番長かった連続日数（解除と解除の間の日数の最大と、今の連続） */
    fun bestStreak(context: Context, prefs: Prefs): Int {
        ensureFirstDay(context, prefs)
        var start = prefs.firstDay!!
        var best = 0
        log(context).map { today(it * 1000) }.distinct().forEach { day ->
            if (!day.isBefore(start)) best = maxOf(best, ChronoUnit.DAYS.between(start, day).toInt())
            start = day.plusDays(1)
        }
        return maxOf(best, streak(context, prefs), prefs.legacyBestStreak)
    }

    @Synchronized
    fun trigger(context: Context, prefs: Prefs, now: Long = System.currentTimeMillis()) {
        ensureFirstDay(context, prefs)
        val rows = log(context) + now / 1000
        csv.write(context, rows.map { listOf(CsvFile.formatTime(it)) })
        prefs.celebratedStreak = 0
        prefs.emergencyUntilMs = now + Config.EMERGENCY_UNLOCK_SEC * 1000
        Log.i(TAG, "emergency unlock #${rows.size}")
    }

    @Synchronized
    fun clear(context: Context): Int {
        val n = count(context)
        csv.write(context, emptyList())
        return n
    }

    // 前の形式では回数と最後の日だけを Prefs に持っていたので、その日の正午として回数分書き出す
    private fun migrate(context: Context, prefs: Prefs) {
        val count = prefs.legacyUnlockCount
        if (count <= 0 || csv.exists(context)) return
        val day = prefs.legacyLastUnlockDay ?: today()
        val at = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toEpochSecond()
        csv.write(context, List(count.toInt()) { listOf(CsvFile.formatTime(at)) })
        prefs.legacyUnlockCount = 0
        Log.i(TAG, "migrated $count unlocks to csv")
    }
}
