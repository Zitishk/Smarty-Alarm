Advanced Alarm App

This Android application implements a privacy-first, sensor-free smart alarm using a Contextual Thompson-Sampling bandit to optimize wake-up parameters nightly.

Features

User inputs: Hard Deadline (HD), Preferred Wake (PW), Estimated Bedtime (EB), Wake Priority

Automatic calibration of E0 (start advance), RG (retry gap), SL (snooze length) via on-device bandit

Alarm flow with gentle ramp, retry, snooze, and hard fail-safe

On-device Room database for logs

Comments and log tags for easy external debugging


### app/build.gradle

```gradle
plugins {
    id 'com.android.application'
    id 'kotlin-android'
    id 'kotlin-kapt'
}
android {
    compileSdk 33
    defaultConfig {
        applicationId "com.example.smartalarm"
        minSdk 23
        targetSdk 33
        versionCode 2
        versionName "1.1"
    }
    buildFeatures { viewBinding true }
}
dependencies {
    implementation "org.jetbrains.kotlin:kotlin-stdlib:1.6.10"
    implementation 'androidx.core:core-ktx:1.7.0'
    implementation 'androidx.appcompat:appcompat:1.4.1'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.3'
    implementation 'androidx.room:room-runtime:2.4.2'
    kapt 'androidx.room:room-compiler:2.4.2'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.4.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0'
}


```

### app/src/main/AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.smartalarm">
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <application
        android:allowBackup="true"
        android:label="SmartAlarm"
        android:theme="@style/Theme.AppCompat.Light.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name=".AlarmActivity" android:exported="true" />
        <service android:name=".AlarmService" android:exported="false" />
        <receiver android:name=".AlarmReceiver" android:exported="true" />
        <receiver android:name=".BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

### app/src/main/java:com:example:smartalarm:/AlarmActivity.kt

```kt
// src/main/java/com/example/smartalarm/AlarmActivity.kt
package com.example.smartalarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.smartalarm.data.AppDatabase
import com.example.smartalarm.data.EventRecord
import com.example.smartalarm.data.NightRecord
import com.example.smartalarm.data.ScheduledNight
import com.example.smartalarm.databinding.ActivityAlarmBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlarmBinding
    private lateinit var hd: String
    private var pw: String? = null
    private lateinit var eb: String
    private lateinit var priority: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        intent.extras?.let {
            hd = it.getString("HD")!!
            pw = it.getString("PW")
            eb = it.getString("EB")!!
            priority = it.getString("PR")!!
            BanditModel.lastHd = hd
        }

        binding.refreshedBtn.setOnClickListener { onOutcome(Reward.WAKE_REFRESHED) }
        binding.groggyBtn.setOnClickListener  { onOutcome(Reward.WAKE_GROGGY)    }
        binding.snoozeBtn.setOnClickListener   { onSnooze()                       }
    }

    private fun onOutcome(reward: Reward) {
        UserActionHandler.stop()
        val cycleEst = BanditModel.cycleFilter(BanditModel.lastE0 + BanditModel.lastSl)
        // Log final wake
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.get(this@AlarmActivity).nightRecordDao().insert(
                NightRecord(
                    hd = hd, pw = pw, eb = eb, priority = priority,
                    e0 = BanditModel.lastE0, rg = BanditModel.lastRg, sl = BanditModel.lastSl,
                    outcome = reward.name,
                    retries = UserActionHandler.retryCount,
                    snoozes = UserActionHandler.snoozeCount,
                    cycleEst = cycleEst
                )
            )
            // Clear scheduled night
            AppDatabase.get(this@AlarmActivity).scheduledNightDao().upsert(
                ScheduledNight(1, "", null, "", "")
            )
        }
        BanditModel.updateReward(BanditModel.lastActionKey, reward.value)
        finish()
    }

    private fun onSnooze() {
        UserActionHandler.stop()
        // Log snooze event
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.get(this@AlarmActivity).eventRecordDao()
                .insert(EventRecord(type = "SNOOZE"))
        }
        UserActionHandler.snoozeCount++
        AlarmScheduler.scheduleNight(this, hd, pw, eb, priority)
        finish()
    }

    companion object {
        fun start(ctx: Context, extras: Bundle) {
            ctx.startActivity(
                Intent(ctx, AlarmActivity::class.java)
                    .putExtras(extras)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }
}
```

### app/src/main/java:com:example:smartalarm:/AlarmReceiver.kt

```kt
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
```

### app/src/main/java:com:example:smartalarm:/AlarmScheduler.kt

```kt
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
        val requestCode = if (isBackup) 0 else triggerTime.timeInMillis.toInt()
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
```

### app/src/main/java:com:example:smartalarm:/AlarmService.kt

```kt
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
                AlarmScheduler.scheduleNight(this, hd, pw=null, eb="", priority="")
            }
        }, 3*60*1000 + 5000)

    override fun onDestroy() {
        handler.removeCallbacks(ringtoneAction)
        UserActionHandler.ringtone?.stop()
        val hd = BanditModel.lastHd
        AlarmScheduler.cancelAll(this, hd)
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

```

