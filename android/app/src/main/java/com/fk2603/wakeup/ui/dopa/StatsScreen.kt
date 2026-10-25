package com.fk2603.wakeup.ui.dopa

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.BedPhoneLog
import com.fk2603.wakeup.BedPhoneSession
import com.fk2603.wakeup.BedPhoneTotal
import com.fk2603.wakeup.Gamification
import com.fk2603.wakeup.Record
import com.fk2603.wakeup.RoutineDay
import com.fk2603.wakeup.RoutineStore
import com.fk2603.wakeup.ui.formatEpoch
import com.fk2603.wakeup.ui.theme.Dopa
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 戦績 */
@Composable
fun StatsScreen(ui: StatsUi, onRefresh: () -> Unit, onScreenTime: () -> Unit) {
    val p = ui.progress
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RECORD", fontSize = 12.sp, color = Dopa.Muted)
                DisplayText("戦績", 24.sp)
            }
            SmallButton("スマホ使用データ", onScreenTime)
            HSpace(8.dp)
            SmallButton(if (ui.loading) "読込中…" else "更新", onRefresh)
        }
        ui.message?.let { Text(it, color = Dopa.Alert, fontSize = 12.sp, maxLines = 2) }

        PlayerCard(ui)

        val today = LocalDate.now()
        ResleepSection(ui.records, p, today)

        BedPhoneSection(ui.bedPhone, ui.bedPhoneActive, today)

        RoutineHistorySection(ui.routineHistory, today)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("STICKERS") { SectionHint("${p.badges.size} / ${Gamification.Badge.entries.size}") }
            Gamification.Badge.entries.chunked(3).forEachIndexed { row, badges ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    badges.forEachIndexed { i, b ->
                        BadgeChip(b, b in p.badges, BADGE_ROTATIONS[(row * 3 + i) % BADGE_ROTATIONS.size], Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private val BADGE_ROTATIONS = listOf(-3f, 2f, -1f, 3f, -2f, 1f)

@Composable
private fun PlayerCard(ui: StatsUi) {
    val p = ui.progress
    DopaCard(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp), radius = 24.dp) {
        Row(Modifier.fillMaxWidth().height(220.dp)) {
            Column(Modifier.weight(1f).padding(16.dp)) {
                Sticker("称号：${p.title?.label ?: "見習いドパガキ"}", rotation = -2f)
                VSpace(6.dp)
                DisplayText(ui.nickname.ifBlank { "名無し" }, 18.sp)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                    LabeledValue("LEVEL", "${p.level}", Dopa.Lime)
                    LabeledValue("RANK", p.recentRank?.name ?: "-", Dopa.Foreground)
                }
            }
            Watcher(
                WatcherMood.CLEAR,
                Modifier
                    .width(145.dp)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp)
                    .padding(end = 8.dp)
            )
        }
        HorizontalDivider(color = Dopa.Border)
        Row(Modifier.fillMaxWidth()) {
            listOf(
                "7日間" to "${p.weekWins}W ${p.weekLosses}L",
                "連続" to "${p.streak}日",
                "最速" to (p.bestSec?.let { formatMinSec(it) } ?: "-"),
            ).forEachIndexed { i, (label, value) ->
                Column(
                    Modifier.weight(1f).drawBehind {
                        if (i > 0) drawLine(Dopa.Border, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                    }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(label, fontSize = 10.sp, color = Dopa.Muted)
                    FitText(value, 16.sp)
                }
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 10.sp, color = Dopa.Muted, lineHeight = 10.sp)
        DisplayText(value, 24.sp, color = color)
    }
}

/** 二度寝：今週・今月の回数と、直近7日のそれぞれで二度寝したか */
@Composable
private fun ResleepSection(records: List<Record>, p: Gamification.Progress, today: LocalDate) {
    val zone = ZoneId.systemDefault()
    val resleepDays = records.filter { it.resleep }.map { Instant.ofEpochSecond(it.ringAt).atZone(zone).toLocalDate() }
    val weekStart = today.with(DayOfWeek.MONDAY)
    val monthStart = today.withDayOfMonth(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("二度寝") { SectionHint("今週は月曜から") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountTile("今週", resleepDays.count { !it.isBefore(weekStart) && !it.isAfter(today) }, Modifier.weight(1f))
            CountTile("今月", resleepDays.count { !it.isBefore(monthStart) && !it.isAfter(today) }, Modifier.weight(1f))
        }
        DopaCard(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp), radius = 20.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.week.forEach { (date, day) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(WEEK_LABELS[date.dayOfWeek.value - 1], fontSize = 10.sp, color = Dopa.Muted)
                        VSpace(6.dp)
                        ResleepMark(day)
                        VSpace(6.dp)
                        // 離床までの時間（二度寝の再アラームを除いた最速）
                        Text(day?.bestSec?.let { formatMinSec(it) } ?: "-", fontSize = 10.sp, color = Dopa.Muted, maxLines = 1)
                    }
                }
            }
            VSpace(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(Icons.Rounded.Check, Dopa.Lime, "二度寝なし")
                Legend(Icons.Rounded.Close, Dopa.Alert, "二度寝した")
            }
        }
    }
}

