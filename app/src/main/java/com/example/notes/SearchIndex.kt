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
) {

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
     * [vectors] is asked for the hashes that are not already stored, and only those are written — so
     * re-indexing a file whose text has not changed costs one query and no embedding at all. That is
     * the same fact as the hash, used from the other side.
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
        val missing = mutableListOf<Chunker.Chunk>()
        val present = mutableSetOf<String>()
        for (chunk in chunks) {
            if (hasVector(chunk.hash)) present.add(chunk.hash) else missing.add(chunk)
        }
        if (missing.isNotEmpty()) {
            val got = vectorFor(missing.map { it.hash }).getOrElse { return Result.failure(it) }
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
        // **`history: false` is a filter, not a second table.** Versions are rows with a commit, so
        // "current files only" is `version = ''` — the same query, one clause.
        val where = if (history) "1 = 1" else "c.version = ''"
        val rows = mutableListOf<Hit>()
        db.rawQuery(
            """
            SELECT c.path, c.version, c.start, c.end, v.vector
            FROM chunks c JOIN vectors v ON v.hash = c.hash
            WHERE $where
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val vector = floats(cursor.getBlob(4))
                if (vector.size != built.dims) continue
                rows.add(
                    Hit(
                        path = cursor.getString(0),
                        commit = cursor.getString(1) ?: "",
                        start = cursor.getInt(2),
                        end = cursor.getInt(3),
                        score = cosine(query, vector),
                    ),
                )
            }
        }
        // **Sorted here rather than in SQL**, because the score is computed in Kotlin — SQL cannot do
        // a cosine over a blob without an extension.
        return Result.success(rows.sortedByDescending { it.score }.take(paths))
    }

    /** How many chunks the index holds, for a screen or a test. */
    fun size(): Int = db.rawQuery("SELECT COUNT(*) FROM chunks", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    /** How many distinct vectors are stored — **fewer than chunks, by design.** */
    fun vectors(): Int = db.rawQuery("SELECT COUNT(*) FROM vectors", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    /** Forgets one file at one version, and any vector no longer referenced. */
    fun forget(path: String, commit: String = "") {
        db.beginTransaction()
        try {
            db.delete("chunks", "path = ? AND version = ?", arrayOf(path, commit))
            // **A vector nothing points at is dead weight**, and collecting it here rather than on a
            // timer keeps the table honest: the count of vectors means "distinct text indexed".
            db.execSQL("DELETE FROM vectors WHERE hash NOT IN (SELECT hash FROM chunks)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Empties the index but keeps its declared model, so a rebuild needs no re-declaration. */
    fun clear() {
        db.beginTransaction()
        try {
            db.delete("chunks", null, null)
            db.delete("vectors", null, null)
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
                put("hash", hash)
                put("vector", bytes(vector))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * A vector as bytes.
     *
     * **Little-endian floats, four bytes each**, because that is what every other reader of such a
     * file expects and what makes a 1024-dimension vector 4096 bytes rather than the ~20 kB its JSON
     * form would take. The order is written down here and read back in [floats], and nowhere else.
     */
    private fun bytes(vector: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vector.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    private fun floats(raw: ByteArray?): FloatArray {
        if (raw == null || raw.size % 4 != 0) return FloatArray(0)
        val buffer = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(raw.size / 4)
        for (i in out.indices) out[i] = buffer.getFloat()
        return out
    }

    companion object {
        /** The database file's name, inside a project's `agent/`. */
        const val FILE = "search.db"

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
                  vector  BLOB NOT NULL
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
