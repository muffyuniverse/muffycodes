package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **What a connection window may store**, asked as rules rather than as a screen.
 *
 * The same reason [GitCredentialsTest] exists: the sentences are the feature. A window that silently
 * saved half a pair would send a key to a host it was not stored for, and a window that refused a
 * complete one would make a self-hosted server impossible to reach — so both directions are asserted
 * here, without a keystore, a remote or a Compose tree.
 */
class ConnectionDraftTest {

    // ---- the llm window ----------------------------------------------------------------

    @Test
    fun `an address with no model is kept, because the server is the one that refuses it`() {
        // **The cap is gone** (operator, 2026-11-09). A model id is still a name the server assigns, so
        // this combination is probably wrong — and the window no longer refuses to close over it,
        // because the answer is the server's: it names the model it does not have, which is a better
        // sentence than "say which model this server serves" from a form that cannot know.
        val draft = ConnectionDraft(
            kind = ConnKind.LLM,
            address = "https://models.example.org/v1",
        )

        assertNull("nothing to refuse: ${draft.problemSaid()}", draft.problemSaid())
        assertEquals(
            "and the address is written as its own field",
            "https://models.example.org/v1",
            draft.connection()?.address,
        )
        assertNull("with no model written, so that one is inherited", draft.choice())
    }

    @Test
    fun `the three numbers are written as typed, and a blank one is not written`() {
        val draft = ConnectionDraft(
            kind = ConnKind.LLM,
            target = "60000",
            forget = "0",
            trim = "250000",
        )

        assertNull("nothing to refuse", draft.problemSaid())
        assertEquals(
            "blank and zero are different: 0 turns the hand off, blank inherits",
            Choice(target = 60_000, forget = 0, trim = 250_000),
            draft.choice(),
        )
        assertNull("and no pair is written by numbers alone", draft.connection())
    }

    @Test
    fun `a number that is not a number is refused, naming the field`() {
        val said = ConnectionDraft(kind = ConnKind.LLM, trim = "12k").problemSaid()

        assertTrue("it refuses: $said", said != null)
        assertTrue("naming the field: $said", "trim" in said.orEmpty())
    }

    @Test
    fun `a field left at the value it inherits is not written`() {
        // **The window opens on the values in force**, so "left alone" and "typed the same" look
        // identical in the text. Saving what did not change would pin the parent's value at this scope,
        // where it would stop following the parent — the opposite of changing only what you need.
        val inForce = ConnectionDraft.opening(
            kind = ConnKind.LLM,
            at = "https://openrouter.ai/api/v1",
            model = "deepseek/deepseek-v4.1-flash",
            effort = "high",
            limits = ContextBudget.Limits.of(trigger = 100_000, hard = 250_000, target = 60_000),
            shape = Shape.CHAT,
        )
        val typed = inForce.copy(model = "llama3.3:70b")

        val changed = typed.changesAgainst(inForce)

        assertEquals("the model is the only thing that changed", "llama3.3:70b", changed.model)
        assertNull("so no pair is written", changed.connection())
        assertEquals(
            "and only the model is in the choice",
            Choice(model = "llama3.3:70b"),
            changed.choice(),
        )
    }

    @Test
    fun `a window whose fields were all left alone writes nothing at all`() {
        val inForce = ConnectionDraft.opening(
            kind = ConnKind.LLM,
            at = "https://openrouter.ai/api/v1",
            model = "a-model",
            limits = ContextBudget.Limits.of(trigger = 100_000, hard = 0, target = 60_000),
            shape = Shape.CHAT,
        )

        val changed = inForce.changesAgainst(inForce)

        assertNull("no pair", changed.connection())
        assertNull("no choice", changed.choice())
    }

    @Test
    fun `a model on its own is allowed, because it inherits the address and the key`() {
        // **The case the split exists for**: the same server, a cheaper model on one project. Nothing
        // about the pair is typed, so nothing about the pair is written — and the model is.
        val draft = ConnectionDraft(kind = ConnKind.LLM, model = "llama3.3:70b", effort = "low")

        assertNull("it is allowed", draft.problemSaid())
        assertNull("no pair is written", draft.connection())
        assertEquals(Choice("llama3.3:70b", "low"), draft.choice())
    }

