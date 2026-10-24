package com.fk2603.wakeup

import java.time.DayOfWeek
import java.time.LocalDate

/** Android がまとめた1区間（週・月など）の合計利用時間 */
data class SpanTotal(val startMs: Long, val endMs: Long, val totalMs: Long)

/** 導入前の基準（1日あたりの平均） */
data class Baseline(
    val avgMs: Long,
    val fromMs: Long,
    val toMs: Long,
    val days: Double,
    /** "weekly" / "monthly" / "daily" */
    val source: String,
)

data class WeekdayRow(
    val dow: DayOfWeek,
    val beforeAvgMs: Long?,
    val beforeDays: Int,
    val afterAvgMs: Long?,
    val afterDays: Int,
)

/** スマホ全体の利用時間を、導入前と導入後で比べる計算（Android に依存しない） */
object ScreenTimeMath {
    private const val DAY_MS = 86_400_000L

    /** 基準に使う期間（導入前のおよそ1か月） */
    const val BASELINE_LOOKBACK_DAYS = 31L

    /**
     * 区間ごとの合計から、導入前の1日平均を出す。
     * - 導入時刻をまたぐ区間（導入後が混ざる）は使わない
     * - 導入前 [lookbackDays] 日より前に始まる区間は使わない
     */
    fun baseline(spans: List<SpanTotal>, installMs: Long, source: String, lookbackDays: Long = BASELINE_LOOKBACK_DAYS): Baseline? {
        val use = spans.filter { it.endMs <= installMs && it.startMs >= installMs - lookbackDays * DAY_MS && it.endMs > it.startMs }
        if (use.isEmpty()) return null
        val days = use.sumOf { it.endMs - it.startMs } / DAY_MS.toDouble()
        if (days < 1.0) return null
        return Baseline(
            avgMs = (use.sumOf { it.totalMs } / days).toLong(),
            fromMs = use.minOf { it.startMs },
            toMs = use.maxOf { it.endMs },
            days = days,
            source = source,
        )
    }

    /** 日ごとの値だけで基準を出す（週・月の記録が取れなかったときの予備） */
    fun baselineFromDaily(daily: Map<LocalDate, Long>, installDay: LocalDate, toMs: (LocalDate) -> Long): Baseline? {
        val before = daily.filterKeys { it.isBefore(installDay) && !it.isBefore(installDay.minusDays(BASELINE_LOOKBACK_DAYS)) }
        if (before.isEmpty()) return null
        return Baseline(
            avgMs = before.values.average().toLong(),
            fromMs = toMs(before.keys.min()),
            toMs = toMs(before.keys.max().plusDays(1)),
            days = before.size.toDouble(),
            source = "daily",
        )
    }

    /**
     * 曜日ごとの平均。導入日（前後が混ざる）と今日（途中）は入れない。
     */
    fun byWeekday(daily: Map<LocalDate, Long>, installDay: LocalDate, today: LocalDate): List<WeekdayRow> {
        val before = daily.filterKeys { it.isBefore(installDay) }
        val after = daily.filterKeys { it.isAfter(installDay) && it.isBefore(today) }
        return DayOfWeek.values().map { dow ->
            val b = before.filterKeys { it.dayOfWeek == dow }.values
            val a = after.filterKeys { it.dayOfWeek == dow }.values
            WeekdayRow(
                dow,
                b.takeIf { it.isNotEmpty() }?.average()?.toLong(), b.size,
                a.takeIf { it.isNotEmpty() }?.average()?.toLong(), a.size,
            )
        }
    }

    /** 導入後の日（導入日と今日を除く） */
    fun afterDays(daily: Map<LocalDate, Long>, installDay: LocalDate, today: LocalDate): Map<LocalDate, Long> =
        daily.filterKeys { it.isAfter(installDay) && it.isBefore(today) }

    fun changePercent(baselineMs: Long, valueMs: Long): Int? =
        if (baselineMs <= 0) null else Math.round((valueMs - baselineMs) * 100.0 / baselineMs).toInt()
}
