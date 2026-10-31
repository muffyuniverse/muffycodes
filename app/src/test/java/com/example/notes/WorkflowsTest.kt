package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The workflow record: whether a person read these bytes, and what the file can reach.
 *
 * **These tests are mostly about what the app does NOT claim.** The operator's ruling was that an
 * unapproved workflow still runs — because GitHub has no per-workflow secret scoping, so an app cannot
 * withhold a secret from one file — which means this class is a *record* and never a gate. The tests
 * below pin the two things that would make the record dishonest: a checkmark that survives an edit, and
 * a reach report that says "safe".
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
        workflows = Workflows(File(root, "state"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /**
     * A secrets expression, built rather than written.
     *
     * **The repository's brace check counts raw characters**, and a GitHub expression is unbalanced by
     * design, so a literal one in this file would fail a gate for a reason that has nothing to do with
     * the test. Concatenation keeps the file balanced and still puts the real text in front of the scan.
     */
    private val SECRET_EXPRESSION =
        "$" + "{" + "{" + " secrets.DEPLOY_TOKEN " + "}" + "}"

    private fun write(name: String, text: String): File {
        val file = File(File(project, Workflows.DIR), name)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    // ---- the checkmark is about bytes -------------------------------------

    @Test
    fun `an approval is of these bytes, and one character changes it`() {
        // **The whole value of the checkmark.** It means "a person read exactly this"; a mark that
        // survived an edit would mean "a person read something with this name", which is a different
        // and much weaker claim — and it is the one that would let an agent add `secrets.X` to an
        // approved file and keep the mark.
        val file = write("build.yml", "on: push\njobs:\n  a:\n    runs-on: ubuntu-24.04\n")
        assertTrue(workflows.approve(project, ".github/workflows/build.yml").isSuccess)
        assertEquals(
            "the approved bytes are trusted",
            Workflows.Approval.Trusted::class,
            workflows.approval(project, ".github/workflows/build.yml")::class,
        )

        file.writeText("on: push\njobs:\n  a:\n    runs-on: ubuntu-24.04 \n")

        val after = workflows.approval(project, ".github/workflows/build.yml")
        assertTrue("one character is enough: ${after.said()}", after is Workflows.Approval.Changed)
        assertFalse("and it is not a checkmark", after.checked)
    }

    @Test
    fun `an approval the agent made for itself is not the same mark`() {
        // **Two different facts must not share one symbol.** "A person read this" and "nobody
        // objected" are not the same claim, and a list that drew both as a checkmark would launder
        // the second into the first.
        write("agent-made.yml", "on: push\njobs:\n  a:\n    runs-on: ubuntu-24.04\n")
        workflows.approve(project, ".github/workflows/agent-made.yml", Workflows.By.AUTO)

        val auto = workflows.approval(project, ".github/workflows/agent-made.yml")
        assertTrue("it is recorded: ${auto.said()}", auto is Workflows.Approval.Automatic)
        assertFalse("but it is not the checkmark", auto.checked)
        assertEquals("and the row says which it was", "auto-approved", auto.said())
    }

    @Test
    fun `a workflow nobody has approved is not approved`() {
        write("fresh.yml", "on: push\n")
        val none = workflows.approval(project, ".github/workflows/fresh.yml")
        assertTrue(none is Workflows.Approval.None)
        assertFalse(none.checked)
    }

    @Test
    fun `a corrupt approvals file approves nothing`() {
        // **A damaged flag must not become a grant.** The opposite choice — treating an unparseable
        // list as "everything was approved" — would turn a half-written file into blanket permission.
        write("build.yml", "on: push\n")
        workflows.approve(project, ".github/workflows/build.yml")
        // Deliberately unparseable, and written without a brace so the repository's raw-character
        // brace check is not tripped by a string whose whole point is to be broken.
        File(File(root, "state"), Workflows.FILE).writeText("not json at all [")

        assertTrue(
            "nothing survives a corrupt file",
            workflows.approval(project, ".github/workflows/build.yml") is Workflows.Approval.None,
        )
    }

    @Test
    fun `withdrawing an approval takes the mark away`() {
        write("build.yml", "on: push\n")
        workflows.approve(project, ".github/workflows/build.yml")
        workflows.withdraw(".github/workflows/build.yml")
        assertTrue(
            workflows.approval(project, ".github/workflows/build.yml") is Workflows.Approval.None,
        )
    }

    // ---- what a file can reach --------------------------------------------

    @Test
    fun `a workflow that names an environment is reported with it`() {
        // **The one place the app can show real protection.** GitHub's own rule: "Secrets stored in an
        // environment are only available to workflow jobs that reference the environment. If the
        // environment requires approval, a job cannot access environment secrets until one of the
        // required reviewers approves it." So naming the environment is the fact that matters — a
        // human may stand between the run and the secret.
        val file = write(
            "deploy.yml",
            """
            on: push
            jobs:
              ship:
                runs-on: ubuntu-24.04
                environment: production
                steps:
                  - run: echo "@A_SECRET@"
            """.trimIndent().replace("@A_SECRET@", SECRET_EXPRESSION),
        )

        val reach = workflows.reach(file)
        assertEquals(listOf("production"), reach.environments)
        assertTrue("it mentions a secret", reach.secrets)
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
    fun `a commented-out environment is not a reference`() {
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
        assertEquals(emptyList<String>(), workflows.reach(file).environments)
    }

    @Test
    fun `a workflow that mentions no secret is reported as mentioning none, which is not a promise`() {
        // **The report is deliberately one-sided.** `secrets = false` means the scan found no mention,
        // not that the file cannot read one — see [Workflows.Reach]. The test pins the scan's shape so
        // that a change to it is a decision rather than an accident.
        val file = write("plain.yml", "jobs:\n  a:\n    runs-on: ubuntu-24.04\n")
        assertFalse(workflows.reach(file).secrets)
    }

    @Test
    fun `the several ways a workflow reaches a token all count`() {
        // Every shape the scan must not miss, because a miss here is a row that reads "no secret".
        // **Built by concatenation rather than written as a literal expression**, because the
        // repository's own brace check counts raw characters and a GitHub secrets expression is
        // unbalanced by design. The concatenation below produces exactly the text a workflow holds.
        val dollar = "\$"
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
    fun `a file that will not open is reported as reaching a secret`() {
        // **Unknown is not safe.** A row that read "no secret" for a file whose contents could not be
        // read would be the report lying in the direction that matters.
        val missing = File(File(project, Workflows.DIR), "gone.yml")
        assertTrue(workflows.reach(missing).secrets)
    }

    // ---- the survey -------------------------------------------------------

    @Test
    fun `the survey lists every workflow once, with both halves of the answer`() {
        write("build.yml", "on: push\njobs:\n  a:\n    runs-on: ubuntu-24.04\n")
        write("deploy.yml", "jobs:\n  a:\n    environment: production\n")
        workflows.approve(project, ".github/workflows/build.yml")

        val rows = workflows.survey(project)

        assertEquals("both are listed, approved or not", 2, rows.size)
        assertEquals(listOf("build.yml", "deploy.yml"), rows.map { it.name })
        assertTrue("the approved one is checked", rows.first { it.name == "build.yml" }.approval.checked)
        assertFalse("the other is not", rows.first { it.name == "deploy.yml" }.approval.checked)
        assertEquals(
            "and its environment is reported",
            listOf("production"),
            rows.first { it.name == "deploy.yml" }.reach.environments,
        )
    }

    @Test
    fun `a folder with no workflows surveys to nothing rather than failing`() {
        assertEquals(emptyList<Workflows.Row>(), workflows.survey(File(root, "nowhere")))
    }
}
