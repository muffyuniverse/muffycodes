package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Setting up CI is three things agreeing, and every mismatch is silent.**
 *
 * The key must exist on this phone, in the repository's `ci` environment, and in a workflow that names
 * that environment. Get one wrong and the seal runs under an empty password: the build is green, the
 * artifacts download, and only the reading fails. So these tests are mostly about the ways it can be
 * half-done — a missing environment, a public key that cannot be fetched, a secret that cannot be
 * written — and about the report that names each of them before a person runs anything.
 */
class CiSetupTest {

    private val ref = RepoRef("dev", "notes")

    /**
     * A `GitHub` that refuses every call, so a fake can override only the two or three it needs.
     *
     * **Written out rather than delegated**, because Kotlin has no `by` for "every method refuses" and
     * a fake that silently returned empty lists would make "the environment is missing" and "the API
     * refused" the same answer — which is the distinction these tests are about.
     */
    private open class Refusing : GitHub {
        private fun no(name: String): Nothing =
            throw UnsupportedOperationException("$name is not part of this test")

        override fun identity(token: String): GitHubResult<Identity> = no("identity")
        override fun listRepos(token: String): GitHubResult<List<Repo>> = no("listRepos")
        override fun licences(token: String): GitHubResult<List<Licence>> = no("licences")
        override fun createRepo(
            token: String,
            name: String,
            licence: String,
            description: String,
            private: Boolean,
        ): GitHubResult<Repo> = no("createRepo")
        override fun renameRepo(
            token: String,
            owner: String,
            name: String,
            newName: String,
        ): GitHubResult<Repo> = no("renameRepo")
        override fun deleteRepo(token: String, owner: String, name: String): GitHubResult<Unit> =
            no("deleteRepo")
        override fun cloneUrlFor(owner: String, name: String): String = no("cloneUrlFor")
        override fun webUrl(cloneUrl: String): String = no("webUrl")
        override fun owns(remoteUrl: String): Boolean = no("owns")
        override fun runs(
            token: String,
            ref: RepoRef,
            workflow: String,
            branch: String,
            limit: Int,
        ): GitHubResult<List<CiRun>> = no("runs")
        override fun jobs(token: String, ref: RepoRef, run: Long): GitHubResult<List<CiJob>> = no("jobs")
        override fun logs(token: String, ref: RepoRef, run: Long): GitHubResult<Map<String, String>> =
            no("logs")
        override fun artifacts(token: String, ref: RepoRef, run: Long): GitHubResult<List<CiArtifact>> =
            no("artifacts")
        override fun artifactBytes(token: String, ref: RepoRef, id: Long): GitHubResult<ByteArray> =
            no("artifactBytes")
        override fun dispatch(
            token: String,
            ref: RepoRef,
            workflow: String,
            branch: String,
            inputs: Map<String, String>,
        ): GitHubResult<Unit> = no("dispatch")
        override fun environments(token: String, ref: RepoRef): GitHubResult<List<CiEnvironment>> =
            no("environments")
        override fun putEnvironment(
            token: String,
            ref: RepoRef,
            name: String,
            reviewers: List<String>,
        ): GitHubResult<CiEnvironment> = no("putEnvironment")
        override fun putEnvironmentSecret(
            token: String,
            ref: RepoRef,
            environment: String,
            name: String,
            sealed: ByteArray,
            keyId: String,
        ): GitHubResult<Unit> = no("putEnvironmentSecret")
        override fun environmentKey(
            token: String,
            ref: RepoRef,
            environment: String,
        ): GitHubResult<Pair<String, ByteArray>> = no("environmentKey")
        override fun repoSecretNames(token: String, ref: RepoRef): GitHubResult<List<String>> =
            no("repoSecretNames")
        override fun environmentSecretNames(
            token: String,
            ref: RepoRef,
            environment: String,
        ): GitHubResult<List<String>> = no("environmentSecretNames")
        override fun deleteEnvironmentSecret(
            token: String,
            ref: RepoRef,
            environment: String,
            name: String,
        ): GitHubResult<Unit> = no("deleteEnvironmentSecret")
        override fun deleteRepoSecret(token: String, ref: RepoRef, name: String): GitHubResult<Unit> =
            no("deleteRepoSecret")
        override fun restrictEnvironmentTo(
            token: String,
            ref: RepoRef,
            environment: String,
            branches: List<String>,
        ): GitHubResult<Unit> = no("restrictEnvironmentTo")
    }

