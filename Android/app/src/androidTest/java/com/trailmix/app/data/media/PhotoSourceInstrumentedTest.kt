package com.trailmix.app.data.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [PhotoSource] against the real `MediaStore` on a real device — this is exactly the
 * kind of query this project's own testing discipline says a JVM unit test (or a Robolectric
 * shadow) can't be trusted for; see [PhotoSourceTest] for the pure `buildSelection` half.
 *
 * Requires `READ_MEDIA_IMAGES` (or `READ_EXTERNAL_STORAGE` pre-33) to already be granted on
 * the test device — this suite doesn't request it, since a runtime permission prompt would
 * block an automated run. On the tethered Pixel 9 Pro it was granted manually while
 * device-verifying the photo-export feature.
 */
@RunWith(AndroidJUnit4::class)
class PhotoSourceInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val photoSource = PhotoSource(context)

    @Test
    fun photosBetween_aWindowInTheDistantPast_isEmpty() = runBlocking {
        // Jan 1 2000 — before smartphone cameras existed, so this is a real query the device's
        // MediaStore is guaranteed to answer with nothing, not a mocked/assumed result.
        val start = 946_684_800_000L
        val end = start + 60_000L

        val photos = photoSource.photosBetween(start, end)

        assertTrue(photos.isEmpty())
    }

    @Test
    fun photosBetween_realPhotosOnDevice_haveWellFormedMetadata() = runBlocking {
        if (!photoSource.hasPermission()) return@runBlocking // nothing to assert without it

        // A 10-year window is wide enough to catch whatever real photos exist on this device
        // without hardcoding an exact count, which would make this test fragile against the
        // device's actual, changing photo library.
        val end = System.currentTimeMillis()
        val start = end - java.util.concurrent.TimeUnit.DAYS.toMillis(3650)

        val photos = photoSource.photosBetween(start, end)

        photos.forEach { photo ->
            assertTrue("content:// URI expected, got ${photo.uri}", photo.uri.toString().startsWith("content://"))
            assertTrue("display name should not be blank", photo.displayName.isNotBlank())
            assertTrue("takenAtEpochMs should be a real timestamp", photo.takenAtEpochMs > 0)
        }
    }
}
