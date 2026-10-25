package com.fk2603.wakeup.ui.dopa

import com.fk2603.wakeup.BedPhoneSession
import com.fk2603.wakeup.Gamification
import com.fk2603.wakeup.LocalAlarm
import com.fk2603.wakeup.M5Config
import com.fk2603.wakeup.Record
import com.fk2603.wakeup.RoutineDay
import com.fk2603.wakeup.RoutineTask
import com.fk2603.wakeup.SleepRecord
import com.fk2603.wakeup.ui.EmergencyStats

// 画面はここにある状態だけを受け取って描く（バックとの接続は AppViewModel / LockOverlay で行う）

/** 権限などの「やってほしいこと」 */
enum class SetupWarning(val short: String, val text: String, val action: String) {
    OVERLAY("重ねて表示", "ロックを表示するため「他のアプリの上に重ねて表示」を許可してください", "設定を開く"),
    BATTERY("電池の最適化", "画面オフ中に監視が止まらないよう、電池の最適化を無効にしてください", "無効にする"),
    USAGE("使用状況へのアクセス", "登録したアプリだけをロックするため「使用状況へのアクセス」を許可してください（未許可だとベッドの上ではスマホ全体をロック）", "設定を開く"),
}

data class HomeUi(
    val nickname: String,
    val level: Int,
    val levelXp: Int,
    val streak: Int,
    val connected: Boolean,
    val inBed: Boolean,
    val state: String?,
    /** 次に鳴るアラーム（UNIX秒）。なければ null */
    val alarmEpoch: Long?,
    /** スマホ側で持つアラーム（複数・曜日つき）。一番近い1件を M5Stack に送る */
    val localAlarms: List<LocalAlarm> = emptyList(),
    /** 朝のルーティンの対象日（次のアラームの日、なければ今日） */
    val routineDate: String = "",
    val routineTasks: List<RoutineTask> = emptyList(),
    val completedTaskIds: Set<Long> = emptySet(),
    /** アラームを変更できない（鳴動中など） */
    val alarmLocked: Boolean,
    /** 封印中のアプリ数 */
    val blockedCount: Int,
    val warnings: List<SetupWarning>,
    val message: String?,
    /** M5Stack を QR で登録済み（false の間だけ「デバイスを追加」を出す） */
    val paired: Boolean = true,
)

data class StatsUi(
    val nickname: String,
    val progress: Gamification.Progress,
    /** 朝のルーティンの記録（新しい順） */
    val routineHistory: List<RoutineDay>,
    /** 起床記録（二度寝の回数・日ごとの二度寝の有無） */
    val records: List<Record> = emptyList(),
    /** ベッドの上でスマホを触った記録（新しい順） */
    val bedPhone: List<BedPhoneSession> = emptyList(),
    /** いま触っている回（まだ保存していない。endAt = 今） */
    val bedPhoneActive: BedPhoneSession? = null,
    val loading: Boolean,
    val message: String?,
)

/** デバイス画面の封印リストの1行 */
data class BlockApp(val pkg: String, val label: String, val blocked: Boolean)

data class DeviceUi(
    val connected: Boolean,
    val lastStatusAgoSec: Long?,
    val state: String?,
    val volume: Int?,
    val pressureRaw: Int?,
    val inBed: Boolean,
    /** null = M5Stack が古いファームで設定を変えられない */
    val config: M5Config?,
    /** ベッドにいる間・アラーム中は M5Stack が設定変更を受け付けない */
    val configLocked: Boolean,
    /** 封印の対象にできるアプリと、いま封印しているか */
    val apps: List<BlockApp>,
    val usageAccess: Boolean,
    val nickname: String,
    val sleeps: List<SleepRecord>,
    val message: String?,
    /** QR で登録した M5Stack の ID。未登録なら null */
    val pairedDevId: String? = null,
    /** 緊急解除の回数・アプリ別の回数 */
    val emergency: EmergencyStats? = null,
)

/** スマホ使用データ（Android の利用時間の記録）が読めるかの確認画面 */
data class ScreenTimeUi(
    val access: Boolean,
    val loading: Boolean,
    /** Android に残っている記録の量 */
    val retentionDaily: Int?,
    val retentionWeekly: Int?,
    val retentionMonthly: Int?,
    /** Android から今読めた日ごとの合計（新しい順） */
    val androidDaily: List<Pair<java.time.LocalDate, Long>>,
    /** 端末に貯めた日ごとの合計の日数 */
    val storedDays: Int,
    val installDay: java.time.LocalDate?,
    /** 導入前の1日平均（ms）と、その元になった記録（weekly / monthly / daily） */
    val baselineAvgMs: Long?,
    val baselineSource: String?,
    val baselineDays: Double?,
    /** 緊急解除 */
    val unlockCount: Long,
    val noUnlockStreak: Int,
    val bestStreak: Int,
    val error: String?,
)

/** 起床クリア画面 */
data class ClearUi(
    val seconds: Long,
    val rank: Gamification.Rank,
    val xpGain: Int,
    val streak: Int,
    val level: Int,
    /** 獲得前のレベル内 XP（0-99） */
    val levelXpBefore: Int,
    val newBadge: Gamification.Badge?,
    val resleep: Boolean,
    val demo: Boolean = false,
    /** 今日の予定（朝のルーティン。これを目標に動き出す） */
    val routine: List<String> = emptyList(),
    /** ベッドに入った時刻（睡眠記録がなければ null） */
    val sleptAt: Long? = null,
    /** アラームが鳴った時刻 */
    val ringAt: Long = 0,
    /** ベッドを出た時刻 */
    val awakeAt: Long = 0,
    /** 総評（起き方と睡眠時間） */
    val summary: String = "",
)

/** オーバーレイ（ロック中の画面） */
data class LockUi(
    val alarm: Boolean,
    val resleep: Boolean,
    val connected: Boolean,
    /** ベッドに入ってからの秒数 */
    val inBedSec: Long,
    val pressureRaw: Int,
    val threshold: Int,
    val inBed: Boolean,
    /** ロックに登録したアプリ（アイコン表示用に先頭数件） */
    val blockedPkgs: List<String>,
    /** 緊急解除しなかった日の連続日数 */
    val streak: Int = 0,
    /** true = 緊急解除で開いているアプリだけを解除する（false = 全体） */
    val appOnly: Boolean = false,
    /** 鳴動中にベッドを出ている（「アラームを止める」を押せる） */
    val canStop: Boolean = false,
    /** このまま出ていれば自動で止まるまでの秒数（わからなければ null） */
    val autoStopInSec: Long? = null,
)
