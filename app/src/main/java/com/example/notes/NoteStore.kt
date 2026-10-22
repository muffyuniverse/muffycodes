package com.example.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where the app keeps its data.
 *
 * The layout itself lives in [FolderStorage] (which has no Android dependency,
 * so it can be unit-tested); this class is the thin Android-facing wrapper that
 * knows where the private files directory is, and that keeps the bindings
 * between folders and git projects.
 *
 * The folders live under `files/folders/`, one directory each, with one file
 * per note inside. That is what lets a folder be its own git repository: the
 * repository sits in that directory and tracks the notes inside it.
 */
class NoteStore(context: Context) {

    private val filesDir = context.filesDir
    private val storage = FolderStorage(File(filesDir, "folders"))

    /** The directory that holds the folder directories. */
    val foldersDir: File get() = File(filesDir, "folders")

    /** The directory of the top level of the app. */
    val rootDir: File get() = storage.rootDir

    // ---- loading -----------------------------------------------------------

    /**
     * Reads the whole tree. Files that used to live in the old single-file
     * storage are migrated into the new layout, so the user's notes are never
     * lost by the upgrade.
     */
    fun load(): NoteCodec.Document {
        val document = storage.load()

        // Nothing in the new layout yet? The old single file may still hold the
        // user's notes.
        if (document.folders.isEmpty() && document.notes.isEmpty()) {
            val migrated = migrateLegacyFile()
            if (migrated != null) return migrated
        }

        return document
    }

    /**
     * Moves the old single-file storage into the new layout. The old file is
     * kept as a backup so nothing is ever lost.
     */
    private fun migrateLegacyFile(): NoteCodec.Document? {
        val legacy = File(filesDir, LEGACY_FILE_NAME)
        if (!legacy.isFile) return null
        return try {
            val document = NoteCodec.decode(legacy.readText())
            if (document.folders.isEmpty() && document.notes.isEmpty()) return null

            storage.save(document.folders, document.notes)

            // Keep the original as a backup, and move it out of the way.
            legacy.copyTo(File(filesDir, "$LEGACY_FILE_NAME.backup"), overwrite = true)
            legacy.renameTo(File(filesDir, "$LEGACY_FILE_NAME.migrated"))

            document
        } catch (e: Exception) {
            // If the migration fails the old file stays where it is, and the next
            // launch will try again.
            null
        }
    }

    // ---- saving ------------------------------------------------------------

    /** Writes the whole tree back to disk. */
    fun save(folders: List<Folder>, notes: List<Note>) {
        storage.save(folders, notes)
    }

    // ---- folders as directories --------------------------------------------

    /** The directory that holds [folder]'s content (and its git repository). */
    fun folderDir(folder: Folder): File = storage.dirFor(folder.id)

    /** The directory of the folder with this id (null means the top level). */
    fun dirForFolder(folderId: String?): File = storage.dirFor(folderId)

    /**
     * The name of the file that holds the note with this id inside the folder
     * directory. This is the path git knows the note by.
     */
    fun fileNameFor(note: Note): String = NoteFile.fileNameFor(note.id)

    // ---- project bindings --------------------------------------------------

    /** The bindings between folders and git projects. */
    fun loadProjects(): ProjectStore {
        val file = File(filesDir, "projects.json")
        if (!file.isFile) return ProjectStore(emptyMap())
        return try {
            ProjectStore.decode(file.readText())
        } catch (e: Exception) {
            ProjectStore(emptyMap())
        }
    }

    /** Saves the bindings between folders and git projects. */
    fun saveProjects(projects: ProjectStore) {
        storage.writeTextAtomic(File(filesDir, "projects.json"), projects.encode())
    }

    private companion object {
        const val LEGACY_FILE_NAME = "notes.json"
    }
}

/**
 * Which git project each folder is attached to.
 *
 * A folder can be its own git repository, so different folders can point at
 * different GitHub repositories.
 */
data class ProjectStore(
    val byFolder: Map<String, GitProject>,
) {
    /** The project attached to [folderId], or null. */
    fun projectFor(folderId: String?): GitProject? =
        folderId?.let { byFolder[it] }

    /** A copy with [folderId] attached to [project] (or detached when null). */
    fun with(folderId: String, project: GitProject?): ProjectStore =
        ProjectStore(
            if (project == null) byFolder - folderId else byFolder + (folderId to project)
        )

    fun encode(): String {
        val array = JSONArray()
        byFolder.forEach { (folderId, project) ->
            array.put(
                JSONObject().apply {
                    put("folderId", folderId)
                    put("name", project.name)
                    put("remoteUrl", project.remoteUrl)
                }
            )
        }
        return JSONObject().put("version", 1).put("projects", array).toString()
    }

    companion object {
        fun decode(text: String): ProjectStore {
            if (text.isBlank()) return ProjectStore(emptyMap())
            val root = JSONObject(text)
            val array = root.optJSONArray("projects") ?: return ProjectStore(emptyMap())
            val map = mutableMapOf<String, GitProject>()
            (0 until array.length()).forEach { i ->
                val obj = array.optJSONObject(i) ?: return@forEach
                val folderId = obj.optString("folderId")
                if (folderId.isEmpty()) return@forEach
                map[folderId] = GitProject(
                    name = obj.optString("name"),
                    remoteUrl = obj.optString("remoteUrl"),
                )
            }
            return ProjectStore(map)
        }
    }
}

/** A git project attached to a folder. */
data class GitProject(
    val name: String,
    val remoteUrl: String,
)
