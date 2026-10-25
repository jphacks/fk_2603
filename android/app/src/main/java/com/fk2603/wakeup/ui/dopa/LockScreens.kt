package com.fk2603.wakeup.ui.dopa

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.BlockCategory
import com.fk2603.wakeup.ui.formatEpoch
import com.fk2603.wakeup.ui.theme.Dopa
import kotlinx.coroutines.delay

private val SNOOZE_COMEBACKS = listOf("スヌーズ？ないよ。", "だからないって。", "押しても無駄。", "ベッドから出ろ。")

/**
 * アラーム鳴動中（プロトの alarm-screen）
 * onStop があればデモ用の「アラームを止める」ボタンを出す。実際はベッドを出ると止まる
 */
@Composable
fun AlarmScreen(ui: LockUi, nowSec: Long, onStop: (() -> Unit)? = null) {
    var snooze by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Dopa.Background).systemBarsPadding()) {
        HazardStripe()
        Marquee("起きろ WAKE UP 起きろ WAKE UP")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Sticker(
                if (ui.resleep) "● 二度寝" else "● ALARM", Modifier.blinking(), tone = StickerTone.ALERT, fontSize = 28.sp,
            )
            VSpace(12.dp)
            DisplayText(formatEpoch(nowSec, "HH:mm"), 92.sp, lineHeight = 92.sp)
            Text(
                if (ui.resleep) "二度寝を検出。ベッドから出ないとまた鳴る" else dayLabel(nowSec),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Dopa.Muted,
            )
            Box(Modifier.padding(top = 16.dp).widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                PulseRing(Modifier.size(192.dp))
                Watcher(WatcherMood.ANGRY, Modifier.fillMaxSize().shaking())
                TalkLabel("起きろ、\nドパガキ。", Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = 8.dp), rotation = 6f)
            }
            Text(
                "ベッドの圧力センサーが反応中。\nベッドから出るまで SNS とショート動画は封印されます。",
                fontSize = 12.sp, color = Dopa.Muted, textAlign = TextAlign.Center, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).border(1.dp, Dopa.Border, shape).clickable { snooze++ }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.NotificationsOff, null, tint = Dopa.Muted, modifier = Modifier.size(16.dp))
                HSpace(8.dp)
                Text(
                    if (snooze == 0) "スヌーズ" else SNOOZE_COMEBACKS[(snooze - 1) % SNOOZE_COMEBACKS.size],
                    color = Dopa.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            if (onStop != null) {
                BigLimeButton("アラームを止める", onStop)
            } else {
                Box(
                    Modifier.fillMaxWidth().clip(shape).background(Dopa.Lime).padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("ベッドから出ると止まる", color = Dopa.LimeForeground, fontFamily = Dopa.Display, fontSize = 20.sp)
                }
            }
        }
    }
}

private val TAUNTS = listOf(
    "あと5分、は嘘。",
    "その布団、もう温まりきってる。",
    "タイムラインは逃げない。",
    "1限、始まるぞ。",
    "ドーパミン、まだお預け。",
)

private data class LockApp(val name: String, val icon: ImageVector, val category: BlockCategory?)

private val LOCK_APPS = listOf(
    LockApp("ショート", Icons.Rounded.Movie, BlockCategory.SHORT),
    LockApp("SNS", Icons.Rounded.ChatBubble, BlockCategory.SNS),
    LockApp("動画", Icons.Rounded.PlayArrow, BlockCategory.VIDEO),
    LockApp("ゲーム", Icons.Rounded.SportsEsports, BlockCategory.GAME),
    LockApp("音楽", Icons.Rounded.MusicNote, null),
)

/**
 * ベッド在床ロック（プロトの lock-screen）
 * onHome: ホーム画面に戻る（封印していないアプリは使える）。onLeaveBed: デモ用の離床ボタン
 */