    @Test
    fun `an address without a scheme is refused`() {
        // Without one there is nothing to join the request's path to, and the failure would be a
        // malformed URL from inside a socket rather than a sentence on the window.
        val said = ConnectionDraft(
            kind = ConnKind.LLM,
            address = "localhost:11434/v1",
            model = "llama3",
        ).problemSaid()

        assertTrue("it refuses: $said", said != null)
        assertTrue("and says what an address is: $said", "scheme" in said.orEmpty())
    }

    @Test
    fun `a keyless server is a complete pair`() {
        // **A model server on this phone wants no key**, and that is a whole credential rather than
        // half of one. The pair is stored with an empty secret, and the request will carry no
        // Authorization header (see `Wired.keyless` and `Door.headers`).
        val draft = ConnectionDraft(
            kind = ConnKind.LLM,
            address = "http://127.0.0.1:11434/v1",
            model = "llama3",
        )

        assertNull("it is allowed", draft.problemSaid())
        val connection = draft.connection()
        assertEquals("http://127.0.0.1:11434/v1", connection?.address)
        assertEquals("with no key at all", "", connection?.secret)
    }

    @Test
    fun `an effort the app does not know is sent, because the server is the one that judges it`() {
        // **This used to refuse, and the refusal was wrong.** `effortRank` came from the catalogue's
        // `supported_efforts`, which is OpenRouter's field — so the app was refusing a word on the
        // authority of one vendor's list, for a request that goes to whatever server the address names.
        // A server that does not take the word says so itself, in its own sentence, naming what it does
        // take; a window that will not close cannot.
        val said = ConnectionDraft(kind = ConnKind.LLM, model = "m", effort = "extreme").problemSaid()

        assertEquals("so it is accepted", null, said)
    }

    @Test
    fun `a shape with a scheme in its path is refused`() {
        // **The one rule here that is a security boundary rather than a predictability rule.** A shape
        // whose url had a scheme could send the request — and with it the key — to a host nobody chose.
        val said = ConnectionDraft(
            kind = ConnKind.LLM,
            address = "https://models.example.org/v1",
            model = "m",
            shapeUrl = "https://elsewhere.example/steal",
            shapeBody = """{"model":"{{model}}"}""",
        ).problemSaid()

        assertTrue("it refuses: $said", said != null)
        assertTrue("and says what a url is: $said", "not an address" in said.orEmpty())
    }

    @Test
    fun `a body that is not JSON is said to be not JSON`() {
        // Not "a missing placeholder": the person has typed something that cannot be parsed, and the
        // difference decides what they do next.
        val said = ConnectionDraft(
            kind = ConnKind.LLM,
            address = "https://models.example.org/v1",
            model = "m",
            shapeUrl = "chat/completions",
            // **Not JSON at all**, rather than a trailing comma: Android's parser tolerates that one,
            // which is exactly the sort of acceptance a test must not rely on.
            shapeBody = """{"model": """,
        ).problemSaid()

        assertTrue("it refuses: $said", said != null)
        assertTrue("and says what is wrong: $said", "not JSON" in said.orEmpty())
    }

    @Test
    fun `a placeholder the app cannot fill is refused by name`() {
        val said = ConnectionDraft(
            kind = ConnKind.LLM,
            address = "https://models.example.org/v1",
            model = "m",
            shapeUrl = "chat/completions",
            shapeBody = """{"model":"{{model}}","who":"{{mood}}"}""",
        ).problemSaid()

        assertTrue("it refuses: $said", said != null)
        assertTrue("naming the placeholder: $said", "{{mood}}" in said.orEmpty())
        assertTrue("and what it can fill: $said", "{{messages}}" in said.orEmpty())
    }

    @Test
    fun `a stored shape keeps its own path and body`() {
        val draft = ConnectionDraft(
            kind = ConnKind.LLM,
            address = "http://127.0.0.1:11434",
            model = "llama3",
            shapeKind = ShapeKind.CHAT,
            shapeUrl = "api/chat",
            shapeBody = """{"model":"{{model}}","messages":"{{messages}}"}""",
        )

        val shape = draft.connection()?.shapeFor(ShapeKind.CHAT)

        assertEquals("api/chat", shape?.url)
        assertNull("and it is a shape the app can fill", shape?.problem())
        assertEquals(
            "and the shape is stored under the kind it was written for",
            "api/chat",
            draft.connection()?.shapeFor(ShapeKind.CHAT)?.url,
        )
    }

