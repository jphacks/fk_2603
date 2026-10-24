package com.fk2603.wakeup

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 「緊急解除しなかった日」の連続日数の計算（Android に依存しない） */
object StreakMath {
    /** 連続記録をお祝いする日数 */
    val MILESTONES = listOf(3, 7, 14, 30, 50, 100, 200, 365)

    /**
     * 1日の区切りを朝 [dayStartHour] 時にした日付。
     * 夜中 2 時の緊急解除は「前の晩」として数える。
     */
    fun logicalDay(epochMs: Long, zone: ZoneId, dayStartHour: Int): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).minusHours(dayStartHour.toLong()).toLocalDate()

    /**
     * 今の連続日数 = 最後に緊急解除した日の翌日（なければ使い始めた日）から、昨日までの日数。
     * 今日はまだ終わっていないので数えない。今日解除していたら 0。
     */
    fun current(firstDay: LocalDate, lastUnlockDay: LocalDate?, today: LocalDate): Int {
        if (lastUnlockDay == today) return 0
        val start = if (lastUnlockDay != null && !lastUnlockDay.isBefore(firstDay)) lastUnlockDay.plusDays(1) else firstDay
        return ChronoUnit.DAYS.between(start, today).toInt().coerceAtLeast(0)
    }

    /** 到達した中で一番大きいお祝いの日数（なければ 0） */
    fun reachedMilestone(streak: Int): Int = MILESTONES.lastOrNull { streak >= it } ?: 0
}
