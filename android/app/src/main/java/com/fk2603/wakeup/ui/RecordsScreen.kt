package com.fk2603.wakeup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fk2603.wakeup.Record
import com.fk2603.wakeup.RecordRepository
import kotlinx.coroutines.launch

@Composable
fun RecordsScreen() {
    val scope = rememberCoroutineScope()
    var ranking by remember { mutableStateOf<List<Record>>(emptyList()) }
    var mine by remember { mutableStateOf<List<Record>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        message = try {
            ranking = RecordRepository.ranking()
            mine = RecordRepository.myRecords()
            null
        } catch (e: Exception) {
            "読み込みに失敗しました: ${e.message}"
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { reloadKey++ }) { Text("更新") }
                Button(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("自分の記録をすべて削除") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        item { SectionTitle("ランキング（起きるまでが速い順）") }
        if (ranking.isEmpty()) item { Text("まだ記録がありません") }
        itemsIndexed(ranking) { i, r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${i + 1}. ${r.nickname}", fontWeight = if (i < 3) FontWeight.Bold else null)
                Text(formatDuration(r.durationSec))
            }
        }

        item { SectionTitle("自分の記録") }
        if (mine.isEmpty()) item { Text("まだ記録がありません") }
        items(mine) { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatEpoch(r.ringAt) + if (r.resleep) "（二度寝）" else "")
                Text(formatDuration(r.durationSec))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("記録を削除") },
            text = { Text("自分の記録をすべて削除します。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        message = try {
                            "${RecordRepository.deleteAllMine()} 件削除しました"
                        } catch (e: Exception) {
                            "削除に失敗しました: ${e.message}"
                        }
                        reloadKey++
                    }
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    HorizontalDivider()
}