    @Test
    fun `nothing typed at all writes nothing`() {
        // A window opened and saved unchanged is a no-op rather than a row of empty strings, which is
        // the whole of "leaving a field alone writes nothing for it".
        val draft = ConnectionDraft(kind = ConnKind.LLM)

        assertNull("no complaint", draft.problemSaid())
        assertNull("no pair", draft.connection())
        assertNull("no choice", draft.choice())
    }

    // ---- the git window ----------------------------------------------------------------

    @Test
    fun `a git window keeps the host and refuses an address`() {
        // **The remote's address is git's own fact.** Pasting one here would store a second copy of it,
        // and the copy is the one that goes stale when the address is changed.
        val said = ConnectionDraft(
            kind = ConnKind.GIT,
            address = "https://gitlab.com/someone/shopping.git",
            secret = "glpat",
        ).problemSaid()

        assertTrue("it refuses: $said", said != null)
        assertTrue("and names what it wants instead: $said", "host" in said.orEmpty())
    }

    @Test
    fun `a git window with a host and a token stores both, and the name`() {
        val draft = ConnectionDraft(
            kind = ConnKind.GIT,
            address = "gitlab.com",
            secret = "glpat-the-key",
            user = "oauth2",
        )

        assertNull("it is allowed", draft.problemSaid())
        val connection = draft.connection()
        assertEquals("gitlab.com", connection?.address)
        assertEquals("glpat-the-key", connection?.secret)
        assertEquals("oauth2", connection?.user)
        assertNull("a git row has no model of its own", draft.choice())
    }

    @Test
    fun `a git window offered a whole URL keeps only its host`() {
        // Forgiving where the mistake is obvious and harmless: what a person has on the clipboard from
        // the host's own page is a clone URL, and the host in it is the fact this window wants.
        val draft = ConnectionDraft(
            kind = ConnKind.GIT,
            address = "https://gitlab.com",
            secret = "glpat",
        )

        assertNull("it is allowed", draft.problemSaid())
        assertEquals("gitlab.com", draft.connection()?.address)
    }

    @Test
    fun `a git window with no token refuses, because a push with no token is a push as nobody`() {
        val said = ConnectionDraft(kind = ConnKind.GIT, address = "gitlab.com").problemSaid()

        assertTrue("it refuses: $said", said != null)
        assertTrue("and says what is missing: $said", "token" in said.orEmpty())
        assertFalse("not the llm sentence", "model" in said.orEmpty())
    }

    // ---- what an opened window shows ----------------------------------------------------

    @Test
    fun `a window opens on the values in force, and never on the key`() {
        // The pair's fields show what the scope would use, so a person changing only the model is not
        // asked to reconstruct an address. **The secret is the exception on purpose**: a window that
        // showed it would be a screenshot away from a leak, so an empty key box means "keep the one
        // that is here".
        val draft = ConnectionDraft.opening(
            kind = ConnKind.LLM,
            at = "https://models.example.org/v1",
            model = "llama3.3:70b",
            effort = "high",
            shape = Shape.CHAT,
        )

        assertEquals("https://models.example.org/v1", draft.address)
        assertEquals("llama3.3:70b", draft.model)
        assertEquals("high", draft.effort)
        assertEquals("", draft.secret)
        assertEquals("and the shape it would send", Shape.CHAT.url, draft.shapeUrl)
    }

    // ---- the grant ------------------------------------------------------------------------

    @Test
    fun `the grant is off unless it was ticked`() {
        // **A permission's only safe default.** A row existing is not a grant: a git token can push
        // and a model key can spend money, so neither becomes nameable because somebody typed an
        // address.
        val unticked = ConnectionDraft(
            kind = ConnKind.LLM,
            address = "https://models.example.org/v1",
            model = "m",
        )
        val ticked = unticked.copy(nameable = true)

        assertEquals("off", false, unticked.connection()?.nameable)
        assertEquals("and on when it is ticked", true, ticked.connection()?.nameable)
    }

    @Test
    fun `a window opens showing whether the grant is already on`() {
        // A window that always opened with it off would look like the grant had been withdrawn, and a
        // person would tick it again — or not notice that it was.
        assertTrue(
            ConnectionDraft.opening(kind = ConnKind.GIT, at = "gitlab.com", nameable = true).nameable,
        )
        assertFalse(
            ConnectionDraft.opening(kind = ConnKind.GIT, at = "gitlab.com").nameable,
        )
    }
}
