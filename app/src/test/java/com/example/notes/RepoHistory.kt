package com.example.notes

import org.eclipse.jgit.api.Git
import java.io.File

/**
 * A project's repository, read from the outside — the assertions' own window into a history.
 *
 * **It exists because the app no longer has a second history.** There used to be two: each
 * project's repository, and an account-wide one for everything outside them. The projects are the
 * folders now and every file below a project is in that project's repository, so a test that wants
 * to ask "what does the history hold" asks one repository.
 *
 * Nothing here writes. The app commits through [ProjectGit]; this reads the same repository with the
 * same [GitService] calls, so an assertion and the screen cannot be told two different stories about
 * what is in a version.
 */
class RepoHistory(private val dir: File) {

    private fun open() = GitService.initOrOpen(dir, GitAuthor("Test", "test@example.com"))

    /** Every commit, newest first, or those that touched [path]. */
    fun log(path: String? = null, limit: Int = 100): List<GitService.CommitInfo> {
        if (!GitService.isRepository(dir)) return emptyList()
        return open().use {
            try {
                GitService.log(it, path, limit)
            } catch (e: Throwable) {
                emptyList()
            }
        }
    }

    /** One file's text at one version. */
    fun fileAt(path: String, revisionId: String): String? {
        if (!GitService.isRepository(dir)) return null
        return open().use {
            try {
                GitService.fileAt(it, path, revisionId)
            } catch (e: Throwable) {
                null
            }
        }
    }

    /** The newest commit, or null when there is none. */
    fun head(): String? = log(null, 1).firstOrNull()?.id

    /** Every path the newest version holds. */
    fun tracked(): List<String> {
        val newest = head() ?: return emptyList()
        return open().use {
            try {
                GitService.pathsUnder(it, null, newest)
            } catch (e: Throwable) {
                emptyList()
            }
        }
    }
}
