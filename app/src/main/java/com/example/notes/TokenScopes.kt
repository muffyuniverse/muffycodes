package com.example.notes

/**
 * **What a token must be allowed to do, and what to say when it is not.**
 *
 * The operator's report: *"window should check that all the scope requirements are satisfied and notify
 * with rejection if they are not set for the provided token."*
 *
 * ## Why this is a separate file with no Android in it
 *
 * The rule is a decision about strings, and it is the part that can be wrong. Keeping it away from the
 * dialog means the answer can be tested with three sets and no device — and it means the dialog's job
 * shrinks to showing whatever this says.
 *
 * ## The two scopes, and why each one
 *
 * - **`repo`** — read and write the repository. Nothing works without it, so its absence is fatal.
 * - **`workflow`** — push a file under `.github/workflows/`. A classic token without it can push every
 *   other file in the repository and is refused *only* on those, with the same "Resource not
 *   accessible by personal access token" that a dozen unrelated faults produce. That is why it has to
 *   be checked rather than discovered.
 *
 * **`delete_repo` used to be a third, and it is gone** (§13b.6). It was the one irreversible
 * capability in the set, and the app never used it: "delete everywhere" leaves the repository and says
 * so. A token without it was refused for a power the app would not exercise — the worst kind of
 * refusal, because the person cannot tell it is unnecessary.
 *
 * `read:user` is asked for as well but is **not checked**: it is implied by `repo` for a classic token,
 * and naming it as missing would be noise about a scope that cannot be the reason anything failed.
 */
object TokenScopes {

    /** The scopes a pasted token must carry. Order is the order they are named in a refusal. */
    val REQUIRED = listOf("repo", "workflow")

    /** What each is for, in the words a refusal and the paste screen both use. */
    private val WHY = mapOf(
        "repo" to "read and write your repositories",
        "workflow" to "push a workflow file",
    )

    /**
     * What [scope] buys, in a few words.
     *
     * **One source for both places it is said** — the paste screen's checklist and a refusal's
     * sentence. Typed twice, the screen could promise a scope the refusal did not mention, and the
     * person would have no way to tell which was true.
     */
    fun why(scope: String): String = WHY[scope] ?: "required by this app"

    /**
     * The verdict on a token.
     *
     * Three answers rather than a boolean, because "this token is missing `workflow`" and "GitHub did
     * not say what this token can do" are different facts and only one of them is a refusal.
     */
    sealed interface Verdict {
        /** Good, or unknowable. Either way the token is accepted. */
        data object Fine : Verdict

        /**
         * A classic token that lacks one or more of [REQUIRED].
         *
         * [missing] is in [REQUIRED]'s order, so two refusals of the same token read the same way.
         */
        data class Missing(val missing: List<String>) : Verdict {
            /** The sentence the dialog shows. */
            fun said(): String {
                val names = missing.joinToString(", ")
                val what = missing.map { why(it) }.joinToString(", ")
                return "This key is missing $names, so it cannot $what. " +
                    "Make a classic key with all of " + REQUIRED.joinToString(", ") + "."
            }
        }
    }

    /**
     * Judges [scopes], which is `null` when GitHub did not report any.
     *
     * **Null is accepted.** GitHub sends no `x-oauth-scopes` header for a fine-grained token, and a
     * fine-grained token can do everything this app does — it simply names its permissions differently
     * and cannot be judged by this rule. Refusing on a missing header would lock out every fine-grained
     * user to catch nobody. The app says what it cannot check rather than pretending either way.
     *
     * An **empty** set is different, and is a refusal: it means the header was there and listed
     * nothing, which is a token with no permissions at all.
     */
    fun judge(scopes: Set<String>?): Verdict {
        if (scopes == null) return Verdict.Fine
        val missing = REQUIRED.filter { it !in scopes }
        return if (missing.isEmpty()) Verdict.Fine else Verdict.Missing(missing)
    }
}
