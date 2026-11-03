package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Where the log lands when Downloads is not available to it.
 *
 * ## Why this branch, and not the other one
 *
 * `MediaStore` is the path a phone actually takes, and it is the path this file cannot
 * honestly test: Robolectric answers `insert` and `openOutputStream` from a fake provider,
 * so a test here would prove that the fake worked. What it can prove is the promise both
 * paths make — **a line, once written, stays written** — and that is what a person
 * reads the log for.
 *
 * The bug this sits beside was not a wrong line, it was a missing file: the folder
 * appeared in Downloads and stayed empty, because append mode needs a file that already
 * exists. A test asserting "the file is there and holds what was written" is the shape
 * that would have caught it, which is why it is written here even though the branch under
 * it is the fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class DownloadsSinkTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun logFile(): File = File(app.getExternalFilesDir("trace"), DownloadsSink.NAME)

    @After
    fun tidy() {
        logFile().delete()
    }

    @Test
    fun `the name says which build wrote it, so no two builds collide`() {
        // **The operator's report**: *"the log name is dangerous, it duplicates itself and adds
        // suffixes (n). probably some more conventional naming where all trace logs are named after
        // the app build will be better."*
        //
        // The suffixes were not cosmetic. `trace.log.txt`, `trace.log (1).txt`, `trace.log (2).txt`,
        // `trace.log (3).txt` sat in Downloads — one per launch — and the newest was four days old
        // when a fresh report was expected. `MediaStore` appends ` (n)` rather than overwrite, so a
        // name that did not identify the build turned every failed lookup into another file.
        assertTrue(
            "the name carries the version: ${DownloadsSink.NAME}",
            DownloadsSink.NAME.contains(BuildConfig.VERSION_NAME),
        )
        assertTrue(
            "and the build number, because two dev builds share a version name: ${DownloadsSink.NAME}",
            DownloadsSink.NAME.contains(BuildConfig.VERSION_CODE.toString()),
        )
        assertTrue(
            "and it is a .log, not a name that grows a second extension: ${DownloadsSink.NAME}",
            DownloadsSink.NAME.endsWith(".log"),
        )
        // **The prefix is what a lookup searches by**, and it must match this build's own name or an
        // upgrade would never find the log it just wrote.
        assertTrue(
            "**the search prefix matches the name**: ${DownloadsSink.NAME} vs ${DownloadsSink.PREFIX}",
            DownloadsSink.NAME.startsWith(DownloadsSink.PREFIX),
        )
    }

    @Test
    fun `a debug build always writes the log, and there is no way to turn it off`() {
        // **The operator's question, as a rule**: *"why is there a switch? dev version is for
        // debugging - trace log should be on by default."*
        //
        // The switch cost a week. A session with it off produced a paste that looked like a healthy
        // session with no file behind it, and the person then had to remember it was off — which is
        // the thing nobody remembers. So the answer is not "default it on": it is that the state
        // cannot exist in a build whose whole purpose is being reported on.
        val name = "trace-${System.nanoTime()}"
        val settings = SettingsStore(app, name)

        assertTrue("**a debug build writes**", settings.liveTrace)

        // **And a preference left over from the old switch cannot turn it back off.** The build that
        // had the switch wrote `live_trace=false` when a person used it — so a phone upgrading to
        // this build still has that key on disk. Reading it again would resurrect the exact silence
        // this removes, on the phones that had already hit it.
        app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("live_trace", false)
            .commit()

        assertTrue(
            "**a stale `live_trace=false` is ignored**",
            SettingsStore(app, name).liveTrace,
        )
    }

    @Test
    fun `a fallback says why it fell back`() {
        // **The week this cost.** `dump()` printed no "also at" line when it had no file, and the
        // sink swallowed twelve exceptions in silence — seven of them saying nothing at all. So a
        // paste from a phone whose Downloads was refusing looked exactly like a paste from a phone
        // where everything worked, and the reason was discarded at the point it was known.
        //
        // sdk 28 takes the private route on purpose, so this asserts the reason exists on the path
        // that always falls back.
        val sink = DownloadsSink(app)
        sink.append(listOf("12:00:00.000    0 NOTE    app-opened  hi"))

        assertEquals(
            "**the fallback names its cause**: ${sink.refused}",
            "This Android is too old for Downloads; writing beside the app.",
            sink.refused,
        )
    }

    @Test
    fun `a sink that writes nowhere makes the paste say so`() {
        // **The case the operator hit**: nothing reached the disk, so `dump()` fell back to the
        // in-memory ring — and the paste looked exactly like a healthy session. A week went by on
        // that ambiguity.
        //
        // **The private fallback is not this case**, which is why this uses a sink that refuses
        // everything: on sdk 28 `DownloadsSink` writes the private file perfectly well and `dump()`
        // reads it back, printing "also at". That is a working log in an awkward place, and it must
        // not be dressed up as a failure.
        val dead = object : Trace.Sink {
            override fun append(lines: List<String>) = Unit
            override fun read(): String? = null
            override fun where(): String? = null
            override fun clear() = Unit
        }
        Trace.attach(dead)
        try {
            Trace.note("app-opened", "hi")
            val pasted = Trace.dump()

            assertTrue(
                "**the paste admits it is only memory**: ${pasted.lines().take(4)}",
                pasted.contains("**Not written to a file**"),
            )
        } finally {
            Trace.attach(null as Trace.Sink?)
        }
    }

    @Test
    fun `a paste says which of the three silences it is`() {
        // **Three different reasons for an empty file**, and the app used to give one sentence for
        // all of them: "the log is turned off". The operator's paste said exactly that while they had
        // not touched the switch — the app blaming them for its own silence.
        //
        // This pins the two states a sink can be in; the third (on, nothing yet) is the `where ==
        // null` branch and needs a fresh sink, which the next test covers.
        val off = Trace.dump()
        assertTrue(
            "**with no sink at all, it says so**: ${off.lines().take(3)}",
            off.contains("**Not written to a file**"),
        )
        assertTrue(
            "and does not claim a file it never wrote: $off",
            !off.contains("also at"),
        )
    }

    @Test
    fun `the first line makes the file`() {
        DownloadsSink(app).append(listOf("12:00:00.000    0 NOTE    app-opened  hi"))

        assertTrue("the file exists after the first line", logFile().isFile)
        assertEquals(
            "and holds exactly that line",
            "12:00:00.000    0 NOTE    app-opened  hi\n",
            logFile().readText(),
        )
    }

    @Test
    fun `a second line is added, not written over the first`() {
        val sink = DownloadsSink(app)
        sink.append(listOf("12:00:00.000    1 TAP     Save  milk.txt"))
        sink.append(listOf("12:00:01.000    1 CHANGED at / · 1 rows"))

        val text = logFile().readText()
        assertTrue("the tap survived: $text", text.contains("TAP"))
        assertTrue("and the answer came after it", text.endsWith("CHANGED at / · 1 rows\n"))
    }

    @Test
    fun `it says where the file it wrote actually is`() {
        val sink = DownloadsSink(app)
        sink.append(listOf("12:00:00.000    0 NOTE    app-opened  hi"))

        val where = sink.where()
        assertNotNull("it names a place", where)
        assertTrue(
            "and the place exists, rather than being a path to nothing: $where",
            File(where!!).isFile,
        )
    }

    @Test
    fun `clearing empties the file and keeps it`() {
        val sink = DownloadsSink(app)
        sink.append(listOf("12:00:00.000    0 NOTE    app-opened  hi"))
        sink.clear()

        assertTrue("the file is still there", logFile().isFile)
        assertEquals("and it is empty", "", logFile().readText())
    }
}
