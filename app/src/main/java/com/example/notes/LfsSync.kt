package com.example.notes

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File

/**
 * **The order two things have to happen in, which is the whole of this class.**
 *
 * A pointer names its bytes by SHA-256, and GitHub refuses a push whose tree references an object it
 * does not hold (`GH008`). So the bytes must arrive **before** the ref that names them — upload first,
 * push second. Doing it the other way round is not a slow path, it is a rejected push.
 *
 * On the way in the order is the same shape: fetch, then download the objects the fetched tree names,
 * and only then check out. JGit's smudge reads `.git/lfs/objects` and downloads what is missing — but
 * over HTTPS on a private repository it cannot authenticate, so it downloads nothing and **leaves the
 * pointer in the working tree without an error**. [missing] is what turns that silence into a sentence.
 */
object LfsSync {

    /** What a transfer did, so a caller can say "3 of 5 arrived" rather than "failed". */
    data class Done(
        val sent: Int = 0,
        val received: Int = 0,
        val failed: Int = 0,
        val why: String? = null,
    ) {
        val ok: Boolean get() = failed == 0 && why == null
    }

    /**
     * Sends every object the repository's commits name that GitHub does not already hold.
     *
     * **Called before the push.** [oids] is the union over the commits being sent, which is what
     * [LfsStore.reachable] already computes; the batch call then says which of them are missing, and
     * only those are uploaded.
     */
    fun push(repo: Repository, token: String?, remoteUrl: String?): Done {
        val endpoint = remoteUrl?.let { LfsClient.endpointOf(it) }
            ?: return Done(why = "This project has no GitHub address to send pictures to")
        if (token.isNullOrBlank()) {
            // **Named rather than attempted.** An anonymous upload answers 401 and would read as a
            // network problem; the real cause is that nobody is signed in.
            return Done(why = "Signing in again is needed before pictures can be sent")
        }
        val stored = storedObjects(repo)
        if (stored.isEmpty()) return Done()
        val objects = stored.map { it.name to it.length() }
        val batch = LfsClient.batch(endpoint, token, "upload", objects).getOrElse {
            return Done(why = it.message)
        }
        var sent = 0
        var failed = batch.failed.size
        var why: String? = null
        for ((oid, _) in objects) {
            // **No action means GitHub already has it**, which is the common case after a re-sync.
            val action = batch.actions[oid] ?: continue
            val file = fileOf(repo, oid) ?: continue
            val result = LfsClient.upload(action, file.readBytes())
            if (result.isSuccess) sent++ else {
                failed++
                if (why == null) why = result.exceptionOrNull()?.message
            }
        }
        return Done(sent = sent, failed = failed, why = why)
    }

    /**
     * Fetches every object the current tree names that the store does not hold.
     *
     * **Called after a fetch and before checkout**, so the smudge finds the bytes already there. That
     * is the step JGit skips on a private repository, and skipping it is the measured bug: 127 bytes
     * on disk where the picture should be, no error anywhere.
     */
    fun pull(repo: Repository, token: String?, remoteUrl: String?): Done {
        val endpoint = remoteUrl?.let { LfsClient.endpointOf(it) }
            ?: return Done(why = "This project has no GitHub address to fetch pictures from")
        if (token.isNullOrBlank()) return Done(why = "Signing in again is needed before pictures can be fetched")
        val wanted = pointersIn(repo)
        // **One entry per object, not per path.** The same bytes can be two files, and the store holds
        // them once; downloading per path would fetch the same object twice.
        val absent = wanted.filter { fileOf(repo, it.oid)?.isFile != true }.distinctBy { it.oid }
        if (absent.isEmpty()) return Done()
        val batch = LfsClient.batch(endpoint, token, "download", absent.map { it.oid to it.size })
            .getOrElse { return Done(why = it.message) }
        var received = 0
        var failed = batch.failed.size
        var why: String? = null
        for (pointer in absent) {
            val action = batch.actions[pointer.oid] ?: continue
            val target = fileOf(repo, pointer.oid) ?: continue
            val result = LfsClient.download(action, target, pointer.size)
            if (result.isSuccess) received++ else {
                failed++
                if (why == null) why = result.exceptionOrNull()?.message
            }
        }
        return Done(received = received, failed = failed, why = why)
    }

    /**
     * Every path in the working tree that is **still a pointer** — which means its bytes never
     * arrived.
     *
     * **The check that makes a silent failure impossible.** Called after any checkout; a non-empty
     * answer is a sentence to the person, never a picture that will not open.
     */
    fun missing(repo: Repository): List<String> {
        // **A bare repository has no working tree**, and a pointer is a fact about a file on disk —
        // so there is nothing here to be missing.
        val workTree = repo.workTree ?: return emptyList()
        val dir: File = workTree
        val out = mutableListOf<String>()
        for (pointer in pointersIn(repo)) {
            if (fileOf(repo, pointer.oid)?.isFile == true) continue
            // Only the paths whose file on disk is still the pointer text — a file that was never
            // checked out is missing for a different reason and is not this function's question.
            val file = File(dir, pointer.path)
            if (file.isFile && looksLikePointer(file)) out += pointer.path
        }
        return out.sorted()
    }

    /** One pointer in the tree: the object it names, how big that object is, and where it is. */
    data class Pointer(val oid: String, val size: Long, val path: String)

    /**
     * Every pointer in `HEAD`'s tree — the set both directions work from.
     *
     * Read from the tree rather than from disk, because the working tree may hold bytes for a pointer
     * and the question "what does this project contain" is answered by what is committed.
     */
    fun pointersIn(repo: Repository): List<Pointer> {
        val head = repo.resolve("HEAD") ?: return emptyList()
        val out = mutableListOf<Pointer>()
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(head)
            TreeWalk(repo).use { tw ->
                tw.addTree(commit.tree)
                tw.isRecursive = true
                while (tw.next()) {
                    val loader = try {
                        repo.open(tw.getObjectId(0))
                    } catch (e: Throwable) {
                        continue
                    }
                    // **Only files small enough to BE a pointer.** A pointer is about 130 bytes;
                    // opening every blob in the project to read three lines would make this cost
                    // more than the transfer it saves.
                    if (loader.size > LfsStore.POINTER_LIMIT) continue
                    val pointer = try {
                        org.eclipse.jgit.lfs.LfsPointer.parseLfsPointer(loader.bytes.inputStream())
                    } catch (e: Throwable) {
                        null
                    }
                    if (pointer != null) {
                        out += Pointer(pointer.oid.name, pointer.size, tw.pathString)
                    }
                }
            }
        }
        return out
    }

    /** Where the store keeps one object: `lfs/objects/<ab>/<cd>/<oid>`. */
    fun fileOf(repo: Repository, oid: String): File? {
        if (oid.length < 4) return null
        return File(LfsStore.dirOf(repo), "${oid.take(2)}/${oid.substring(2, 4)}/$oid")
    }

    /** Every file the store holds, which is what an upload has to consider. */
    private fun storedObjects(repo: Repository): List<File> {
        val root = LfsStore.dirOf(repo)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile }.toList()
    }

    /** True when the file's first bytes are a pointer header rather than a picture. */
    private fun looksLikePointer(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val head = ByteArray(64)
            val read = stream.read(head)
            read > 0 && String(head, 0, read).startsWith("version https://git-lfs")
        }
    } catch (e: Throwable) {
        false
    }
}
