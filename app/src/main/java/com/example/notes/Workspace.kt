package com.example.notes

import org.json.JSONArray
import java.io.File

/** What a thing in the tree is. Decided by its extension, as §9 of the design says. */
enum class EntryKind { FOLDER, TEXT, IMAGE }

/** One row of a folder: a file or a folder. */
data class Entry(
    val name: String,
    val file: File,
    val kind: EntryKind,
) {
    val isFolder: Boolean get() = kind == EntryKind.FOLDER
}

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

    private val pinFile: File get() = File(stateDir, PINS_FILE)

    // ---- reading -----------------------------------------------------------

    /** The folder's contents, hidden things left out: folders first, then names. */
    fun list(dir: File): List<Entry> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { !isHidden(it.name) }
            .map { Entry(it.name, it, kindOf(it)) }
            .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
    }

    /** How a file is handled, from the part of its name after the last dot. */
    fun kindOf(file: File): EntryKind = when {
        file.isDirectory -> EntryKind.FOLDER
        file.extension.lowercase() in IMAGE_EXTENSIONS -> EntryKind.IMAGE
        else -> EntryKind.TEXT
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
    fun createFolder(dir: File, name: String): Result<File> {
        val safe = name.trim()
        nameProblem(safe)?.let { return Result.failure(IllegalArgumentException(it)) }
        val target = File(dir, safe)
        if (target.exists()) {
            return Result.failure(IllegalArgumentException("There is already a \"$safe\" here"))
        }
        return if (target.mkdirs()) Result.success(target)
        else Result.failure(IllegalStateException("Could not make the folder"))
    }

    /** Makes a file with some text in it. */
    fun createFile(dir: File, name: String, text: String = ""): Result<File> {
        val safe = name.trim()
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

    /** Renames [file] to [newName] in the same folder. */
    fun rename(file: File, newName: String): Result<File> {
        val safe = newName.trim()
        touchedProblem(file)?.let { return Result.failure(IllegalArgumentException(it)) }
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
    fun pins(): Set<String> {
        if (!pinFile.isFile) return emptySet()
        return try {
            val array = JSONArray(pinFile.readText())
            (0 until array.length()).mapNotNull { array.optString(it)?.takeIf(String::isNotEmpty) }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /** Replaces the pinned paths. */
    fun setPins(paths: Set<String>) {
        stateDir.mkdirs()
        val array = JSONArray()
        paths.sorted().forEach { array.put(it) }
        write(pinFile, array.toString())
    }

    /** True when [file] is pinned. */
    fun isPinned(file: File): Boolean {
        val path = relative(file) ?: return false
        return pathString(path) in pins()
    }

    // ---- creating a project on disk ---------------------------------------

    /**
     * Makes the folder structure of a project: the project folder and the
     * `repo` worktree inside it. Returns the project folder.
     */
    fun createProjectFolders(name: String): Result<File> {
        projectsDir.mkdirs()
        val folder = File(projectsDir, name)
        if (folder.exists()) {
            return Result.failure(IllegalArgumentException("There is already a \"$name\" here"))
        }
        return try {
            File(folder, REPO).mkdirs()
            Result.success(folder)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not make the project folder"))
        }
    }

    companion object {
        const val PROJECTS = "projects"
        const val REPO = "repo"
        const val STATE_DIR = ".muffy"
        const val PINS_FILE = "pins.json"

        /** The extensions treated as pictures rather than text (§9). */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        /** The default extension for a new file (§2: "Default `.txt`"). */
        const val DEFAULT_EXTENSION = "txt"

        /** The name a new file gets when the user gives no extension. */
        fun withDefaultExtension(name: String): String =
            if ('.' in name.removePrefix(".")) name else "$name.$DEFAULT_EXTENSION"
    }
}
