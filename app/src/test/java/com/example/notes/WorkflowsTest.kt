package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * What a workflow file can reach, and whether a person has sealed it.
 *
 * **The tests are mostly about the direction the scan errs in.** The environment name can be an
 * expression — GitHub's own example is `name: ${{ github.ref_name }}` — so no scan can resolve every
 * declaration. A false "it names an environment" costs a person one glance; a false "it names none"
 * lets an unreviewed workflow read a secret. Every ambiguous case here is asserted in the safe
 * direction.
 */
class WorkflowsTest {

    private lateinit var root: File
    private lateinit var project: File
    private lateinit var workflows: Workflows

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "workflows-" + System.nanoTime())
        root.mkdirs()
        project = File(root, "project")
        File(project, Workflows.DIR).mkdirs()
        workflows = Workflows()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(name: String, text: String): File {
        val file = File(File(project, Workflows.DIR), name)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    /** A GitHub expression, built by concatenation so this file's own braces stay balanced. */
    private val open = "$" + "{"
    private val close = "}"

    // ---- what a file can reach --------------------------------------------

    @Test
    fun `a workflow that names an environment is reported with it`() {
        // **The one fact that decides whether a run can read a secret.** GitHub: "Secrets stored in an
        // environment are only available to workflow jobs that reference the environment."
        val file = write(
            "deploy.yml",
            """
            jobs:
              ship:
                runs-on: ubuntu-24.04
                environment: production
                steps:
                  - run: echo hello
            """.trimIndent(),
        )

        val reach = workflows.reach(file)
        assertEquals(listOf("production"), reach.environments)
        assertTrue("it declares one", reach.declaresEnvironment)
    }

    @Test
    fun `an environment written on the next line is found too`() {
        val file = write(
            "deploy.yml",
            """
            jobs:
              ship:
                environment:
                  name: staging
                steps:
                  - run: echo hi
            """.trimIndent(),
        )
        assertEquals(listOf("staging"), workflows.reach(file).environments)
    }

    @Test
    fun `an environment whose name is an expression declares one without naming it`() {
        // **The case that decides the scan's shape.** GitHub documents `name: ${{ github.ref_name }}`
        // as legal, so the name is unknowable at scan time. The declaration must still be seen —
        // answering "names no environment" here is the failure that would let an unreviewed workflow
        // read a secret.
        val file = write(
            "dynamic.yml",
            """
            jobs:
              ship:
                environment:
                  name: ${open}{{ github.ref_name }}$close
            """.trimIndent(),
        )

        val reach = workflows.reach(file)
        assertTrue("the declaration is seen", reach.declaresEnvironment)
        assertEquals("but there is no name to report", emptyList<String>(), reach.environments)
    }

    @Test
    fun `a commented-out environment is not a declaration`() {
        // A note in the file must not make a row claim protection the run never touches.
        val file = write(
            "notes.yml",
            """
            # environment: production
            jobs:
              a:
                runs-on: ubuntu-24.04
            """.trimIndent(),
        )
        assertFalse(workflows.reach(file).declaresEnvironment)
    }

    @Test
    fun `an environment with a space before the colon still counts`() {
        // `environment :` is legal YAML, and a scan that missed it would be the wrong kind of wrong.
        val file = write("odd.yml", "jobs:\n  a:\n    environment : production\n")
        assertTrue(workflows.reach(file).declaresEnvironment)
    }

    @Test
    fun `the several ways a workflow reaches a token all count`() {
        val dollar = "$"
        for (text in listOf(
            "run: echo " + dollar + "{{ secrets.ANY }}",
            "with:\n  token: " + dollar + "{{ github.token }}",
            "secrets: inherit",
            "run: echo " + dollar + "{{ secrets[format('{0}', 'X')] }}",
        )) {
            val file = write("one.yml", "jobs:\n  a:\n    steps:\n      - $text\n")
            assertTrue("\"$text\" must be seen as reaching a secret", workflows.reach(file).secrets)
        }
    }

    @Test
    fun `a workflow with no environment and no secret reaches nothing`() {
        val file = write("plain.yml", "jobs:\n  a:\n    runs-on: ubuntu-24.04\n")
        val reach = workflows.reach(file)
        assertFalse("no environment", reach.declaresEnvironment)
        assertFalse("no secret", reach.secrets)
        assertFalse("so nothing is in reach", reach.canReachSecrets)
    }

    @Test
    fun `a file that will not open is reported as reaching everything`() {
        // **Unknown is not safe.** Reporting "reaches nothing" for a file whose contents could not be
        // read would be the report lying in the direction that matters.
        val missing = File(File(project, Workflows.DIR), "gone.yml")
        val reach = workflows.reach(missing)
        assertTrue(reach.declaresEnvironment)
        assertTrue(reach.secrets)
    }

    // ---- the lock is the approval -----------------------------------------

    @Test
    fun `a locked workflow is sealed, and an unlocked one is exposed`() {
        // **The operator's design: locked file = approved and sealed file.** A locked file cannot be
        // written by the agent, so its bytes are the bytes a person left — which is what approval
        // means, and it needs no second store.
        write("deploy.yml", "jobs:\n  a:\n    environment: release\n")
        write("build.yml", "jobs:\n  a:\n    runs-on: ubuntu-24.04\n")

        val rows = workflows.survey(project, setOf("project/.github/workflows/deploy.yml"))

        val deploy = rows.first { it.name == "deploy.yml" }
        assertTrue("the locked one is sealed", deploy.locked)
        assertFalse("so it is not exposed", deploy.exposed)

        val build = rows.first { it.name == "build.yml" }
        assertFalse("the other is unlocked", build.locked)
        assertFalse("but it reaches nothing, so it is not exposed either", build.exposed)
    }

    @Test
    fun `an unlocked workflow that names an environment is the exposed state`() {
        // The one state worth drawing in red: a file the agent can still rewrite, which can read a
        // secret. This is the state the dispatch check refuses.
        write("deploy.yml", "jobs:\n  a:\n    environment: release\n")
        val row = workflows.survey(project, emptySet()).single()
        assertFalse(row.locked)
        assertTrue("it can reach secrets and nobody sealed it", row.exposed)
    }

    @Test
    fun `a lock covers what is inside the folder`() {
        // The inheritance rule is Workspace's, and a workflow in a subfolder of a locked folder is
        // as sealed as one at the top.
        write("nested/deep.yml", "jobs:\n  a:\n    environment: release\n")
        val rows = workflows.survey(project, setOf("project/.github/workflows/nested"))
        val deep = rows.first { it.name == "deep.yml" }
        assertTrue("the folder's lock covers it", deep.locked)
        assertFalse(deep.exposed)
    }

    @Test
    fun `the survey lists every workflow once, sealed or not`() {
        write("build.yml", "on: push\n")
        write("deploy.yml", "jobs:\n  a:\n    environment: production\n")

        val rows = workflows.survey(project, setOf("project/.github/workflows/build.yml"))

        assertEquals("both are listed", 2, rows.size)
        assertEquals(listOf("build.yml", "deploy.yml"), rows.map { it.name })
    }

    @Test
    fun `a folder with no workflows surveys to nothing rather than failing`() {
        assertEquals(emptyList<Workflows.Row>(), workflows.survey(File(root, "nowhere"), emptySet()))
    }

    @Test
    fun `a lock path is account-relative, and the check knows it`() {
        // **The trap this test exists for.** `Workspace` stores lock paths relative to the ACCOUNT
        // folder, so a workflow in `project/.github/workflows/x.yml` is locked as
        // `project/.github/workflows/x.yml` — and everything this class reports is relative to the
        // PROJECT. Comparing the two directly would never match, so the dispatch check would never
        // fire, and a security check that silently never fires is worse than none.
        val projectRelative = ".github/workflows/deploy.yml"

        assertTrue(
            "the account-relative path matches",
            workflows.isLocked(projectRelative, setOf("project/.github/workflows/deploy.yml"), "project"),
        )
        assertFalse(
            "and a project-relative lock path does not, which is why the project is prepended",
            workflows.isLocked(projectRelative, setOf(projectRelative), "project"),
        )
        assertTrue(
            "a locked folder covers what is inside it",
            workflows.isLocked(projectRelative, setOf("project/.github"), "project"),
        )
    }

    @Test
    fun `only yaml files count as workflows`() {
        assertTrue(Workflows.isWorkflow(".github/workflows/build.yml"))
        assertTrue(Workflows.isWorkflow(".github/workflows/build.yaml"))
        assertFalse("a text file is not a workflow", Workflows.isWorkflow("README.md"))
    }
}
