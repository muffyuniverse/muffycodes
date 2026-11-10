package com.example.notes

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * The log's home on a phone: one file in Downloads, appended to as things happen.
 *
 * ## Why Downloads
 *
 * The app keeps everything else in a folder no other app can see, which is right for
 * notes and useless for a log whose entire purpose is to be read from outside. Downloads
 * is the one place a person can already reach with the file manager they have, a cable,
 * or a chat app.
 *
 * ## Why no permission
 *
 * On Android 10 and later an app may add files to Downloads through `MediaStore` without
 * asking for anything: it is writing *its own* file into a shared collection. This app
 * asks for exactly one permission — the network — and a debugging aid is not worth a
 * second. Below Android 10 it falls back to the app's own external folder and says where,
 * because an answer that names nothing is how "restore deleted did nothing" felt.
 *
 * ## Why append
 *
 * Because the file is evidence. Rewriting it on a timer meant an event seen a moment late
 * had already been overwritten by the next write — which is the failure this whole
 * rewrite exists to end. A line, once written, stays written; the only thing that ever
 * removes lines is the trim inside [Trace.FileSink], and that leaves a marker where it
 * happened.
 */
class DownloadsSink(context: Context) : Trace.Sink {

    private val app: Context = context.applicationContext

    /** The one file. Found once, then kept. */
    private var uri: Uri? = null

    /** Where it landed, in words a person can find. */
    private var path: String? = null

    /**
     * **Why the last write did not land where it promised**, or null when it did.
     *
     * This exists because the sink used to swallow every exception in silence: twelve `catch
     * (t: Throwable)` blocks, seven of which said nothing at all. When Downloads refused, the text
     * went to the private folder and the reason was discarded — so the one question worth asking
     * ("why is my log not in Downloads?") had no answer anywhere, on the phone or off it.
     *
     * **A failure must say what happened.** The report screen reads this, so a person who is already
     * looking at the log to report a bug gets the reason for the log being short in the same place.
     */
    var refused: String? = null
        private set

