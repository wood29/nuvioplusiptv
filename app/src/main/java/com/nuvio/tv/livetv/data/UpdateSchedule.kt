package com.nuvio.tv.livetv.data

import java.util.Calendar

/**
 * Nuvio Plus: when automatic updates (channels, guide, films and series) are due.
 *
 * Like TiviMate overnight: with an update hour set (say 4:00), an update runs in the three hours
 * from then, so it's done while nobody watches (TV boxes keep the app running). If the box was
 * off at night and an update is a whole day overdue, it runs anyway (in the background).
 */
object UpdateSchedule {
    private const val HOUR = 60L * 60L * 1000L
    private const val WINDOW_HOURS = 3

    /**
     * @param ageMs how long ago it was last downloaded
     * @param everyHours the "update every" setting (0 = only when you press Update)
     * @param updateHour the hour updates run at (-1 = whenever due)
     */
    fun due(ageMs: Long, everyHours: Int, updateHour: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (everyHours <= 0) return false
        val interval = everyHours * HOUR
        if (updateHour !in 0..23) return ageMs > interval
        // Missed nights (box off): catch up.
        if (ageMs > interval + 24 * HOUR) return true
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val inWindow = (hour - updateHour + 24) % 24 < WINDOW_HOURS
        // Last night's update ran a little later in the window than tonight's check: a few hours'
        // margin so "every day" really means every night.
        return inWindow && ageMs > interval - minOf(6 * HOUR, interval / 4)
    }
}
