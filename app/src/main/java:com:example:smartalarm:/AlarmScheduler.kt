// src/main/java/com/example/smartalarm/AlarmScheduler.kt
package com.example.smartalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.smartalarm.data.AppDatabase
import com.example.smartalarm.data.ScheduledNight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

object AlarmScheduler {
    private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.US)

    fun scheduleNight(
        context: Context,
        hd: String,
        pw: String?,
        eb: String,
        priority: String
    ) {
        // compute user-specific knobs
        val plannedSleep = computePlannedSleepMs(hd, eb)
        val (e0, rg, sl) = BanditModel.nextParams(
            plannedSleep,
            Calendar.getInstance().get(Calendar.DAY_OF_WEEK),
            priority
        )

        // primary alarm before preferred/hard deadline
        val base = pw ?: hd
        val firstTrigger = computeTriggerTime(base).apply { add(Calendar.MINUTE, -e0) }
        scheduleAlarm(context, firstTrigger, rg, sl, hd, pw, eb, priority, isBackup = false)

        // backup alarm exactly at hard deadline
        val backupTrigger = computeTriggerTime(hd)
        scheduleAlarm(context, backupTrigger, 0.0, 0, hd, pw, eb, priority, isBackup = true)

        // persist tonight's schedule
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.get(context)
                .scheduledNightDao()
                .upsert(ScheduledNight(id = 1, hd = hd, pw = pw, eb = eb, priority = priority))
        }
    }

    private fun scheduleAlarm(
        context: Context,
        triggerTime: Calendar,
        rg: Double,
        sl: Int,
        hd: String,
        pw: String?,
        eb: String?,
        priority: String,
        isBackup: Boolean
    ) {
        // if trigger is in the past, schedule for tomorrow
        if (triggerTime.before(Calendar.getInstance())) {
            triggerTime.add(Calendar.DAY_OF_YEAR, 1)
        }

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val requestCode = if (isBackup) 1001 else 1000
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("RG", rg)
            putExtra("SL", sl)
            putExtra("HD", hd)
            putExtra("PW", pw)
            putExtra("EB", eb)
            putExtra("PR", priority)
            putExtra("BACKUP", isBackup)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime.timeInMillis,
            pi
        )
    }

    fun cancelAll(context: Context, hd: String) {
        val now = Calendar.getInstance()
        listOf(false, true).forEach { isBackup ->
            val cal = if (isBackup) computeTriggerTime(hd) else now
            val code = if (isBackup) 0 else cal.timeInMillis.toInt()
            val intent = Intent(context, AlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, code, intent, PendingIntent.FLAG_NO_CREATE
            )
            pi?.let { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(it) }
        }
    }

    private fun computePlannedSleepMs(hd: String, eb: String): Long {
        val hdCal = Calendar.getInstance().apply { time = TIME_FMT.parse(hd)!! }
        val ebCal = Calendar.getInstance().apply { time = TIME_FMT.parse(eb)!! }
        if (ebCal.after(hdCal)) ebCal.add(Calendar.DAY_OF_YEAR, -1)
        return hdCal.timeInMillis - ebCal.timeInMillis
    }

    private fun computeTriggerTime(timeStr: String): Calendar {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            time = TIME_FMT.parse(timeStr)!!
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            set(Calendar.MONTH, now.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
        }
        if (cal.before(now)) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal
    }
}