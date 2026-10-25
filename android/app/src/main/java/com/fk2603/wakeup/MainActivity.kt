package com.fk2603.wakeup

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fk2603.wakeup.ui.AppViewModel
import com.fk2603.wakeup.ui.dopa.AppShell
import com.fk2603.wakeup.ui.theme.DopaTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

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

        // 背景が黒なのでステータスバーの文字は白
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            DopaTheme {
                // 設定画面から戻ったら権限と記録を確認し直す
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    vm.refreshPermissions()
                    vm.refreshRecords()
                }
                AppShell(vm)
            }
        }
    }
}
