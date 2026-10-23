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
    fun anAccountLivesInItsOwnFolderWithItsDetails() {
        store.save(Account("muffyuniverse", "Muffy World", "muffyworld365@gmail.com"))

        assertTrue(File(store.dirOf("muffyuniverse"), "account.json").isFile)
        val read = store.account("muffyuniverse")
        assertEquals("Muffy World", read?.commitName)
        assertEquals("muffyworld365@gmail.com", read?.commitEmail)
    }

    @Test
    fun severalAccountsAreListedAndNoneIsHidden() {
        store.save(Account("beta", "B", "b@example.com"))
        store.save(Account("Alpha", "A", "a@example.com"))
        assertEquals(listOf("Alpha", "beta"), store.accounts().map { it.login })
    }

    @Test
    fun removingAnAccountTakesItsFolderAway() {
        store.save(Account("muffyuniverse", "M", "m@example.com"))
        assertTrue(store.exists("muffyuniverse"))

        store.remove("muffyuniverse")

        assertFalse(store.exists("muffyuniverse"))
        assertNull(store.account("muffyuniverse"))
    }

    @Test
    fun anAccountWithNoDetailsIsNotAnAccount() {
        // A folder left behind by a half-finished sign-in is not shown as one.
        store.dirOf("ghost").mkdirs()
        assertTrue(store.accounts().isEmpty())
        assertNull(store.account("ghost"))
    }

    @Test
    fun theCommitIdentityFallsBackToTheLogin() {
        val account = Account("muffyuniverse", "", "")
        assertEquals("muffyuniverse", account.author().name)
        assertEquals("muffyuniverse@users.noreply.github.com", account.author().email)
    }

    @Test
    fun theCommitIdentityIsUsedWhenItIsSet() {
        val account = Account("someone", "Muffy World", "muffyworld365@gmail.com")
        assertEquals("Muffy World", account.author().name)
        assertEquals("muffyworld365@gmail.com", account.author().email)
    }
}
