package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        // **The classification, not the algorithm's exact edit script.** Which lines a diff engine
        // groups into one hunk is its business (Histogram can call three changed lines one edit), and
        // what the window paints is the three kinds — so what is asserted is that the answer says
        // which line went, which came, and which did not move.
        fun lines(kind: GitService.DiffLine.Kind) =
            diff.filter { it.kind == kind }.map { it.text }
        assertTrue("the line that changed is shown as removed: ${lines(GitService.DiffLine.Kind.REMOVED)}",
            "two" in lines(GitService.DiffLine.Kind.REMOVED))
        assertTrue("and as added", "TWO" in lines(GitService.DiffLine.Kind.ADDED))
        assertTrue("the new line is an addition", "four" in lines(GitService.DiffLine.Kind.ADDED))
        assertTrue("and the lines that did not move are unchanged",
            "one" in lines(GitService.DiffLine.Kind.SAME))
        assertFalse("nothing unchanged is painted as a change", "two" in lines(GitService.DiffLine.Kind.SAME))
    }

    @Test
    fun `a file too big to preview is refused before it is read`() {
        // **The limit is the difference between a window and a stalled phone.** The size is asked of
        // the repository first, because reading 40 MB in order to discover it is 40 MB is the failure
        // the limit exists to avoid — and an answer that only said "failed" would send a person
        // looking for a bug in a file that is simply large.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")!!.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        val worktree = workspace.repoOf("Shopping")
        val big = File(worktree, "ledger.txt")
        // Over half a megabyte: the limit the preview refuses past.
        big.writeText(buildString { repeat(600) { append("x".repeat(1_000)).append("\n") } })
        git.commit("Shopping", "a long file")

        val preview = git.diff("Shopping", "ledger.txt")

        assertTrue("no preview: ${preview.exceptionOrNull()?.message}", preview.isFailure)
        assertTrue(
            "and it says the reason is size: ${preview.exceptionOrNull()?.message}",
            preview.exceptionOrNull()?.message.orEmpty().contains("too big"),
        )
    }

    @Test
    fun `a file that has never been sent is all additions`() {
        // **There is nothing to compare it with, and that is not an error.** A new file's diff is its
        // own lines, painted as added; answering "no preview" would hide the work a person is about
        // to send, which is exactly what the window is for.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")!!.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        val worktree = workspace.repoOf("Shopping")
        File(worktree, "milk.txt").writeText("one\ntwo")
        git.commit("Shopping", "first")

        val diff = git.diff("Shopping", "milk.txt").getOrThrow()

        assertEquals(
            "both lines came, and neither was removed",
            listOf(GitService.DiffLine.Kind.ADDED, GitService.DiffLine.Kind.ADDED),
            diff.map { it.kind },
        )
        assertEquals("with their text", listOf("one", "two"), diff.map { it.text })
    }

    @Test
    fun `a file deleted here is all removals`() {
        // The other side of the same coin: the repository still holds it, the phone does not. The
        // window has to be able to say what a sync would remove from GitHub.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")!!.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        val worktree = workspace.repoOf("Shopping")
        File(worktree, "milk.txt").writeText("one\ntwo")
        git.commit("Shopping", "first")
        assertTrue(git.sync("Shopping").success)

        File(worktree, "milk.txt").delete()
        git.commit("Shopping", "gone")

        val diff = git.diff("Shopping", "milk.txt").getOrThrow()

        assertEquals(
            "both lines went, and none came",
            listOf(GitService.DiffLine.Kind.REMOVED, GitService.DiffLine.Kind.REMOVED),
            diff.map { it.kind },
        )
        assertEquals("with their text", listOf("one", "two"), diff.map { it.text })
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
    fun `a sync can say what it was for`() {
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
    fun `a sync with nothing to say makes no empty commit`() {
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
    fun `replacing uses whatever branch GitHub actually has`() {
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
    fun `a sync pushes the branch GitHub actually has`() {
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
    fun `replacing a project brings the phone onto GitHub's branch`() {
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
    fun `replacing a project GitHub has nothing on says so`() {
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
    fun `syncing commits and pushes what changed`() {
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
    fun `the commit message names the files`() {
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
    fun `the last synced commit is the remote's, and null when nothing was pushed`() {
        // **The other end of the editor's default diff.** "What changed since I last synced" is a
        // question about two commits, and this is the one that is easy to get wrong: `HEAD` is
        // where the file is now, and the answer has to be where GitHub last saw it.
        workspace.createProjectFolders("groceries").getOrThrow()
        val git = gitFor()
        git.init("groceries").getOrThrow()
        git.open("groceries")!!.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        val worktree = workspace.repoOf("groceries")

        File(worktree, "milk.txt").writeText("two litres")
        git.commit("groceries", "a save")

        // **Nothing pushed yet, so there is nothing to compare against** - which is a different
        // answer from "the same as HEAD", and the screen says it rather than drawing a diff.
        assertNull(
            "a file whose whole history is local has no synced version",
            git.open("groceries")!!.use { GitService.lastSynced(it) },
        )

        val pushed = git.open("groceries")!!.use { repo -> GitService.push(repo, null) }
        assertTrue("the push really reached the remote: ${pushed.message}", pushed.success)
        val synced = git.open("groceries")!!.use { GitService.lastSynced(it) }
        val head = git.open("groceries")!!.use { it.resolve("HEAD")?.name }
        assertEquals("after a push the synced commit is HEAD", head, synced)

        // And a commit after the push moves HEAD away from it, which is what makes the pair useful:
        // the diff between them is exactly what is waiting to be sent.
        File(worktree, "milk.txt").writeText("three litres")
        git.commit("groceries", "another save")
        val head2 = git.open("groceries")!!.use { it.resolve("HEAD")?.name }
        assertNotEquals("HEAD has moved on", head2, synced)
        assertEquals(
            "and the synced commit stayed where it was",
            "two litres",
            git.open("groceries")!!.use { GitService.fileAt(it, "milk.txt", synced) },
        )
        assertEquals(
            "while HEAD holds what was just written",
            "three litres",
            git.open("groceries")!!.use { GitService.fileAt(it, "milk.txt", head2) },
        )
    }

    @Test
    fun `a clean project says nothing changed`() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")?.use { GitService.setRemoteUrl(it, bare.absolutePath) }

        // The first commit is the empty one the repository starts with.
        assertEquals(0, git.changes("Shopping").total)
    }

    @Test
    fun `a project's history is shortened after a sync`() {
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
    fun `a sync with no repository says so`() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val result = gitFor().sync("Shopping")
        assertFalse(result.success)
        assertTrue(result.message.contains("no repository"))
    }

    @Test
    fun `the app's own folder is neither a change nor a sync`() {
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
        // **The record is in the LOCAL history and not on the remote, and that is the change.**
        // This used to assert that the record had no commit at all, which was true while `agent/`
        // was handled by `.git/info/exclude`: an ignore means git never saw the file. Unsyncing is
        // the other act - the record is committed here like anything else, and left out of the tree
        // that is *sent* - so the local log holds it and the remote must not.
        assertFalse(
            "the record is versioned on this phone",
            git.log("Shopping", "${AgentLog.DIR}/session.log").isEmpty(),
        )
        // The remote is a bare repository on disk, so its branch is read directly - the same thing
        // a clone would see, and the only place that answers "did this travel?".
        val sent = org.eclipse.jgit.api.Git.open(bare).use { remote ->
            GitService.treePaths(remote.repository, remote.repository.resolve("refs/heads/main")
                ?: remote.repository.resolve("refs/heads/master"))
        }
        assertTrue(
            "and never reaches the remote",
            sent.none { it.startsWith(AgentLog.DIR + "/") },
        )
    }

    // ---- the file being written is never content --------------------------------------------
    //
    // **Found while chasing a flake, and the flake was the evidence.** `HistoryFeatureTest` failed
    // once with "nothing is offered expected:[] but was:[kept.txt.new]" - a file nobody created,
    // offered back from the history. The mechanism: a save writes `<name>.new` beside the file and
    // renames over it, and a commit that ran between the two recorded the temporary, which the
    // rename then deleted. The phantom then sits in every later deletion window, offering to
    // restore a file that never existed.

    @Test
    fun `a half-written file is not committed`() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val worktree = workspace.repoOf("Shopping")

        File(worktree, "milk.txt").writeText("two litres")
        // **The exact state a save is in for one instant**: the target beside a temporary, both real
        // files, the rename not yet done.
        File(worktree, Beside.TEMP_PREFIX + "milk.txt").writeText("three litres")

        git.commit("Shopping", "Edited milk.txt")

        val held = git.paths("Shopping", null, git.log("Shopping", null, 1).single().id)
        assertTrue("the file is committed: $held", "milk.txt" in held)
        assertFalse(
            "and the half-written one is not: $held",
            held.any { it.startsWith(Beside.TEMP_PREFIX) },
        )
    }

    @Test
    fun `the exclusion is local, so it is not a file the person wrote`() {
        // **`.git/info/exclude`, not `.gitignore`.** An ignore line in `.gitignore` is content: it
        // would be committed, pushed to GitHub, and appear in the change list as a file nobody wrote.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val worktree = workspace.repoOf("Shopping")

        File(worktree, "milk.txt").writeText("two litres")
        git.commit("Shopping", "Edited milk.txt")

        val held = git.paths("Shopping", null, git.log("Shopping", null, 1).single().id)
        assertFalse("no .gitignore was invented for the person", ".gitignore" in held)
        assertTrue(
            "the hint lives inside .git",
            File(worktree, ".git/info/exclude").isFile,
        )
    }

    @Test
    fun `the hint is written once, however many commits there are`() {
        // It is written on the way into every commit, because a project made before this existed has
        // no line. That makes duplication the obvious failure: an exclude file that grew a line per
        // commit would be a slow leak in a file the person may one day read.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val worktree = workspace.repoOf("Shopping")

        repeat(3) { n ->
            File(worktree, "milk.txt").writeText("$n litres")
            git.commit("Shopping", "Edited milk.txt")
        }

        val text = File(worktree, ".git/info/exclude").readText()
        assertEquals(
            "one line, not three",
            1,
            text.lineSequence().count { it.trim() == "${Beside.TEMP_PREFIX}*" },
        )
    }

    @Test
    fun `a project made before the hint was written still gets it`() {
        // The line is added at commit time rather than at init precisely for this: every project on
        // a phone already has a repository with no exclude file, and a fix that only helped new
        // projects would leave the hazard on all of them.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val worktree = workspace.repoOf("Shopping")
        File(worktree, ".git/info/exclude").delete()

        File(worktree, "milk.txt").writeText("two litres")
        git.commit("Shopping", "Edited milk.txt")

        assertTrue(
            "the line is there now",
            File(worktree, ".git/info/exclude").readText().contains("${Beside.TEMP_PREFIX}*"),
        )
    }

    @Test
    fun `a file being written leaves nothing behind and is whole`() {
        // **The rename is the whole point of the temporary.** A write that left the temporary in
        // place would put a second copy of the file in the folder, and one that wrote through it
        // would leave the target half-written after a kill.
        val file = File(root, "note.txt")
        Beside.write(file, "first")
        Beside.write(file, "second")

        assertEquals("the target is whole", "second", file.readText())
        assertEquals(
            "and no temporary is left",
            emptyList<String>(),
            file.parentFile!!.list()!!.filter { it.startsWith(Beside.TEMP_PREFIX) },
        )
    }

    @Test
    fun `a half-written file is not a row in any listing`() {
        // Even before git is asked, the app must not draw it: a folder refresh landing inside the
        // write window would show a row that disappears, and the deletion window asks the same
        // question through the same method.
        assertTrue("hidden", workspace.isHidden(Beside.TEMP_PREFIX + "milk.txt"))
        assertFalse("and the file itself is not", workspace.isHidden("milk.txt"))
        // **A person's own `.new` is theirs.** The old name was `<name>.new`, and hiding that suffix
        // would now hide a file somebody really has.
        assertFalse("a person's `.new` stays visible", workspace.isHidden("notes.new"))
    }

}
