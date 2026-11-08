package com.example.notes

/**
 * Publishing a release: the one thing the agent may never do, and the person may do in two presses.
 *
 * ## Why this is separate from the `ci` tool
 *
 * `AgentTools`' `ci` dispatches on [AgentTools.BRANCH] and **refuses any other branch**, because `main`
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
     * **`main`, and this constant is the only place the app says so.** [AgentTools.BRANCH] says the
     * same word for the same reason; they are deliberately not derived from one another, because a
     * release branch and the branch a project happens to have are two different claims that must be
     * able to disagree. Today they agree.
     */
    const val BRANCH = "main"

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
