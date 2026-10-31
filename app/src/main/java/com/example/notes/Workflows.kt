package com.example.notes

import java.io.File

/**
 * What a workflow file can reach, and whether a person has put their hands on it.
 *
 * ## The design, and why it is not a hash store
 *
 * The operator's ruling, in their words: *"only approved workflows can be executed with secrets
 * access. unapproved workflows still can be executed but without secrets."* And then, sharper:
 * *"locked file = approved and sealed file"*, and *"can dispatch script check if workflow defines
 * environment and reject if it does so and isnt locked?"*
 *
 * So there are two facts, and both already have machinery:
 *
 * 1. **Does the file declare an environment?** That is what decides whether a run can read a secret,
 *    and it is declared *in the file* — GitHub's rule: *"Secrets stored in an environment are only
 *    available to workflow jobs that reference the environment."* No expansion, no dispatch flag, no
 *    juggling between scopes: a workflow that names no environment reaches no environment secret.
 * 2. **Is the file locked?** A lock the person set means the agent may not write it
 *    ([Workspace.isAgentLocked]) — so a locked file's bytes are the bytes the person left there. That
 *    is what "approved" means, and it is enforced by the funnel rather than promised by a prompt.
 *
 * **There is no hash and no approval file.** An earlier version of this class kept a SHA-256 per file,
 * which was a second store answering a question the lock already answers, and a second place to be
 * wrong. The lock is the approval.
 *
 * ## What this class does, and what it does not
 *
 * It **reads** and it **reports**. The refusing is [AgentTools]' business, at dispatch, because that is
 * the one moment the app controls — and the class comment there says plainly what the check does and
 * does not protect against.
 *
 * ## Why the scan errs toward "it can reach something"
 *
 * The environment name can be an **expression** — GitHub's own documented example is
 * `name: ${{ github.ref_name }}` — so no parser can resolve every case. A file that mentions
 * `environment:` in any form therefore counts as declaring one, including a form this scan cannot read.
 * A false "it names an environment" costs a person one glance at the file; a false "it names none" is
 * the mistake that matters, because it would let an unreviewed workflow read a secret.
 */
class Workflows {

    /**
     * What a workflow file can reach, read from its text.
     *
     * **A report, never a guarantee.** [secrets] being true means the file *mentions* a secret, not
     * that it can read one — and [secrets] being false means only that this scan found no mention,
     * which a determined file could arrange while still reading one.
     */
    data class Reach(
        /** Environment names the file references, in the order they appear. */
        val environments: List<String>,
        /**
         * True when the text names an `environment:` key at all, **even one this scan cannot read**.
         *
         * This is the flag the dispatch check uses, and it is deliberately not the same question as
         * [environments].isNotEmpty(): a file whose environment is an expression has no resolvable
         * name, and answering "no environments" for it would be the dangerous direction.
         */
        val declaresEnvironment: Boolean,
        /** True when the text mentions a secret, a token, or inherits secrets. */
        val secrets: Boolean,
    ) {
        /** True when this workflow's runs may read an environment secret. */
        val canReachSecrets: Boolean get() = declaresEnvironment || secrets
    }

    /**
     * What a project's secrets look like from the outside, and the one finding that matters.
     *
     * **A repository-level secret is the hole the whole design cannot close.** Every workflow in a
     * repository reads every repository secret, including one the agent wrote and started — so a single
     * one at this scope makes the environment rule decorative. The app cannot move it; only the person
     * can. So the honest thing is to name it, in the place a person is looking when they set secrets up.
     */
    data class Secrets(
        /** Names of the secrets at repository scope. **Any of these is a finding.** */
        val atRepository: List<String>,
        /** Names of the secrets in the environment this app writes to. */
        val inEnvironment: List<String>,
        /** The environment's name, for the sentence. */
        val environment: String,
    ) {
        /** True when nothing is wrong and nothing is missing. */
        val settled: Boolean get() = atRepository.isEmpty() && inEnvironment.isNotEmpty()

        /**
         * One sentence, or null when there is nothing to say.
         *
         * **Silence is a real answer**: a person who has done this right should not be told about it
         * every time they open a project.
         */
        fun said(): String? {
            if (atRepository.isNotEmpty()) {
                val names = atRepository.take(3).joinToString(", ")
                val rest = if (atRepository.size > 3) " and ${atRepository.size - 3} more" else ""
                return "This repository has ${atRepository.size} secret" +
                    (if (atRepository.size == 1) "" else "s") +
                    " at repository scope ($names$rest). Every workflow in the repository can read " +
                    "them, including one the agent wrote — so they should be moved to the " +
                    "`$environment` environment."
            }
            if (inEnvironment.isEmpty()) {
                return "Nothing is in the `$environment` environment, so this project's builds " +
                    "cannot sign anything. Send the signing key to fix it."
            }
            return null
        }
    }

    /** One workflow in a project, as a list row needs it. */
    data class Row(
        val path: String,
        /** True when the person has locked this file, so the agent cannot have changed it. */
        val locked: Boolean,
        val reach: Reach,
    ) {
        /** The file's own name, for a row that already sits inside `.github/workflows/`. */
        val name: String get() = path.substringAfterLast('/')

        /**
         * True when this file could be started with secrets in reach but nobody has sealed it.
         *
         * **The one state worth drawing in red**, because it is the one the operator named: a workflow
         * the agent can still rewrite, which can read a secret.
         */
        val exposed: Boolean get() = reach.canReachSecrets && !locked
    }

