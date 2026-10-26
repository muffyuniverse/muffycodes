package com.example.notes

import org.json.JSONArray
import java.io.File

/**
 * What a thing in the tree is. Decided by its extension, as §9 of the design says.
 *
 * Three kinds of file, and the third one matters more than it looks. The design
 * says "anything else → named as unknown; rename, move and delete only", and
 * that list is not a tidiness rule: a `.zip`, a `.pdf` and an `.apk` are not
 * text, so opening one in the editor and saving it back would rewrite real bytes
 * as UTF-8 and destroy the file. `TEXT` is the fallback for anything that looks
 * like text, not for everything.
 */
enum class EntryKind { FOLDER, TEXT, IMAGE, UNKNOWN }

/** One row of a folder: a file or a folder. */
data class Entry(
    val name: String,
    val file: File,
    val kind: EntryKind,
) {
    val isFolder: Boolean get() = kind == EntryKind.FOLDER
}

/**
 * Something a folder's history holds and the folder itself no longer does.
 *
 * [name] is the name as the folder showed it, so a restore puts it back where it
 * was; [revision] is the version it is read from — the newest one that had it.
 * A folder is offered as one row rather than as every file it held, because a
 * person deleting a folder thinks of it as one thing and bringing back five
 * separate names is not the same act.
 */
data class Gone(
    val name: String,
    val revision: String,
    val isFolder: Boolean = false,
)

/**
 * The tree of one account, as it is on disk.
 *
 * The account directory **is** the app's filesystem: **one folder per project, at the root**,
 * and each of those folders **is** the repository — its `.git`, its `.gitignore` and the person's
 * files live together, so the thing on GitHub is the folder they are looking at. Nothing is stored
 * in an index and nothing has an id — a thing's identity is its path, which is what makes the
 * repository read like a person wrote it.
 *
 * Two rules decide what may be seen and touched:
 *
 * - **A name starting with `.` belongs to the app and is never shown.** That is
 *   where `.git` and the `.muffy` state live, so the rule covers all of them at once.
 * - **`agent/` inside a project is the app's** — the record's folder, named in the
 *   project's `.git/info/exclude` by the app itself — so it cannot be renamed, moved or deleted,
 *   and its `session.log` cannot be written by anyone but [AgentLog].
 */
class Workspace(val accountDir: File) {

    val stateDir: File get() = File(accountDir, STATE_DIR)

    /** The folder of one project. **This is the repository** — `.git` is in here. */
    fun projectDir(project: String): File = File(accountDir, project)

    /**
     * Makes the folder an account lives in, so that an empty account and a new one look the same.
     *
     * Nothing is created for a project here: a project's folder is made when it is made, and its
     * repository is initialised by [ProjectGit] into that same folder.
     */
    fun ensure() {
        accountDir.mkdirs()
    }

    /**
     * The two lines that keep the app's own folders out of the project's repository.
     *
     * **They go in `.git/info/exclude`, not in the person's `.gitignore`.** That file is git's own
     * per-clone list of things this working copy ignores, it is not part of any commit, and it
     * cannot be pushed — which is exactly what these two lines are: a rule of *this phone's* app,
     * not a fact about the person's repository. Writing them into `.gitignore` put the app's
     * bookkeeping into the repository the person owns and, because a `.gitignore` is a tracked
     * file, made a project that had just arrived say "1 changed" before anybody touched it.
     *
     * A person's own "Add to `.gitignore`" is a different act and still writes `.gitignore`: that
     * one is them telling GitHub to ignore something, and it belongs in the history.
     *
     * Nothing happens for a folder with no repository yet — there is nowhere to put the lines and
     * nothing to exclude from until `ProjectGit` has made one.
     */
    fun ensureIgnore(projectDir: File) {
        val git = File(projectDir, ".git")
        if (!git.isDirectory) return
        val file = File(git, "info" + File.separator + "exclude")
        val current = if (file.isFile) read(file) else ""
        val missing = APP_IGNORES.filter { line ->
            current.lineSequence().none { it.trim() == line }
        }
        if (missing.isEmpty()) return
        val text = buildString {
            append(current)
            if (current.isNotEmpty() && !current.endsWith("\n")) append("\n")
            if (current.isEmpty()) {
                appendLine("# The app's own bookkeeping: the agent's record and its flags. Not shipped.")
            }
            missing.forEach { appendLine(it) }
        }
        file.parentFile?.mkdirs()
        put(file, text)
    }

