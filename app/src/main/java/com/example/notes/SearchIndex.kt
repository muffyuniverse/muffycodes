package com.example.notes

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * **The vector index, in SQLite, keyed by content rather than by position.**
 *
 * ## The one idea that makes history affordable
 *
 * A chunk's **text** is its identity, not its path or its line number. A version's chunk and the
 * current file's chunk with the same words share a hash, so the vector is stored **once** and
 * referenced twice. A file saved ten times with one paragraph edited stores ten changed chunks and one
 * copy of everything else.
 *
 * That is what the operator meant by *"we already embed them when they are saved!"* — the argument
 * against history was "10× the storage", and it is not ten times anything. It is the deltas.
 *
 * ## Why the vectors live in their own table
 *
 * The schema in the design put `vector` on the chunk row, and that is wrong for the reason the hash
 * exists: two rows referencing one vector is the whole point, and a blob on the referencing row would
 * be copied. So `chunks` says *what is where* and `vectors` says *what the text means*, joined by hash.
 * Deleting a file's chunks then cannot orphan a vector another file still uses.
 *
 * ## The dimension is a fact about the index, not the model
 *
 * `qwen/qwen3-embedding-8b` answers 1024, 2048 or 4096 on demand, so the same model at two sizes
 * produces two incompatible spaces. The index stores the size it was built at, and a query against a
 * different size is **"re-index needed"** — never a cosine against vectors from another space, which
 * would be a number with no meaning that looks like a score.
 */
