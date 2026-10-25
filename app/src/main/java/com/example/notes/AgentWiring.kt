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
                timeoutMs = settings.timeoutMinutes * 60_000,
            )
        },
        notify: (AgentSession) -> Unit = {},
        changed: (List<AgentSession>) -> Unit = {},
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
        )
    }

    /**
     * The model a runner asks when the config cannot be read.
     *
     * The app's own default, not a guess: a config that will not parse is a person's file being
     * edited, and the right answer is the model the app ships with rather than no request at all.
     */
    val DEFAULT_MODEL = ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high")
}
