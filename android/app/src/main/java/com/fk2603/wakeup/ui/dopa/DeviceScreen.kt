package com.fk2603.wakeup.ui.dopa

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.M5Config
import com.fk2603.wakeup.PressureLog
import com.fk2603.wakeup.ui.formatEpoch
import com.fk2603.wakeup.ui.formatReturns
import com.fk2603.wakeup.ui.formatSleep
import com.fk2603.wakeup.ui.stateLabel
import com.fk2603.wakeup.ui.theme.Dopa
import kotlin.math.roundToInt

@Composable
fun DeviceScreen(
    ui: DeviceUi,
    nowSec: Long,
    onConfig: (threshold: Int, wakeConfirmSec: Int) -> Unit,
    onToggleApp: (pkg: String, blocked: Boolean) -> Unit,
    onBlockAll: () -> Unit,
    onAllowAll: () -> Unit,
    onUsageAccess: () -> Unit,
    onSaveNickname: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onUnpair: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.padding(top = 4.dp)) {
            Text("DEVICE", fontSize = 12.sp, color = Dopa.Muted)
            DisplayText("センサー設定", 24.sp)
        }
        ui.message?.let { Text(it, color = Dopa.Lime, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

        DeviceCard(ui)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("PRESSURE LOG") { SectionHint("直近8時間") }
            PressureChart(ui, nowSec)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("判定チューニング")
            Tuning(ui, onConfig)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("在床中に封印するアプリ") {
                SectionHint("${ui.apps.count { it.blocked }} / ${ui.apps.size} を制限中")
            }
            if (!ui.usageAccess) {
                Text(
                    "「使用状況へのアクセス」が未許可のため、いまはベッドの上ではスマホ全体をロックします。許可するとチェックしたアプリだけを封印します。",
                    fontSize = 11.sp, color = Dopa.Alert,
                )
                SmallButton("使用状況へのアクセスを許可", onUsageAccess)
            }
            BlockAppList(ui.apps, onToggleApp, onBlockAll, onAllowAll)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("PROFILE") { SectionHint("ランキングに出る名前") }
            NicknameField(ui.nickname, onSaveNickname)
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
        if (ui.pairedDevId != null) {
            Text(
                "この M5Stack の登録を解除",
                Modifier.align(Alignment.CenterHorizontally).clickable(onClick = onUnpair).padding(8.dp),
                color = Dopa.Muted, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun DeviceCard(ui: DeviceUi) {
    DopaCard(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(size = 48.dp, modifier = Modifier.clip(RoundedCornerShape(16.dp))) {
                Icon(Icons.Rounded.Memory, null, tint = Dopa.LimeForeground, modifier = Modifier.size(24.dp))
            }
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                DisplayText("M5Stack Basic", 14.sp, maxLines = 1)
                Text("+ FSR406 圧力センサー", fontSize = 11.sp, color = Dopa.Muted)
            }
            Sticker(if (ui.connected) "ONLINE" else "OFFLINE", tone = if (ui.connected) StickerTone.LIME else StickerTone.ALERT)
        }
        VSpace(16.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Icons.Rounded.Sensors, "状態", ui.state?.let { shortState(it) } ?: "-", Modifier.weight(1f))
            StatTile(Icons.AutoMirrored.Rounded.VolumeUp, "音量", ui.volume?.toString() ?: "-", Modifier.weight(1f))
            StatTile(
                Icons.Rounded.Sync, "同期",
                ui.lastStatusAgoSec?.let { if (it < 60) "${it}秒前" else "${it / 60}分前" } ?: "未接続",
                Modifier.weight(1f),
            )
        }
        ui.state?.let {
            VSpace(10.dp)
            Text(stateLabel(it), fontSize = 11.sp, color = Dopa.Muted)
        }
        ui.pairedDevId?.let {
            VSpace(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = Dopa.Lime, modifier = Modifier.size(14.dp))
                HSpace(6.dp)
                Text(
                    "ID $it ・ AES-256-GCM で暗号化（QR で鍵交換）",
                    fontSize = 11.sp, color = Dopa.Muted, maxLines = 1,
                )
            }
        }
    }
}

