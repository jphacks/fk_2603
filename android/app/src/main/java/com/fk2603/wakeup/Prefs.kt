package com.fk2603.wakeup

import android.content.Context
import java.time.LocalDate

/** 端末に保存する設定 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wakeup", Context.MODE_PRIVATE)

    var nickname: String
        get() = sp.getString("nickname", "")!!
        set(v) {
            sp.edit().putString("nickname", v.trim()).apply()
            userStatsDirty = true
        }

    /** アップロード済みの最後の記録（M5Stack 再起動で id が戻るので ringAt と組み合わせる） */
    var lastUploadedKey: String
        get() = sp.getString("lastUploadedKey", "")!!
        set(v) = sp.edit().putString("lastUploadedKey", v).apply()

    // ---- 緊急解除と連続記録 ----

    /** 緊急解除が終わる時刻（ms）。これより前はロックしない */
    var emergencyUntilMs: Long
        get() = sp.getLong("emergencyUntilMs", 0)
        set(v) = sp.edit().putLong("emergencyUntilMs", v).apply()

    var unlockCount: Long
        get() = sp.getLong("unlockCount", 0)
        set(v) = sp.edit().putLong("unlockCount", v).apply()

    /** 最後に緊急解除した日（1日の区切りは Config.DAY_START_HOUR） */
    var lastUnlockDay: LocalDate?
        get() = sp.getString("lastUnlockDay", null)?.let(LocalDate::parse)
        set(v) = sp.edit().putString("lastUnlockDay", v?.toString()).apply()

    /** 使い始めた日（連続記録の数え始め） */
    var firstDay: LocalDate?
        get() = sp.getString("firstDay", null)?.let(LocalDate::parse)
        set(v) = sp.edit().putString("firstDay", v?.toString()).apply()

    var bestStreak: Int
        get() = sp.getInt("bestStreak", 0)
        set(v) = sp.edit().putInt("bestStreak", v).apply()

    /** お祝い済みの連続日数（同じお祝いを何度も出さない） */
    var celebratedStreak: Int
        get() = sp.getInt("celebratedStreak", 0)
        set(v) = sp.edit().putInt("celebratedStreak", v).apply()

    /** Firestore に送っていない変更があるか */
    var userStatsDirty: Boolean
        get() = sp.getBoolean("userStatsDirty", true)
        set(v) = sp.edit().putBoolean("userStatsDirty", v).apply()

    var lastUserStatsSyncMs: Long
        get() = sp.getLong("lastUserStatsSyncMs", 0)
        set(v) = sp.edit().putLong("lastUserStatsSyncMs", v).apply()
}
