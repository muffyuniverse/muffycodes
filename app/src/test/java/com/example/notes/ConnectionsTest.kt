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
    fun `each field is inherited on its own, so one scope can change one thing`() {
        // **The operator's rule** (2026-11-09): an empty field is the inheritance and a written one
        // overwrites it — *"this way user will be able to change only what he needs and keep the rest
        // of the fields inherited"*. The branch writes a model and nothing else, so its address, key,
        // effort and numbers all come from the scopes above it.
        val wired = asked(
            connections = mapOf(
                SecretScope.Project("acme") to Connection(
                    address = "https://models.example.org/v1",
                    secret = "sk-the-project-key",
                ),
            ),
            choices = mapOf(
                SecretScope.Project("acme") to Choice(model = "llama3.3:70b", effort = "low"),
                SecretScope.Branch("acme", "feature") to Choice(model = "qwen3:32b"),
            ),
        )

        assertEquals("the branch's model", "qwen3:32b", wired.active.model)
        assertEquals("the project's effort", "low", wired.active.effort)
        assertEquals("the project's address", "https://models.example.org/v1", wired.active.baseUrl)
        assertEquals("and the project's key", "sk-the-project-key", wired.key())
    }

    @Test
    fun `an address with an inherited model is sent, and the server refuses it`() {
        // **The cap is gone.** A model id is a name the server assigns, so pointing a project at
        // another server while inheriting OpenRouter's model name is a request that server refuses —
        // naming the model it does not have. That is a better answer than a window that would not
        // close, and it is the same feedback every number in this app now gets.
        val wired = asked(
            connections = mapOf(
                SecretScope.Project("acme") to Connection("https://models.example.org/v1", "sk-p"),
            ),
        )

        assertEquals("the project's address", "https://models.example.org/v1", wired.active.baseUrl)
        assertEquals("and the account's model, as written", account.model, wired.active.model)
        assertEquals("with the project's own key", "sk-p", wired.key())
        assertNull("and nothing to complain about", wired.keyProblem)
    }

    @Test
    fun `the numbers are inherited one at a time, down to the general settings`() {
        // **A 32k model and a million-token model in one account**: the sizes belong to the model, so
        // they are chosen where the model is chosen. Each of the three is resolved on its own, and the
        // general settings are the root — the value a scope with no row of its own inherits.
        val general = ContextBudget.Limits.of(trigger = 100_000, hard = 250_000, target = 60_000)
        val wired = asked(
            general = general,
            choices = mapOf(
                // The project changes the clear-up point and nothing else.
                SecretScope.Project("acme") to Choice(forget = 40_000),
                // The branch changes only the trim.
                SecretScope.Branch("acme", "feature") to Choice(trim = 0),
            ),
        )

        assertEquals("the project's clear-up point", 40_000L, wired.limits.triggerAt)
        assertEquals("the branch's trim, which is off", 0L, wired.limits.hardAt)
        assertEquals("and the general target", 60_000L, wired.limits.targetAt)
    }

    @Test
    fun `a key written for one host is not offered to another`() {
        // **The one composition the resolver still refuses, and the only one that cannot be undone by
        // a refusal.** The project names a server of its own and inherits the account's key, which was
        // made for openrouter.ai. Sending it would hand that key to a machine nobody issued it for —
        // every other mistake here produces an error from a server, and this one is already spent by
        // the time anybody could read the error.
        val wired = asked(
            connections = mapOf(
                SecretScope.Project("acme") to Connection("https://elsewhere.example/v1"),
            ),
        )

        assertEquals("the address is the project's", "https://elsewhere.example/v1", wired.active.baseUrl)
        assertNull("but the account's key is not offered", wired.key())
        assertNotNull("and the refusal is a sentence", wired.keyProblem)
        assertTrue(
            "naming both hosts: ${wired.keyProblem}",
            wired.keyProblem!!.contains("elsewhere.example") &&
                wired.keyProblem!!.contains("openrouter.ai"),
        )
    }

    @Test
    fun `a key and an address in the same row are for the same host by construction`() {
        // The ordinary case, asserted so the refusal above cannot spread: a row that writes both is
        // coherent, whatever the row above it says.
        val wired = asked(
            connections = mapOf(
                SecretScope.Project("acme") to Connection(
                    address = "https://elsewhere.example/v1",
                    secret = "sk-elsewhere",
                ),
            ),
        )

        assertEquals("sk-elsewhere", wired.key())
        assertNull(wired.keyProblem)
    }

    @Test
    fun `a branch that moves elsewhere must bring its own key`() {
        // **The key follows the host it was made for, not the turn's own address.** The project holds a
        // key for openrouter.ai and no address; the branch points somewhere else. The branch is the
        // scope that changed the server, so the branch is the scope that has to supply a key — the
        // project's is refused rather than quietly sent to the new host.
        val wired = asked(
            connections = mapOf(
                SecretScope.Project("acme") to Connection(secret = "sk-openrouter"),
                SecretScope.Branch("acme", "feature") to Connection("https://elsewhere.example/v1"),
            ),
        )

        assertEquals("the branch's address", "https://elsewhere.example/v1", wired.active.baseUrl)
        assertNull("the project's key is for another host", wired.key())
        assertNotNull("and the sentence says so", wired.keyProblem)
    }

    @Test
    fun `a branch may move to another host when it writes that host's key too`() {
        val wired = asked(
            connections = mapOf(
                SecretScope.Project("acme") to Connection(secret = "sk-openrouter"),
                SecretScope.Branch("acme", "feature") to Connection(
                    address = "https://elsewhere.example/v1",
                    secret = "sk-elsewhere",
                ),
            ),
        )

        assertEquals("sk-elsewhere", wired.key())
        assertNull(wired.keyProblem)
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
        effort = "high",
        baseUrl = "https://openrouter.ai/api/v1",
    )

    /** The resolution, with the account's key and a vault that answers what a test says. */
    private fun asked(
        project: String? = "acme",
        branch: String? = "feature",
        connections: Map<SecretScope, Connection> = emptyMap(),
        choices: Map<SecretScope, Choice> = emptyMap(),
        key: String? = "sk-the-account-key",
        general: ContextBudget.Limits = ContextBudget.Limits.of(trigger = 0, hard = 0),
    ): Wired = wiredFor(
        project = project,
        branch = branch,
        account = account,
        general = general,
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
        // **The address is the upstream**, and `providerOf` is the one place a sentence gets a word for
        // it — so the assertion that used to read `active.provider` reads the host the address names.
        assertEquals(
            "and the word for the address is the host",
            "models.example.org",
            providerOf(wired.active.baseUrl),
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
    fun `a pair with no key is a server that wants none`() {
        // **Not the same fact as a key that is not stored.** A model server on this phone needs no
        // key, and telling its owner to type one is telling them to look for something that does not
        // exist. The request carries no Authorization header at all (see `Door.headers`).
        // **"Wants no key" is a statement somebody made**, because a blank key now means "inherit the
        // one above" — so the row says so with `keyless`, and an unticked box says nothing at all.
        val wired = asked(
            connections = mapOf(
                SecretScope.Project("acme") to Connection(
                    address = "http://127.0.0.1:11434/v1",
                    keyless = true,
                ),
            ),
            choices = mapOf(SecretScope.Project("acme") to Choice("llama3")),
        )

        assertEquals("http://127.0.0.1:11434/v1", wired.active.baseUrl)
        assertTrue("it is keyless", wired.keyless)
        assertNull("and there is no key to send", wired.key())
    }

    @Test
    fun `a connection answers with the shape it stored, and falls back to the preset`() {
        // **One kind is left, and both halves still matter.** A connection stored by another build may
        // carry no shape for the chat turn, and a row that answered null would be a request that cannot
        // be sent; the preset is what a compatible server needs, and it is the app's own fallback.
        val ollama = Shape(
            url = "/api/chat",
            // **A template, not a document**: a placeholder is a whole value, so the field that
            // carries one holds the quoted placeholder and the app puts an object there.
            body = JSONObject("""{"model":"{{model}}","messages":"{{messages}}"}"""),
            kind = ShapeKind.CHAT,
        )
        val stored = Connection(
            address = "http://127.0.0.1:11434",
            secret = "",
            shapes = mapOf(ShapeKind.CHAT to ollama),
        )
        assertEquals("the shape it stored", "/api/chat", stored.shapeFor(ShapeKind.CHAT).url)

        val bare = Connection(address = "http://127.0.0.1:11434", secret = "", shapes = emptyMap())
        assertEquals(
            "and a row with no shape answers the app's preset",
            Shape.CHAT.url,
            bare.shapeFor(ShapeKind.CHAT).url,
        )
    }

    @Test
    fun `the general scope never answers a chat turn`() {
        // **A chat request is made *as* somebody.** Even with a general pair and a general model — a
        // combination the windows cannot make — the answer is the account's rather than the phone's.
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
        kind = kindFor(name) ?: ConnKind.LLM,
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
    fun `the two kinds are two names, and neither answers for the other`() {
        // **Independent checkmarks, because the blast radii differ.** A person may let the agent ask
        // the model server what models exist without letting it push as them — and a name that
        // resolved the other kind's token would be the worst of both.
        val both = mapOf<Pair<SecretScope, ConnKind>, Connection>(
            (SecretScope.Project("acme") to ConnKind.GIT) to
                Connection("gitlab.com", "glpat-git", nameable = true),
            (SecretScope.Branch("acme", "feature") to ConnKind.LLM) to Connection(
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

    @Test
    fun `the account may hold a row, and its records are the floor rather than a scope`() {
        // **One rule with three stopping points.** The account is asked like any other scope; a row
        // there is a person saying "not the account's own pair", and its own records — the model key
        // and the address in `model_conf.json` — are what answers when there is none. A special case
        // for the account would have been a second rule to keep in step with this one.
        val stored = mapOf<SecretScope, Connection>(
            SecretScope.Account to Connection("https://elsewhere.example/v1", "sk-other"),
        )
        val chosen = mapOf<SecretScope, Choice>(SecretScope.Account to Choice("their-model"))

        val wired = asked(connections = stored, choices = chosen)

        assertEquals(SecretScope.Account, wired.at)
        assertEquals("the account's own row wins over its records", "their-model", wired.active.model)
        assertEquals("https://elsewhere.example/v1", wired.active.baseUrl)
        // **The key is the row's, not the account's**, which is what "a pair travels together" means
        // in a place a person can see it.
        assertEquals("sk-other", wired.key())
    }

    @Test
    fun `a row at the account is used like any other, and its key is its own`() {
        // **The account is a scope, not a special case.** A row there says "not the account's own
        // records" — and it is read by the same walk that reads a project's or a branch's. This used
        // to be the completeness rule's last stand: a row with an address and no model was "not in
        // force" and the account's records answered instead, which is a second rule for the same
        // question.
        val wired = asked(
            connections = mapOf<SecretScope, Connection>(
                SecretScope.Account to Connection("https://elsewhere.example/v1", "sk-other"),
            ),
        )

        assertEquals("the row's address", "https://elsewhere.example/v1", wired.active.baseUrl)
        assertEquals("and the model it inherits from the account's records", account.model, wired.active.model)
        assertEquals("with the row's own key", "sk-other", wired.key())
        assertEquals("and the scope it came from", SecretScope.Account, wired.at)
    }
}
