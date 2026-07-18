package com.trailmix.app.data.db

/**
 * REL-04 (v1.8.0): the Recently deleted recovery window. Deleted notes stay restorable
 * for one day, then the purge removes them for real.
 */
object RecentlyDeleted {
    const val RECOVERY_WINDOW_MS: Long = 24L * 60 * 60 * 1000

    fun isExpired(deletedAtEpochMs: Long, nowEpochMs: Long): Boolean =
        nowEpochMs - deletedAtEpochMs > RECOVERY_WINDOW_MS

    /** "Removes in 23h" / "Removes in 40m" / "Removing soon" — shown on each deleted row. */
    fun timeLeftLabel(deletedAtEpochMs: Long, nowEpochMs: Long): String {
        val leftMs = RECOVERY_WINDOW_MS - (nowEpochMs - deletedAtEpochMs)
        if (leftMs <= 0) return "Removing soon"
        val minutes = leftMs / 60_000
        return if (minutes >= 60) "Removes in ${minutes / 60}h" else "Removes in ${minutes}m"
    }
}
