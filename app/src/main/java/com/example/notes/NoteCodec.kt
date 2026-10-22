package com.example.notes

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts the note/folder document to and from its on-disk JSON representation.
 *
 * The document is versioned. Version 1 was a bare array of notes with no folders;
 * version 2 is an object holding both folders and notes. [decode] migrates a
 * version 1 file on the fly so existing notes survive the upgrade.
 *
 * Kept free of Android dependencies so it can be unit-tested on the JVM;
 * [NoteStore] owns the file handling and delegates the encoding here.
 */
object NoteCodec {

    const val CURRENT_VERSION = 2

    fun encode(folders: List<Folder>, notes: List<Note>): String {
        val folderArray = JSONArray()
        folders.forEach { folder ->
            folderArray.put(
                JSONObject().apply {
                    put("id", folder.id)
                    put("name", folder.name)
                    if (folder.parentId != null) put("parentId", folder.parentId)
                }
            )
        }

        val noteArray = JSONArray()
        notes.forEach { note ->
            noteArray.put(
                JSONObject().apply {
                    put("id", note.id)
                    put("title", note.title)
                    put("body", note.body)
                    put("pinned", note.pinned)
                    put("updatedAt", note.updatedAt)
                    if (note.folderId != null) put("folderId", note.folderId)
                }
            )
        }

        return JSONObject().apply {
            put("version", CURRENT_VERSION)
            put("folders", folderArray)
            put("notes", noteArray)
        }.toString()
    }

    /**
     * Decodes the document. Malformed entries are skipped rather than failing the
     * whole file, so one bad record cannot lose the rest of the data.
     */
    fun decode(text: String): Document {
        if (text.isBlank()) return Document(emptyList(), emptyList())

        val trimmed = text.trimStart()
        // A version 1 file was a bare JSON array of notes.
        if (trimmed.startsWith("[")) {
            return Document(emptyList(), decodeLegacyNotes(text))
        }

        val root = JSONObject(text)
        val folders = decodeFolders(root.optJSONArray("folders"))
        val notes = decodeNotes(root.optJSONArray("notes"))
        return Document(folders, notes)
    }

    private fun decodeFolders(array: JSONArray?): List<Folder> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            Folder(
                id = id,
                name = obj.optString("name"),
                parentId = obj.optString("parentId").ifEmpty { null },
            )
        }
    }

    private fun decodeNotes(array: JSONArray?): List<Note> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            Note(
                id = id,
                title = obj.optString("title"),
                body = obj.optString("body"),
                pinned = obj.optBoolean("pinned", false),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                folderId = obj.optString("folderId").ifEmpty { null },
            )
        }
    }

    /** Version 1 files were a bare array of notes with no folder field. */
    private fun decodeLegacyNotes(text: String): List<Note> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            Note(
                id = id,
                title = obj.optString("title"),
                body = obj.optString("body"),
                pinned = obj.optBoolean("pinned", false),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                folderId = null,
            )
        }
    }

    /** The decoded contents of the document. */
    data class Document(
        val folders: List<Folder>,
        val notes: List<Note>,
    )
}
