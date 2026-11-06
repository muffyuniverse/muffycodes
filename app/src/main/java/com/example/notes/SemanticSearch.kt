package com.example.notes

import java.io.File

/**
 * **Search by meaning, inside one project.**
 *
 * ## Why per project, and not per account
 *
 * The operator's decision (2026-11-04): *"semantic search is per project, make it this way, so that
 * account wide semantic search is forbidden and scope must be project and lower."*
 *
 * The reason is the index, not taste. A vector space is only meaningful against the vectors it was
 * built with: one model, one dimension, one table. Per account, every project's text would share one
 * database that every project's save has to write to, and a single project's re-index would take the
 * write lock on the whole account's search. Per project, the unit that changes is the unit that is
 * indexed, and [SearchIndex.open] already takes a project folder — the decision cost nothing to place.
 *
 * **So `mode: "semantic"` never reaches outside the project the agent is working in**, which two
 * things enforce and one says out loud:
 *
 * - the walk starts at the project and refuses anything whose canonical path leaves it, so a link
 *   cannot carry a search into a sibling;
 * - [AgentTools] refuses a `scope` that names another project, by name, rather than answering
 *   "nothing matches" about a folder it never looked in;
 * - every answer ends with the folder that was searched, because a limit nobody can see is a limit
 *   somebody will mistake for an empty corpus.
 *
 * ## What it does, in order
 *
 * 1. **Keep the index current** — walk the project, and for each text file whose modification time is
 *    not the one already indexed, chunk and embed it. Unchanged files cost one directory entry and no
 *    request, which is what makes this cheap enough to run inside a search.
 * 2. **Embed the query**, and score it against every stored chunk.
 * 3. **Rerank the top [RERANK_DEPTH]**, because a vector compares a paragraph with a question without
 *    knowing what the question is.
 * 4. **Answer one row per path** — the same shape `search` answers in text mode, so a hit is read with
 *    the same `read {path, from}` as any other line number.
 *
 * ## Why the index is built here, in the search, rather than on save
 *
 * Because there is no other place that runs for every way a file changes. The editor writes, the agent
 * writes, a clone writes, a restore writes; a save hook would have to be added to each, and the one
 * that was missed would be a project whose search silently misses its newest work. Here it is one
 * pass over the tree with a modification time per file, so anything that ever wrote a file is caught
 * the next time anybody searches. The cost is paid once per project per change, and it is bounded by
 * the same [Deadline] as every other command: a search that runs out of time answers with what is
 * indexed and says what is left, which is true and actionable, where a search that refused would
 * leave a person with nothing.
 *
 * **A file the walk could not take is recorded, not skipped quietly** — with its reason — and the
 * count travels in the answer. That is the operator's rule for every other tool here: a failure says
 * what happened.
 */