### app/src/main/java:com:example:smartalarm:/BanditModel.kt

```kt
package com.example.smartalarm

import kotlin.random.Random

object BanditModel {
    private const val DELIM = "_"
    private val arms = mutableMapOf<String, Pair<Double, Double>>()
    var lastActionKey = ""
    var lastHd = ""
    var lastE0 = 0; var lastRg = 0.0; var lastSl = 0
    private var cycleEst = 90.0
    private const val KALMAN_GAIN = 0.15

    fun nextParams(plannedSleep: Long, dow: Int, priority: String): Triple<Int, Double, Int> {
        val e0Choices= listOf(15,25,35,45)
        val rgChoices= listOf(10.0,12.5,15.0)
        val slChoices= listOf(30,45,60,90)
        // Compute context score
        val sleepHrs = plannedSleep / (1000.0*60*60)
        val prioMap = mapOf("Low" to 1.0, "Medium" to 2.0, "High" to 3.0)
        val prioVal = prioMap[priority] ?: 2.0
        val contextScore =
            0.1 * (dow/7.0) +
            0.2 * (sleepHrs/12.0) +
            0.3 * (prioVal/3.0) +
            0.4 * (cycleEst/120.0)

        var bestKey=""; var bestSample=Double.NEGATIVE_INFINITY
        for(e0 in e0Choices) for(rg in rgChoices) for(sl in slChoices) {
            val key="$e0$DELIM$rg$DELIM$sl"
            val (mu0,var0)=arms.getOrDefault(key,0.0 to 1.0)
            val mu = mu0 + contextScore
            val sample = Random.nextGaussian()*kotlin.math.sqrt(var0) + mu
            if(sample>bestSample){bestSample=sample;bestKey=key}
        }
        val (e0,rg,sl) = bestKey.split(DELIM).let{Triple(it[0].toInt(),it[1].toDouble(),it[2].toInt())}
        // Store
        lastActionKey=bestKey; lastE0=e0; lastRg=rg; lastSl=sl
        return Triple(e0,rg,sl)
    }

    fun cycleFilter(observedCycle: Double): Double {
        cycleEst += KALMAN_GAIN*(observedCycle-cycleEst)
        return cycleEst
    }

    fun updateReward(actionKey:String,reward:Double){
        val (mu0,var0)=arms.getOrDefault(actionKey,0.0 to 1.0)
        val noise=1.0
        val postVar=1.0/(1.0/var0+1.0/noise)
        val postMu=(mu0/var0+reward/noise)*postVar
        arms[actionKey]=postMu to postVar
    }
}

```

### app/src/main/java:com:example:smartalarm:/BootReceiver.kt

```kt
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
```

### app/src/main/java:com:example:smartalarm:/MainActivity.kt

```kt
package com.example.smartalarm

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smartalarm.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.setupButton.setOnClickListener {
            val hdStr = binding.hdInput.text.toString()
            val pwStr = binding.pwInput.text.toString().takeIf { it.isNotBlank() }
            val ebStr = binding.ebInput.text.toString()

            val hd = validateTime(hdStr) ?: return@setOnClickListener
            val pw = pwStr?.let { validateTime(it) }
            val eb = validateTime(ebStr) ?: return@setOnClickListener

            val priority = binding.prioritySpinner.selectedItem.toString()
            AlarmScheduler.scheduleNight(this, hd, pw, eb, priority)

            CoroutineScope(Dispatchers.IO).launch {
                AppDatabase.get(this@MainActivity).scheduledNightDao()
                    .upsert(ScheduledNight(1, hd, pw, eb, priority))
            }

            Toast.makeText(this, "Alarm scheduled for $hd", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateTime(input: String): String? = try {
        timeFmt.parse(input)
        input
    } catch (e: ParseException) {
        Toast.makeText(this, "Invalid time: $input", Toast.LENGTH_SHORT).show()
        null
    }
}
```

### app/src/main/java:com:example:smartalarm:/UserActionHandler.kt

```kt
package com.example.smartalarm

import android.media.Ringtone

object UserActionHandler {
    var ringtone: Ringtone? = null
    var responded: Boolean = false

    fun stop() {
        responded = true
        ringtone?.stop()
    }
}
```

### app/src/main/java:com:example:smartalarm:/data/AppDatabase.kt

```kt
@Database(
    entities = [NightRecord::class, ScheduledNight::class, EventRecord::class],
    version = 2
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nightRecordDao(): NightRecordDao
    abstract fun scheduledNightDao(): ScheduledNightDao
    abstract fun eventRecordDao(): EventRecordDao
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext,
                AppDatabase::class.java, "smart_alarm.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
```

### app/src/main/java:com:example:smartalarm:/data/EventRecord.kt

