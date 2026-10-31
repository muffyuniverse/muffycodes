package com.example.notes

/**
 * Publishing a release: the one thing the agent may never do, and the person may do in two presses.
 *
 * ## Why this is separate from the `ci` tool
 *
 * `AgentTools`' `ci` dispatches on `AGENT_BRANCH` and **refuses any other branch**, because `main`
 * publishes. This class is the other lane: it dispatches on [BRANCH], and the only way to reach it is a
 * button in the app that a person pressed.
 *
 * The separation is the design rather than an inconvenience. A single `dispatch(branch)` helper shared
 * by both would be one refactor away from the agent reaching `main`, and the rule would then be a
 * convention in a call site rather than a constant in a class the agent cannot see.
 *
 * ## Why no reviewer
 *
 * The threat was the agent publishing, and the mechanism against it is a **deployment branch policy**:
 * the `release` environment may only be used by `main` and `development`, and the agent only ever
 * touches `development`. So an agent that somehow dispatched on `main` would find the environment — and
 * therefore the signing key — unreachable, and the release would fail rather than ship.
 *
 * That makes a human approval redundant: it would ask a person to confirm a decision the branch rule
 * has already made, on every release, forever.
 */
object Release {

    /**
     * The branch a release is published from.
     *
     * **`main`, and this constant is the only place the app says so.** The agent's own constant is
     * [AgentTools.AGENT_BRANCH] and the two are deliberately not derived from one another.
     */
    const val BRANCH = "main"

    /**
     * The workflow that builds and publishes.
     *
     * A **file name**, not a workflow id: the dispatch endpoint takes `workflow_id`, and GitHub accepts
     * either the numeric id or the file name. The file name is the one a person can read and the one
     * that survives a workflow being deleted and remade, which the id does not.
     */
    const val WORKFLOW = "build-and-release.yml"

    /** What a dispatch did, in words a screen can show. */
    data class Report(val versionName: String, val branch: String, val workflow: String) {
        fun said(): String =
            "Started the release of $versionName from $branch. It takes about ten minutes; " +
                "the release appears on GitHub when it passes."
    }

    /**
     * Starts a release of [versionName].
     *
     * **The version name is checked here rather than left to the workflow.** The workflow checks it too,
     * and would reject a bad one — but it would reject it *after* the run had started, on a branch that
     * publishes, which spends a full pipeline to discover something a regular expression can answer. The
     * workflow's own check stays: it is the one that protects a dispatch from github.com.
     */
    fun start(
        github: GitHub,
        token: String,
        ref: RepoRef,
        versionName: String,
        devBuild: Boolean = false,
    ): Result<Report> {
        val name = versionName.trim()
        val problem = nameProblem(name)
        if (problem != null) return Result.failure(IllegalStateException(problem))
        val inputs = buildMap {
            put("version_name", name)
            // **Sent as a string, because a dispatch input is one**, and because a boolean `false` in
            // this map would arrive at GitHub as the JSON literal `false` rather than the string the
            // workflow's `type: boolean` input expects.
            if (devBuild) put("dev_build", "true")
        }
        val started = github.dispatch(token, ref, WORKFLOW, BRANCH, inputs)
        if (started.error != null) {
            return Result.failure(
                IllegalStateException("The release could not be started: ${started.error}"),
            )
        }
        return Result.success(Report(name, BRANCH, WORKFLOW))
    }

    /**
     * Why [name] cannot be a version name, or null when it can.
     *
     * **The same rule the workflow applies**, so the answer arrives before a pipeline is spent rather
     * than ten seconds into one. Letters, digits, dots and dashes: the name becomes a git tag and a
     * release title, and anything else is either unsafe in a shell word or refused by git.
     */
    fun nameProblem(name: String): String? {
        if (name.isEmpty()) return "Say what version this release is, for example 1.5"
        val bad = name.firstOrNull { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }
        if (bad != null) {
            return "A version name may only hold letters, digits, dots and dashes, and " +
                "\"$name\" has \"$bad\" in it"
        }
        return null
    }
}
