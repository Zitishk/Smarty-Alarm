package com.example.smartalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Start foreground service for ramp + retry logic
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            putExtras(intent.extras!!)
        }
        context.startForegroundService(serviceIntent)
    }
}