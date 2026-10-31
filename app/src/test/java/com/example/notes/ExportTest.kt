package com.example.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Putting a file where the phone can open it.
 *
 * **The two things worth testing are both about names.** A store that renames what it is given turns
 * `notes.md` into `notes.md.txt` — the trap `DownloadsSink` already records for the trace — and the MIME
 * type is what decides that. And a file that is not there must be refused rather than exported as an
 * empty one.
 *
 * The writing itself is `MediaStore`, which Robolectric does not implement; what can be checked here is
 * the decision that comes before it.
 */
// **`@Config(sdk = [34])`, because `MediaStore` and `ApplicationProvider` need a real Android
// environment** — this file is one of the few that needs no scenario, only a phone. `FeatureTest`
// would bring a whole signed-in account for nothing.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ExportTest {

    @Test
    fun `a markdown file is exported as markdown, so the store does not rename it`() {
        // **`text/plain` for `notes.md` makes the store write `notes.md.txt`**, because it appends an
        // extension for what the type says the file is. The type has to agree with the name.
        assertEquals("text/markdown", Export.mimeOf("notes.md"))
        assertEquals("application/json", Export.mimeOf("state.json"))
        assertEquals("application/zip", Export.mimeOf("app-release.zip"))
    }

    @Test
    fun `an apk keeps its own type, so Android offers to install it`() {
        assertEquals("application/vnd.android.package-archive", Export.mimeOf("app-release.apk"))
    }

    @Test
    fun `a type the app does not know is opaque, which leaves the name alone`() {
        // **The honest answer for anything unrecognised.** `application/octet-stream` is a type the
        // store has no extension for, so the name survives as it was.
        assertEquals("application/octet-stream", Export.mimeOf("trace.bin"))
        assertEquals("application/octet-stream", Export.mimeOf("no-extension-at-all"))
    }

    @Test
    fun `the type is read from the last dot, so a dotted name keeps its real one`() {
        assertEquals("text/markdown", Export.mimeOf("2026-10-31.notes.md"))
        assertEquals("image/png", Export.mimeOf("Screenshot.2026.10.31.png"))
    }

    @Test
    fun `an upper-case extension is the same type`() {
        assertEquals("image/jpeg", Export.mimeOf("PHOTO.JPG"))
        assertEquals("application/zip", Export.mimeOf("BUNDLE.ZIP"))
    }

    @Test
    fun `a file that is not there is refused rather than exported as nothing`() {
        // An export that silently produced an empty file would be worse than a refusal: the person
        // would find a zero-byte row in Downloads and have to work out why.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val missing = File(context.cacheDir, "not-here-${System.nanoTime()}.txt")

        val result = Export.copyToDownloads(context, missing)

        assertTrue("refused", result.isFailure)
        assertTrue(
            "and it says what is missing: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("no file"),
        )
        assertFalse("and nothing was written", missing.exists())
    }
}
