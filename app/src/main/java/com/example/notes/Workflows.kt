package com.example.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * What the app knows about a project's workflows: whether a person read these bytes, and what the
 * file can reach.
 *
 * ## Why this exists
 *
 * The operator's decision was that `.github/` is agent-writable — *"this is our version of 'bash'
 * command… github runner is the ideal sandbox"* — and that premise is right: a disposable Ubuntu
 * machine returning stdout, an exit code and files is a better answer to "run this program" than
 * anything an app without a shell could build.
 *
 * But **a runner is a sandbox for the machine, not for the repository.** A workflow file is not data
 * the runner processes; it is what the runner *obeys*. And every workflow in a repository can read
 * every repository secret, so an agent that writes one can write:
 *
 * ```yaml
 * - run: echo "${{ secrets.ANYTHING }}" > out.txt
 * - uses: actions/upload-artifact@…
 * ```
 *
 * which is the attack the operator named when specifying the vault: *"an agent can just print a secret
 * inside a runner and export value as artifact even if he gets only the key."*
 *
 * ## What this class does, and what it deliberately does not
 *
 * **It does not gate anything.** The operator's ruling: *"unapproved workflows still can be executed
 * but without secrets."* The first half of that is buildable and this does it — an unapproved workflow
 * runs. The second half is not buildable by an app, and pretending otherwise would be worse than not
 * trying:
 *
 * - GitHub has **no per-workflow secret scoping**. Repository secrets are available to every workflow
 *   in the repository; there is no switch anywhere that says "this file may read `DEPLOY_TOKEN`, that
 *   one may not."
 * - The app holds the token, but the token is not the gate. The app can refuse to *dispatch*; once it
 *   dispatches, GitHub applies its own rules.
 * - A static scan of the YAML is **defeatable** — `secrets[format('{0}','X')]`, a reusable
 *   `workflow_call` that reads secrets in the *called* file, a `run:` block that shells out. A
 *   checkmark that can be a lie is worse than none, because people trust it.
 *
 * **What is enforceable is environment secrets with required reviewers**, and GitHub documents it
 * exactly: *"Secrets stored in an environment are only available to workflow jobs that reference the
 * environment. If the environment requires approval, a job cannot access environment secrets until one
 * of the required reviewers approves it."* So a secret on an environment is **structurally** out of
 * reach of a workflow that does not name that environment — no scan required, no promise from us.
 *
 * That is why this class reports **which environments a workflow names** rather than pretending to
 * know whether it can reach a secret. The honest sentence is *"this file references an environment"*,
 * not *"this file is safe."*
 *
 * ## What the checkmark means
 *
 * **Version-specific, and it means one thing: a person read exactly these bytes.** Change one
 * character and the mark goes away, because the bytes a person read are not the bytes that would run.
 * An approval made by the auto-approve switch is recorded as such and **does not get the same mark** —
 * the switch is a decision to stop reading, and the list should say so rather than laundering it into
 * the mark that means somebody did.
 */
class Workflows(private val stateDir: File) {

    /** How a workflow came to be approved. */
    enum class By {
        /** A person read the file and approved it. This is what the checkmark means. */
        PERSON,

        /** The auto-approve switch was on. Nobody read it. */
        AUTO,
    }

    /** One approval: which file, the bytes it had, and who said so. */
    data class Approved(
        /** Path from the project's root, forward slashes: `.github/workflows/build.yml`. */
        val path: String,
        /** SHA-256 of the file's bytes, lower-case hex. */
        val sha: String,
        val at: Long,
        val by: By,
    )

    /**
     * Whether these bytes are the ones a person approved.
     *
     * **A state, not a permission.** Nothing refuses on this — see the class comment — so it is
     * reported rather than consulted.
     */
    sealed class Approval {
        /** A person approved exactly these bytes. */
        data class Trusted(val at: Long) : Approval()

        /** Approved by the auto-approve switch, so nobody read it. */
        data class Automatic(val at: Long) : Approval()

        /** Approved once, and the file has changed since. */
        data class Changed(val was: String, val now: String) : Approval()

        /** Never approved. */
        data object None : Approval()

