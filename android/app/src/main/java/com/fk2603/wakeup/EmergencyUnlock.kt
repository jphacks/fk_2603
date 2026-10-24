package com.fk2603.wakeup

import java.time.LocalDate
import java.time.ZoneId

/**
 * 緊急解除: どうしてもスマホが必要なとき、ベッドの上でも一定時間だけロックを外す。
 * - アラームは止めない（スマホが使えるようになるだけ）
 * - 使うと回数が記録され、「緊急解除しなかった日」の連続記録が 0 に戻る
 */
object EmergencyUnlock {
    fun today(now: Long = System.currentTimeMillis()): LocalDate =
        StreakMath.logicalDay(now, ZoneId.systemDefault(), Config.DAY_START_HOUR)

    /** 初めて使った日を記録する（連続記録の数え始め） */
    fun ensureFirstDay(prefs: Prefs) {
        if (prefs.firstDay == null) prefs.firstDay = today()
    }

    fun isActive(prefs: Prefs, now: Long = System.currentTimeMillis()): Boolean = now < prefs.emergencyUntilMs

    fun remainingSec(prefs: Prefs, now: Long = System.currentTimeMillis()): Long =
        ((prefs.emergencyUntilMs - now) / 1000).coerceAtLeast(0)

    fun streak(prefs: Prefs): Int {
        ensureFirstDay(prefs)
        return StreakMath.current(prefs.firstDay!!, prefs.lastUnlockDay, today())
    }

    fun bestStreak(prefs: Prefs): Int = maxOf(prefs.bestStreak, streak(prefs))

    fun trigger(prefs: Prefs, now: Long = System.currentTimeMillis()) {
        ensureFirstDay(prefs)
        prefs.bestStreak = bestStreak(prefs)
        prefs.unlockCount = prefs.unlockCount + 1
        prefs.lastUnlockDay = today(now)
        prefs.celebratedStreak = 0
        prefs.emergencyUntilMs = now + Config.EMERGENCY_UNLOCK_SEC * 1000
        prefs.userStatsDirty = true
    }
}
