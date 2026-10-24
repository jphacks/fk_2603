package com.fk2603.wakeup

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
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

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

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

/** 分析用の保存データ（SharedPreferences） */
class ScreenTimeStore(context: Context) {
    private val sp = context.getSharedPreferences("screen_time", Context.MODE_PRIVATE)

    /** 導入時刻（ms）。0 はまだ */
    var installMs: Long
        get() = sp.getLong("installMs", 0)
        set(v) = sp.edit().putLong("installMs", v).apply()

    val installDay: LocalDate?
        get() = installMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }

    var baseline: Baseline?
        get() = sp.getString("baseline", null)?.let {
            val j = JSONObject(it)
            Baseline(j.getLong("avg"), j.getLong("from"), j.getLong("to"), j.getDouble("days"), j.getString("source"))
        }
        set(v) = sp.edit().putString(
            "baseline",
            v?.let {
                JSONObject().put("avg", it.avgMs).put("from", it.fromMs).put("to", it.toMs)
                    .put("days", it.days).put("source", it.source).toString()
            },
        ).apply()

    var lastSnapshotMs: Long
        get() = sp.getLong("lastSnapshotMs", 0)
        set(v) = sp.edit().putLong("lastSnapshotMs", v).apply()

    fun loadDaily(): Map<LocalDate, Long> {
        val o = JSONObject(sp.getString("daily", "{}")!!)
        return o.keys().asSequence().associate { LocalDate.parse(it) to o.getLong(it) }.toSortedMap()
    }

    /** Android から読めた日の値で上書きする（読めなくなった古い日は保存済みの値を残す） */
    fun mergeDaily(fresh: Map<LocalDate, Long>) {
        val o = JSONObject(sp.getString("daily", "{}")!!)
        fresh.forEach { (d, ms) -> o.put(d.toString(), ms) }
        sp.edit().putString("daily", o.toString()).apply()
    }
}
