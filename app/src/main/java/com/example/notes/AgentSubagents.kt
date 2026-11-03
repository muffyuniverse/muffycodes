package com.example.notes

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * **The subagents this project has, and the two facts a person cannot see from the files.**
 *
 * A subagent is not a session in the ordinary sense. It has a log and a context like any agent, and
 * what it does *not* have is a screen: it is not a project row and it is not a chat. So the two facts
 * that would otherwise be derivable from a screen — **which id is next** and **which ids have been
 * retired** — have to be written down somewhere, and this is that file.
 *
 * ## Why the ids never come back
 *
 * The operator's rule: *"when amount of running subagents is above the limit then the first idle
 * subagent is removed, and if chat agent tries to write to an agent (by id) who has been removed then
 * he gets exception."*
 *
 * That only works if a removed id stays removed. If `#2` could be reissued, then a report the chat
 * agent already read, or a task it already wrote, would silently attach to a different agent's work —
 * and nothing would say so, because the id is the only thing tying the two together. So the counter
 * only ever goes up.
 *
 * ## Why this file needs the same lock `AgentSessions` has
 *
 * It has two writers by design: the screen writes it when the person changes the count, and the
 * runner writes it when a subagent starts or finishes. Two `AgentSubagents` objects over one account
 * would each read the whole file, change their own part, and write it back — so the second write
 * undoes the first, and a subagent somebody just started vanishes. The read-modify-write is
 * **inside** the lock rather than around it, which is the same lesson `AgentSessions` records.
 */
class AgentSubagents(private val accountDir: File) {

    private val stateDir: File get() = File(accountDir, Workspace.STATE_DIR)

    private val file: File get() = File(stateDir, FILE)

    private val guard: Any get() = lockFor(file)

    /** One subagent, as the file holds it. */
    data class Sub(
        val id: Int,
        /** The task it was last given, which is how a person tells two apart in a list. */
        val task: String = "",
        /**
         * **Retired by the count, and it does not come back.**
         *
         * Different from *stopped*, which is a subagent that reached its warm ceiling and can be
         * woken with its context intact. A retired one cannot be woken, because waking it would put
         * the count back over the limit — so the refusal names its folder and says to start a new
         * one.
         */
        val retired: Boolean = false,
        /** When it last did anything, for the count's "first idle" and for a list. */
        val lastAt: Long = 0,
    )

    /** Everything this project knows, ids ascending. */
    fun read(project: String): List<Sub> = synchronized(guard) {
        val root = readRoot()
        val list = root.optJSONObject(project)?.optJSONArray(LIST)
        if (list == null) {
            emptyList()
        } else {
            (0 until list.length()).mapNotNull { i ->
                list.optJSONObject(i)?.let { readOne(it) }
            }.sortedBy { it.id }
        }
    }

    private fun readOne(json: JSONObject): Sub? = try {
        val id = json.optInt(ID, 0)
        if (id <= 0) null else Sub(
            id = id,
            task = json.optString(TASK, ""),
            retired = json.optBoolean(RETIRED, false),
            lastAt = json.optLong(LAST_AT, 0),
        )
    } catch (t: Throwable) {
        null
    }

    /**
     * **The id a new subagent gets, and it is never one this project has used.**
     *
     * Read and written under one hold, because two callers asking at the same moment must not get
     * the same answer — which is the lost-update shape this class exists to avoid, arriving through
     * the counter rather than through the list.
     */
    fun mint(project: String, task: String): Sub = synchronized(guard) {
        val root = readRoot()
        val bucket = root.optJSONObject(project) ?: JSONObject().also { root.put(project, it) }
        val list = bucket.optJSONArray(LIST) ?: org.json.JSONArray().also { bucket.put(LIST, it) }
        // **The high-water mark, not the list's length.** A retired entry keeps its place in the
        // list, so the length is the count of ids ever issued — but the mark is what makes that true
        // even if a list is ever pruned, and a counter that can go backwards is the bug this class
        // is named for.
        val next = (bucket.optInt(NEXT, 1)).coerceAtLeast(list.length() + 1)
        val sub = Sub(id = next, task = task, lastAt = System.currentTimeMillis())
        list.put(JSONObject().apply {
            put(ID, sub.id)
            put(TASK, sub.task)
            put(RETIRED, false)
            put(LAST_AT, sub.lastAt)
        })
        bucket.put(NEXT, next + 1)
        writeRoot(root)
        sub
    }

    /** Records that [id] was retired. Does nothing if it is already retired or unknown. */
    fun retire(project: String, id: Int): Boolean = synchronized(guard) {
        val root = readRoot()
        val list = root.optJSONObject(project)?.optJSONArray(LIST) ?: return@synchronized false
        for (i in 0 until list.length()) {
            val one = list.optJSONObject(i) ?: continue
            if (one.optInt(ID, 0) != id) continue
            one.put(RETIRED, true)
            writeRoot(root)
            return@synchronized true
        }
        false
    }