@Composable
fun LockScreen(ui: LockUi, onHome: (() -> Unit)? = null, onLeaveBed: (() -> Unit)? = null, leaving: Boolean = false) {
    var taunt by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            taunt = (taunt + 1) % TAUNTS.size
        }
    }
    val pressure = toPercent(ui.pressureRaw)
    val threshold = toPercent(ui.threshold)
    val gauge by animateFloatAsState(pressure / 100f, label = "pressure")
    Column(
        Modifier.fillMaxSize().background(Dopa.Background).gridBackground().systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Sticker(
                if (ui.inBed) "LOCKED" else "UNLOCKING",
                tone = if (ui.inBed) StickerTone.ALERT else StickerTone.LIME,
                leading = {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(12.dp), tint = if (ui.inBed) Dopa.Foreground else Dopa.LimeForeground)
                },
            )
            Spacer(Modifier.weight(1f))
            Text(if (ui.connected) "M5Stack 接続中" else "M5Stack 未接続", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Dopa.Muted)
        }
        Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ベッド滞在時間", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Dopa.Muted, letterSpacing = 2.sp)
            DisplayText(formatMinSec(ui.inBedSec), 72.sp, lineHeight = 72.sp)
            DisplayText("ベッドから出ろ。", 24.sp, Modifier.padding(top = 8.dp), lineHeight = 30.sp)
            DisplayText("話はそれからだ。", 24.sp, color = Dopa.Lime, lineHeight = 30.sp)
        }
        DopaCard(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Row {
                Text("PRESSURE SENSOR", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Dopa.Muted)
                Text("$pressure / 100", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (ui.inBed) Dopa.Alert else Dopa.Lime)
            }
            VSpace(12.dp)
            ProgressBar(gauge, color = if (ui.inBed) Dopa.Alert else Dopa.Lime, height = 16.dp, marker = threshold / 100f)
            VSpace(8.dp)
            Text(
                if (ui.inBed) "しきい値 $threshold を下回ると制限解除" else "離床を検知。制限を解除しています…",
                fontSize = 11.sp, color = Dopa.Muted,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            LOCK_APPS.forEach { app ->
                val locked = ui.inBed && app.category != null && (ui.alarm || app.category in ui.blocked)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (locked) Dopa.Surface2 else Dopa.Lime),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(app.icon, null, tint = if (locked) Dopa.Muted else Dopa.LimeForeground, modifier = Modifier.size(20.dp))
                        }
                        if (locked) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(16.dp).clip(CircleShape).background(Dopa.Alert),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Lock, "制限中", tint = Dopa.Foreground, modifier = Modifier.size(10.dp)) }
                        }
                    }
                    Text(app.name, fontSize = 9.sp, color = Dopa.Muted)
                }
            }
        }
        DopaCard(Modifier.fillMaxWidth().padding(top = 16.dp), padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Watcher(WatcherMood.SLEEPY, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                HSpace(12.dp)
                Text(TAUNTS[taunt], fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.padding(top = 24.dp))
        val shape = RoundedCornerShape(16.dp)
        if (onLeaveBed != null) {
            Text(
                if (leaving) "センサー検知中…" else "ベッドから出る",
                Modifier.fillMaxWidth().clip(shape).background(Dopa.Foreground.copy(alpha = if (leaving) 0.6f else 1f))
                    .clickable(enabled = !leaving, onClick = onLeaveBed).padding(vertical = 16.dp),
                color = Dopa.Background, fontFamily = Dopa.Display, fontSize = 16.sp, textAlign = TextAlign.Center,
            )
            Text(
                "デモ：圧力センサーの離床検知をシミュレート", Modifier.fillMaxWidth().padding(top = 8.dp),
                fontSize = 10.sp, color = Dopa.Muted, textAlign = TextAlign.Center,
            )
        } else if (onHome != null) {
            Text(
                "ホームに戻る",
                Modifier.fillMaxWidth().clip(shape).background(Dopa.Foreground).clickable(onClick = onHome).padding(vertical = 16.dp),
                color = Dopa.Background, fontFamily = Dopa.Display, fontSize = 16.sp, textAlign = TextAlign.Center,
            )
            Text(
                "封印していないアプリは使えます｜緊急通話は常に利用可", Modifier.fillMaxWidth().padding(top = 8.dp),
                fontSize = 10.sp, color = Dopa.Muted, textAlign = TextAlign.Center,
            )
        }
    }
}
