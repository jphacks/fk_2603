package com.fk2603.wakeup

import android.content.Context
import java.time.LocalDate

/** 端末に保存する設定 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wakeup", Context.MODE_PRIVATE)

    var nickname: String
        get() = sp.getString("nickname", "")!!
        set(v) = sp.edit().putString("nickname", v.trim()).apply()

    /** 起床クリア画面を表示済みの最後の記録（キーは "id-ringAt"。M5Stack 再起動で id が戻るので ringAt と組み合わせる） */
    var lastSeenResultKey: String
        get() = sp.getString("lastSeenResultKey", "")!!
        set(v) = sp.edit().putString("lastSeenResultKey", v).apply()

    /** ベッドにいる間も使えるアプリ（ユーザーが封印を解除したもの）。ここにないアプリはすべて封印する */
    var allowedApps: Set<String>
        get() = sp.getStringSet("allowedApps", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("allowedApps", v).apply()

    /** デモ用の1週間分の記録を取り込んだか（取り込みは1回だけ） */
    var demoImported: Boolean
        get() = sp.getBoolean("demoImported", false)
        set(v) = sp.edit().putBoolean("demoImported", v).apply()

    /** デモ用のルーティン（定義と1週間分のチェック）を取り込んだか */
    var demoRoutinesImported: Boolean
        get() = sp.getBoolean("demoRoutinesImported", false)
        set(v) = sp.edit().putBoolean("demoRoutinesImported", v).apply()

    // ---- 緊急解除と連続記録 ----

    /** 緊急解除が終わる時刻（ms）。これより前はロックしない */
    var emergencyUntilMs: Long
        get() = sp.getLong("emergencyUntilMs", 0)
        set(v) = sp.edit().putLong("emergencyUntilMs", v).apply()

    /** 使い始めた日（連続記録の数え始め） */
    var firstDay: LocalDate?
        get() = sp.getString("firstDay", null)?.let(LocalDate::parse)
        set(v) = sp.edit().putString("firstDay", v?.toString()).apply()

    // 緊急解除の記録は emergency_log.csv に移した。以下は前の形式の値（CSV へ移すときだけ読む）
    var legacyUnlockCount: Long
        get() = sp.getLong("unlockCount", 0)
        set(v) = sp.edit().putLong("unlockCount", v).apply()

    val legacyLastUnlockDay: LocalDate?
        get() = sp.getString("lastUnlockDay", null)?.let(LocalDate::parse)

    val legacyBestStreak: Int
        get() = sp.getInt("bestStreak", 0)

    /** お祝い済みの連続日数（同じお祝いを何度も出さない） */
    var celebratedStreak: Int
        get() = sp.getInt("celebratedStreak", 0)
        set(v) = sp.edit().putInt("celebratedStreak", v).apply()

}
