package com.fk2603.wakeup

import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * デモ用の1週間分の記録（assets/demo/）を、初回だけ本番と同じ保存処理で取り込む
 * - 起床: WakeStore.add()（M5Stack から届いた記録と同じ）
 * - 睡眠: SleepStore.apply()（M5Stack から届く S/R/E イベントと同じ）
 * - 朝のルーティン: RoutineStore.addTask() / setDone()（ホームで登録・チェックしたときと同じ）
 * 日付は「取り込んだ日から何日前」で書いてあるので、いつ取り込んでも直近1週間に並ぶ。
 * 取り込んだ後の実際の記録は、同じファイルに追記されていく
 */
object DemoSeed {
    private const val TAG = "DemoSeed"
    private const val WAKE_FILE = "demo/wake_records.csv"
    private const val SLEEP_FILE = "demo/sleep_events.csv"
    private const val ROUTINE_TASK_FILE = "demo/routine_tasks.csv"
    private const val ROUTINE_LOG_FILE = "demo/routine_log.csv"
    private const val BED_PHONE_FILE = "demo/bed_phone.csv"

    @Synchronized
    fun importOnce(context: Context, prefs: Prefs = Prefs(context)) {
        if (prefs.demoImported) return
        try {
            val today = LocalDate.now()
            var wakes = 0
            rows(context, WAKE_FILE).forEachIndexed { i, c ->
                val ringAt = epoch(today, c[0], c[1])
                val sec = c[2].toLong()
                val entry = WakeEntry("demo-${i + 1}", ringAt, ringAt + sec, sec, c[3] == "1", WakeStore.SOURCE_DEMO)
                if (WakeStore.add(context, entry)) wakes++
            }
            val events = rows(context, SLEEP_FILE)
            events.forEachIndexed { i, c ->
                SleepStore.apply(context, SleepEvent(id = -(i + 1L), type = c[2][0], at = epoch(today, c[0], c[1])))
            }
            prefs.demoImported = true
            Log.i(TAG, "imported $wakes wake records, ${events.size} sleep events")
        } catch (e: Exception) {
            // 取り込めなければ次の起動でもう一度試す
            Log.w(TAG, "import failed: $e")
        }
    }

    /** デモ用の朝のルーティン（定義と1週間分のチェック）を、初回だけ本番と同じ RoutineStore で取り込む */
    @Synchronized
    fun importRoutinesOnce(context: Context, prefs: Prefs = Prefs(context)) {
        if (prefs.demoRoutinesImported) return
        try {
            val tasks = rows(context, ROUTINE_TASK_FILE).mapNotNull { c ->
                val days = c.getOrElse(1) { "1234567" }.mapNotNull { it.digitToIntOrNull() }.filter { it in 1..7 }.toSet()
                RoutineStore.addTask(context, c[0], days, null)
            }
            val today = LocalDate.now()
            // 7日前〜昨日の、当てはまるタスクを書き出してから、完了したものにチェックを入れる
            (1L..7L).forEach { RoutineStore.ensureDay(context, today.minusDays(it)) }
            rows(context, ROUTINE_LOG_FILE).forEach { c ->
                val task = tasks.find { it.title == c[1] } ?: return@forEach
                RoutineStore.setDone(context, today.minusDays(c[0].toLong()), task.id, true)
            }
            prefs.demoRoutinesImported = true
            Log.i(TAG, "imported ${tasks.size} routine tasks")
        } catch (e: Exception) {
            Log.w(TAG, "routine import failed: $e")
        }
    }

    /** デモ用の「ベッドの上でスマホを触った」記録を、初回だけ本番と同じ BedPhoneLog.add() で取り込む */
    @Synchronized
    fun importBedPhoneOnce(context: Context, prefs: Prefs = Prefs(context)) {
        if (prefs.demoBedPhoneImported) return
        try {
            val today = LocalDate.now()
            var n = 0
            rows(context, BED_PHONE_FILE).forEach { c ->
                val start = epoch(today, c[0], c[1])
                if (BedPhoneLog.add(context, BedPhoneSession(start, start + c[2].toLong() * 60))) n++
            }
            prefs.demoBedPhoneImported = true
            Log.i(TAG, "imported $n bed phone sessions")
        } catch (e: Exception) {
            Log.w(TAG, "bed phone import failed: $e")
        }
    }

    // 「#」で始まる行と見出し行（最初の行）を飛ばし、カンマで分ける
    fun rows(context: Context, path: String): List<List<String>> =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .drop(1)
            .map { line -> line.split(",").map { it.trim() } }

    private fun epoch(today: LocalDate, daysAgo: String, time: String): Long =
        epoch(today.minusDays(daysAgo.toLong()), LocalTime.parse(time))

    private fun epoch(day: LocalDate, time: LocalTime): Long = day.atTime(time).atZone(ZoneId.systemDefault()).toEpochSecond()
}
