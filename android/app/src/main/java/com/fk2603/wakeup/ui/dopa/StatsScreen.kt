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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.SentimentVeryDissatisfied
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.fk2603.wakeup.Gamification
import com.fk2603.wakeup.RoutineDay
import com.fk2603.wakeup.RoutineStore
import java.time.LocalDate
import com.fk2603.wakeup.ui.theme.Dopa

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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("7 DAYS") { SectionHint("離床までの時間") }
            WeekChart(p, Modifier.height(200.dp))
        }

        RoutineHistorySection(ui.routineHistory, LocalDate.now())

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

@Composable
private fun WeekChart(p: Gamification.Progress, modifier: Modifier) {
    val max = p.week.maxOf { it.second?.bestSec ?: 0L }.coerceAtLeast(1)
    DopaCard(modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.week.forEach { (date, day) ->
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val sec = day?.bestSec
                    Text(sec?.let { formatMinSec(it) } ?: "-", fontSize = 10.sp, color = Dopa.Muted, maxLines = 1)
                    // 棒は残りの高さに対する割合で描く
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        val fraction = if (sec != null) maxOf(0.08f, sec.toFloat() / max) else 0.08f
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(fraction).clip(RoundedCornerShape(6.dp)).then(
                                when (day?.win) {
                                    true -> Modifier.background(Dopa.Lime)
                                    false -> Modifier.background(Dopa.Alert)
                                    null -> Modifier.drawBehind {
                                        drawRoundRect(
                                            Dopa.Muted.copy(alpha = 0.4f),
                                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                                            cornerRadius = CornerRadius(6.dp.toPx()),
                                        )
                                    }
                                },
                            ),
                        )
                    }
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Dopa.Surface2), contentAlignment = Alignment.Center) {
                        Text(WEEK_LABELS[date.dayOfWeek.value - 1], fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        VSpace(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(Icons.Rounded.EmojiEvents, Dopa.Lime, "WIN：5分以内に離床")
            Legend(Icons.Rounded.SentimentVeryDissatisfied, Dopa.Alert, "LOSE：二度寝 / 遅い")
        }
    }
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
