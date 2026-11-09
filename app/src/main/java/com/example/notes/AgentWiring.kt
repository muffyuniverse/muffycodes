package com.example.notes

import android.app.Application
import java.io.File

/**
 * The one way to build an [AgentRunner] from what is on the phone.
 *
 * **Two callers need this: the screen and the foreground service.** They differ in the seams a
 * test needs (credentials, settings, the door) and in nothing else — which workspace, which
 * repository, which model, which door when nothing is injected. Writing that twice would mean two
 * answers to "which account is the agent working for", and the wrong one would poll somebody
 * else's project.
 *
 * A runner cannot be built without an account and a repository, so the answer is nullable rather
 * than a runner over a folder that does not exist.
 */
object AgentWiring {

    /** The runner for whoever is signed in on this phone, or null when nobody is. */
    fun runnerFor(
        app: Application,
        rootDir: File = app.filesDir,
        credentials: Credentials = CredentialStore(app),
        settings: SettingsStore = SettingsStore(app),
        door: (Wired, String) -> Door = { wired, project ->
            OpenRouterDoor(
                // **The key that belongs to the address the turn goes to**, which is the scoped
                // connection's when one answered and the account's when none did. Read through the
                // resolution rather than here, because "which key" and "which address" are one
                // decision and two lambdas would let them disagree.
                key = wired.key,
                // **The turn's own address**, read per request: a person who moves to another
                // compatible server expects the next poll to go there.
                baseUrl = wired.active.baseUrl,
                keyState = wired.keyState,
                // **The one refusal the resolver makes**, in the same door as the key it is about.
                keyProblem = { wired.keyProblem },
                keyless = wired.keyless,
                // **The server's own request shape**, when the connection recorded one. A self-hosted
                // server that answers at another path, or in another dialect, is asked in its own
                // words rather than in the app's — and the window that stores a shape and the door
                // that sends it read the same object.
                shape = wired.shape,
                model = wired.active.model,
                // **The host, because the address is the upstream.** The tag that used to sit here was
                // OpenRouter's routing data; this is the word a person recognises in the log.
                provider = providerOf(wired.active.baseUrl),
                effort = wired.active.effort,
                // **The worktree the turn belongs to**, so the request log answers "which project
                // spent this" rather than filing every row under an empty name.
                project = project,
                // **The same connection timeout as a streamed turn**, because it answers the
                // same question: nothing has come back yet. It used to have a number of its own
                // that no screen could set, which made it in force and invisible at once.
                timeoutMs = settings.responseMs,
                responseMs = settings.responseMs,
                // **The service's door writes the log too.** The runner built here is the one that
                // runs with the app in the background, so a door without this would log only the
                // turns somebody was watching - which is the minority of the requests and none of
                // the keepalives.
                onStats = { stats ->
                    // Asked here rather than captured: this lambda is a *default parameter*, so it
                    // is evaluated before the function body that makes `accounts`. Reading the
                    // login at write time also means a response landing after a sign-out is not
                    // filed under the account that has just gone.
                    credentials.activeLogin?.let { login ->
                        RequestDb.write(
                            AccountStore(rootDir).dirOf(login),
                            System.currentTimeMillis(),
                            stats,
                        )
                    }
                },
            )
        },
        notify: (AgentSession) -> Unit = {},
        changed: (List<AgentSession>) -> Unit = {},
        /**
         * What the agent is doing, page by page — **[session key]** (`project/branch`), not the project.
         *
         * The key rather than the name because two branches of one project are two conversations with
         * two logs (§13d): a page from `feature`'s agent landing in main's chat would show work that
         * is not happening in the conversation the person is reading.
         */
        onPage: (String, Agent.Live) -> Unit = { _, _ -> },
        /**
         * Called when a command has changed a project on disk, so the file list can be re-read.
         *
         * **A parameter rather than something this function does itself**, because this is a plain
         * factory with no `Looper` and no view model: `main` and `refresh()` live in the caller. The
         * default does nothing, so a wiring built in a test needs no screen.
         */
        onDiskChanged: () -> Unit = {},
        /**
         * The browser the agent may use.
         *
         * **Made once and shared, not per runner.** A WebView owns a `Looper` thread and a Chromium
         * process for its whole life, so building one per turn would be a thread and an engine per
         * request. It is also why this is a parameter rather than made inside: the caller decides
         * whether an engine exists at all, and a test passes a fake.
         *
         * Defaults to `Browser.NONE`, so a wiring built without a decision cannot browse.
         */
        browser: com.example.notes.browser.Browser = com.example.notes.browser.Browser.NONE,
        /**
         * Where `fetch` gets its bytes.
         *
         * **A parameter so a test can answer without a socket**, the same rule the browser follows.
         *
         * **Null means "the real one, with this wiring's settings"**, and that default is not
         * `Fetcher.http()` with its own policy: the fetcher checks each redirect hop, and a fetcher
         * that refused a private hop while the operator had allowed private addresses would be a
         * rule the person turned off coming back through a different door.
         */
        fetcher: Fetcher? = null,
    ): AgentRunner? {
        val login = credentials.activeLogin ?: return null
        val accounts = AccountStore(rootDir)
        val ws = Workspace(accounts.dirOf(login), accounts.sharedDir)
        val author = accounts.account(login)?.author() ?: AppViewModel.DEFAULT_AUTHOR
        val git = ProjectGit(
            workspace = ws,
            tokenProvider = { credentials.token(login) },
            authorProvider = { author },
            index = { project, branch, why -> IndexQueue.want(project, why, branch) },
            // **The same rule the screen's `ProjectGit` follows**, because the agent's push is the
            // same push: a project on another host authenticates with its own secret for that host.
            vaultToken = { project, branch, host ->
                credentials.secret(project, branch, GitCredentials.SECRET, host)
            },
            // The same fact for the agent's push: the account says which host its key is for.
            accountHost = { accounts.account(login)?.host ?: GITHUB_HOST },
            gitUser = { accounts.account(login)?.gitUser.orEmpty() },
            // **The scoped connection, asked the same way the screen asks it.** An agent pushing from
            // `feature` must present the credential that branch holds, not the project's — a token a
            // host limits to one branch is a credential a branch owns.
            gitConnection = { project, branch, host -> credentials.gitConnectionFor(project, branch, host) },
        )
        // **The indexer, wired before anything can commit.** Both the runner and the screen build a
        // `ProjectGit`, and either of them can be the first to save something, so both wire this —
        // `configure` is idempotent and this is the same account, key and model file either way.
        IndexQueue.configure(
            ws = ws,
            embedder = Embedder.openRouter(
                key = { credentials.activeLogin?.let { credentials.modelKey(it) } },
                baseUrl = { ModelConfigFile.load(ws.accountDir).getOrNull()?.baseUrl ?: OPENROUTER_BASE },
            ),
            models = { ModelConfigFile.load(ws.accountDir).getOrNull() },
            context = app,
        )
        // The model is read per request, not once: a person who changes the model while the
        // service is running expects the next poll to use it, and a runner built at boot with a
        // model baked in would keep asking the old one until the process died.
        return AgentRunner(
            ws = ws,
            git = git,
            settings = settings,
            // **Resolved per worktree, and per poll.** A branch may have a model of its own
            // (`PLAN-credentials` §2.2), and a person who changes one while the service runs expects
            // the next poll of *that* worktree to use it.
            wired = { project, branch -> wiredFor(ws, credentials, project, branch, settings) },
            door = door,
            notify = notify,
            changed = changed,
            onPage = onPage,
            browser = browser,
            // **The real fetcher, and the private-address switch is the browser's own.** One
            // setting decides "may this agent reach the private network", because a fetch obeying a
            // different answer from a browse would be two rules for one question — and the one that
            // is wrong is the one nobody reads.
            fetcher = fetcher ?: Fetcher.http(privateAllowed = { settings.browsePrivate }),
            // **The real transport, and the account's own store.** An MCP article names a secret; the store
            // is what turns that name into a token, and it is passed here rather than read inside the tool
            // so a test can be a store without a keystore.
            mcp = Mcp.http(),
            credentials = credentials,
            // **The real embedder, over the same key the chat model uses.** One key per account is the
            // operator's rule, and a second key for vectors would be a second thing to rotate, leak or
            // forget. Reading it per call means pasting a new key reaches the next search, not the next
            // launch.
            embedder = Embedder.openRouter(
                key = { credentials.activeLogin?.let { credentials.modelKey(it) } },
            ),
            // **The whole config, read per call, not just the two rows.** `SemanticSearch` asks it for
            // its `embedding` and `rerank` at the moment of the search, so a person who changes either
            // while the agent is idle is answered by the choice in force.
            models = { ModelConfigFile.load(ws.accountDir).getOrNull() },
            // **The real scanner, and the account's own threshold.** Read per call, like the other limits: a
            // person who moves the number while an agent is idle means the next page read.
            // **The account's own answer, read per call.** A threshold of zero and the switch being off are
            // two ways to say "do not scan", and the switch is checked here so the number survives it — a
            // person who turns the scanner back on should not have to remember what it used to be.
            scanner = Scanner.openRouter(
                key = { credentials.modelKey(login) },
                threshold = { if (settings.scanAllowed) settings.scanThreshold else 0.0 },
                baseUrl = { ModelConfigFile.load(ws.accountDir).getOrNull()?.baseUrl ?: OPENROUTER_BASE },
            ),
            httpAllowed = { settings.httpAllowed },
            // **Read per call, so a cap lowered while the agent is idle applies to the next request**
            // rather than to the runner built before the person changed it.
            fetchLimit = { settings.fetchBytes },
            // **The same function the project's ⋮ menu calls.** An agent that exports a file and a
            // person who exports the same one should find them identical, and two implementations of
            // "copy this to Downloads" would drift in exactly the ways nobody notices.
            phone = MediaStoreDownloads(app),
            onDiskChanged = onDiskChanged,
        )
    }

