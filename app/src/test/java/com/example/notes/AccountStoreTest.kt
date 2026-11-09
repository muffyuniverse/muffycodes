package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The accounts on this phone (§5): each one is a folder with its own details, so
 * an account is self-contained and removing it leaves nothing behind.
 */
class AccountStoreTest {

    private lateinit var files: File
    private lateinit var store: AccountStore

    @Before
    fun makeSpace() {
        files = File(System.getProperty("java.io.tmpdir"), "accounts-${System.nanoTime()}")
        files.mkdirs()
        store = AccountStore(files)
    }

    @After
    fun cleanUp() {
        files.deleteRecursively()
    }

    @Test
    fun `the provisional account exists, and is not a GitHub login`() {
        // **`~` is the whole guarantee.** A GitHub login cannot contain one, so this name can never
        // be shadowed by somebody who registers `local` - and a folder called `~local` in a path or
        // a log is unmistakably not a person's account.
        val provisional = store.ensureProvisional()
        assertTrue("it is a real account folder", store.detailsOf(provisional.login).isFile)
        assertTrue("and the store knows it", store.isProvisional(provisional.login))
        assertTrue("the name cannot be a GitHub login", provisional.login.contains('~'))
        assertFalse("a real login is not provisional", store.isProvisional("muffyuniverse"))
        assertFalse("and neither is nothing", store.isProvisional(null))
    }

    @Test
    fun `making the provisional account twice leaves its contents alone`() {
        // **This folder is the one account whose contents are never disposable.** A person's local
        // projects live here before they sign in, so a second call must be a no-op rather than a
        // rewrite - and `ensureProvisional` runs on every app start.
        val first = store.ensureProvisional()
        val kept = File(store.dirOf(first.login), "my-project")
        kept.mkdirs()
        File(kept, "notes.txt").writeText("work in progress")

        store.ensureProvisional()

        assertTrue("the project is still there", kept.isDirectory)
        assertEquals(
            "and its file is untouched",
            "work in progress",
            File(kept, "notes.txt").readText(),
        )
    }

    @Test
    fun `signing in leaves the local account and its projects exactly where they are`() {
        // **The operator's 2026-11-09 ruling, and it reverses what this test used to assert.**
        // `~local` cannot be removed, so a sign-in cannot rename it away either: a person who made
        // projects before signing in still finds them in Local, and moves the ones they want with
        // the transfer verb. The account that arrives is empty, which is what it is - a new identity
        // with a new key, and none of Local's history.
        val provisional = store.ensureProvisional()
        val project = File(store.dirOf(provisional.login), "first-project")
        project.mkdirs()
        File(project, "milk.txt").writeText("2 litres")

        store.save(Account(login = "muffyuniverse", commitName = "Muffy", commitEmail = "m@example.com"))

        assertTrue(
            "the project is still Local's",
            File(store.dirOf(provisional.login), "first-project/milk.txt").isFile,
        )
        assertFalse(
            "and nothing was copied into the account",
            File(store.dirOf("muffyuniverse"), "first-project").exists(),
        )
    }

    @Test
    fun `the local account cannot be removed, however it is asked`() {
        // **Two verbs, one answer**, because "delete the copies" and "keep them on the phone" are the
        // same act for an account that is not an identity: neither can leave the phone without a
        // workspace. The files stay because nothing ran.
        val provisional = store.ensureProvisional()
        val project = File(store.dirOf(provisional.login), "first-project")
        project.mkdirs()
        File(project, "milk.txt").writeText("2 litres")

        assertFalse("removing it is refused", store.remove(provisional.login))
        assertFalse("and forgetting it too", store.forget(provisional.login))
        assertTrue("so the project is still there", File(project, "milk.txt").isFile)
        assertTrue("and it is still an account", store.exists(provisional.login))
        assertTrue("with its own details", store.detailsOf(provisional.login).isFile)
    }

    @Test
    fun `an account lives in its own folder with its details`() {
        store.save(Account("muffyuniverse", "Muffy World", "muffyworld365@gmail.com"))

        assertTrue(
            "the details are inside the app's own hidden folder",
            File(store.dirOf("muffyuniverse"), ".muffy/account.json").isFile,
        )
        val read = store.account("muffyuniverse")
        assertEquals("Muffy World", read?.commitName)
        assertEquals("muffyworld365@gmail.com", read?.commitEmail)
    }

    @Test
    fun `several accounts are listed and none is hidden`() {
        store.save(Account("beta", "B", "b@example.com"))
        store.save(Account("Alpha", "A", "a@example.com"))
        assertEquals(listOf("Alpha", "beta"), store.accounts().map { it.login })
    }

