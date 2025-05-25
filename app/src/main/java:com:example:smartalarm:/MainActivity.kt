package com.example.smartalarm

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smartalarm.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import com.example.smartalarm.data.ScheduledNight
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