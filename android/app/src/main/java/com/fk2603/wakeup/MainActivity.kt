package com.fk2603.wakeup

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.fk2603.wakeup.ui.HomeScreen
import com.fk2603.wakeup.ui.RecordsScreen
import com.fk2603.wakeup.ui.ScreenTimeScreen

class MainActivity : ComponentActivity() {
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M5Link は権限が揃うまで待ち、許可されたら自動で接続を始める
        val permissions = M5Link.requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionRequest.launch(permissions.toTypedArray())
        MonitorService.start(this)

        val prefs = Prefs(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                var tab by remember { mutableIntStateOf(0) }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("アラーム") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("記録・ランキング") })
                            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("分析") })
                        }
                        when (tab) {
                            0 -> HomeScreen(prefs)
                            1 -> RecordsScreen()
                            2 -> ScreenTimeScreen()
                        }
                    }
                }
            }
        }
    }
}
