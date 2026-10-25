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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.Config
import com.fk2603.wakeup.ui.formatEpoch
import com.fk2603.wakeup.ui.theme.Dopa
import kotlinx.coroutines.delay

// アラーム中・ロック中の画面。スクロールせず1画面に収める（監視員の画像が余った高さを使う）

private val SNOOZE_COMEBACKS = listOf("スヌーズ？ないよ。", "だからないって。", "押しても無駄。", "ベッドから出ろ。")

/**
 * アラーム鳴動中（プロトの alarm-screen）
 * onStop があればデモ用の「アラームを止める」ボタンを出す。実際はベッドを出ると止まる
 * onHome: ホーム画面に戻る（登録していないアプリは使える）
 */
@Composable
fun AlarmScreen(
    ui: LockUi,
    nowSec: Long,
    onStop: (() -> Unit)? = null,
    onHome: (() -> Unit)? = null,
    onEmergency: (() -> Unit)? = null,
) {
    var snooze by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Dopa.Background).systemBarsPadding()) {
        HazardStripe()
        Marquee("起きろ WAKE UP 起きろ WAKE UP")
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Sticker(if (ui.resleep) "● 二度寝" else "● ALARM", Modifier.blinking(), tone = StickerTone.ALERT, fontSize = 24.sp)
            VSpace(8.dp)
            FitText(formatEpoch(nowSec, "HH:mm"), 92.sp)
            Text(
                if (ui.resleep) "二度寝を検出。ベッドから出ないとまた鳴る" else dayLabel(nowSec),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Dopa.Muted, maxLines = 1,
            )
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true), contentAlignment = Alignment.Center) {
                    PulseRing(Modifier.fillMaxSize(0.7f))
                    Watcher(WatcherMood.ANGRY, Modifier.fillMaxSize().shaking())
                    TalkLabel("起きろ、\nドパガキ。", Modifier.align(Alignment.TopEnd), rotation = 6f)
                }
            }
            Text(
                "ベッドから出るまで、登録したアプリはロックされます。",
                fontSize = 12.sp, color = Dopa.Muted, textAlign = TextAlign.Center, maxLines = 2,
            )
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            onHome?.let {
                Text(
                    "ホームに戻る",
                    Modifier.fillMaxWidth().clip(shape).background(Dopa.Foreground).clickable(onClick = it).padding(vertical = 14.dp),
                    color = Dopa.Background, fontFamily = Dopa.Display, fontSize = 16.sp, textAlign = TextAlign.Center,
                )
            }
            onEmergency?.let { EmergencyButton(ui.streak, it) }
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

/**
 * ベッド在床ロック（プロトの lock-screen）
 * onHome: ホーム画面に戻る（登録していないアプリは使える）。onLeaveBed: デモ用の離床ボタン
 */