    /** A GitHub that answers what the test says, and records what it was asked to do. */
    private inner class Fake(
        private val existing: List<CiEnvironment> = emptyList(),
        private val failEnvironments: String? = null,
        private val failPutEnvironment: String? = null,
        private val failKey: String? = null,
        private val failSecret: String? = null,
        private val secrets: List<String> = emptyList(),
    ) : Refusing() {
        val wrote = mutableListOf<Triple<String, String, ByteArray>>()
        var madeEnvironment: String? = null

        override fun environments(token: String, ref: RepoRef): GitHubResult<List<CiEnvironment>> =
            if (failEnvironments != null) GitHubResult(error = failEnvironments)
            else GitHubResult(value = existing)

        override fun putEnvironment(
            token: String,
            ref: RepoRef,
            name: String,
            reviewers: List<String>,
        ): GitHubResult<CiEnvironment> {
            if (failPutEnvironment != null) return GitHubResult(error = failPutEnvironment)
            madeEnvironment = name
            return GitHubResult(value = CiEnvironment(name, reviewers.size))
        }

        override fun environmentKey(
            token: String,
            ref: RepoRef,
            environment: String,
        ): GitHubResult<Pair<String, ByteArray>> = if (failKey != null) {
            GitHubResult(error = failKey)
        } else {
            // **The second of the pair is the public key** — `SealedBox.keyPair` answers
            // `(secret, public)`, and sealing with the secret would produce a value GitHub cannot
            // open. That ordering is the same trap the class comment warns about.
            GitHubResult(value = "a-key-id" to SealedBox.keyPair().second)
        }

        override fun putEnvironmentSecret(
            token: String,
            ref: RepoRef,
            environment: String,
            name: String,
            sealed: ByteArray,
            keyId: String,
        ): GitHubResult<Unit> {
            if (failSecret != null) return GitHubResult(error = failSecret)
            wrote += Triple(environment, name, sealed)
            return GitHubResult(value = Unit)
        }

        override fun environmentSecretNames(
            token: String,
            ref: RepoRef,
            environment: String,
        ): GitHubResult<List<String>> = GitHubResult(value = secrets)
    }

    @Test
    fun `setting up makes the environment and puts the key in it`() {
        val github = Fake()
        val done = CiSetup.run(github, "t", ref, key = "a-key-this-phone-made").getOrThrow()

        assertEquals("the environment is made", CiSetup.ENVIRONMENT, github.madeEnvironment)
        assertTrue("and the caller is told it was made", done.madeEnvironment)
        assertEquals("one secret was written", 1, github.wrote.size)
        val (environment, name, sealed) = github.wrote.single()
        assertEquals("into the ci environment", CiSetup.ENVIRONMENT, environment)
        assertEquals("named as the reader expects", EncryptedLog.KEY_NAME, name)
        assertTrue("and sealed, never plain", sealed.isNotEmpty())
        assertFalse(
            "the plaintext key is not in the sealed bytes",
            String(sealed, Charsets.ISO_8859_1).contains("a-key-this-phone-made"),
        )
        assertEquals("and the key comes back so it can be stored", "a-key-this-phone-made", done.key)
    }

    @Test
    fun `an environment that already exists is not made twice`() {
        // **The second run of a setup screen.** Making it again is not harmful, but reporting "made"
        // when nothing was made teaches a person that the report is decorative.
        val github = Fake(existing = listOf(CiEnvironment(CiSetup.ENVIRONMENT, 0)))
        val done = CiSetup.run(github, "t", ref).getOrThrow()

        assertEquals("nothing was created", null, github.madeEnvironment)
        assertFalse("and it says so", done.madeEnvironment)
        assertEquals("but the key is still written", 1, github.wrote.size)
    }

