package com.fk2603.wakeup.ui.dopa

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.Gamification
import com.fk2603.wakeup.ui.formatEpoch
import com.fk2603.wakeup.ui.formatHours
import com.fk2603.wakeup.ui.theme.Dopa
import kotlinx.coroutines.delay

@Composable
fun ClearScreen(ui: ClearUi, onDone: () -> Unit) {
    var filled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(500)
        filled = true
    }
    val per = Gamification.XP_PER_LEVEL
    val before = ui.levelXpBefore
    val afterRaw = before + ui.xpGain
    val leveledUp = afterRaw >= per
    val bar by animateFloatAsState(
        (if (filled) minOf(afterRaw, per) else before) / per.toFloat(), tween(1000), label = "xp",
    )

    // 総評・予定のカードが増えて1画面に収まらないことがあるので、全体をスクロールにする
    Column(Modifier.fillMaxSize().background(Dopa.Background).verticalScroll(rememberScrollState())) {
        Marquee("MISSION CLEAR ドパ解放")
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 中抜き文字
            Text(
                "MISSION", fontFamily = Dopa.Display, fontSize = 34.sp, lineHeight = 36.sp, color = Dopa.Foreground,
                style = TextStyle(drawStyle = Stroke(width = 3f)), maxLines = 1,
            )
            FitText("CLEAR!!", 44.sp, Modifier.stamp(), color = Dopa.Lime)
            VSpace(12.dp)
            // 監視員・ランク・セリフを重ねる（大きさは固定）
            Box(Modifier.widthIn(max = 220.dp).fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                Watcher(WatcherMood.CLEAR, Modifier.size(190.dp).rise())
                Box(
                    Modifier.align(Alignment.TopEnd).stamp(250).size(65.dp)
                        .clip(CircleShape).background(Dopa.Background).border(3.dp, Dopa.Lime, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // Dela Gothic One は字面が下に寄るので少し持ち上げて丸の中央に置く
                    Text(
                        ui.rank.name, Modifier.offset(y = (-2).dp),
                        fontFamily = Dopa.Display, fontSize = 38.sp, lineHeight = 40.sp, color = Dopa.Lime,
                        textAlign = TextAlign.Center, maxLines = 1,
                    )
                }
                TalkLabel(
                    if (ui.resleep) "…二度寝は許してない。" else "…まあ、やるじゃん。",
                    Modifier.align(Alignment.BottomCenter).offset(y = (-4).dp),
                    rotation = -6f, shadow = Dopa.Background, fontSize = 11.sp,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(Icons.Rounded.Timer, "離床タイム", formatMinSec(ui.seconds)),
                Triple(Icons.Rounded.LocalFireDepartment, "連続起床", "${ui.streak}日"),
                Triple(Icons.AutoMirrored.Rounded.TrendingUp, "獲得XP", "+${ui.xpGain}"),
            ).forEachIndexed { i, (icon, label, value) ->
                DopaCard(Modifier.weight(1f).rise(300 + i * 100), radius = 16.dp, padding = PaddingValues(12.dp)) {
                    Icon(icon, null, tint = Dopa.Lime, modifier = Modifier.size(16.dp))
                    Text(label, fontSize = 10.sp, color = Dopa.Muted, modifier = Modifier.padding(top = 6.dp))
                    FitText(value, 18.sp)
                }
            }
        }
        // 総評: 何時に寝て何時に起きたか
        if (ui.awakeAt > 0) {
            DopaCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp), radius = 16.dp, padding = PaddingValues(12.dp)) {
                Row {
                    listOf(
                        "就寝" to (ui.sleptAt?.let { formatEpoch(it, "HH:mm") } ?: "--:--"),
                        "アラーム" to formatEpoch(ui.ringAt, "HH:mm"),
                        "起床" to formatEpoch(ui.awakeAt, "HH:mm"),
                        "睡眠" to (ui.sleptAt?.let { formatHours(ui.awakeAt - it) } ?: "-"),
                    ).forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 10.sp, color = Dopa.Muted)
                            FitText(value, 16.sp)
                        }
                    }
                }
                if (ui.summary.isNotEmpty()) {
                    VSpace(6.dp)
                    Text(ui.summary, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 17.sp)
                }
            }
        }
        DopaCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp), radius = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DisplayText("LV.${ui.level}", 14.sp, Modifier.weight(1f))
                if (leveledUp && filled) Sticker("LEVEL UP!", Modifier.stamp())
            }
            VSpace(8.dp)
            ProgressBar(bar)
            VSpace(8.dp)
            Text(
                when {
                    ui.newBadge != null -> "新しい称号「${ui.newBadge.label}」を獲得"
                    leveledUp -> "LV.${ui.level + 1} に到達！"
                    else -> "次のレベルまで ${per - afterRaw} XP"
                },
                fontSize = 11.sp, color = Dopa.Muted,
            )
        }
        // ベッドを出たら、その日の予定（朝のルーティン）を出す
        if (ui.routine.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Dopa.Lime).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("今日の予定｜これを目標に動き出そう！", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Dopa.LimeForeground)
                ui.routine.take(4).forEach {
                    Text("☐ $it", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Dopa.LimeForeground, maxLines = 1)
                }
                if (ui.routine.size > 4) Text("ほか${ui.routine.size - 4}件", fontSize = 11.sp, color = Dopa.LimeForeground)
            }
        }
        BigLimeButton("スマホを解放する", onDone, Modifier.padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 14.dp), fontSize = 18.sp)
        Text(
            if (ui.demo) "デモ：記録には残りません" else "記録は端末に保存しました", Modifier.fillMaxWidth().padding(bottom = 12.dp),
            fontSize = 10.sp, color = Dopa.Muted, textAlign = TextAlign.Center, fontWeight = FontWeight.Normal,
        )
    }
}
