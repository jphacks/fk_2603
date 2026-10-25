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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.SentimentVeryDissatisfied
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.Gamification
import com.fk2603.wakeup.ui.formatReturns
import com.fk2603.wakeup.ui.formatSleep
import com.fk2603.wakeup.ui.theme.Dopa

@Composable
fun StatsScreen(ui: StatsUi, onRefresh: () -> Unit, onDeleteAll: () -> Unit) {
    val p = ui.progress
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RECORD", fontSize = 12.sp, color = Dopa.Muted)
                DisplayText("戦績", 24.sp)
            }
            SmallButton(if (ui.loading) "読込中…" else "更新", onRefresh)
        }
        ui.message?.let { Text(it, color = Dopa.Alert, fontSize = 12.sp) }

        PlayerCard(ui)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("THIS WEEK") { SectionHint("離床までの時間") }
            WeekChart(p)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("ランキング") { SectionHint("起きるまでが速い順") }
            if (ui.ranking.isEmpty()) SectionHint("まだ記録がありません")
            ui.ranking.forEachIndexed { i, r -> RankingRow(i, r) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("STICKERS") { SectionHint("${p.badges.size} / ${Gamification.Badge.entries.size}") }
            Gamification.Badge.entries.chunked(3).forEachIndexed { row, badges ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    badges.forEachIndexed { i, b ->
                        BadgeTile(b, b in p.badges, BADGE_ROTATIONS[(row * 3 + i) % BADGE_ROTATIONS.size], Modifier.weight(1f))
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("SLEEP LOG") { SectionHint("就寝 → 起床") }
            if (ui.sleeps.isEmpty()) SectionHint("まだ記録がありません")
            ui.sleeps.take(14).forEach { s ->
                DopaCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), radius = 16.dp) {
                    Text(formatSleep(s), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    formatReturns(s.returnsAt)?.let { Text(it, fontSize = 11.sp, color = Dopa.Alert) }
                }
            }
        }

        Text(
            "自分の記録をすべて削除",
            Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(12.dp))
                .border(1.dp, Dopa.Alert, RoundedCornerShape(12.dp)).clickable(onClick = onDeleteAll)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = Dopa.Alert, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
    }
}

private val BADGE_ROTATIONS = listOf(-3f, 2f, -1f, 3f, -2f, 1f)

@Composable
private fun PlayerCard(ui: StatsUi) {
    val p = ui.progress
    DopaCard(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp), radius = 28.dp) {
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            Column(Modifier.weight(1f).padding(20.dp)) {
                Sticker("称号：${p.title?.label ?: "見習いドパガキ"}", rotation = -2f)
                VSpace(12.dp)
                Text("PLAYER", fontSize = 11.sp, color = Dopa.Muted)
                DisplayText(ui.nickname.ifBlank { "名無し" }, 20.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column {
                        Text("LEVEL", fontSize = 10.sp, color = Dopa.Muted)
                        DisplayText("${p.level}", 30.sp, color = Dopa.Lime)
                    }
                    Column {
                        Text("RANK", fontSize = 10.sp, color = Dopa.Muted)
                        DisplayText(p.recentRank?.name ?: "-", 30.sp)
                    }
                }
            }
            Watcher(WatcherMood.CLEAR, Modifier.width(140.dp).fillMaxHeight())
        }
        HorizontalDivider(color = Dopa.Border)
        Row(Modifier.fillMaxWidth()) {
            listOf(
                "今週" to "${p.weekWins}W ${p.weekLosses}L",
                "連続" to "${p.streak}日",
                "最速" to (p.bestSec?.let { formatMinSec(it) } ?: "-"),
            ).forEachIndexed { i, (label, value) ->
                Column(
                    Modifier.weight(1f).drawBehind {
                        if (i > 0) drawLine(Dopa.Border, androidx.compose.ui.geometry.Offset(0f, 0f),
                            androidx.compose.ui.geometry.Offset(0f, size.height), 1.dp.toPx())
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(label, fontSize = 10.sp, color = Dopa.Muted)
                    DisplayText(value, 16.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun WeekChart(p: Gamification.Progress) {
    val max = p.week.maxOf { it.second?.bestSec ?: 0L }.coerceAtLeast(1)
    DopaCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            p.week.forEachIndexed { i, (_, day) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sec = day?.bestSec
                    Text(sec?.let { formatMinSec(it) } ?: "-", fontSize = 10.sp, color = Dopa.Muted, maxLines = 1)
                    val h = if (sec != null) maxOf(10f, sec.toFloat() / max * 96f) else 10f
                    val barShape = RoundedCornerShape(6.dp)
                    Box(
                        Modifier.fillMaxWidth().height(h.dp).clip(barShape).then(
                            when (day?.win) {
                                true -> Modifier.background(Dopa.Lime)
                                false -> Modifier.background(Dopa.Alert)
                                null -> Modifier.drawBehind {
                                    drawRoundRect(
                                        Dopa.Muted.copy(alpha = 0.4f),
                                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                                    )
                                }
                            },
                        ),
                    )
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Dopa.Surface2), contentAlignment = Alignment.Center) {
                        Text(WEEK_LABELS[i], fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        VSpace(16.dp)
        HorizontalDivider(color = Dopa.Border)
        VSpace(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(Icons.Rounded.EmojiEvents, Dopa.Lime, "WIN：5分以内に離床")
            Legend(Icons.Rounded.SentimentVeryDissatisfied, Dopa.Alert, "LOSE：二度寝 / 遅い")
        }
    }
}

private val WEEK_LABELS = listOf("月", "火", "水", "木", "金", "土", "日")

@Composable
private fun Legend(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        HSpace(6.dp)
        Text(text, fontSize = 11.sp, color = Dopa.Muted)
    }
}

@Composable
private fun RankingRow(i: Int, r: RankRow) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (r.me) Dopa.Lime.copy(alpha = 0.1f) else Dopa.Surface)
            .border(1.dp, if (r.me) Dopa.Lime else Dopa.Border, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DisplayText("${i + 1}", 18.sp, Modifier.width(28.dp), color = Dopa.Muted)
        if (i == 0) {
            Icon(Icons.Rounded.WorkspacePremium, "1位", tint = Dopa.Lime, modifier = Modifier.size(16.dp))
            HSpace(8.dp)
        }
        Text(r.name + if (r.me) "（あなた）" else "", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        DisplayText(formatMinSec(r.sec), 16.sp)
    }
}

@Composable
private fun BadgeTile(b: Gamification.Badge, got: Boolean, rotation: Float, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.aspectRatio(1f).clip(shape)
            .then(
                if (got) Modifier.background(Dopa.Surface).border(1.dp, Dopa.Border, shape)
                else Modifier.drawBehind {
                    drawRoundRect(
                        Dopa.Border, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                    )
                },
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val labelShape = RoundedCornerShape(8.dp)
        Text(
            if (got) b.label else "？？？",
            Modifier.rotate(rotation).clip(labelShape)
                .then(if (got) Modifier.background(Dopa.Lime) else Modifier)
                .border(2.dp, if (got) Dopa.Background else Dopa.Muted.copy(alpha = 0.3f), labelShape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = if (got) Dopa.LimeForeground else Dopa.Muted.copy(alpha = 0.5f),
            fontFamily = Dopa.Display, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center,
        )
        Text(
            if (b.hidden && !got) "???" else b.desc,
            fontSize = 9.sp, lineHeight = 11.sp, color = Dopa.Muted, textAlign = TextAlign.Center,
        )
    }
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
