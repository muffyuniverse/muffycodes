package com.example.notes

/**
 * **Which of the app's messages are worth showing, and which only restate what just happened.**
 *
 * The operator's report, and it is a rule rather than a list of sentences to delete: "can you remove
 * any in-screen popup notifications that dont bring value and just restate what happened before (they
 * are the majority, I can't even remember the last time I saw a useful notification - notifications
 * should explain why something that user expected to happen didn't happen)".
 *
 * **The person already knows what they did.** They pressed Save and watched the editor close; a strip
 * sliding up to say "Saved" tells them nothing they did not see, and a strip that appears after every
 * act is a strip that gets dismissed without being read — which is how the one message that mattered
 * gets dismissed too. So a message earns the screen only when the outcome is **not** what the person
 * asked for: a refusal, a partial success, or a reason a thing could not be done.
 *
 * **The sentence is still written to the trace either way.** This decides what covers the screen, not
 * what is recorded; `Trace.said` is called by [AppViewModel.message]'s setter regardless, so the log
 * of what the app told a person stays complete. What changes is that the log is where a routine
 * confirmation belongs.
 *
 * **The test is the outcome, not the words.** A list of sentences would rot the moment one was
 * reworded, and the question "is this good news?" cannot be answered by grepping for "Saved". What can
 * be answered is whether the app's own sentence says something went wrong, which is the shape every
 * refusal here already has.
 */
object Said {

    /**
     * Words that mean the act did **not** happen as asked.
     *
     * Every message the app builds a refusal from already contains one of these — the refusals were
     * written to explain a reason, which is the same thing as explaining why not. A confirmation
     * ("Copied …", "Imported", "Saved") contains none of them.
     */
    private val REFUSALS = listOf(
        "locked", "cannot", "can not", "could not", "couldn't", "not ", "no ", "nothing",
        "already", "only", "refused", "failed", "failure", "error", "missing", "needs",
        "first", "unavailable", "no longer", "not part", "not in", "stays", "kept off",
    )

    /**
     * True when [message] explains something the person did not expect.
     *
     * Deliberately generous: a message wrongly shown is a small annoyance, and a refusal wrongly
     * hidden is the app failing to say why nothing happened. So the question is asked of the words
     * the app itself writes, and anything that looks like a reason is shown.
     */
    fun worthShowing(message: String?): Boolean {
        val said = message?.trim().orEmpty()
        if (said.isEmpty()) return false
        val lower = said.lowercase()
        return REFUSALS.any { lower.contains(it) }
    }
}
