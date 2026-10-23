package com.example.notes

import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * Manages the git repositories of the app folders.
 *
 * Every app folder is a directory, and a folder that is attached to a git
 * project has a repository in that directory. The project simply tracks the
 * files of that folder, so committing a folder commits the files inside it.
 *
 * The class takes its secrets and its identity as functions rather than
 * reading them itself, which keeps it free of Android dependencies and lets
 * the JVM tests drive it directly.
 */
class GitProjects(
    /** The directory that holds the folder directories. */
    private val rootDir: File,
    /** The personal access token, when the account is set up. */
    private val tokenProvider: () -> String?,
    /** The identity used for commits. */
    private val authorProvider: () -> GitAuthor,
) {

    /** The directory of the folder with this id (null means the top level). */
    fun dirFor(folderId: String?): File =
        if (folderId == null) rootDir else File(rootDir, folderId)

    /** True when the folder has a git repository. */
    fun isRepository(folderId: String?): Boolean =
        GitService.isRepository(dirFor(folderId))

    /**
     * Opens the repository of the folder, or null when it has none.
     */
    fun openRepository(folderId: String?): Repository? {
        val dir = dirFor(folderId)
        if (!GitService.isRepository(dir)) return null
        return try {
            GitService.initOrOpen(dir, authorProvider())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Makes the folder a git repository. It is created with a first (empty)
     * commit so that it has a history to browse straight away.
     */
    fun initRepository(folderId: String?): Result<Unit> {
        return try {
            val dir = dirFor(folderId)
            dir.mkdirs()
            GitService.initOrOpen(dir, authorProvider()).close()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Stages and commits every change in the folder. Returns the new commit id,
     * or null when there was nothing to commit.
     */
    fun commit(folderId: String?, message: String): Result<String?> {
        val repo = openRepository(folderId) ?: return Result.failure(
            IllegalStateException("This folder is not a git project yet")
        )
        return repo.use {
            try {
                Result.success(GitService.commitAll(it, message, authorProvider()))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** How many changes are waiting to be committed in the folder. */
    fun changes(folderId: String?): GitService.Changes {
        val repo = openRepository(folderId) ?: return GitService.Changes(0, 0, 0)
        return repo.use {
            try {
                GitService.status(it)
            } catch (e: Exception) {
                GitService.Changes(0, 0, 0)
            }
        }
    }

    /** Pushes the folder's branch to its remote. */
    fun push(folderId: String?): GitService.RemoteResult {
        val repo = openRepository(folderId) ?: return GitService.RemoteResult(
            "This folder is not a git project yet", false
        )
        return repo.use { GitService.push(it, tokenProvider()) }
    }

    /** Pulls the latest changes into the folder. */
    fun pull(folderId: String?): GitService.RemoteResult {
        val repo = openRepository(folderId) ?: return GitService.RemoteResult(
            "This folder is not a git project yet", false
        )
        return repo.use { GitService.pull(it, tokenProvider()) }
    }

    /** The commits that touched [path] inside the folder, newest first. */
    fun history(folderId: String?, path: String?): List<GitService.CommitInfo> {
        val repo = openRepository(folderId) ?: return emptyList()
        return repo.use {
            try {
                GitService.log(it, path)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * The versions of a file inside the folder, newest first, each with the
     * contents of the file at that commit.
     */
    fun versions(folderId: String?, path: String): List<Pair<GitService.CommitInfo, String?>> {
        val repo = openRepository(folderId) ?: return emptyList()
        return repo.use {
            try {
                GitService.versionsOf(it, path)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** The contents of a folder inside the project at one revision. */
    fun treeAt(folderId: String?, path: String?, revisionId: String?): List<GitService.TreeEntry> {
        val repo = openRepository(folderId) ?: return emptyList()
        return repo.use {
            try {
                GitService.treeAt(it, path, revisionId)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** The contents of a file inside the project at one revision. */
    fun fileAt(folderId: String?, path: String, revisionId: String?): String? {
        val repo = openRepository(folderId) ?: return null
        return repo.use {
            try {
                GitService.fileAt(it, path, revisionId)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Puts an old version of a file back, ready to be reviewed and committed. */
    fun restore(folderId: String?, path: String, revisionId: String): Result<Unit> {
        val repo = openRepository(folderId) ?: return Result.failure(
            IllegalStateException("This folder is not a git project yet")
        )
        return repo.use {
            try {
                GitService.restore(it, path, revisionId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Clones a remote into the folder. The folder must not already have a
     * repository.
     */
    fun cloneInto(folderId: String?, remoteUrl: String): GitService.RemoteResult {
        val dir = dirFor(folderId)
        if (GitService.isRepository(dir)) {
            return GitService.RemoteResult("This folder is already a git project", false)
        }
        // The token travels as credentials inside the clone, never inside the
        // address: the address is written to .git/config and must stay clean.
        val result = GitService.clone(remoteUrl, dir, authorProvider(), tokenProvider())
        if (result.success) {
            openRepository(folderId)?.use { repo ->
                GitService.scrubRemoteUrls(repo)
            }
        }
        return result
    }

    /** Sets the remote of the folder's repository. */
    fun setRemote(folderId: String?, remoteUrl: String): Result<Unit> {
        val repo = openRepository(folderId) ?: return Result.failure(
            IllegalStateException("This folder is not a git project yet")
        )
        return repo.use {
            try {
                GitService.setRemoteUrl(it, remoteUrl)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** The remote of the folder's repository, or null. */
    fun remoteUrl(folderId: String?): String? {
        val repo = openRepository(folderId) ?: return null
        return repo.use { GitService.remoteUrl(it) }
    }
}