    /**
     * **Sets what [id] is owed next — and a blank task is the claim that clears it.**
     *
     * The registry is the queue, rather than a second list beside it: a subagent whose `task` has not
     * run yet *is* a pending task, and two places saying what is outstanding is two answers to one
     * question. The runner claims by writing `""` before the work goes out, so a tick during the turn
     * does not start it twice.
     *
     * **It does not clear `retired`.** A retired subagent keeps its place and its record; only
     * `fitTo` and `retire` write that flag.
     */
    fun touchTask(project: String, id: Int, task: String) {
        synchronized(guard) {
            val root = readRoot()
            val list = root.optJSONObject(project)?.optJSONArray(LIST) ?: return
            for (i in 0 until list.length()) {
                val one = list.optJSONObject(i) ?: continue
                if (one.optInt(ID, 0) != id) continue
                one.put(TASK, task)
                writeRoot(root)
                return
            }
        }
    }

    /** Records that [id] did something, so the count knows which is the first idle one. */
    fun touched(project: String, id: Int, at: Long = System.currentTimeMillis()) {
        synchronized(guard) {
            val root = readRoot()
            val list = root.optJSONObject(project)?.optJSONArray(LIST) ?: return
            for (i in 0 until list.length()) {
                val one = list.optJSONObject(i) ?: continue
                if (one.optInt(ID, 0) != id) continue
                one.put(LAST_AT, at)
                writeRoot(root)
                return
            }
        }
    }

    /**
     * **Retires the first idle subagent until the live count fits [limit], and says which.**
     *
     * "First idle" is oldest `lastAt`, and a subagent with a task still running is [busy] and is
     * never retired — the count settles when one finishes, rather than killing work in progress.
     * Returns the ids retired, so the caller can trace and stop them.
     */
    fun fitTo(project: String, limit: Int, busy: Set<Int>): List<Int> = synchronized(guard) {
        // **Read inside this hold rather than through [read]**, because a second `synchronized`
        // here would be a second read of the file and a different answer if the screen wrote
        // between them.
        val root0 = readRoot()
        val all = root0.optJSONObject(project)?.optJSONArray(LIST)?.let { list ->
            (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { readOne(it) } }
        } ?: emptyList()
        val live = all.filter { !it.retired }
        val excess = live.size - limit.coerceAtLeast(0)
        if (excess <= 0) return@synchronized emptyList()

        // **Idle first, oldest first.** A subagent that is busy is not a candidate, and if there are
        // not enough idle ones the count stays over until one finishes — which is the rule: a stop
        // is never a kill.
        val candidates = live
            .filter { it.id !in busy }
            .sortedBy { it.lastAt }
            .take(excess)
        if (candidates.isEmpty()) return@synchronized emptyList()

        // **The same `root0` the candidates came from**, so the retirement lands on the entries that
        // were actually judged — not on a re-read that the screen may have changed in between.
        val root = root0
        val list = root.optJSONObject(project)?.optJSONArray(LIST)
        val ids = candidates.map { it.id }
        if (list != null) {
            for (i in 0 until list.length()) {
                val one = list.optJSONObject(i) ?: continue
                if (one.optInt(ID, 0) in ids) one.put(RETIRED, true)
            }
            writeRoot(root)
        }
        ids
    }

    /** The subagent with this id, or null when this project has never issued it. */
    fun find(project: String, id: Int): Sub? = read(project).firstOrNull { it.id == id }

    // ---- the file -----------------------------------------------------------

    private fun readRoot(): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (t: Throwable) {
        // **A file that will not parse is not a reason to lose the subagents.** It is a reason to
        // start from nothing, and the records themselves are still in `agent/<id>/`.
        JSONObject()
    }

    private fun writeRoot(root: JSONObject) {
        try {
            stateDir.mkdirs()
            file.writeText(root.toString())
        } catch (t: Throwable) {
            // A count that cannot be written is worth more than the operation it describes: the
            // subagent still runs, and the next write tries again.
        }
    }

    companion object {
        const val FILE = "subagents.json"
        private const val LIST = "list"
        private const val NEXT = "next"
        private const val ID = "id"
        private const val TASK = "task"
        private const val RETIRED = "retired"
        private const val LAST_AT = "lastAt"

        /** One lock per file, shared by every instance over the same account. */
        private val locks = ConcurrentHashMap<String, Any>()

        private fun lockFor(file: File): Any = locks.computeIfAbsent(file.absolutePath) { Any() }
    }
}
