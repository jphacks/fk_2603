package com.fk2603.wakeup.ui.dopa

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.Config
import com.fk2603.wakeup.M5Config
import com.fk2603.wakeup.ui.EmergencyStats
import com.fk2603.wakeup.ui.formatEpoch
import com.fk2603.wakeup.ui.formatReturns
import com.fk2603.wakeup.ui.formatSleep
import com.fk2603.wakeup.ui.stateLabel
import com.fk2603.wakeup.ui.theme.Dopa
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun DeviceScreen(
    ui: DeviceUi,
    /** いまの圧力（raw）。M5Stack とつながっていなければ null。グラフが 0.5 秒ごとに読む */
    livePressure: () -> Int?,
    onConfig: (threshold: Int, wakeConfirmSec: Int, resleepDelayMin: Int?) -> Unit,
    onToggleApp: (pkg: String, blocked: Boolean) -> Unit,
    onBlockAll: () -> Unit,
    onAllowAll: () -> Unit,
    onUsageAccess: () -> Unit,
    onSaveNickname: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onUnpair: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(ui.apps, query) { ui.apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) } }
    // アプリの一覧は数百件になることがあるので、見えている行だけを描く
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        item {
            Column(Modifier.padding(top = 4.dp)) {
                Text("DEVICE", fontSize = 12.sp, color = Dopa.Muted)
                DisplayText("センサー設定", 24.sp)
            }
        }
        ui.message?.let { item { Text(it, Modifier.padding(top = 20.dp), color = Dopa.Lime, fontSize = 13.sp, fontWeight = FontWeight.Bold) } }

        item {
            Section {
                SectionLabel("PRESSURE") { SectionHint("直近${LIVE_WINDOW_SEC}秒・0.5秒ごと") }
                LivePressureChart(ui.config?.threshold, livePressure)
            }
        }

        item {
            Section {
                SectionLabel("判定チューニング")
                Tuning(ui, onConfig)
            }
        }

        item {
            Section {
                SectionLabel("起きるまでロックするアプリ") {
                    SectionHint("${ui.apps.count { it.blocked }} 個を登録中")
                }
                SectionHint("チェックしたアプリを開くと、ベッドから出るまでロック画面が出ます。ほかのアプリは使えます。")
                if (!ui.usageAccess) {
                    Text(
                        "「使用状況へのアクセス」が未許可のため、いまはベッドの上ではスマホ全体をロックします。許可すると登録したアプリだけをロックします。",
                        fontSize = 11.sp, color = Dopa.Alert,
                    )
                    SmallButton("使用状況へのアクセスを許可", onUsageAccess)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("すべて登録", onBlockAll, enabled = ui.apps.any { !it.blocked })
                    SmallButton("すべて外す", onAllowAll, enabled = ui.apps.any { it.blocked })
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
            }
        }
        item { VSpace(12.dp) }
        if (shown.isEmpty()) {
            item {
                ListRow(0, 1) {
                    Box(Modifier.padding(16.dp)) { SectionHint(if (ui.apps.isEmpty()) "アプリを読み込んでいます…" else "見つかりません") }
                }
            }
        }
        itemsIndexed(shown, key = { _, app -> app.pkg }) { i, app ->
            ListRow(i, shown.size) { BlockAppRow(app, onToggleApp) }
        }

        ui.emergency?.let { item { Section { EmergencySection(it) } } }

        item {
            Section {
                SectionLabel("PROFILE") { SectionHint("ランキングに出る名前") }
                NicknameField(ui.nickname, onSaveNickname)
            }
        }

        item {
            Section(spacing = 8.dp) {
                SectionLabel("SLEEP LOG") { SectionHint("就寝 → 起床") }
                if (ui.sleeps.isEmpty()) SectionHint("まだ記録がありません")
                ui.sleeps.take(14).forEach { s ->
                    DopaCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), radius = 16.dp) {
                        Text(formatSleep(s), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        formatReturns(s.returnsAt)?.let { Text(it, fontSize = 11.sp, color = Dopa.Alert) }
                    }
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "自分の記録をすべて削除",
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Dopa.Alert, RoundedCornerShape(12.dp)).clickable(onClick = onDeleteAll)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Dopa.Alert, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
                if (ui.pairedDevId != null) {
                    Text(
                        "この M5Stack の登録を解除",
                        Modifier.padding(top = 20.dp).clickable(onClick = onUnpair).padding(8.dp),
                        color = Dopa.Muted, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/** 1つの見出しのまとまり（前のまとまりとの間をあける） */
@Composable
private fun Section(spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/** 一覧の1行。上下の端だけ角を丸め、行の間に線を引いて1枚のカードに見せる */
@Composable
private fun ListRow(index: Int, count: Int, content: @Composable () -> Unit) {
    val r = 24.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) r else 0.dp, topEnd = if (index == 0) r else 0.dp,
        bottomStart = if (index == count - 1) r else 0.dp, bottomEnd = if (index == count - 1) r else 0.dp,
    )
    Column(Modifier.fillMaxWidth().clip(shape).background(Dopa.Surface)) {
        if (index > 0) HorizontalDivider(color = Dopa.Border)
        content()
    }
}

@Composable
private fun BlockAppRow(app: BlockApp, onToggle: (pkg: String, blocked: Boolean) -> Unit) {
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

private const val LIVE_WINDOW_SEC = 10
private const val LIVE_INTERVAL_MS = 500L
private const val LIVE_POINTS = LIVE_WINDOW_SEC * 1000 / LIVE_INTERVAL_MS.toInt() + 1

/**
 * 圧力のリアルタイムグラフ（直近 10 秒・0.5 秒ごと）。縦軸は 0〜100、点線は在床しきい値
 * 値はこのグラフの中だけで持ち、描き直すのもこのグラフだけ（画面全体を 0.5 秒ごとに描き直さない）
 */
@Composable
private fun LivePressureChart(threshold: Int?, livePressure: () -> Int?) {
    // 古い順。null = つながっていない
    val points = remember { mutableStateListOf<Int?>() }
    LaunchedEffect(Unit) {
        while (true) {
            points.add(livePressure())
            if (points.size > LIVE_POINTS) points.removeAt(0)
            delay(LIVE_INTERVAL_MS)
        }
    }
    val current = points.lastOrNull()
    val over = current != null && threshold != null && current >= threshold
    DopaCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            DisplayText(current?.let { "${toPercent(it)}" } ?: "--", 28.sp, color = if (over) Dopa.Lime else Dopa.Foreground)
            Text(
                when {
                    current == null -> "  M5Stack 未接続"
                    threshold == null -> ""
                    over -> "  ベッドにいる判定（しきい値 ${toPercent(threshold)}）"
                    else -> "  ベッドの外（しきい値 ${toPercent(threshold)}）"
                },
                Modifier.padding(bottom = 4.dp), fontSize = 11.sp, color = Dopa.Muted,
            )
        }
        VSpace(8.dp)
        Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            fun x(i: Int) = size.width * (i + LIVE_POINTS - points.size).toFloat() / (LIVE_POINTS - 1)
            fun y(raw: Int) = size.height * (1 - raw.toFloat() / M5Config.RAW_MAX)
            // 目盛り（50）
            drawLine(Dopa.Border, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            threshold?.let {
                val ty = y(it)
                drawLine(
                    Dopa.Foreground.copy(alpha = 0.4f), Offset(0f, ty), Offset(size.width, ty), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
            // つながっていない間で線を切る
            var line: Path? = null
            var first = 0
            fun flush(last: Int) {
                val l = line ?: return
                val fill = Path().apply {
                    addPath(l)
                    lineTo(x(last), size.height)
                    lineTo(x(first), size.height)
                    close()
                }
                drawPath(fill, Dopa.Lime.copy(alpha = 0.12f))
                drawPath(l, Dopa.Lime, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                line = null
            }
            points.forEachIndexed { i, raw ->
                if (raw == null) {
                    flush(i - 1)
                } else {
                    val l = line
                    if (l == null) {
                        first = i
                        line = Path().apply { moveTo(x(i), y(raw)) }
                    } else {
                        l.lineTo(x(i), y(raw))
                    }
                }
            }
            flush(points.size - 1)
            points.lastOrNull()?.let { drawCircle(Dopa.Lime, 4.dp.toPx(), Offset(x(points.size - 1), y(it))) }
        }
        VSpace(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ChartLabel("${LIVE_WINDOW_SEC}秒前")
            ChartLabel("${LIVE_WINDOW_SEC / 2}秒前")
            ChartLabel("いま")
        }
    }
}

@Composable
private fun ChartLabel(text: String) = Text(text, fontSize = 10.sp, color = Dopa.Muted)

@Composable
private fun Tuning(ui: DeviceUi, onConfig: (Int, Int, Int?) -> Unit) {
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
            onDone = { onConfig(threshold.roundToInt(), confirm.roundToInt(), config.resleepDelayMin) },
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
            onDone = { onConfig(threshold.roundToInt(), confirm.roundToInt(), config.resleepDelayMin) },
        )
        VSpace(20.dp)
        Text("二度寝の再アラーム", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            "起きて20分以内にベッドに戻ったら、この時間で鳴らす。二度寝アラームのあとにまた戻ったら（3度寝）すぐ鳴らす",
            fontSize = 11.sp, color = Dopa.Muted,
        )
        VSpace(8.dp)
        val delay = config.resleepDelayMin
        if (delay == null) {
            SectionHint("M5Stack のファームウェアを更新すると選べます")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                M5Config.RESLEEP_DELAY_CHOICES.forEach { m ->
                    val selected = m == delay
                    Text(
                        "${m}分",
                        Modifier.clip(CircleShape).background(if (selected) Dopa.Lime else Dopa.Surface2)
                            .clickable(enabled = enabled && !selected) { onConfig(threshold.roundToInt(), confirm.roundToInt(), m) }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        color = if (selected) Dopa.LimeForeground else if (enabled) Dopa.Foreground else Dopa.Muted,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
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

/** 緊急解除の回数（合計・直近7日・しなかった日の連続）と、アプリごとの回数 */
@Composable
private fun EmergencySection(e: EmergencyStats) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("緊急解除") { SectionHint("1回でそのアプリだけ${Config.EMERGENCY_UNLOCK_SEC / 60}分使える") }
        DopaCard(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
            Row(Modifier.fillMaxWidth()) {
                listOf("合計" to "${e.count}回", "7日間" to "${e.week}回", "しなかった連続" to "${e.streak}日")
                    .forEachIndexed { i, (label, value) ->
                        Column(
                            Modifier.weight(1f).drawBehind {
                                if (i > 0) drawLine(Dopa.Border, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                            }.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(label, fontSize = 10.sp, color = Dopa.Muted, maxLines = 1)
                            FitText(value, 16.sp)
                        }
                    }
            }
        }
        if (e.byApp.isEmpty()) {
            SectionHint("まだ緊急解除していません")
            return@Column
        }
        val shape = RoundedCornerShape(24.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(Dopa.Surface).border(1.dp, Dopa.Border, shape)) {
            e.byApp.forEachIndexed { i, (pkg, n) ->
                key(pkg) {
                    if (i > 0) HorizontalDivider(color = Dopa.Border)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // パッケージが空 = 使用状況へのアクセスがなくスマホ全体を解除したとき
                        if (pkg.isEmpty()) {
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Dopa.Surface2))
                            HSpace(12.dp)
                            Text("スマホ全体", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        } else {
                            val app = rememberAppInfo(pkg)
                            AppIcon(app, 32.dp)
                            HSpace(12.dp)
                            Text(app.label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DisplayText("${n}回", 16.sp, color = Dopa.Alert)
                    }
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
