package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **What the git connection window may store**, asked as rules rather than as a screen.
 *
 * The same reason [GitCredentialsTest] exists: the sentences are the feature. A window that silently
 * saved half a pair would send a token to a host it was not stored for, and a window that refused a
 * complete one would make a self-hosted host impossible to reach — so both directions are asserted
 * here, without a keystore, a remote or a Compose tree.
 *
 * **The llm half of this file went with `ConnKind.LLM`** (2026-11-10). It tested a window that stored
 * a model, an effort, three compaction numbers and a request shape per scope — and every one of those
 * belongs to a request form (`Schema`) now, where the rules are `Schema`'s own and tested by
 * [SchemasTest]. What is left is the credential git needs.
 */
class ConnectionDraftTest {

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
    }

    @Test
    fun `a field left at the value it inherits is not written`() {
        // **The rule that keeps "change only what you need" true.** The window opens on the values in
        // force, so "unchanged" and "typed the same" are the same text — and storing that text would
        // copy the parent's value into this scope, where it stops following the parent.
        val inForce = ConnectionDraft(kind = ConnKind.GIT, address = "gitlab.com", user = "muffy")
        val typed = ConnectionDraft(
            kind = ConnKind.GIT,
            address = "gitlab.com",
            secret = "a-new-token",
            user = "muffy",
        )

        val changed = typed.changesAgainst(inForce)
        assertEquals("the address is inherited, so it is not written", "", changed.address)
        assertEquals("and so is the name", "", changed.user)
        assertEquals("the token is the change", "a-new-token", changed.secret)
    }

    @Test
    fun `a window whose fields were all left alone writes nothing at all`() {
        val inForce = ConnectionDraft(kind = ConnKind.GIT, address = "gitlab.com", user = "muffy")
        val changed = inForce.changesAgainst(inForce)

        assertNull("no pair to store", changed.connection())
        assertTrue("and nothing was typed", changed.secret.isEmpty())
    }

    @Test
    fun `a window opens on the values in force, and never on the key`() {
        // The pair's fields show what the scope would use, so a person changing only the token is not
        // asked to reconstruct an address. **The secret is the exception on purpose**: a window that
        // showed it would be a screenshot away from a leak, so an empty key box means "keep the one
        // that is here".
        val draft = ConnectionDraft.opening(
            kind = ConnKind.GIT,
            at = "gitlab.com",
            user = "muffy",
        )

        assertEquals("gitlab.com", draft.address)
        assertEquals("muffy", draft.user)
        assertEquals("", draft.secret)
    }

    // ---- the grant ------------------------------------------------------------------------

    @Test
    fun `the grant is off unless it was ticked`() {
        // **A permission's only safe default.** A row existing is not a grant: a git token can push,
        // so it does not become nameable because somebody typed an address.
        val unticked = ConnectionDraft(
            kind = ConnKind.GIT,
            address = "gitlab.com",
            secret = "glpat",
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
