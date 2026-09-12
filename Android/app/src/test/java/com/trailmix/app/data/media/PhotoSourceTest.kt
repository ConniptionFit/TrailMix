package com.trailmix.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The actual `ContentResolver.query()` call needs a real device/emulator (see `androidTest`
 * once it exists) — this pins the one piece of [PhotoSource] that's pure: the selection
 * string and bind args, including the `DATE_TAKEN`-null fallback to `DATE_ADDED` (which is
 * stored in whole seconds, not milliseconds, unlike `DATE_TAKEN`).
 */
class PhotoSourceTest {

    @Test
    fun `selection matches DATE_TAKEN in millis or falls back to DATE_ADDED in seconds`() {
        val (selection, args) = PhotoSource.buildSelection(1_000_000L, 2_000_000L)

        assertEquals(
            "(datetaken BETWEEN ? AND ?) OR (datetaken IS NULL AND date_added BETWEEN ? AND ?)",
            selection,
        )
        assertEquals(listOf("1000000", "2000000", "1000", "2000"), args.toList())
    }
}