    /** The folder's contents, hidden things left out: folders first, then names. */
    fun list(dir: File): List<Entry> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { !isHidden(it.name) && !isAppOwned(it) }
            .map { Entry(it.name, it, kindOf(it)) }
            .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
    }

    /**
     * True for a file the app keeps in a place a person browses: the model's config.
     *
     * §3's rule is that everything the app made is invisible, and a hidden name is how that is
     * usually said. `model_conf.json` breaks that rule by living in the account's root, where the
     * projects list is drawn — so it is named here and left out of the list, rather than being
     * renamed to a dot and made unreachable to anyone who wants to read what their agent is set to.
     *
     * **It lives at the root because it is the account's, not a project's:** the model is one
     * conversation's setting for the whole phone, and a project does not bring its own.
     */
    fun isAppOwned(file: File): Boolean =
        file.parentFile?.canonicalFile == accountDir.canonicalFile && file.name in APP_FILES

    /**
     * How a file is handled, from the part of its name after the last dot.
     *
     * A file with no extension is text, because that is what a person typing a
     * name gets — `notes` is a note. A file with an extension this app does not
     * recognise is unknown, and is never opened as text.
     */
    fun kindOf(file: File): EntryKind = when {
        file.isDirectory -> EntryKind.FOLDER
        file.extension.lowercase() in IMAGE_EXTENSIONS -> EntryKind.IMAGE
        file.extension.isEmpty() -> EntryKind.TEXT
        file.extension.lowercase() in TEXT_EXTENSIONS -> EntryKind.TEXT
        else -> EntryKind.UNKNOWN
    }

    /** True for a name the app made: never shown, never touched. */
    fun isHidden(name: String): Boolean = name.startsWith(".")

    /**
     * True for the agent's record, `<project>/agent/session.log` — and for the second file an older
     * build wrote, `context.log`, until [AgentLog] folds it in.
     *
     * **The record is readable and not writable, and that is a rule about the person too.** The
     * whole value of the file is that it is what happened and what the model is sent; a person who
     * can save over it has an agent whose memory is not evidence of anything. So the app refuses
     * the save, as it already refuses to rename `agent/` — the app depends on this file the way it
     * depends on that folder.
     *
     * Three writers remain, and each is the app's own act, inside [AgentLog]: the turn appends
     * rows, the autotrim shortens the file, a reset marks every row `app`, and the agent's
     * compaction flips named rows to `out`. None of them goes through this class. What a person
     * can still do is [delete] the project, which takes the record with it — the one lever they had
     * before, kept.
     *
     * **This is the app refusing, not the system.** Any file manager on the phone can edit these
     * files; nothing here pretends otherwise.
     */
    fun isRecord(file: File): Boolean {
        val dir = file.parentFile?.canonicalFile ?: return false
        if (dir.name != AgentLog.DIR) return false
        if (dir.parentFile?.parentFile?.canonicalFile != accountDir.canonicalFile) return false
        return file.name == AgentLog.SESSION || file.name == AgentLog.CONTEXT
    }

    /**
     * True for a folder the app depends on: a project's `agent/`.
     *
     * There is nothing structural at the account root any more. The two levels the app used to
     * own — `projects/` at the root and a repository folder inside each project — are gone: the
     * project *is* the folder, which is the whole of the consolidation.
     */
    fun isStructural(file: File): Boolean =
        file.isDirectory && file.name == AgentLog.DIR &&
            file.parentFile?.parentFile?.canonicalFile == accountDir.canonicalFile

    /**
     * True when [file] is a project: a direct child folder of the account root **that holds a
     * repository.**
     *
     * The second half is not a detail. A project's folder *is* its repository, so a folder at the
     * root with no `.git` in it is not a project — and calling it one made the app claim things it
     * could not do: `projectOf` answered with a name, every change inside tried to commit and
     * silently wrote nothing (a commit that fails and a commit with nothing to do look identical,
     * which is the failure mode this app's history was built to avoid), and the list drew a project
     * row for a folder with nothing behind it.
     */
    fun isProjectFolder(file: File): Boolean =
        file.isDirectory && file.parentFile?.canonicalFile == accountDir.canonicalFile &&
            !isHidden(file.name) && File(file, ".git").exists()

    /** The names of the projects on this phone, in display order. */
    fun projectNames(): List<String> =
        (accountDir.listFiles() ?: emptyArray())
            .filter { isProjectFolder(it) }
            .map { it.name }
            .sortedBy { it.lowercase() }

    /** The folder of a project — which is also its repository. */
    fun repoOf(project: String): File = projectDir(project)

    /**
     * True when [file] is inside a project — and now that means inside a repository.
     *
     * The two histories this used to divide are one: every file below a project is in that
     * project's repository, and the only things outside a project are the app's own file at the
     * root and its hidden state, which are nobody's history.
     */
    fun inWorktree(file: File): Boolean = projectOf(file) != null

    /** The project a path belongs to, or null when it is not inside one. */
    fun projectOf(file: File): String? {
        val path = relative(file) ?: return null
        return projectAt(path)
    }

    /**
     * The project this path is inside, including the project folder itself —
     * or null when the path is at the root.
     */
    fun projectAt(path: List<String>): String? {
        val first = path.firstOrNull() ?: return null
        val dir = File(accountDir, first)
        return if (isProjectFolder(dir)) first else null
    }

    /** The path of [file] from the account root, or null when it is not below it. */
    fun relative(file: File): List<String>? {
        val root = accountDir.canonicalFile.toPath()
        val target = try {
            file.canonicalFile.toPath()
        } catch (e: Exception) {
            file.absoluteFile.toPath()
        }
        if (!target.startsWith(root)) return null
        val names = root.relativize(target).toString()
        return if (names.isEmpty()) emptyList() else names.split(File.separatorChar)
    }

    /** The file at a path from the account root. */
    fun fileAt(path: List<String>): File = path.fold(accountDir) { dir, name -> File(dir, name) }

    /** A path from the account root as one string, as git names it. */
    fun pathString(path: List<String>): String = path.joinToString("/")

    // ---- text --------------------------------------------------------------

    /** Reads a file as text. Unreadable files read as empty, never as a crash. */
    fun read(file: File): String = try {
        file.readText()
    } catch (e: Exception) {
        ""
    }

    /** Writes text, replacing the file atomically so a crash cannot half-write it.
     *
     * **A locked file is not written, and the refusal is here rather than at the call
     * sites.** It used to be checked only by the screen's own code, which meant the rule
     * held for exactly the callers that remembered it: the editor did, and a restore, an
     * import or a promoted agent did not. A lock that only some writers honour is not a
     * lock, it is a habit.
     *
     * Returns false when the write was refused, so a caller that must say something can.
     */
    /**
     * Writes a file, unless it is locked or it is the agent's record.
     *
     * **There is no second door, and that is the simplification.** The record used to need one,
     * because the agent's compaction was a text edit of the context file. Compaction is now a list
     * of ids applied by [AgentLog] itself, so nothing outside that class writes the record at all —
     * not the person, not the agent's tools, and not a caller who thought of a reason.
     */
    fun write(file: File, text: String): Boolean {
        if (isLocked(file)) return false
        if (isRecord(file)) return false
        put(file, text)
        return true
    }

    /**
     * Writes without asking about locks.
     *
     * For the app's own bookkeeping and its own markers — the flag files in `.muffy` and
     * the `.gitkeep` that gives an empty folder a version. Those are never the person's
     * documents, and routing them through [write] would mean the app could lock itself
     * out of remembering what is locked.
     */
    private fun put(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    // ---- changing the tree -------------------------------------------------

    /** Makes a folder. Fails when the name is taken, the name is illegal, or [dir] is locked. */
    /**
     * Puts a hidden marker in an empty folder.
     *
     * Git cannot record an empty folder, so without one an empty folder would have
     * no version to come back to — and §4 promises that any folder state can be
     * rolled back. The marker is the app's (a dot name, never shown, never
     * searched), and it is the only way to keep that promise for a folder that
     * holds nothing yet.
     */
    fun markTrackable(folder: File) {
        if (!folder.isDirectory) return
        val filled = folder.listFiles()?.any { !isHidden(it.name) || it.name == KEEPER } == true
        if (!filled) put(File(folder, KEEPER), "")
    }

    fun createFolder(dir: File, name: String): Result<File> {
        lockProblem(dir)?.let { return Result.failure(IllegalArgumentException(it)) }
        val safe = cleanName(name, "folder")
        nameProblem(safe)?.let { return Result.failure(IllegalArgumentException(it)) }
        val target = File(dir, safe)
        if (target.exists()) {
            return Result.failure(IllegalArgumentException("There is already a \"$safe\" here"))
        }
        return if (target.mkdirs()) {
            markTrackable(target)
            Result.success(target)
        } else {
            Result.failure(IllegalStateException("Could not make the folder"))
        }
    }

    /** Makes a file with some text in it. Refused when [dir] is locked. */
    fun createFile(dir: File, name: String, text: String = ""): Result<File> {
        lockProblem(dir)?.let { return Result.failure(IllegalArgumentException(it)) }
        val safe = cleanName(name, "untitled")
        nameProblem(safe)?.let { return Result.failure(IllegalArgumentException(it)) }
        val target = File(dir, safe)
        if (target.exists()) {
            return Result.failure(IllegalArgumentException("There is already a \"$safe\" here"))
        }
        return try {
            write(target, text)
            Result.success(target)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not make the file"))
        }
    }

    /**
     * Renames [file] to [newName] in the same folder.
     *
     * [tidy] is false for a project folder, which is named after its repository: the
     * name of a repository is GitHub's, and one that was pulled in already has it.
     * Putting that through [cleanName] would rename `muffyuniverse/muffycodes` on the
     * phone and leave the remote called something else.
     */
    fun rename(file: File, newName: String, tidy: Boolean = true): Result<File> {
        touchedProblem(file)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (!tidy) {
            val asIs = newName.trim()
            val target = File(file.parentFile, asIs)
            if (target.canonicalFile == file.canonicalFile) return Result.success(file)
            if (target.exists()) {
                return Result.failure(IllegalArgumentException("There is already a \"$asIs\" here"))
            }
            return if (file.renameTo(target)) Result.success(target)
            else Result.failure(IllegalStateException("Could not rename \"${file.name}\""))
        }
        // A new name without an extension keeps the one the file has. The extension
        // is not decoration here: it is how §9 decides what may be edited and what
        // must be left alone, so dropping it by accident would turn a picture into
        // "text" and let the editor rewrite its bytes.
        val typed = cleanName(newName, file.nameWithoutExtension.ifBlank { "untitled" })
        val safe = if ('.' !in typed && file.isFile && file.extension.isNotEmpty()) {
            "$typed.${cleanName(file.extension)}"
        } else {
            typed
        }
        nameProblem(safe)?.let { return Result.failure(IllegalArgumentException(it)) }
        val target = File(file.parentFile, safe)
        if (target.canonicalFile == file.canonicalFile) return Result.success(file)
        if (target.exists()) {
            return Result.failure(IllegalArgumentException("There is already a \"$safe\" here"))
        }
        return if (file.renameTo(target)) Result.success(target)
        else Result.failure(IllegalStateException("Could not rename \"${file.name}\""))
    }

    /**
     * Moves [file] into the folder [into]. A folder cannot be moved into itself
     * or into one of its own descendants.
     */
    fun move(file: File, into: File): Result<File> {
        touchedProblem(file)?.let { return Result.failure(IllegalArgumentException(it)) }
        lockProblem(into)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (!into.isDirectory) {
            return Result.failure(IllegalArgumentException("That is not a folder"))
        }
        // The destination is guarded as well as the source, because §3 says no
        // command can target anything the app owns — and a move *into* a hidden folder
        // targets one just as surely as renaming it. Only the source was checked, so a file
        // could be moved into the app's own bookkeeping and then be hidden from every screen
        // at once: still on disk, unreachable.
        //
        // There is no exception any more. There used to be one, for the repository folder,
        // because "move into the repository" was a move a person wanted. The repository is the
        // project now, so the rule is simply the rule.
        if (isHidden(into.name)) {
            return Result.failure(IllegalArgumentException("That folder belongs to the app"))
        }
        if (isStructural(into)) {
            return Result.failure(IllegalArgumentException("That folder belongs to the app"))
        }
        if (file.isDirectory && (into.canonicalPath == file.canonicalPath ||
                into.canonicalPath.startsWith(file.canonicalPath + File.separator))
        ) {
            return Result.failure(
                IllegalArgumentException("A folder cannot be moved inside itself")
            )
        }
        val target = File(into, file.name)
        if (target.exists()) {
            return Result.failure(
                IllegalArgumentException("There is already a \"${file.name}\" there")
            )
        }
        // **A rename first, because it is atomic and instant** — and then bytes, because a rename
        // cannot move anything between two filesystems. Android has more than one: a project on the
        // phone's own storage and one on a card are different volumes, and `renameTo` reports that by
        // returning false rather than throwing, so the old code said "could not move" for a move that
        // a copy would have made.
        //
        // Copy-then-delete is not atomic, and that is accepted knowingly: a crash in between leaves
        // the file in both places, which a person can see and fix. Refusing the move leaves them with
        // no way to do it at all.
        if (file.renameTo(target)) return Result.success(target)
        return try {
            if (file.isDirectory) {
                file.copyRecursively(target, overwrite = false)
            } else {
                file.copyTo(target, overwrite = false)
            }
            if (!file.deleteRecursively()) {
                // Both copies exist. Reporting a move that did not happen would hide a duplicate.
                target.deleteRecursively()
                return Result.failure(
                    IllegalStateException(
                        "Could not move \"${file.name}\" — the copy would not delete from where it was"
                    )
                )
            }
            Result.success(target)
        } catch (e: Throwable) {
            // The copy failed part way: what was written is not the file, so none of it is kept.
            target.deleteRecursively()
            Result.failure(IllegalStateException("Could not move \"${file.name}\""))
        }
    }

    /**
     * Copies [file] into the folder [into], leaving the original where it is.
     *
     * **A name that is taken is refused, in a copy as much as in a move.** `milk.txt` becoming
     * `milk 2.txt` sounds helpful and is the opposite: the person asked for `milk.txt`, the answer
     * said "copied", and the file is somewhere they did not ask for with a name they will not
     * recognise in a list. The refusal is one sentence they can act on, and it is the same sentence
     * a move gives, so the app has one answer to one question.
     *
     * An **import** is different and keeps [freeName]: there the person never chose the name, so
     * there is nothing they asked for that a number could contradict.
     *
     * A folder cannot be copied into itself: the copy would contain the folder it was
     * made from, forever.
     */
    fun copy(file: File, into: File, deadline: Deadline = Deadline.none()): Result<File> {
        // **A copy is a read of the source, so a lock does not stop it.** That is the whole
        // distinguishing line between a lock and a permission: copying a locked file is
        // how a person works on a draft of something they froze, and the original is
        // untouched either way. The destination is checked instead, because writing *into*
        // a locked folder is a change to it.
        coreProblem(file)?.let { return Result.failure(IllegalArgumentException(it)) }
        lockProblem(into)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (!into.isDirectory) {
            return Result.failure(IllegalArgumentException("That is not a folder"))
        }
        if (isHidden(into.name)) {
            return Result.failure(IllegalArgumentException("That folder belongs to the app"))
        }
        if (isStructural(into)) {
            return Result.failure(IllegalArgumentException("That folder belongs to the app"))
        }
        if (file.isDirectory && (into.canonicalPath == file.canonicalPath ||
                into.canonicalPath.startsWith(file.canonicalPath + File.separator))
        ) {
            return Result.failure(
                IllegalArgumentException("A folder cannot be copied inside itself")
            )
        }
        val target = File(into, file.name)
        if (target.exists()) {
            return Result.failure(
                IllegalArgumentException("There is already a \"${file.name}\" there"),
            )
        }
        return try {
            if (file.isDirectory) {
                copyTree(file, target, deadline)
            } else {
                file.copyTo(target, overwrite = false)
            }
            Result.success(target)
        } catch (e: StoppedException) {
            // **Half a folder is worse than none, and a stop must not leave one.** The copy is
            // removed before the stop is passed on, which is what lets its sentence say that
            // nothing was kept and be telling the truth.
            target.deleteRecursively()
            throw e
        } catch (e: Exception) {
            // Half a folder is worse than none: a tree copy that failed leaves the
            // destination looking complete from the outside.
            target.deleteRecursively()
            Result.failure(IllegalStateException("Could not copy \"${file.name}\""))
        }
    }

    /** Every file and folder under [from], into [to], keeping the shape. */
    private fun copyTree(from: File, to: File, deadline: Deadline) {
        if (from.isDirectory) {
            to.mkdirs()
            from.listFiles()?.forEach { child ->
                if (deadline.expired) throw StoppedException(Stopped.body("copy", deadline))
                copyTree(child, File(to, child.name), deadline)
            }
        } else {
            to.parentFile?.mkdirs()
            from.copyTo(to, overwrite = false)
        }
    }

    /** Deletes a file, or a folder and everything inside it. */
    fun delete(file: File, deadline: Deadline = Deadline.none()): Result<Unit> {
        touchedProblem(file)?.let { return Result.failure(IllegalArgumentException(it)) }
        return if (removeTree(file, deadline)) Result.success(Unit)
        else Result.failure(IllegalStateException("Could not delete \"${file.name}\""))
    }

    /**
     * Deletes [file] and everything under it, asking the deadline between entries.
     *
     * **The one removal that can stop half way.** A single file is one call and is either gone or
     * still there; a folder is a walk, and a walk interrupted leaves a folder that is neither. The
     * question is asked per entry so the answer stays current, and the exception it throws says
     * exactly that — [Stopped.partway], not "nothing was kept".
     */
    private fun removeTree(file: File, deadline: Deadline): Boolean {
        if (deadline.expired) throw StoppedException(Stopped.partway("delete", deadline))
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!removeTree(child, deadline)) return false
            }
        }
        return file.delete()
    }

    /** Why [name] cannot be used, or null when it can. */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "A name is needed"
        name == "." || name == ".." -> "That name cannot be used"
        name.startsWith(".") -> "A name cannot start with a dot"
        name.contains('/') || name.contains('\\') -> "A name cannot contain a slash"
        else -> null
    }

    /** The locked paths. Read once and kept, because every write asks. */
    private var lockedCache: Set<String>? = null

    /**
     * Why [file] cannot be renamed, moved or deleted, or null when it can.
     *
     * Three questions in order: is it there, does it belong to the app, and did the
     * person lock it. The lock is last and is the one that can change without the file
     * changing at all.
     */
    private fun touchedProblem(file: File): String? =
        coreProblem(file) ?: lockProblem(file)

    /** Why [file] cannot be handled at all, ignoring its lock. */
    private fun coreProblem(file: File): String? = when {
        !file.exists() -> "That is no longer there"
        isHidden(file.name) -> "That belongs to the app"
        isAppOwned(file) -> "That belongs to the app"
        isStructural(file) -> "That folder belongs to the app"
        // The record is not the person's to move, rename or delete — the same rule that stops them
        // saving over it. Deleting the project still takes it, which is the lever they keep.
        isRecord(file) -> "That is the agent's record"
        else -> null
    }

    // ---- pinned flags ------------------------------------------------------

    /** The paths that are pinned, as git-style paths from the account root. */
    fun pins(): Set<String> = readFlags(PINS_FILE)

    /** Replaces the pinned paths. */
    fun setPins(paths: Set<String>) = writeFlags(PINS_FILE, paths)

    /** True when [file] is pinned. */
    fun isPinned(file: File): Boolean {
        val path = relative(file) ?: return false
        return pathString(path) in pins()
    }

    // ---- locked files -------------------------------------------------------

    /**
     * The paths that are locked, as paths from the account root.
     *
     * A locked file is one the app will only ever **show**. It is a property of
     * this phone's copy, not of the file: nothing is written into the file or into
     * its history, so locking does not make a commit and unlocking does not undo
     * one. Two reasons it is stored here rather than in the file itself — a marker
     * in the text would be content the person did not write, and a marker in the
     * name would change the name the file has.
     */
    fun locked(): Set<String> = lockedCache ?: readFlags(LOCKED_FILE).also { lockedCache = it }

    /** Replaces the locked paths. */
    fun setLocked(paths: Set<String>) = writeFlags(LOCKED_FILE, paths)

    /**
     * True when [file] is locked against editing.
     *
     * **A lock covers what is inside it, and the separator is what makes that exact.**
     * Locking a folder has to protect its children — that is what locking a folder is
     * for, and it was not true: the check compared one whole path, so a person could
     * edit every file in a folder they had locked. The comparison is `locked + "/"`
     * rather than a bare prefix, or locking `milk.txt` would also lock `milk.txt.bak`.
     */
    fun isLocked(file: File): Boolean {
        val path = relative(file)?.let(::pathString) ?: return false
        val locked = locked()
        if (locked.isEmpty()) return false
        if (path in locked) return true
        return locked.any { it.isNotEmpty() && path.startsWith("$it/") }
    }

    /**
     * True when [file] itself was locked, rather than sitting inside a locked folder.
     *
     * The row's lock icon answers "did I lock this?", which is a different question from
     * "may this be written?": a folder's twenty files are all read-only, and twenty lock
     * icons would say the person had done twenty things.
     */
    fun isLockedHere(file: File): Boolean {
        val path = relative(file)?.let(::pathString) ?: return false
        return path in locked()
    }

    /** Why [file] cannot be written because of a lock, or null when it can. */
    fun lockProblem(file: File): String? =
        if (isLocked(file)) "\"${file.name}\" is locked, so it cannot be changed" else null


    /**
     * A name list in `.muffy`, which is where every flag of the app lives.
     *
     * A file that cannot be read is an empty set, not a crash: flags are a
     * convenience, and a damaged one must not stop the app from opening the folder
     * it describes.
     */
    private fun readFlags(name: String): Set<String> {
        val file = File(stateDir, name)
        if (!file.isFile) return emptySet()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { array.optString(it)?.takeIf(String::isNotEmpty) }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun writeFlags(name: String, paths: Set<String>) {
        stateDir.mkdirs()
        val array = JSONArray()
        paths.sorted().forEach { array.put(it) }
        put(File(stateDir, name), array.toString())
        // **The cache is updated where the file is written, or a rewrite lifts a lock.**
        // `rewriteFlagPaths` moved every locked path to the new folder name after the
        // `repo` → `GitHub` migration, and the cache kept the old paths: the lock read as
        // gone, on a file nobody had unlocked. One writer, one cache.
        if (name == LOCKED_FILE) lockedCache = paths
    }

    // ---- creating a project on disk ---------------------------------------

    /**
     * Makes a project's folder, and the two lines that keep the app's own things out of its
     * repository. Returns the project folder.
     *
     * **The folder is the repository.** It used to be a folder holding a `repo/` worktree, which
     * meant a person looking at their project saw a folder they did not make, inside which was the
     * thing that actually synced. There is one folder now, and [ProjectGit] initialises a
     * repository in it.
     */
    fun createProjectFolders(name: String): Result<File> {
        accountDir.mkdirs()
        // A project's folder is named after its repository, and is **not** put
        // through [cleanName]. The name of a repository is GitHub's, and one that
        // was pulled in already has it: renaming `muffyuniverse/muffycodes` to
        // `muffycodes` on the phone would make the folder and the remote disagree
        // about what the project is called. The one name shape governs what a person
        // makes *inside* a folder — files, folders, imports — not the folders that
        // stand for repositories.
        val safe = name
        val folder = File(accountDir, safe)
        if (folder.exists()) {
            return Result.failure(IllegalArgumentException("There is already a \"$safe\" here"))
        }
        return try {
            folder.mkdirs()
            ensureIgnore(folder)
            Result.success(folder)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not make the project folder"))
        }
    }

    companion object {
        /** Enough for any real name, and well under a filesystem's limit. */
        const val MAX_STEM = 60

        /** Extensions are short; anything longer is not one. */
        const val MAX_EXTENSION = 12

        /**
         * [name] in the one shape the app writes, or [fallback] when nothing of it
         * survives — a name made only of symbols becomes the fallback rather than
         * a row of underscores.
         */
        fun cleanName(name: String, fallback: String = "untitled"): String {
            val trimmed = name.trim()
            // The extension is what follows the last dot, and only when something
            // is left of it: `.txt` is a name, not an extension.
            val dot = trimmed.lastIndexOf('.')
            val rawStem = if (dot > 0) trimmed.substring(0, dot) else trimmed
            val rawExtension = if (dot > 0) trimmed.substring(dot + 1) else ""
            val stem = joined(rawStem).take(MAX_STEM).trim('_').ifBlank { fallback }
            val extension = joined(rawExtension).replace("_", "").take(MAX_EXTENSION)
            return if (extension.isEmpty()) stem else "$stem.$extension"
        }

        /** [text] lowercased, with every run of anything else turned into one `_`. */
        private fun joined(text: String): String {
            val out = StringBuilder(text.length)
            var lastWasSeparator = true
            for (ch in text.lowercase()) {
                if (ch.isLetterOrDigit()) {
                    out.append(ch)
                    lastWasSeparator = false
                } else if (!lastWasSeparator) {
                    out.append('_')
                    lastWasSeparator = true
                }
            }
            return out.toString()
        }
        /**
         * A name in [dir] that is not taken: the name itself, or "name 2", "name 3"…
         *
         * The extension stays on the end, where a person and the app's own reader both
         * expect it: `milk.txt` becomes `milk 2.txt`, never `milk.txt 2`.
         *
         * It is here, beside [cleanName], because it is a **naming rule** rather than
         * something a particular account does.
         *
         * **An import is its only caller, and that is the point.** A copy or a move refuses an
         * occupied name, because the person named the thing and a number on the end would put it
         * somewhere they did not ask for. An import never had a name to contradict: the file
         * arrived from outside and the app has to put it somewhere, so a free name is a service
         * rather than a surprise.
         */
        fun freeName(dir: File, name: String): String {
            if (!File(dir, name).exists()) return name
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val tail = if (dot > 0) name.substring(dot) else ""
            var n = 2
            while (File(dir, "$stem $n$tail").exists()) n++
            return "$stem $n$tail"
        }

        const val STATE_DIR = ".muffy"

        /** A project's ignore file, and the two lines the app keeps in it. */
        const val IGNORE = ".gitignore"
        val APP_IGNORES = listOf("agent/", ".muffy/")

        /**
         * The hidden marker that gives an empty folder a version.
         *
         * A folder with nothing in it does not exist as far as git is concerned:
         * git records files and the paths that hold them, never a directory on its
         * own. So an empty folder would vanish from history, and the app promises
         * that any change can be taken back — including making a folder. The marker
         * is written when a folder is made and left alone afterwards, and it is
         * hidden by the same rule that hides everything else the app owns.
         */
        const val KEEPER = ".gitkeep"
        /** The app's own files that live where a person browses, so they are not shown. */
        val APP_FILES = setOf(ModelConfigFile.NAME)

        const val PINS_FILE = "pins.json"
        const val LOCKED_FILE = "locked.json"

        /** The extensions treated as pictures rather than text (§9). */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        /**
         * The extensions the app will open in the editor (§9: "code, anything not
         * listed below").
         *
         * This is a list rather than "everything that is not an image" precisely
         * because the other way is destructive: a file type nobody thought of would
         * default to being editable text, and saving it would corrupt it. A type
         * nobody thought of now lands on `UNKNOWN`, which is safe — it can still be
         * renamed, moved and deleted. Adding a language here is how a new kind of
         * text becomes editable.
         */
        val TEXT_EXTENSIONS = setOf(
            // Writing and documents
            "txt", "md", "markdown", "text", "rst", "adoc", "org", "log",
            // Configuration and data
            "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
            "xml", "csv", "tsv", "env", "gitignore", "license",
            // Code
            "kt", "kts", "java", "py", "rb", "js", "mjs", "cjs", "ts", "tsx",
            "jsx", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "swift",
            "sh", "bash", "zsh", "fish", "ps1", "bat", "sql", "php", "pl",
            "lua", "dart", "scala", "clj", "ex", "exs", "erl", "hs", "r",
            // Markup and web
            "html", "htm", "css", "scss", "sass", "less", "vue", "svelte",
            "svg",
            // Build and scripts
            "gradle", "cmake", "make", "mk", "dockerfile", "containerfile",
        )

        /** The default extension for a new file (§2: "Default `.txt`"). */
        const val DEFAULT_EXTENSION = "txt"

        /** The name a new file gets when the user gives no extension. */
        fun withDefaultExtension(name: String): String =
            if ('.' in name.removePrefix(".")) name else "$name.$DEFAULT_EXTENSION"
    }
}
