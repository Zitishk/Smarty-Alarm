package com.example.smartalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CoroutineScope(Dispatchers.IO).launch {
                AppDatabase.get(ctx).scheduledNightDao().get()?.let { s ->
                    AlarmScheduler.scheduleNight(
                        ctx, s.hd, s.pw, s.eb, s.priority
                    )
                }
            }
        }
    }
}