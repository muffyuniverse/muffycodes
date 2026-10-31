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
     * What a project's signing setup looks like right now — **asked, never acted on.**
     *
     * ## Why this exists
     *
     * Sending a signing key is not something an app may decide to do. The key is the most consequential
     * secret this app can hold, and a background reconcile that "helpfully" pushed it to a repository
     * the person had just added would be the app choosing to widen its own blast radius. So there is no
     * automatic send anywhere: this answers a question, and a screen puts the answer in front of a
     * person who can press the thing that sends.
     *
     * ## The four states, and why three of them are silent
     *
     * - **Ready**: a key on the phone and all four secrets in the environment. Nothing to say.
     * - **Not set up**: no key on the phone. Nothing to say — this project was never going to sign.
     * - **Needs sending**: a key on the phone and the environment is missing some of the four. **This
     *   is the one that speaks**, because the person has the material and CI cannot see it, so their
     *   next release is unsigned and they would find out from a failed install.
     * - **Unreachable**: the environment could not be asked about. Silent, because "I could not check"
     *   is not "you are missing something", and a warning nobody can act on trains people to ignore
     *   the ones they can.
     *
     * A person who never sets a key up is never told they should. That is deliberate: this app has
     * projects that are a folder of notes, and telling every one of them about a signing key would be
     * the nagging that makes the one real warning invisible.
     */
    data class Reconcile(
        /** The key is on the phone. */
        val haveKey: Boolean,
        /** Which of the four the environment already holds. */
        val inEnvironment: Set<String>,
        /** The environment's name. */
        val environment: String,
        /** Why the environment could not be asked about, or null when it was. */
        val unknown: String? = null,
        /**
         * Secrets sitting at **repository** scope, where every workflow can read them.
         *
         * **The state the app exists to get a person out of**, and the one it can fix: see
         * [Signing.move]. Empty is the ordinary answer.
         */
        val atRepository: List<String> = emptyList(),
    ) {
        /** Every name the build reads, in the order the report lists them. */
        private val all: List<String> get() = Field.entries.map { it.secretName }

        /** The four the environment is missing. */
        val missing: List<String> get() = all.filter { it !in inEnvironment }

        /** True when a key exists on the phone and the environment cannot see all of it. */
        val needsSending: Boolean get() = haveKey && unknown == null && missing.isNotEmpty()

        /**
         * True when the app can fix this in one press — a key on the phone, and something to do.
         *
         * **This is what the row is for.** A person who installs this app and has a signing key should
         * not have to know that five steps on github.com have an order; the app knows the order.
         */
        val fixable: Boolean get() = haveKey && unknown == null && (needsSending || atRepository.isNotEmpty())

        /**
         * One line for a screen, or null when there is nothing worth saying.
         *
         * **Silence is the common answer**, and that is the design: the warning exists for one state,
         * and a state that is fine, absent, or unknowable all read as fine.
         */
        fun said(): String? {
            // **Exposure first, because it is the worse of the two.** A missing key means the release
            // is unsigned; a repository-level key means every workflow the agent writes can read the
            // key that signs them. The second is the one worth interrupting for.
            if (atRepository.isNotEmpty()) {
                val names = atRepository.take(3).joinToString(", ")
                val rest = if (atRepository.size > 3) " and ${atRepository.size - 3} more" else ""
                return "This project's signing key can be read by every workflow in the repository " +
                    "($names$rest). It should be moved to the `$environment` environment, where " +
                    "only a build on ${BRANCHES.joinToString(" or ")} can reach it."
            }
            if (!needsSending) return null
            return "This project can't sign releases yet: ${missing.size} of the four signing " +
                "secret" + (if (missing.size == 1) " is" else "s are") + " not in the " +
                "`$environment` environment."
        }
    }

    /**
     * Asks whether a key on the phone is missing from the environment. **Reads only.**
     *
     * [keyFile] is where this project's `keystore.properties` would be — the caller decides where to
     * look, because a project's own folder is the only place the app can be sure a key belongs to it.
     */
    fun reconcile(
        github: GitHub,
        token: String,
        ref: RepoRef,
        keyFile: File,
        environment: String = ENVIRONMENT,
    ): Reconcile {
        if (!keyFile.isFile) {
            return Reconcile(haveKey = false, inEnvironment = emptySet(), environment = environment)
        }
        val names = github.environmentSecretNames(token, ref, environment)
        if (names.error != null) {
            return Reconcile(
                haveKey = true,
                inEnvironment = emptySet(),
                environment = environment,
                unknown = names.error,
            )
        }
        // **Asked here rather than by a second call from the screen**, because the two answers are one
        // question: "is this project's key where it should be". A screen that asked twice could draw
        // half an answer.
        val exposed = github.repoSecretNames(token, ref).value.orEmpty()
            .filter { it in Field.entries.map { field -> field.secretName } }
        return Reconcile(
            haveKey = true,
            inEnvironment = names.value.orEmpty().toSet(),
            environment = environment,
            atRepository = exposed,
        )
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
