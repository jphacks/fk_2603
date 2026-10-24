package com.fk2603.wakeup

import android.content.Context

/** 端末に保存する設定 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wakeup", Context.MODE_PRIVATE)

    var nickname: String
        get() = sp.getString("nickname", "")!!
        set(v) = sp.edit().putString("nickname", v.trim()).apply()

    /** アップロード済みの最後の記録（M5Stack 再起動で id が戻るので ringAt と組み合わせる） */
    var lastUploadedKey: String
        get() = sp.getString("lastUploadedKey", "")!!
        set(v) = sp.edit().putString("lastUploadedKey", v).apply()
}
