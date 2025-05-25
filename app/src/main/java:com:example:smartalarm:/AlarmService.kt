package com.example.smartalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.*
import kotlin.math.roundToInt

class AlarmService : Service() {
    private val handler = Handler()
    private lateinit var ringtoneAction: Runnable

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val rg = intent.getDoubleExtra("RG", 10.0)
        val sl = intent.getIntExtra("SL", 30)
        val hd = intent.getStringExtra("HD")!!
        val isBackup = intent.getBooleanExtra("BACKUP", false)

        UserActionHandler.responded = false

        // Build foreground notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Alarm")
            .setContentText("Alarm is ringing")
            .setSmallIcon(R.drawable.ic_alarm)
            .build()
        startForeground(1, notification)

        // Volume ramp
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val steps = (3 * 60) / 5
        val stepVol = maxVol / steps
        var currentVol = 1
        am.setStreamVolume(AudioManager.STREAM_ALARM, currentVol, 0)
        ringtoneAction = object : Runnable {
            override fun run() {
                if (!UserActionHandler.responded && currentVol < maxVol) {
                    currentVol += stepVol
                    am.setStreamVolume(AudioManager.STREAM_ALARM, currentVol, 0)
                    handler.postDelayed(this, 5000)
                }
            }
        }
        handler.post(ringtoneAction)

        // Play ringtone
        val ringtone = RingtoneManager.getRingtone(this,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        UserActionHandler.ringtone = ringtone
        ringtone.play()

        // Schedule retry after ramp
        // When retry triggers
        handler.postDelayed({
            if (!UserActionHandler.responded && !isBackup) {
                // Log retry event
                CoroutineScope(Dispatchers.IO).launch {
                    AppDatabase.get(this@AlarmService)
                        .eventRecordDao().insert(EventRecord(type="RETRY"))
                }
                val origPw = intent.getStringExtra("PW")
                val origEb = intent.getStringExtra("EB")!!
                val origPr = intent.getStringExtra("PR")!!
                AlarmScheduler.scheduleNight(this, hd, origPw, origEb, origPr)
            }
        }, 3*60*1000 + 5000)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        UserActionHandler.ringtone?.stop()
        val hd = BanditModel.lastHd
        AlarmScheduler.cancelAll(this, hd)
        UserActionHandler.ringtone = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "Alarm Service", NotificationManager.IMPORTANCE_HIGH
            )
            (getSystemService(NotificationManager::class.java))
                .createNotificationChannel(chan)
        }
    }

    companion object {
        private const val CHANNEL_ID = "alarm_service"
    }
}
