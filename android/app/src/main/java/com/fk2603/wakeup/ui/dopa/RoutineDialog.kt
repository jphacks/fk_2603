package com.fk2603.wakeup.ui.dopa

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fk2603.wakeup.ui.theme.Dopa
import java.time.LocalDate

/** タスクを登録・削除。毎週の曜日、または特定日を指定できる。 */
@Composable
fun RoutineDialog(
    tasks: List<MorningTask>,
    onDismiss: () -> Unit,
    onAdd: (String, Set<Int>, String?) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var days by remember { mutableStateOf((1..7).toSet()) }
    var singleDate by remember { mutableStateOf(false) }
    var dateText by remember { mutableStateOf(LocalDate.now().toString()) }
    val parsedDate = runCatching { LocalDate.parse(dateText) }.getOrNull()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Dopa.Surface).padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("MORNING ROUTINE", color = Dopa.Foreground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("タスク名") }, placeholder = { Text("例：顔を洗う") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "毎週", true to "指定日だけ").forEach { (single, label) ->
                    FilterChip(selected = singleDate == single, onClick = { singleDate = single }, label = { Text(label) })
                }
            }
            if (singleDate) {
                OutlinedTextField(
                    value = dateText, onValueChange = { dateText = it },
                    label = { Text("日付（例：2026-10-26）") },
                    isError = parsedDate == null, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("実行する曜日", color = Dopa.Foreground, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("月", "火", "水", "木", "金", "土", "日").forEachIndexed { index, label ->
                        val day = index + 1
                        val selected = day in days
                        Text(label, Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Dopa.Lime else Dopa.Background)
                            .clickable { days = if (selected) days - day else days + day }
                            .padding(horizontal = 9.dp, vertical = 10.dp),
                            color = if (selected) Dopa.LimeForeground else Dopa.Foreground, fontSize = 12.sp)
                    }
                }
            }
            Button(
                onClick = {
                    onAdd(title.trim(), if (singleDate) emptySet() else days, if (singleDate) parsedDate.toString() else null)
                    title = ""
                },
                enabled = title.isNotBlank() && (if (singleDate) parsedDate != null else days.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Dopa.Lime, contentColor = Dopa.LimeForeground),
            ) { Text("＋ タスクを追加") }
            HorizontalDivider()
            Text("登録済みタスク", color = Dopa.Foreground, fontWeight = FontWeight.Bold)
            if (tasks.isEmpty()) Text("まだ登録されていません", color = Dopa.Muted)
            tasks.forEach { task ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, color = Dopa.Foreground, fontSize = 13.sp)
                        val desc = task.onlyDate ?: if (task.weekdays.size == 7) "毎日" else
                            task.weekdays.sorted().joinToString("・") { listOf("月", "火", "水", "木", "金", "土", "日")[it - 1] }
                        Text(desc, color = Dopa.Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { onDelete(task.id) }) { Text("削除", color = Dopa.Alert) }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("閉じる", color = Dopa.Foreground) }
        }
    }
}
