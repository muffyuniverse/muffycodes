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
     * What a file being written is called while it is being written.
     *
     * **Not `<name>.new`, and the difference is not cosmetic.** The temporary lives in the same
     * folder as the file — it has to, because a rename is only atomic within one file system — so it
     * lives *inside the project*, where `git add .` can see it. A commit that ran between the write
     * and the rename would record a file that the rename then deleted, and the person would find a
     * phantom "deleted file" in their history for ever. The prefix is unmistakable, it is excluded
     * from staging ([Workspace.isHidden] hides it too), and no person names a file this.
     */
    const val TEMP_PREFIX = ".muffy-write-"

    /**
     * Writes [text] to [file], making its folder if it is not there.
     *
     * Throws what the file system throws: a caller that can carry on without the write catches it,
     * and a caller that cannot lets it out. The temporary is named `.new` and lives beside the file
     * so the rename is within one directory — and therefore one file system, where a rename is a
     * rename rather than a copy. The name is [TEMP_PREFIX], which is what keeps a commit from
     * recording a file that is about to disappear.
     */
    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val fresh = File(file.parentFile, TEMP_PREFIX + file.name)
        fresh.writeText(text)
        if (!fresh.renameTo(file)) {
            fresh.copyTo(file, overwrite = true)
            fresh.delete()
        }
    }
}
