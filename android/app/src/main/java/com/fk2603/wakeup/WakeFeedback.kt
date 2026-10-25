package com.fk2603.wakeup

import android.content.Context
import com.fk2603.wakeup.ui.dopa.ClearUi
import com.fk2603.wakeup.ui.formatHours
import java.time.LocalDate

/**
 * 起きたあとのフィードバック画面（ClearScreen）の中身を作る
 * サービスが自動で出すオーバーレイと、アプリ内（デモ・重ねて表示が未許可のとき）で同じものを使う
 */
object WakeFeedback {
    /** 起床からこれより時間がたった記録は出さない（アプリを入れ直した直後に古い記録を出さない） */
    const val MAX_AGE_SEC = 12 * 3600L

    /** 1回の睡眠とみなす長さの上限（これより前に入ったベッドは別の晩） */
    private const val MAX_SLEEP_SEC = 18 * 3600L

    /** 記録を見分けるキー（M5Stack 再起動で id が戻るので ringAt と組み合わせる） */
    fun key(r: WakeResult) = "${r.id}-${r.ringAt}"

    /**
     * record: 今回の起床、awakeAt: ベッドを出た時刻、others: それより前の起床記録（XP・連続の計算用）
     * 就寝時刻は端末の睡眠記録から、鳴る前に入ったベッドのうち一番新しいものを使う
     */
    fun build(context: Context, record: Record, awakeAt: Long, others: List<Record>, demo: Boolean = false): ClearUi {
        SleepStore.load(context)
        RoutineStore.load(context)
        val sleptAt = SleepStore.records.value
            .firstOrNull { it.startAt <= record.ringAt && record.ringAt - it.startAt < MAX_SLEEP_SEC }
            ?.startAt
        val before = Gamification.progress(others)
        val after = Gamification.progress(others + record)
        val rank = Gamification.rankFor(record.durationSec)
        return ClearUi(
            seconds = record.durationSec,
            rank = rank,
            xpGain = Gamification.xpFor(record),
            streak = after.streak,
            level = before.level,
            levelXpBefore = before.levelXp,
            newBadge = (after.badges - before.badges).firstOrNull(),
            resleep = record.resleep,
            demo = demo,
            routine = RoutineStore.tasks.value.filter { it.appliesTo(LocalDate.now()) }.map { it.title },
            sleptAt = sleptAt,
            ringAt = record.ringAt,
            awakeAt = awakeAt,
            summary = summary(rank, record.resleep, sleptAt?.let { awakeAt - it }),
        )
    }

    /** 総評（起き方と睡眠時間をひとことずつ） */
    private fun summary(rank: Gamification.Rank, resleep: Boolean, sleepSec: Long?): String {
        val wake = when {
            resleep -> "二度寝した。明日はアラーム一発でベッドを出よう。"
            rank == Gamification.Rank.S -> "鳴って1分以内に離床。文句なしの朝。"
            rank == Gamification.Rank.A -> "3分以内に離床。いい朝。"
            rank == Gamification.Rank.B -> "離床まで少しかかった。次は3分以内を目指そう。"
            else -> "布団に負けかけ。明日はすぐ出よう。"
        }
        val sleep = when {
            sleepSec == null || sleepSec <= 0 -> null
            sleepSec < 6 * 3600 -> "睡眠 ${formatHours(sleepSec)}は短め。今夜は早めに寝よう。"
            sleepSec > 9 * 3600 -> "睡眠 ${formatHours(sleepSec)}。少し寝すぎ。"
            else -> "睡眠 ${formatHours(sleepSec)}。しっかり眠れた。"
        }
        return listOfNotNull(wake, sleep).joinToString("\n")
    }
}
