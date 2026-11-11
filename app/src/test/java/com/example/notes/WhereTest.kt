package com.example.notes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Which screen is showing** — one ladder, read by the render, the trace and the report.
 *
 * The ladder used to be written three times, and two of the copies had already drifted: the report's
 * had no `chat` arm, so a report opened from a conversation recorded `browser/…`. A precedence ladder
 * written twice is a ladder that will disagree, so it is a value now and this is the test that says
 * what it means.
 */
class WhereTest {

    /** Nothing is showing but the browser, so each test names only the one thing it turns on. */
    private fun of(
        trace: Boolean = false,
        secrets: Boolean = false,
        settings: Boolean = false,
        history: List<String>? = null,
        editing: File? = null,
        chat: String? = null,
        accounts: Boolean = false,
        branches: Boolean = false,
    ) = Where.of(
        trace = trace,
        secrets = secrets,
        settings = settings,
        history = history,
        editing = editing,
        chat = chat,
        accounts = accounts,
        branches = branches,
    )

    @Test
    fun `with nothing open it is the browser`() {
        assertEquals(Where.Browser, of())
        assertEquals("browser", of().name())
    }

    @Test
    fun `a conversation is named as a conversation, which is what the report used to get wrong`() {
        // **The bug this test exists for.** The report's own copy of the ladder had no `chat` arm, so
        // a report opened from a conversation recorded `browser/…` — and the chat is exactly where a
        // wrong answer is noticed. The name comes from the one ladder now.
        assertEquals(Where.Chat("groceries"), of(chat = "groceries"))
        assertEquals("chat/groceries", of(chat = "groceries").name())
    }

    @Test
    fun `a window opened over settings sits above it`() {
        // **Nesting, not replacement.** Opening the trace from settings leaves settings set, so back
        // returns to it. The order is what expresses that, which is why it is asserted rather than
        // left to whichever arm happens to be written first.
        val nested = of(trace = true, secrets = true, settings = true)
        assertEquals("the trace is the topmost window", Where.Trace, nested)
        assertEquals("and closing it reveals the vault", Where.Secrets, of(secrets = true, settings = true))
        assertEquals("and closing that reveals settings", Where.Settings, of(settings = true))
    }

    @Test
    fun `a file's history outranks its editor, and the editor outranks the folder`() {
        // **The order is the whole of a bug that was fixed once already**: the editor stays open
        // underneath while its file's history is being looked at, so testing the editor first kept
        // drawing the editor and the history only appeared after Done had closed it.
        val file = File("/tmp/milk.txt")
        assertEquals(
            "history over editor",
            Where.History(listOf("milk.txt")),
            of(history = listOf("milk.txt"), editing = file, chat = "groceries"),
        )
        assertEquals("editor over chat", Where.Editor(file), of(editing = file, chat = "groceries"))
    }

    @Test
    fun `the accounts list and the branches screen are screens, not modes`() {
        // Each is a branch of its own because the back gesture has to return to it — a different
        // subject from the folder under it, which is why neither is a flag inside the browser.
        assertEquals(Where.Accounts, of(accounts = true))
        assertEquals(Where.Branches, of(branches = true))
        // **Both outrank the browser**, which is what makes back return to them rather than to the
        // folder underneath — the reason each is a screen rather than a mode.
        assertEquals("a branch choice is not the folder under it", "branches", of(branches = true).name())
        assertEquals("and the accounts list is not either", "accounts", of(accounts = true).name())
    }

    @Test
    fun `every name is distinct, so two screens cannot be filed as one`() {
        // A trace that named two screens the same word would make the log unable to answer "which
        // screen was this". The set is compared by size rather than by listing the words twice.
        val all = listOf(
            Where.Accounts,
            Where.Branches,
            Where.Chat("a"),
            Where.History(listOf("b")),
            Where.Editor(File("/tmp/c")),
            Where.Browser,
            Where.Settings,
            Where.Secrets,
            Where.Trace,
        )
        assertEquals("nine screens, nine names", all.size, all.map { it.name() }.toSet().size)
    }
}
