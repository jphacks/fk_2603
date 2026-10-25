package com.fk2603.wakeup.ui.dopa

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.BlockCategory
import com.fk2603.wakeup.Config
import com.fk2603.wakeup.Gamification
import com.fk2603.wakeup.M5Config
import com.fk2603.wakeup.M5Link
import com.fk2603.wakeup.ui.AppViewModel
import com.fk2603.wakeup.ui.theme.Dopa
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("ホーム", Icons.Rounded.Home),
    STATS("戦績", Icons.Rounded.BarChart),
    DEVICE("デバイス", Icons.Rounded.Memory),
}

/** デモ（アプリ内だけで起床フローを再生する） */
private enum class DemoStep { ALARM, LOCK, CLEAR }

@Composable
fun AppShell(vm: AppViewModel) {
    val context = LocalContext.current
    val status by vm.status.collectAsState()
    val statusError by vm.statusError.collectAsState()
    val sleeps by vm.sleeps.collectAsState()
    val samples by vm.pressure.collectAsState()
    val mine by vm.myRecords.collectAsState()
    val ranking by vm.ranking.collectAsState()
    val myUid by vm.myUid.collectAsState()
    val recordsError by vm.recordsError.collectAsState()
    val nickname by vm.nickname.collectAsState()
    val blocked by vm.blocked.collectAsState()
    val warnings by vm.warnings.collectAsState()
    val usageAccess by vm.usageAccess.collectAsState()
    val message by vm.message.collectAsState()
    val loading by vm.loading.collectAsState()
    val clear by vm.clear.collectAsState()

    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    val nowSec = nowMs / 1000
    val connected = status != null && nowMs - M5Link.lastStatusMs < Config.BLE_STALE_MS
    val progress = remember(mine) { Gamification.progress(mine) }

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var demo by remember { mutableStateOf<DemoStep?>(null) }
    // アラームは端末内に保存。M5Stackの接続有無に関係なく一覧表示できる。
    val prefs = remember(context) { context.getSharedPreferences("dopa_local_alarms", android.content.Context.MODE_PRIVATE) }
    val localAlarms = remember {
        mutableStateListOf<LocalAlarm>().apply {
            val raw = prefs.getString("alarms", "[]") ?: "[]"
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val daysArray = obj.optJSONArray("days") ?: JSONArray()
                    val days = (0 until daysArray.length()).map { daysArray.getInt(it) }.toSet()
                    add(LocalAlarm(obj.getLong("id"), obj.getInt("hour"), obj.getInt("minute"), days, obj.optBoolean("enabled", true)))
                }
            }
        }
    }
    fun saveLocalAlarms() {
        val arr = JSONArray()
        localAlarms.forEach { alarm ->
            arr.put(JSONObject().apply {
                put("id", alarm.id)
                put("hour", alarm.hour)
                put("minute", alarm.minute)
                put("enabled", alarm.enabled)
                put("days", JSONArray().apply { alarm.weekdays.sorted().forEach { put(it) } })
            })
        }
        prefs.edit().putString("alarms", arr.toString()).apply()
    }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    val nextLocalAlarmEpoch = localAlarms.mapNotNull { it.nextEpoch() }.minOrNull()

    // ルーティンの保存。チェック状態はタスクIDと対象日を組にして管理する。
    val routinePrefs = remember(context) { context.getSharedPreferences("dopa_routines", android.content.Context.MODE_PRIVATE) }
    val routineTasks = remember {
        mutableStateListOf<MorningTask>().apply {
            runCatching {
                val arr = JSONArray(routinePrefs.getString("tasks", "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val days = obj.optJSONArray("days") ?: JSONArray()
                    add(MorningTask(obj.getLong("id"), obj.getString("title"),
                        (0 until days.length()).map { days.getInt(it) }.toSet(),
                        obj.optString("onlyDate").takeIf { it.isNotBlank() }))
                }
            }
        }
    }
    val doneKeys = remember {
        mutableStateListOf<String>().apply {
            runCatching {
                val arr = JSONArray(routinePrefs.getString("done", "[]") ?: "[]")
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        }
    }
    fun saveRoutines() {
        val arr = JSONArray()
        routineTasks.forEach { task ->
            arr.put(JSONObject().apply {
                put("id", task.id); put("title", task.title); put("onlyDate", task.onlyDate ?: "")
                put("days", JSONArray().apply { task.weekdays.sorted().forEach { put(it) } })
            })
        }
        routinePrefs.edit().putString("tasks", arr.toString())
            .putString("done", JSONArray(doneKeys.toList()).toString()).apply()
    }
    var showRoutineEditor by remember { mutableStateOf(false) }
    val missionDate = (nextLocalAlarmEpoch ?: status?.alarmEpoch?.takeIf {
        connected && it > 0 && status?.state in ALARM_SHOWN_STATES
    })?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        ?: LocalDate.now()
    val missionDateKey = missionDate.toString()
    val visibleTasks = routineTasks.filter { it.appliesTo(missionDate) }
    val completedForMission = visibleTasks.filter { "${missionDateKey}:${it.id}" in doneKeys }.map { it.id }.toSet()

    // 操作結果のメッセージは数秒で消す
    LaunchedEffect(message) {
        if (message != null) {
            delay(5000)
            vm.clearMessage()
        }
    }

    fun openSetting(w: SetupWarning) {
        val pkg = Uri.parse("package:${context.packageName}")
        val intent = when (w) {
            SetupWarning.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            SetupWarning.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            SetupWarning.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }
        runCatching { context.startActivity(intent) }
    }

    Box(Modifier.fillMaxSize().background(Dopa.Background)) {
        val c = clear
        when {
            demo != null -> DemoFlow(demo!!, nowSec, blocked, onStep = { demo = it }, clearUi = { vm.demoClear(it) })
            c != null -> {
                BackHandler { vm.dismissClear() }
                Box(Modifier.statusBarsPadding().navigationBarsPadding()) { ClearScreen(c, vm::dismissClear) }
            }
            else -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                AnimatedContent(tab, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                    when (t) {
                        Tab.HOME -> {
                            val s = status
                            HomeScreen(
                                HomeUi(
                                    nickname = nickname,
                                    level = progress.level,
                                    levelXp = progress.levelXp,
                                    streak = progress.streak,
                                    connected = connected,
                                    inBed = s?.inBed == true,
                                    state = s?.state,
                                    alarmEpoch = nextLocalAlarmEpoch ?: s?.alarmEpoch?.takeIf {
                                        connected && it > 0 && s.state in ALARM_SHOWN_STATES
                                    },
                                    localAlarms = localAlarms.toList(),
                                    routineDate = missionDateKey,
                                    routineTasks = visibleTasks,
                                    completedTaskIds = completedForMission,
                                    alarmLocked = s?.state in ALARM_ACTIVE_STATES,
                                    blocked = blocked,
                                    warnings = warnings,
                                    message = message ?: statusError?.takeIf { !connected },
                                ),
                                nowSec = nowSec,
                                onAddAlarm = { showPicker = true },
                                onCancelAlarm = vm::cancelAlarm,
                                onToggleLocalAlarm = { id, enabled ->
                                    val index = localAlarms.indexOfFirst { it.id == id }
                                    if (index >= 0) {
                                        localAlarms[index] = localAlarms[index].copy(enabled = enabled)
                                        saveLocalAlarms()
                                    }
                                },
                                onDeleteLocalAlarm = { pendingDeleteId = it },
                                onEditRoutine = { showRoutineEditor = true },
                                onToggleRoutineTask = { id ->
                                    val key = "${missionDateKey}:$id"
                                    if (key in doneKeys) doneKeys.remove(key) else doneKeys.add(key)
                                    saveRoutines()
                                },
                                onWarning = ::openSetting,
                                onDemo = { demo = DemoStep.ALARM },
                            )
                        }
                        Tab.STATS -> StatsScreen(
                            StatsUi(
                                nickname = nickname,
                                progress = progress,
                                ranking = ranking.map { RankRow(it.nickname.ifBlank { "名無し" }, it.durationSec, it.uid == myUid) },
                                sleeps = sleeps,
                                loading = loading,
                                message = message ?: recordsError,
                            ),
                            onRefresh = { vm.refreshPermissions(); vm.refreshRecords() },
                            onDeleteAll = { confirmDelete = true },
                        )
                        Tab.DEVICE -> {
                            val s = status
                            val latest = sleeps.firstOrNull()
                            DeviceScreen(
                                DeviceUi(
                                    connected = connected,
                                    lastStatusAgoSec = M5Link.lastStatusMs.takeIf { it > 0 }?.let { (nowMs - it) / 1000 },
                                    state = s?.state,
                                    volume = s?.volume,
                                    pressureRaw = s?.pressureRaw,
                                    inBed = s?.inBed == true,
                                    samples = samples,
                                    sleepStartAt = latest?.startAt,
                                    sleepEndAt = latest?.endAt?.takeIf { it > 0 },
                                    config = s?.config,
                                    configLocked = s?.locked == true,
                                    blocked = blocked,
                                    usageAccess = usageAccess,
                                    nickname = nickname,
                                    message = message,
                                ),
                                nowSec = nowSec,
                                onConfig = vm::setConfig,
                                onToggleBlock = vm::setBlocked,
                                onUsageAccess = { openSetting(SetupWarning.USAGE) },
                                onSaveNickname = vm::saveNickname,
                            )
                        }
                    }
                }
                NavBar(tab) { tab = it }
            }
        }
    }

    if (showRoutineEditor) {
        RoutineDialog(
            tasks = routineTasks.toList(),
            onDismiss = { showRoutineEditor = false },
            onAdd = { title, weekdays, onlyDate ->
                routineTasks.add(MorningTask(System.nanoTime(), title, weekdays, onlyDate))
                saveRoutines()
            },
            onDelete = { id ->
                routineTasks.removeAll { it.id == id }
                doneKeys.removeAll { it.endsWith(":$id") }
                saveRoutines()
            },
        )
    }

    if (showPicker) {
        AlarmTimeDialog(
            initial = status?.alarmEpoch?.takeIf { it > 0 }
                ?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime() }
                ?: LocalTime.of(7, 30),
            onDismiss = { showPicker = false },
            onSave = { h, m, days ->
                showPicker = false
                localAlarms.add(LocalAlarm(System.nanoTime(), h, m, days))
                saveLocalAlarms()
                // 既存のM5Stack側は単一アラーム仕様のため、ここでは自動送信しない。
            },
        )
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            containerColor = Dopa.Surface,
            title = { Text("アラームを削除") },
            text = { Text("このアラームを削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    localAlarms.removeAll { it.id == id }
                    saveLocalAlarms()
                    pendingDeleteId = null
                }) { Text("削除", color = Dopa.Alert) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("キャンセル", color = Dopa.Foreground)
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Dopa.Surface,
            title = { Text("記録を削除") },
            text = { Text("ランキングの記録と睡眠記録をすべて削除します。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteAll()
                }) { Text("削除", color = Dopa.Alert) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル", color = Dopa.Foreground) } },
        )
    }
}

