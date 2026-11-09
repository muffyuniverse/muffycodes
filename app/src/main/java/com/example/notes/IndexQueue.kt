package com.example.notes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * **Indexing follows the project, not the search.**
 *
 * Three things ask for an index pass, and none of them should wait for the work:
 *
 * - a **commit** — every change the app makes is one, so `ProjectGit.commitOutcome` is the single
 *   choke point for "the project's text changed" ([want] is called from there);
 * - a **project arriving from GitHub** — a clone writes a whole tree at once and nothing commits it
 *   file by file, so the comprehensive pass is asked for once, after the clone;
 * - a **search**, whose guard stays exactly as it was. It is the net under the other two: anything
 *   that changed without a commit — a pull, a file the app did not write, a commit that failed — is
 *   still caught the next time somebody searches.
 *
 * ## Why an object, and why the work is idempotent
 *
 * The app builds `ProjectGit` twice (the editor's and the runner's), and a per-instance hook would
 * mean two indexers racing on one database. A single queue that coalesces per project fixes that by
 * construction: a second request for a project already waiting is *the same request*, because an
 * index pass answers "make this index match this project", not "do one pass". That is also why a
 * pass can run while a search is reading: both are the same idempotent statement, and SQLite
 * serialises the writes.
 *
 * **Unconfigured is the default**, so a test — or a build with no credentials — gets a queue that
 * does nothing rather than one that spends a key. `configure` is idempotent and cheap, so the two
 * places that can build a `ProjectGit` both call it and either is enough.
 */
object IndexQueue {

    /**
     * How long one background pass may take.
     *
     * **Fifteen minutes, which is not a policy but a stop.** A comprehensive pass over a large
     * repository is tens of seconds at the measured throughput; the bound exists so a wedged
     * network call cannot leave a thread alive for the life of the process. A pass that hits it
     * records what it did and says what is left, and the next request continues from there.
     */
    const val PASS_MS = 15L * 60L * 1000L

    /** What a pass did, or the reason it could not start. */
    data class Done(val project: String, val why: String, val said: String, val ok: Boolean)

    private class Wiring(
        val ws: Workspace,
        val embedder: Embedder,
        val models: () -> ModelConfig?,
        val context: Context?,
        /** Called on the worker thread when a pass finishes, for a screen that wants to refresh. */
        val onDone: (Done) -> Unit,
    )

    private val lock = Any()

    /** Wired once; null means "no indexer", which is what an unconfigured build and every test get. */
    private var wiring: Wiring? = null

    /** Projects waiting for a pass, with the reason the first asker gave. */
    private val waiting = LinkedHashMap<String, String>()

    private var worker: Thread? = null

    /**
     * Bumped by [clear], and captured by each worker when it starts.
     *
     * **A cleared queue must not keep working.** The worker runs passes off its own thread, and a
     * pass over a large project outlives the request that asked for it — so a test that cleared the
     * queue and moved on would still have a thread walking a temp folder that no longer exists, and
     * a shard would spend its budget on work nobody asked for. The generation is the stop: a worker
     * whose generation is no longer current finishes the pass it is in and exits.
     */
    private var generation = 0

    /** How many passes this process has run, for a test that must not race a thread. */
    @Volatile
    var passes: Int = 0
        private set

    /** True while a worker thread is alive — one may be between passes and about to finish. */
    val busy: Boolean get() = synchronized(lock) { worker != null }

    /** True when nothing is waiting and no worker is alive. */
    val idle: Boolean get() = synchronized(lock) { worker == null && waiting.isEmpty() }

    /**
     * Wires the queue to this account's project store, key and model choices.
     *
     * **Called with the app's own `Context`** so a finished pass can be posted as a notification;
     * null is allowed and means "session log only", which is what a unit test wants.
     */
    fun configure(
        ws: Workspace,
        embedder: Embedder,
        models: () -> ModelConfig?,
        context: Context? = null,
        onDone: (Done) -> Unit = {},
    ) {
        synchronized(lock) { wiring = Wiring(ws, embedder, models, context, onDone) }
    }

    /** Takes the indexer away. For a test that must be sure nothing is running behind it. */
    fun clear() {
        synchronized(lock) {
            wiring = null
            waiting.clear()
            // **Stops the worker as well as the wiring.** Without this a pass in flight runs to
            // completion against a folder the caller has already deleted — and in a test shard it
            // spends the next test's budget.
            generation++
        }
    }

