package com.example.smartalarm

/**
 * Represents various alarm outcomes with their corresponding reward weights.
 */
enum class Reward(val value: Double) {
    /** User woke up feeling refreshed */
    WAKE_REFRESHED(3.0),
    /** User woke up feeling groggy */
    WAKE_GROGGY(-2.0),
    /** Alarm reached hard deadline backup */
    FAIL_SAFE(-4.0),
    /** User tapped snooze */
    SNOOZE(-1.5),
    /** No-response retry event */
    RETRY(-0.7)
}