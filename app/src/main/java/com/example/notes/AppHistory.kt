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
            appendLine("${Workspace.PROJECTS}/*/${Workspace.REPO}/")
            appendLine("# A half-written file, if the app stops mid-save.")
            appendLine(".*.tmp")
        }
        if (!gitignore.isFile || gitignore.readText() != wanted) {
            gitignore.writeText(wanted)
        }
    }

    /** Opens the history, creating it the first time the account is used. */
    fun open(): Repository {
        accountDir.mkdirs()
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
        val repo = open()
        return repo.use {
            val id = try {
                GitService.commitAll(it, message, authorProvider())
            } catch (e: Exception) {
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
