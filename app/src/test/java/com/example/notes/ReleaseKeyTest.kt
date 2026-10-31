package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The release signing key, on its way to GitHub.
 *
 * **What is asserted here is mostly about what is never asserted anywhere**: that the plaintext of a
 * password never reaches the thing that sends it. The value is read from a file, sealed, and the sealed
 * bytes are the only thing the GitHub interface is given — so the test can prove the boundary by looking
 * at what the fake stored.
 *
 * The fake is [DiskGitHub], whose secrets are files of exactly the bytes it was handed.
 */
class ReleaseKeyTest {

    private lateinit var root: File
    private lateinit var project: File
    private lateinit var github: DiskGitHub
    private lateinit var properties: File

    /** A keystore, small enough to be a test fixture and shaped like a real one. */
    private val keystoreBytes = ByteArray(64) { (it * 7).toByte() }

    private val ref = RepoRef("dev", "muffycodes")

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "release-key-" + System.nanoTime())
        root.mkdirs()
        project = File(root, "project")
        project.mkdirs()
        github = DiskGitHub(File(root, "github"), "dev")
        github.repoDir(ref.name).mkdirs()

        File(project, "release.jks").writeBytes(keystoreBytes)
        properties = File(project, ReleaseKey.FILE)
        properties.writeText(
            """
            storeFile=release.jks
            storePassword=hunter2-not-really
            keyAlias=notes
            keyPassword=second-password
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun key() = ReleaseKey(properties)

    // ---- reading the file -------------------------------------------------

    @Test
    fun `the four values the build reads are the four the app sends`() {
        // **The same four names `build-and-release.yml` reads**, because a name the app invented would
        // be a secret the workflow never looks for.
        val found = key().read().getOrThrow()
        assertEquals(
            listOf(
                "MUFFY_KEYSTORE_BASE64",
                "MUFFY_KEYSTORE_PASSWORD",
                "MUFFY_KEY_ALIAS",
                "MUFFY_KEY_PASSWORD",
            ),
            found.names,
        )
        assertEquals("hunter2-not-really", found.storePassword)
        assertEquals("notes", found.alias)
        assertEquals("second-password", found.keyPassword)
    }

    @Test
    fun `a missing key password falls back to the store password`() {
        // The Gradle build does the same (`keyPass = keyPasswordEnv ?: storePasswordEnv`), and a
        // keystore whose key password matches its store password is the common case.
        properties.writeText(
            """
            storeFile=release.jks
            storePassword=one-password
            keyAlias=notes
            """.trimIndent(),
        )
        assertEquals("one-password", key().read().getOrThrow().keyPassword)
    }

    @Test
    fun `a properties file with no keystore beside it is refused, and says which`() {
        File(project, "release.jks").delete()
        val why = key().read().exceptionOrNull()?.message.orEmpty()
        assertTrue("it names the file: $why", why.contains("release.jks"))
    }

    @Test
    fun `a properties file missing a value is refused, and names it`() {
        properties.writeText("storeFile=release.jks\nstorePassword=x\n")
        val why = key().read().exceptionOrNull()?.message.orEmpty()
        assertTrue("it names the alias: $why", why.contains("keyAlias"))
    }

    @Test
    fun `no properties file at all is a sentence, not a crash`() {
        properties.delete()
        val why = key().read().exceptionOrNull()?.message.orEmpty()
        assertTrue("it says there is none: $why", why.contains("no keystore.properties"))
    }

    // ---- sending it -------------------------------------------------------

    @Test
    fun `the key goes to an environment, and the plaintext never travels`() {
        // **The assertion this whole class exists for.** What the fake stored must be a sealed box —
        // not the password — and it must open with the environment's key, which proves the sealing was
        // done against the right one.
        val report = key().send(github, "a-key", ref).getOrThrow()

        assertEquals("all four are written", 4, report.written.size)
        assertEquals(
            listOf(
                "MUFFY_KEYSTORE_BASE64",
                "MUFFY_KEYSTORE_PASSWORD",
                "MUFFY_KEY_ALIAS",
                "MUFFY_KEY_PASSWORD",
            ).sorted(),
            report.written.sorted(),
        )
        assertEquals("into the release environment", "release", report.environment)

        val stored = github.sealedSecret(ref, "release", "MUFFY_KEYSTORE_PASSWORD")
        assertTrue("the password was stored", stored != null)
        val text = stored!!.toString(Charsets.ISO_8859_1)
        assertFalse(
            "the password is not in the stored bytes",
            text.contains("hunter2-not-really"),
        )
        // **Opened, not merely measured.** The fake's key is derived from the environment's name, so
        // the test can ask for it again and decrypt — which proves the value was sealed against the
        // right key. A length check alone would pass even if the seal were wrong.
        val opened = SealedBox.open(
            github.fakeSecretKey("release"),
            github.environmentKey("a-key", ref, "release").value!!.second,
            stored,
        ).getOrThrow()
        assertEquals("and it opens to the password", "hunter2-not-really", String(opened))
    }

    @Test
    fun `the keystore travels as base64, because the build decodes it`() {
        // `app/build.gradle.kts` does `Base64.getDecoder().decode(base64Key)`, so a secret holding raw
        // bytes would decode to nothing and the release would be unsigned with no error to show.
        key().send(github, "a-key", ref).getOrThrow()
        val stored = github.sealedSecret(ref, "release", "MUFFY_KEYSTORE_BASE64")!!
        val (keyId, public) = github.environmentKey("a-key", ref, "release").value!!
        val opened = SealedBox.open(github.fakeSecretKey("release"), public, stored).getOrThrow()
        // **What the secret holds is base64 of the keystore**, and decoding it must give the file back
        // byte for byte — which is exactly what `app/build.gradle.kts` does with it.
        val decoded = Base64.getDecoder().decode(String(opened))
        assertTrue("the keystore comes back", decoded.contentEquals(keystoreBytes))
        assertTrue("the fake gave a key id", keyId.isNotEmpty())
        assertTrue("and a public key", public.size == SealedBox.PUBLIC_KEY)
    }

    @Test
    fun `the environment is made before any secret goes into it`() {
        // **A secret put into an environment that does not exist** is a value GitHub accepts and no job
        // can reference, so the environment is created first — and with its reviewers, or it is not a
        // gate at all.
        val report = key().send(github, "a-key", ref, reviewers = listOf("muffyuniverse"))
            .getOrThrow()

        // **`GitHubResult`, not `Result`** — it carries a status as well as a value, so it has no
        // `getOrThrow`. The value is present exactly when the error is absent, which is what
        // `isSuccess` says.
        val environments = github.environments("a-key", ref).value.orEmpty()
        assertEquals(1, environments.size)
        assertEquals("release", environments.first().name)
        assertTrue("it has a reviewer", environments.first().protected)
        assertEquals("and the report says so", 1, report.reviewers)
        assertTrue("in the sentence: ${report.said()}", report.said().contains("reviewer"))
    }

    @Test
    fun `an environment with no reviewers is reported rather than refused`() {
        // A person may be setting this up before deciding who reviews it. The report says plainly that
        // nothing stands between a run and the key, which is the fact that matters.
        val report = key().send(github, "a-key", ref).getOrThrow()
        assertEquals("no reviewers", 0, report.reviewers)
        assertFalse(
            "and the sentence does not claim one: ${report.said()}",
            report.said().contains("reviewer"),
        )
    }

    @Test
    fun `a refused key is reported as a failure, not as a success with nothing written`() {
        val report = key().send(github, "", ref)
        assertTrue("it failed", report.isFailure)
    }

    @Test
    fun `the report never contains a value`() {
        // **The rule the vault proposal was written around.** An agent that can print a secret has the
        // secret, and no amount of "do not print it" is a control — so the one object that travels back
        // through the app is checked for the plaintext.
        val report = key().send(github, "a-key", ref).getOrThrow()
        val said = report.said()
        assertFalse("not the store password", said.contains("hunter2-not-really"))
        assertFalse("not the key password", said.contains("second-password"))
        assertFalse("not the alias", said.contains("notes"))
        assertTrue("only names and counts: $said", said.contains("4 secrets"))
    }
}
