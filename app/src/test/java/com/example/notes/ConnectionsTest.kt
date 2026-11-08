package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // ---- what a turn is asked with -------------------------------------------------------

    private val account = ActiveModel(
        model = "deepseek/deepseek-v4.1-flash",
        provider = "deepseek",
        effort = "high",
        contextLength = 163_840,
        maxOutput = 65_536,
    )

    /** The resolution, with the account's key and a vault that answers what a test says. */
    private fun asked(
        project: String? = "acme",
        branch: String? = "feature",
        connections: Map<SecretScope, Connection> = emptyMap(),
        choices: Map<SecretScope, Choice> = emptyMap(),
        key: String? = "sk-the-account-key",
    ): Wired = wiredFor(
        project = project,
        branch = branch,
        account = account,
        connectionAt = { connections[it] },
        choiceAt = { choices[it] },
        accountKey = { key },
        accountKeyState = { if (key == null) KeyState.NONE else KeyState.READABLE },
    )

    @Test
    fun `with nothing stored every worktree is asked as the account`() {
        // **The regression suite for the whole feature.** Nothing in the vault means nothing has
        // changed: the model, the address and the key are the account's own, and the scope says so
        // rather than claiming a branch that was never written.
        val wired = asked()

        assertEquals(SecretScope.Account, wired.at)
        assertEquals(account.model, wired.active.model)
        assertEquals(account.baseUrl, wired.active.baseUrl)
        assertEquals(account.effort, wired.active.effort)
        assertEquals("sk-the-account-key", wired.key())
        assertEquals(KeyState.READABLE, wired.keyState())
        assertTrue("and it is not keyless", !wired.keyless)
    }

    @Test
    fun `a project's pair and its own model are what that project asks with`() {
        // The feature itself: a project pointed at another server, with the model *that* server
        // named. Both come from the same scope, and the account's key is not sent anywhere.
        val wired = asked(
            connections = mapOf(
                SecretScope.Project("acme") to Connection(
                    address = "https://models.example.org/v1",
                    secret = "sk-the-project-key",
                ),
            ),
            choices = mapOf(SecretScope.Project("acme") to Choice("llama3.3:70b", "low")),
        )

        assertEquals(SecretScope.Project("acme"), wired.at)
        assertEquals("llama3.3:70b", wired.active.model)
        assertEquals("https://models.example.org/v1", wired.active.baseUrl)
        assertEquals("low", wired.active.effort)
        assertEquals("sk-the-project-key", wired.key())
        assertEquals(
            "and the word for the address is the host, because nobody named one",
            "models.example.org",
            wired.active.provider,
        )
    }

    @Test
    fun `a branch may choose another model and keep the project's address and key`() {
        // **The half that inherits.** Changing only the model must not mean re-typing a key: the
        // pair is atomic and the choice is not, and this is the case that made the split worth it.
        val project = SecretScope.Project("acme")
        val wired = asked(
            connections = mapOf(
                project to Connection("https://models.example.org/v1", "sk-the-project-key"),
            ),
            choices = mapOf(
                project to Choice("llama3.3:70b", "low"),
                SecretScope.Branch("acme", "feature") to Choice("qwen3:32b", "high"),
            ),
        )

        assertEquals(SecretScope.Project("acme"), wired.at)
        assertEquals("qwen3:32b", wired.active.model)
        assertEquals("high", wired.active.effort)
        assertEquals("https://models.example.org/v1", wired.active.baseUrl)
        assertEquals("sk-the-project-key", wired.key())
    }

    @Test
    fun `a scope that names an address and no model inherits nothing, because it cannot`() {
        // **The cap, at the one place it can do harm.** A model id is a name the server assigns, and
        // `deepseek/deepseek-v4.1-flash` is OpenRouter's name for its model — sending it to somebody
        // else's server asks for something that is not there. A half-made row is therefore not in
        // force: the account's whole answer is, which is the same answer as before the row existed.
        val wired = asked(
            connections = mapOf(
                SecretScope.Project("acme") to Connection("https://models.example.org/v1", "sk-p"),
            ),
        )

        assertEquals(SecretScope.Account, wired.at)
        assertEquals(account.model, wired.active.model)
        assertEquals(account.baseUrl, wired.active.baseUrl)
        assertEquals("sk-the-account-key", wired.key())
    }

    @Test
    fun `a pair with no key is a server that wants none`() {
        // **Not the same fact as a key that is not stored.** A model server on this phone needs no
        // key, and telling its owner to type one is telling them to look for something that does not
        // exist. The request carries no Authorization header at all (see `Door.headers`).
        val wired = asked(
            connections = mapOf(
                SecretScope.Project("acme") to Connection("http://127.0.0.1:11434/v1", ""),
            ),
            choices = mapOf(SecretScope.Project("acme") to Choice("llama3")),
        )

        assertEquals("http://127.0.0.1:11434/v1", wired.active.baseUrl)
        assertTrue("it is keyless", wired.keyless)
        assertNull("and there is no key to send", wired.key())
    }

    @Test
    fun `one server record carries a shape per kind, and a missing kind is the preset`() {
        // **One connection, three requests.** A self-hosted server answers a chat, an embedding and a
        // rerank at three paths with three bodies; a single shape on the row would have had to be the
        // chat's, and a server that speaks all three would have been told how to answer one.
        val ollama = Shape(
            url = "/api/chat",
            body = JSONObject("""{"model":"{{model}}","messages":{{messages}},"stream":{{stream}}}"""),
            kind = ShapeKind.CHAT,
        )
        val connection = Connection(
            address = "http://127.0.0.1:11434",
            secret = "",
            shapes = mapOf(ShapeKind.CHAT to ollama),
        )

        assertEquals("/api/chat", connection.shapeFor(ShapeKind.CHAT).url)
        assertEquals(
            "an embedding is not the chat's shape, and falls back to the app's own",
            Shape.EMBEDDING.url,
            connection.shapeFor(ShapeKind.EMBEDDING).url,
        )
    }

    @Test
    fun `the general scope never answers a chat turn`() {
        // **A general entry is the app-wide embedding key.** Chat is made *as* somebody, so even
        // with a general pair and a general model — a combination the windows cannot make — the
        // answer is the account's rather than the phone's.
        val wired = asked(
            connections = mapOf(
                SecretScope.General to Connection("https://models.example.org/v1", "sk-general"),
            ),
            choices = mapOf(SecretScope.General to Choice("someone-elses-model")),
        )

        assertEquals(SecretScope.Account, wired.at)
        assertEquals(account.model, wired.active.model)
        assertEquals("sk-the-account-key", wired.key())
    }

    @Test
    fun `a branch is asked about before a project, and the account last`() {
        // Order, as one assertion: the narrower pair wins even when the wider one is complete.
        val wired = asked(
            connections = mapOf(
                SecretScope.Branch("acme", "feature") to Connection("https://b.example/v1", "sk-b"),
                SecretScope.Project("acme") to Connection("https://p.example/v1", "sk-p"),
            ),
            choices = mapOf(
                SecretScope.Branch("acme", "feature") to Choice("branch-model"),
                SecretScope.Project("acme") to Choice("project-model"),
            ),
        )

        assertEquals(SecretScope.Branch("acme", "feature"), wired.at)
        assertEquals("branch-model", wired.active.model)
        assertEquals("https://b.example/v1", wired.active.baseUrl)
    }

    @Test
    fun `a turn with no project starts at the account`() {
        // **A conversation before any worktree exists** — the chat box with no project chosen —
        // must not invent a branch scope to look in, and must still be answered.
        val wired = asked(project = null, branch = null)

        assertEquals(SecretScope.Account, wired.at)
        assertEquals(account.model, wired.active.model)
    }

    // ---- the checkmark, and the two names it enables --------------------------------------

    private fun named(
        name: String,
        host: String,
        connections: Map<SecretScope, Connection>,
        project: String? = "acme",
        branch: String? = "feature",
    ): Connection? = namedConnection(
        name = name,
        host = host,
        kind = kindFor(name) ?: ConnKind.LLM,
        project = project,
        branch = branch,
        at = { connections[it] },
    )

    @Test
    fun `a connection is invisible to auth unless the checkmark is on`() {
        // **The default is off, and that is a permission's only safe default.** A git token can push;
        // a model key can spend money. Neither is handed to an agent because a row exists.
        val off = mapOf(
            SecretScope.Project("acme") to Connection("gitlab.com", "glpat", nameable = false),
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
        val on = mapOf(
            SecretScope.Project("acme") to Connection("gitlab.com", "glpat", nameable = true),
        )

        assertNull("another host gets nothing", named("git", "github.com", on))
        assertNull("and neither does an empty one", named("git", "", on))
        assertNull("a look-alike is another host", named("git", "notgitlab.com", on))
        assertNotNull("while the host it was stored for does", named("git", "gitlab.com", on))
    }

    @Test
    fun `the two kinds are two names, and neither answers for the other`() {
        // **Independent checkmarks, because the blast radii differ.** A person may let the agent ask
        // the model server what models exist without letting it push as them — and a name that
        // resolved the other kind's token would be the worst of both.
        val both = mapOf(
            SecretScope.Project("acme") to Connection("gitlab.com", "glpat-git", nameable = true),
            SecretScope.Branch("acme", "feature") to Connection(
                address = "https://models.example/v1",
                secret = "sk-llm",
                nameable = true,
            ),
        )

        assertEquals("git answers for git", "glpat-git", named("git", "gitlab.com", both)?.secret)
        assertEquals("llm answers for llm", "sk-llm", named("llm", "models.example", both)?.secret)
        assertNull("and neither answers on the other's host", named("git", "models.example", both))
        assertNull("a name that is neither kind is nobody's", named("github", "gitlab.com", both))
    }

    @Test
    fun `a nearer scope is asked first, and only if it was granted`() {
        // **The chain applies to a name as well as to a push.** A branch's own checkmarked token wins
        // over the project's; a branch that has one *without* the checkmark does not shadow the
        // project's, because a checkmark that is absent is not a refusal of everything wider — it is
        // the absence of a grant for that row.
        val project = SecretScope.Project("acme")
        val branch = SecretScope.Branch("acme", "feature")
        val grant = mapOf(
            project to Connection("gitlab.com", "glpat-project", nameable = true),
            branch to Connection("gitlab.com", "glpat-branch", nameable = true),
        )

        assertEquals("the branch's", "glpat-branch", named("git", "gitlab.com", grant)?.secret)
        assertEquals(
            "and without the checkmark the project's is still the answer",
            "glpat-project",
            named("git", "gitlab.com", grant + (branch to grant.getValue(branch).copy(nameable = false)))
                ?.secret,
        )
    }
}
