package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **Subagents: never-reused ids, a ceiling, and three states that are not two.**
 *
 * The operator's rules, exactly: *"when amount of running subagents is above the limit then the first
 * idle subagent is removed, and if chat agent tries to write to an agent (by id) who has been removed
 * then he gets exception"*, and *"folder stays, id inactive"*.
 *
 * The tests that matter are the ones about **identity**: an id that comes back is a report attached to
 * the wrong agent's work, and nothing would say so, because the id is the only thing tying the two
 * together.
 */
class AgentSubagentsTest {

    private lateinit var root: File
    private lateinit var account: File

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "subagents-${System.nanoTime()}")
        account = File(root, "muffyuniverse").apply { mkdirs() }
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun registry() = AgentSubagents(account)

    // ---- identity -----------------------------------------------------------

    @Test
    fun `ids are issued in order and never come back`() {
        val reg = registry()
        val one = reg.mint("groceries", "count the tests")
        val two = reg.mint("groceries", "read the log")

        assertEquals("the first is 1", 1, one.id)
        assertEquals("and the second is 2", 2, two.id)

        reg.retire("groceries", 1)
        val three = reg.mint("groceries", "something else")

        assertEquals(
            "**the third is 3, not 1** — a reused id would attach a report to work it never did",
            3,
            three.id,
        )
        assertNotEquals("and it is not the retired one", 1, three.id)
    }

    @Test
    fun `a retired subagent stays retired and keeps its place in the list`() {
        val reg = registry()
        val one = reg.mint("groceries", "the first task")
        reg.retire("groceries", one.id)

        val all = reg.read("groceries")

        assertEquals("it is still listed", 1, all.size)
        assertTrue("as retired", all.first().retired)
        assertEquals(
            "**with its task still on it** — the record is the only account of what it did",
            "the first task",
            all.first().task,
        )
    }

    @Test
    fun `each project has its own ids`() {
        // A subagent belongs to a project, and `#1` in two projects are two different agents. A
        // counter shared across the account would work and would make a report's `#1` ambiguous in
        // a way nothing could resolve.
        val reg = registry()
        assertEquals(1, reg.mint("groceries", "a").id)
        assertEquals("another project starts at 1 again", 1, reg.mint("notes", "b").id)
        assertEquals("and they do not see each other", 1, reg.read("groceries").size)
        assertEquals(1, reg.read("notes").size)
    }

    @Test
    fun `an id this project never issued is null, not invented`() {
        val reg = registry()
        reg.mint("groceries", "a")
        assertNull("no #7", reg.find("groceries", 7))
        assertNull("and no ids at all in a project with none", reg.find("notes", 1))
    }

    // ---- the count ----------------------------------------------------------

    @Test
    fun `lowering the limit retires the first idle subagents, oldest first`() {
        val reg = registry()
        val a = reg.mint("groceries", "a").also { reg.touched("groceries", it.id, at = 100) }
        val b = reg.mint("groceries", "b").also { reg.touched("groceries", it.id, at = 200) }
        val c = reg.mint("groceries", "c").also { reg.touched("groceries", it.id, at = 300) }

        val retired = reg.fitTo("groceries", limit = 1, busy = emptySet())

        assertEquals("two were retired", setOf(a.id, b.id), retired.toSet())
        assertEquals("and the newest is still live", listOf(c.id), reg.read("groceries").filter { !it.retired }.map { it.id })
    }

    @Test
    fun `a busy subagent is never retired, so lowering the limit cannot kill work`() {
        // **The rule: a stop is never a kill.** A settings change must not end a turn mid-write —
        // that is the half-file the app spends effort preventing — so the count settles when the
        // work finishes rather than when the number is typed.
        val reg = registry()
        val a = reg.mint("groceries", "a").also { reg.touched("groceries", it.id, at = 100) }
        val b = reg.mint("groceries", "b").also { reg.touched("groceries", it.id, at = 200) }

        val retired = reg.fitTo("groceries", limit = 0, busy = setOf(a.id, b.id))

        assertEquals("nothing was retired", emptyList<Int>(), retired)
        assertEquals("both are still live", 2, reg.read("groceries").count { !it.retired })
    }

    @Test
    fun `the busy one is skipped and the idle one retired`() {
        val reg = registry()
        val a = reg.mint("groceries", "a").also { reg.touched("groceries", it.id, at = 100) }
        val b = reg.mint("groceries", "b").also { reg.touched("groceries", it.id, at = 200) }

        // `a` is the oldest, but it is busy — so the rule must reach past it to `b`.
        val retired = reg.fitTo("groceries", limit = 1, busy = setOf(a.id))

        assertEquals("the idle one went, not the busy one", listOf(b.id), retired)
        assertFalse("and the busy one is still live", reg.read("groceries").first { it.id == a.id }.retired)
    }

    @Test
    fun `raising the limit creates nothing`() {
        // **A settings change is not a folder-creating event.** That is exactly the bug that made
        // the tick flake for two days: a reader that `mkdirs()`ed a project the person had deleted.
        // Raising the number says "more may exist", not "more do".
        val reg = registry()
        reg.mint("groceries", "a")

        reg.fitTo("groceries", limit = 5, busy = emptySet())

        assertEquals("still one", 1, reg.read("groceries").size)
        assertFalse(
            "and no subagent folders were made",
            File(account, "groceries/agent/2").exists(),
        )
    }

    // ---- the file -----------------------------------------------------------

    @Test
    fun `two instances over one account see the same subagents`() {
        // **The lost-update shape this class exists to avoid.** The screen writes the file when the
        // person changes the count and the runner writes it when a subagent starts, so a per-instance
        // lock would exclude nothing and the second write would silently undo the first.
        val one = AgentSubagents(account)
        val two = AgentSubagents(account)

        one.mint("groceries", "from the first instance")

        assertEquals(
            "the second instance sees it",
            1,
            two.read("groceries").size,
        )
        assertEquals("and mints the next id", 2, two.mint("groceries", "second").id)
    }

    @Test
    fun `a file that will not parse is not a reason to lose the subagents`() {
        // The record is in `agent/<id>/` and survives; the file only holds the counter and the
        // retired set. Starting from nothing is the honest answer, and it is what `AgentSessions`
        // does with its own file for the same reason.
        File(account, Workspace.STATE_DIR).mkdirs()
        File(File(account, Workspace.STATE_DIR), AgentSubagents.FILE).writeText("{not json")

        assertEquals("nothing is read from it", emptyList<AgentSubagents.Sub>(), registry().read("groceries"))
        assertEquals("and a new one can still be made", 1, registry().mint("groceries", "a").id)
    }
}
