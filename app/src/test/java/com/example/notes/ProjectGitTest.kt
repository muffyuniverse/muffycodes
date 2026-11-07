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

    private fun gitFor(token: String? = null) = ProjectGit(
        workspace = workspace,
        tokenProvider = { token },
        authorProvider = { author },
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

    @Test
    fun `a project of text files can be pushed while the LFS filter is on`() {
        // **The bug that made every sync fail, and it had nothing to do with pictures.**
        //
        // Once `filter.lfs.useJGitBuiltin` is enabled, JGit's LFS pre-push hook runs on *every* push
        // and asks `LfsConnectionFactory` for a connection before it has checked whether it has
        // anything to upload. That call reaches `SshSupport.runSshCommand`, which dereferences
        // `SshSessionFactory.getInstance()` — null on Android, since nothing configures one — and
        // throws a `NullPointerException` that takes the whole push with it. Measured both ways: the
        // identical push succeeds with the registration absent and fails with it present.
        //
        // So `push` turns the filter off for its own duration. This test is the one that fails if
        // that guard is ever removed: a project with no pictures at all must still be able to sync.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")!!.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        workspace.write(File(workspace.projectDir("Shopping"), "milk.txt"), "two litres\n")
        git.commit("Shopping", "Edited milk.txt")

        val result = git.sync("Shopping")

        assertTrue("the sync succeeded: ${result.message}", result.success)
        // **And the filter is left as it was**, so the next picture is still a pointer.
        git.open("Shopping")!!.use {
            assertTrue(
                "the filter is back on afterwards",
                it.config.getBoolean("filter", "lfs", "useJGitBuiltin", false),
            )
        }
    }

    // ---- a picture has one version, so it cannot be put back ----------------

    @Test
    fun `restoring a picture is refused by name, and changes nothing`() {
        // **A picture is a pointer, and history holds no version of its bytes.** Every commit names
        // the same SHA-256, so "going back" would hand a person the same picture they are looking at,
        // presented as a restore that changes something. The refusal says so by name instead.
        //
        // **The folders first.** `init` makes the repository but not the project folder, and the
        // first version of this test wrote into a directory that did not exist yet.
        workspace.createProjectFolders("shopping").getOrThrow()
        val git = gitFor()
        val picture = File(workspace.projectDir("shopping"), "page.png")
        // Real PNG magic bytes, so this is a picture by content as well as by name.
        picture.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "one".toByteArray())
        git.init("shopping").getOrThrow()
        val before = picture.readBytes()

        val outcome = git.restore("shopping", "page.png", "HEAD")

        assertTrue("it is refused: ${outcome.exceptionOrNull()?.message}", outcome.isFailure)
        val said = outcome.exceptionOrNull()?.message.orEmpty()
        assertTrue("and it names the file: $said", said.contains("page.png"))
        assertTrue("and says why: $said", said.contains("only one version"))
        assertTrue("the picture on disk is untouched", before.contentEquals(picture.readBytes()))
    }

    @Test
    fun `restoring text still works, because text keeps its versions`() {
        // **The refusal is about pictures, not about restore.** A test that only proves the refusal
        // would pass just as well if `restore` had stopped working altogether.
        val git = gitFor()
        val file = File(workspace.projectDir("shopping"), "milk.txt")
        workspace.write(file, "one\n")
        git.init("shopping").getOrThrow()
        val first = RepoHistory(workspace.projectDir("shopping")).log().first().id
        workspace.write(file, "two\n")
        requireNotNull(git.commit("shopping", "second"))

        git.restore("shopping", "milk.txt", first).getOrThrow()

        assertEquals("the old text is back", "one\n", file.readText())
    }

    // ---- what each commit changed, and which commit a blob came from ---------

    /**
     * **The two primitives everything about saved versions rests on.**
     *
     * `walkChanges` is the one pass over history that both the indexer and the answer use, and
     * `VersionResolver` is how a blob id — which is what the index stores, because it survives a
     * prune — becomes a commit id, which is what a person can read. Neither had a test of its own:
     * they were only ever exercised through a search, where a failure looks like "nothing matched".
     */
    @Test
    fun `walkChanges reports what each commit changed, newest first`() {
        val git = gitFor()
        workspace.write(File(workspace.projectDir("shopping"), "milk.txt"), "one\n")
        workspace.write(File(workspace.projectDir("shopping"), "bread.txt"), "two\n")
        git.init("shopping")
        workspace.write(File(workspace.projectDir("shopping"), "milk.txt"), "one, and more\n")
        git.commit("shopping", "second")

        val seen = mutableListOf<Pair<String, List<String>>>()
        val repo = git.open("shopping")!!
        repo.use { GitService.walkChanges(it) { commit, changes -> seen += commit.message to changes.map { c -> c.path } } }

        // **Newest first**, which is what makes the resolver cheap: the version a person asks about
        // is usually one of the first few commits.
        assertEquals("two commits", 2, seen.size)
        assertEquals("the newest first", "second", seen[0].first)
        assertEquals("and it changed one file", listOf("milk.txt"), seen[0].second)
        // **The first commit lists everything it holds**, because it is diffed against nothing.
        assertEquals("the first commit", "Initial commit", seen[1].first)
        assertTrue("holding both files: ${seen[1].second}", seen[1].second.containsAll(listOf("milk.txt", "bread.txt")))
    }

    @Test
    fun `a deletion is not a change with a blob`() {
        // **A deletion has no content**, so it is not something the index can hold a version of.
        // Reporting it with the zero id would be a row keyed by a blob that does not exist.
        val git = gitFor()
        val file = File(workspace.projectDir("shopping"), "milk.txt")
        workspace.write(file, "one\n")
        git.init("shopping")
        file.delete()
        git.commit("shopping", "removed it")

        val repo = git.open("shopping")!!
        // **The deletion commit's own changes**, not the whole walk: the *first* commit legitimately
        // reports `milk.txt`, because it is diffed against nothing and holds the file. The question
        // is what the commit that removed it says.
        val newest = mutableListOf<Pair<String, String>>()
        repo.use {
            GitService.walkChanges(it, limit = 1) { _, changes ->
                changes.forEach { c -> newest += c.path to c.blob }
            }
        }

        // **Absent, not present with a zero id.** `walkChanges` skips deletions outright, so there is
        // no row for the index to key by a blob that does not exist.
        assertTrue("the deletion is not in the walk: $newest", newest.none { it.first == "milk.txt" })
    }

    @Test
    fun `a blob resolves to the newest commit that put it there`() {
        val git = gitFor()
        val file = File(workspace.projectDir("shopping"), "milk.txt")
        workspace.write(file, "one\n")
        git.init("shopping")
        workspace.write(file, "two\n")
        val second = git.commit("shopping", "second")!!
        workspace.write(file, "three\n")
        git.commit("shopping", "third")

        val repo = git.open("shopping")!!
        repo.use {
            // **The blob of the middle version**, found by walking, so the test asks about a real
            // pair rather than a made-up id.
            var blob: String? = null
            GitService.walkChanges(it) { commit, changes ->
                if (commit.id == second) blob = changes.first { c -> c.path == "milk.txt" }.blob
            }
            val found = GitService.VersionResolver(it).resolve(setOf("milk.txt" to blob!!))

            assertEquals("the commit that wrote it", second, found["milk.txt" to blob])
        }
    }

    @Test
    fun `a blob no commit reaches resolves to nothing`() {
        // **The prune case, and the reason the answer drops the row rather than naming a dead id.**
        val git = gitFor()
        workspace.write(File(workspace.projectDir("shopping"), "milk.txt"), "one\n")
        git.init("shopping")

        val repo = git.open("shopping")!!
        repo.use {
            val found = GitService.VersionResolver(it).resolve(setOf("milk.txt" to "0".repeat(40)))
            assertTrue("nothing reaches it", found.isEmpty())
        }
    }

    @Test
    fun `a blob reads back by its own id, without a path or a commit`() {
        // **`bytesAt` takes a revision and walks a tree**, so it cannot read a blob id at all — that
        // was a real defect: every saved version read as null and was skipped without a word.
        val git = gitFor()
        workspace.write(File(workspace.projectDir("shopping"), "milk.txt"), "the milk went sour\n")
        git.init("shopping")

        val repo = git.open("shopping")!!
        repo.use {
            var blob: String? = null
            GitService.walkChanges(it) { _, changes -> blob = changes.first { c -> c.path == "milk.txt" }.blob }
            assertEquals(
                "the text of that version",
                "the milk went sour\n",
                GitService.bytesOf(it, blob!!)?.let { raw -> String(raw, Charsets.UTF_8) },
            )
        }
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
        // **The first version is sent before the counting starts.** A project's first version now
        // carries `.gitattributes`, and the remote did not exist when it was made. Pushing it here
        // leaves the project genuinely clean, so the numbers below are about the edits and nothing
        // else. Lowering the expected counts instead would have kept the test passing while it
        // stopped asking its question.
        assertTrue("the first version is sent", git.sync("Shopping").success)
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

        // **Sent first, so the question is about being clean.** The first version carries
        // `.gitattributes`, and the remote was set after it was made. Sending it leaves the project
        // with nothing waiting, which is what this test is named for.
        assertTrue("the first version is sent", git.sync("Shopping").success)
        assertEquals(0, git.changes("Shopping").total)
    }

    @Test
    fun `seven syncs keep all seven versions, because nothing shortens a history now`() {
        // **The inverse of the test that used to be here**, and the inversion is the point: there was
        // a length, a person chose it, and a sync shortened the history to it. The length is gone
        // because the answer turned out to be about the *kind* of file rather than about a count —
        // text compresses 120x, so keeping all of it costs almost nothing, and a picture keeps one
        // version instead. A test that still asserted a shortened history would be asserting a
        // feature that was removed on purpose.
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")?.use { GitService.setRemoteUrl(it, bare.absolutePath) }

        repeat(7) { i ->
            File(workspace.repoOf("Shopping"), "count.txt").writeText("value $i")
            assertTrue(git.sync("Shopping").success)
        }

        // Seven saves, seven sync commits on top of the first version — and none of them dropped.
        assertTrue(
            "every version is still there: ${git.log("Shopping").size}",
            git.log("Shopping").size >= 8,
        )
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
        // **Still excluded, and now for every path.** `agent/`, `.muffy/` and `requests/` are
        // bookkeeping the app writes, not the person's work. `.git/info/exclude` keeps them out of
        // git entirely.
        //
        // **This is now the only mechanism.** The other one — a tracked file kept out of a derived
        // tree that was force-pushed — is deleted, because the operator replaced it with classic
        // gitignore: "a simple tracked/untracked switch". So a path is either tracked and sent, or
        // ignored and not, and there is no third state for a test to distinguish.
        assertTrue(
            "the record has no commit at all",
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

    // ---- branches (§13d) ----------------------------------------------------

    /**
     * **A new project has `main` and nothing else**, which is why the branch screen needs no empty
     * state: `ProjectGit.init` sets `setInitialBranch("main")`, so there is always one row.
     */
    @Test
    fun `a new project has main and only main`() {
        val git = gitFor()
        git.init("Shopping").getOrThrow()

        val repo = git.open("Shopping")!!
        val branches = GitService.localBranches(repo)
        repo.close()

        assertEquals("one branch, and it is main", listOf("main"), branches)
    }

    /**
     * **A branch made here is a local branch**, and the screen lists only those. A remote-tracking ref
     * is git's copy of what the server had, not a place a person can work — offering it as a row would
     * be a row that cannot be entered.
     */
    @Test
    fun `a branch is made from main and appears in the list`() {
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val repo = git.open("Shopping")!!

        val made = GitService.createBranch(repo, "feature")
        assertTrue("it was made: $made", made.isSuccess)
        assertTrue(
            "and listed: ${GitService.localBranches(repo)}",
            "feature" in GitService.localBranches(repo),
        )
        // **main is first**, because it is the branch a project is made of and the one every other
        // branch merges into.
        assertEquals("main", GitService.localBranches(repo).first())
        repo.close()
    }

    /** Making the same branch twice is refused, because the second one would be a silent no-op. */
    @Test
    fun `a branch that already exists is refused`() {
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val repo = git.open("Shopping")!!
        GitService.createBranch(repo, "feature").getOrThrow()

        val again = GitService.createBranch(repo, "feature")
        assertTrue("refused", again.isFailure)
        assertTrue(
            "and it says which: ${again.exceptionOrNull()?.message}",
            again.exceptionOrNull()?.message.orEmpty().contains("already a branch"),
        )
        repo.close()
    }

    /**
     * **`main` cannot be deleted**, because it is what a project is made of — and the screen does not
     * even offer the button, so this is the store refusing what a caller could still ask for.
     */
    @Test
    fun `main cannot be deleted`() {
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val repo = git.open("Shopping")!!

        val refused = GitService.deleteBranch(repo, "main")
        assertTrue("refused", refused.isFailure)
        assertTrue("and main is still there", "main" in GitService.localBranches(repo))
        repo.close()
    }

    /**
     * **A branch with work that is not on `main` is refused**, which is the one act here that would
     * destroy something a person cannot get back — the same rule that removed `delete_repo`.
     */
    @Test
    fun `a branch with unmerged work cannot be deleted`() {
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val repo = git.open("Shopping")!!
        GitService.createBranch(repo, "feature").getOrThrow()

        // **A real commit on the branch, so it is genuinely ahead of main.** Written with JGit's own
        // plumbing rather than by touching the working tree: what is being tested is the branch's
        // reachability from main, which is a property of the commit graph and nothing else.
        val head = repo.resolve("refs/heads/feature")!!
        val tree = repo.resolve("refs/heads/main^{tree}")!!
        // **`who` is built OUTSIDE the `apply` block**, because inside it `this` is the
        // CommitBuilder — which has its own `author` property, so `author.ident()` there resolves to
        // JGit's `PersonIdent` and fails. The test's own field is only reachable unshadowed.
        val who = author.ident()
        val builder = org.eclipse.jgit.lib.CommitBuilder().apply {
            setTreeId(tree)
            setAuthor(who)
            setCommitter(who)
            setMessage("only on feature")
            setParentId(head)
        }
        val inserter = repo.newObjectInserter()
        val id = inserter.insert(builder)
        inserter.flush()
        inserter.close()
        // **`setNewObjectId`, not `newObjectId =`** — the property is a read-only val on RefUpdate;
        // the setter is the method, and assigning to the property is a compile error that names the
        // reassignment rather than the missing setter.
        repo.updateRef("refs/heads/feature").apply { setNewObjectId(id) }.update()

        val refused = GitService.deleteBranch(repo, "feature")
        assertTrue("refused: $refused", refused.isFailure)
        assertTrue(
            "and it says why: ${refused.exceptionOrNull()?.message}",
            refused.exceptionOrNull()?.message.orEmpty().contains("not on main"),
        )
        assertTrue("the branch is still there", "feature" in GitService.localBranches(repo))
        repo.close()
    }

    /** And a branch with nothing of its own goes quietly, because nothing is lost. */
    @Test
    fun `a branch with no work of its own is deleted`() {
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val repo = git.open("Shopping")!!
        GitService.createBranch(repo, "scratch").getOrThrow()

        val gone = GitService.deleteBranch(repo, "scratch")
        assertTrue("deleted: $gone", gone.isSuccess)
        assertFalse("and gone", "scratch" in GitService.localBranches(repo))
        repo.close()
    }

    /**
     * **The name rules are stated once**, so a name git would reject is refused where the person can
     * still change it rather than by a command that fails later in git's own words.
     */
    @Test
    fun `a branch name git would reject is refused before anything is made`() {
        val bad = listOf("", " ", "-x", "main", "a..b", "a b", "a~b", "a^b", "a:b", "a?b", "a*b",
            "a[b", "a\\b", "a/", "a.lock", "/a")
        for (name in bad) {
            assertNotNull("refused: \"$name\"", GitService.branchNameProblem(name))
        }
        for (name in listOf("feature", "fix-123", "wip/ui", "a_b")) {
            assertNull("allowed: \"$name\"", GitService.branchNameProblem(name))
        }
    }

    // ---- linked worktrees (§13d) ------------------------------------------

    /**
     * Makes a linked worktree the way git does, and hands back its folder.
     *
     * **git's own four files, written by hand**, because JGit has no `worktree add` — measured: the
     * 7.8.1 jar contains no worktree command at all. That is also why §13d step 4 writes these four
     * files from the app rather than calling a library.
     *
     * `commondir` is the one that makes the shared refs and objects reachable; `gitdir` points back at
     * the pointer; `HEAD` says which branch this tree is checked out on.
     */
    private fun linkedWorktree(branch: String): File {
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        val main = workspace.repoOf("Shopping")
        val common = File(main, ".git")
        val worktree = File(root, "Shopping-$branch").apply { mkdirs() }
        val private = File(common, "worktrees/Shopping-$branch").apply { mkdirs() }
        File(worktree, ".git").writeText("gitdir: " + private.absolutePath + "\n")
        File(private, "commondir").writeText("../..\n")
        File(private, "gitdir").writeText(File(worktree, ".git").absolutePath + "\n")
        File(private, "HEAD").writeText("ref: refs/heads/$branch\n")
        // The branch has to exist before a worktree can be checked out on it.
        File(common, "refs/heads").mkdirs()
        File(common, "refs/heads/$branch").writeText(File(common, "refs/heads/main").readText())
        return worktree
    }

    @Test
    fun `a worktree opens with its branches, not as an empty repository`() {
        // **The bug that would have made every branch index nothing.** `openExisting` used
        // `setGitDir(File(dir, ".git"))`, and a worktree's `.git` is a FILE — measured on 7.8.1:
        // `getBranch()` null, `resolve("HEAD")` null, zero branches, **and no exception thrown**. It
        // answered a repository, so SemanticSearch believed it and indexed nothing, silently.
        val worktree = linkedWorktree("feature")

        val repo = GitService.openExisting(worktree)

        assertNotNull("it opens", repo)
        repo!!.use {
            assertNotNull("HEAD resolves", it.resolve("HEAD"))
            assertEquals("and it is the worktree's own branch", "feature", GitService.currentBranch(it))
            assertTrue(
                "and the shared branches are reachable",
                it.branchList().call().any { r -> r.name == "refs/heads/main" },
            )
        }
    }

    @Test
    fun `a worktree's branch is the one pushed, and it is not main`() {
        // **The wrong answer that cannot be walked back.** `currentBranch` used to fall back to
        // `"main"`, so a repository whose HEAD was unreadable pushed `refs/heads/feature` onto
        // `refs/heads/main`. On 7.8.1 a worktree reports its own HEAD, which is what makes a branch
        // pushable at all — asserted against the library rather than trusted.
        val worktree = linkedWorktree("feature")

        GitService.openExisting(worktree)!!.use { repo ->
            assertEquals("the worktree's branch", "feature", GitService.currentBranch(repo))
            assertNotEquals("and emphatically not main", "main", GitService.currentBranch(repo))
        }
    }

    @Test
    fun `an unreadable HEAD is refused rather than answered as main`() {
        // **Rebuilt broken on purpose**, by opening the way the old code did: `setGitDir` pointed at a
        // `.git` FILE. That is a repository JGit builds happily and cannot read — exactly the state
        // the old `?: "main"` turned into a push onto main.
        val worktree = linkedWorktree("feature")
        val broken = org.eclipse.jgit.storage.file.FileRepositoryBuilder()
            .setGitDir(File(worktree, ".git"))
            .setWorkTree(worktree)
            .build()

        broken.use {
            assertNull("no branch can be read from it", GitService.currentBranch(it))
        }
    }
}
