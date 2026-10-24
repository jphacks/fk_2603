package com.fk2603.wakeup

/**
 * アプリ側の設定値（ここを変更して調整する）
 *
 * 起床判定・二度寝監視の時間（WAKE_CONFIRM_SEC, RESLEEP_WINDOW_SEC など）は
 * M5Stack 側 arduino/wakeup_alarm/wakeup_alarm.ino の上部で設定する。
 */
object Config {
    /** M5Stack を探すスキャンの長さ */
    const val BLE_SCAN_TIMEOUT_MS = 10_000L

    /** 接続してからサービスを見つけるまでの上限 */
    const val BLE_CONNECT_TIMEOUT_MS = 15_000L

    /** コマンドを送ってから結果が返るまでの上限 */
    const val BLE_COMMAND_TIMEOUT_MS = 5_000L

    /** 接続が切れてから再接続を試みるまで */
    const val BLE_RECONNECT_DELAY_MS = 3_000L

    /** この時間 M5Stack から状態が届かなければ接続をやり直す（M5Stack は1秒ごとに送る） */
    const val BLE_STALE_MS = 10_000L

    /** スマホの時刻を M5Stack に送る間隔（M5Stack は Wi-Fi がないので NTP の代わり） */
    const val TIME_SYNC_INTERVAL_MS = 4 * 60_000L

    /** ロック中に M5Stack と通信できない状態がこの時間続いたらロックを解除する（電源断で永久ロックを防ぐ） */
    const val UNLOCK_IF_UNREACHABLE_SEC = 5 * 60L

    /** ランキングに表示する件数 */
    const val RANKING_LIMIT = 50L

    /** Firestore のコレクション名 */
    const val RECORDS_COLLECTION = "records"
}
