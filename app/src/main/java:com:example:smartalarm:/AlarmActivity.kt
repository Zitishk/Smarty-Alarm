// src/main/java/com/example/smartalarm/AlarmActivity.kt
package com.example.smartalarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smartalarm.data.AppDatabase
import com.example.smartalarm.data.EventRecord
import com.example.smartalarm.data.NightRecord
import com.example.smartalarm.data.ScheduledNight
import com.example.smartalarm.databinding.ActivityAlarmBinding
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

        // Use lifecycleScope so work is cancelled if Activity is destroyed
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(this@AlarmActivity).nightRecordDao().insert(
                NightRecord(
                    hd = hd,
                    pw = pw,
                    eb = eb,
                    priority = priority,
                    e0 = BanditModel.lastE0,
                    rg = BanditModel.lastRg,
                    sl = BanditModel.lastSl,
                    outcome = reward.name,
                    retries = UserActionHandler.retryCount,
                    snoozes = UserActionHandler.snoozeCount,
                    cycleEst = cycleEst
                )
            )
            // Clear scheduled night record
            AppDatabase.get(this@AlarmActivity).scheduledNightDao().upsert(
                ScheduledNight(1, "", null, "", "")
            )
        }

        BanditModel.updateReward(BanditModel.lastActionKey, reward.value)
        finish()
    }

    private fun onSnooze() {
        UserActionHandler.stop()

        lifecycleScope.launch(Dispatchers.IO) {
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