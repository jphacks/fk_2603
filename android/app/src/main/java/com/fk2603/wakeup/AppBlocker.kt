package com.fk2603.wakeup

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/** ベッドにいる間に封印するアプリの種類 */
enum class BlockCategory(val id: String, val label: String, val sub: String) {
    SHORT("short", "ショート動画", "縦スワイプ系すべて"),
    SNS("sns", "SNS", "タイムライン・ストーリー"),
    VIDEO("video", "動画アプリ", "長尺動画"),
    GAME("game", "ゲーム", "デイリーボーナス含む");

    companion object {
        val DEFAULT = setOf(SHORT, SNS, VIDEO)
    }
}

/**
 * 前面に出ているアプリが封印対象かを判定する（「使用状況へのアクセス」の許可が必要）
 * - アプリが申告しているカテゴリ（SOCIAL / VIDEO / GAME）と、主要アプリのパッケージ名で判定する
 * - 自アプリ・ホーム画面・電話は対象にしない
 */
class AppBlocker(private val context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val pm = context.packageManager
    private val categoryCache = mutableMapOf<String, Set<BlockCategory>>()
    private var foreground: String? = null
    private var lastQueryMs = 0L

    fun hasUsageAccess(): Boolean = hasUsageAccess(context)

    /** いま前面にあるアプリが blocked のどれかに当たるなら true */
    fun isForegroundBlocked(blocked: Set<BlockCategory>): Boolean {
        val pkg = foregroundPackage() ?: return false
        if (pkg in exempt) return false
        return categoriesOf(pkg).any { it in blocked }
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

    private fun categoriesOf(pkg: String): Set<BlockCategory> = categoryCache.getOrPut(pkg) {
        val result = KNOWN_APPS[pkg].orEmpty().toMutableSet()
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        if (info != null) {
            when (info.category) {
                ApplicationInfo.CATEGORY_SOCIAL -> result += BlockCategory.SNS
                ApplicationInfo.CATEGORY_VIDEO -> result += BlockCategory.VIDEO
                ApplicationInfo.CATEGORY_GAME -> result += BlockCategory.GAME
            }
            @Suppress("DEPRECATION")
            if (info.flags and ApplicationInfo.FLAG_IS_GAME != 0) result += BlockCategory.GAME
        }
        result
    }

    private val exempt: Set<String> by lazy {
        val homes = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        ).map { it.activityInfo.packageName }
        val dialers = pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0).map { it.activityInfo.packageName }
        homes.toSet() + dialers + context.packageName + "com.android.systemui"
    }

    companion object {
        private const val INITIAL_LOOKBACK_MS = 60 * 60_000L
        private const val OVERLAP_MS = 2_000L

        @Suppress("DEPRECATION")
        private val RESUMED =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) UsageEvents.Event.ACTIVITY_RESUMED
            else UsageEvents.Event.MOVE_TO_FOREGROUND

        private val KNOWN_APPS: Map<String, Set<BlockCategory>> = mapOf(
            // ショート動画
            "com.zhiliaoapp.musically" to setOf(BlockCategory.SHORT),
            "com.ss.android.ugc.trill" to setOf(BlockCategory.SHORT),
            "com.zhiliaoapp.musically.go" to setOf(BlockCategory.SHORT),
            "com.google.android.youtube" to setOf(BlockCategory.SHORT, BlockCategory.VIDEO),
            "com.instagram.android" to setOf(BlockCategory.SHORT, BlockCategory.SNS),
            // SNS
            "com.twitter.android" to setOf(BlockCategory.SNS),
            "com.instagram.barcelona" to setOf(BlockCategory.SNS),
            "com.facebook.katana" to setOf(BlockCategory.SNS),
            "com.snapchat.android" to setOf(BlockCategory.SNS),
            "com.bereal.ft" to setOf(BlockCategory.SNS),
            "xyz.blueskyweb.app" to setOf(BlockCategory.SNS),
            "com.reddit.frontpage" to setOf(BlockCategory.SNS),
            // 動画
            "com.netflix.mediaclient" to setOf(BlockCategory.VIDEO),
            "com.amazon.avod.thirdpartyclient" to setOf(BlockCategory.VIDEO),
            "com.disney.disneyplus" to setOf(BlockCategory.VIDEO),
            "tv.abema" to setOf(BlockCategory.VIDEO),
            "jp.unext.mediaplayer" to setOf(BlockCategory.VIDEO),
            "jp.happyon.android" to setOf(BlockCategory.VIDEO),
            "jp.nicovideo.android" to setOf(BlockCategory.VIDEO),
            "tv.twitch.android.app" to setOf(BlockCategory.VIDEO),
        )

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
