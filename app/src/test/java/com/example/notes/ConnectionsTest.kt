package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Where a git credential is found, and which host it may be offered to.**
 *
 * One rule, and it is the vault's own boundary: **a key follows its host.** A token is written for the
 * server that issued it, and a scope that names a different host while inheriting an older token would
 * send that token to a machine nobody issued it for. Every other mistake in this app is recoverable —
 * a server refuses a wrong model, a typo'd number produces a refusal — but a token sent to the wrong
 * host cannot be un-sent.
 *
 * **The model half of this file went with `ConnKind.LLM`** (2026-11-10). It tested a chain that
 * resolved a model, an effort and three numbers by walking branch → project → account; a chat's model
 * belongs to the request form it picks now, so `Wired.ofForm` is the only resolver and there is no
 * chain left to test. What survives is the half with the safety property: the scope walk, the host
 * match, and the checkmark that lets an agent name a token.
 */
class ConnectionsTest {

    @Test
    fun `a question is asked narrowest first, and stops at the account`() {
        // A branch only exists inside a project, so a branch with no project is not a place a value
        // can live - it is skipped rather than invented.
        assertEquals(
            "branch, project, account, general",
            listOf(
                SecretScope.Branch("acme", "feature"),
                SecretScope.Project("acme"),
                SecretScope.Account,
                SecretScope.General,
            ),
            connectionScopes("acme", "feature"),
        )
        assertEquals(
            "no branch without a project",
            listOf(SecretScope.Account, SecretScope.General),
            connectionScopes(null, null),
        )
    }

    // ---- which host a key may be offered to ---------------------------------------------

    @Test
    fun `a host covers its subdomains and no look-alike`() {
        // **The check an attacker tries first.** A bare `endsWith` accepts `notgithub.com` for
        // `github.com`; whole labels accept `api.github.com` (which is the case that must work) and
        // refuse the look-alike.
        assertTrue("a subdomain is covered", hostCovers("github.com", "api.github.com"))
        assertTrue("the host itself is covered", hostCovers("github.com", "github.com"))
        assertTrue("and case is not a difference", hostCovers("GitHub.com", "API.github.com"))
        assertFalse("a look-alike is not", hostCovers("github.com", "notgithub.com"))
        assertFalse(
            "nor is another host that merely ends with the same letters",
            hostCovers("hub.com", "github.com"),
        )
        // **An empty host on either side is a match only with itself**, which is how "this secret is
        // not being sent anywhere" is expressed.
        assertTrue("empty is empty", hostCovers("", ""))
        assertFalse("and empty covers no host", hostCovers("", "github.com"))
    }

    @Test
    fun `an address names its host whether it was written as a host or as a base URL`() {
        // **The two kinds store different things on purpose**: a git connection stores the host a
        // token is for, an llm connection stores the base URL a request goes to. Comparisons must not
        // have to know which one they have.
        assertEquals("github.com", hostOfAddress("github.com"))
        assertEquals("openrouter.ai", hostOfAddress("https://openrouter.ai/api/v1"))
        assertEquals("192.168.1.9", hostOfAddress("http://192.168.1.9:8000/v1"))
        assertTrue(
            "and a base URL is matched by the host it names",
            hostCovers(hostOfAddress("https://gitlab.com/api/v4"), "gitlab.com"),
        )
    }

    // ---- the key a row lives under ------------------------------------------------------

    @Test
    fun `every kind of row says its own scope, and nothing else does`() {
        val login = "dev"
        assertEquals(
            "an account secret",
            SecretScope.Account,
            keyScope("secret_dev_linear", login),
        )
        assertEquals(
            "a project secret",
            SecretScope.Project("acme"),
            keyScope("secret_dev_project_acme/linear", login),
        )
        assertEquals(
            "a branch secret",
            SecretScope.Branch("acme", "feature"),
            keyScope("secret_dev_branch_acme/feature/linear", login),
        )
        assertEquals(
            "a general secret, which has no login in its key",
            SecretScope.General,
            keyScope("secret_general_stripe", login),
        )
        assertEquals(
            "a general connection",
            SecretScope.General,
            keyScope("conn_general_llm", login),
        )
        assertEquals(
            "a project connection, and its choice is the same row",
            SecretScope.Project("acme"),
            keyScope("conn_dev_project_acme/llm_choice", login),
        )
        assertEquals(
            "a branch connection",
            SecretScope.Branch("acme", "feature"),
            keyScope("conn_dev_branch_acme/feature/git", login),
        )

        // **What is not a scoped row** must answer null, because the sweeps run over the whole store.
        assertNull("the active login", keyScope("active_login", login))
        assertNull("a token", keyScope("token_dev", login))
        assertNull("a model key", keyScope("model_key_dev", login))
        assertNull("another account's rows", keyScope("secret_other_linear", login))
    }

    @Test
    fun `a project called acme does not own acme2's rows`() {
        // The failure a `startsWith` produces: `secret_dev_project_acme2/x` starts with
        // `secret_dev_project_acme`. Decoding the part and comparing it is the fix, and this is the
        // assertion that keeps it.
        assertEquals(
            "acme2's row is acme2's",
            SecretScope.Project("acme2"),
            keyScope("secret_dev_project_acme2/x", "dev"),
        )
        assertEquals(
            "a login called dev does not own dev2's rows",
            SecretScope.Account,
            keyScope("secret_dev_x", "dev"),
        )
        assertNull("and the other way round is nothing", keyScope("secret_dev2_x", "dev"))
    }

