package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A version name is a tag name**, and this is the rule that decides it.
 *
 * What used to be here — a dispatch to a workflow, a dev-build input, an artifact's file name — was
 * GitHub Actions wearing a feature's clothes. The git protocol has a release of its own and it is
 * `git tag`, so the app makes one (`ProjectGit.tag`) and this file keeps only the part that has to be
 * right before anything is sent: the name.
 */
class ReleaseTest {

    @Test
    fun `a version is tagged on the branch the app calls main`() {
        // **One branch, and both actors are on it.** The two-lane split is ended by the operator's
        // decision (plan §13b.1), and a tag is made at the top of the branch that is published.
        assertEquals("main", Release.BRANCH)
        assertEquals("which is the branch the agent pushes", AgentTools.BRANCH, Release.BRANCH)
    }

    @Test
    fun `a version name that would be unsafe in a tag is refused in words`() {
        // The name becomes a git ref, so this is a rule about refs: letters, digits, dots, dashes and
        // underscores. `v1.5; rm -rf /` is the shape that a shell would have hurt and that git would
        // have refused with a message about refs, which names nothing a person recognises.
        val said = Release.nameProblem("1.5; rm -rf /")
        assertNotNull("refused", said)
        assertTrue("and says why: $said", said.orEmpty().contains("letters, digits"))
    }

    @Test
    fun `an empty version name is refused`() {
        assertNotNull(Release.nameProblem("  ".trim()))
    }

    @Test
    fun `an ordinary version name is allowed`() {
        // The three shapes people actually write. A rule that refused any of these would send them
        // looking for a workaround for a version they had already released.
        assertEquals(null, Release.nameProblem("1.4"))
        assertEquals(null, Release.nameProblem("v2.0.0"))
        assertEquals(null, Release.nameProblem("1.5-rc.1"))
    }
}
