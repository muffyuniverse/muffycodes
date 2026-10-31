package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Moving a signing key from where it is exposed to where only a build can reach it.
 *
 * **What is being tested is the order, not the steps.** Each step is one API call; the reason this
 * class exists is that doing them in the wrong order breaks every build, and the failure appears later
 * as "the release is unsigned". So most of these tests are about what has happened *by the time*
 * something else has happened.
 */
class SigningTest {

    private lateinit var root: File
    private lateinit var project: File
    private lateinit var github: DiskGitHub
    private lateinit var properties: File
    private lateinit var workflow: File

    private val ref = RepoRef("dev", "muffycodes")

    /** A workflow shaped like this repository's: one job, a step that reads the keystore. */
    private val workflowText = """
        name: Build and release
        on:
          push:
            branches: [main, development]
        jobs:
          build-release:
            name: Build the release
            runs-on: ubuntu-24.04
            steps:
              - name: Build the release
                env:
                  MUFFY_KEYSTORE_BASE64: ${'$'}{{ secrets.MUFFY_KEYSTORE_BASE64 }}
                  MUFFY_KEYSTORE_PASSWORD: ${'$'}{{ secrets.MUFFY_KEYSTORE_PASSWORD }}
                run: ./gradlew assembleRelease
    """.trimIndent()

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "signing-" + System.nanoTime())
        root.mkdirs()
        project = File(root, "project")
        project.mkdirs()
        github = DiskGitHub(File(root, "github"), "dev")
        github.repoDir(ref.name).mkdirs()

        File(project, "release.jks").writeBytes(ByteArray(64) { it.toByte() })
        properties = File(project, ReleaseKey.FILE)
        properties.writeText(
            """
            storeFile=release.jks
            storePassword=hunter2-not-really
            keyAlias=notes
            keyPassword=second-password
            """.trimIndent(),
        )
        workflow = File(project, "build.yml")
        workflow.writeText(workflowText)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** Puts the four at repository scope, which is the state this exists to leave. */
    private fun exposed() {
        ReleaseKey.Field.entries.forEach {
            github.putRepoSecret(ref.name, it.secretName, ByteArray(4))
        }
    }

    private fun repoSecrets() = github.repoSecretNames("a-key", ref).value.orEmpty()
    private fun environmentSecrets() =
        github.environmentSecretNames("a-key", ref, ReleaseKey.ENVIRONMENT).value.orEmpty()

    @Test
    fun `the whole move happens in one press`() {
        exposed()

        val moved = Signing.move(github, "a-key", ref, project, properties, workflow).getOrThrow()

        assertEquals("all four arrived", 4, moved.written.size)
        assertEquals("all four left", 4, moved.removed.size)
        assertEquals("the environment has them", 4, environmentSecrets().size)
        assertEquals("and the repository has none", emptyList<String>(), repoSecrets())
        assertTrue("settled", moved.settled)
    }

    @Test
    fun `the workflow names the environment before any secret moves`() {
        // **This is the order that matters.** A job that names an environment still sees repository
        // secrets, so naming it first changes nothing — and doing it first is what makes the deletion
        // safe. The reverse has a window where the key is in the environment and the build is not
        // looking there.
        exposed()

        Signing.move(github, "a-key", ref, project, properties, workflow).getOrThrow()

        assertEquals(
            "the workflow names it",
            "release",
            Workflows.environmentNameOf(workflow),
        )
        assertTrue("and the text really says so", workflow.readText().contains("environment: release"))
    }

    @Test
    fun `a workflow that already names an environment is left alone`() {
        // A project that says `production` is set up, and moving its secrets to `release` would be
        // moving them where the build is not looking.
        workflow.writeText(workflowText.replace("runs-on: ubuntu-24.04", "runs-on: ubuntu-24.04\n    environment: production"))
        exposed()

        val moved = Signing.move(github, "a-key", ref, project, properties, workflow).getOrThrow()

        assertEquals("the environment it named is used", "production", moved.environment)
        assertNull("and nothing was edited", moved.edited)
        assertEquals("production has the four", 4, github
            .environmentSecretNames("a-key", ref, "production").value.orEmpty().size)
    }

    @Test
    fun `a workflow with no signing step is refused, and nothing moves`() {
        // An `environment:` on the wrong job is a key the build still cannot see, and the failure
        // would look exactly like the mistake it is.
        workflow.writeText("jobs:\n  test:\n    runs-on: ubuntu-24.04\n    steps:\n      - run: ./gradlew test\n")
        exposed()

        val result = Signing.move(github, "a-key", ref, project, properties, workflow)

        assertTrue("refused", result.isFailure)
        assertTrue(
            "and it says why: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("no step that reads a signing key"),
        )
        assertEquals("nothing arrived", emptyList<String>(), environmentSecrets())
        assertEquals("and nothing was removed", 4, repoSecrets().size)
    }

    @Test
    fun `no key on the phone means nothing is touched`() {
        properties.delete()
        exposed()

        val result = Signing.move(github, "a-key", ref, project, properties, workflow)

        assertTrue("refused", result.isFailure)
        assertEquals("nothing arrived", emptyList<String>(), environmentSecrets())
        assertEquals("and nothing was removed", 4, repoSecrets().size)
        assertFalse("the workflow was not edited either", workflow.readText().contains("environment:"))
    }

    @Test
    fun `the environment is restricted to the branches that build`() {
        exposed()
        Signing.move(github, "a-key", ref, project, properties, workflow).getOrThrow()
        assertEquals(
            listOf("main", "development"),
            github.branchesOf(ref, ReleaseKey.ENVIRONMENT),
        )
    }

    @Test
    fun `running it twice is not a failure and does not lose the key`() {
        // A person pressing the button again, or a retry after a dropped connection, must land on "it
        // is already done" rather than on an error about a secret that is not there.
        exposed()
        Signing.move(github, "a-key", ref, project, properties, workflow).getOrThrow()

        val again = Signing.move(github, "a-key", ref, project, properties, workflow)

        assertTrue("it succeeds: ${again.exceptionOrNull()?.message}", again.isSuccess)
        assertEquals("the environment still has the four", 4, environmentSecrets().size)
        assertNotNull("and the second run reports what it did", again.getOrThrow().written)
    }
}
