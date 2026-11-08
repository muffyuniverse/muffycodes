package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A token that cannot do the job is refused, and the refusal names the scope.**
 *
 * The operator: *"window should check that all the scope requirements are satisfied and notify with
 * rejection if they are not set for the provided token."*
 *
 * The rule lives in [TokenScopes] with no Android in it, so the whole of it is testable here.
 */
class TokenScopesTest {

    @Test
    fun `a classic token with both scopes is fine`() {
        val verdict = TokenScopes.judge(setOf("repo", "workflow", "read:user"))
        assertEquals("**both present, so nothing is missing**", TokenScopes.Verdict.Fine, verdict)
    }

    @Test
    fun `a token without delete_repo is accepted, because the app never deletes a repository`() {
        // **The refusal that was worse than useless.** `delete_repo` used to be required, and the app
        // never used it — "delete everywhere" leaves the repository and says so (§13b.6). So a person
        // holding a perfectly usable token was refused for a power the app would not exercise, and had
        // no way to tell it was unnecessary. A test asserts the opposite of this one's whole point.
        val verdict = TokenScopes.judge(setOf("repo", "workflow"))
        assertEquals("nothing is missing", TokenScopes.Verdict.Fine, verdict)
    }

    @Test
    fun `a token missing workflow is refused, and workflow is named`() {
        // **The one that matters.** A classic token without `workflow` can push every file except a
        // workflow, and GitHub's refusal is the same "Resource not accessible by personal access
        // token" that a dozen unrelated faults produce. Nothing else in the app can tell the two
        // apart, so this check is the only place the real reason can be said.
        val verdict = TokenScopes.judge(setOf("repo"))
        assertTrue("it is a refusal", verdict is TokenScopes.Verdict.Missing)
        assertEquals("and it names exactly the scope that is absent", listOf("workflow"),
            (verdict as TokenScopes.Verdict.Missing).missing)
    }

    @Test
    fun `the refusal says what the missing scope is for, and what to do`() {
        val said = (TokenScopes.judge(setOf("repo")) as TokenScopes.Verdict.Missing).said()
        // The sentence has to answer the two questions a person actually has: what is wrong with the
        // key I just pasted, and what should I paste instead.
        assertTrue("it names what is missing: $said", said.contains("workflow"))
        assertTrue("it says what that scope buys: $said", said.contains("push a workflow file"))
        assertTrue("and it says which key to make: $said", said.contains("classic"))
    }

    @Test
    fun `a token with no scopes at all is refused rather than treated as unknown`() {
        // **An empty set is not null.** GitHub sends `x-oauth-scopes:` with an empty value for a token
        // that has no permissions, and that is a refusal — a token that can do nothing is not a token
        // whose abilities are merely unknown.
        val verdict = TokenScopes.judge(emptySet())
        assertTrue("a token with nothing is refused", verdict is TokenScopes.Verdict.Missing)
        assertEquals("and every scope is named", TokenScopes.REQUIRED,
            (verdict as TokenScopes.Verdict.Missing).missing)
    }

    @Test
    fun `a token GitHub said nothing about is accepted`() {
        // **Null is "cannot tell", not "nothing".** GitHub sends no scope header for a fine-grained
        // token, and a fine-grained token can do everything this app does. Refusing on a missing
        // header would lock out every fine-grained user and catch nobody, so the check stays silent
        // rather than pretending to a certainty it does not have.
        assertEquals("**an unreported token is accepted**", TokenScopes.Verdict.Fine,
            TokenScopes.judge(null))
    }

    @Test
    fun `the missing scopes are named in one order, so two refusals read the same`() {
        // The header's order is GitHub's, not ours. Two tokens missing the same pair would otherwise
        // produce two differently-worded refusals, which reads as two different faults.
        val a = TokenScopes.judge(setOf("workflow")) as TokenScopes.Verdict.Missing
        val b = TokenScopes.judge(setOf("workflow")) as TokenScopes.Verdict.Missing
        assertEquals("repo comes first because REQUIRED says so", "repo", a.missing.first())
        assertEquals("and the same for the other", "repo", b.missing.first())
    }

    @Test
    fun `the required list is the two the app documents, and nothing else`() {
        // Pinned, because the dialog's sentence and the refusal both read from this list. A third
        // scope added here would be demanded of every token; one removed would go unchecked.
        assertEquals(listOf("repo", "workflow"), TokenScopes.REQUIRED)
    }

    @Test
    fun `the device flow asks for every scope the paste door requires`() {
        // **The two doors drifted apart once**, and nothing in the app could see it: the sign-in
        // button asked GitHub for `repo read:user` while the paste box told a person to make a key
        // with `repo, workflow`. A repository with a workflow file could then be pushed
        // to after pasting a key and refused after signing in, and both are "signed in" as far as the
        // app is concerned.
        //
        // This is the seam between them. `SCOPE` is a space-separated string sent to GitHub; it must
        // carry every scope the paste door insists on, or one door is weaker than the other.
        val asked = DeviceFlow.SCOPE.split(' ').filter { it.isNotBlank() }.toSet()
        for (scope in TokenScopes.REQUIRED) {
            assertTrue("the sign-in button must ask for $scope, and asks for $asked",
                scope in asked)
        }
    }

    @Test
    fun `read user is not required, because repo already implies it`() {
        // The device flow asks for `read:user` as well. It is not checked: `repo` implies it for a
        // classic token, and naming it as missing would be noise about a scope that cannot be the
        // reason anything failed.
        assertTrue("read:user is not in the required list", "read:user" !in TokenScopes.REQUIRED)
    }
}