        /** True when a person read these bytes — **the only state that draws a checkmark.** */
        val checked: Boolean get() = this is Trusted

        /** One phrase for a list row. */
        fun said(): String = when (this) {
            is Trusted -> "approved"
            is Automatic -> "auto-approved"
            is Changed -> "changed since approval"
            is None -> "not approved"
        }
    }

    /**
     * What a workflow file can reach, read from its text.
     *
     * **A report, never a guarantee.** [secrets] being true means the file *mentions* a secret, not
     * that it can read one — and [secrets] being false means only that this scan found no mention,
     * which a determined file could arrange while still reading one. The scan errs toward **showing
     * risk**: a false "it mentions secrets" costs a person a glance, and a false "it does not" is the
     * mistake that matters.
     */
    data class Reach(
        /** Environment names the file references, in the order they appear. */
        val environments: List<String>,
        /** True when the text mentions a secret, a token, or inherits secrets. */
        val secrets: Boolean,
    )

    /** One workflow in a project, as a list row needs it. */
    data class Row(
        val path: String,
        val approval: Approval,
        val reach: Reach,
    ) {
        /** The file's own name, for a row that already sits inside `.github/workflows/`. */
        val name: String get() = path.substringAfterLast('/')
    }

    private val file: File get() = File(stateDir, FILE)

