package com.droidhost

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class DroidHostApplication:Application(){ override fun onCreate(){super.onCreate(); getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("server","Server mode",NotificationManager.IMPORTANCE_LOW))} }
