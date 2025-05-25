package com.example.smartalarm

import android.media.Ringtone

object UserActionHandler {
    var ringtone: Ringtone? = null
    var responded: Boolean = false
    var retryCount = 0
    var snoozeCount = 0

    fun stop() {
        responded = true
        ringtone?.stop()
        retryCount = 0
        snoozeCount = 0
    }
}