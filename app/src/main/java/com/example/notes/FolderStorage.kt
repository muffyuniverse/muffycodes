package com.example.notes

import java.io.File

/**
 * The on-disk layout of the app, without any Android dependency.
 *
 * Every app folder gets its own directory, and the directories are flat
 * (siblings, not nested):
 *
 * ```
 * folders/
 *   __root__/            the top level of the app
 *     name               the name of the folder
 *     8f1c….md           one file per note
 *   <folder-id>/         one directory per folder
 *     name
 *     parent
 *     8f1c….md
 * ```
 *
 * The directories are deliberately flat. Each folder can be its own git
 * repository — the repository lives in that folder's directory — and flat
 * directories mean no folder is ever inside another folder's repository, which
 * would turn the inner folder into a git link.
 *
 * Because every note is a file of its own, a git project can track each note
 * separately, so the version history of a note is the history of that note.
 */
class FolderStorage(private val foldersDir: File) {

    /** The directory of the top level of the app. */
    val rootDir: File get() = File(foldersDir, ROOT_ID)

    /** The directory of the folder with this id (null means the top level). */
    fun dirFor(folderId: String?): File =
        if (folderId == null) rootDir else File(foldersDir, folderId)

    /**
     * Reads the whole tree. The folder structure comes from the directories on
     * disk, and each folder's notes come from the files in its directory.
     */
    fun load(): NoteCodec.Document {
        val folders = mutableListOf<Folder>()
        val notes = mutableListOf<Note>()

        if (rootDir.isDirectory) {
            readNotes(rootDir, null, notes)
        }
        readChildFolders(folders, notes)

        return NoteCodec.Document(folders, notes)
    }

    /** Reads every folder directory. */
    private fun readChildFolders(
        folders: MutableList<Folder>,
        notes: MutableList<Note>,
    ) {
        if (!foldersDir.isDirectory) return
        foldersDir.listFiles()
            ?.filter { it.isDirectory && it.name != ROOT_ID }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                val id = dir.name
                val name = File(dir, NAME_FILE).takeIf { it.isFile }?.readText().orEmpty()
                val parentId = File(dir, PARENT_FILE).takeIf { it.isFile }?.readText()?.trim()
                    ?.takeIf { it.isNotEmpty() && it != ROOT_ID }
                folders.add(Folder(id = id, name = name, parentId = parentId))
                readNotes(dir, id, notes)
            }
    }

    /** Reads every note file of one folder directory. */
    private fun readNotes(dir: File, folderId: String?, notes: MutableList<Note>) {
        if (!dir.isDirectory) return
        dir.listFiles()
            ?.filter { it.isFile && NoteFile.isNoteFile(it.name) }
            ?.forEach { file ->
                try {
                    val note = NoteFile.decode(
                        file.readText(),
                        fallbackId = file.name.removeSuffix(NoteFile.EXTENSION),
                    )
                    if (note != null) {
                        notes.add(note.copy(folderId = folderId))
                    }
                } catch (e: Exception) {
                    // A single unreadable file must not take the app down.
                }
            }
    }

    /** Writes the whole tree back to disk. */
    fun save(folders: List<Folder>, notes: List<Note>) {
        foldersDir.mkdirs()
        rootDir.mkdirs()
        writeFolderFile(rootDir, notes.filter { it.folderId == null })

        folders.forEach { folder ->
            val dir = dirFor(folder.id)
            dir.mkdirs()
            writeTextAtomic(File(dir, NAME_FILE), folder.name)
            writeTextAtomic(File(dir, PARENT_FILE), folder.parentId ?: "")
            writeFolderFile(dir, notes.filter { it.folderId == folder.id })
        }

        // Remove the directories of folders that no longer exist, so the disk
        // does not fill up with the ghosts of deleted folders.
        val live = folders.map { it.id }.toSet()
        foldersDir.listFiles()
            ?.filter { it.isDirectory && it.name != ROOT_ID }
            ?.forEach { child ->
                if (child.name !in live) child.deleteRecursively()
            }
    }

    /**
     * Writes the notes of one folder, one file per note. A note that is gone is
     * removed from the disk.
     */
    private fun writeFolderFile(dir: File, notes: List<Note>) {
        val wanted = notes.map { NoteFile.fileNameFor(it.id) }.toSet()
        dir.listFiles()
            ?.filter { it.isFile && NoteFile.isNoteFile(it.name) }
            ?.forEach { file ->
                if (file.name !in wanted) file.delete()
            }
        notes.forEach { note ->
            writeTextAtomic(File(dir, NoteFile.fileNameFor(note.id)), NoteFile.encode(note))
        }
    }

    /** Writes text to a file, replacing it atomically. */
    fun writeTextAtomic(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            // renameTo can fail on some filesystems; fall back to a plain copy.
            file.delete()
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        /** The id used for the top level of the app. */
        const val ROOT_ID = "__root__"

        const val NAME_FILE = "name"
        const val PARENT_FILE = "parent"
    }
}
