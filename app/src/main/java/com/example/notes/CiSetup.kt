package com.example.notes

/**
 * Setting up a project's CI so that its logs and artifacts can be read by this app.
 *
 * ## The three facts that must all be true
 *
 * A workflow seals its output with a key. For the app to open that output again, three things have to
 * agree, and **a mismatch in any one of them is silent**:
 *
 * 1. **The key exists in this app.** It is the account's secret, kept in the same store as the GitHub
 *    token, and it is what `EncryptedLog.read` and `EncryptedLog.openBytes` are given.
 * 2. **The same key is in the repository's `ci` environment.** Not a repository secret: the workflow
 *    names `environment: ci`, and GitHub's rule is that environment secrets reach only jobs that name
 *    the environment. A repository secret would also be readable by any workflow in the repository,
 *    including one an agent wrote.
 * 3. **The workflow names that environment.** `tools/check-workflow.py` refuses a workflow that
 *    encrypts without one, and the app's own `Workflows.reach` reports the same thing to a person.
 *
 * Get one wrong and the seal runs under an empty password: every log and every artifact is unreadable,
 * and **nothing reports a problem** — the build is green, the artifacts download, and only the reading
 * fails. So this function exists rather than a paragraph of instructions, and it reports each step.
 *
 * ## Why it can be run twice
 *
 * A person will run it again after rotating a key, after re-creating a project, or because they are not
 * sure whether it worked. Every step here is therefore either idempotent or reads the current state
 * first: the environment is created only when it is absent, and the secret is written every time
 * because GitHub gives no way to read a secret's value back — so "is it the right key" cannot be asked,
 * only re-asserted.
 */
object CiSetup {

    /** The environment every sealing workflow names. One constant, because both sides must agree. */
    const val ENVIRONMENT = "ci"

    /** What setting up produced, in the order it happened, so a screen can show it. */
    data class Done(
        /** The key that is now stored on this phone and in the repository. Never printed. */
        val key: String,
        /** True when this call made the environment, false when it already existed. */
        val madeEnvironment: Boolean,
        /** The steps, each a sentence a person can read. */
        val steps: List<String>,
    )

    /**
     * Makes this account's key exist, and puts it in [ref]'s `ci` environment.
     *
     * **Returns the key so the caller can store it**, rather than writing to the store itself: the
     * store is per-account and this function is given a repository, and an object that reaches for the
     * store cannot be tested without one.
     *
     * On any failure the answer is a `Result.failure` carrying the sentence a person needs — never a
     * partial success reported as success, because a half-set-up CI is exactly the state that produces
     * unreadable logs with nothing to explain them.
     */
    fun run(
        github: GitHub,
        token: String,
        ref: RepoRef,
        key: String = EncryptedLog.newKey(),
    ): Result<Done> {
        val steps = mutableListOf<String>()

        // **The environment first, and its absence is not a failure.** A repository that has never had
        // one answers 404; that is the ordinary first run, not an error.
        val existing = github.environments(token, ref)
        if (existing.error != null) {
            return Result.failure(
                IllegalStateException(
                    "This project's environments could not be read, so CI was not set up: " +
                        existing.error,
                ),
            )
        }
        val present = existing.value.orEmpty().any { it.name == ENVIRONMENT }
        val made = if (present) {
            false
        } else {
            val put = github.putEnvironment(token, ref, ENVIRONMENT, emptyList())
            if (put.error != null) {
                return Result.failure(
                    IllegalStateException(
                        "The \"$ENVIRONMENT\" environment could not be made, so there is nowhere to " +
                            "put the key: " + put.error,
                    ),
                )
            }
            true
        }
        steps += if (made) {
            "Made the \"$ENVIRONMENT\" environment."
        } else {
            "The \"$ENVIRONMENT\" environment was already there."
        }

        // **The key is sealed to the environment's own public key.** GitHub hands out a different key
        // per environment, and sealing with the repository's produces a value the environment will not
        // decrypt — a failure that looks exactly like a wrong password.
        val pair = github.environmentKey(token, ref, ENVIRONMENT)
        if (pair.error != null) {
            return Result.failure(
                IllegalStateException(
                    "GitHub would not give the public key for the \"$ENVIRONMENT\" environment, so " +
                        "the key could not be sealed: " + pair.error,
                ),
            )
        }
        val (keyId, publicKey) = pair.value
            ?: return Result.failure(
                IllegalStateException("GitHub returned no public key for \"$ENVIRONMENT\"."),
            )

        val sealed = try {
            SealedBox.seal(publicKey, key.toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) {
            return Result.failure(
                IllegalStateException(
                    "The key could not be sealed for GitHub: " + Secrets.explain(e, "not sealed"),
                ),
            )
        }

        // **Written every time, because it cannot be read back.** GitHub returns secret *names* and
        // never values, so "is the stored key the right one" is a question the API cannot answer. The
        // honest move is to assert it rather than to skip it on a guess.
        val wrote = github.putEnvironmentSecret(
            token, ref, ENVIRONMENT, EncryptedLog.KEY_NAME, sealed, keyId,
        )
        if (wrote.error != null) {
            return Result.failure(
                IllegalStateException(
                    "The key could not be written to the \"$ENVIRONMENT\" environment: " + wrote.error,
                ),
            )
        }
        steps += "Put ${EncryptedLog.KEY_NAME} into the \"$ENVIRONMENT\" environment."

        return Result.success(Done(key = key, madeEnvironment = made, steps = steps))
    }

    /**
     * What is still missing, so a screen can say it before a person runs anything.
     *
     * **Reads only.** A workflow that encrypts and names no environment is reported here rather than
     * discovered later as a log nobody can read; `Workflows.survey` already answers that question per
     * file, and this is the project-wide summary of it.
     */
    fun problems(
        github: GitHub,
        token: String,
        ref: RepoRef,
        projectDir: java.io.File,
        workflows: Workflows = Workflows(),
    ): List<String> {
        val said = mutableListOf<String>()

        val existing = github.environments(token, ref)
        if (existing.error == null && existing.value.orEmpty().none { it.name == ENVIRONMENT }) {
            said += "This project has no \"$ENVIRONMENT\" environment, so a sealing workflow has " +
                "nowhere to read its key from. Set up CI to make one."
        }

        val names = github.environmentSecretNames(token, ref, ENVIRONMENT)
        if (names.error == null && EncryptedLog.KEY_NAME !in names.value.orEmpty()) {
            said += "The \"$ENVIRONMENT\" environment has no ${EncryptedLog.KEY_NAME}, so anything " +
                "a workflow seals could not be opened."
        }

        // **A sealing workflow with no environment is the one failure that looks like success**, so it
        // is named even when the secret exists.
        //
        // `Reach.declaresEnvironment` is the question, and it is the same flag the dispatch guard uses
        // — deliberately not `environments.isNotEmpty()`, because a file whose environment is an
        // expression has no resolvable name and answering "none" for it would be the dangerous
        // direction. Reusing it here means this report and that guard can never disagree.
        // **`Workflows` is a class, not an object**, so the survey is asked of an instance. It holds
        // no state — every question it answers is read from the files — so making one here costs
        // nothing and keeps the constructor a seam a test can pass something else through.
        for (row in workflows.survey(projectDir, emptySet())) {
            if (!row.seals) continue
            if (!row.reach.declaresEnvironment) {
                said += "${row.path} seals its output but names no environment, so it cannot reach " +
                    "the key and everything it seals is unreadable."
            }
        }
        return said
    }
}
