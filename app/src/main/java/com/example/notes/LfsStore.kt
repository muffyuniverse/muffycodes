package com.example.notes

import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File

/**
 * The bytes git-LFS keeps beside the repository, and how to reclaim the ones nothing points at.
 *
 * **`git gc` does not know this directory exists.** It collects the object database — commits, trees,
 * blobs — and `.git/lfs/objects` is not in that database: it is a content-addressed store the filter
 * writes to and the smudge reads from. Measured on this box: after `gc`, the store still held every
 * version of every picture that had ever been saved (200 KB → 400 KB → 600 KB → 800 KB, one per save,
 * none collected).
 *
 * So a picture needs **two** deletions, not one: the pointer leaves the tree when the picture is
 * deleted, and this reclaims the bytes nothing names any more. There is no rewrite involved — no
 * commit is rebuilt and no id changes — which is why a picture can be deleted without the history
 * being touched at all.
 *
 * **Reachability is over pointer oids, not over paths.** A pointer names its bytes by SHA-256, and
 * that name is what the store is keyed by — so the question "is this object still used" is answered by
 * walking every tree of every commit and collecting the oids in the pointers found there. A path-based
 * rule would have to guess which files are pointers; this one reads them.
 */
object LfsStore {

    /** Where a repository's LFS objects live. `objects/<first two>/<next two>/<rest>`. */
    fun dirOf(repo: Repository): File = File(repo.directory, "lfs/objects")

    /**
     * Every LFS object id that some commit reachable from HEAD still points at.
     *
     * **Every tree, not only the newest.** A commit's own tree is what the rewrite changes, and a
     * commit that was not rewritten still names the bytes it always did — so the union over all
     * commits is the honest answer. This is also why the rewrite has to happen before the sweep:
     * an unreachable commit would otherwise keep its objects alive.
     */
    fun reachable(repo: Repository): Set<String> {
        val out = mutableSetOf<String>()
        // **`HEAD`, not a branch name.** `fullBranch` answers the *symbolic* ref when HEAD is
        // detached and the short name otherwise, and `findRef` on the short name fails — so a
        // repository between two states answers "nothing is reachable", which the sweep treats as
        // "delete everything". `HEAD` always resolves to a commit when there is one.
        val head = try {
            repo.resolve("HEAD")
        } catch (e: Throwable) {
            null
        } ?: return out
        // **A commit id, walked directly** — no ref object needed, and nothing to fail to resolve.
        val tip = try {
            RevWalk(repo).use { it.parseCommit(head) }
        } catch (e: Throwable) {
            null
        } ?: return out
        try {
            RevWalk(repo).use { walk ->
                walk.markStart(tip)
                for (commit in walk) {
                    TreeWalk(repo).use { tw ->
                        tw.addTree(commit.tree)
                        tw.isRecursive = true
                        while (tw.next()) {
                            val loader = try {
                                repo.open(tw.getObjectId(0))
                            } catch (e: Throwable) {
                                continue
                            }
                            // **Only files small enough to BE a pointer.** A pointer is about 130
                            // bytes; opening every blob in a large repository to read three lines
                            // would make the sweep cost more than the space it reclaims.
                            if (loader.size > POINTER_LIMIT) continue
                            val oid = try {
                                loader.bytes.let { bytes ->
                                    LfsPointer.parseLfsPointer(bytes.inputStream())?.oid?.name
                                }
                            } catch (e: Throwable) {
                                null
                            }
                            if (oid != null) out += oid
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // A repository that could not be walked keeps everything. Deleting bytes on a partial
            // answer would destroy pictures, which is worse than keeping them.
            return out
        }
        return out
    }

    /**
     * Deletes every stored object no commit points at, and answers how many bytes that freed.
     *
     * **The deletion is by name, which is the only safe way.** Each stored file is named by its own
     * oid, so a file whose name is not in [reachable] is by definition unreferenced — no reading of
     * the content, and no chance of deleting the wrong one through a path guess.
     */
    fun sweep(repo: Repository): Long {
        val root = dirOf(repo)
        if (!root.isDirectory) return 0L
        val keep = reachable(repo)
        // **An empty answer is not proof that nothing is used.** If the store holds objects and no
        // commit names any of them, the likelier explanation is that the pointers could not be read
        // — a repository whose filter was never registered stores pictures whole, so its trees hold
        // no pointers at all while its store is full. Deleting on that answer would destroy every
        // picture in the project. So the sweep refuses and says it did nothing.
        if (keep.isEmpty() && root.walkTopDown().any { it.isFile }) return -1L
        var freed = 0L
        // Two levels deep, which is the layout: objects/ab/cd/abcdef...
        root.listFiles()?.forEach { first ->
            first.listFiles()?.forEach { second ->
                second.listFiles()?.forEach { stored ->
                    val oid = first.name + second.name + stored.name
                    if (oid in keep) return@forEach
                    val size = stored.length()
                    if (stored.delete()) freed += size
                }
                // An empty fan-out folder is litter, and harmless to remove.
                if (second.listFiles()?.isEmpty() == true) second.delete()
            }
            if (first.listFiles()?.isEmpty() == true) first.delete()
        }
        return freed
    }

    /** How many bytes the store holds, so a person can be told what it costs. */
    fun sizeOf(repo: Repository): Long {
        val root = dirOf(repo)
        if (!root.isDirectory) return 0L
        var total = 0L
        root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /**
     * The largest a pointer can be and still be worth parsing.
     *
     * A real pointer is 130-ish bytes; the limit is generous so a differently-formatted one still
     * parses, and small enough that a repository of large pictures costs almost nothing to walk.
     */
    const val POINTER_LIMIT = 1_024L
}
