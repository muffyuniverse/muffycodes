package com.example.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Which of the app's own sentences are worth covering the screen with.**
 *
 * The operator's rule: "notifications should explain why something that user expected to happen
 * didn't happen". Everything else — "Copied …", "Imported", "Saved" — restates an act the person
 * just performed and watched finish, and a strip that appears after every act is one that gets
 * dismissed without being read. The cost of a useless strip is not the strip; it is the useful one
 * that gets dismissed with it.
 */
class SaidTest {

    @Test
    fun `a refusal is shown, because it says why the thing did not happen`() {
        // Every one of these is a sentence the app already writes, and each names a reason. They are
        // the whole justification for the strip existing.
        val refusals = listOf(
            "milk.txt is locked, so it was not deleted",
            "There is already a project called \"shopping\"",
            "Sign in to GitHub first",
            "Nothing is selected",
            "Could not change .gitignore here",
            "The app keeps secrets.txt off GitHub, and only the app can change that",
            "shopping stays on this phone: it has no GitHub repository to send to",
            "shopping is already only on this phone",
            "Restore target is locked, so nothing was put back",
        )

        for (said in refusals) {
            assertTrue("\"$said\" explains something, so it is shown", Said.worthShowing(said))
        }
    }

    @Test
    fun `a confirmation is not shown, because the person just watched it happen`() {
        // The operator's complaint in its own words: "they are the majority, I can't even remember
        // the last time I saw a useful notification". These are the majority.
        val confirmations = listOf(
            "Copied milk.txt into drafts/",
            "Imported",
            "Imported into shopping/",
            "Saved",
            "Left without saving",
            "The agent is on for shopping",
        )

        for (said in confirmations) {
            assertFalse("\"$said\" only restates an act, so it is not shown", Said.worthShowing(said))
        }
    }

    @Test
    fun `an empty message is nothing to say, not something to show`() {
        assertFalse(Said.worthShowing(null))
        assertFalse(Said.worthShowing(""))
        assertFalse(Said.worthShowing("   "))
    }

    @Test
    fun `the rule is generous, because hiding a refusal is worse than showing a confirmation`() {
        // **The asymmetry is deliberate.** A confirmation shown by mistake costs a dismiss; a refusal
        // hidden by mistake leaves a person watching nothing happen with no explanation. So the test
        // is "does this look like a reason", and an unusual sentence that does is shown rather than
        // filtered.
        assertTrue(
            "an unfamiliar refusal is still shown",
            Said.worthShowing("That name cannot be used here"),
        )
        assertTrue(
            "a sentence that explains a partial result is shown",
            "The repository was made, but shopping could not be pointed at it".let {
                Said.worthShowing(it)
            },
        )
    }
}
