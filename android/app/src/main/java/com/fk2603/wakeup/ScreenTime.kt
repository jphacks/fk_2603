package com.fk2603.wakeup

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Android に残っていた記録の量（端末によって違うので画面に出して確かめる） */
data class Retention(val dailyDays: Int, val weeklyCount: Int, val monthlyCount: Int)

/**
 * スマホ全体（＝このアプリが止めている全アプリ）の1日の合計利用時間を読む。
 *
 * - UsageStatsManager.queryUsageStats の totalTimeInForeground を使う。
 *   Android 10 で増えた totalTimeVisible や、イベント名の変更（ACTIVITY_RESUMED など）に左右されないよう、
 *   Android 8〜最新で同じ意味の値だけを使う（Digital Wellbeing の表示とは少しずれることがある）
 * - ホームアプリ・システム UI・このアプリ自身は「利用」に入れない
 * - Android が日ごとの記録を残すのは数日〜1週間ほどなので、1時間ごとに端末内へ写して貯める
 * - 導入前の「1か月平均」は、日ごとより長く残る週ごと（なければ月ごと）の記録から作り、導入時に保存する
 */
object ScreenTime {
    private const val DAY_MS = 86_400_000L
    const val SNAPSHOT_INTERVAL_MS = 60 * 60_000L

    fun hasAccess(context: Context): Boolean = AppBlocker.hasUsageAccess(context)

    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    private fun excluded(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = context.packageManager
            .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
        return launchers.toSet() + context.packageName + "com.android.systemui"
    }

    private fun query(context: Context, interval: Int, fromMs: Long, toMs: Long): List<UsageStats> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val skip = excluded(context)
        return (usm.queryUsageStats(interval, fromMs, toMs) ?: emptyList()).filter { it.packageName !in skip }
    }

    /** 日付 → 合計(ms)。Android に残っている分だけ */
    fun readDaily(context: Context, days: Long = 40): Map<LocalDate, Long> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        return query(context, UsageStatsManager.INTERVAL_DAILY, now - days * DAY_MS, now)
            .groupBy { Instant.ofEpochMilli(it.firstTimeStamp).atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.sumOf { it.totalTimeInForeground } }
    }

    /** 週ごと・月ごとの区間の合計 */
    fun readSpans(context: Context, interval: Int, days: Long): List<SpanTotal> {
        val now = System.currentTimeMillis()
        return query(context, interval, now - days * DAY_MS, now)
            .groupBy { it.firstTimeStamp }
            .map { (start, list) -> SpanTotal(start, list.maxOf { it.lastTimeStamp }, list.sumOf { it.totalTimeInForeground }) }
            .sortedBy { it.startMs }
    }

    fun retention(context: Context): Retention = Retention(
        dailyDays = readDaily(context, 60).size,
        weeklyCount = readSpans(context, UsageStatsManager.INTERVAL_WEEKLY, 120).size,
        monthlyCount = readSpans(context, UsageStatsManager.INTERVAL_MONTHLY, 400).size,
    )

    /** 導入前の1か月平均を、残っている中で一番良い記録から作る */
    fun computeBaseline(context: Context, installMs: Long, daily: Map<LocalDate, Long>, installDay: LocalDate): Baseline? {
        val zone = ZoneId.systemDefault()
        return ScreenTimeMath.baseline(readSpans(context, UsageStatsManager.INTERVAL_WEEKLY, 70), installMs, "weekly")
            ?: ScreenTimeMath.baseline(
                readSpans(context, UsageStatsManager.INTERVAL_MONTHLY, 100), installMs, "monthly", lookbackDays = 62,
            )
            ?: ScreenTimeMath.baselineFromDaily(daily, installDay) { it.atStartOfDay(zone).toInstant().toEpochMilli() }
    }

    /**
     * Android の記録を端末内に写す。初回はその時刻を導入時刻にし、導入前の基準を保存する。
     * 許可がなければ何もしない。
     */
    fun snapshot(context: Context, store: ScreenTimeStore = ScreenTimeStore(context)) {
        store.lastSnapshotMs = System.currentTimeMillis()
        if (!hasAccess(context)) return
        store.mergeDaily(readDaily(context))
        if (store.installMs == 0L) resetInstall(context, store)
    }

    /** 今を導入時刻にして、導入前の基準を取り直す */
    fun resetInstall(context: Context, store: ScreenTimeStore) {
        val now = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        store.installMs = now
        store.baseline = computeBaseline(context, now, store.loadDaily(), day)
    }
}