    @Test
    fun `the secret is written every time, because it cannot be read back`() {
        // **GitHub returns secret names and never values**, so "is the stored key the right one" is a
        // question the API cannot answer. Asserting it is the only honest move — skipping it on a
        // guess is how a rotated key leaves every log unreadable.
        val github = Fake(
            existing = listOf(CiEnvironment(CiSetup.ENVIRONMENT, 0)),
            secrets = listOf(EncryptedLog.KEY_NAME),
        )
        CiSetup.run(github, "t", ref, key = "a-rotated-key").getOrThrow()

        assertEquals("it was written even though a secret of that name exists", 1, github.wrote.size)
    }

    @Test
    fun `a failure at each step is named, and none of them reports success`() {
        // **A half-set-up CI is the state that produces unreadable logs with nothing to explain them**,
        // so every failure has to stop and say which step it was.
        val cases = listOf(
            Triple("environments could not be read", Fake(failEnvironments = "403"), "environments"),
            Triple("environment could not be made", Fake(failPutEnvironment = "422"), "environment"),
            Triple("public key refused", Fake(failKey = "404"), "public key"),
            Triple("secret refused", Fake(failSecret = "422"), "could not be written"),
        )
        for ((what, github, expected) in cases) {
            val result = CiSetup.run(github, "t", ref)
            assertTrue("$what is a failure", result.isFailure)
            val said = result.exceptionOrNull()?.message.orEmpty()
            assertTrue("$what names the step: $said", said.contains(expected, ignoreCase = true))
        }
    }

    @Test
    fun `the report names a missing environment before anything is run`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "cisetup-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val said = CiSetup.problems(Fake(), "t", ref, dir)
            assertTrue(
                "it says the environment is missing: $said",
                said.any { it.contains("no \"ci\" environment") },
            )
            assertTrue(
                "and that there is no key in it: $said",
                said.any { it.contains(EncryptedLog.KEY_NAME) },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the report is empty when everything is in place`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "cisetup-ok-${System.nanoTime()}")
        File(dir, Workflows.DIR).mkdirs()
        File(dir, Workflows.DIR + "/build.yml").writeText(
            """
            on: push
            jobs:
              build:
                environment: ci
                runs-on: ubuntu-24.04
                steps:
                  - run: ./gradlew test > out.txt 2>&1; openssl enc -aes-256-cbc -pass env:K -in out.txt
            """.trimIndent(),
        )
        try {
            val said = CiSetup.problems(
                Fake(existing = listOf(CiEnvironment(CiSetup.ENVIRONMENT, 0)),
                    secrets = listOf(EncryptedLog.KEY_NAME)),
                "t", ref, dir,
            )
            assertEquals("nothing to report: $said", emptyList<String>(), said)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a workflow that seals without an environment is named`() {
        // **The one failure that looks like success.** The workflow runs, seals, uploads — and every
        // byte it produced is unreadable, with the build green.
        val dir = File(System.getProperty("java.io.tmpdir"), "cisetup-seal-${System.nanoTime()}")
        File(dir, Workflows.DIR).mkdirs()
        File(dir, Workflows.DIR + "/build.yml").writeText(
            """
            on: push
            jobs:
              build:
                runs-on: ubuntu-24.04
                steps:
                  - run: openssl enc -aes-256-cbc -pass env:K -in out.txt
            """.trimIndent(),
        )
        try {
            val said = CiSetup.problems(
                Fake(existing = listOf(CiEnvironment(CiSetup.ENVIRONMENT, 0)),
                    secrets = listOf(EncryptedLog.KEY_NAME)),
                "t", ref, dir,
            )
            assertTrue(
                "it names the file and the reason: $said",
                said.any { it.contains("build.yml") && it.contains("no environment") },
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
