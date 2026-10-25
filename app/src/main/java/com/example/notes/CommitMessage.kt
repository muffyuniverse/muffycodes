package com.example.notes

/**
 * The message for a commit, written from what changed (§8 of the design).
 *
 * Names are what the eye scans for when looking for the version to restore, so
 * the message carries them rather than a count: `Milk.txt, Shop.png`, and
 * `+3 more` once there are too many to read at a glance.
 *
 * It lives in its own file rather than beside a history, because it is a **naming rule and not a
 * repository's business**: which words a version is called by does not change when the number of
 * histories does, and the class that used to hold it was the account's, which this build removed.
 */
object CommitMessage {

    /** How many names are spelled out before the rest become a count. */
    const val NAMES_SHOWN = 3

    /**
     * What a save's commit says: **one word, and nothing else.**
     *
     * A save cannot go unrecorded — the version it makes *is* the thing the arrows walk and
     * Restore brings back — so "disable the save commit" would mean "stop keeping versions of
     * what you write", which is the feature, not a message. What can go is the message: the
     * person never types it, every row of a file's history would carry the same sentence, and
     * [appVoice] hides it from the screen anyway. Naming the file (`Edited milk.txt`) was the
     * app narrating itself in a place nobody reads, at every save, forever.
     *
     * The note typed in `SyncDialog` is untouched: that one is the person's words, and it is the
     * only message a history ever shows.
     */
    const val SAVE = "Saved"

    /**
     * The message for a set of changed paths. [verb] lets the same shape serve
     * a restore (`Restored`) as well as a sync.
     */
    fun of(verb: String, paths: List<String>): String {
        val names = paths
            .map { it.trim('/').substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .distinct()
        if (names.isEmpty()) return verb
        val shown = names.take(NAMES_SHOWN).joinToString(", ")
        val rest = names.size - NAMES_SHOWN
        return if (rest > 0) "$verb $shown +$rest more" else "$verb $shown"
    }

    /** The message for an ordinary sync: the names that changed. */
    fun sync(paths: List<String>): String = of("Changed", paths)
}