    /**
     * The model a runner asks when the config cannot be read.
     *
     * The app's own default, not a guess: a config that will not parse is a person's file being
     * edited, and the right answer is the model the app ships with rather than no request at all.
     */
    val DEFAULT_MODEL = ActiveModel(ModelConfigFile.DEFAULT_MODEL, "high")

    /**
     * **What a turn on [project] at [branch] is asked with**, from what is on this phone.
     *
     * The one place the account's own records and the scoped vault are put together: the account
     * supplies the model and address it has chosen (or the shipped default when its file will not
     * parse), the vault supplies whatever a branch or project wrote, and [wiredFor] inherits the rest
     * field by field — with the general settings as the root for the three numbers.
     *
     * **The account's key is a function, not a read**, so a poll with nothing due decrypts nothing.
     */
    fun wiredFor(
        ws: Workspace,
        credentials: Credentials,
        project: String?,
        branch: String?,
        settings: SettingsStore,
    ): Wired {
        val login = credentials.activeLogin
        // **The general settings are the root of the chain**, and the numbers below them are the
        // person's own: a branch writes what it wants to change and inherits the rest.
        val general = ContextBudget.Limits.of(
            trigger = settings.triggerTokens.toLong(),
            hard = settings.hardTokens.toLong(),
            target = settings.targetTokens.toLong(),
        )
        return wiredFor(
            project = project,
            branch = branch,
            account = ModelConfigFile.load(ws.accountDir).getOrNull()?.active ?: DEFAULT_MODEL,
            general = general,
            connectionAt = { scope -> credentials.connection(scope, ConnKind.LLM) },
            choiceAt = { scope -> credentials.choice(scope, ConnKind.LLM) },
            accountKey = { login?.let { credentials.modelKey(it) } },
            accountKeyState = { login?.let { credentials.modelKeyState(it) } ?: KeyState.NONE },
        )
    }
}