/**
 * スマホ使用データの保存（記録は CSV、CsvFile の形式）
 * - screen_time_daily.csv: 日付,利用時間(ms)（1日1行。Android から読めた日の値で上書き、読めなくなった古い日は残す）
 * - screen_time_baseline.csv: 導入日時,導入前の1日平均(ms),期間開始,期間終了,日数,元データ（1行）
 * 最後に写した時刻は状態なので SharedPreferences に持つ。前の形式（SharedPreferences の JSON）は初回に CSV へ移す
 */
class ScreenTimeStore(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("screen_time", Context.MODE_PRIVATE)
    private val dailyCsv = CsvFile("screen_time_daily.csv", "日付,利用時間(ms)")
    private val baselineCsv = CsvFile("screen_time_baseline.csv", "導入日時,導入前の1日平均(ms),期間開始,期間終了,日数,元データ")

    init {
        migrate()
    }

    /** 導入時刻（ms）。0 はまだ */
    var installMs: Long
        get() = baselineRow()?.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { CsvFile.parseTime(it) * 1000 } ?: 0L
        set(v) = writeBaseline(v, baseline)

    val installDay: LocalDate?
        get() = installMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }

    var baseline: Baseline?
        get() = baselineRow()?.takeIf { it.size >= 6 && it[1].isNotEmpty() }?.let {
            Baseline(
                avgMs = it[1].toLong(),
                fromMs = CsvFile.parseTime(it[2]) * 1000,
                toMs = CsvFile.parseTime(it[3]) * 1000,
                days = it[4].toDouble(),
                source = it[5],
            )
        }
        set(v) = writeBaseline(installMs, v)

    var lastSnapshotMs: Long
        get() = sp.getLong("lastSnapshotMs", 0)
        set(v) = sp.edit().putLong("lastSnapshotMs", v).apply()

    fun loadDaily(): Map<LocalDate, Long> =
        dailyCsv.read(app).mapNotNull { c -> runCatching { LocalDate.parse(c[0]) to c[1].toLong() }.getOrNull() }
            .toMap().toSortedMap()

    /** Android から読めた日の値で上書きする（読めなくなった古い日は保存済みの値を残す） */
    fun mergeDaily(fresh: Map<LocalDate, Long>) {
        val merged = loadDaily() + fresh
        dailyCsv.write(app, merged.toSortedMap().map { (d, ms) -> listOf(d.toString(), ms.toString()) })
    }

    private fun baselineRow(): List<String>? = runCatching { baselineCsv.read(app).firstOrNull() }.getOrNull()

    private fun writeBaseline(installMs: Long, b: Baseline?) {
        val row = listOf(
            if (installMs > 0) CsvFile.formatTime(installMs / 1000) else "",
            b?.avgMs?.toString() ?: "",
            b?.let { CsvFile.formatTime(it.fromMs / 1000) } ?: "",
            b?.let { CsvFile.formatTime(it.toMs / 1000) } ?: "",
            b?.days?.toString() ?: "",
            b?.source ?: "",
        )
        baselineCsv.write(app, listOf(row))
    }

    private fun migrate() {
        val daily = sp.getString("daily", null)
        if (daily != null && !dailyCsv.exists(app)) {
            val o = JSONObject(daily)
            mergeDaily(o.keys().asSequence().associate { LocalDate.parse(it) to o.getLong(it) })
        }
        val install = sp.getLong("installMs", 0)
        if (install > 0 && !baselineCsv.exists(app)) {
            val b = sp.getString("baseline", null)?.let {
                val j = JSONObject(it)
                Baseline(j.getLong("avg"), j.getLong("from"), j.getLong("to"), j.getDouble("days"), j.getString("source"))
            }
            writeBaseline(install, b)
        }
        if (daily != null || install > 0) sp.edit().remove("daily").remove("installMs").remove("baseline").apply()
    }
}
