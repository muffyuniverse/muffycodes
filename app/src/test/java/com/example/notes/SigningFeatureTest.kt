package com.example.notes

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The signing key, from the app's side: when it is asked about, and who may send it.
 *
 * **The rule under test is that the app never sends one on its own.** A reconcile that pushed a
 * signing key to whatever repository the person had just synced would be the app widening its own
 * blast radius without being asked, and the key is the most consequential secret it can hold.
 */
@RunWith(RobolectricTestRunner::class)
class SigningFeatureTest {

    private lateinit var scene: Scenario

    @Before
    fun setUp() {
        scene = Scenario()
    }

    @After
    fun tearDown() {
        scene.root.deleteRecursively()
    }

    /** A key in the project's own folder, shaped like the one a person would have. */
    private fun putKey(project: String) {
        val dir = scene.viewModel.workspace!!.projectDir(project)
        File(dir, "release.jks").writeBytes(ByteArray(32) { it.toByte() })
        File(dir, ReleaseKey.FILE).writeText(
            """
            storeFile=release.jks
            storePassword=hunter2-not-really
            keyAlias=notes
            keyPassword=second-password
            """.trimIndent(),
        )
    }

    private fun secretNames(project: String): List<String> {
        val ref = RepoRef.of(scene.git.remoteUrl(project)) ?: error("no remote for $project")
        return scene.git.environmentSecretNames(scene.login, ref, ReleaseKey.ENVIRONMENT)
            .value.orEmpty()
    }

    @Test
    fun `a first push asks about the signing key, and sends nothing`() {
        // **The first push is the first moment a repository is reachable**, and so the first moment a
        // key could go to it. Adding a project is a local act with no repository behind it.
        scene.project("groceries")
        putKey("groceries")

        scene.sync("groceries")

        scene.await("the check to run") { scene.viewModel.signingSaid != null }
        assertTrue(
            "it says the project cannot sign yet: ${scene.viewModel.signingSaid}",
            scene.viewModel.signingSaid!!.contains("can't sign releases"),
        )
        assertEquals(
            "and NOTHING was sent, because asking is not sending",
            emptyList<String>(),
            secretNames("groceries"),
        )
    }

    @Test
    fun `a project with no key is never asked about, and never told`() {
        // This app has projects that are a folder of notes. Telling every one of them about a signing
        // key would be the nagging that makes the one real warning invisible.
        scene.project("groceries")

        scene.sync("groceries")

        scene.await("the sync to finish", 60_000) { !scene.viewModel.busy }
        assertNull("it says nothing", scene.viewModel.signingSaid)
        assertNull("and offers nothing", scene.viewModel.signingKeyFile)
    }

    @Test
    fun `pressing send puts the four in the environment, and the row goes away`() {
        // **The press is the whole permission.** There is no setting and no confirmation beyond the
        // row existing, and the row exists only because a key is on the phone that CI cannot see.
        scene.project("groceries")
        putKey("groceries")
        scene.sync("groceries")
        scene.await("the check to run") { scene.viewModel.signingSaid != null }
        assertNotNull("the row is offering the key", scene.viewModel.signingKeyFile)

        scene.viewModel.sendSigningKey("groceries")
        scene.await("the send to finish", 60_000) { !scene.viewModel.busy }
        scene.await("the row to go away") { scene.viewModel.signingSaid == null }

        assertEquals(
            "the four the build reads, and nothing else",
            ReleaseKey.Field.entries.map { it.secretName }.sorted(),
            secretNames("groceries"),
        )
        assertNull("and nothing is offered any more", scene.viewModel.signingKeyFile)
    }

    @Test
    fun `the environment is restricted to the branches that build before a secret goes in`() {
        // **A secret put into an unrestricted environment is unprotected for however long the rest of
        // the setup takes.** The policy is what makes the environment unreachable by the agent, which
        // only ever touches `development`.
        scene.project("groceries")
        putKey("groceries")
        scene.sync("groceries")
        scene.await("the check to run") { scene.viewModel.signingSaid != null }

        scene.viewModel.sendSigningKey("groceries")
        scene.await("the send to finish", 60_000) { !scene.viewModel.busy }

        val ref = RepoRef.of(scene.git.remoteUrl("groceries"))!!
        assertEquals(
            listOf("main", "development"),
            scene.git.branchesOf(ref, ReleaseKey.ENVIRONMENT),
        )
    }

    @Test
    fun `a project pulled from the person's own GitHub counts as theirs`() {
        // **The operator's point, as a test.** A repository already on their GitHub is obviously
        // theirs, so a project pulled from it is one whose signing key belongs there — the check must
        // find its key missing rather than assuming a fresh project.
        val made = scene.viewModel.createProject("groceries", "mit")
        scene.await("the project to exist") {
            scene.viewModel.projectNames.any { it.equals("groceries", ignoreCase = true) }
        }
        scene.await("the making to finish", 60_000) { !scene.viewModel.busy }
        putKey("groceries")
        scene.sync("groceries")

        // Now the project exists on "GitHub" and has a remote, which is what makes it reachable.
        val ref = RepoRef.of(scene.git.remoteUrl("groceries"))
        assertNotNull("the project has a remote, so it is a repository the person owns", ref)

        scene.viewModel.checkSigning("groceries")
        scene.await("the check to run") { scene.viewModel.signingSaid != null }
        assertFalse(
            "and its key is reported missing, not assumed present",
            secretNames("groceries").contains(ReleaseKey.Field.KEYSTORE.secretName),
        )
    }
}