private fun shortState(state: String) = when (state) {
    "IDLE" -> "待機"
    "ARMED" -> "セット済"
    "WAITING" -> "待ち"
    "RINGING" -> "鳴動中"
    "AWAKE" -> "起床"
    "RESLEEP_ARMED" -> "二度寝"
    else -> state
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Dopa.Surface2).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Dopa.Lime, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 10.sp, color = Dopa.Muted, modifier = Modifier.padding(top = 4.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PressureChart(ui: DeviceUi, nowSec: Long) {
    val start = nowSec - PressureLog.WINDOW_SEC
    DopaCard(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            fun x(at: Long) = ((at - start).toFloat() / PressureLog.WINDOW_SEC) * size.width
            fun y(raw: Int) = size.height * (1 - raw.toFloat() / M5Config.RAW_MAX)
            ui.config?.let {
                val ty = y(it.threshold)
                drawLine(
                    Dopa.Foreground.copy(alpha = 0.4f), Offset(0f, ty), Offset(size.width, ty), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
            val pts = ui.samples.filter { it.at >= start }
            if (pts.size >= 2) {
                val line = Path()
                pts.forEachIndexed { i, s -> if (i == 0) line.moveTo(x(s.at), y(s.raw)) else line.lineTo(x(s.at), y(s.raw)) }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(pts.last().at), size.height)
                    lineTo(x(pts.first().at), size.height)
                    close()
                }
                drawPath(fill, Dopa.Lime.copy(alpha = 0.12f))
                drawPath(line, Dopa.Lime, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            }
        }
        VSpace(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ChartLabel(formatEpoch(start, "H:mm"))
            ui.sleepStartAt?.takeIf { it >= start }?.let { ChartLabel("就寝 ${formatEpoch(it, "H:mm")}") }
            ui.sleepEndAt?.takeIf { it >= start }?.let { ChartLabel("離床 ${formatEpoch(it, "H:mm")}") }
            ChartLabel(formatEpoch(nowSec, "H:mm"))
        }
        if (ui.samples.isEmpty()) {
            VSpace(4.dp)
            SectionHint("M5Stack とつながると記録が始まります")
        }
    }
}

@Composable
private fun ChartLabel(text: String) = Text(text, fontSize = 10.sp, color = Dopa.Muted)

@Composable
private fun Tuning(ui: DeviceUi, onConfig: (Int, Int) -> Unit) {
    val config = ui.config
    DopaCard(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        if (config == null) {
            SectionHint(if (ui.connected) "M5Stack のファームウェアを更新すると変更できます" else "M5Stack とつながると変更できます")
            return@DopaCard
        }
        val enabled = ui.connected && !ui.configLocked
        // 動かしている間は手元の値を表示し、指を離したら M5Stack に送る
        var threshold by remember(config.threshold) { mutableFloatStateOf(config.threshold.toFloat()) }
        var confirm by remember(config.wakeConfirmSec) { mutableFloatStateOf(config.wakeConfirmSec.toFloat()) }
        RangeField(
            label = "在床しきい値",
            hint = "これより重いと「ベッドにいる」判定（いま ${ui.pressureRaw?.let { toPercent(it) } ?: "-"}）",
            value = "${toPercent(threshold.roundToInt())}",
            suffix = "",
            current = threshold,
            range = M5Config.THRESHOLD_RANGE.first.toFloat()..M5Config.THRESHOLD_RANGE.last.toFloat(),
            enabled = enabled,
            onChange = { threshold = it },
            onDone = { onConfig(threshold.roundToInt(), confirm.roundToInt()) },
        )
        VSpace(20.dp)
        RangeField(
            label = "離床確認時間",
            hint = "この秒数ベッドから離れたら起床",
            value = "${confirm.roundToInt()}",
            suffix = "秒",
            current = confirm,
            range = M5Config.WAKE_CONFIRM_RANGE.first.toFloat()..M5Config.WAKE_CONFIRM_RANGE.last.toFloat(),
            enabled = enabled,
            onChange = { confirm = it },
            onDone = { onConfig(threshold.roundToInt(), confirm.roundToInt()) },
        )
        if (ui.configLocked) {
            VSpace(12.dp)
            Text("ベッドにいる間とアラーム中は変更できません", fontSize = 11.sp, color = Dopa.Alert)
        }
    }
}

/** raw(0-4095) を 0-100 に */
fun toPercent(raw: Int): Int = (raw * 100f / M5Config.RAW_MAX).roundToInt()

@Composable
private fun RangeField(
    label: String,
    hint: String,
    value: String,
    suffix: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        DisplayText(value, 20.sp, color = Dopa.Lime)
        if (suffix.isNotEmpty()) Text(suffix, fontSize = 12.sp, color = Dopa.Lime, fontFamily = Dopa.Display)
    }
    Slider(
        value = current,
        onValueChange = onChange,
        onValueChangeFinished = onDone,
        valueRange = range,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = Dopa.Lime,
            activeTrackColor = Dopa.Lime,
            inactiveTrackColor = Dopa.Surface2,
            disabledThumbColor = Dopa.Muted,
            disabledActiveTrackColor = Dopa.Muted,
            disabledInactiveTrackColor = Dopa.Surface2,
        ),
    )
    Text(hint, fontSize = 11.sp, color = Dopa.Muted)
}

/** 封印するアプリの一覧（チェック = 封印）。最初は全部チェック済みで、使いたいものだけ外す */
@Composable
private fun BlockAppList(
    apps: List<BlockApp>,
    onToggle: (pkg: String, blocked: Boolean) -> Unit,
    onBlockAll: () -> Unit,
    onAllowAll: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("すべて制限", onBlockAll, enabled = apps.any { !it.blocked })
        SmallButton("すべて解除", onAllowAll, enabled = apps.any { it.blocked })
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("アプリ名で絞り込む") },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = dopaFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    val shown = apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Dopa.Surface).border(1.dp, Dopa.Border, shape)) {
        if (shown.isEmpty()) {
            Box(Modifier.padding(16.dp)) { SectionHint(if (apps.isEmpty()) "アプリを読み込んでいます…" else "見つかりません") }
        }
        shown.forEachIndexed { i, app ->
            key(app.pkg) {
                if (i > 0) HorizontalDivider(color = Dopa.Border)
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(app.pkg, !app.blocked) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(rememberAppInfo(app.pkg, app.label), 32.dp)
                    HSpace(12.dp)
                    Text(app.label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Checkbox(
                        checked = app.blocked,
                        onCheckedChange = { onToggle(app.pkg, it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Dopa.Lime,
                            checkmarkColor = Dopa.LimeForeground,
                            uncheckedColor = Dopa.Muted,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun dopaFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Dopa.Lime,
    unfocusedBorderColor = Dopa.Border,
    cursorColor = Dopa.Lime,
    focusedContainerColor = Dopa.Surface,
    unfocusedContainerColor = Dopa.Surface,
)

@Composable
private fun NicknameField(saved: String, onSave: (String) -> Unit) {
    var text by remember(saved) { mutableStateOf(saved) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("ニックネーム") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            shape = RoundedCornerShape(16.dp),
            colors = dopaFieldColors(),
            modifier = Modifier.weight(1f),
        )
        HSpace(8.dp)
        SmallButton("保存", { onSave(text) }, enabled = text.trim() != saved)
    }
}
