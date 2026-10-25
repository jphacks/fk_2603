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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bed
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.ui.formatEpoch
import com.fk2603.wakeup.ui.theme.Dopa
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(
    ui: HomeUi,
    nowSec: Long,
    onAddAlarm: () -> Unit,
    onCancelAlarm: () -> Unit,
    onToggleLocalAlarm: (Long, Boolean) -> Unit,
    onDeleteLocalAlarm: (Long) -> Unit,
    onEditRoutine: () -> Unit,
    onToggleRoutineTask: (Long) -> Unit,
    onWarning: (SetupWarning) -> Unit,
    onDemo: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header(ui)
        ui.warnings.forEach { WarningCard(it) { onWarning(it) } }
        NextMission(ui, nowSec, onEditRoutine, onToggleRoutineTask)
        Row(Modifier.fillMaxWidth().height(260.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WatcherCard(ui, Modifier.weight(3f).fillMaxHeight())
            Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BedCard(ui, Modifier.weight(1f).fillMaxWidth())
                StreakCard(ui.streak, Modifier.weight(1f).fillMaxWidth())
            }
        }

        DemoButton(onDemo)

        
        ui.message?.let { Text(it, color = Dopa.Lime, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("ALARMS") {
                IconBadge(size = 32.dp, background = Dopa.Surface2, modifier = Modifier.clickable(enabled = !ui.alarmLocked, onClick = onAddAlarm)) {
                    Icon(Icons.Rounded.Add, "アラームをセット", tint = Dopa.Foreground, modifier = Modifier.size(16.dp))
                }
            }
            if (ui.localAlarms.isEmpty()) {
                AlarmRow(ui, onAddAlarm, onCancelAlarm)
            } else {
                ui.localAlarms.sortedWith(compareBy({ it.hour }, { it.minute })).forEach { alarm ->
                    LocalAlarmRow(alarm, ui.alarmLocked, onToggleLocalAlarm, onDeleteLocalAlarm)
                }
            }
        }
    }
}

@Composable
private fun Header(ui: HomeUi) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("おはよ、${ui.nickname.ifBlank { "ドパガキ" }}。", fontSize = 12.sp, color = Dopa.Muted)
            Row {
                DisplayText("DOPA", 24.sp)
                DisplayText("DOPA", 24.sp, color = Dopa.Lime)
            }
        }
        Row(
            Modifier.clip(CircleShape).background(Dopa.Surface).border(1.dp, Dopa.Border, CircleShape)
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(size = 28.dp) { DisplayText("${ui.level}", 12.sp, color = Dopa.LimeForeground) }
            HSpace(8.dp)
            Column {
                Text("LV", fontSize = 10.sp, color = Dopa.Muted, lineHeight = 10.sp)
                ProgressBar(ui.levelXp / 100f, Modifier.width(48.dp), height = 4.dp)
            }
        }
    }
}

@Composable
private fun WarningCard(w: SetupWarning, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Dopa.Alert.copy(alpha = 0.12f)).border(2.dp, Dopa.Alert, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(w.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Dopa.Foreground)
        Text(
            w.action,
            Modifier.clip(RoundedCornerShape(12.dp)).background(Dopa.Alert).clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            color = Dopa.Foreground, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
    }
}

@Composable
private fun NextMission(ui: HomeUi, nowSec: Long, onEditRoutine: () -> Unit, onToggleRoutineTask: (Long) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Dopa.Lime).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Alarm, null, tint = Dopa.LimeForeground, modifier = Modifier.size(16.dp))
            HSpace(6.dp)
            Text("NEXT MISSION", fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = Dopa.Display, color = Dopa.LimeForeground, modifier = Modifier.weight(1f))
            ui.alarmEpoch?.let { epoch ->
                Text(
                    remaining(epoch - nowSec),
                    Modifier.clip(CircleShape).background(Dopa.LimeForeground).padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Dopa.Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        VSpace(8.dp)
        DisplayText(ui.alarmEpoch?.let { formatEpoch(it, "HH:mm") } ?: "--:--", 76.sp, color = Dopa.LimeForeground, lineHeight = 76.sp)
        Text(
            ui.alarmEpoch?.let { dayLabel(it) } ?: "アラーム未設定｜＋ からセット",
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Dopa.LimeForeground,
        )
        VSpace(14.dp)
        HorizontalDivider(color = Dopa.LimeForeground.copy(alpha = 0.22f))
        VSpace(12.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MORNING ROUTINE", fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Dopa.Display,
                color = Dopa.LimeForeground, modifier = Modifier.weight(1f))
            Text("編集 ＋", Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onEditRoutine)
                .padding(8.dp), color = Dopa.LimeForeground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (ui.routineTasks.isEmpty()) {
            Text("タスクを追加して、朝の予定を決めよう", color = Dopa.LimeForeground.copy(alpha = 0.75f), fontSize = 12.sp)
        } else {
            ui.routineTasks.forEach { task ->
                val done = task.id in ui.completedTaskIds
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable { onToggleRoutineTask(task.id) }
                    .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (done) "☑" else "☐", fontSize = 20.sp, color = Dopa.LimeForeground)
                    HSpace(10.dp)
                    Text(task.title, fontSize = 13.sp, color = Dopa.LimeForeground.copy(alpha = if (done) 0.55f else 1f))
                }
            }
            Text("${ui.completedTaskIds.size} / ${ui.routineTasks.size} 完了  ・ ${ui.routineDate}",
                fontSize = 11.sp, color = Dopa.LimeForeground.copy(alpha = 0.75f))
        }
        VSpace(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val blocked = ui.blocked.sortedBy { it.ordinal }.joinToString(" / ") { it.label }.ifEmpty { "なし" }
            OutlineChip("制限: $blocked", Dopa.LimeForeground)
            OutlineChip("解除: 離床", Dopa.LimeForeground)
        }
    }
}

