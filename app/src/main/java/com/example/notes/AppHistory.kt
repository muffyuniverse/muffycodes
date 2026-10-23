package com.example.notes

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * The account's whole tree, as a history of this phone.
 *
 * This is the second history of §4 of the design: **every** change outside a
 * project's `repo/` is committed here automatically — a saved edit, a new file,
 * a rename, a move, a deletion, a project's own name. It has no remote and takes
 * no key, so nothing it remembers can leave the phone.
 *
 * Project repositories are excluded (`.gitignore`), so no content is stored
 * twice and the two histories never disagree about a file.
 */
class AppHistory(
    /** The account directory, which is also the repository. */
    private val accountDir: File,
    private val authorProvider: () -> GitAuthor,
) {

    private val gitignore: File get() = File(accountDir, ".gitignore")

    /** The ignore file that keeps project repositories out of this history. */
    private fun writeIgnoreFile() {
        val wanted = buildString {
            appendLine("# The app's own bookkeeping.")
            appendLine(".muffy/")
            appendLine("# A project's repository is its own history; it is not repeated here.")
            // Both forms are needed. `repo/` matches what is inside the folder,
            // but the folder itself is a working tree with its own `.git`, and git
            // would otherwise record that folder as a *submodule* — a pointer to a
            // commit that lives in the other repository. Reading the account's
            // history then reaches for an object it does not have, and the whole
            // history becomes unreadable.
            appendLine("${Workspace.PROJECTS}/*/${Workspace.REPO}")
            appendLine("${Workspace.PROJECTS}/*/${Workspace.REPO}/")
            appendLine("# A half-written file, if the app stops mid-save.")
            appendLine(".*.tmp")
        }
        if (!gitignore.isFile || gitignore.readText() != wanted) {
            gitignore.writeText(wanted)
        }
    }

    /** What went wrong the last time the history tried to record something. */
    var lastError: String? = null
        private set

    /** Opens the history, creating it the first time the account is used. */
    fun open(): Repository {
        // The folder belongs to the account, and the account made it. A history
        // that arrives after its account was removed must not bring it back.
        if (!accountDir.isDirectory) {
            throw IllegalStateException("The account is no longer on this phone")
        }
        if (!GitService.isRepository(accountDir)) {
            Git.init().setDirectory(accountDir).setInitialBranch("main").call().close()
        }
        writeIgnoreFile()
        return org.eclipse.jgit.storage.file.FileRepositoryBuilder()
            .setGitDir(File(accountDir, ".git"))
            .setWorkTree(accountDir)
            .build()
    }

    /**
     * Commits whatever changed, with [message]. Returns the new commit's id, or
     * null when nothing had changed.
     */
    fun commit(message: String, keep: Int = 0): String? {
        // An account can be removed while a commit is still on its way to this
        // method. Opening the history would then make the folder again, and the
        // account would come back from the dead.
        if (!accountDir.isDirectory) return null
        val repo = try {
            open()
        } catch (e: Exception) {
            lastError = e.message
            return null
        }
        return repo.use {
            val id = try {
                GitService.commitAll(it, message, authorProvider())
            } catch (e: Exception) {
                lastError = Secrets.scrub(e.message ?: "Could not record the change")
                null
            } ?: return@use null
            pruneIfNeeded(it, keep)
            id
        }
    }

    /** Applies the retention setting, if one is set. */
    fun pruneIfNeeded(repo: Repository, keep: Int) {
        if (keep <= 0) return
        try {
            HistoryPrune.prune(repo, keep)
        } catch (e: Exception) {
            // A history that could not be shortened is still a working history.
        }
    }

    /** The recent commits of the account, newest first. */
    fun log(path: String? = null, limit: Int = 100): List<GitService.CommitInfo> {
        if (!GitService.isRepository(accountDir)) return emptyList()
        val repo = open()
        return repo.use {
            try {
                GitService.log(it, path, limit)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** The text of a file at a past commit, or null when it is not there. */
    fun fileAt(path: String, revisionId: String): String? {
        if (!GitService.isRepository(accountDir)) return null
        val repo = open()
        return repo.use {
            try {
                GitService.fileAt(it, path, revisionId)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Writes a file's past contents back over the working copy. */
    fun restore(path: String, revisionId: String): Boolean {
        if (!GitService.isRepository(accountDir)) return false
        val repo = open()
        return repo.use {
            try {
                GitService.restore(it, path, revisionId)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /** The id of the newest commit, or null when nothing has been recorded. */
    fun head(): String? = log(null, 1).firstOrNull()?.id

    /**
     * Every path the newest version holds.
     *
     * Asked through the history rather than through JGit directly, so that a test
     * asking the question gets the app's own answer — and the app's own error when
     * there is one.
     */
    fun tracked(): List<String> {
        if (head() == null) return emptyList()
        if (!GitService.isRepository(accountDir)) return emptyList()
        val repo = try {
            open()
        } catch (e: Exception) {
            lastError = Secrets.scrub(e.message ?: "Could not open the history")
            return emptyList()
        }
        return repo.use {
            try {
                // HEAD rather than an id read a moment earlier: a commit's objects
                // and the reference that points at them do not appear at the same
                // instant, so an id can briefly name something not yet on disk.
                GitService.filesUnder(it, null, null).map { file -> file.first }
            } catch (e: Exception) {
                lastError = Secrets.scrub(e.message ?: "Could not read the newest version")
                emptyList()
            }
        }
    }

    /** Every file under a folder at a past commit, with its text. */
    fun filesUnder(path: String?, revisionId: String): List<Pair<String, String>> {
        if (!GitService.isRepository(accountDir)) return emptyList()
        val repo = open()
        return repo.use {
            try {
                GitService.filesUnder(it, path, revisionId)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** The contents of a folder at a past commit; a blank path means the root. */
    fun treeAt(path: String?, revisionId: String): List<GitService.TreeEntry> {
        if (!GitService.isRepository(accountDir)) return emptyList()
        val repo = open()
        return repo.use {
            try {
                GitService.treeAt(it, path, revisionId)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * The message for a commit, written from what changed (§8 of the design).
 *
 * Names are what the eye scans for when looking for the version to restore, so
 * the message carries them rather than a count: `Milk.txt, Shop.png`, and
 * `+3 more` once there are too many to read at a glance.
 */
object CommitMessage {

    /** How many names are spelled out before the rest become a count. */
    const val NAMES_SHOWN = 3

    /**
     * The message for a set of changed paths. [verb] lets the same shape serve
     * the account's history (`Edited`, `Created`) as well as a sync.
     */
    fun of(verb: String, paths: List<String>): String {
        val names = paths
            .map { it.trim('/').substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .distinct()
        if (names.isEmpty()) return verb
        val shown = names.take(NAMES_SHOWN).joinToString(", ")
        val rest = names.size - NAMES_SHOWN
        return if (rest > 0) "$verb $shown +$rest more" else "$verb $shown"
    }

    /** The message for an ordinary sync: the names that changed. */
    fun sync(paths: List<String>): String = of("Changed", paths)
}
