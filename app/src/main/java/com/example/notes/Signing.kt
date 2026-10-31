package com.example.notes

import java.io.File

/**
 * Making a project's releases signable — **the whole job, in one press, in the only order that cannot
 * break a build.**
 *
 * ## Why this exists at all
 *
 * Doing it by hand is five steps on github.com, and the order matters:
 *
 * 1. add `environment: release` to the workflow's signing job;
 * 2. create the environment;
 * 3. restrict it to the branches that build;
 * 4. write the four values into it;
 * 5. delete the four repository-level ones.
 *
 * **Steps 4 and 5 are the dangerous pair.** A repository secret is visible to every workflow, so
 * leaving it there defeats the whole arrangement — but deleting it *before* the workflow names the
 * environment makes the key invisible and every build fails, with the failure appearing as "the release
 * is unsigned" long after the mistake.
 *
 * A person who installs this app and has a signing key should not have to know that. This class knows
 * it, and runs the steps in order so nobody else has to.
 *
 * ## Why the workflow edit comes first
 *
 * Adding `environment:` to a job that already reads repository secrets **changes nothing** — the job
 * still finds them, because a repository secret is visible at every scope. So it is safe to do first,
 * and doing it first is what makes the deletion in step 5 safe. The reverse order has a window, however
 * short, in which the key is in the environment and the workflow is not looking there.
 *
 * ## What it will not do
 *
 * **It never reads a secret back.** `ReleaseKey` reads the four from `keystore.properties` and seals
 * them; nothing here asks GitHub for a value, because GitHub does not return one and an app that could
 * would be an app whose keystore is its weakest link.
 *
 * **It does not invent the values.** No key on the phone means nothing to move, and that is reported
 * rather than guessed at.
 */
object Signing {

    /** What a move did, in words a screen can show. */
    data class Moved(
        val environment: String,
        val written: List<String>,
        val removed: List<String>,
        /** The workflow file that was given `environment:`, or null when it already had one. */
        val edited: String? = null,
        val failed: List<Pair<String, String>> = emptyList(),
    ) {
        /** True when everything a build needs is in place. */
        val settled: Boolean get() = failed.isEmpty() && written.isNotEmpty()

        fun said(): String {
            if (written.isEmpty() && failed.isEmpty()) return "Nothing was moved"
            val head = if (written.isEmpty()) {
                "Nothing was moved"
            } else {
                "Moved ${written.size} signing secret" + (if (written.size == 1) "" else "s") +
                    " to the `$environment` environment"
            }
            val gone = if (removed.isEmpty()) {
                ""
            } else {
                ", and removed ${removed.size} from the repository so no other workflow can read " +
                    (if (removed.size == 1) "it" else "them")
            }
            val workflow = if (edited == null) {
                ""
            } else {
                ". `$edited` now names the environment, so the build can find the key"
            }
            val bad = if (failed.isEmpty()) {
                ""
            } else {
                ". ${failed.size} could not be moved: " +
                    failed.take(2).joinToString("; ") { (name, why) -> "$name: $why" }
            }
            return head + gone + workflow + bad + "."
        }
    }

    /**
     * Does everything [Moved] describes, or stops before anything that would break a build.
     *
     * [projectDir] is where the workflow files and the key live. [workflow] is the file that signs; when
     * it already names an environment, nothing is edited and the environment is used as it is — because
     * a project that names `production` should keep naming it rather than be moved to this app's name.
     */
    fun move(
        github: GitHub,
        token: String,
        ref: RepoRef,
        projectDir: File,
        keyFile: File,
        workflow: File,
        environment: String = ReleaseKey.ENVIRONMENT,
    ): Result<Moved> {
        if (!keyFile.isFile) {
            return Result.failure(
                IllegalStateException(
                    "There is no ${keyFile.name} in this project, so there is no signing key to move.",
                ),
            )
        }
        // ---- 1. the workflow names the environment, before anything moves ----
        //
        // **A no-op when it already does**, which is why this is safe to run on every press. The
        // named environment is read from the file rather than assumed: a project that says
        // `environment: production` is set up, and moving its secrets to `release` would be moving
        // them somewhere the build is not looking.
        val workflows = Workflows()
        val named = workflows.environmentNameOf(workflow)
        val target = named ?: environment
        val edited = if (named != null) {
            null
        } else {
            workflows.nameEnvironment(workflow, target).getOrElse { return Result.failure(it) }
        }

        // ---- 2 and 3. the environment exists, and only the branches that build may use it ----
        val made = github.putEnvironment(token, ref, target, emptyList())
        if (made.error != null) {
            return Result.failure(
                IllegalStateException("The environment could not be made: ${made.error}"),
            )
        }
        val restricted = github.restrictEnvironmentTo(token, ref, target, ReleaseKey.BRANCHES)
        if (restricted.error != null) {
            return Result.failure(
                IllegalStateException(
                    "The environment could not be restricted to " +
                        "${ReleaseKey.BRANCHES.joinToString(", ")}: ${restricted.error}",
                ),
            )
        }

        // ---- 4. the four values, sealed for this environment ----
        val key = github.environmentKey(token, ref, target)
        if (key.error != null) {
            return Result.failure(
                IllegalStateException("The environment's key could not be read: ${key.error}"),
            )
        }
        val sent = ReleaseKey(keyFile).send(github, token, ref, target)
        val report = sent.getOrElse { return Result.failure(it) }
        if (report.written.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "Nothing could be written to the environment, so nothing was removed from the " +
                        "repository either: " + report.failed.joinToString("; ") { it.first },
                ),
            )
        }

        // ---- 5. the repository copies go, and only the ones that arrived ----
        //
        // **A name that failed to be written is left alone.** Deleting the repository copy of a value
        // that did not reach the environment would lose the key entirely, which is the one outcome
        // worse than leaving it exposed.
        val removed = mutableListOf<String>()
        val failed = report.failed.toMutableList()
        for (name in report.written) {
            val gone = github.deleteRepoSecret(token, ref, name)
            if (gone.error != null) {
                failed.add(name to "the repository copy could not be removed: ${gone.error}")
            } else {
                removed.add(name)
            }
        }
        return Result.success(
            Moved(
                environment = target,
                written = report.written,
                removed = removed,
                edited = edited,
                failed = failed,
            ),
        )
    }
}
