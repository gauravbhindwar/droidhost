package com.droidhost.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val autoStart = context.getSharedPreferences("droidhost_settings", Context.MODE_PRIVATE)
                .getBoolean("vm_auto_start", false) ||
                context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getBoolean("autoStart", false)

            if (autoStart) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ServerModeService::class.java).setAction(ServerModeService.ACTION_START)
                )
            }
        }
    }
}
