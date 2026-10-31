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
        door: (ActiveModel) -> Door = { active ->
            OpenRouterDoor(
                key = { credentials.activeLogin?.let { credentials.modelKey(it) } },
                keyState = { credentials.activeLogin?.let { credentials.modelKeyState(it) } ?: KeyState.NONE },
                model = active.model,
                provider = active.provider,
                effort = active.effort,
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
        /** What the agent is doing, page by page, while it works. See `Agent.onPage`. */
        onPage: (String, Agent.Live) -> Unit = { _, _ -> },
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
    ): AgentRunner? {
        val login = credentials.activeLogin ?: return null
        val accounts = AccountStore(rootDir)
        val ws = Workspace(accounts.dirOf(login))
        val author = accounts.account(login)?.author() ?: AppViewModel.DEFAULT_AUTHOR
        val git = ProjectGit(
            workspace = ws,
            tokenProvider = { credentials.token(login) },
            authorProvider = { author },
            keepProvider = { settings.historyLength },
        )
        // The model is read per request, not once: a person who changes the model while the
        // service is running expects the next poll to use it, and a runner built at boot with a
        // model baked in would keep asking the old one until the process died.
        return AgentRunner(
            ws = ws,
            git = git,
            settings = settings,
            model = { ModelConfigFile.load(ws.accountDir).getOrNull()?.active ?: DEFAULT_MODEL },
            door = door,
            notify = notify,
            changed = changed,
            onPage = onPage,
            browser = browser,
            // **The real GitHub, and the key read per call.** A local project has a remote that is
            // not GitHub's, and `where()` in the tools refuses that in words rather than asking.
            github = {
                AppViewModel.githubFor(login, accounts, BuildConfig.DEBUG)
            },
            githubToken = { credentials.token(login) },
        )
    }

    /**
     * The model a runner asks when the config cannot be read.
     *
     * The app's own default, not a guess: a config that will not parse is a person's file being
     * edited, and the right answer is the model the app ships with rather than no request at all.
     */
    val DEFAULT_MODEL = ActiveModel(ModelConfigFile.DEFAULT_MODEL, "deepseek", "high")
}
