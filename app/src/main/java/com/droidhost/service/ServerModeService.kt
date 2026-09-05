package com.droidhost.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.droidhost.MainActivity

class ServerModeService:LifecycleService(){
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int { super.onStartCommand(intent,flags,startId); when(intent?.action){ACTION_STOP->{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()};ACTION_START->{startForeground(NOTIFICATION_ID,notification())} }; return START_NOT_STICKY }
 private fun notification():Notification { val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT); return NotificationCompat.Builder(this,"server").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("DroidHost server mode").setContentText("ARM64 Linux VM is managed by DroidHost").setOngoing(true).setContentIntent(open).addAction(android.R.drawable.ic_media_pause,"Stop",PendingIntent.getService(this,1,Intent(this,ServerModeService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE)).build() }
 companion object { const val ACTION_START="com.droidhost.START";const val ACTION_STOP="com.droidhost.STOP";private const val NOTIFICATION_ID=1001 }
}