    /**
     * Every approval, keyed by path.
     *
     * Read from disk on each call rather than cached, unlike `locked.json`. The difference is who
     * writes it: a lock changes when a person taps, several times a minute at worst, while an approval
     * changes when somebody reads a workflow — once in a while.
     */
    fun approved(): Map<String, Approved> {
        if (!file.isFile) return emptyMap()
        return try {
            val array = JSONArray(file.readText())
            buildMap {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val path = obj.optString("path").trim('/')
                    val sha = obj.optString("sha")
                    if (path.isNotEmpty() && sha.isNotEmpty()) {
                        put(
                            path,
                            Approved(
                                path = path,
                                sha = sha,
                                at = obj.optLong("at", 0L),
                                // **A record with no `by` is a person's**, because the switch that
                                // writes AUTO did not exist before this field did: every approval
                                // written by an earlier build was a person reading the file.
                                by = if (obj.optString("by") == "auto") By.AUTO else By.PERSON,
                            ),
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            // **A file that will not parse approves nothing.** The opposite — treating a corrupt list
            // as "everything was approved" — would turn a damaged flag into a grant.
            emptyMap()
        }
    }

    /** Approves [path] as it is on disk now, and answers the hash it recorded. */
    fun approve(projectDir: File, path: String, by: By = By.PERSON): Result<String> {
        val clean = path.trim('/')
        val target = File(projectDir, clean)
        if (!target.isFile) return Result.failure(IllegalStateException("There is no file at $clean"))
        val sha = try {
            sha(target.readBytes())
        } catch (e: Throwable) {
            return Result.failure(IllegalStateException("$clean could not be read: ${e.message}"))
        }
        val all = approved().toMutableMap()
        all[clean] = Approved(clean, sha, System.currentTimeMillis(), by)
        return write(all).map { sha }
    }

    /** Withdraws an approval. */
    fun withdraw(path: String): Result<Unit> {
        val all = approved().toMutableMap()
        all.remove(path.trim('/'))
        return write(all)
    }

    /**
     * Whether [path] in [projectDir] is the workflow somebody approved — **on the bytes.**
     *
     * A modification time would answer wrongly in both directions: a file can be touched without
     * changing, and a checkout can restore an old version whose timestamp is newer than the approval.
     */
    fun approval(projectDir: File, path: String): Approval {
        val clean = path.trim('/')
        val target = File(projectDir, clean)
        if (!target.isFile) return Approval.None
        val now = try {
            sha(target.readBytes())
        } catch (e: Throwable) {
            return Approval.None
        }
        val recorded = approved()[clean] ?: return Approval.None
        return when {
            recorded.sha != now -> Approval.Changed(recorded.sha.take(SHOWN), now.take(SHOWN))
            recorded.by == By.AUTO -> Approval.Automatic(recorded.at)
            else -> Approval.Trusted(recorded.at)
        }
    }

    /**
     * Every workflow in [projectDir], with its approval and what it can reach.
     *
     * **One list, both halves.** A screen showing only the approved ones would leave a person
     * wondering why a workflow they can see is missing; showing only the unapproved ones would hide the
     * files that are fine.
     */
    fun survey(projectDir: File): List<Row> {
        val dir = File(projectDir, DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && isWorkflow(it.name) }
            .map { one ->
                val path = one.relativeTo(projectDir).invariantSeparatorsPath
                Row(path, approval(projectDir, path), reach(one))
            }
            .sortedBy { it.path }
            .toList()
    }

    /**
     * What a workflow file names, read from its text.
     *
     * The two questions are answered from the same read, and both are answered **generously**: an
     * environment is any `environment:` key's value (inline or on the next line), and a secret is any
     * mention of the `secrets` context, a `secrets:` key, `github.token`, or the token's own name.
     * That is deliberately broader than the grammar — see [Reach].
     */
    fun reach(workflow: File): Reach {
        val text = try {
            workflow.readText()
        } catch (e: Throwable) {
            // A file that will not open is one whose contents are unknown, and unknown is not safe.
            return Reach(emptyList(), secrets = true)
        }
        return Reach(environmentsOf(text), secretsOf(text))
    }

    private fun environmentsOf(text: String): List<String> {
        val found = LinkedHashSet<String>()
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            // **A comment is not a reference.** `# environment: production` in a note would otherwise
            // make a row claim an environment the run never touches.
            if (trimmed.startsWith("#")) continue
            if (!trimmed.startsWith("environment:")) continue
            val inline = trimmed.removePrefix("environment:").trim()
            if (inline.isNotEmpty()) {
                // `environment: production`, or the older `environment: {name: production}`.
                val name = inline
                    .removePrefix("{").removeSuffix("}")
                    .substringAfter("name:", inline)
                    .trim()
                    .trim('"', '\'')
                    .substringBefore(',').trim()
                if (name.isNotEmpty() && name != "{}") found.add(name)
                continue
            }
            // `environment:` then `name: production` on a following line.
            for (next in lines.drop(index + 1)) {
                val one = next.trim()
                if (one.isEmpty()) continue
                if (!next.startsWith(" ") && !next.startsWith("\t")) break
                if (one.startsWith("name:")) {
                    val name = one.removePrefix("name:").trim().trim('"', '\'')
                    if (name.isNotEmpty()) found.add(name)
                    break
                }
            }
        }
        return found.toList()
    }

    private fun secretsOf(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("secrets.") ||
            lower.contains("secrets:") ||
            lower.contains("github.token") ||
            lower.contains("github_token") ||
            lower.contains("secrets[")
    }

    private fun write(all: Map<String, Approved>): Result<Unit> = try {
        stateDir.mkdirs()
        val array = JSONArray()
        all.values.sortedBy { it.path }.forEach { one ->
            array.put(
                JSONObject().apply {
                    put("path", one.path)
                    put("sha", one.sha)
                    put("at", one.at)
                    put("by", if (one.by == By.AUTO) "auto" else "person")
                },
            )
        }
        file.writeText(array.toString())
        Result.success(Unit)
    } catch (e: Throwable) {
        Result.failure(IllegalStateException("The approval could not be saved: ${e.message}"))
    }

    companion object {
        /** Where approvals are kept, inside the account's own state folder. */
        const val FILE = "workflows.json"

        /** The folder a project's workflows live in, from the project's root. */
        const val DIR = ".github/workflows"

        /** How much of a hash a sentence shows. A whole SHA-256 in prose is noise. */
        const val SHOWN = 8

        /** SHA-256 of [bytes], lower-case hex. */
        fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /**
         * True when [path] is a workflow file — **the only files this has an opinion about.**
         *
         * `.github/` holds more than workflows (issue templates, CODEOWNERS, dependabot config), and
         * none of those are obeyed by a runner in the way a workflow is. Asking a person to read those
         * would be asking them to approve files that cannot run anything.
         */
        fun isWorkflow(path: String): Boolean {
            val clean = path.trim('/')
            return (clean.endsWith(".yml") || clean.endsWith(".yaml"))
        }
    }
}