class SemanticSearch(
    private val ws: Workspace,
    /** The project this agent is working in. **The whole of the search's extent.** */
    private val project: String,
    private val embedder: Embedder,
    /** The chosen embedding model, read per call: a person may pick one while the agent is idle. */
    private val embedding: () -> PickedModel? = { null },
    /** The chosen rerank model, read per call, or null when the person has not chosen one. */
    private val rerank: () -> PickedModel? = { null },
) {

    /**
     * Rows for what [query] is about, inside this project.
     *
     * **The guard runs first**: anything that changed since the last pass is embedded before the
     * question is asked, so an answer is never about text the project no longer has. That is the net
     * under the two faster paths — [IndexQueue] indexes after a commit and after a clone — and it is
     * what catches a pull, a file the app did not write, or a commit that failed.
     *
     * @return the body of the answer, or the reason it could not be answered. **A failure is a
     * sentence**, never a text search standing in for a meaning search: the operator's rule, and the
     * reason is that a substitute answers a question nobody asked while looking like the one asked.
     */
    fun answer(
        scope: Regex?,
        query: String,
        paths: Int,
        depth: Int,
        deadline: Deadline,
        /** Also search the saved versions, which the pass has just brought up to date. */
        history: Boolean = false,
    ): Result<String> {
        val ready = ready().getOrElse { return Result.failure(it) }
        try {
            val caught = catchUp(ready.index, ready.model, ready.dims, deadline)
            return search(
                ready.index, scope, query, paths, depth, ready.model, ready.dims, caught, deadline,
                history,
            )
        } finally {
            ready.close()
        }
    }

    /**
     * One pass: **make the index match the project.**
     *
     * The same pass the guard runs, without a question at the end — this is what [IndexQueue] calls
     * after a commit and after a clone, so a project's text is indexed while nobody is waiting.
     *
     * Idempotent by construction: a file whose modification time is the one already recorded costs one
     * directory entry and no request, so running this twice answers "nothing had changed" and spends
     * nothing. That is what lets the queue coalesce three requests into one pass, and what makes a
     * pass safe to interrupt — the rows it wrote stay true and the next pass carries on.
     */
    fun index(deadline: Deadline): Result<Indexed> {
        val ready = ready().getOrElse { return Result.failure(it) }
        return try {
            Result.success(catchUp(ready.index, ready.model, ready.dims, deadline))
        } finally {
            ready.close()
        }
    }

    /**
     * An index open and agreeing about its own space.
     *
     * ## One model, and it is the one the index was built with
     *
     * A vector only means something against vectors from the **same model** — and, less obviously,
     * the same size. Two models at 1024 dimensions are still two coordinate systems: a cosine between
     * a query from one and a chunk from the other is arithmetic on unrelated numbers that lands in
     * [-1, 1] and looks exactly like a score. That is why [SearchIndex.declare] compares the pair and
     * why fixing the dimensions everywhere would not make models interchangeable.
     *
     * So the index's own record decides, and a **different** model chosen in Settings is refused by
     * name rather than obeyed or ignored: obeying it compares two spaces, ignoring it is the silent
     * no-op this app treats as a defect. The refusal says what empties the index, which is the one
     * act that makes the sentence true.
     */
    private class Ready(val index: SearchIndex, val model: String, val dims: Int) : java.io.Closeable {
        override fun close() = index.close()
    }

    private fun ready(): Result<Ready> {
        val pick = embedding()
        val root = ws.projectDir(project)
        val index = try {
            SearchIndex.open(root)
        } catch (e: Throwable) {
            return Result.failure(
                IllegalStateException(
                    "The index for \"$project\" could not be opened, so nothing was searched: " +
                        reason(e),
                ),
            )
        }
        val built = index.builtWith()
        // **A project that has never been indexed needs a choice; one that has does not.** Settings
        // is where the choice is made, and an index already built carries its own — which is what
        // lets a search work on a phone whose model config was lost.
        if (built == null && pick == null) {
            index.close()
            return Result.failure(IllegalStateException(NO_MODEL))
        }
        // **A model chosen after the index was built is refused, not ignored.** The index's own record
        // used to win silently, so a person who picked a different embedding model in Settings got no
        // effect and no word about it — the silent no-op this app treats as a defect. Dimensions do
        // not rescue this: two models at 1024 are still two coordinate systems, and a cosine between
        // them is a number that looks like a score. So the refusal names both and says what empties
        // it, because a rebuild is a real cost and not a thing to do behind somebody's cursor.
        if (built != null && pick != null && built.model != pick.model) {
            index.close()
            return Result.failure(
                IllegalStateException(
                    "The index for \"$project\" was built with \"${built.model}\", and " +
                        "\"${pick.model}\" is chosen now. Vectors from two models cannot be " +
                        "compared, so the index has to be emptied and rebuilt before this model " +
                        "can answer — choose \"${built.model}\" again to keep the index, or empty " +
                        "it to switch.",
                ),
            )
        }
        val model = built?.model ?: pick!!.model
        val dims = built?.dims ?: pick!!.dimensions ?: run {
            index.close()
            return Result.failure(
                IllegalStateException(
                    "\"${pick!!.model}\" is chosen as the embedding model but no dimension is, and " +
                        "an index has to be one size. Open Settings, choose the row called " +
                        "\"Embedding model\", and pick its dimensions.",
                ),
            )
        }
        index.declare(model, dims).onFailure { why ->
            index.close()
            return Result.failure(
                IllegalStateException(
                    "${why.message} Emptying the index rebuilds it under the model chosen now; " +
                        "until then this one can still answer, and nothing about it is lost.",
                ),
            )
        }
        return Result.success(Ready(index, model, dims))
    }

    // ---- 1. keeping the index current --------------------------------------

    /**
     * What one pass over the project did.
     *
     * **`left` is arithmetic rather than a second walk**: the files were listed before any of them
     * were read, so what was not reached is a subtraction and not a guess.
     *
     * **[said] is the sentence both audiences get** — the note under a search answer, the row in the
     * session log and the line in the notification. One sentence, because three copies of the same
     * fact written three ways is three chances to disagree.
     */
    data class Indexed(
        val total: Int,
        val done: Int,
        val unchanged: Int,
        val failed: Int,
        /** Chunks embedded, which is what a request was actually spent on. */
        val chunks: Int,
        /** Why the pass stopped early, or null when it finished. */
        val stopped: String?,
        /** Saved versions whose text was added or refreshed by the history pass. */
        val versions: Int = 0,
    ) {
        val left: Int get() = (total - done - unchanged - failed).coerceAtLeast(0)

        /** Nothing was stale and nothing failed: the index already said what the project says. */
        val nothingToDo: Boolean
            get() = done == 0 && failed == 0 && versions == 0 && stopped == null

        fun said(): String = when {
            total == 0 -> "there is no text to index"
            // **"nothing had changed" is the answer to a pass that was asked for anyway**, and it says
            // how much is behind it so the number is not mistaken for an empty project.
            nothingToDo ->
                "nothing had changed: all $total file(s) were already indexed"
            stopped != null && left > 0 ->
                "stopped at $done of $total file(s): $stopped"
            stopped != null -> "$done file(s) indexed, then it stopped: $stopped"
            failed > 0 ->
                "$done of $total file(s) indexed, $failed could not be, $chunks chunk(s) embedded" +
                    versionsSaid()
            else -> "$done of $total file(s) indexed, $chunks chunk(s) embedded" + versionsSaid()
        }

        /** The history half of [said], or nothing when the pass found no new version. */
        private fun versionsSaid(): String =
            if (versions == 0) "" else ", $versions saved version(s)"
    }

    private fun catchUp(
        index: SearchIndex,
        model: String,
        dims: Int,
        deadline: Deadline,
    ): Indexed {
        val root = ws.projectDir(project)
        val files = mutableListOf<File>()
        var stopped: String? = null
        // **One canonical root, used for both halves.** `relativeTo` compares paths textually, so a
        // root with a link in it and a file without one would disagree about their own relationship —
        // the file would look like it was outside the project it was just listed from.
        val canon = try {
            root.canonicalFile
        } catch (e: Throwable) {
            root.absoluteFile
        }
        // **The list first, the reads after.** Listing is directory entries and costs nothing next to
        // an embedding request, and knowing the whole list before touching any file is what lets
        // "how many are left" be a subtraction instead of a wish.
        try {
            gather(root, canon, 0, files, deadline)
        } catch (e: StoppedException) {
            stopped = "the time limit ran out while listing the project"
        }
        var done = 0
        var unchanged = 0
        var failed = 0
        var chunks = 0
        for (file in files) {
            if (stopped != null) break
            val rel = relTo(canon, file) ?: continue
            val mtime = file.lastModified()
            val size = file.length()
            // **The whole of why this is affordable.** An unchanged file is one comparison — of two
            // numbers, because the time alone says an edited file is untouched when whatever wrote it
            // preserved the timestamp (a restore, `cp -p`, two saves in one tick).
            if (index.seen(rel) == (mtime to size)) {
                unchanged++
                continue
            }
            try {
                deadline.check(TOOL)
            } catch (e: StoppedException) {
                stopped = "the time limit ran out while indexing"
                break
            }
            val text = try {
                file.readText()
            } catch (e: Throwable) {
                index.note(rel, mtime, SearchIndex.FAILED, reason(e), size)
                failed++
                continue
            }
            // **A file too big to chunk is refused, not cut.** Half a file in the index is a search
            // that answers about text nobody wrote, and the note says which file and why.
            if (text.length > MAX_CHARS) {
                index.note(
                    rel,
                    mtime,
                    SearchIndex.FAILED,
                    "it is ${text.length / 1024} kB and this index takes files up to ${MAX_CHARS / 1024} kB",
                    size,
                )
                failed++
                continue
            }
            val pieces = Chunker.split(text)
            val put = try {
                index.put(rel, "", pieces) { texts -> embedAll(texts, model, dims, deadline) }
            } catch (e: StoppedException) {
                stopped = "the time limit ran out while embedding"
                break
            } catch (e: Throwable) {
                Result.failure(e)
            }
            put.fold(
                onSuccess = { embedded ->
                    // **An empty file is indexed**, with no chunks: "there is nothing here" is an
                    // answer, and a file that stayed unindexed would be counted behind on every pass.
                    index.note(rel, mtime, SearchIndex.INDEXED, size = size)
                    chunks += embedded
                    done++
                },
                onFailure = { e ->
                    index.note(rel, mtime, SearchIndex.FAILED, reason(e), size)
                    failed++
                    // **One failure ends the pass.** Every remaining file would ask the same model
                    // the same question and get the same refusal, and a person wants the reason once
                    // rather than forty times inside a note.
                    stopped = "the index could not be written: ${reason(e)}"
                },
            )
        }
        // **The history half, after the working tree.** Only when the tree finished: a pass that ran
        // out of time has not indexed the newest text yet, and spending what is left of the turn on
        // old versions would answer a search about the past while the present is missing.
        var versions = 0
        if (stopped == null) {
            val walked = history(index, model, dims, deadline)
            versions = walked.first
            chunks += walked.second
            if (walked.third != null) stopped = walked.third
        }
        return Indexed(files.size, done, unchanged, failed, chunks, stopped, versions)
    }

    /**
     * Indexes the **saved versions** of the project's files.
     *
     * ## Why this is affordable, and why it is not a second copy of the project
     *
     * A version's chunks are keyed by their text ([Chunker.hashOf]), so a version that edits one
     * paragraph pays for that paragraph. Everything else it shares with the version before it, and
     * with the working tree — which is why "index the history" is not "index the project forty times
     * over". [SearchIndex.put] skips a hash it already holds and reports how much it embedded, so a
     * second pass over an unchanged history costs nothing at all.
     *
     * ## Why the blob, and not the commit
     *
     * [GitService.walkChanges] reports the blob each commit left at each path. That is the key: a
     * commit id does not survive a prune (which rewrites every commit and no blob) or a force-pushed
     * remote, so keying by it would leave rows dangling in bulk. The commit is resolved when an answer
     * is written, never stored.
     *
     * @return versions added, chunks embedded, and the reason it stopped early (or null).
     */
    private fun history(
        index: SearchIndex,
        model: String,
        dims: Int,
        deadline: Deadline,
    ): Triple<Int, Int, String?> {
        val dir = ws.projectDir(project)
        // **`openExisting`, never `initOrOpen`.** The index is derived data and must never be the
        // thing that creates a repository: an initialised repo here would make a project with no
        // history report one, and it would commit whatever the index walk happened to find. A project
        // with no `.git` has no versions, and that is an answer.
        // **A project with no repository has no versions**, which is not a failure: it is a project
        // that has never been saved. The working tree half already indexed it.
        val repo: org.eclipse.jgit.lib.Repository = try {
            GitService.openExisting(dir)
        } catch (e: Throwable) {
            null
        } ?: return Triple(0, 0, null)
        var versions = 0
        var chunks = 0
        var stopped: String? = null
        try {
            repo.use {
                // **What is already held, asked once.** Every skip below is then a set lookup rather
                // than a tree read, which is what keeps a repeated pass cheap.
                val held = index.versions()
                // **Every commit, with no window.** This used to read the newest forty, inherited
                // from the text-mode history where forty was how far a person scrolls. For an index
                // that is the wrong question: the answer to "where did I write about this" is not
                // "somewhere in the last forty saves", and the measured cost of the whole history of
                // a medium project is $0.31 to embed and about seventeen minutes for the first pass.
                // The operator's line: *"our app is supposed to support medium sized projects, not
                // giants"*, and squashing a project that outgrows that is git's job, not ours.
                //
                // **`READ_ALL`, not a large number**, because a number would be a ceiling that
                // pretends to be a bound: `walkChanges` counts commits, and a project with more is a
                // project the person can squash.
                GitService.walkChanges(it, deadline = deadline) { _, changes ->
                    if (stopped != null) return@walkChanges
                    for (change in changes) {
                        if (stopped != null) return@walkChanges
                        if (!change.real) continue
                        if (change.path to change.blob in held) continue
                        // **By blob id, not by path and revision.** `bytesAt` walks a tree to find a
                        // file in a revision, and a blob is not a tree — asking it that way found
                        // nothing and the version was silently skipped.
                        val text = try {
                            GitService.bytesOf(it, change.blob)
                                ?.let { raw -> String(raw, Charsets.UTF_8) }
                        } catch (e: Throwable) {
                            null
                        } ?: continue
                        // **The same exclusions the tree walk makes, made again here.** `gather`
                        // leaves out `agent/` because it holds the log, the fetched pages and this
                        // index; git holds those paths too, for any project that committed before
                        // the ignore list existed or that has one in its own history. Indexing them
                        // from history would put back exactly what the walk refuses — including the
                        // index's own database, which is the recursion the walk names.
                        // **`ws.isHidden`, the app's own rule**, not a second one written here: a
                        // name the listing hides must not come back through the history.
                        val parts = change.path.split('/')
                        if (parts.any { it == AgentLog.DIR || ws.isHidden(it) }) continue
                        // **Text only, by the same rule the tree walk uses.** The check below is
                        // `looksBinary`, which is a *guess* about bytes decoded as UTF-8 - and a PNG
                        // often survives it, because compressed data holds plenty of bytes that are
                        // valid characters. A picture's bytes would be embedded as noise, at 400 KB a
                        // version, and would pollute every answer they appeared in. The kind is a fact
                        // about the name, and it is the one the listing already trusts.
                        if (Workspace.kindOfPath(change.path) != EntryKind.TEXT) continue
                        // **Binary and oversized content is skipped silently here.** A version is not
                        // something a person chose to index, so a failure to take one must not turn
                        // into a note on every answer; the working tree half already reports the
                        // files it could not read, and a version of one of those is the same text.
                        if (GitService.looksBinary(text)) continue
                        if (text.length > MAX_CHARS) continue
                        val put = try {
                            index.put(change.path, change.blob, Chunker.split(text)) { texts ->
                                embedAll(texts, model, dims, deadline)
                            }
                        } catch (e: StoppedException) {
                            stopped = "the time limit ran out while indexing saved versions"
                            return@walkChanges
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                        put.fold(
                            onSuccess = { embedded ->
                                chunks += embedded
                                versions++
                            },
                            onFailure = { e ->
                                stopped = "saved versions could not be indexed: ${reason(e)}"
                            },
                        )
                    }
                }
            }
        } catch (e: StoppedException) {
            stopped = "the time limit ran out while reading the history"
        } catch (e: Throwable) {
            stopped = "the history could not be read: ${reason(e)}"
        }
        return Triple(versions, chunks, stopped)
    }

    /**
     * The project's text files, as files.
     *
     * **Hidden names, the agent's own folder, and anything outside the project are not files to
     * index.** `ws.list` already leaves out the first two kinds it knows — `.git`, a half-written
     * temporary — but `agent/` is an ordinary name holding the log, the fetched pages and this index
     * itself, and indexing the index is the one recursion worth naming rather than discovering.
     */
    private fun gather(
        dir: File,
        root: File,
        depth: Int,
        out: MutableList<File>,
        deadline: Deadline,
    ) {
        if (depth > WALK_DEPTH) return
        for (entry in ws.list(dir)) {
            deadline.check(TOOL)
            if (entry.isFolder) {
                if (entry.name == AgentLog.DIR) continue
                // **Canonical, because a link is the same escape with no `..` in it.** The index is
                // the project; a folder that leaves it is another project's text, and another
                // project is where this search is not allowed to look.
                if (!inside(entry.file, root)) continue
                gather(entry.file, root, depth + 1, out, deadline)
                continue
            }
            if (entry.kind != EntryKind.TEXT) continue
            if (!inside(entry.file, root)) continue
            out.add(entry.file)
        }
    }

    private fun inside(file: File, root: File): Boolean = try {
        val canon = file.canonicalFile
        canon == root || canon.path.startsWith(root.path + File.separator)
    } catch (e: Throwable) {
        false
    }

    private fun relTo(root: File, file: File): String? = try {
        file.canonicalFile.relativeTo(root).invariantSeparatorsPath
    } catch (e: Throwable) {
        null
    }

    /**
     * [texts] embedded in as few requests as the provider allows.
     *
     * ## Why the batch is [BATCH] and not a handful, measured
     *
     * **Two thousand five hundred tokens a second in one request, ten thousand eight hundred in a
     * request of 256** — the cost of an embedding call is dominated by getting to the model, not by
     * the text, so a small batch pays that overhead once per handful of chunks. Measured on
     * `perplexity/pplx-embed-v1-0.6b` with this app's own 800-character chunks:
     *
     * | chunks per request | tokens | time | throughput |
     * |---|---|---|---|
     * | 32 | 4 662 | 1.9 s | 2 470 tok/s |
     * | 128 | 23 826 | 2.9 s | 8 085 tok/s |
     * | **512** | 95 634 | **3.1 s** | **30 837 tok/s** |
     *
     * 1024 is refused outright — `input array exceeds maximum of 512 items` — so **512 is the
     * provider's ceiling, not a guess**, and it is the number this uses.
     *
     * ## Why halving, on a refusal only
     *
     * The ceiling is the provider's, and a different model may have a lower one. Rather than keep a
     * table of each model's limit — which would be wrong the week it changed — a batch that is
     * **refused for its size** is halved and retried, down to one. A batch that fails for any other
     * reason (a dead network, a refused key) is returned at once: retrying that nine times slower
     * would spend a minute to learn what the first answer already said.
     */
    private fun embedAll(
        texts: List<String>,
        model: String,
        dims: Int,
        deadline: Deadline,
    ): Result<List<FloatArray>> {
        val out = mutableListOf<FloatArray>()
        var size = BATCH
        var at = 0
        while (at < texts.size) {
            deadline.check(TOOL)
            val batch = texts.subList(at, minOf(at + size, texts.size))
            val answer = embedder.embed(batch, model, dims)
            answer.fold(
                onSuccess = { got ->
                    if (got.size != batch.size) {
                        return Result.failure(
                            IllegalStateException(
                                "The embedder answered ${got.size} vectors for ${batch.size} chunks.",
                            ),
                        )
                    }
                    out += got
                    at += batch.size
                },
                onFailure = { e ->
                    // **Only a size refusal is worth a second try**, and it is worth exactly
                    // log2(BATCH) of them.
                    if (size > 1 && tooBig(e)) {
                        size /= 2
                    } else {
                        return Result.failure(e)
                    }
                },
            )
        }
        return Result.success(out)
    }

    /**
     * Whether [e] is the provider saying "that request was too large".
     *
     * **Matched on the numbers and words the two shapes actually use** — a bare 413, or a 400 whose
     * message says the array is too long — and nothing else, so a timeout is never mistaken for a
     * size problem and retried as a smaller request that will time out the same way.
     */
    private fun tooBig(e: Throwable): Boolean {
        val chain = generateSequence(e as Throwable?) { it.cause }
            .map { it.message.orEmpty() }
            .joinToString(" ")
            .lowercase()
        if ("413" in chain || "too large" in chain || "too many" in chain) return true
        return "400" in chain && listOf("exceed", "maximum", "too long", "limit").any { it in chain }
    }

    // ---- 2. answering ------------------------------------------------------

    /** One chunk that survived the walk, with the text a reader needs to judge it. */
    private data class Candidate(
        val path: String,
        /** The first line of the chunk. */
        val line: Int,
        /** The last line of the chunk, so the answer can name the range `read` takes. */
        val last: Int,
        val text: String,
        /** The vector's score, or the reranker's when one ran. */
        val score: Double,
        /** The save this text was found in, or `""` for the file as it is now. */
        val commit: String = "",
    )

    private fun search(
        index: SearchIndex,
        scope: Regex?,
        query: String,
        paths: Int,
        depth: Int,
        model: String,
        dims: Int,
        caught: Indexed,
        deadline: Deadline,
        history: Boolean,
    ): Result<String> {
        val root = ws.projectDir(project)
        val notes = mutableListOf<String>()
        // **The extent, in every answer.** A person reading "nothing matches" cannot tell an empty
        // project from a search that only looked in one folder, and the second is this tool's design.
        notes += "$project/ only — semantic search is per project"
        // **What the indexing pass did, also in every answer** — and before the empty case is decided,
        // because the answer that finds nothing is the one that most needs to know its index is
        // incomplete. The first version added these after the search and skipped them on the way out,
        // so a pass that ran out of time answered "nothing is indexed yet" with no reason beside it.
        notes += caughtNotes(index, caught)

        val vector = embedder.embed(listOf(query), model, dims)
            .getOrElse { return Result.failure(it) }
            .firstOrNull()
            ?: return Result.failure(
                IllegalStateException("The embedder answered nothing for the query."),
            )
        // **Thirty, not ten**: the reranker's whole job is to disagree with the vector order, so it
        // has to be shown a few answers it can move. Ten would leave it reordering the top of a list
        // that is already the answer.
        val hits = index.nearest(vector, RERANK_DEPTH, history = history)
            .getOrElse { return Result.failure(it) }
            // **A cosine that is not positive is not a hit.** `nearest` answers with the closest
            // rows whether or not any of them is close, so without this every search would answer
            // thirty files: the ones that matched and a tail of ones that are merely indexed. Zero or
            // below is the mathematical line — the text points somewhere else, or nowhere — and it is
            // a line the code can justify without inventing a number.
            .filter { it.score > 0.0 }
            .filter { withinDepth(it.path, depth) }
            .filter { scope == null || scope.containsMatchIn("$project/${it.path}") }

        if (hits.isEmpty()) {
            val said = if (index.size() == 0) {
                "nothing is indexed in \"$project\" yet"
            } else {
                "nothing in \"$project\" is close to \"$query\""
            }
            return Result.success(compose(listOf(said), notes))
        }

        // **The versions are resolved before anything is read**, because a row whose blob no commit
        // reaches cannot be answered at all — and because resolving them is one walk of the history
        // rather than one per hit.
        val saves = saves(root, hits.map { it.path to it.commit }, notes)
        val cache = mutableMapOf<String, List<String>>()
        var candidates = hits.mapNotNull { hit ->
            // **Thirty files are read here, so this is where a slow disk is felt.** The check is per
            // hit rather than per file, because a cached file costs nothing and a new one may be big.
            deadline.check(TOOL)
            val commit = if (hit.commit.isEmpty()) "" else saves[hit.path to hit.commit] ?: return@mapNotNull null
            val text = chunkText(root, hit.path, hit.start, hit.end, hit.commit, cache)
                ?: return@mapNotNull null
            // **The range, not just the start.** `hit.end` is the chunk's last line, which is what
            // makes `path:12-31:` a directly usable `read {from: 12, to: 31}`.
            Candidate(hit.path, hit.start, hit.end, text, hit.score, commit)
        }
        if (candidates.isEmpty()) {
            // **A row whose text is gone is not an answer.** A file deleted between the index and the
            // query leaves a chunk pointing at nothing; showing its path would send the model to read
            // a file that is not there, with no way to tell why.
            return Result.success(
                compose(
                    listOf(
                        "nothing in \"$project\" is close to \"$query\" — the index points at " +
                            "text that is no longer on disk",
                    ),
                    notes,
                ),
            )
        }

        val rerankModel = rerank()
        if (rerankModel != null && candidates.size > 1) {
            val reranked = embedder.rerank(query, candidates.map { it.text }, rerankModel.model)
            val order = reranked.getOrNull()
            if (order.isNullOrEmpty()) {
                // **Vector order is still a meaning search**, so this is a note rather than a
                // failure — and unlike a text search it answers the question that was asked. The
                // reason travels because a person whose model is misconfigured deserves to see it.
                notes += "the reranker did not reorder these, so they are in vector order: " +
                    reason(reranked.exceptionOrNull())
            } else {
                val before = candidates
                candidates = order.mapNotNull { ranked ->
                    before.getOrNull(ranked.index)?.copy(score = ranked.score)
                }
                if (candidates.isEmpty()) {
                    notes += "the reranker answered with positions this search does not have, so " +
                        "these are in vector order"
                }
            }
        }

        // **One row per place, when the answer spans versions.**
        //
        // `nearest(history = true)` drops the `version = ''` filter, so the current file and every
        // historical version of it compete on score — and the same paragraph scores the same at
        // every sha it was saved under. `paths` then fills with thirteen rows that all say the same
        // thing at thirteen different commits, which is a ranking of *saves* where the question was
        // *where*.
        //
        // **The newest version wins a tie**, because `nearest` answers in descending score order and
        // a stable grouping keeps the first — and when two versions score the same, the one still on
        // disk is the more useful answer. How many were collapsed is said, so a person who wants the
        // earlier saves knows there are some.
        val collapsed = if (!history) {
            0
        } else {
            val before = candidates.size
            // **Keyed by `path@commit`, so a version is never hidden behind the file's current
            // text.** Collapsing on the path alone was wrong in the one case history exists for: a
            // paragraph that was *deleted* is a version row, and the current file has no such text —
            // but the current file can still score on the same words, and keeping "the best per
            // path" would then drop the very save the question was about.
            //
            // So the duplicate this removes is the narrow one that is actually a duplicate: the same
            // path at the same commit, reachable through two different chunks. Different commits are
            // different answers.
            val best = LinkedHashMap<String, Candidate>()
            for (one in candidates) {
                val key = "${one.path}@${one.commit}"
                val there = best[key]
                if (there == null || one.score > there.score) best[key] = one
            }
            candidates = best.values.toList()
            before - candidates.size
        }

        val rows = candidates.take(paths).map { one ->
            // **The whole chunk, not a fifth of it.**
            //
            // This row used to show `excerpt(one.text)` — 160 characters of an 800-character chunk.
            // The cost of that was paid three times over and thrown away: the embedding was computed
            // on all 800, the reranker scored all 800, and then the answer showed the first line.
            // A model reading it had to guess a line range and call `read` again to see the text it
            // had already paid for.
            //
            // **The line range is named**, so that second call is exact rather than a guess:
            // `path:12-31:` is directly `read {path, from: 12, to: 31}`. A one-line chunk is
            // answered as `:12:` the same as a text-mode hit, so the two kinds of row read alike.
            //
            // **`path@sha7` for a saved version**, the same spelling `search {history}` answers in
            // text mode and the same thing `read {revision}` takes — so a row is directly usable as
            // the next call whether it names the file now or the file as it was.
            val where = if (one.commit.isEmpty()) one.path else "${one.path}@${one.commit.take(7)}"
            val range = if (one.last > one.line) "${one.line}-${one.last}" else "${one.line}"
            "$project/$where:$range: ${whole(one.text)}  [${score(one.score)}]"
        }
        if (collapsed > 0) {
            notes += "$collapsed duplicate row(s) of the same text were left out"
        }
        return Result.success(compose(rows, notes))
    }

    /**
     * The lines of one chunk — **from the working tree, or from the version it was found in.**
     *
     * The two are not interchangeable: a hit in a saved version quotes text the file may no longer
     * have, so reading the working tree would show a line that is not what matched. The key carries
     * the blob for that reason, and a version's bytes come from git.
     */
    private fun chunkText(
        root: File,
        path: String,
        start: Int,
        end: Int,
        blob: String,
        cache: MutableMap<String, List<String>>,
    ): String? {
        val key = if (blob.isEmpty()) path else "$path@$blob"
        val lines = try {
            cache.getOrPut(key) {
                if (blob.isEmpty()) {
                    File(root, path).readLines()
                } else {
                    val repo = GitService.openExisting(root) ?: return null
                    repo.use {
                        val raw = GitService.bytesOf(it, blob) ?: return null
                        String(raw, Charsets.UTF_8).lines()
                    }
                }
            }
        } catch (e: Throwable) {
            return null
        }
        if (start < 1 || start > lines.size) return null
        val last = minOf(end, lines.size)
        if (last < start) return null
        return lines.subList(start - 1, last).joinToString("\n")
    }

    /**
     * **The commits behind the saved versions in this answer — resolved once, never stored.**
     *
     * The index is keyed by content, so a version row names a blob. A person (and a model) needs a
     * *save*: "which version do I read?" — and that is a commit id, which [GitService.VersionResolver]
     * answers by walking the history **once** for every pair at once.
     *
     * **A pair no commit reaches is dropped, and said out loud.** A row can outlive the commits that
     * produced it: a prune rewrites the history, a force-pushed remote replaces it, and a collection
     * may then take the objects. Answering with the blob id would hand out an id `read` cannot use —
     * so the row goes, and the note counts them.
     */
    private fun saves(
        root: File,
        pairs: List<Pair<String, String>>,
        notes: MutableList<String>,
    ): Map<Pair<String, String>, String> {
        val wanted = pairs.filter { it.second.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        val repo = try {
            GitService.openExisting(root)
        } catch (e: Throwable) {
            null
        } ?: return emptyMap()
        val found = try {
            repo.use { GitService.VersionResolver(it).resolve(wanted) }
        } catch (e: StoppedException) {
            // **A turn that ran out of time still answered** — with the rows it could resolve and the
            // count of the ones it could not, which is more use than a refusal.
            emptyMap()
        } catch (e: Throwable) {
            emptyMap()
        }
        val lost = wanted.size - found.size
        if (lost > 0) {
            notes += if (found.isEmpty()) {
                "$lost saved version(s) are no longer in this project's history, so they were " +
                    "not answered"
            } else {
                "$lost saved version(s) are no longer in this project's history and were left out"
            }
        }
        return found
    }

    /**
     * Whether the account-relative path this index holds at [path] is inside `depth`.
     *
     * **The same arithmetic the text walk uses**, so `depth` means one thing in both modes: the
     * account is level 0, a project is 1, and a project's own top-level file is 2. The index holds
     * project-relative paths, so the project's own level is the `+ 2` — and the walk's own test is
     * "found with `left > 0` at level `depth`", which is the same statement.
     */
    private fun withinDepth(path: String, depth: Int): Boolean =
        path.count { it == '/' } + 2 <= depth

    /** The chunk's first line that says something, cut to a readable length. */
    /**
     * A chunk as it is, minus the control characters that would break the answer's shape.
     *
     * **Not cut to a length.** A chunk is bounded by `Chunker.CHARS` (800), which is the unit this
     * search works in — showing less of it than was scored is showing an answer to a smaller question
     * than the one that was asked. Newlines become ` \u23ce ` so a row stays one row and a model can
     * still see where the line breaks were.
     */
    private fun whole(text: String): String =
        text.filterNot { it < ' ' && it != '\n' }
            .replace("\n", " \u23ce ")
            // **Trimmed after the substitution, not before.** A chunk usually ends with a newline,
            // which becomes ` ⏎ ` — so trimming first left a marker hanging at the end of every row
            // with nothing after it.
            .trim()
            .removeSuffix("\u23ce")
            .trim()

    private fun excerpt(text: String): String {
        val line = text.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.filterNot { it < ' ' && it != '\t' }
            ?.trim()
        if (line.isNullOrEmpty()) return "empty"
        return if (line.length > EXCERPT) line.take(EXCERPT) + "\u2026" else line
    }

    private fun score(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.2f", value)

    /**
     * What the indexing pass could not take, as notes for the answer.
     *
     * **Two facts, and they are different:** a pass that stopped early has not seen every file, and a
     * file it did see and could not take is missing from an index that otherwise looks complete. A
     * person told neither would trust an answer that is missing their newest work.
     */
    private fun caughtNotes(index: SearchIndex, caught: Indexed): List<String> {
        val notes = mutableListOf<String>()
        // **The same sentence the log and the notification carry**, said twice in different words
        // would be two chances to disagree about one pass.
        if (caught.stopped != null) {
            // **"0 of 0 are left" is not a sentence a person can use.** When the pass stopped before
            // it could name anything, what is true is that the index may not have seen every file.
            notes += if (caught.left > 0) {
                "indexing stopped: ${caught.stopped}; ${caught.left} of ${caught.total} file(s) are " +
                    "not indexed yet, so this answer may be incomplete"
            } else {
                "indexing stopped: ${caught.stopped}, so this index may be missing files"
            }
        }
        if (caught.failed > 0) {
            val why = index.failed().second
            notes += "${caught.failed} file(s) could not be indexed" + (why?.let { ": $it" } ?: "")
        }
        return notes
    }

    private fun compose(rows: List<String>, notes: List<String>): String =
        if (notes.isEmpty()) {
            rows.joinToString("\n")
        } else {
            rows.joinToString("\n") + "\n(" + notes.joinToString("; ") + ")"
        }

    companion object {
        /** What the tool is called, for a [Deadline]'s own sentence. */
        private const val TOOL = "search"

        /** The sentence a person sees when the feature has no model to work with. */
        const val NO_MODEL =
            "No embedding model is chosen, so there is nothing here that can search by meaning. " +
                "Open Settings, choose the row called \"Embedding model\", and pick its dimensions."

        /**
         * How many chunks are shown to the reranker.
         *
         * **About thirty, and the number is the model's, not a preference**: a reranker is shown the
         * query and each document together, so the cost is linear in what is offered and a hundred
         * would be a second's work to reorder a list whose tail nobody reads.
         */
        const val RERANK_DEPTH = 30

        /**
         * Chunks per embedding request — **the provider's own ceiling**, measured; see [embedAll].
         *
         * 512 is where `input array exceeds maximum of 512 items` begins, and it is 3.7× the
         * throughput of 128 because the per-request cost is paid once.
         */
        const val BATCH = 512

        /**
         * The largest file the index will take, in characters.
         *
         * **400 000 characters**, which is comfortably more than the app's own biggest source file
         * (`AppViewModel.kt`, about 250 000) and far less than a log or a dump that would cost more to
         * index than every answer it could ever give. A larger file is recorded as failed with its
         * size, so the note can say which file and why.
         */
        const val MAX_CHARS = 400_000

        /** Folder levels below the project that the walk will follow. A tree, not a graph. */
        const val WALK_DEPTH = 24

        /** How much of a chunk's first line an answer shows. */
        const val EXCERPT = 160

        /** A reason from a [Throwable], never empty, because "null" is not a sentence. */
        private fun reason(e: Throwable?): String {
            if (e == null) return "no reason given"
            val said = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "it failed"
            return Secrets.explain(e, said).ifBlank { said }
        }
    }
}