/** アラームの時刻を表示する状態（WAITING の時刻は待ちの期限、RESLEEP_ARMED は再鳴動の時刻なので出さない） */
private val ALARM_SHOWN_STATES = setOf("ARMED", "AWAKE")
/** アラームを変えられない状態 */
private val ALARM_ACTIVE_STATES = setOf("RINGING", "RESLEEP_ARMED")

@Composable
private fun NavBar(tab: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp)
            .fillMaxWidth().clip(CircleShape).background(Dopa.Surface.copy(alpha = 0.9f))
            .border(1.dp, Dopa.Border, CircleShape).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Tab.entries.forEach { t ->
            val active = t == tab
            Row(
                Modifier.weight(1f).clip(CircleShape).background(if (active) Dopa.Lime else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(t) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (active) Dopa.LimeForeground else Dopa.Muted
                Icon(t.icon, null, tint = color, modifier = Modifier.size(16.dp))
                HSpace(6.dp)
                Text(t.label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DemoFlow(
    step: DemoStep,
    nowSec: Long,
    blocked: Set<BlockCategory>,
    onStep: (DemoStep?) -> Unit,
    clearUi: (Long) -> ClearUi,
) {
    BackHandler { onStep(null) }
    var elapsed by remember { mutableIntStateOf(134) }
    var pressure by remember { mutableIntStateOf(82) }
    var leaving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ClearUi?>(null) }
    LaunchedEffect(step) {
        while (step == DemoStep.LOCK) {
            delay(1000)
            elapsed++
        }
    }
    LaunchedEffect(leaving) {
        if (!leaving) {
            while (true) {
                pressure = 76 + (0..12).random()
                delay(700)
            }
        }
        while (pressure > 0) {
            delay(90)
            pressure = maxOf(0, pressure - 7)
        }
        delay(450)
        result = clearUi(elapsed.toLong())
        onStep(DemoStep.CLEAR)
    }
    val threshold = 30
    val lockUi = LockUi(
        alarm = step == DemoStep.ALARM,
        resleep = false,
        connected = true,
        inBedSec = elapsed.toLong(),
        pressureRaw = pressure * M5Config.RAW_MAX / 100,
        threshold = threshold * M5Config.RAW_MAX / 100,
        inBed = pressure >= threshold,
        blocked = blocked,
    )
    Box(Modifier.fillMaxSize()) {
        when (step) {
            DemoStep.ALARM -> AlarmScreen(lockUi, nowSec, onStop = { onStep(DemoStep.LOCK) })
            DemoStep.LOCK -> LockScreen(lockUi, onLeaveBed = { leaving = true }, leaving = leaving)
            DemoStep.CLEAR -> result?.let {
                Box(Modifier.statusBarsPadding().navigationBarsPadding()) { ClearScreen(it) { onStep(null) } }
            }
        }
    }
}