    override fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        val text = lines.joinToString("") { it + "\n" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val found = if (uri != null) uri else find()?.first
            if (found != null) {
                if (write(found, text, first = false)) {
                    uri = found
                    path = "Download/$FOLDER/${name(found)}"
                    return
                }
                // The entry went away (the person deleted the folder), or the row is one
                // whose file was never made. Either way, stop trusting it and make a real
                // one rather than writing nowhere for the rest of the session.
                uri = null
            }
            val made = createThenWrite(text)
            if (made != null) {
                uri = made
                path = "Download/$FOLDER/${name(made)}"
                return
            }
        }
        // **Named rather than silent.** Every route here means Downloads did not take the line, and
        // the person is about to be told the log is shorter than their session.
        if (refused == null) {
            refused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Downloads did not accept the file."
            } else {
                "This Android is too old for Downloads; writing beside the app."
            }
        }
        intoPrivate(text)
    }

    override fun read(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val target = uri ?: find()?.first
            if (target != null) {
                uri = target
                return read(target)
            }
        }
        return privateFile()?.takeIf { it.isFile }?.let {
            try {
                it.readText()
            } catch (t: Throwable) {
                null
            }
        }
    }

    /**
     * **Where the log actually is, and it says when it is not where it promised.**
     *
     * The fallback used to be silent: Downloads refused, the text went to the app's own folder, and
     * `where()` reported that path as though it were the plan. A person looking in Downloads found a
     * four-day-old file and no reason to think anything had gone wrong — which is exactly how a
     * session's log went missing without a word.
     *
     * **Null means nothing has been written yet**, which is different from "written somewhere you
     * cannot see". Settings draws the difference; a path here is always a real one.
     */
    override fun where(): String? = path ?: privateFile()?.absolutePath

    override fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val target = uri ?: find()?.first
            if (target != null) {
                try {
                    // A null stream is a clear that did not happen; the private copy
                    // below is emptied either way, so the ring still starts clean.
                    app.contentResolver.openOutputStream(target, "wt")?.close()
                } catch (t: Throwable) {
                    // Falls through to the private copy, which may also be the home.
                }
            }
        }
        try {
            privateFile()?.writeText("")
        } catch (t: Throwable) {
            // Nothing to do.
        }
    }

    /**
     * Adds text to an existing file, and never loses what was there.
     *
     * **"wa" needs a file that already exists.** On a `MediaStore` row that has just been
     * inserted and never written, append can throw — and when it did, the folder appeared
     * in Downloads and the log did not: a session's evidence went to the private fallback
     * while the app reported the other path. So an append that fails is not the end of it:
     * the text already in the file is read, the new text is put after it, and the whole
     * thing is written. Slower, and only on the path that was already broken.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun write(target: Uri, text: String, first: Boolean): Boolean {
        if (!first && put(target, text, "wa")) return true
        // Either the first write, or the append did not happen: put the whole file back,
        // old text and new. Only reached on a path that has already failed once.
        return put(target, (read(target) ?: "") + text, "w")
    }

    /**
     * Writes [text] to [target] in [mode], and **says no when there is no stream**.
     *
     * This is the bug that made a folder appear in Downloads and stay empty. The old
     * version read `openOutputStream(target, "wa")?.use { it.write(...) }` and then
     * `return true`: when the resolver answered `null` — which it does for a `MediaStore`
     * row that has no file behind it yet — the `?.` skipped the write, nothing was thrown,
     * and the function reported success. The caller then believed the line was safe and
     * did not fall back to anywhere. A null stream is a failure, and it has to be said
     * out loud to be handled.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun put(target: Uri, text: String, mode: String): Boolean = try {
        val stream = app.contentResolver.openOutputStream(target, mode)
        if (stream == null) {
            false
        } else {
            stream.use { it.write(text.toByteArray()) }
            true
        }
    } catch (t: Throwable) {
        false
    }

    /** Whatever is already in the file, or null when it cannot be read. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun read(target: Uri): String? = try {
        app.contentResolver.openInputStream(target)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        null
    }

    /**
     * Makes the file and puts the first lines in it.
     *
     * `IS_PENDING` stays set until those lines are written, and only then is cleared: the
     * point of pending is that no other app can read a half-made file, and clearing it
     * before the file existed was the other half of the same bug — a visible row for a
     * file nobody had created yet.
     *
     * A failure deletes the row rather than leaving it: a phantom entry is worse than no
     * entry, because [find] would keep choosing it.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createThenWrite(text: String): Uri? {
        val made = try {
            Downloads.pending(app, NAME, "text/plain", FOLDER)
        } catch (t: Throwable) {
            // **The message, not just the null.** A SecurityException here is the most likely reason
            // a phone's Downloads refuses, and "null" would have hidden it for another week.
            refused = "Downloads refused the file: " + (t.message ?: t.javaClass.simpleName)
            null
        } ?: run {
            refused = refused ?: "Downloads returned no entry for the file."
            return null
        }

        if (!write(made, text, first = true)) {
            refused = "Downloads made the entry but would not open it for writing."
            try {
                app.contentResolver.delete(made, null, null)
            } catch (t: Throwable) {
                // Nothing to clean up.
            }
            return null
        }
        Downloads.reveal(app, made)
        return made
    }

    /**
     * The log this phone already has, with the name it is really stored under.
     *
     * **The name is not what was asked for, and assuming it was cost a session's log.**
     * A `MediaStore` row inserted as `trace.log` with a text MIME type comes back as
     * `trace.log.txt`: the store appends an extension for what the type says the file is.
     * Searching for the exact name therefore never found the file this app had made, so
     * every launch inserted a fresh row — `trace.log.txt`, `trace.log (1).txt`,
     * `trace.log (2).txt` — and the one-log promise, and the monitoring that depended on
     * it, quietly became one file per launch.
     *
     * So the search is by prefix, and the newest row wins. That also adopts a file made by
     * an older build, and a file a person renamed within the folder keeps being found.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun find(): Pair<Uri, String>? = try {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
        )
        app.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("$PREFIX%"),
            // Newest first: `_ID DESC` is insertion order, which is what "the one that has
            // been appended to lately" means here. Sorting in the query rather than in
            // memory is what makes the newest row the one the cursor sees first.
            "${MediaStore.Downloads._ID} DESC",
        )?.use { cursor ->
            var found: Pair<Uri, String>? = null
            while (cursor.moveToNext() && found == null) {
                val folder = cursor.getString(2).orEmpty().trimEnd('/')
                if (folder.endsWith(FOLDER)) {
                    found = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0),
                    ) to cursor.getString(1).orEmpty()
                }
            }
            found
        }
    } catch (t: Throwable) {
        null
    }

    /** The display name behind [target], asked of the store rather than assumed. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun name(target: Uri): String = try {
        app.contentResolver.query(target, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else NAME }
            ?.takeIf { it.isNotBlank() } ?: NAME
    } catch (t: Throwable) {
        NAME
    }

/** Before Android 10, or when Downloads cannot be written at all. */
    /**
     * **The fallback, and it no longer pretends.**
     *
     * This is reached when Downloads cannot be written — no permission, a full volume, a store that
     * refuses — and the log still has to go somewhere, because a trace that vanishes is worse than
     * one in an awkward place. What it must not do is *report* the Downloads path afterwards, which
     * is what made a missing log look like a person's mistake.
     *
     * `path` stays null on this route, so [where] answers the private path and Settings says
     * *"Writing to … instead — Downloads refused, so other apps cannot see it."* A person then knows
     * to look there, or to fix the reason, rather than hunting a folder that was never written.
     */
    private fun intoPrivate(text: String) {
        val file = privateFile() ?: return
        try {
            file.parentFile?.mkdirs()
            file.appendText(text)
        } catch (t: Throwable) {
            // **Nowhere at all**, which is the worst case and used to be the quietest: the ring
            // still shows the session, so the app looked like it was working while nothing at all
            // reached the disk.
            refused = "Nothing could be written: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun privateFile(): File? = try {
        app.getExternalFilesDir("trace")?.let { File(it, NAME) }
    } catch (t: Throwable) {
        null
    }

    companion object {
        /** The folder inside Downloads. */
        const val FOLDER = "MuffyCodes log"

        /**
         * **The file is named after the build**, so a log is never ambiguous and never duplicates.
         *
         * The operator: *"the log name is dangerous, it duplicates itself and adds suffixes (n).
         * probably some more conventional naming where all trace logs are named after the app build
         * will be better."* Exactly right, and the duplication was not cosmetic — it was evidence
         * that the one-file promise had broken: `trace.log.txt`, `trace.log (1).txt`,
         * `trace.log (2).txt`, `trace.log (3).txt` sat in Downloads, one per launch, and the newest
         * was four days old.
         *
         * **Why the suffix appeared at all.** `MediaStore` refuses to give two rows the same display
         * name in one folder; it appends ` (1)`, ` (2)` instead. The old code searched for `trace.log%`
         * and took the newest row, so it should have found the file it made — but any failure to find
         * it inserted a fresh row, and each insert was another suffix. A name that changes with the
         * build makes the opposite true: this build writes `trace-<version>-<build>.log` every time,
         * so a person can see at a glance which build a log came from, and two builds do not collide.
         *
         * **The version and code are in the name** rather than only the version: a dev build and the
         * next dev build share a `versionName` for a while, and the number is what tells them apart.
         */
        val NAME: String =
            "trace-${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}.log"

        /**
         * **What [find] looks for**, which is deliberately not [NAME].
         *
         * A new build makes a new file, so the search must not be narrowed to this build's name or a
         * person upgrading would start a second log beside the first. `trace-` matches every build's
         * file, and the newest row wins — so an upgrade continues the log rather than forking it.
         */
        const val PREFIX = "trace-"
    }
}
