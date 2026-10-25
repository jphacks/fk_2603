package com.fk2603.wakeup.ui.dopa

import com.fk2603.wakeup.BlockCategory
import com.fk2603.wakeup.Gamification
import com.fk2603.wakeup.M5Config
import com.fk2603.wakeup.PressureSample
import com.fk2603.wakeup.SleepRecord

// 画面はここにある状態だけを受け取って描く（バックとの接続は AppViewModel / LockOverlay で行う）

/** 権限などの「やってほしいこと」 */
enum class SetupWarning(val text: String, val action: String) {
    OVERLAY("ロックを表示するため「他のアプリの上に重ねて表示」を許可してください", "設定を開く"),
    BATTERY("画面オフ中に監視が止まらないよう、電池の最適化を無効にしてください", "無効にする"),
    USAGE("SNS や動画だけを封印するため「使用状況へのアクセス」を許可してください（未許可だとベッドの上ではスマホ全体をロック）", "設定を開く"),
}

data class HomeUi(
    val nickname: String,
    val level: Int,
    val levelXp: Int,
    val streak: Int,
    val connected: Boolean,
    val inBed: Boolean,
    val state: String?,
    /** セット中のアラーム（UNIX秒）。なければ null */
    val alarmEpoch: Long?,
    val localAlarms: List<LocalAlarm> = emptyList(),
    val routineDate: String = "",
    val routineTasks: List<MorningTask> = emptyList(),
    val completedTaskIds: Set<Long> = emptySet(),
    /** アラームを変更できない（鳴動中など） */
    val alarmLocked: Boolean,
    val blocked: Set<BlockCategory>,
    val warnings: List<SetupWarning>,
    val message: String?,
)

data class RankRow(val name: String, val sec: Long, val me: Boolean)

data class StatsUi(
    val nickname: String,
    val progress: Gamification.Progress,
    val ranking: List<RankRow>,
    val sleeps: List<SleepRecord>,
    val loading: Boolean,
    val message: String?,
)

data class DeviceUi(
    val connected: Boolean,
    val lastStatusAgoSec: Long?,
    val state: String?,
    val volume: Int?,
    val pressureRaw: Int?,
    val inBed: Boolean,
    val samples: List<PressureSample>,
    /** 最新の睡眠（グラフのラベル用） */
    val sleepStartAt: Long?,
    val sleepEndAt: Long?,
    /** null = M5Stack が古いファームで設定を変えられない */
    val config: M5Config?,
    /** ベッドにいる間・アラーム中は M5Stack が設定変更を受け付けない */
    val configLocked: Boolean,
    val blocked: Set<BlockCategory>,
    val usageAccess: Boolean,
    val nickname: String,
    val message: String?,
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
    val blocked: Set<BlockCategory>,
)

/** スマホ側で管理するアラーム。曜日は月=1〜日=7、空なら1回のみ。 */
data class LocalAlarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val weekdays: Set<Int> = emptySet(),
    val enabled: Boolean = true,
)

fun LocalAlarm.nextEpoch(now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): Long? {
    if (!enabled) return null
    for (offset in 0..7) {
        val date = now.toLocalDate().plusDays(offset.toLong())
        if (weekdays.isNotEmpty() && date.dayOfWeek.value !in weekdays) continue
        val target = date.atTime(hour, minute).atZone(now.zone)
        if (target.isAfter(now)) return target.toEpochSecond()
    }
    return null
}

/** 毎週繰り返すタスク、または指定日の単発タスク。曜日は月=1〜日=7。 */
data class MorningTask(
    val id: Long,
    val title: String,
    val weekdays: Set<Int> = (1..7).toSet(),
    val onlyDate: String? = null,
)

fun MorningTask.appliesTo(date: java.time.LocalDate): Boolean =
    if (onlyDate != null) onlyDate == date.toString() else date.dayOfWeek.value in weekdays