/** 1日の二度寝の有無（記録がない日は点線） */
@Composable
private fun ResleepMark(day: Gamification.Day?) {
    val m = Modifier.size(32.dp).clip(CircleShape)
    when (day?.resleep) {
        true -> Box(m.background(Dopa.Alert), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "二度寝した", tint = Dopa.Foreground, modifier = Modifier.size(18.dp))
        }
        false -> Box(m.background(Dopa.Lime), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, "二度寝なし", tint = Dopa.LimeForeground, modifier = Modifier.size(18.dp))
        }
        null -> Box(m.drawBehind {
            drawCircle(
                Dopa.Muted.copy(alpha = 0.4f), radius = size.minDimension / 2 - 1.dp.toPx(),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
            )
        })
    }
}

@Composable
private fun CountTile(label: String, count: Int, modifier: Modifier) {
    DopaCard(modifier, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), radius = 16.dp) {
        Text(label, fontSize = 10.sp, color = Dopa.Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            DisplayText("$count", 28.sp, color = if (count > 0) Dopa.Alert else Dopa.Lime)
            // Dela Gothic One に「回」の字がないので普通のフォントで出す
            Text("回", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        }
    }
}

private enum class Period(val label: String) { DAY("日"), WEEK("週"), MONTH("月") }

/** ベッドの上でスマホを触った回数・時間（日 / 週 / 月） */
@Composable
private fun BedPhoneSection(saved: List<BedPhoneSession>, active: BedPhoneSession?, today: LocalDate) {
    var period by rememberSaveable { mutableStateOf(Period.DAY) }
    // 触っている最中の回は終わるまで保存されないので、計測中として足して出す
    val sessions = if (active != null) saved + active else saved
    val byDay = remember(sessions) { BedPhoneLog.byDay(sessions) }
    // 夜（18時〜）の分は翌朝の日付で数えるので、「日」もその日付にそろえる
    val day = BedPhoneLog.dayOf(System.currentTimeMillis() / 1000)
    val (from, to, prevFrom) = when (period) {
        Period.DAY -> Triple(day, day, day.minusDays(1))
        Period.WEEK -> today.with(DayOfWeek.MONDAY).let { Triple(it, it.plusDays(6), it.minusWeeks(1)) }
        Period.MONTH -> today.withDayOfMonth(1).let { Triple(it, it.plusMonths(1).minusDays(1), it.minusMonths(1)) }
    }
    val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
    val total = sum(byDay, from, to)
    // 前の期間の、今日までと同じ日数分と比べる
    val elapsed = minOf(dates.size.toLong(), ChronoUnit.DAYS.between(from, maxOf(day, today)) + 1)
    val prev = sum(byDay, prevFrom, prevFrom.plusDays(elapsed - 1))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("ベッドでスマホ") { PeriodTabs(period) { period = it } }
        DopaCard(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp), radius = 20.dp) {
            Text(
                when (period) {
                    Period.DAY -> "${dayText(day, today)}（前の晩〜朝）"
                    Period.WEEK -> "今週 ${from.monthValue}/${from.dayOfMonth}〜${to.monthValue}/${to.dayOfMonth}"
                    Period.MONTH -> "${from.monthValue}月"
                },
                fontSize = 11.sp, color = Dopa.Muted,
            )
            VSpace(6.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("回数", fontSize = 10.sp, color = Dopa.Muted)
                    Row(verticalAlignment = Alignment.Bottom) {
                        DisplayText("${total.count}", 28.sp)
                        // Dela Gothic One に「回」の字がないので普通のフォントで出す
                        Text("回", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("時間", fontSize = 10.sp, color = Dopa.Muted)
                    FitText(formatDuration(total.sec), 28.sp, color = if (total.sec > 0) Dopa.Alert else Dopa.Lime)
                }
            }
            Text(compareText(total, prev, period), fontSize = 11.sp, color = Dopa.Muted, modifier = Modifier.padding(top = 4.dp))
            VSpace(12.dp)
            if (period == Period.DAY) {
                val list = sessions.filter { BedPhoneLog.dayOf(it.startAt) == day }.sortedBy { it.startAt }
                if (list.isEmpty()) SectionHint("ベッドの上では触っていません")
                list.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        val end = if (s == active) "" else formatEpoch(s.endAt, "HH:mm")
                        Text("${formatEpoch(s.startAt, "HH:mm")} 〜 $end", Modifier.weight(1f), fontSize = 12.sp)
                        if (s == active) Text("計測中 ", fontSize = 12.sp, color = Dopa.Alert, fontWeight = FontWeight.Bold)
                        Text(formatDuration(s.sec), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                MinutesChart(dates, byDay, today)
            }
        }
    }
}