```kt
@Entity(tableName = "event_records")
data class EventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // "RETRY" or "SNOOZE"
    val timestamp: Long = System.currentTimeMillis()
)
```

### app/src/main/java:com:example:smartalarm:/data/EventRecordDao.kt

```kt
@Dao
interface EventRecordDao {
    @Insert suspend fun insert(event: EventRecord)

    @Query("SELECT * FROM event_records ORDER BY timestamp DESC LIMIT 100")
    suspend fun recent(): List<EventRecord>
}
```

### app/src/main/java:com:example:smartalarm:/data/NightRecord.kt

```kt
@Entity(tableName = "night_records")
data class NightRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hd: String,
    val pw: String?,
    val eb: String,
    val priority: String,
    val e0: Int,
    val rg: Double,
    val sl: Int,
    val outcome: String,
    val retries: Int,
    val snoozes: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val cycleEst: Double
)
```

### app/src/main/java:com:example:smartalarm:/data/NightRecordDao.kt

```kt
package com.example.smartalarm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NightRecordDao {
    @Insert
    suspend fun insert(record: NightRecord)

    @Query("SELECT * FROM night_records ORDER BY timestamp DESC LIMIT 30")
    suspend fun recent(): List<NightRecord>
}
```

### app/src/main/java:com:example:smartalarm:/data/ScheduledNight.kt

```kt
@Entity(tableName = "scheduled_night")
data class ScheduledNight(
    @PrimaryKey val id: Int = 1,
    val hd: String,
    val pw: String?,
    val eb: String,
    val priority: String
)
```

### app/src/main/java:com:example:smartalarm:/data/ScheduledNightDao.kt

```kt
import androidx.room.OnConflictStrategy
import com.example.smartalarm.data.NightRecord

@Dao
interface ScheduledNightDao {
    @Query("SELECT * FROM scheduled_night WHERE id=1")
    suspend fun get(): ScheduledNight?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(night: ScheduledNight)
}
```

### app/src/main/res/drawables/ic_alarm.xml

```xml
<!-- src/main/res/drawable/ic_alarm.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18.3,16v-5c0,-3.07 -1.63,-5.64 -4.5,-6.32V4
            c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68
            C7.34,5.36 5.7,7.92 5.7,11v5l-1.7,1.7v0.8h16v-0.8L18.3,16z"/>
</vector>
```

### app/src/main/res/layout/activity_alarm.xml

```xml
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="32dp">

    <Button
        android:id="@+id/refreshedBtn"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="I'm Refreshed"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <Button
        android:id="@+id/groggyBtn"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="I'm Groggy"
        app:layout_constraintTop_toBottomOf="@id/refreshedBtn"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="16dp" />

    <Button
        android:id="@+id/snoozeBtn"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="Snooze"
        app:layout_constraintTop_toBottomOf="@id/groggyBtn"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="16dp" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

### app/src/main/res/layout/activity_main.xml

```xml
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="16dp">

    <EditText
        android:id="@+id/hdInput"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:hint="Hard Deadline (HH:mm)"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <EditText
        android:id="@+id/pwInput"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:hint="Preferred Wake (HH:mm, optional)"
        app:layout_constraintTop_toBottomOf="@id/hdInput"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp" />

    <EditText
        android:id="@+id/ebInput"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:hint="Estimated Bedtime (HH:mm)"
        app:layout_constraintTop_toBottomOf="@id/pwInput"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp" />

    <Spinner
        android:id="@+id/prioritySpinner"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:entries="@array/priority_levels"
        app:layout_constraintTop_toBottomOf="@id/ebInput"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp" />

    <Button
        android:id="@+id/setupButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Set Alarm"
        app:layout_constraintTop_toBottomOf="@id/prioritySpinner"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="16dp" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

### app/src/main/res/layout/boot_receiver.xml

```xml
<receiver xmlns:android="http://schemas.android.com/apk/res/android"
    android:name="com.example.smartalarm.BootReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

### app/src/main/res/values/arrays.xml

```xml
<resources>
    <string-array name="priority_levels">
        <item>Low</item>
        <item>Medium</item>
        <item>High</item>
    </string-array>
</resources>
```

### app/src/main/res/values/strings.xml

```xml
<!-- src/main/res/values/strings.xml -->
<resources>
    <string name="app_name">SmartAlarm</string>
    <string name="hd_hint">Hard Deadline (HH:mm)</string>
    <string name="pw_hint">Preferred Wake (HH:mm, optional)</string>
    <string name="eb_hint">Estimated Bedtime (HH:mm)</string>
    <string name="set_alarm">Set Alarm</string>
    <string name="alarm_scheduled">Alarm scheduled for %1$s</string>
    <string name="invalid_time">Invalid time: %1$s</string>
    <string name="im_refreshed">I'm Refreshed</string>
    <string name="im_groggy">I'm Groggy</string>
    <string name="snooze">Snooze</string>
</resources>
```