    @Test
    fun `removing an account takes its folder away`() {
        store.save(Account("muffyuniverse", "M", "m@example.com"))
        assertTrue(store.exists("muffyuniverse"))

        store.remove("muffyuniverse")

        assertFalse(store.exists("muffyuniverse"))
        assertNull(store.account("muffyuniverse"))
    }

    @Test
    fun `an account with no details is not an account`() {
        // A folder left behind by a half-finished sign-in is not shown as one.
        store.dirOf("ghost").mkdirs()
        assertTrue(store.accounts().isEmpty())
        assertNull(store.account("ghost"))
    }

    @Test
    fun `a written null is not the word null`() {
        // `org.json`'s `optString` stringifies a JSON null into the four-letter word
        // "null", so an account whose email was stored as null showed that word where a
        // person's address belongs — and committed as it. Absent and null both mean
        // "nothing stored".
        store.save(Account("muffyuniverse", "Muffy", "muffy@example.com"))
        val details = File(store.stateDir("muffyuniverse"), "account.json")
        assertTrue("the details file is there", details.isFile)
        details.writeText("""{"login":"muffyuniverse","commitName":null,"commitEmail":null}""")

        val read = store.account("muffyuniverse")

        assertEquals("no name stored", "", read!!.commitName)
        assertEquals("no email stored, rather than the word", "", read.commitEmail)
        assertEquals(
            "so the commit falls back to the login",
            "muffyuniverse@users.noreply.github.com",
            read.author().email,
        )
    }

    @Test
    fun `an account keeps the host it was made against`() {
        // **The field that makes an account more than a GitHub login.** A key may only be sent to the
        // host it was made for, so the host is part of the account's identity rather than a detail of
        // whichever project happens to be open.
        store.save(Account("someone", "S", "s@example.com", host = "gitlab.com", gitUser = "oauth2"))

        val read = store.account("someone")

        assertEquals("gitlab.com", read?.host)
        assertEquals("and the username, when the host needs one", "oauth2", read?.gitUser)
    }

    @Test
    fun `an account with no host is a GitHub account`() {
        // **The default, and it is a statement of fact rather than a guess.** GitHub is the one host
        // this phone signs in to without being given an address, so a file with no `host` key
        // describes that account. Reading it as "unknown" would refuse the key that signed it in.
        store.save(Account("muffyuniverse", "M", "m@example.com"))
        val details = File(store.stateDir("muffyuniverse"), "account.json")
        details.writeText("""{"login":"muffyuniverse","commitName":"M","commitEmail":"m@example.com"}""")

        val read = store.account("muffyuniverse")

        assertEquals(GITHUB_HOST, read?.host)
        assertEquals("and no git username was ever asked for", "", read?.gitUser)
    }

    @Test
    fun `the commit identity falls back to the login`() {
        val account = Account("muffyuniverse", "", "")
        assertEquals("muffyuniverse", account.author().name)
        assertEquals("muffyuniverse@users.noreply.github.com", account.author().email)
    }

    @Test
    fun `removing says whether the account is gone, and forgetting says it by the identity`() {
        store.save(Account("go_away", "G", "g@example.com"))
        assertTrue("a full removal reports the folder gone", store.remove("go_away"))
        assertFalse("and the folder really is gone", store.dirOf("go_away").exists())

        // **The kept-copies case is a success with the folder still there.** Keeping the files means
        // leaving a folder full of projects behind, so the answer cannot be "the folder went" - it is
        // "this phone no longer has this account", which is what `accounts()` reads, and the thing a
        // caller has to know before offering a retry.
        store.save(Account("keep_me", "K", "k@example.com"))
        File(store.dirOf("keep_me"), "a_note.md").writeText("mine")
        assertTrue("forgetting reports the account gone", store.forget("keep_me"))
        assertTrue("while the files stay", store.dirOf("keep_me").isDirectory)
        assertNull("and the account with them", store.account("keep_me"))
    }

    @Test
    fun `a removal that did not finish says the account is still here`() {
        // **A sentence, because the failure must say what happened.** The person pressed a button
        // that promised the account would go; the one thing that must not happen is a screen that
        // has forgotten it while the disk has not.
        val said = removalProblemSaid("go_away")
        assertTrue("names the account: $said", said.contains("\"go_away\""))
        assertTrue("says it is still here: $said", said.contains("still on this phone"))
        assertTrue("and what to do: $said", said.contains("Remove it again"))
    }

    @Test
    fun `the commit identity is used when it is set`() {
        val account = Account("someone", "Muffy World", "muffyworld365@gmail.com")
        assertEquals("Muffy World", account.author().name)
        assertEquals("muffyworld365@gmail.com", account.author().email)
    }
}
