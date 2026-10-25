package com.fk2603.wakeup

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/** ロックに登録できるアプリ（ホーム画面に並ぶもの） */
data class InstalledApp(val pkg: String, val label: String)

/**
 * 前面に出ているアプリがロック対象かを判定する（「使用状況へのアクセス」の許可が必要）
 * - ユーザーが登録したアプリ（blocked）だけが対象。それ以外はいつでも使える
 * - 自アプリ・ホーム画面・電話は登録の一覧に出さない
 */
class AppBlocker(private val context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private var foreground: String? = null
    private var lastQueryMs = 0L
    private var apps: List<InstalledApp> = emptyList()
    private var appsLoadedMs = 0L

    fun hasUsageAccess(): Boolean = hasUsageAccess(context)

    /** いま前面にあるアプリが登録済み（blocked にある）なら true */
    fun isForegroundBlocked(blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        val pkg = foregroundPackage() ?: return false
        return pkg in blocked
    }

    /** ホーム画面に並ぶアプリ（除外分を抜いてラベル順）。新しく入れたアプリを拾うため一定時間ごとに読み直す */
    fun launchableApps(): List<InstalledApp> {
        val now = System.currentTimeMillis()
        if (appsLoadedMs == 0L || now - appsLoadedMs >= APPS_CACHE_MS) {
            apps = loadLaunchableApps(context)
            appsLoadedMs = now
        }
        return apps
    }

    // 前回からの差分だけイベントを読み、最後に前面に来たアプリを覚えておく
    private fun foregroundPackage(): String? {
        val now = System.currentTimeMillis()
        val from = if (lastQueryMs == 0L) now - INITIAL_LOOKBACK_MS else lastQueryMs - OVERLAP_MS
        lastQueryMs = now
        val events = usm.queryEvents(from, now) ?: return foreground
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == RESUMED) foreground = e.packageName
        }
        return foreground
    }

    companion object {
        private const val INITIAL_LOOKBACK_MS = 60 * 60_000L
        private const val OVERLAP_MS = 2_000L
        private const val APPS_CACHE_MS = 60_000L

        @Suppress("DEPRECATION")
        private val RESUMED =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) UsageEvents.Event.ACTIVITY_RESUMED
            else UsageEvents.Event.MOVE_TO_FOREGROUND

        fun loadLaunchableApps(context: Context): List<InstalledApp> {
            val pm = context.packageManager
            val exempt = exemptPackages(context)
            return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first !in exempt }
                .distinctBy { it.first }
                .map { InstalledApp(it.first, it.second) }
                .sortedBy { it.label.lowercase() }
        }

        private fun exemptPackages(context: Context): Set<String> {
            val pm = context.packageManager
            val homes = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
            ).map { it.activityInfo.packageName }
            val dialers = pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0).map { it.activityInfo.packageName }
            return homes.toSet() + dialers + context.packageName + "com.android.systemui"
        }

        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
