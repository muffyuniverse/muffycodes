package com.example.notes

import java.io.File

/**
 * The app's own release signing key, put where the build can reach it.
 *
 * ## Why this is the first secret, and the only one for now
 *
 * `build-and-release.yml` reads four repository secrets — `MUFFY_KEYSTORE_BASE64`,
 * `MUFFY_KEYSTORE_PASSWORD`, `MUFFY_KEY_ALIAS`, `MUFFY_KEY_PASSWORD` — and without them the release is
 * unsigned, which the workflow says out loud: *"A release that is not signed cannot be installed."* The
 * same four values already live on this phone in `keystore.properties`. So the app can put them where CI
 * needs them without anybody opening github.com, base64-encoding a keystore by hand, and pasting a
 * password into a web form.
 *
 * That is the concrete use case that made the whole secret path worth building: not a hypothetical, but
 * a step this repository already depends on.
 *
 * ## Why an environment, and never the repository
 *
 * A **repository** secret is readable by every workflow in the repository — including one the agent
 * wrote and started. An **environment** secret is readable only by a job that names the environment, and
 * if that environment requires reviewers, not until a human approves the job. The signing key signs
 * releases; it must not be reachable by a workflow on `development`.
 *
 * So the key goes to an environment called [ENVIRONMENT], and the workflow that needs it must say
 * `environment: release`.
 *
 * ## What this class will not do
 *
 * **It never returns a secret value, and it never puts one in a message.** The four values are read from
 * the properties file, sealed one at a time, and sent. What comes back is a count and a list of names —
 * which is the same rule the vault proposal was written around: an agent that can print a secret has the
 * secret, and no amount of "do not print it" in a prompt is a control.
 */
class ReleaseKey(private val properties: File) {

    /** One of the four values the build reads. */
    enum class Field(val secretName: String, val property: String) {
        KEYSTORE("MUFFY_KEYSTORE_BASE64", "storeFile"),
        STORE_PASSWORD("MUFFY_KEYSTORE_PASSWORD", "storePassword"),
        ALIAS("MUFFY_KEY_ALIAS", "keyAlias"),
        KEY_PASSWORD("MUFFY_KEY_PASSWORD", "keyPassword"),
    }

    /** What the file says, without saying what any of it is. */
    data class Found(
        val keystore: File,
        val storePassword: String,
        val alias: String,
        val keyPassword: String,
    ) {
        /** The names this would write, for a screen that has to say what will happen. */
        val names: List<String> get() = Field.entries.map { it.secretName }
    }

