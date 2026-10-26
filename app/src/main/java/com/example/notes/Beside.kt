package com.example.notes

import java.io.File

/**
 * Writing a file so a reader sees either its old text whole or its new text whole.
 *
 * **Beside the file, then renamed over it.** A write in place that stopped halfway — a killed
 * process, a phone that ran out of room, a battery manager ending the app — leaves a file whose last
 * row is half a row. A rename replaces the old file with the new one in one step, so a concurrent
 * reader gets one of two complete versions and never a mixture.
 *
 * The fallback is for the one case a rename cannot do: it returned false. The text is then copied
 * over the file from the finished temporary, which is still whole — writing the string again would
 * be a second chance to fail at the same thing.
 *
 * **One rule, one place.** This was five copies — the agent's log, the app's trace, the session file,
 * a background result and the account's own bookkeeping — and they had drifted: two fallbacks wrote
 * the text again, two copied, one deleted the file first. All five were the same intention, so a bug
 * found in one of them was a bug left in the other four.
 */
internal object Beside {

    /**
     * Writes [text] to [file], making its folder if it is not there.
     *
     * Throws what the file system throws: a caller that can carry on without the write catches it,
     * and a caller that cannot lets it out. The temporary is named `.new` and lives beside the file
     * so the rename is within one directory — and therefore one file system, where a rename is a
     * rename rather than a copy.
     */
    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val fresh = File(file.parentFile, file.name + ".new")
        fresh.writeText(text)
        if (!fresh.renameTo(file)) {
            fresh.copyTo(file, overwrite = true)
            fresh.delete()
        }
    }
}
