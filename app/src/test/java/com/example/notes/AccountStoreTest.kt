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
    fun `a new store has no accounts, and the app's folder is not one`() {
        // **The operator's ruling of 2026-11-09**: *"scrapping local account so that there are no
        // prebuilt accounts and projects"*. A store that has never been signed in to holds nothing —
        // and the app's own folder is a sibling of `accounts/`, not inside it, so it can never be
        // mistaken for one however much it holds.
        assertTrue("no accounts at all", store.accounts().isEmpty())
        assertFalse("and nothing is an account by accident", store.exists("~local"))
        assertTrue(
            "the app's folder is beside the accounts, not inside them",
            !store.sharedDir.path.startsWith(store.accountsDir.path + File.separator),
        )
    }

    @Test
    fun `a sign-in makes an empty account and nothing is adopted`() {
        // **There is nothing to adopt.** A person who has just signed in gets exactly the folder the
        // identity makes — no projects, no rename, no copy. Work reaches another account only by
        // `Transfer`, when the person says so (§3.9).
        store.save(Account(login = "muffyuniverse", commitName = "Muffy", commitEmail = "m@example.com"))

        assertEquals("one account", 1, store.accounts().size)
        assertEquals(
            "with no projects of its own",
            emptyList<String>(),
            File(store.dirOf("muffyuniverse"), "projects").list()?.toList().orEmpty(),
        )
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
