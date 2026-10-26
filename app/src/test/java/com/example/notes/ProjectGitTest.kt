package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A project's git side (§8): the sync writes its message from the names that
 * changed, the force push really replaces the remote, and the history length of
 * a project is kept the same way the account's is.
 */
class ProjectGitTest {

    private val author = GitAuthor("Test", "test@example.com")
    private lateinit var root: File
    private lateinit var workspace: Workspace
    private lateinit var bare: File
    private var keep = 0

    private fun gitFor(token: String? = null) = ProjectGit(
        workspace = workspace,
        tokenProvider = { token },
        authorProvider = { author },
        keepProvider = { keep },
    )

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "projectgit-${System.nanoTime()}")
        workspace = Workspace(File(root, "muffyuniverse"))
        workspace.accountDir.mkdirs()
        workspace.accountDir.mkdirs()

        // A bare repository is what a push target really is.
        bare = File(root, "remote.git")
        org.eclipse.jgit.api.Git.init()
            .setDirectory(bare)
            .setBare(true)
            .setInitialBranch("main")
            .call()
            .close()
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a commit is work GitHub does not have, and a push clears it`() {
        // The reported bug, at the level where it can be seen without a screen: "changed a
        // file, saved but sync is still grey and inactive and new change isnt reported".
        // Every change is committed the moment it happens, so git's own status is clean —
        // whatever a row counts has to be the commits GitHub has never been sent.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")!!.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        assertEquals("a new project has nothing to send", 0, git.changes("Shopping").total)

        val worktree = workspace.repoOf("Shopping")
        File(worktree, "milk.txt").writeText("two litres")
        git.commit("Shopping", "Edited milk.txt")

        assertEquals("one file, one thing to send", 1, git.changes("Shopping").ahead)
        assertEquals("and the row says so", 1, git.changes("Shopping").total)

        // **Two saves of one file are still one file to send.** A commit count said "2" here and
        // said "1" for a batch that moved ten files; the row is about what GitHub does not have.
        File(worktree, "milk.txt").writeText("three litres")
        git.commit("Shopping", "Edited milk.txt again")

        assertEquals("two commits, one file to send", 1, git.changes("Shopping").ahead)

        File(worktree, "bread.txt").writeText("one loaf")
        git.commit("Shopping", "Added bread.txt")
        assertEquals("and a second file makes two", 2, git.changes("Shopping").ahead)

        val pushed = git.sync("Shopping")

        assertTrue(pushed.message, pushed.success)
        assertEquals("after a push there is nothing left", 0, git.changes("Shopping").ahead)
        assertEquals("which the row reports as up to date", 0, git.changes("Shopping").total)
    }

    @Test
    fun `a file's diff says which lines were added, removed and left alone`() {
        // **The preview behind "See changes".** Against the last push, not against the last commit:
        // every save is a commit, so a diff of the working tree would be empty whenever a person
        // looked at it.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")!!.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        val worktree = workspace.repoOf("Shopping")
        File(worktree, "milk.txt").writeText("one\ntwo\nthree")
        git.commit("Shopping", "first")
        assertTrue(git.sync("Shopping").success)

        File(worktree, "milk.txt").writeText("one\nTWO\nthree\nfour")
        git.commit("Shopping", "second")

        val diff = git.diff("Shopping", "milk.txt").getOrThrow()

        assertEquals(
            listOf(
                GitService.DiffLine.Kind.SAME to "one",
                GitService.DiffLine.Kind.REMOVED to "two",
                GitService.DiffLine.Kind.ADDED to "TWO",
                GitService.DiffLine.Kind.SAME to "three",
                GitService.DiffLine.Kind.ADDED to "four",
            ),
            diff.map { it.kind to it.text },
        )
    }

    @Test
    fun `a file that is not text has no preview, and says so`() {
        // Rendering a picture's bytes as lines is convincing nonsense, and a person reading the
        // window has no way to tell it from the file.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")!!.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        val worktree = workspace.repoOf("Shopping")
        File(worktree, "photo.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x01, 0x02))
        git.commit("Shopping", "a picture")

        val preview = git.diff("Shopping", "photo.png")

        assertTrue("no preview: ${preview.exceptionOrNull()?.message}", preview.isFailure)
        assertTrue(
            "and the reason is text: ${preview.exceptionOrNull()?.message}",
            preview.exceptionOrNull()?.message.orEmpty().contains("not text"),
        )
    }

    @Test
    fun aSyncCanSayWhatItWasFor() {
        // Every other commit in a project is the app narrating a file — "Edited
        // milk.txt" — which is the right message for an undo step and the wrong one
        // for the history on GitHub, where the question is why a push happened.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        val repo = git.open("Shopping") ?: run {
            git.init("Shopping").getOrThrow()
            git.open("Shopping")!!
        }
        repo.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("milk")

        val result = git.sync("Shopping", "Added the week's shopping")

        assertTrue(result.message, result.success)
        org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(bare).build().use { remote ->
            val tip = org.eclipse.jgit.revwalk.RevWalk(remote).use { walk ->
                walk.parseCommit(remote.resolve("refs/heads/main"))
            }
            assertEquals(
                "the person's own words are what GitHub shows for the push",
                "Added the week's shopping",
                tip.fullMessage.trim(),
            )
        }
    }

    @Test
    fun `a lock left by a killed app does not lose the change`() {
        // The reported failure, exactly: a phone killed mid-write leaves `.git/index.lock`
        // behind, and every write after it is refused with "Cannot lock … ensure that no
        // other process has an open file handle". On a phone nobody can delete that file,
        // so before this the project was dead: nothing could be saved to it again.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val repo = git.open("Shopping")!!
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("milk")
        assertTrue("the first commit lands", git.commit("Shopping", "Added Milk.txt") != null)
        repo.close()

        // What the corpse of a killed process looks like.
        val lock = File(workspace.repoOf("Shopping"), ".git/index.lock")
        lock.writeText("")

        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("two litres")
        val outcome = git.commitOutcome("Shopping", "Edited Milk.txt")

        assertTrue("the change is written despite the lock: ${outcome.error}", outcome.ok)
        assertTrue("and it became a version", outcome.written)
        assertFalse("and the corpse is gone", lock.exists())
        assertEquals(
            "with the text that was saved",
            "two litres",
            File(workspace.repoOf("Shopping"), "Milk.txt").readText(),
        )
    }

    @Test
    fun `a lock that really is held is said, not swallowed`() {
        // The falsification: a lock that cannot be cleared (something is genuinely
        // holding it) must come back as a failure with words. The old signature returned
        // null for both "nothing to commit" and "could not commit", and the caller read
        // both as success — which is how a save went into the editor as saved while the
        // history stayed empty.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("milk")

        // A lock that is a non-empty directory cannot be deleted by `File.delete`, which
        // is this phone's way of saying "somebody really is holding it".
        val lock = File(workspace.repoOf("Shopping"), ".git/index.lock")
        lock.mkdirs()
        File(lock, "held").writeText("by something")

        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("two litres")
        val outcome = git.commitOutcome("Shopping", "Edited Milk.txt")

        assertFalse("the commit did not happen", outcome.written)
        assertFalse("and it is a failure, not silence", outcome.ok)
        assertTrue(
            "in words a person can act on: ${outcome.error}",
            outcome.error!!.contains("locked by another copy of the app"),
        )
        lock.deleteRecursively()
    }

    @Test
    fun `a lock that really is held stops the sync from pushing over it`() {
        // A push over a commit that did not happen would send a tree missing exactly the
        // work the person was trying to save, and report the sync as done.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        val repo = git.open("Shopping") ?: run {
            git.init("Shopping").getOrThrow()
            git.open("Shopping")!!
        }
        repo.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("milk")
        assertTrue("the first sync lands", git.sync("Shopping").success)

        val lock = File(workspace.repoOf("Shopping"), ".git/index.lock")
        lock.mkdirs()
        File(lock, "held").writeText("by something")
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("two litres")

        val result = git.sync("Shopping", "Two litres")

        assertFalse("the sync did not report success", result.success)
        assertTrue(
            "and said why: ${result.message}",
            result.message.contains("Nothing was pushed"),
        )
        org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(bare).build().use { remote ->
            val tip = org.eclipse.jgit.revwalk.RevWalk(remote).use { walk ->
                walk.parseCommit(remote.resolve("refs/heads/main"))
            }
            assertEquals(
                "and GitHub still holds the old text",
                "milk",
                org.eclipse.jgit.treewalk.TreeWalk.forPath(remote, "Milk.txt", tip.tree).use {
                    String(remote.open(it.getObjectId(0)).bytes, Charsets.UTF_8)
                },
            )
        }
        lock.deleteRecursively()
    }

    @Test
    fun aSyncWithNothingToSayMakesNoEmptyCommit() {
        // A marker is a landmark, not a habit: pressing sync with no note, and
        // nothing waiting, must not add one commit per press.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        val repo = git.open("Shopping") ?: run {
            git.init("Shopping").getOrThrow()
            git.open("Shopping")!!
        }
        repo.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("milk")
        assertTrue(git.sync("Shopping").success)
        val before = git.log("Shopping").size

        assertTrue(git.sync("Shopping").success)

        assertEquals("no commit was added", before, git.log("Shopping").size)
    }

    @Test
    fun replacingUsesWhateverBranchGitHubActuallyHas() {
        // The reported failure: replace a local project with the remote one, and get
        // an exception. A repository made before GitHub's own default changed has
        // `master`, and asking for `origin/main` threw `RefNotFoundException: Ref
        // origin/main not found` — which reads as the app being broken rather than as
        // a different branch name.
        val masterBare = File(root, "master.git")
        org.eclipse.jgit.api.Git.init()
            .setDirectory(masterBare)
            .setBare(true)
            .setInitialBranch("master")
            .call()
            .close()
        // Something on the remote's `master`, so there is a copy to take.
        val seed = File(root, "seed")
        org.eclipse.jgit.api.Git.cloneRepository()
            .setURI(masterBare.absolutePath)
            .setDirectory(seed)
            .call()
            .use { clone ->
                File(seed, "FromGitHub.txt").writeText("on github")
                clone.add().addFilepattern(".").call()
                clone.commit().setMessage("seed").setAuthor(author.ident())
                    .setCommitter(author.ident()).call()
                clone.push().setRemote("origin")
                    .add("refs/heads/master:refs/heads/master").call()
            }

        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        val repo = git.open("Shopping") ?: run {
            git.init("Shopping").getOrThrow()
            git.open("Shopping")!!
        }
        repo.use { open -> GitService.setRemoteUrl(open, masterBare.absolutePath) }
        File(workspace.repoOf("Shopping"), "OnlyHere.txt").writeText("mine")

        val result = git.replaceWithRemote("Shopping")

        assertTrue(result.message, result.success)
        assertTrue(
            "the remote's file arrived",
            File(workspace.repoOf("Shopping"), "FromGitHub.txt").isFile,
        )
        assertFalse(
            "and the local one that GitHub does not have went",
            File(workspace.repoOf("Shopping"), "OnlyHere.txt").exists(),
        )
    }

    @Test
    fun aSyncPushesTheBranchGitHubActuallyHas() {
        // Given a time-shaped failure: the phone said "synced", and GitHub's default
        // branch never moved, because the push went to `main` while the repository
        // answers to `master` — leaving a second branch on GitHub that nobody is
        // looking at, and a history there as long and as old as it ever was.
        val masterBare = File(root, "master-push.git")
        org.eclipse.jgit.api.Git.init()
            .setDirectory(masterBare)
            .setBare(true)
            .setInitialBranch("master")
            .call()
            .close()
        val seed = File(root, "seed-push")
        org.eclipse.jgit.api.Git.cloneRepository()
            .setURI(masterBare.absolutePath)
            .setDirectory(seed)
            .call()
            .use { clone ->
                File(seed, "FromGitHub.txt").writeText("on github")
                clone.add().addFilepattern(".").call()
                clone.commit().setMessage("seed").setAuthor(author.ident())
                    .setCommitter(author.ident()).call()
                clone.push().setRemote("origin")
                    .add("refs/heads/master:refs/heads/master").call()
            }

        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        val repo = git.open("Shopping") ?: run {
            git.init("Shopping").getOrThrow()
            git.open("Shopping")!!
        }
        repo.use { open -> GitService.setRemoteUrl(open, masterBare.absolutePath) }
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("milk")

        val result = git.sync("Shopping")

        assertTrue(result.message, result.success)
        org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(masterBare).build().use { remote ->
            val branch = repo.use { GitService.currentBranch(it) }
            val tip = remote.resolve("refs/heads/$branch")
            assertNotNull("the branch GitHub has was moved", tip)
            assertTrue(
                "and the file went with it",
                GitService.filesUnder(remote, null, tip!!.name).any { it.first == "Milk.txt" },
            )
            // The branch the phone is on must be the one GitHub shows: a second
            // branch would make every future sync a report about a tree nobody reads.
            assertTrue(
                "no second branch was created",
                remote.exactRef("refs/heads/master") != null,
            )
        }
    }

    @Test
    fun replacingAProjectBringsThePhoneOntoGitHubsBranch() {
        // Replacing the files is the visible half. The other half is the branch
        // name: a phone left on `main` after taking a `master` repository pushes a
        // branch GitHub does not show, so the next sync is a silent no-op as far as
        // anybody reading GitHub is concerned.
        val masterBare = File(root, "master-replace.git")
        org.eclipse.jgit.api.Git.init()
            .setDirectory(masterBare)
            .setBare(true)
            .setInitialBranch("master")
            .call()
            .close()
        val seed = File(root, "seed-replace")
        org.eclipse.jgit.api.Git.cloneRepository()
            .setURI(masterBare.absolutePath)
            .setDirectory(seed)
            .call()
            .use { clone ->
                File(seed, "FromGitHub.txt").writeText("on github")
                clone.add().addFilepattern(".").call()
                clone.commit().setMessage("seed").setAuthor(author.ident())
                    .setCommitter(author.ident()).call()
                clone.push().setRemote("origin")
                    .add("refs/heads/master:refs/heads/master").call()
            }

        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        val repo = git.open("Shopping") ?: run {
            git.init("Shopping").getOrThrow()
            git.open("Shopping")!!
        }
        repo.use { open -> GitService.setRemoteUrl(open, masterBare.absolutePath) }
        assertEquals("the phone starts on its own branch", "main", repo.use { GitService.currentBranch(it) })

        val replaced = git.replaceWithRemote("Shopping")
        assertTrue(replaced.message, replaced.success)

        assertEquals(
            "and now it is on the one GitHub has",
            "master",
            git.open("Shopping")!!.use { GitService.currentBranch(it) },
        )
    }

    @Test
    fun replacingAProjectGitHubHasNothingOnSaysSo() {
        // A project this phone made and never synced: there is no remote branch at
        // all. That is not an exception, it is an answer — and the answer has to say
        // what to do instead.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        val repo = git.open("Shopping") ?: run {
            git.init("Shopping").getOrThrow()
            git.open("Shopping")!!
        }
        repo.use { open -> GitService.setRemoteUrl(open, bare.absolutePath) }

        val result = git.replaceWithRemote("Shopping")

        assertFalse("it did not claim to have replaced anything", result.success)
        assertTrue(
            "and it said why: ${result.message}",
            result.message.contains("Sync this phone's copy"),
        )
    }

    @Test
    fun syncingCommitsAndPushesWhatChanged() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        val repo = git.open("Shopping") ?: run {
            git.init("Shopping").getOrThrow()
            git.open("Shopping")!!
        }
        repo.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("milk")

        val result = git.sync("Shopping")

        assertTrue(result.message, result.success)
        org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(bare).build().use { remote ->
            assertNotNull("the commit reached the remote", remote.resolve("refs/heads/main"))
        }
        // The worktree is clean afterwards: everything went in one commit.
        assertEquals(0, git.changes("Shopping").total)
    }

    @Test
    fun theCommitMessageNamesTheFiles() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")?.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("milk")
        File(workspace.repoOf("Shopping"), "Bread.txt").writeText("bread")

        assertTrue(git.sync("Shopping").success)

        val message = git.log("Shopping").first().message
        assertTrue("the message must name the files, got: $message", message.contains("Milk.txt"))
        assertTrue("the message must name the files, got: $message", message.contains("Bread.txt"))
    }

    @Test
    fun aCleanProjectSaysNothingChanged() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")?.use { GitService.setRemoteUrl(it, bare.absolutePath) }

        // The first commit is the empty one the repository starts with.
        assertEquals(0, git.changes("Shopping").total)
    }

    @Test
    fun aProjectsHistoryIsShortenedAfterASync() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")?.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        keep = 3

        repeat(7) { i ->
            File(workspace.repoOf("Shopping"), "count.txt").writeText("value $i")
            assertTrue(git.sync("Shopping").success)
        }

        assertEquals("the project keeps the length it was given", 3, git.log("Shopping").size)
        assertEquals("value 6", File(workspace.repoOf("Shopping"), "count.txt").readText())
    }

    @Test
    fun aSyncWithNoRepositorySaysSo() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val result = gitFor().sync("Shopping")
        assertFalse(result.success)
        assertTrue(result.message.contains("no repository"))
    }

    @Test
    fun theAppsOwnFolderIsNeitherAChangeNorASync() {
        // **A project's folder is its repository**, so the only thing here that must not travel is
        // what the app put in it: `agent/` (the record) and `.muffy/` (its flags). The two lines
        // `ensureIgnore` writes into `.git/info/exclude` are what make that true, and this asks git
        // rather than the app — including that they are not a change to the repository.
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")?.use { GitService.setRemoteUrl(it, bare.absolutePath) }

        val records = File(project, AgentLog.DIR).apply { mkdirs() }
        File(records, "session.log").writeText("what the model was sent")
        val flags = File(project, ".muffy").apply { mkdirs() }
        File(flags, "pins").writeText("[]")

        assertTrue(git.sync("Shopping").success)

        assertEquals("nothing to send", 0, git.changes("Shopping").total)
        assertTrue(git.log("Shopping", "${AgentLog.DIR}/session.log").isEmpty())
    }
}
