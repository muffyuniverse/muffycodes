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
    fun `signing in adopts the provisional account by renaming its folder`() {
        // **A rename, not a copy**, and the difference is the point: after it there is exactly one
        // workspace holding the projects. A copy that failed halfway would leave two, with nothing
        // on screen to say which one was real.
        val provisional = store.ensureProvisional()
        val project = File(store.dirOf(provisional.login), "first-project")
        project.mkdirs()
        File(project, "milk.txt").writeText("2 litres")

        val adopted = store.adoptProvisional("muffyuniverse")

        assertTrue("it was adopted", adopted)
        assertTrue(
            "the project is now the account's",
            File(store.dirOf("muffyuniverse"), "first-project/milk.txt").isFile,
        )
        assertFalse("and the provisional folder is gone", store.dirOf(provisional.login).exists())
    }

    @Test
    fun `an account that already has a folder is not merged into`() {
        // **Refused, and the provisional content is left where it is.** Merging two workspaces is
        // not a rename: it needs a rule for two projects of the same name and two git repositories
        // that disagree about their history. Leaving it untouched is the only answer that cannot
        // lose work, and the caller is told so it can say something.
        val provisional = store.ensureProvisional()
        val theirs = File(store.dirOf(provisional.login), "theirs")
        theirs.mkdirs()
        File(theirs, "keep.txt").writeText("still mine")

        // The account already exists, with content of its own.
        val existing = File(store.dirOf("muffyuniverse"), "existing")
        existing.mkdirs()

        val adopted = store.adoptProvisional("muffyuniverse")

        assertFalse("nothing was adopted", adopted)
        assertTrue("the provisional project is untouched", File(theirs, "keep.txt").isFile)
        assertTrue("and the account's own project is too", existing.isDirectory)
    }

    @Test
    fun `an empty provisional account is removed rather than adopted`() {
        // **An empty `~local` should not become an empty project folder on the account.** Signing in
        // first thing is the common case, and a workspace with nothing in it has nothing to hand
        // over - but the folder still has to go, or the next start remakes it beside a real account.
        val provisional = store.ensureProvisional()
        // Only the app's own state folder, which every account has and which is not content.
        assertFalse("it starts with nothing to adopt", store.adoptProvisional("muffyuniverse"))
        assertFalse("and the empty folder is gone", store.dirOf(provisional.login).exists())
        assertFalse("with no account folder made", store.dirOf("muffyuniverse").exists())
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
    fun `the commit identity falls back to the login`() {
        val account = Account("muffyuniverse", "", "")
        assertEquals("muffyuniverse", account.author().name)
        assertEquals("muffyuniverse@users.noreply.github.com", account.author().email)
    }

    @Test
    fun `the commit identity is used when it is set`() {
        val account = Account("someone", "Muffy World", "muffyworld365@gmail.com")
        assertEquals("Muffy World", account.author().name)
        assertEquals("muffyworld365@gmail.com", account.author().email)
    }
}
