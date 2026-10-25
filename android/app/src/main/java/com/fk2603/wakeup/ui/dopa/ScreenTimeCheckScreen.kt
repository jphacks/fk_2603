package com.fk2603.wakeup.ui.dopa

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fk2603.wakeup.ui.theme.Dopa

/**
 * スマホ使用データが読めるかの確認画面（簡易。本番の UI はチームで作る）
 * 設定画面と同じ扱いなのでスクロールする
 */
@Composable
fun ScreenTimeCheckScreen(ui: ScreenTimeUi, onReload: () -> Unit, onAccess: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SCREEN TIME", fontSize = 12.sp, color = Dopa.Muted)
                DisplayText("スマホ使用データ", 22.sp)
            }
            SmallButton("戻る", onClose)
        }
        ui.error?.let { Text(it, color = Dopa.Alert, fontSize = 12.sp) }

        DopaCard(Modifier.fillMaxWidth()) {
            Line("使用状況へのアクセス", if (ui.access) "許可済み" else "未許可", if (ui.access) Dopa.Lime else Dopa.Alert)
            VSpace(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ui.access) SmallButton("許可する", onAccess)
                SmallButton(if (ui.loading) "読込中…" else "今すぐ読み込む", onReload, enabled = !ui.loading)
            }
        }

        SectionLabel("ANDROID に残っている記録")
        DopaCard(Modifier.fillMaxWidth()) {
            Line("日ごと", ui.retentionDaily?.let { "${it}日" } ?: "-")
            Line("週ごと", ui.retentionWeekly?.let { "${it}件" } ?: "-")
            Line("月ごと", ui.retentionMonthly?.let { "${it}件" } ?: "-")
            VSpace(6.dp)
            SectionHint("日ごとは数日〜1週間で消えるので、1時間ごとに端末へ写して貯めます")
        }

        SectionLabel("導入前との比較の基準")
        DopaCard(Modifier.fillMaxWidth()) {
            Line("導入日", ui.installDay?.toString() ?: "まだ（読み込むと今日になります）")
            Line("導入前の1日平均", ui.baselineAvgMs?.let { formatDurationMs(it) } ?: "-")
            Line("元にした記録", ui.baselineSource?.let { "$it（${"%.1f".format(ui.baselineDays ?: 0.0)}日分）" } ?: "-")
            Line("端末に貯めた日数", "${ui.storedDays}日")
        }

        SectionLabel("緊急解除")
        DopaCard(Modifier.fillMaxWidth()) {
            Line("緊急解除した回数", "${ui.unlockCount}回")
            Line("緊急解除なしの連続", "${ui.noUnlockStreak}日")
            Line("最高記録", "${ui.bestStreak}日")
        }

        SectionLabel("日ごとの合計（Android から読めた分）")
        DopaCard(Modifier.fillMaxWidth()) {
            if (ui.androidDaily.isEmpty()) SectionHint(if (ui.access) "記録がありません" else "許可すると表示されます")
            ui.androidDaily.forEach { (day, ms) -> Line(day.toString(), formatDurationMs(ms)) }
        }
    }
}

@Composable
private fun Line(label: String, value: String, color: androidx.compose.ui.graphics.Color = Dopa.Foreground) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = Dopa.Muted)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/** 例: 3時間12分 */
fun formatDurationMs(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60}時間${min % 60}分" else "${min}分"
}