    /**
     * The four values, or a sentence about which one is missing.
     *
     * **Read from the properties file rather than from the keystore itself**, because the keystore
     * needs a password to open and the build already treats these four as the whole of the
     * configuration. Reading the same four things the build reads is what makes "the app sent the key"
     * and "the build found the key" the same statement.
     */
    fun read(): Result<Found> {
        if (!properties.isFile) {
            return Result.failure(
                IllegalStateException(
                    "There is no ${properties.name} on this phone, so there is no signing key to send.",
                ),
            )
        }
        val loaded = try {
            java.util.Properties().apply { properties.inputStream().use { load(it) } }
        } catch (e: Throwable) {
            return Result.failure(
                IllegalStateException("${properties.name} could not be read: ${e.message}"),
            )
        }
        val storePath = loaded.getProperty("storeFile")?.trim().orEmpty()
        if (storePath.isEmpty()) {
            return Result.failure(
                IllegalStateException("${properties.name} does not say where the keystore is."),
            )
        }
        // **Relative to the file's own folder**, which is how the Gradle build resolves it.
        val keystore = File(properties.parentFile, storePath)
        if (!keystore.isFile) {
            return Result.failure(
                IllegalStateException("The keystore ${keystore.name} is not beside ${properties.name}."),
            )
        }
        val storePassword = loaded.getProperty("storePassword").orEmpty()
        val alias = loaded.getProperty("keyAlias").orEmpty()
        val keyPassword = loaded.getProperty("keyPassword").orEmpty().ifEmpty { storePassword }
        val missing = buildList {
            if (storePassword.isEmpty()) add("storePassword")
            if (alias.isEmpty()) add("keyAlias")
            if (keyPassword.isEmpty()) add("keyPassword")
        }
        if (missing.isNotEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "${properties.name} does not say ${missing.joinToString(", ")}.",
                ),
            )
        }
        return Result.success(Found(keystore, storePassword, alias, keyPassword))
    }

    /**
     * The four values as the strings the secrets must hold.
     *
     * **The keystore travels as base64**, because it is a binary file and the workflow decodes it —
     * `Base64.getDecoder().decode(base64Key)` in `app/build.gradle.kts`. Sending the raw bytes would
     * produce a secret that decodes to nothing.
     */
    private fun values(found: Found): Map<String, String> = mapOf(
        Field.KEYSTORE.secretName to try {
            java.util.Base64.getEncoder().encodeToString(found.keystore.readBytes())
        } catch (e: Throwable) {
            ""
        },
        Field.STORE_PASSWORD.secretName to found.storePassword,
        Field.ALIAS.secretName to found.alias,
        Field.KEY_PASSWORD.secretName to found.keyPassword,
    )

    /** What happened, in names and counts. **Never a value.** */
    data class Report(
        val environment: String,
        val written: List<String>,
        val failed: List<Pair<String, String>> = emptyList(),
        val reviewers: Int = 0,
    ) {
        /** One sentence for the screen. */
        fun said(): String {
            if (written.isEmpty() && failed.isEmpty()) return "Nothing was sent"
            val head = when (written.size) {
                0 -> "Nothing was sent"
                1 -> "Sent 1 secret to $environment"
                else -> "Sent ${written.size} secrets to $environment"
            }
            val guarded = if (reviewers > 0) {
                " A run that wants them waits for $reviewers reviewer" +
                    (if (reviewers == 1) "." else "s.")
            } else {
                ""
            }
            if (failed.isEmpty()) return head + "." + guarded
            val why = failed.take(2).joinToString("; ") { (name, said) -> "$name: $said" }
            val rest = if (failed.size > 2) " and ${failed.size - 2} more" else ""
            return "$head, and ${failed.size} could not be: $why$rest."
        }
    }

    /**
     * Sends the four values to [environment], sealed for it.
     *
     * **Each value is sealed separately with the environment's own key**, which is what GitHub requires:
     * the key is per environment, and sealing with the repository's key produces a value the environment
     * will not decrypt.
     *
     * [reviewers] is passed straight to the environment, and **an environment with no reviewers is
     * reported rather than refused** — a person may be setting this up before deciding who reviews it,
     * and the report's own sentence says whether a human stands in the way.
     */
    fun send(
        github: GitHub,
        token: String,
        ref: RepoRef,
        environment: String = ENVIRONMENT,
        reviewers: List<String> = emptyList(),
    ): Result<Report> {
        val found = read().getOrElse { return Result.failure(it) }
        val values = values(found)
        val blank = values.filterValues { it.isEmpty() }.keys
        if (blank.isNotEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "The keystore could not be read, so ${blank.joinToString(", ")} cannot be sent.",
                ),
            )
        }
        // **The environment first, with its reviewers**, because a secret put into an environment that
        // does not exist is a secret GitHub accepts and no job can reference.
        val made = github.putEnvironment(token, ref, environment, reviewers)
        if (made.error != null) {
            return Result.failure(
                IllegalStateException("The environment could not be made: ${made.error}"),
            )
        }
        val key = github.environmentKey(token, ref, environment)
        if (key.error != null) {
            return Result.failure(
                IllegalStateException("The environment's key could not be read: ${key.error}"),
            )
        }
        // **The environment is restricted to the branches that build, before any secret goes in it.**
        // The threat is the agent publishing a release, and the agent only touches `development` — so an
        // environment `main` and `development` may use is unreachable by anything the agent can do. That
        // is a gate with nothing for a person to approve, which is why no reviewer is needed.
        //
        // `development` as well as `main`, because `build-release` runs on both and signs both with the
        // same key on purpose: a dev build installs over the release one.
        val restricted = github.restrictEnvironmentTo(token, ref, environment, BRANCHES)
        if (restricted.error != null) {
            return Result.failure(
                IllegalStateException(
                    "The environment could not be restricted to ${BRANCHES.joinToString(", ")}: " +
                        restricted.error,
                ),
            )
        }
        val (keyId, publicKey) = key.value!!
        val written = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for ((name, value) in values.entries.sortedBy { it.key }) {
            val sealed = try {
                SealedBox.seal(publicKey, value.toByteArray())
            } catch (e: Throwable) {
                failed.add(name to (e.message ?: "it could not be sealed"))
                continue
            }
            val put = github.putEnvironmentSecret(token, ref, environment, name, sealed, keyId)
            if (put.error != null) {
                failed.add(name to put.error)
            } else {
                written.add(name)
            }
        }
        return Result.success(
            Report(
                environment = environment,
                written = written,
                failed = failed,
                reviewers = made.value?.reviewers ?: reviewers.size,
            ),
        )
    }

    companion object {
        /**
         * The environment the signing key lives in.
         *
         * **A name rather than a setting**, because the workflow has to say `environment: release` in
         * its own text: a name the app chose and the workflow did not would be an environment nothing
         * references, and the secrets in it would never be read.
         */
        const val ENVIRONMENT = "release"

        /** The file the four values come from, at the root of a checkout. */
        const val FILE = "keystore.properties"

        /**
         * The branches that may use the `release` environment.
         *
         * **`main` publishes and `development` builds an installable APK signed with the same key** —
         * that is deliberate, so a dev build installs over the release one. Both need the key; nothing
         * else does.
         */
        val BRANCHES = listOf("main", "development")
    }
}