    @Test
    fun `a branch called feature does not own feature-x's rows`() {
        // **Percent-encoding is what makes one branch's prefix safe**, and this is the case that
        // would break a naive prefix match: `feature/x` encodes to `feature%2Fx`, so it is one part
        // and neither branch's key is a prefix of the other's.
        assertEquals(
            "the slash is inside the part",
            SecretScope.Branch("acme", "feature/x"),
            keyScope("secret_dev_branch_acme/feature%2Fx/token", "dev"),
        )
        assertEquals(
            "and the plain branch is its own row",
            SecretScope.Branch("acme", "feature"),
            keyScope("secret_dev_branch_acme/feature/token", "dev"),
        )
    }

    @Test
    fun `a row with too few parts is not a row`() {
        // A half-written key must answer null rather than invent a project called "", because the
        // sweeps delete whatever this function says belongs to a project.
        assertNull(keyScope("secret_dev_project_acme", "dev"))
        assertNull(keyScope("secret_dev_branch_acme/feature", "dev"))
        assertNull(keyScope("conn_dev_project_acme", "dev"))
    }

    @Test
    fun `a branch project and branch are one decoded part each`() {
        // **Encoded on the way in, decoded on the way out** - otherwise the window would show
        // `feature%2Fx` to a person as the branch name, and dropping `feature/x` would be
        // impossible to express.
        assertEquals(
            SecretScope.Branch("my project", "a/b"),
            keyScope("secret_dev_branch_my%20project/a%2Fb/token", "dev"),
        )
        assertTrue(
            "and the general scope needs no login at all",
            keyScope("conn_general_embedding", null) == SecretScope.General,
        )
    }

    // ---- the checkmark, and the two names it enables --------------------------------------

    /**
     * The lookup, **keyed by scope and kind** — because that is what a stored row is: a row's kind is
     * in its key and nowhere else. A fake that ignored the kind would have hidden the bug this class
     * is about, which is exactly what the suite is for.
     */
    private fun named(
        name: String,
        host: String,
        rows: Map<Pair<SecretScope, ConnKind>, Connection>,
        project: String? = "acme",
        branch: String? = "feature",
    ): Connection? = namedConnection(
        name = name,
        host = host,
        kind = kindFor(name) ?: ConnKind.GIT,
        project = project,
        branch = branch,
        at = { scope, kind -> rows[scope to kind] },
    )

    @Test
    fun `a connection is invisible to auth unless the checkmark is on`() {
        // **The default is off, and that is a permission's only safe default.** A git token can push;
        // a model key can spend money. Neither is handed to an agent because a row exists.
        val off = mapOf<Pair<SecretScope, ConnKind>, Connection>(
            (SecretScope.Project("acme") to ConnKind.GIT) to
                Connection("gitlab.com", "glpat", nameable = false),
        )

        assertNull("off means nothing is named", named("git", "gitlab.com", off))
        assertEquals(
            "and on means the token, for that host",
            "glpat",
            named("git", "gitlab.com", off.mapValues { it.value.copy(nameable = true) })?.secret,
        )
    }

    @Test
    fun `a name never chooses the destination`() {
        // **The rule that makes `auth:` an authentication feature rather than an exfiltration
        // primitive.** The agent names the value; the vault decides where it may go. A token stored
        // for gitlab.com is not answered for another host, and not for "nowhere".
        val on = mapOf<Pair<SecretScope, ConnKind>, Connection>(
            (SecretScope.Project("acme") to ConnKind.GIT) to
                Connection("gitlab.com", "glpat", nameable = true),
        )

        assertNull("another host gets nothing", named("git", "github.com", on))
        assertNull("and neither does an empty one", named("git", "", on))
        assertNull("a look-alike is another host", named("git", "notgitlab.com", on))
        assertNotNull("while the host it was stored for does", named("git", "gitlab.com", on))
    }

    @Test
    fun `git is the only name a connection answers to, and it answers only on its own host`() {
        // **`auth: llm` went with `ConnKind.LLM`** (2026-11-10). A model key is a named vault secret
        // (`auth: @openrouter`), so the only credential the app has a *kind* for is git's — and a name
        // that is not it is nobody's, which is what stops a typo from reaching a token.
        val row = mapOf<Pair<SecretScope, ConnKind>, Connection>(
            (SecretScope.Project("acme") to ConnKind.GIT) to
                Connection("gitlab.com", "glpat-git", nameable = true),
        )

        assertEquals("git answers for git", "glpat-git", named("git", "gitlab.com", row)?.secret)
        assertNull("and on no other host", named("git", "models.example", row))
        assertNull("llm is not a name any more", named("llm", "gitlab.com", row))
        assertNull("nor is anything else", named("github", "gitlab.com", row))
    }

    @Test
    fun `a nearer scope is asked first, and only if it was granted`() {
        // **The chain applies to a name as well as to a push.** A branch's own checkmarked token wins
        // over the project's; a branch that has one *without* the checkmark does not shadow the
        // project's, because a checkmark that is absent is not a refusal of everything wider — it is
        // the absence of a grant for that row.
        val project = SecretScope.Project("acme")
        val branch = SecretScope.Branch("acme", "feature")
        val projectRow = project to ConnKind.GIT
        val branchRow = branch to ConnKind.GIT
        val grant = mapOf<Pair<SecretScope, ConnKind>, Connection>(
            projectRow to Connection("gitlab.com", "glpat-project", nameable = true),
            branchRow to Connection("gitlab.com", "glpat-branch", nameable = true),
        )

        assertEquals("the branch's", "glpat-branch", named("git", "gitlab.com", grant)?.secret)
        assertEquals(
            "and without the checkmark the project's is still the answer",
            "glpat-project",
            named(
                "git",
                "gitlab.com",
                grant + (branchRow to grant.getValue(branchRow).copy(nameable = false)),
            )?.secret,
        )
    }
}
