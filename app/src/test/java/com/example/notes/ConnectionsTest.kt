package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Where a connection is found, and how far its choice may be inherited.**
 *
 * The operator's correction is the rule under test: the chat model is chosen per account, per project
 * and per branch, in the window that adds the thing and in the row's ⋮ afterwards; a scope opens on
 * what it inherits, and leaving a field alone writes nothing for it.
 *
 * Two facts make that safe, and both are asserted here rather than argued in a comment: **the pair is
 * atomic** (an address and the key for it travel together, because a key is bound to one host) and
 * **the choice stops where the address stopped** (a model id is a name the server assigns, and the
 * names disagree between servers).
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

    @Test
    fun `the narrowest scope that has a connection is the one used`() {
        val at = connectionScope("acme", "feature") { it == SecretScope.Project("acme") }

        assertEquals("the project's, because the branch has none", SecretScope.Project("acme"), at)
        assertNull("and nothing at all when nothing has one", connectionScope("acme", "feature") { false })
    }

    @Test
    fun `a scope may override the model of the connection it inherits`() {
        // **The case that makes scopes worth having**: one server, the cheap model on one project.
        // The project has no address of its own, so the choice may be read at the project and at
        // anything narrower - a branch may even choose differently inside the same project.
        assertEquals(
            "the branch, the project and the account may all say what model to ask for",
            listOf(
                SecretScope.Branch("acme", "feature"),
                SecretScope.Project("acme"),
                SecretScope.Account,
            ),
            choiceScopes("acme", "feature", SecretScope.Account),
        )
        // **And the general scope is not among them, which is the same cap doing a second job.** A
        // general entry is the app-wide embedding key; a chat request is made *as* somebody, so no
        // wider scope than the account may choose its model - even when the account's address was the
        // one inherited, because the account is where the chain ends.
        assertTrue(
            "the general scope is never a chat choice",
            SecretScope.General !in choiceScopes("acme", "feature", SecretScope.Account),
        )
    }

    @Test
    fun `a scope with its own address may not inherit a model from a wider one`() {
        // **The cap, and the reason for it is measured rather than cautious.** A model id is a name
        // the server assigns: `deepseek/deepseek-v4.1-flash` at one address, `deepseek-chat` at
        // another, whatever `--served-model-name` said on somebody's vLLM. A project that points at
        // its own server must therefore name its own model, because no wider scope knows a name that
        // server serves.
        assertEquals(
            "the project's own connection caps the choice at the project",
            listOf(SecretScope.Branch("acme", "feature"), SecretScope.Project("acme")),
            choiceScopes("acme", "feature", SecretScope.Project("acme")),
        )
        assertEquals(
            "a branch that points elsewhere is capped at the branch",
            listOf(SecretScope.Branch("acme", "feature")),
            choiceScopes("acme", "feature", SecretScope.Branch("acme", "feature")),
        )
    }

    @Test
    fun `a cap from outside this question does not empty the list`() {
        // A caller that passes an account scope for a question with no project is asking about the
        // whole chain; answering "nothing may be chosen" would silently refuse every model.
        assertEquals(
            "the chain it does have",
            connectionScopes(null, null),
            choiceScopes(null, null, SecretScope.Project("somewhere")),
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
}
