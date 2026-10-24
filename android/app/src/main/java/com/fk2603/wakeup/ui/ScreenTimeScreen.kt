package com.fk2603.wakeup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fk2603.wakeup.Baseline
import com.fk2603.wakeup.Retention
import com.fk2603.wakeup.ScreenTime
import com.fk2603.wakeup.ScreenTimeMath
import com.fk2603.wakeup.ScreenTimeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

private val BEFORE_COLOR = Color(0xFF9E9E9E)
private val AFTER_COLOR = Color(0xFF3F51B5)
private val GOOD_COLOR = Color(0xFF2E7D32)
private val BAD_COLOR = Color(0xFFC62828)
private val DATE = DateTimeFormatter.ofPattern("M/d")

private data class ScreenTimeData(
    val daily: Map<LocalDate, Long>,
    val installDay: LocalDate,
    val baseline: Baseline?,
    val retention: Retention?,
)

/** スマホ全体の1日の合計利用時間を、導入前の1か月平均と比べる画面 */
@Composable
fun ScreenTimeScreen() {
    val context = LocalContext.current
    val store = remember { ScreenTimeStore(context) }
    var hasAccess by remember { mutableStateOf(ScreenTime.hasAccess(context)) }
    var sample by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var real by remember { mutableStateOf<ScreenTimeData?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasAccess = ScreenTime.hasAccess(context)
        reloadKey++
    }
    LaunchedEffect(reloadKey, hasAccess) {
        if (!hasAccess) return@LaunchedEffect
        real = withContext(Dispatchers.IO) {
            ScreenTime.snapshot(context, store)
            ScreenTimeData(store.loadDaily(), store.installDay ?: LocalDate.now(), store.baseline, ScreenTime.retention(context))
        }
    }

    val data = if (sample) sampleData() else real
    val today = LocalDate.now()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("デモ用サンプルデータを表示")
                Switch(checked = sample, onCheckedChange = { sample = it })
            }
            if (sample) Text("※ 表示中の数値は架空のサンプルです（実データではありません）", color = MaterialTheme.colorScheme.error)
        }

        if (!sample && !hasAccess) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("分析には「使用状況へのアクセス」（Usage access）の許可が必要です")
                        Text("アプリごとの利用時間だけを読みます。画面の内容は読みません。")
                        Button(onClick = { context.startActivity(ScreenTime.settingsIntent()) }) { Text("設定を開く") }
                    }
                }
            }
            return@LazyColumn
        }
        if (data == null) {
            item { Text("読み込み中…") }
            return@LazyColumn
        }

        val baselineMs = data.baseline?.avgMs
        val after = ScreenTimeMath.afterDays(data.daily, data.installDay, today)

        item { BaselineCard(data.baseline, data.installDay) }
        item { AfterCard(baselineMs, after.values.toList()) }
        item { WeekdayCard(ScreenTimeMath.byWeekday(data.daily, data.installDay, today)) }

        item { SectionTitle("曜日ごとの記録（導入前の同じ曜日との差）") }
        val grouped = ScreenTimeMath.groupByWeekday(data.daily, data.installDay, today)
        items(DayOfWeek.values().toList()) { dow ->
            val (before, afterList) = grouped.getValue(dow)
            WeekdayDetail(dow, before, afterList)
        }

        item { SectionTitle("日ごとの合計（点線＝導入前の1か月平均）") }
        val maxMs = maxOf(data.daily.values.maxOrNull() ?: 0L, baselineMs ?: 0L).coerceAtLeast(1L)
        items(data.daily.keys.sortedDescending()) { d ->
            DayRow(d, data.daily[d] ?: 0L, maxMs, baselineMs, data.installDay, today)
        }

        if (!sample) {
            data.retention?.let { r ->
                item {
                    Text(
                        "この端末に残っていた記録: 日ごと ${r.dailyDays}日分 ／ 週ごと ${r.weeklyCount}週分 ／ 月ごと ${r.monthlyCount}か月分",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            item {
                OutlinedButton(onClick = {
                    ScreenTime.resetInstall(context, store)
                    reloadKey++
                }) { Text("今を導入日にして基準を取り直す（現在: ${data.installDay.format(DATE)}）") }
            }
        }

        item {
            Text(
                "・ホームアプリ、システム UI、このアプリは利用時間に入れていません\n" +
                    "・導入日（前後が混ざる）と今日（まだ途中）は平均に入れていません\n" +
                    "・Android が日ごとの記録を残すのは数日〜1週間ほどなので、導入前の1か月平均は週ごと（なければ月ごと）の記録から作り、導入時に保存しています\n" +
                    "・そのため導入前の曜日別は、日ごとの記録が残っていた日だけの参考値です\n" +
                    "・Android 8〜最新で同じ意味になる値（前面に表示されていた時間）を使うため、Digital Wellbeing の表示とは少しずれることがあります",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun BaselineCard(b: Baseline?, installDay: LocalDate) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("導入前の1日平均（約1か月）", style = MaterialTheme.typography.titleMedium)
            if (b == null) {
                Text("導入前の記録が取れませんでした")
            } else {
                Text(formatMinutes(b.avgMs), style = MaterialTheme.typography.headlineMedium, color = BEFORE_COLOR)
                val source = when (b.source) {
                    "weekly" -> "週ごとの記録"
                    "monthly" -> "月ごとの記録"
                    else -> "日ごとの記録"
                }
                Text("${formatEpoch(b.fromMs / 1000, "M/d")}〜${formatEpoch(b.toMs / 1000, "M/d")} の$source（${"%.0f".format(b.days)}日分）から")
            }
            Text("導入日: ${installDay.format(DATE)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AfterCard(baselineMs: Long?, after: List<Long>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("導入後の1日平均", style = MaterialTheme.typography.titleMedium)
            val avg = if (after.isEmpty()) null else after.average().toLong()
            if (avg == null) {
                Text("導入の翌日から集まり始めます")
            } else {
                Text(formatMinutes(avg), style = MaterialTheme.typography.headlineMedium, color = AFTER_COLOR)
                Text("${after.size}日分（${formatMinutes(after.min())}〜${formatMinutes(after.max())}）")
            }
            if (baselineMs != null && avg != null) {
                val pct = ScreenTimeMath.changePercent(baselineMs, avg)
                if (pct != null) {
                    Text(
                        "導入前より ${formatDiff(avg - baselineMs)}（${if (pct > 0) "+" else ""}$pct%）",
                        fontWeight = FontWeight.Bold,
                        color = if (pct <= 0) GOOD_COLOR else BAD_COLOR,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekdayCard(rows: List<com.fk2603.wakeup.WeekdayRow>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("曜日ごとの1日平均", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                Text("", Modifier.width(40.dp))
                Text("導入前", Modifier.weight(1f), color = BEFORE_COLOR)
                Text("導入後", Modifier.weight(1f), color = AFTER_COLOR)
                Text("差", Modifier.weight(1f))
            }
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth()) {
                    Text(r.dow.getDisplayName(TextStyle.SHORT, Locale.JAPANESE), Modifier.width(40.dp))
                    Text(r.beforeAvgMs?.let { "${formatMinutes(it)}(${r.beforeDays})" } ?: "-", Modifier.weight(1f))
                    Text(r.afterAvgMs?.let { "${formatMinutes(it)}(${r.afterDays})" } ?: "-", Modifier.weight(1f))
                    val diff = if (r.beforeAvgMs != null && r.afterAvgMs != null) r.afterAvgMs - r.beforeAvgMs else null
                    Text(
                        diff?.let(::formatDiff) ?: "-",
                        Modifier.weight(1f),
                        color = when {
                            diff == null -> Color.Unspecified
                            diff <= 0 -> GOOD_COLOR
                            else -> BAD_COLOR
                        },
                    )
                }
            }
            Text("（ ）内は日数。導入前は日ごとの記録が残っていた日だけの参考値です", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WeekdayDetail(dow: DayOfWeek, before: List<Pair<LocalDate, Long>>, after: List<Pair<LocalDate, Long>>) {
    // 導入前の同じ曜日（ふつうは1日分）を基準にする
    val ref = before.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.toLong()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(dow.getDisplayName(TextStyle.FULL, Locale.JAPANESE), fontWeight = FontWeight.Bold)
        if (before.isEmpty() && after.isEmpty()) Text("　記録なし", style = MaterialTheme.typography.bodySmall)
        before.forEach { (d, ms) ->
            Text("　導入前 ${d.format(DATE)}  ${formatMinutes(ms)}", color = BEFORE_COLOR)
        }
        after.forEach { (d, ms) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("　導入後 ${d.format(DATE)}  ${formatMinutes(ms)}", color = AFTER_COLOR)
                if (ref != null) {
                    val diff = ms - ref
                    Text(formatDiff(diff), color = if (diff <= 0) GOOD_COLOR else BAD_COLOR)
                }
            }
        }
        if (after.size >= 2) {
            Text("　導入後の平均 ${formatMinutes(after.map { it.second }.average().toLong())}（${after.size}回）",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}

@Composable
private fun DayRow(d: LocalDate, ms: Long, maxMs: Long, baselineMs: Long?, installDay: LocalDate, today: LocalDate) {
    val color = if (d.isBefore(installDay)) BEFORE_COLOR else AFTER_COLOR
    val note = when (d) {
        installDay -> "導入日"
        today -> "今日"
        else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${d.format(DATE)} $note", Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).height(16.dp)) {
                Box(
                    Modifier.fillMaxWidth((ms.toFloat() / maxMs).coerceIn(0.01f, 1f)).height(16.dp)
                        .background(color, RoundedCornerShape(3.dp))
                )
                if (baselineMs != null) {
                    // 基準の位置に細い線を引く
                    Box(
                        Modifier.fillMaxWidth((baselineMs.toFloat() / maxMs).coerceIn(0f, 1f)).height(16.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) { Box(Modifier.width(2.dp).height(16.dp).background(Color.Black)) }
                }
            }
            Text(formatMinutes(ms), Modifier.width(76.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (baselineMs != null && d.isAfter(installDay) && d.isBefore(today)) {
            val diff = ms - baselineMs
            Text(
                "　平均より ${formatDiff(diff)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (diff <= 0) GOOD_COLOR else BAD_COLOR,
                modifier = Modifier.padding(start = 88.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    HorizontalDivider()
}

private fun formatMinutes(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60}時間${min % 60}分" else "${min}分"
}

private fun formatDiff(ms: Long): String = (if (ms > 0) "+" else "−") + formatMinutes(kotlin.math.abs(ms))

/** デモ用の架空データ（導入前1週間＋1か月平均、導入後7日）。画面上でもサンプルと明示する */
private fun sampleData(): ScreenTimeData {
    val today = LocalDate.now()
    val install = today.minusDays(8)
    val zone = ZoneId.systemDefault()
    val rnd = Random(2603)
    val daily = (0..14).associate { i ->
        val d = today.minusDays(i.toLong())
        val base = if (d.isBefore(install)) 290 else 205
        val weekend = if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) 50 else 0
        d to (base + weekend + rnd.nextInt(-30, 31)) * 60_000L
    }
    val baseline = Baseline(
        avgMs = 297 * 60_000L,
        fromMs = install.minusDays(28).atStartOfDay(zone).toInstant().toEpochMilli(),
        toMs = install.atStartOfDay(zone).toInstant().toEpochMilli(),
        days = 28.0,
        source = "weekly",
    )
    return ScreenTimeData(daily, install, baseline, null)
}
