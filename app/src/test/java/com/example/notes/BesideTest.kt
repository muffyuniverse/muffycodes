package com.example.notes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * One file written whole, or not written at all.
 *
 * The five writers this replaced — the agent's log, the app's trace, the session file, a background
 * result and the account's own bookkeeping — each had their own copy of the rule and had drifted.
 * What is tested here is the rule itself, once: the text arrives, an older text is replaced rather
 * than appended to, a folder that is not there is made, and nothing is left beside the file.
 */
class BesideTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a write leaves the text and nothing beside it`() {
        val file = File(tmp.root, "agents.json")

        Beside.write(file, """{"one":1}""")

        assertEquals("the text is there", """{"one":1}""", file.readText())
        assertFalse(
            "and the temporary is gone, so nothing is left for a reader to trip over",
            File(tmp.root, "agents.json.new").exists(),
        )
    }

    @Test
    fun `the old text is replaced rather than added to`() {
        val file = File(tmp.root, "trace.log")

        Beside.write(file, "the first line\nand a second\n")
        Beside.write(file, "only this\n")

        assertEquals(
            "a shorter write is the whole file, not the tail of the last one",
            "only this\n",
            file.readText(),
        )
    }

    @Test
    fun `a file whose folder is not there is made with its folder`() {
        val file = File(tmp.root, "account/state/agents.json")

        Beside.write(file, "[]")

        assertTrue("the folder was made", file.parentFile.isDirectory)
        assertEquals("and the file is in it", "[]", file.readText())
    }
}
