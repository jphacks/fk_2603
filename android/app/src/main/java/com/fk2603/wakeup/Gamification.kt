package com.fk2603.wakeup

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 起床記録から XP・レベル・連続日数・バッジを計算する（保存はせず、毎回記録から作り直す）
 * - 1回の起床（二度寝による再アラームを除く）で、離床までの秒数に応じたランクの XP がもらえる
 * - 1日の勝敗: 二度寝した日は LOSE、それ以外は最速の離床が WIN_SEC 以内なら WIN
 */
object Gamification {
    const val XP_PER_LEVEL = 100
    const val WIN_SEC = 5 * 60L

    enum class Rank(val xp: Int) { S(150), A(110), B(70), C(30) }

    fun rankFor(sec: Long): Rank = when {
        sec <= 60 -> Rank.S
        sec <= 180 -> Rank.A
        sec <= 420 -> Rank.B
        else -> Rank.C
    }

    fun xpFor(r: Record): Int = if (r.resleep) 0 else rankFor(r.durationSec).xp

    enum class Badge(val label: String, val desc: String, val hidden: Boolean = false) {
        FUTON_SLAYER("布団スレイヤー", "7日連続で3分以内に離床"),
        FIRST_PERIOD("1限の覇者", "9時前のアラームで10勝"),
        INSTANT("秒で起きる人", "30秒以内に離床"),
        DOPA_FAST("ドパ断ち", "二度寝なしで5日連続"),
        RESLEEP_KING("二度寝王", "二度寝10回", hidden = true),
        MORNING("朝活ガチ勢", "6時台に5回起床"),
    }

    /** 1日分（その日の記録をまとめたもの） */
    data class Day(val date: LocalDate, val bestSec: Long?, val resleep: Boolean) {
        val win: Boolean get() = !resleep && bestSec != null && bestSec <= WIN_SEC
    }

    data class Progress(
        val xp: Int,
        val streak: Int,
        val bestSec: Long?,
        val badges: Set<Badge>,
        /** いちばん最近とったバッジ（称号） */
        val title: Badge?,
        /** 直近7回の平均ランク */
        val recentRank: Rank?,
        /** 直近7日（6日前〜今日）。記録のない日は null */
        val week: List<Pair<LocalDate, Day?>>,
    ) {
        val level get() = xp / XP_PER_LEVEL
        val levelXp get() = xp % XP_PER_LEVEL
        val weekWins get() = week.count { it.second?.win == true }
        val weekLosses get() = week.count { it.second?.win == false }
    }

    fun progress(records: List<Record>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Progress {
        val sorted = records.sortedBy { it.ringAt }
        val days = days(sorted, zone)
        val wakes = sorted.filter { !it.resleep }
        // 記録を古い順に足していき、バッジが増えたところを「とった」とみなす
        var owned = emptySet<Badge>()
        var title: Badge? = null
        for (i in sorted.indices) {
            val now = badges(sorted.subList(0, i + 1), zone)
            (now - owned).lastOrNull()?.let { title = it }
            owned = now
        }
        val byDate = days.associateBy { it.date }
        return Progress(
            xp = sorted.sumOf { xpFor(it) },
            streak = streak(days, today),
            bestSec = wakes.minOfOrNull { it.durationSec },
            badges = owned,
            title = title,
            recentRank = wakes.takeLast(7).takeIf { it.isNotEmpty() }
                ?.let { recent -> rankFor(recent.sumOf { it.durationSec } / recent.size) },
            week = (6L downTo 0L).map { today.minusDays(it) }.map { it to byDate[it] },
        )
    }

    fun days(records: List<Record>, zone: ZoneId = ZoneId.systemDefault()): List<Day> =
        records.groupBy { localDate(it.ringAt, zone) }
            .map { (date, rs) ->
                Day(date, rs.filter { !it.resleep }.minOfOrNull { it.durationSec }, rs.any { it.resleep })
            }
            .sortedBy { it.date }

    /** 今日（記録がまだなければ昨日）からさかのぼって WIN が続いている日数 */
    private fun streak(days: List<Day>, today: LocalDate): Int {
        val byDate = days.associateBy { it.date }
        var d = if (byDate[today] == null) today.minusDays(1) else today
        var n = 0
        while (byDate[d]?.win == true) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    private fun badges(records: List<Record>, zone: ZoneId): Set<Badge> {
        val days = days(records, zone)
        val wakes = records.filter { !it.resleep }
        val result = mutableSetOf<Badge>()
        if (longestRun(days) { it.bestSec != null && it.bestSec <= 180 && !it.resleep } >= 7) result += Badge.FUTON_SLAYER
        if (days.count { d -> d.win && wakes.any { localDate(it.ringAt, zone) == d.date && hour(it.ringAt, zone) < 9 } } >= 10) {
            result += Badge.FIRST_PERIOD
        }
        if (wakes.any { it.durationSec <= 30 }) result += Badge.INSTANT
        if (longestRun(days) { !it.resleep } >= 5) result += Badge.DOPA_FAST
        if (records.count { it.resleep } >= 10) result += Badge.RESLEEP_KING
        if (wakes.count { hour(it.ringAt, zone) == 6 } >= 5) result += Badge.MORNING
        return result
    }

    // 日付が連続していて、すべて ok を満たす最長の日数
    private fun longestRun(days: List<Day>, ok: (Day) -> Boolean): Int {
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (ok(d)) (if (prev != null && d.date == prev.plusDays(1)) run + 1 else 1) else 0
            best = maxOf(best, run)
            prev = d.date
        }
        return best
    }

    private fun localDate(epochSec: Long, zone: ZoneId) = Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate()
    private fun hour(epochSec: Long, zone: ZoneId) = Instant.ofEpochSecond(epochSec).atZone(zone).hour
}
