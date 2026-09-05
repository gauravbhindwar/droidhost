package com.droidhost.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver:BroadcastReceiver(){ override fun onReceive(context:Context,intent:Intent){ if(intent.action==Intent.ACTION_BOOT_COMPLETED && context.getSharedPreferences("settings",0).getBoolean("autoStart",false)){ ContextCompat.startForegroundService(context,Intent(context,ServerModeService::class.java).setAction(ServerModeService.ACTION_START)) } } }