    /**
     * Asks for a pass over [project], and returns immediately.
     *
     * **A request while one is outstanding is the same request**, so a hundred saves in a session
     * ask for a handful of passes rather than a hundred: the map is the coalescer, and [why] is
     * only there so the row in the log can say what prompted the first ask.
     */
    fun want(project: String, why: String, branch: String = AgentSession.MAIN) {
        // **Keyed by the worktree, not by the project** (§13d). Two branches of one project are two
        // indexes over two trees; coalescing them by project would make the second branch's pass a
        // duplicate of the first's request and leave its index stale — a `search` that answers with
        // files from the other branch.
        val key = AgentSession.keyOf(project, branch)
        val start = synchronized(lock) {
            if (wiring == null) return
            waiting.putIfAbsent(key, why)
            if (worker != null) return
            val mine = generation
            val thread = Thread({ drain(mine) }, "index-queue")
            thread.isDaemon = true
            worker = thread
            thread
        }
        start.start()
    }

    /**
     * Runs passes until nothing is waiting.
     *
     * **A pass that fails does not retry by itself.** The queue's whole contract is "make the index
     * match"; if the model refused, asking again immediately would ask it the same thing again — the
     * next commit, the next project or the next search is a better moment, and each of those will
     * ask.
     */
    private fun drain(mine: Int) {
        while (true) {
            val job = synchronized(lock) {
                // **The stop, checked before every pass.** A queue cleared while this worker was
                // busy is a queue whose wiring is gone and whose remaining asks belong to nobody.
                if (mine != generation) {
                    worker = null
                    return
                }
                val next = waiting.entries.firstOrNull()
                if (next == null) {
                    worker = null
                    return
                }
                waiting.remove(next.key)
                wiring?.let { it to next.key to next.value }
            } ?: return
            val (pair, why) = job
            val (wire, key) = pair
            val project = AgentSession.projectOf(key)
            val branch = AgentSession.branchOf(key)
            val done = run(wire, project, branch, why)
            passes++
            try {
                wire.onDone(done)
            } catch (e: Throwable) {
                // A listener is a screen's business; its failure must not stop the queue.
            }
        }
    }

    /** One pass, its log row, and its notification. */
    private fun run(wire: Wiring, project: String, branch: String, why: String): Done {
        val dir = wire.ws.treeOf(project, branch)
        if (!dir.isDirectory) {
            return Done(project, why, "there is no folder called \"$project\" any more", false)
        }
        val engine = SemanticSearch(
            ws = wire.ws,
            project = project,
            branch = branch,
            embedder = wire.embedder,
            embedding = { wire.models()?.embedding },
            rerank = { wire.models()?.rerank },
            // **The folder, read per call**, because a template can be edited while this app runs and
            // the pointer only names it. Wired here rather than inside `SemanticSearch` so that class
            // keeps taking facts rather than reaching for a store.
            schema = { name -> Schemas.read(wire.ws.accountDir, name) },
        )
        val result = try {
            engine.index(Deadline(PASS_MS))
        } catch (e: Throwable) {
            Result.failure(
                IllegalStateException(Secrets.explain(e, "The project could not be indexed")),
            )
        }
        val said = result.fold(
            onSuccess = { it.said() },
            onFailure = { it.message ?: "it could not be indexed" },
        )
        val ok = result.isSuccess
        // **The session log, so the record answers "what has this app done"** — `appNote`, so the row
        // is in the file and not in the model's context: indexing is not part of the conversation,
        // which is the same split `forget` and the scanner follow for text that is not.
        try {
            AgentLog.open(dir).appNote("index", "$why: $said")
        } catch (e: Throwable) {
            // A project whose log cannot be opened is a project whose indexing still worked; the
            // notification carries the same sentence.
        }
        notify(wire.context, project, said, ok)
        return Done(project, why, said, ok)
    }

    /**
     * Puts the result in the tray, **once per pass, replacing the last** — so a project indexed ten
     * times in an afternoon leaves one line, not ten.
     *
     * Reuses the agent's own channel rather than making a second one: it is the same subject (what
     * this app is doing in the background), and a person who has muted that has said what they think
     * of it once rather than twice.
     */
    private fun notify(context: Context?, project: String, said: String, ok: Boolean) {
        if (context == null || !Notices.granted(context)) return
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(AgentService.CHANNEL) == null
            ) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        AgentService.CHANNEL,
                        "The agent",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "One line while an agent is on: how many, and how many need you"
                        setShowBadge(true)
                    },
                )
            }
            manager.notify(
                ID + (project.hashCode() and 0x7FFF),
                Notification.Builder(context, AgentService.CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(if (ok) "Indexed $project" else "$project could not be indexed")
                    .setContentText(said)
                    .setStyle(Notification.BigTextStyle().bigText(said))
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: Throwable) {
            // A phone that refuses notifications is not a phone whose index failed.
        }
    }

    /** Where a project's indexing line lives, clear of the service's own ids. */
    private const val ID = 71_000
}
