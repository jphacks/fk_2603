package com.fk2603.wakeup.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fk2603.wakeup.M5Exception
import com.fk2603.wakeup.M5Link
import com.fk2603.wakeup.Prefs
import com.fk2603.wakeup.SleepStore
import com.fk2603.wakeup.StatusHolder
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by StatusHolder.status.collectAsState()
    val error by StatusHolder.error.collectAsState()
    val sleeps by SleepStore.records.collectAsState()
    val latestSleep = sleeps.firstOrNull()
    remember { SleepStore.load(context) }

    var nickname by remember { mutableStateOf(prefs.nickname) }
    var message by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var ignoringBattery by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canOverlay = Settings.canDrawOverlays(context)
        ignoringBattery = power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun call(block: suspend () -> String) {
        scope.launch {
            message = try {
                block()
            } catch (e: M5Exception) {
                when (e.message) {
                    "locked" -> "アラーム中は変更できません"
                    "time not set" -> "M5Stack の時刻がまだ合っていません。少し待ってから試してください"
                    else -> "エラー: ${e.message}"
                }
            } catch (e: Exception) {
                "M5Stack に接続できません（Bluetooth）"
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!canOverlay) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("二度寝防止のため「他のアプリの上に重ねて表示」を許可してください")
                    Button(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }) { Text("設定を開く") }
                }
            }
        }

        if (!ignoringBattery) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("画面オフ中に監視が止まらないよう、電池の最適化を無効にしてください")
                    Button(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }) { Text("無効にする") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val s = status
                if (s == null) {
                    Text(error ?: "接続中…")
                } else {
                    Text(stateLabel(s.state), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        when {
                            s.alarmEpoch <= 0 -> "アラーム: 未設定"
                            s.state == "WAITING" -> "${formatEpoch(s.alarmEpoch)} までにベッドに入ると鳴ります"
                            else -> "アラーム: ${formatEpoch(s.alarmEpoch)}"
                        }
                    )
                    Text("ベッド: ${if (s.inBed) "寝ている" else "いない"}（raw ${s.pressureRaw}）")
                    Text("音量: ${s.volume}")
                    s.lastResult?.let {
                        Text("前回: ${formatDuration(it.durationSec)}で起床${if (it.resleep) "（二度寝後）" else ""}")
                    }
                    latestSleep?.let { Text("睡眠: ${formatSleep(it)}") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showPicker = true }) { Text("アラームをセット") }
            OutlinedButton(onClick = {
                call { M5Link.cancelAlarm(); "アラームを解除しました" }
            }) { Text("解除") }
        }
        message?.let { Text(it) }

        OutlinedTextField(
            value = nickname, onValueChange = { nickname = it },
            label = { Text("ニックネーム（ランキング表示名）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Button(onClick = {
            prefs.nickname = nickname
            message = "保存しました"
        }) { Text("保存") }
    }

    if (showPicker) {
        val now = LocalTime.now()
        val pickerState = rememberTimePickerState(now.hour, now.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("起きる時刻") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    showPicker = false
                    var target = ZonedDateTime.now()
                        .withHour(pickerState.hour).withMinute(pickerState.minute)
                        .withSecond(0).withNano(0)
                    if (!target.isAfter(ZonedDateTime.now())) target = target.plusDays(1)
                    call {
                        M5Link.setAlarm(target.toEpochSecond())
                        "${formatEpoch(target.toEpochSecond())} にセットしました"
                    }
                }) { Text("セット") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("キャンセル") } },
        )
    }
}
