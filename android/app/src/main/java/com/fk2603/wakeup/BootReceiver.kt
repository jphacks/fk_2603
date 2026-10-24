package com.fk2603.wakeup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 端末再起動後も監視を再開する（再起動でロックを抜けられないように） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) MonitorService.start(context)
    }
}
