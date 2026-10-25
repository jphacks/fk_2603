package com.fk2603.wakeup.ui.dopa

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fk2603.wakeup.ui.theme.Dopa
import java.time.LocalTime

/** アラーム時刻の入力（プロトの alarm-modal） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmTimeDialog(initial: LocalTime, onDismiss: () -> Unit, onSave: (hour: Int, minute: Int) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Dopa.Surface).border(2.dp, Dopa.Lime, shape).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DisplayText("NEW ALARM", 24.sp, Modifier.weight(1f))
                Text("✕", Modifier.clickable(onClick = onDismiss).padding(8.dp), color = Dopa.Foreground, fontSize = 18.sp)
            }
            VSpace(20.dp)
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = Dopa.Background,
                    clockDialSelectedContentColor = Dopa.LimeForeground,
                    clockDialUnselectedContentColor = Dopa.Foreground,
                    selectorColor = Dopa.Lime,
                    containerColor = Dopa.Surface,
                    timeSelectorSelectedContainerColor = Dopa.Lime,
                    timeSelectorUnselectedContainerColor = Dopa.Background,
                    timeSelectorSelectedContentColor = Dopa.LimeForeground,
                    timeSelectorUnselectedContentColor = Dopa.Foreground,
                ),
            )
            VSpace(12.dp)
            BigLimeButton("この時間にセット！", { onSave(state.hour, state.minute) }, fontSize = 16.sp)
        }
    }
}