@Composable
private fun WatcherCard(ui: HomeUi, modifier: Modifier) {
    DopaCard(modifier, padding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxSize()) {
            Watcher(WatcherMood.SLEEPY, Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 42.dp, bottom = 12.dp).floating())
            SpeechBubble(
                if (ui.inBed) "「まだ寝る気か？」" else "「スマホより俺を見ろ。」",
                Modifier
                    .padding(start = 19.dp, top = 30.dp)
                    .width(170.dp)
                    .height(60.dp),
            )
            Sticker("ドパ監視員", Modifier.align(Alignment.BottomStart).padding(12.dp), rotation = -3f)
        }
    }
}

@Composable
private fun BedCard(ui: HomeUi, modifier: Modifier) {
    DopaCard(modifier) {
        Icon(Icons.Rounded.Bed, null, tint = Dopa.Lime, modifier = Modifier.size(20.dp))
        Spacer(Modifier.weight(1f))
        Text("BED SENSOR", fontSize = 10.sp, color = Dopa.Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when {
                !ui.connected -> Dopa.Muted
                ui.inBed -> Dopa.Lime
                else -> Dopa.Foreground
            }
            Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
                if (ui.connected) PulseRing(Modifier.size(8.dp), color = color, filled = true)
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            }
            HSpace(6.dp)
            DisplayText(
                when {
                    !ui.connected -> "OFFLINE"
                    ui.inBed -> "IN BED"
                    else -> "OUT"
                },
                16.sp, maxLines = 1,
            )
        }
    }
}

@Composable
private fun StreakCard(streak: Int, modifier: Modifier) {
    DopaCard(modifier) {
        Icon(Icons.Rounded.LocalFireDepartment, null, tint = Dopa.Alert, modifier = Modifier.size(20.dp))
        Spacer(Modifier.weight(1f))
        Text("連続起床", fontSize = 10.sp, color = Dopa.Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            DisplayText("$streak", 18.sp)
            Text("日", fontSize = 12.sp, fontFamily = Dopa.Display, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

@Composable
private fun DemoButton(onDemo: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .drawBehind {
                drawRoundRect(
                    Dopa.Lime.copy(alpha = 0.5f),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                )
            }
            .clickable(onClick = onDemo)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            DisplayText("アラームを鳴らす", 14.sp)
            Text("デモ：起床フローを体験する（記録には残りません）", fontSize = 11.sp, color = Dopa.Muted)
        }
        IconBadge {
            Icon(Icons.Rounded.Bolt, null, tint = Dopa.LimeForeground, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AlarmRow(ui: HomeUi, onAddAlarm: () -> Unit, onCancelAlarm: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Dopa.Surface).border(1.dp, Dopa.Border, shape)
            .clickable(enabled = !ui.alarmLocked, onClick = onAddAlarm)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val epoch = ui.alarmEpoch
        Column(Modifier.weight(1f).alpha(if (epoch != null) 1f else 0.4f)) {
            DisplayText(epoch?.let { formatEpoch(it, "HH:mm") } ?: "--:--", 30.sp)
            Text(
                epoch?.let { dayLabel(it) } ?: "タップしてセット",
                fontSize = 11.sp, color = Dopa.Muted, modifier = Modifier.padding(top = 4.dp),
            )
        }
        DopaSwitch(
            checked = epoch != null,
            onChange = { on -> if (on) onAddAlarm() else onCancelAlarm() },
            label = "アラーム",
            enabled = !ui.alarmLocked,
        )
    }
}

private fun remaining(sec: Long): String {
    if (sec <= 0) return "まもなく"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "あと ${h}時間${m}分" else "あと ${m}分"
}

private val WEEKDAYS = listOf("月", "火", "水", "木", "金", "土", "日")

/** 例: 明日・木曜 */
fun dayLabel(epochSec: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val day = when (date) {
        today -> "今日"
        today.plusDays(1) -> "明日"
        else -> formatEpoch(epochSec, "M/d")
    }
    return "$day・${WEEKDAYS[date.dayOfWeek.value - 1]}曜"
}

@Composable
private fun LocalAlarmRow(
    alarm: LocalAlarm,
    locked: Boolean,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val days = if (alarm.weekdays.isEmpty()) "1回のみ" else
        if (alarm.weekdays.size == 7) "毎日" else
            alarm.weekdays.sorted().joinToString("・") { listOf("月", "火", "水", "木", "金", "土", "日")[it - 1] }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Dopa.Surface)
            .border(1.dp, Dopa.Border, shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (alarm.enabled) 1f else 0.4f)) {
            DisplayText("%02d:%02d".format(alarm.hour, alarm.minute), 30.sp)
            Text(days, fontSize = 11.sp, color = Dopa.Muted)
        }
        DopaSwitch(
            checked = alarm.enabled,
            onChange = { onToggle(alarm.id, it) },
            label = "アラーム",
            enabled = !locked,
        )
        IconButton(onClick = { onDelete(alarm.id) }, enabled = !locked) {
            Icon(Icons.Rounded.Delete, contentDescription = "アラームを削除", tint = Dopa.Alert)
        }
    }
}
