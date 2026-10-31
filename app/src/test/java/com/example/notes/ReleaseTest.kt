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
 * The release lane: what it dispatches, and the one thing it must never do.
 *
 * **The rule under test is that this lane is unreachable from the agent's.** `Release.BRANCH` and
 * `AgentTools.AGENT_BRANCH` are two constants on purpose, and a test that asserted only "a release goes
 * to main" would pass just as well if they were one.
 */
class ReleaseTest {

    private lateinit var root: File
    private lateinit var github: DiskGitHub
    private val ref = RepoRef("dev", "muffycodes")

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "release-" + System.nanoTime())
        root.mkdirs()
        github = DiskGitHub(File(root, "github"), "dev")
        github.repoDir(ref.name).mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a release goes to main, with the version the person typed`() {
        val report = Release.start(github, "a-key", ref, "1.5").getOrThrow()

        assertEquals("main", report.branch)
        assertEquals("1.5", report.versionName)
        val sent = github.dispatches(ref)
        assertEquals("one dispatch", 1, sent.size)
        assertEquals("on main", "main", sent[0].branch)
        assertEquals("of the workflow a person can read", Release.WORKFLOW, sent[0].workflow)
        assertEquals("with the version", "1.5", sent[0].inputs["version_name"])
    }

    @Test
    fun `the release lane and the agent's lane are two different branches`() {
        // **The separation is the design, not an inconvenience.** A single shared `dispatch(branch)`
        // helper would be one refactor away from the agent reaching main, and the rule would then be a
        // convention in a call site rather than a constant.
        assertEquals("main", Release.BRANCH)
        assertEquals("development", AgentTools.AGENT_BRANCH)
        assertFalse(
            "and they are not the same branch",
            Release.BRANCH == AgentTools.AGENT_BRANCH,
        )
    }

    @Test
    fun `a version name that would be unsafe in a tag is refused before a pipeline is spent`() {
        // The workflow checks this too, and that check is the one that protects a dispatch from
        // github.com. This one exists so the answer arrives before ten minutes of CI rather than
        // during it.
        val report = Release.start(github, "a-key", ref, "1.5; rm -rf /")
        assertTrue("refused", report.isFailure)
        assertTrue(
            "and says why: ${report.exceptionOrNull()?.message}",
            report.exceptionOrNull()?.message.orEmpty().contains("letters, digits"),
        )
        assertEquals("and nothing was dispatched", 0, github.dispatches(ref).size)
    }

    @Test
    fun `an empty version name is refused`() {
        assertTrue(Release.start(github, "a-key", ref, "  ").isFailure)
        assertEquals(0, github.dispatches(ref).size)
    }

    @Test
    fun `a dev build is asked for by a string, because a dispatch input is one`() {
        // A boolean `false` in the inputs map would arrive at GitHub as the JSON literal `false`
        // rather than the string its `type: boolean` input expects.
        Release.start(github, "a-key", ref, "1.5", devBuild = true).getOrThrow()

        assertEquals("true", github.dispatches(ref)[0].inputs["dev_build"])
    }

    @Test
    fun `the plain release does not ask for a dev build`() {
        Release.start(github, "a-key", ref, "1.5").getOrThrow()
        assertNull(
            "the key is absent rather than false",
            github.dispatches(ref)[0].inputs["dev_build"],
        )
    }

    // ---- the artifact's name ---------------------------------------------

    @Test
    fun `an artifact keeps the name the workflow gave it`() {
        // **`cleanName` turned `app-release` into `app_release`**, and a model that fetched one then
        // read the other was looking for a file that does not exist. An artifact's name is the
        // workflow's label, not a name a person typed.
        assertEquals("app-release", Release.artifactName("app-release"))
        assertEquals("scenarios", Release.artifactName("scenarios"))
        assertEquals("Screenshot.2026.10.31", Release.artifactName("Screenshot.2026.10.31"))
    }

    @Test
    fun `an artifact name that could leave the folder is made safe, not refused`() {
        // A name from a workflow is trusted to be a name, but a `../` in one must not become a path.
        // **What matters is that no separator survives**, not the exact shape of what replaces it —
        // asserting the shape would pin an implementation detail to a security property.
        val escaped = Release.artifactName("../../etc/passwd")
        assertFalse("no slash survives: $escaped", escaped.contains('/'))
        assertFalse("and it does not start with a dot: $escaped", escaped.startsWith("."))
        assertTrue("and it is still readable: $escaped", escaped.contains("passwd"))

        val nested = Release.artifactName("a/b")
        assertFalse("a nested name is flattened: $nested", nested.contains('/'))
    }

    @Test
    fun `a name that is nothing but dots or separators becomes empty, for the caller to fall back on`() {
        assertEquals("", Release.artifactName(".."))
        assertEquals("", Release.artifactName("---"))
        assertEquals("", Release.artifactName("   "))
    }
}
