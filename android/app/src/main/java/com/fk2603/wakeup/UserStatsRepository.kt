package com.fk2603.wakeup

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

data class UserStats(
    val uid: String,
    val nickname: String,
    val unlockCount: Long,
    val streak: Int,
    val bestStreak: Int,
)

/**
 * 緊急解除の回数と連続記録を Firestore の users/{uid} に1件で持つ。
 * 連続日数は日付から計算できるので、毎日更新しなくても読む側で正しい値になる。
 */
object UserStatsRepository {
    private val users get() = FirebaseFirestore.getInstance().collection(Config.USERS_COLLECTION)

    /** 端末の値をそのまま書く（何度送っても同じ結果になる） */
    suspend fun upload(prefs: Prefs) {
        EmergencyUnlock.ensureFirstDay(prefs)
        val uid = RecordRepository.uid()
        users.document(uid).set(
            mapOf(
                "uid" to uid,
                "nickname" to prefs.nickname.ifBlank { "名無し" },
                "unlockCount" to prefs.unlockCount,
                "lastUnlockDay" to prefs.lastUnlockDay?.toString(),
                "firstDay" to prefs.firstDay.toString(),
                "bestStreak" to prefs.bestStreak,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun all(): List<UserStats> {
        RecordRepository.uid()
        val today = EmergencyUnlock.today()
        return users.limit(Config.RANKING_LIMIT * 4).get().await().documents.mapNotNull { d ->
            runCatching {
                val firstDay = LocalDate.parse(d.getString("firstDay")!!)
                val lastUnlock = d.getString("lastUnlockDay")?.let(LocalDate::parse)
                val streak = StreakMath.current(firstDay, lastUnlock, today)
                UserStats(
                    uid = d.getString("uid") ?: d.id,
                    nickname = d.getString("nickname") ?: "",
                    unlockCount = d.getLong("unlockCount") ?: 0,
                    streak = streak,
                    bestStreak = maxOf((d.getLong("bestStreak") ?: 0).toInt(), streak),
                )
            }.getOrNull()
        }
    }
}
