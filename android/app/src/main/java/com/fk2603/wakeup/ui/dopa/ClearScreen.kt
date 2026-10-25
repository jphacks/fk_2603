package com.fk2603.wakeup.ui.dopa

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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

    Column(Modifier.fillMaxSize().background(Dopa.Background).verticalScroll(rememberScrollState())) {
        Marquee("MISSION CLEAR ドパ解放")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // MISSION
            Text(
                text = "MISSION",
                fontFamily = Dopa.Display,
                fontSize = 34.sp,
                lineHeight = 36.sp,
                color = Dopa.Foreground,
                style = TextStyle(
                    drawStyle = Stroke(width = 3f)
                ),
                maxLines = 1
            )

            // CLEAR!!
            DisplayText(
                "CLEAR!!",
                44.sp,
                Modifier.stamp(),
                color = Dopa.Lime
            )

            Spacer(Modifier.height(12.dp))

            // キャラクター・ランク・セリフ
            Box(
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center
            ) {
                // キャラクター
                Watcher(
                    WatcherMood.CLEAR,
                    Modifier
                        .size(190.dp)
                        .rise()
                )

                // ランク表示
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 0.dp, y = 0.dp)
                        .stamp(250)
                        .size(65.dp)
                        .clip(CircleShape)
                        .background(Dopa.Background)
                        .border(3.dp, Dopa.Lime, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ui.rank.name,
                        fontFamily = Dopa.Display,
                        fontSize = 38.sp,
                        lineHeight = 40.sp,
                        color = Dopa.Lime,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.offset(y = (-2).dp)
                    )
                }

                // キャラクターのセリフ
                TalkLabel(
                    if (ui.resleep) {
                        "…二度寝は許してない。"
                    } else {
                        "…まあ、やるじゃん。"
                    },
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-4).dp),
                    rotation = -6f,
                    shadow = Dopa.Background,
                    fontSize = 11.sp
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple(Icons.Rounded.Timer, "離床タイム", formatMinSec(ui.seconds)),
                Triple(Icons.Rounded.LocalFireDepartment, "連続起床", "${ui.streak}日"),
                Triple(Icons.AutoMirrored.Rounded.TrendingUp, "獲得XP", "+${ui.xpGain}"),
            ).forEachIndexed { i, (icon, label, value) ->
                DopaCard(Modifier.weight(1f).rise(300 + i * 100), radius = 16.dp, padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    Icon(icon, null, tint = Dopa.Lime, modifier = Modifier.size(16.dp))
                    Text(label, fontSize = 10.sp, color = Dopa.Muted, modifier = Modifier.padding(top = 8.dp))
                    DisplayText(value, 18.sp, maxLines = 1)
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
        Spacer(Modifier.height(24.dp))
        BigLimeButton("スマホを解放する", onDone, Modifier.padding(20.dp).padding(bottom = 12.dp), fontSize = 18.sp)
        Text(
            if (ui.demo) "デモ：記録には残りません" else "記録はランキングに送信されます", Modifier.fillMaxWidth().padding(bottom = 24.dp),
            fontSize = 10.sp, color = Dopa.Muted, textAlign = TextAlign.Center, fontWeight = FontWeight.Normal,
        )
    }
}
