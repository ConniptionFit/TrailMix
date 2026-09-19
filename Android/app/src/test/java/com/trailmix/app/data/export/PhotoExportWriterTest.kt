package com.trailmix.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * INT-06: [PhotoExportWriter.identityName] is the pure half of the re-export idempotency fix —
 * the part a JVM test can reach without a real `DocumentFile`/`ContentResolver`. The actual
 * "already there, skip the copy" branch in [PhotoExportWriter.copyOne] needs SAF, so it stays
 * device-verified only, same as the rest of this file's Android-bound I/O.
 */
class PhotoExportWriterTest {

    @Test
    fun `keys the name off the MediaStore row id, keeping the extension`() {
        assertEquals("vacation-501.jpg", PhotoExportWriter.identityName("vacation.jpg", "501"))
    }

    @Test
    fun `same photo, same call, same name — the whole point of the fix`() {
        val first = PhotoExportWriter.identityName("vacation.jpg", "501")
        val second = PhotoExportWriter.identityName("vacation.jpg", "501")
        assertEquals(first, second)
    }

    @Test
    fun `different photos never collide`() {
        val a = PhotoExportWriter.identityName("vacation.jpg", "501")
        val b = PhotoExportWriter.identityName("vacation.jpg", "502")
        assert(a != b)
    }

    @Test
    fun `no extension is handled without a stray dot`() {
        assertEquals("IMG-501", PhotoExportWriter.identityName("IMG", "501"))
    }

    @Test
    fun `a null id falls back to the plain display name`() {
        assertEquals("vacation.jpg", PhotoExportWriter.identityName("vacation.jpg", null))
    }
}
