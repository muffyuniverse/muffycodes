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
 * A file that a folder's history holds and the folder itself no longer does.
 *
 * [name] is the path from the folder shown, so a restore puts it back where it
 * was; [revision] is the version it is read from — the newest one that had it.
 */
data class Gone(val name: String, val revision: String)

/**
 * The tree of one account, as it is on disk.
 *
 * The account directory **is** the app's filesystem: `projects/` holds one
 * folder per project, and a project holds the `repo/` worktree beside whatever
 * local files and folders the user keeps there. Nothing is stored in an index
 * and nothing has an id — a thing's identity is its path, which is what makes
 * the repository on GitHub read like a person wrote it.
 *
 * Two rules decide what may be seen and touched:
 *
 * - **A name starting with `.` belongs to the app and is never shown.** That is
 *   where `.git`, `.gitignore` and the `.muffy` state live, so the rule covers
 *   all of them at once.
 * - **`projects` at the root and `repo` inside a project are structural** — the
 *   app made them, so they cannot be renamed, moved or deleted; everything
 *   inside them is the user's and is free.
 */
class Workspace(val accountDir: File) {

    val projectsDir: File get() = File(accountDir, PROJECTS)

    val stateDir: File get() = File(accountDir, STATE_DIR)

    /**
     * Makes the folders the app itself owns, so that the shape of an account is
     * the same the moment it is signed in as it is after the first project.
     */
    fun ensure() {
        projectsDir.mkdirs()
    }

    // ---- reading -----------------------------------------------------------

    /** The folder's contents, hidden things left out: folders first, then names. */
    fun list(dir: File): List<Entry> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { !isHidden(it.name) }
            .map { Entry(it.name, it, kindOf(it)) }
            .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
    }

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
     * True for a folder the app depends on: `projects` at the account root and
     * `repo` inside a project. Those cannot be renamed, moved or deleted.
     */
    fun isStructural(file: File): Boolean = when {
        file.parentFile?.canonicalFile == accountDir.canonicalFile -> file.name == PROJECTS
        file.parentFile?.parentFile?.canonicalFile == projectsDir.canonicalFile -> file.name == REPO
        else -> false
    }

    /** True when [file] is a project folder: a direct child of `projects/`. */
    fun isProjectFolder(file: File): Boolean =
        file.isDirectory && file.parentFile?.canonicalFile == projectsDir.canonicalFile &&
            !isHidden(file.name)

    /** The names of the projects on this phone, in display order. */
    fun projectNames(): List<String> =
        (projectsDir.listFiles() ?: emptyArray())
            .filter { isProjectFolder(it) }
            .map { it.name }
            .sortedBy { it.lowercase() }

    /** The `repo` folder of a project. */
    fun repoOf(project: String): File = File(File(projectsDir, project), REPO)

    /**
     * True when [file] is inside a project's repository — the part that is synced.
     *
     * This is where the two histories divide, and it is **not** the same as "belongs
     * to a project": a note kept in the project's folder beside `repo/` is a local
     * file, versioned by the account's own history. Telling the two apart by
     * "belongs to a project" loses the change outright, because each history then
     * trusts the other to have committed it and neither does.
     */
    fun inWorktree(file: File): Boolean {
        val path = relative(file) ?: return false
        return path.size >= 3 && path[0] == PROJECTS && path[2] == REPO
    }

    /** The project a path belongs to, or null when it is not inside one. */
    fun projectOf(file: File): String? {
        val path = relative(file) ?: return null
        if (path.size < 2 || path[0] != PROJECTS) return null
        return path[1]
    }

    /**
     * The project this path is inside, including the project folder itself —
     * or null when the path is outside `projects/`.
     */
    fun projectAt(path: List<String>): String? =
        if (path.size >= 2 && path[0] == PROJECTS) path[1] else null

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

    /** Writes text, replacing the file atomically so a crash cannot half-write it. */
    fun write(file: File, text: String) {
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

    /** Makes a folder. Fails when the name is taken or is not a legal name. */
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
        if (!filled) File(folder, KEEPER).writeText("")
    }

    fun createFolder(dir: File, name: String): Result<File> {
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

    /** Makes a file with some text in it. */
    fun createFile(dir: File, name: String, text: String = ""): Result<File> {
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
        if (!into.isDirectory) {
            return Result.failure(IllegalArgumentException("That is not a folder"))
        }
        // The destination is guarded as well as the source, because §3 says no
        // command can target anything the app owns — and a move *into* `projects/`
        // or `.muffy/` targets one just as surely as renaming it. Only the source
        // was checked, so a file could be moved into the app's own bookkeeping and
        // then be hidden from every screen at once: still on disk, unreachable.
        //
        // `repo/` is the exception, and it is the whole point of §3's two crosses:
        // "Move into the repository" is a move into a structural folder, on purpose.
        // So the rule is about the folder this app files things under, not about
        // every folder it made.
        if (isHidden(into.name)) {
            return Result.failure(IllegalArgumentException("That folder belongs to the app"))
        }
        if (isStructural(into) && into.name != REPO) {
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
        return if (file.renameTo(target)) Result.success(target)
        else Result.failure(IllegalStateException("Could not move \"${file.name}\""))
    }

    /** Deletes a file, or a folder and everything inside it. */
    fun delete(file: File): Result<Unit> {
        touchedProblem(file)?.let { return Result.failure(IllegalArgumentException(it)) }
        return if (file.deleteRecursively()) Result.success(Unit)
        else Result.failure(IllegalStateException("Could not delete \"${file.name}\""))
    }

    /** Why [name] cannot be used, or null when it can. */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "A name is needed"
        name == "." || name == ".." -> "That name cannot be used"
        name.startsWith(".") -> "A name cannot start with a dot"
        name.contains('/') || name.contains('\\') -> "A name cannot contain a slash"
        else -> null
    }

    /** Why [file] cannot be renamed, moved or deleted, or null when it can. */
    private fun touchedProblem(file: File): String? = when {
        !file.exists() -> "That is no longer there"
        isHidden(file.name) -> "That belongs to the app"
        isStructural(file) -> "That folder belongs to the app"
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
    fun locked(): Set<String> = readFlags(LOCKED_FILE)

    /** Replaces the locked paths. */
    fun setLocked(paths: Set<String>) = writeFlags(LOCKED_FILE, paths)

    /** True when [file] is locked against editing. */
    fun isLocked(file: File): Boolean {
        val path = relative(file) ?: return false
        return pathString(path) in locked()
    }

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
        write(File(stateDir, name), array.toString())
    }

    // ---- creating a project on disk ---------------------------------------

    /**
     * Makes the folder structure of a project: the project folder and the
     * `repo` worktree inside it. Returns the project folder.
     */
    fun createProjectFolders(name: String): Result<File> {
        projectsDir.mkdirs()
        // A project's folder is named after its repository, and is **not** put
        // through [cleanName]. The name of a repository is GitHub's, and one that
        // was pulled in already has it: renaming `muffyuniverse/muffycodes` to
        // `muffycodes` on the phone would make the folder and the remote disagree
        // about what the project is called. The one name shape governs what a person
        // makes *inside* a folder — files, folders, imports — not the folders that
        // stand for repositories.
        val safe = name
        val folder = File(projectsDir, safe)
        if (folder.exists()) {
            return Result.failure(IllegalArgumentException("There is already a \"$safe\" here"))
        }
        return try {
            File(folder, REPO).mkdirs()
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
        const val PROJECTS = "projects"
        const val REPO = "repo"
        const val STATE_DIR = ".muffy"
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
