package com.droidhost.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.droidhost.MainActivity
import com.droidhost.data.HttpAgentRepository
import com.droidhost.domain.VmState
import kotlinx.coroutines.launch

class ServerModeService : LifecycleService() {

    private lateinit var vmManager: VmManager

    override fun onCreate() {
        super.onCreate()
        val token = getSharedPreferences("agent", MODE_PRIVATE).getString("token", "").orEmpty()
        val repo = HttpAgentRepository("http://127.0.0.1:8899", token)
        vmManager = VmManager.getInstance(this, repo)

        lifecycleScope.launch {
            vmManager.vmState.collect { state ->
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, notification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                vmManager.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, notification(vmManager.vmState.value))
                vmManager.start()
            }
            ACTION_RESTART -> {
                startForeground(NOTIFICATION_ID, notification(VmState.STARTING))
                vmManager.restart()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(state: VmState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = when (state) {
            VmState.RUNNING -> "ARM64 Linux VM is running"
            VmState.STARTING -> "ARM64 Linux VM is booting..."
            VmState.STOPPING -> "ARM64 Linux VM is shutting down..."
            VmState.STOPPED -> "ARM64 Linux VM is stopped"
            VmState.FAILED -> vmManager.lastError.value ?: "VM failed to boot"
        }

        val builder = NotificationCompat.Builder(this, "server")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("DroidHost Server Mode")
            .setContentText(statusText)
            .setOngoing(state == VmState.RUNNING || state == VmState.STARTING)
            .setContentIntent(open)

        if (state == VmState.RUNNING || state == VmState.STARTING) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Stop VM",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, ServerModeService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Start VM",
                PendingIntent.getService(
                    this,
                    2,
                    Intent(this, ServerModeService::class.java).setAction(ACTION_START),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        return builder.build()
    }

    companion object {
        const val ACTION_START = "com.droidhost.START"
        const val ACTION_STOP = "com.droidhost.STOP"
        const val ACTION_RESTART = "com.droidhost.RESTART"
        private const val NOTIFICATION_ID = 1001
    }
}
