package com.example.notes

import org.json.JSONObject
import java.io.File

/**
 * The sessions this phone is running, on disk.
 *
 * **A session outlives the screen that started it**, so it cannot live in the view model's memory:
 * the app is closed, the system kills it, the phone reboots, and a session that was waiting has to
 * still be waiting rather than silently gone. It is written every time a session changes and read
 * when the app opens — which is the design's "state is rebuilt from disk, so a session resumes
 * instead of dying quietly".
 *
 * It lives under the account's `.muffy/`, beside the locks and the pins, and **not** in the
 * project: it is this phone's state, like a lock is, and it must not become a commit in the
 * person's history. Nothing here is written into the project, so an agent being on is not a
 * change the project can see.
 *
 * One file, `<account>/.muffy/agents.json`, keyed by project. A file per project would be a
 * directory to keep in step; a project that is renamed or deleted simply loses its key.
 */
class AgentSessions(private val accountDir: File) {

    private val stateDir: File get() = File(accountDir, Workspace.STATE_DIR)

    private val file: File get() = File(stateDir, AGENTS)

    /**
     * The lock the file is protected by — **shared by every instance over the same file.**
     *
     * A per-instance lock would exclude nothing, and this file has two writers by design: the screen
     * writes a session when the person starts or stops an agent and when a turn it ran ends, and the
     * runner writes one on every poll, from its own worker. Two `AgentSessions` objects over one
     * account would each read the whole file, each change their own key, and each write the whole
     * file back — so the second write silently undoes the first, and an agent somebody just switched
     * off comes back, or one just switched on never appears.
     *
     * The read-modify-write is therefore *inside* the lock rather than around it: reading outside
     * and writing inside is the same lost update with better manners.
     */
    private val guard: Any get() = lockFor(file)

    /** Every session this phone knows, newest-looking first is not meaningful: by name. */
    fun read(): List<AgentSession> {
        if (!file.isFile) return emptyList()
        val root = try {
            JSONObject(file.readText())
        } catch (t: Throwable) {
            // A file that will not parse is not a reason to lose the agent: it is a reason to
            // start from nothing, and the record still holds every turn that happened.
            return emptyList()
        }
        return root.keys().asSequence().mapNotNull { name ->
            root.optJSONObject(name)?.let { readOne(name, it) }
        }.sortedBy { it.project }.toList()
    }

    private fun readOne(project: String, json: JSONObject): AgentSession? = try {
        AgentSession(
            project = project,
            state = enumOr(json.optString("state"), AgentSession.State.IDLE),
            sub = enumOr(json.optString("sub"), AgentSession.Sub.WAITING),
            reason = json.optString("reason").takeIf { it.isNotEmpty() }
                ?.let { enumOr<AgentSession.StopReason>(it, AgentSession.StopReason.ERROR) },
            retries = json.optInt("retries", 0).coerceAtLeast(0),
            askedAt = json.optLong("askedAt", 0),
            reminders = json.optInt("reminders", 0).coerceAtLeast(0),
            lastPollAt = json.optLong("lastPollAt", 0),
            // **An older file has no interval**, and zero is "the app's own" — which is exactly
            // what a session written before an agent could ask for one was getting.
            idleMs = json.optLong("idleMs", 0).coerceAtLeast(0),
            warmFrom = json.optLong("warmFrom", 0).coerceAtLeast(0),
            wokenThrough = json.optLong("wokenThrough", 0).coerceAtLeast(0),
            lastWords = json.optString("lastWords"),
        )
    } catch (t: Throwable) {
        null
    }

    /** Writes one session, and forgets any other key for the same project. */
    fun put(session: AgentSession) {
        synchronized(guard) {
            val root = objectOf()
            root.put(session.project, one(session))
            write(root)
        }
    }

    /** Forgets a project's session. */
    fun remove(project: String) {
        synchronized(guard) {
            val root = objectOf()
            if (!root.has(project)) return
            root.remove(project)
            write(root)
        }
    }

    private fun objectOf(): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun write(root: JSONObject) {
        try {
            stateDir.mkdirs()
            // **Written beside the file and renamed over it.** A process killed mid-write leaves
            // a truncated `agents.json`, and a truncated file is every session lost at once —
            // the one failure this file exists to prevent. A rename replaces it whole or not at
            // all, and the reader sees one of two complete versions.
            val fresh = File(stateDir, "$AGENTS.new")
            fresh.writeText(root.toString())
            if (!fresh.renameTo(file)) {
                file.writeText(root.toString())
                fresh.delete()
            }
        } catch (t: Throwable) {
            // A session that could not be written is a session that will have to be started
            // again. It is not worth losing the turn in progress over, and there is nowhere to
            // record the failure except the file that failed.
        }
    }

    /** The same value as one JSON object, with the key names kept short and stable. */
    private fun one(s: AgentSession): JSONObject = JSONObject()
        .put("state", s.state.name)
        .put("sub", s.sub.name)
        .put("reason", s.reason?.name ?: "")
        .put("retries", s.retries)
        .put("askedAt", s.askedAt)
        .put("reminders", s.reminders)
        .put("lastPollAt", s.lastPollAt)
        .put("idleMs", s.idleMs)
        .put("warmFrom", s.warmFrom)
        .put("wokenThrough", s.wokenThrough)
        .put("lastWords", s.lastWords)

    /** An enum by name, and [fallback] rather than a crash when the file holds something else. */
    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    companion object {
        /** The file's name, under `.muffy/`. */
        const val AGENTS = "agents.json"

        /**
         * One lock per file, for the whole process — the same rule as the record's, for the same
         * reason: two objects over one file are two writers, not two owners.
         */
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        private fun lockFor(file: File): Any = locks.computeIfAbsent(file.absolutePath) { Any() }
    }
}
