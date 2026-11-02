package com.example.notes

/**
 * **How far an agent may reach.**
 *
 * Not the same question as *what it may do* — that is [AgentTools.specs], and it is answered by
 * leaving a tool out. This is answered by refusing a path, and the difference matters because the
 * tool list is not a sandbox: `tree` accepts `../` on purpose and `search` walks every project in the
 * account, so a subagent handed `{read, tree, edit}` could still read the whole account and write
 * into any project it could name.
 *
 * **Two scopes, because there are two kinds of agent.** A chat agent works for the account — the
 * operator's rule is *"any project's agent gets readonly access to account folders outside his
 * project"* — and a subagent works on one project, because that is what it was started for.
 */
sealed interface Scope {

    /** The account: every project, the system project, and the account's own folder. */
    data object Account : Scope

    /**
     * **One project, and nothing outside it.**
     *
     * [project] is the name, not the folder, so a refusal can name it in a sentence — *"you are
     * working on groceries"* — rather than printing a path the model would then have to compare
     * against the one it asked for.
     */
    data class Project(val project: String) : Scope

    /** The sentence a refusal carries, naming where the boundary is. */
    fun refusal(): String = when (this) {
        Account -> ""
        is Project ->
            "That is outside $project. You are working on $project, so you can read and write " +
                "only inside it. Ask the chat agent if the work needs another project."
    }
}