/** 日ごとの触っていた時間の棒グラフ（週は7本、月は日数分） */
@Composable
private fun MinutesChart(dates: List<LocalDate>, byDay: Map<LocalDate, BedPhoneTotal>, today: LocalDate) {
    val wide = dates.size <= 7
    val max = dates.maxOf { byDay[it]?.sec ?: 0L }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 2.dp)) {
        dates.forEach { d ->
            val sec = byDay[d]?.sec ?: 0L
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (wide) Text(if (sec > 0) "${sec / 60}分" else "-", fontSize = 9.sp, color = Dopa.Muted, maxLines = 1)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val color = when {
                        d.isAfter(today) -> Dopa.Surface2
                        sec > 0 -> Dopa.Alert
                        else -> Dopa.Lime.copy(alpha = 0.5f)
                    }
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(maxOf(0.04f, sec.toFloat() / max))
                            .clip(RoundedCornerShape(if (wide) 6.dp else 2.dp)).background(color),
                    )
                }
                VSpace(4.dp)
                val label = when {
                    wide -> WEEK_LABELS[d.dayOfWeek.value - 1]
                    d.dayOfMonth == 1 || d.dayOfMonth % 5 == 0 -> "${d.dayOfMonth}"
                    else -> ""
                }
                // 月の棒は細いので、日付は棒の幅からはみ出して描く
                Text(
                    label, Modifier.wrapContentWidth(unbounded = true),
                    fontSize = 9.sp, color = if (d == today) Dopa.Lime else Dopa.Muted, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun PeriodTabs(selected: Period, onSelect: (Period) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Dopa.Surface2).padding(2.dp)) {
        Period.entries.forEach { p ->
            val on = p == selected
            Text(
                p.label,
                Modifier.clip(CircleShape).then(if (on) Modifier.background(Dopa.Lime) else Modifier)
                    .clickable { onSelect(p) }.padding(horizontal = 14.dp, vertical = 6.dp),
                color = if (on) Dopa.LimeForeground else Dopa.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun sum(byDay: Map<LocalDate, BedPhoneTotal>, from: LocalDate, to: LocalDate): BedPhoneTotal {
    val list = byDay.filterKeys { !it.isBefore(from) && !it.isAfter(to) }.values
    return BedPhoneTotal(list.sumOf { it.count }, list.sumOf { it.sec })
}

private fun compareText(now: BedPhoneTotal, prev: BedPhoneTotal, period: Period): String {
    val name = when (period) {
        Period.DAY -> "前日"
        Period.WEEK -> "先週の同じ曜日まで"
        Period.MONTH -> "先月の同じ日まで"
    }
    fun signed(v: Long) = if (v > 0) "+$v" else "$v"
    return "$name より ${signed((now.count - prev.count).toLong())}回 / ${signed((now.sec - prev.sec) / 60)}分"
}

/** 例: 1時間05分 / 12分 / 40秒 */
private fun formatDuration(sec: Long): String = when {
    sec >= 3600 -> "${sec / 3600}時間${"%02d".format((sec % 3600) / 60)}分"
    sec >= 60 -> "${sec / 60}分"
    sec > 0 -> "${sec}秒"
    else -> "0分"
}

private val WEEK_LABELS = listOf("月", "火", "水", "木", "金", "土", "日")

@Composable
private fun Legend(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        HSpace(6.dp)
        Text(text, fontSize = 11.sp, color = Dopa.Muted)
    }
}

/** バッジ（1行のチップ） */
@Composable
private fun BadgeChip(b: Gamification.Badge, got: Boolean, rotation: Float, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        if (got) b.label else "？？？",
        modifier.rotate(rotation).clip(shape)
            .then(if (got) Modifier.background(Dopa.Lime) else Modifier)
            .border(2.dp, if (got) Dopa.Background else Dopa.Muted.copy(alpha = 0.3f), shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        color = if (got) Dopa.LimeForeground else Dopa.Muted.copy(alpha = 0.5f),
        fontFamily = Dopa.Display, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 1,
    )
}

/** 朝のルーティンの記録（日ごとに 完了 / 全体 と達成度 0〜10） */
@Composable
private fun RoutineHistorySection(days: List<RoutineDay>, today: LocalDate) {
    val recent = days.filter { !it.date.isAfter(today) && !it.date.isBefore(today.minusDays(6)) && it.total > 0 }
    val avg = if (recent.isEmpty()) null else recent.sumOf { it.score }.toDouble() / recent.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("朝タスクの記録") {
            SectionHint(avg?.let { "直近7日の平均 ${"%.1f".format(it)}/${RoutineStore.SCORE_MAX}" } ?: "")
        }
        if (days.isEmpty()) SectionHint("まだ記録がありません。ホームの MORNING ROUTINE でタスクを追加しよう")
        days.filter { it.total > 0 }.take(14).forEach { d ->
            DopaCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), radius = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dayText(d.date, today), Modifier.width(64.dp), fontSize = 11.sp, color = Dopa.Muted)
                    Text(
                        d.items.joinToString("・") { (title, done) -> if (done) "✓$title" else title },
                        Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    HSpace(8.dp)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${d.score}/${RoutineStore.SCORE_MAX}",
                            color = scoreColor(d.score), fontFamily = Dopa.Display, fontSize = 15.sp,
                        )
                        Text("${d.done}/${d.total} 完了", fontSize = 10.sp, color = Dopa.Muted)
                    }
                }
            }
        }
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 7 -> Dopa.Lime
    score >= 4 -> Dopa.Foreground
    else -> Dopa.Alert
}

private fun dayText(date: LocalDate, today: LocalDate) = when (date) {
    today -> "今日"
    today.minusDays(1) -> "昨日"
    else -> "${date.monthValue}/${date.dayOfMonth}(${WEEK_LABELS[date.dayOfWeek.value - 1]})"
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        text,
        Modifier.clip(CircleShape).background(Dopa.Surface2).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (enabled) Dopa.Foreground else Dopa.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
    )
}

/** 例: 1:05 */
fun formatMinSec(sec: Long): String = "${sec / 60}:${"%02d".format(sec % 60)}"