@Composable
fun LockScreen(
    ui: LockUi,
    onHome: (() -> Unit)? = null,
    onLeaveBed: (() -> Unit)? = null,
    leaving: Boolean = false,
    onEmergency: (() -> Unit)? = null,
) {
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
            .padding(horizontal = 20.dp).padding(bottom = 16.dp),
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
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ベッド滞在時間", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Dopa.Muted, letterSpacing = 2.sp)
            FitText(formatMinSec(ui.inBedSec), 72.sp)
            DisplayText("ベッドから出ろ。", 22.sp, Modifier.padding(top = 4.dp), lineHeight = 28.sp)
            DisplayText("話はそれからだ。", 22.sp, color = Dopa.Lime, lineHeight = 28.sp)
        }
        DopaCard(Modifier.fillMaxWidth().padding(top = 16.dp), padding = PaddingValues(14.dp)) {
            Row {
                Text("PRESSURE SENSOR", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Dopa.Muted)
                Text("$pressure / 100", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (ui.inBed) Dopa.Alert else Dopa.Lime)
            }
            VSpace(10.dp)
            ProgressBar(gauge, color = if (ui.inBed) Dopa.Alert else Dopa.Lime, height = 14.dp, marker = threshold / 100f)
            VSpace(6.dp)
            Text(
                if (ui.inBed) "しきい値 $threshold を下回ると制限解除" else "離床を検知。制限を解除しています…",
                fontSize = 11.sp, color = Dopa.Muted,
            )
        }
        // ロック中のアプリ（登録したものの先頭数件）を実アイコンで並べる
        if (ui.blockedPkgs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ui.blockedPkgs.forEach { pkg ->
                    val app = rememberAppInfo(pkg)
                    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box {
                            AppIcon(app, 44.dp, dimmed = ui.inBed)
                            if (ui.inBed) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(16.dp).clip(CircleShape).background(Dopa.Alert),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Lock, "制限中", tint = Dopa.Foreground, modifier = Modifier.size(10.dp)) }
                            }
                        }
                        Text(app.label, fontSize = 9.sp, color = Dopa.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // 余った高さは監視員に使う
        DopaCard(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp), padding = PaddingValues(12.dp)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Watcher(
                    WatcherMood.SLEEPY,
                    Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(16.dp)),
                )
                HSpace(12.dp)
                Text(TAUNTS[taunt], fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        VSpace(12.dp)
        val shape = RoundedCornerShape(16.dp)
        if (onLeaveBed != null) {
            Text(
                if (leaving) "センサー検知中…" else "ベッドから出る",
                Modifier.fillMaxWidth().clip(shape).background(Dopa.Foreground.copy(alpha = if (leaving) 0.6f else 1f))
                    .clickable(enabled = !leaving, onClick = onLeaveBed).padding(vertical = 16.dp),
                color = Dopa.Background, fontFamily = Dopa.Display, fontSize = 16.sp, textAlign = TextAlign.Center,
            )
            Text(
                "デモ：圧力センサーの離床検知をシミュレート", Modifier.fillMaxWidth().padding(top = 6.dp),
                fontSize = 10.sp, color = Dopa.Muted, textAlign = TextAlign.Center,
            )
        } else if (onHome != null) {
            Text(
                "ホームに戻る",
                Modifier.fillMaxWidth().clip(shape).background(Dopa.Foreground).clickable(onClick = onHome).padding(vertical = 16.dp),
                color = Dopa.Background, fontFamily = Dopa.Display, fontSize = 16.sp, textAlign = TextAlign.Center,
            )
            Text(
                "登録していないアプリは使えます｜緊急通話は常に利用可", Modifier.fillMaxWidth().padding(top = 6.dp),
                fontSize = 10.sp, color = Dopa.Muted, textAlign = TextAlign.Center,
            )
        }
        onEmergency?.let {
            VSpace(8.dp)
            EmergencyButton(ui.streak, it)
        }
    }
}

/**
 * 緊急解除（動作確認用の仮ボタン。デザインはチームで差し替える）
 * 1回目で「連続記録が0に戻る」と警告し、EMERGENCY_CONFIRM_MS 以内にもう一度押すと解除
 */
@Composable
fun EmergencyButton(streak: Int, onEmergency: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(Config.EMERGENCY_CONFIRM_MS)
            armed = false
        }
    }
    val minutes = Config.EMERGENCY_UNLOCK_SEC / 60
    val label = when {
        !armed -> "緊急解除（${minutes}分）"
        streak > 0 -> "もう一度押すと${minutes}分間解除（${streak}日の連続記録が0に戻ります）"
        else -> "もう一度押すと${minutes}分間解除（記録に残ります）"
    }
    Text(
        label,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (armed) Dopa.Alert else Dopa.Border, RoundedCornerShape(12.dp))
            .clickable {
                if (armed) {
                    armed = false
                    onEmergency()
                } else {
                    armed = true
                }
            }
            .padding(vertical = 10.dp, horizontal = 12.dp),
        color = if (armed) Dopa.Alert else Dopa.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, maxLines = 2,
    )
}