    /**
     * Every workflow in [projectDir], with its lock state and what it can reach.
     *
     * **One list, both halves.** A screen showing only the locked ones would leave a person wondering
     * why a workflow they can see is missing; showing only the unlocked ones would hide the files that
     * are fine.
     */
    fun survey(projectDir: File, locked: Set<String>): List<Row> {
        // **The project's own name is the first segment of a lock path**, because `Workspace` keeps
        // them account-relative. Taking it from the folder rather than from a parameter means a
        // caller cannot pass one that disagrees with where the files actually are.
        val project = projectDir.name
        val dir = File(projectDir, DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && isWorkflow(it.name) }
            .map { one ->
                val path = one.relativeTo(projectDir).invariantSeparatorsPath
                Row(path, isLocked(path, locked, project), reach(one))
            }
            .sortedBy { it.path }
            .toList()
    }

    /**
     * Whether [path] is held by the person's lock, **or sits inside a locked folder.**
     *
     * **The paths are not the same shape, and that is the trap.** `Workspace` keeps lock paths
     * *account-relative* — `relative()` is against the account folder — so a workflow at
     * `<account>/muffycodes/.github/workflows/deploy.yml` is locked as
     * `muffycodes/.github/workflows/deploy.yml`, while everything this class reports is relative to
     * the project. Comparing them directly would never match, and the check would silently never
     * fire — the worst failure a security check can have.
     *
     * So [project] is prepended before the comparison. The inheritance rule itself is `Workspace`'s,
     * repeated here rather than asked of it because a row drawn by Compose must read the state it is
     * watching rather than re-read a file, or a tap changes nothing the row can see.
     */
    fun isLocked(path: String, locked: Set<String>, project: String): Boolean {
        val clean = path.trim('/')
        if (locked.isEmpty()) return false
        val full = if (project.isBlank()) clean else "$project/$clean"
        if (full in locked) return true
        return locked.any { it.isNotEmpty() && full.startsWith("$it/") }
    }

    /**
     * What a workflow file names, read from its text.
     *
     * The questions are answered from one read, and all of them are answered **generously**: an
     * environment is any `environment:` key at all, and a secret is any mention of the `secrets`
     * context, a `secrets:` key, `github.token`, or the token's own name.
     */
    fun reach(workflow: File): Reach {
        val text = try {
            workflow.readText()
        } catch (e: Throwable) {
            // **A file that will not open is one whose contents are unknown, and unknown is not
            // safe.** Reporting "reaches nothing" for it would be the report lying in the direction
            // that matters.
            return Reach(emptyList(), declaresEnvironment = true, secrets = true)
        }
        return Reach(environmentsOf(text), declaresEnvironment(text), secretsOf(text))
    }

    /**
     * True when the file names an `environment:` key **in any form, at any indent.**
     *
     * Deliberately a plain text test rather than a YAML parse. A parser would have to be given the
     * grammar, and the grammar allows an expression for the name — `environment: {name: "${{ … }}"}`
     * is a legal declaration of an environment this scan cannot name. So the question asked here is
     * only "does this file mention one", and [environmentsOf] answers the stronger question
     * separately, for the sentence a person reads.
     */
    private fun declaresEnvironment(text: String): Boolean =
        text.lines().any { line ->
            val trimmed = line.trim()
            // **A comment is not a reference.** A note saying "environment: production" would
            // otherwise make a row claim protection the run never touches.
            !trimmed.startsWith("#") && (
                trimmed.startsWith("environment:") ||
                    // `environment :` is legal YAML, and a scan that missed it would be the wrong
                    // kind of wrong.
                    trimmed.matches(Regex("""environment\s*:.*"""))
                )
        }

    private fun environmentsOf(text: String): List<String> {
        val found = LinkedHashSet<String>()
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue
            if (!trimmed.matches(Regex("""environment\s*:.*"""))) continue
            val inline = trimmed.substringAfter(":").trim()
            if (inline.isNotEmpty()) {
                // `environment: production`, or the older `environment: {name: production}`.
                val name = inline
                    .removePrefix("{").removeSuffix("}")
                    .substringAfter("name:", inline)
                    .trim()
                    .trim('"', '\'')
                    .substringBefore(',').trim()
                // **An expression is not a name.** `${{ github.ref_name }}` is a declaration whose
                // value this scan cannot know, so it is reported as the declaration without a name
                // rather than as a name that happens to look like code.
                if (name.isNotEmpty() && name != "{}" && !name.startsWith("\$")) found.add(name)
                continue
            }
            // `environment:` then `name: production` on a following line.
            for (next in lines.drop(index + 1)) {
                val one = next.trim()
                if (one.isEmpty()) continue
                if (!next.startsWith(" ") && !next.startsWith("\t")) break
                if (one.startsWith("name:")) {
                    val name = one.removePrefix("name:").trim().trim('"', '\'')
                    if (name.isNotEmpty() && !name.startsWith("\$")) found.add(name)
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

    companion object {
        /** The folder a project's workflows live in, from the project's root. */
        const val DIR = ".github/workflows"

        /**
         * True when [path] is a workflow file — **the only files this has an opinion about.**
         *
         * `.github/` holds more than workflows (issue templates, CODEOWNERS, dependabot config), and
         * none of those are obeyed by a runner in the way a workflow is. Asking a person to lock those
         * would be asking them to seal files that cannot run anything.
         */
        fun isWorkflow(path: String): Boolean {
            val clean = path.trim('/')
            return clean.endsWith(".yml") || clean.endsWith(".yaml")
        }
    }
}