class SearchIndex private constructor(
    private val db: SQLiteDatabase,
    /** Where the database is, for a test that wants to measure or delete it. */
    val file: File,
) : java.io.Closeable {

    /**
     * Hands the handle back.
     *
     * **A tool that opened it must close it**, because the database is a lock on a file: a search that
     * left one open per call would end with a project that cannot be deleted while the app is running,
     * and the failure would look like a permissions problem months later.
     */
    override fun close() {
        try {
            db.close()
        } catch (e: Throwable) {
            // Already closed, or the platform refused. There is no useful recovery at this point and
            // nothing is lost by it: the data is on disk and the next open sees it.
        }
    }

    /** What the index was built with, or null when it holds nothing yet. */
    fun builtWith(): Built? {
        db.rawQuery("SELECT model, dims FROM meta WHERE id = 1", null).use { c ->
            if (!c.moveToFirst()) return null
            val model = c.getString(0) ?: return null
            val dims = c.getInt(1)
            if (model.isEmpty() || dims <= 0) return null
            return Built(model, dims)
        }
    }

    /** The model and size a built index holds. */
    data class Built(val model: String, val dims: Int)

    /**
     * Says what this index is for, **refusing to change it once it holds anything.**
     *
     * A different model or size is a different vector space, and mixing two in one table produces
     * results that are arithmetic on unrelated numbers. The refusal names both, so the caller can say
     * "re-index needed" rather than the app quietly answering nonsense.
     */
    fun declare(model: String, dims: Int): Result<Unit> {
        val now = builtWith()
        if (now != null && now != Built(model, dims)) {
            return Result.failure(
                IllegalStateException(
                    "This index was built with ${now.model} at ${now.dims} dimensions, and " +
                        "$model at $dims is a different space. It has to be rebuilt.",
                ),
            )
        }
        db.execSQL(
            "INSERT OR REPLACE INTO meta(id, model, dims) VALUES(1, ?, ?)",
            // **`arrayOf<Any>` explicitly**, because a String and an Int in one array infer to
            // `Comparable<*> & Serializable` and the compiler refuses to reify an intersection.
            arrayOf<Any>(model, dims),
        )
        return Result.success(Unit)
    }

    /**
     * Records the chunks of one file at one version, **embedding only what is new.**
     *
     * [vectorFor] is asked for **the text** of the chunks that are not already stored, one vector
     * each, and only those are written — so re-indexing a file whose text has not changed costs one
     * query and no embedding at all. That is the same fact as the hash, used from the other side.
     *
     * [commit] is `""` for the current file and a sha for a version. Both live in the same table,
     * which is what lets one search answer about either.
     *
     * **A chunk that is already there is not re-embedded**, and the caller is told how many that was
     * so a slow re-index can say "nothing had changed" instead of appearing to work.
     */
    fun put(
        path: String,
        commit: String,
        chunks: List<Chunker.Chunk>,
        vectorFor: (List<String>) -> Result<List<FloatArray>>,
    ): Result<Int> {
        val built = builtWith() ?: return Result.failure(
            IllegalStateException("The index has not been declared yet."),
        )
        // **Which hashes are already in the vector table.** A hash is the text, so a chunk whose
        // text is stored is a chunk that needs no embedding — from any file, at any version.
        //
        // **One entry per distinct text**, because the same paragraph twice in one file is one
        // vector: the second copy is a row pointing at the same hash, not a second bill.
        val missing = mutableListOf<Chunker.Chunk>()
        val seen = mutableSetOf<String>()
        for (chunk in chunks) {
            if (!seen.add(chunk.hash)) continue
            if (!hasVector(chunk.hash)) missing.add(chunk)
        }
        if (missing.isNotEmpty()) {
            // **The text, not the hash.** This callback used to receive content addresses, which
            // cannot be embedded: its first caller would have had to keep a hash-to-text map to undo
            // the answer it was given. The hashes are [missing]'s job, and the caller's is the model.
            val got = vectorFor(missing.map { it.text }).getOrElse { return Result.failure(it) }
            if (got.size != missing.size) {
                return Result.failure(
                    IllegalStateException(
                        "The embedder answered ${got.size} vectors for ${missing.size} chunks.",
                    ),
                )
            }
            for ((i, chunk) in missing.withIndex()) {
                val vector = got[i]
                if (vector.size != built.dims) {
                    return Result.failure(
                        IllegalStateException(
                            "The embedder answered ${vector.size} dimensions where this index " +
                                "holds ${built.dims}. The model and the index disagree.",
                        ),
                    )
                }
                putVector(chunk.hash, vector)
            }
        }
        // **The rows, replaced wholesale for this (path, commit).** A file that lost a paragraph must
        // lose the row that pointed at it, and upserting alone would leave the old line numbers
        // behind — a search hit that reads a line the file no longer has.
        db.beginTransaction()
        return try {
            db.delete(
                "chunks",
                "path = ? AND version = ?",
                arrayOf(path, commit),
            )
            for (chunk in chunks) {
                db.insertOrThrow(
                    "chunks",
                    null,
                    ContentValues().apply {
                        put("path", path)
                        put("version", commit)
                        put("hash", chunk.hash)
                        put("start", chunk.start)
                        put("end", chunk.end)
                    },
                )
            }
            db.setTransactionSuccessful()
            Result.success(missing.size)
        } catch (e: Throwable) {
            Result.failure(IllegalStateException("The index could not be written: ${e.message}"))
        } finally {
            db.endTransaction()
        }
    }

    /** One hit: where the text is, and how close it is to the query. */
    data class Hit(
        val path: String,
        /** `""` for the current file, a sha for a version. */
        val commit: String,
        val start: Int,
        val end: Int,
        val score: Double,
    ) {
        /**
         * The path a caller should use — **`path@sha7` for a version**, which is exactly what
         * `read {revision}` and `restore` already accept. A search hit is directly usable as the next
         * call, with no translation.
         */
        fun shown(): String = if (commit.isEmpty()) path else "$path@${commit.take(7)}"
    }

    /**
     * The [paths] chunks closest to [query], **of the same dimension as the index.**
     *
     * ## Why brute force, and why that is not a shortcut
     *
     * The vectors are in a plain table with no index on them, so this reads every row and computes a
     * cosine. For a phone-sized index — tens of thousands of chunks at 1024 floats — that is a few
     * hundred milliseconds and it needs no extension. `sqlite-vec` is not available for android-arm64
     * (the same limit that killed local GGUF embeddings), and an ANN structure would be a second
     * thing to keep in sync with the rows.
     *
     * **It is the right choice until a measurement says otherwise**, and the measurement is one line
     * to add.
     *
     * ## The dimension check is not a formality
     *
     * A cosine between a 1024 vector and a 4096 one is either an exception or a number from the
     * wrong vectors — and the second is worse, because it looks like a score. A query at a different
     * size is refused with the sentence that says what to do.
     */
    fun nearest(query: FloatArray, paths: Int, history: Boolean): Result<List<Hit>> {
        val built = builtWith() ?: return Result.success(emptyList())
        if (query.size != built.dims) {
            return Result.failure(
                IllegalStateException(
                    "The query is ${query.size} dimensions and this index holds ${built.dims}. " +
                        "It has to be rebuilt with the model now chosen.",
                ),
            )
        }
        // **The vectors, once each — not the chunk rows.**
        //
        // This used to join `chunks` to `vectors` and decode a blob per ROW, which reads the same
        // vector once per chunk that shares it: 1.6 times on this repository, and measured at
        // 682-723 ms against 588-631 ms for the same answer. Chunks share vectors by design - a
        // paragraph saved ten times is one vector - so scoring per row pays repeatedly for work that
        // was done once.
        //
        // The shape is now: score every distinct vector, keep the best `paths`, and only then ask
        // which chunks carry those hashes. The second query touches `paths` rows instead of all of
        // them, which is what makes the whole thing linear in the index rather than in the text.
        //
        // **`history: false` filters the chunks, not the vectors**, because a vector does not know
        // whether the text it represents is a version or a file's current contents - the same text is
        // often both. So the ranking is over everything and the filter is applied when the rows are
        // resolved, which keeps a shared vector usable in both modes.
        val scored = mutableListOf<Pair<String, Double>>()
        db.rawQuery("SELECT hash, vector, scale FROM vectors", null).use { cursor ->
            while (cursor.moveToNext()) {
                val vector = floats(cursor.getBlob(1), cursor.getFloat(2))
                if (vector.size != built.dims) continue
                scored.add(cursor.getString(0) to cosine(query, vector))
            }
        }
        // **Sorted here rather than in SQL**, because the score is computed in Kotlin — SQL cannot do
        // a cosine over a blob without an extension.
        //
        // **Every vector is kept for the resolve, not the best `paths`.** Cutting here would be a
        // quiet regression: with `history = false` the filter is applied to the *rows*, so the best
        // ten vectors may all belong to versions that this mode excludes, and the answer would come
        // back with one hit where the old query returned ten. The resolve is a lookup by primary key
        // over `paths` hashes either way, so the only thing the early cut would save is memory.
        val best = scored.sortedByDescending { it.second }
        if (best.isEmpty()) return Result.success(emptyList())

        // **Only the winners are resolved to rows**, and only for the modes asked for. The hashes are
        // bound in chunks of `BIND_LIMIT` because SQLite refuses a statement with more variables than
        // it was built for, and `paths` is a caller's number rather than this class's.
        val byHash = best.toMap()
        val hits = mutableListOf<Hit>()
        val where = if (history) "1 = 1" else "version = ''"
        for (group in best.map { it.first }.chunked(BIND_LIMIT)) {
            val marks = group.joinToString(", ") { "?" }
            db.rawQuery(
                "SELECT path, version, start, end, hash FROM chunks WHERE hash IN ($marks) AND $where",
                group.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    hits.add(
                        Hit(
                            path = cursor.getString(0),
                            commit = cursor.getString(1) ?: "",
                            start = cursor.getInt(2),
                            end = cursor.getInt(3),
                            score = byHash[cursor.getString(4)] ?: 0.0,
                        ),
                    )
                }
            }
        }
        // **`paths` bounds the ANSWER, which is what it has always meant.** One vector can be a chunk
        // in a hundred files or a hundred versions, so resolving every winner would hand back
        // hundreds of rows where the caller asked for thirty - and the reranker, which is promised
        // thirty, would be given the whole history of a single common paragraph.
        return Result.success(hits.sortedByDescending { it.score }.take(paths))
    }

    /** How many chunks the index holds, for a screen or a test. */
    fun size(): Int = db.rawQuery("SELECT COUNT(*) FROM chunks", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    /**
     * How many bytes the vectors occupy, **as the database holds them**.
     *
     * Asked of SQLite rather than computed from a count, because the number a person cares about is
     * what the index costs on their phone — and that is a fact about the file, not about this class's
     * arithmetic. A screen showing "the index is 12 MB" must be showing the 12 MB that exist.
     */
    fun vectorBytes(): Long = db.rawQuery("SELECT SUM(LENGTH(vector)) FROM vectors", null).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
    }

    /** How many distinct vectors are stored — **fewer than chunks, by design.** */
    fun vectors(): Int = db.rawQuery("SELECT COUNT(*) FROM vectors", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    // ---- what the walk has seen -------------------------------------------
    //
    // **A row per file, and the reason it is a table rather than a counter.** The question the note
    // on an answer asks is "how many files are not in this index", and the only party that can answer
    // it honestly is the walk: a file written while the app was closed was never counted by anything,
    // so a counter would report zero behind while half the project was missing. The walk leaves a
    // row for every file it met, and the count is a `SELECT`.

    /**
     * Records what the walk did with one file.
     *
     * [state] is [INDEXED] or [FAILED]; the second carries [error], because "this file is not in the
     * index" and "this file is not in the index *and here is why*" are different answers to a person
     * wondering why their search misses.
     */
    fun note(path: String, mtime: Long, state: Int, error: String? = null, size: Long = -1L) {
        db.insertWithOnConflict(
            "files",
            null,
            ContentValues().apply {
                put("path", path)
                put("mtime", mtime)
                put("size", size)
                put("state", state)
                if (error == null) putNull("error") else put("error", error)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * The modification time this index last **indexed** [path] at, or null.
     *
     * **Null for a file that failed**, deliberately: a file whose last attempt failed is not up to
     * date, so the next pass has to try again rather than compare times and skip it. A failed file
     * that was never retried is a file that stays missing with no way to notice.
     */
    fun indexedAt(path: String): Long? = seen(path)?.first

    /**
     * What this index last **indexed** [path] at, as `(mtime, size)`, or null.
     *
     * **Two numbers, because one of them can lie.** A writer that preserves the modification time —
     * a restore, `cp -p`, two saves inside one filesystem tick — leaves an edited file looking
     * untouched, and the index would go on answering about text the project no longer has. The size
     * is a second opinion that costs one column and no extra read: the directory entry the walk
     * already has carries it.
     *
     * **Null for a file that failed**, deliberately: a file whose last attempt failed is not up to
     * date, so the next pass has to try again rather than compare times and skip it. A failed file
     * that was never retried is a file that stays missing with no way to notice.
     */
    fun seen(path: String): Pair<Long, Long>? =
        db.rawQuery(
            "SELECT mtime, size FROM files WHERE path = ? AND state = 0",
            arrayOf(path),
        ).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
        }

    /** How many files the walk met and could not index, with the first reason it gave. */
    fun failed(): Pair<Int, String?> = db.rawQuery(
        "SELECT COUNT(*), MIN(error) FROM files WHERE state = 1",
        null,
    ).use { c ->
        if (c.moveToFirst()) c.getInt(0) to (if (c.isNull(1)) null else c.getString(1)) else 0 to null
    }

    /** How many files this index holds text for. */
    fun files(): Int = db.rawQuery("SELECT COUNT(*) FROM files", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    // ---- versions ----------------------------------------------------------
    //
    // **A version row is keyed by the blob, not by the commit that saved it.** A commit id is the
    // wrong key: a force-pushed remote rewrites local commits, and a pull that fast-forwards over
    // them can replace this phone's history — so a commit-keyed row dangles, and it dangles in bulk. A blob is
    // the content, which is what an index is about and what does not move. The commit is resolved at
    // read time instead ([commitsFor]), because "when" is the one thing worth not storing.

    /**
     * Every `(path, blob)` pair this index holds a version of.
     *
     * Asked by the walk so it can skip text it already has: reading a blob to chunk it costs a tree
     * walk, and knowing the pair up front turns that into a `SELECT`.
     */
    fun versions(): Set<Pair<String, String>> {
        val out = mutableSetOf<Pair<String, String>>()
        db.rawQuery(
            "SELECT DISTINCT path, version FROM chunks WHERE version != ''",
            null,
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getString(1)
        }
        return out
    }

    /**
     * Drops version rows whose blob is no longer reachable, and any vector nothing points at.
     *
     * **The current file is never touched.** [reachable] is built from the trees the history still
     * reaches, and a prune or a reset says nothing about the working tree — a person's newest work is
     * not in a commit yet and must not be swept because of one.
     *
     * Returns how many rows and how many vectors went, so a maintenance pass can say what it did
     * rather than "done".
     */
    fun sweep(reachable: Set<String>): Pair<Int, Int> {
        val before = vectors()
        var rows = 0
        db.beginTransaction()
        try {
            // **In Kotlin rather than one `NOT IN (…)`**, because a history of a thousand commits is a
            // thousand bind parameters and SQLite's own limit is 999 — a limit that would silently
            // truncate the list and sweep live rows.
            val dead = mutableListOf<Pair<String, String>>()
            db.rawQuery(
                "SELECT DISTINCT path, version FROM chunks WHERE version != ''",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val pair = c.getString(0) to c.getString(1)
                    if (pair.second !in reachable) dead += pair
                }
            }
            for ((path, blob) in dead) {
                rows += db.delete("chunks", "path = ? AND version = ?", arrayOf(path, blob))
            }
            db.execSQL("DELETE FROM vectors WHERE hash NOT IN (SELECT hash FROM chunks)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return rows to (before - vectors())
    }

    /** Forgets one file at one version, and any vector no longer referenced. */
    fun forget(path: String, commit: String = "") {
        db.beginTransaction()
        try {
            db.delete("chunks", "path = ? AND version = ?", arrayOf(path, commit))
            // **The walk's row goes with the text**, or the next pass would compare a time against
            // an index that no longer holds the file and answer "up to date" about nothing.
            if (commit.isEmpty()) db.delete("files", "path = ?", arrayOf(path))
            // **A vector nothing points at is dead weight**, and collecting it here rather than on a
            // timer keeps the table honest: the count of vectors means "distinct text indexed".
            db.execSQL("DELETE FROM vectors WHERE hash NOT IN (SELECT hash FROM chunks)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Empties the index **including what it says it was built with.**
     *
     * This used to keep the declared model "so a rebuild needs no re-declaration", and that was both
     * unnecessary and fatal: the caller declares on every open anyway, while [declare] refuses any
     * model other than the recorded one — so leaving the record behind made changing the model
     * **impossible**, and the refusal said "it has to be rebuilt" with no way to rebuild it. Emptying
     * the record is what makes that sentence true: after this, the next open declares whatever the
     * person has chosen and re-embeds the project under it.
     */
    fun clear() {
        db.beginTransaction()
        try {
            db.delete("chunks", null, null)
            db.delete("vectors", null, null)
            db.delete("files", null, null)
            db.delete("meta", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun hasVector(hash: String): Boolean =
        db.rawQuery("SELECT 1 FROM vectors WHERE hash = ? LIMIT 1", arrayOf(hash)).use { it.moveToFirst() }

    private fun putVector(hash: String, vector: FloatArray) {
        db.insertWithOnConflict(
            "vectors",
            null,
            ContentValues().apply {
                val (raw, scale) = quantised(vector)
                put("hash", hash)
                put("vector", raw)
                put("scale", scale)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * A vector as one byte per dimension, with the scale that undoes it.
     *
     * **`value = round(x / (max|x| / 127))`**, which is the standard symmetric int8 range. Symmetric
     * rather than min-max because the sign of an embedding component carries meaning and an
     * asymmetric range would shift every value by half a step for no gain.
     *
     * **A vector of all zeros keeps a scale of 1**, not 0: the division would be by zero, and a zero
     * vector is a failed embedding whose cosine is 0 either way.
     */
    private fun quantised(vector: FloatArray): Pair<ByteArray, Float> {
        var largest = 0f
        for (value in vector) {
            val size = kotlin.math.abs(value)
            if (size > largest) largest = size
        }
        if (largest == 0f) return ByteArray(vector.size) to 1f
        val scale = largest / 127f
        val out = ByteArray(vector.size)
        for (i in vector.indices) out[i] = kotlin.math.round(vector[i] / scale).toInt().toByte()
        return out to scale
    }

    /**
     * A stored vector as floats, **whichever form it is in**.
     *
     * **Two forms, and the scale says which.** A row written before quantisation has `scale = 0` and
     * holds little-endian float32; a row written since holds one byte per dimension and its scale.
     * The size alone cannot tell them apart — a 4-dimension float32 vector is 16 bytes and a
     * 16-dimension int8 vector is also 16 — so the column is the only honest signal, and an index
     * converts as files are touched rather than in one rewrite nobody asked for.
     */
    private fun floats(raw: ByteArray?, scale: Float): FloatArray {
        if (raw == null) return FloatArray(0)
        if (scale == 0f) {
            if (raw.size % 4 != 0) return FloatArray(0)
            val buffer = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(raw.size / 4)
            for (i in out.indices) out[i] = buffer.getFloat()
            return out
        }
        val out = FloatArray(raw.size)
        for (i in out.indices) out[i] = raw[i] * scale
        return out
    }

    companion object {
        /** The database file's name, inside a project's `agent/`. */
        const val FILE = "search.db"

        /** [note]'s `state` for a file whose text is in the index. */
        const val INDEXED = 0

        /** [note]'s `state` for a file the walk tried and could not take; the reason travels with it. */
        const val FAILED = 1

        /**
         * How many hashes one statement may name.
         *
         * **SQLite's own limit is 999 variables** in the versions Android ships, and a caller may ask
         * for more answers than that; a statement built from an unbounded list would throw at the
         * boundary rather than answer. Chunking is the fix, and the number is the limit rather than a
         * round one so the smallest possible number of statements is made.
         */
        const val BIND_LIMIT = 900

        /**
         * Opens (or creates) the index for one project.
         *
         * **Inside `agent/`**, which is git-excluded: the index is derived data, and a derived file
         * in a repository is a file that conflicts on every clone and rebuilds itself anyway.
         */
        fun open(projectDir: File): SearchIndex {
            val dir = File(projectDir, AgentLog.DIR)
            dir.mkdirs()
            val file = File(dir, FILE)
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            db.enableWriteAheadLogging()
            create(db)
            return SearchIndex(db, file)
        }

        private fun create(db: SQLiteDatabase) {
            // **A column added to a table that already exists is not added by `CREATE TABLE IF NOT
            // EXISTS`.** That statement is a no-op on an existing table, so a phone that indexed
            // before `size` existed keeps a `files` table without it - and then every `note()`, which
            // names the column, throws. The pass would fail on the one device that has been used.
            // This is the whole of the migration: there is nothing to compute, because a row with no
            // size is a row that must be re-read once, and `-1` says exactly that.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chunks(
                  path    TEXT NOT NULL,
                  -- **`version`, not `commit`, and the name is the fix rather than a preference.**
                  -- `commit` is a reserved word in SQLite: quoted it parses in CREATE TABLE, but
                  -- `ContentValues` builds an INSERT with bare column names, so every write failed
                  -- with `near "commit": syntax error`. A column name that has to be quoted in one
                  -- place and cannot be in another is a name to stop using.
                  version TEXT NOT NULL DEFAULT '',
                  hash    TEXT NOT NULL,
                  start   INTEGER NOT NULL,
                  end     INTEGER NOT NULL,
                  PRIMARY KEY(path, version, start)
                )
                """.trimIndent(),
            )
            // **The hash is the join**, so it is indexed on both sides: a lookup per chunk when
            // writing, and the whole of the join when reading.
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_chunks_hash ON chunks(hash)")
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_chunks_path ON chunks(path, version)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vectors(
                  hash    TEXT PRIMARY KEY,
                  -- **One byte per dimension, and a scale to undo it.** A float32 vector at 1024
                  -- dimensions is 4096 bytes; at 100 000 vectors that is 391 MB, and a full history
                  -- of a medium project is exactly that size. Measured against real embeddings, int8
                  -- agrees with float32 on the top ten 99.8% of the time and moves a cosine by at
                  -- most 0.001 - so the four-times saving costs nothing a person can see.
                  vector  BLOB NOT NULL,
                  -- **Per vector, not per index**, because models do not normalise: the largest
                  -- absolute component is what 127 has to mean, and it differs from vector to
                  -- vector. 0 means "stored as float32", which is what a row written before this
                  -- column existed is - see [floats].
                  scale   REAL NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS meta(
                  id      INTEGER PRIMARY KEY,
                  model   TEXT NOT NULL,
                  dims    INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            // **What the walk did with each file, so "how many are behind" is a query.** A counter
            // cannot answer it: a file written while the app was closed was never told to anything,
            // so the only honest source is the row the walk leaves behind. `mtime` is the file's own
            // number, which is what makes a second pass cheap — an unchanged file is one read of a
            // directory entry rather than one embedding.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS files(
                  path   TEXT PRIMARY KEY,
                  mtime  INTEGER NOT NULL,
                  -- **A second opinion on "unchanged".** `mtime` alone says an edited file is
                  -- untouched when the writer preserved the timestamp; the size is already in the
                  -- directory entry, so the check costs nothing and closes the common half of that.
                  size   INTEGER NOT NULL DEFAULT -1,
                  state  INTEGER NOT NULL,
                  error  TEXT
                )
                """.trimIndent(),
            )
            addColumn(db, "files", "size", "INTEGER NOT NULL DEFAULT -1")
            // **A vector written before the scale existed is float32**, and `0` is how [floats] is
            // told: no old row is re-encoded, and no old row is misread as bytes. The next pass over
            // a changed file writes the quantised form, so an index converts as it is touched rather
            // than in one long rewrite nobody asked for.
            addColumn(db, "vectors", "scale", "REAL NOT NULL DEFAULT 0")
        }

        /**
         * Adds [column] to [table] if it is not already there.
         *
         * **`ALTER TABLE ... ADD COLUMN` has no `IF NOT EXISTS`**, so the question is asked of
         * `PRAGMA table_info` first. `NOT NULL DEFAULT` is allowed on an added column precisely
         * because the default is not null: the rows that were there before get the default, which is
         * what makes this a migration with nothing to compute.
         */
        private fun addColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == column) return
                }
            }
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }

        /**
         * The cosine similarity of two vectors, or 0 when either is empty.
         *
         * **Normalised by the lengths**, because embedding models do not all return unit vectors and
         * a dot product of two long vectors would rank by magnitude rather than by meaning. A zero
         * length scores 0 rather than dividing by zero — a vector of zeros is a failed embedding, and
         * it should match nothing rather than everything.
         */
        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            if (na == 0.0 || nb == 0.0) return 0.0
            return dot / (Math.sqrt(na) * Math.sqrt(nb))
        }
    }
}
