package com.example.notes

import java.io.File

/**
 * **One subagent's turn: its own log, its own scope, and a report written into the chat agent's
 * conversation.**
 *
 * A subagent is not a tool call. It has its own record under `agent/<id>/`, its own context, its own
 * clock and its own session — which is what "it keeps its context between tasks" means, and it is
 * why its report arrives minutes later as a [AgentLog.SUB] row rather than as the answer to the
 * call that started it.
 *
 * ## Why this is not part of [AgentRunner]
 *
 * The runner's job is *which session runs when*. This class's job is *what one subagent's turn is*,
 * and the two are separable: a subagent's turn needs a scope, a log and a report sink, and none of
 * those are facts about the tick. Keeping it separate also means it can be driven in a test with no
 * clock, no session store and no worker.
 *
 * ## What it deliberately does not do
 *
 * **It does not start itself.** A subagent runs when the chat agent gives it a task, and the runner
 * decides that from the registry. A class that both decided and ran would be the second answer to
 * "when does this happen" that this codebase keeps having to delete.
 */
class SubagentRun(
    private val ws: Workspace,
    private val git: ProjectGit,
    private val settings: SettingsStore,
    private val door: (ActiveModel) -> Door,
    /** Where a report goes: the chat agent's own log, as a `SUB` row. */
    private val report: (project: String, from: Int, body: String) -> Unit,
    private val model: () -> ActiveModel,
    private val onPage: (String, Agent.Live) -> Unit = { _, _ -> },
    private val browser: com.example.notes.browser.Browser = com.example.notes.browser.Browser.NONE,
    private val fetcher: Fetcher = Fetcher.NONE,
    private val mcp: Mcp = Mcp.NONE,
    private val credentials: Credentials? = null,
    private val scanner: Scanner = Scanner.NONE,
    private val fetchAllowed: () -> Boolean = { true },
    private val github: () -> GitHub? = { null },
    private val githubToken: () -> String? = { null },
    private val downloads: ((File) -> Result<Export.Done>)? = null,
) {

    /**
     * **Keeps a subagent's cache warm, the way the chat agent's own keepalive does.**
     *
     * The operator's rule: *"subagents do need keepalive because they keep context between tasks."*
     * That is the whole reason a subagent has a log of its own — and it is *more* justified than for
     * a chat agent, because a subagent's context is a body of work rather than a conversation, so
     * paying a full prompt again on every task is the cost the keepalive exists to avoid.
     *
     * **A ping is not a turn and cannot end one.** Nothing is written into the conversation, nothing
     * is parsed, and the subagent's task is untouched — which is the same contract `Agent.warm` has
     * for the chat agent, and the reason this reuses it rather than inventing a second one.
     *
     * Returns null on success, or the sentence a person would read.
     */
    fun warm(project: String, id: Int): String? {
        val dir = ws.projectDir(project)
        if (!dir.isDirectory) return "$project is gone"
        return try {
            val log = AgentLog.openAt(File(dir, "${AgentLog.DIR}/$id"))
            val agent = agentFor(project, log)
            when (val warm = agent.warm()) {
                is Agent.Warm.Sent -> null
                is Agent.Warm.Failed -> warm.why
            }
        } catch (t: Throwable) {
            Secrets.explain(t, "subagent #$id could not be kept warm")
        }
    }

    /** What one turn did, so the caller can decide whether the subagent keeps its place. */
    sealed interface Done {
        /** It said something. [said] is the report, already result-shaped. */
        data class Reported(val said: String) : Done

        /** It could not run, and [why] is the sentence a person reads. */
        data class Failed(val why: String) : Done
    }

    /**
     * **The agent a subagent turn runs**, built in one place so a ping and a task cannot disagree.
     *
     * A keepalive that was built from a different model, a different window or a different scope
     * would warm the wrong cache — and the difference would be invisible until a task's first
     * request paid for a prompt the ping had not warmed.
     */
    private fun agentFor(
        project: String,
        log: AgentLog,
        active: ActiveModel = model(),
        limits: ContextBudget.Limits = limitsFor(active),
    ): Agent = Agent(
        accountDir = ws.accountDir,
        project = project,
        ws = ws,
        git = git,
        log = log,
        door = door(active),
        model = active,
        maxResult = ContextBudget.resultLimit(settings.maxResult, active.contextLength)
            .inForce.toInt(),
        limits = limits,
        bg = Background.inline(),
        foregroundMs = settings.foregroundMs,
        backgroundMs = { settings.backgroundMs },
        triggerTokens = { limits.triggerAt },
        sessionTokens = settings.sessionLimit,
        onPage = { live -> onPage(project, live) },
        browser = browser,
        fetcher = fetcher,
        mcp = mcp,
        credentials = credentials,
        scanner = scanner,
        fetchAllowed = fetchAllowed,
        privateAllowed = { settings.browsePrivate },
        browseMs = { settings.browseMs },
        github = github,
        githubToken = githubToken,
        downloads = downloads,
        // **The boundary, and the reason a shorter tool list was not enough.** `tree` accepts
        // `../` on purpose and `search` walks the account, so a subagent handed `{read, tree, edit}`
        // could still reach everything.
        scope = Scope.Project(project),
    )

    /** The two context limits, from the operator's numbers and the pinned endpoint's window. */
    private fun limitsFor(active: ActiveModel) = ContextBudget.Limits.of(
        triggerAsked = settings.triggerTokens.toLong(),
        hardAsked = settings.hardTokens.toLong(),
        targetAsked = settings.targetTokens.toLong(),
        ceiling = ContextBudget.ceiling(active.provider, active.contextLength, active.maxOutput),
    )

    /**
     * **Runs one task for subagent [id], in [project].**
     *
     * The log is `agent/<id>/` — inside the project, so it is excluded from git by the `agent/` line
     * that already exists, and so a person opening the project folder can find it. It is *not*
     * `agent/` itself: that is the chat agent's record, and two agents writing one file is the
     * lost-update shape the whole app avoids.
     */
    fun run(project: String, id: Int, task: String): Done {
        val dir = ws.projectDir(project)
        // **A subagent for a project that is gone does not run.** The same rule the tick applies:
        // a folder that is not there is not a place to write a record, and a reader that made it
        // would recreate the project the person deleted.
        if (!dir.isDirectory) {
            return Done.Failed("$project is gone, so subagent #$id did not run")
        }
        val active = model()
        val limits = limitsFor(active)
        return try {
            // **`openAt`, not `open`.** `open` appends `agent/`, so passing it `agent/2` would put
            // the record at `agent/2/agent/` — a folder nobody would think to look in, made by a
            // reader, which is the exact shape of the bug that brought a deleted project back.
            val log = AgentLog.openAt(File(dir, "${AgentLog.DIR}/$id"))
            val agent = agentFor(project, log, active, limits)
            // **`wake`, not `burst`.** A subagent's task is a thing that happened and that it must
            // act on, which is exactly what a wake says — the same shape a finished background
            // command has. `burst` would make it look like the person speaking to it.
            when (val end = agent.wake(task)) {
                is Agent.End.Concluded -> {
                    val said = end.said.trim()
                    // **A subagent that says nothing still reports.** A silent finish would be work
                    // with no trace, and the app's rule is that everything that exists is
                    // reachable — so "nothing to report" is a report.
                    //
                    // **The test is on what the agent actually produces, not on emptiness.**
                    // `wordsWithoutCalls` substitutes `(the model said nothing)` for a reply with no
                    // text, so `said` is never blank — a check for `isEmpty()` would be a branch
                    // that can never run, which is a guard that looks like one and is not.
                    val body = if (said == Agent.EMPTY_REPLY) {
                        "0\nsubagent #$id finished with nothing to report"
                    } else {
                        "0\n$said"
                    }
                    report(project, id, body)
                    Done.Reported(body)
                }
                is Agent.End.Stopped -> {
                    val body = "1\nsubagent #$id stopped: ${end.why}"
                    report(project, id, body)
                    Done.Reported(body)
                }
                is Agent.End.Interrupted ->
                    // **Not reported.** An interruption is the person speaking, and the chat agent
                    // will hear that from the person itself — a report would say the same thing
                    // twice, in a voice that did not do it.
                    Done.Failed("interrupted: ${end.why}")
            }
        } catch (t: Throwable) {
            val why = Secrets.explain(t, "subagent #$id could not run")
            // **The failure is reported**, because a subagent that dies silently is a task the chat
            // agent thinks is still running. The status is `1` so the model reads it as an outcome.
            report(project, id, "1\nsubagent #$id failed: $why")
            Done.Failed(why)
        }
    }
}
