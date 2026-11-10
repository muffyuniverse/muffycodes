package com.example.notes

/**
 * **What the git connection window holds, and the sentences it refuses with.**
 *
 * A window's fields are text, and the rules about which text may be stored are not: they are here, as
 * one function per question, so a test can ask them without drawing anything. The same reason
 * [GitCredentials] and [TokenScopes] are separate files.
 *
 * ### The rules that make this window small
 *
 * 1. **A blank field writes nothing, and an empty field is the inheritance.** The operator's rule
 *    (2026-11-09): *"empty value means that it is inherited from the parent, while if lower settings
 *    scope defines it, then it overwrites — this way user will be able to change only what he needs and
 *    keep the rest of the fields inherited."* So there is no "inherit" switch to keep in step with the
 *    text, and clearing a field returns the scope to what it had.
 * 2. **A field left at the value it inherits is not written.** The window opens on the values in force,
 *    so "unchanged" and "typed the same" look identical; [changesAgainst] is what keeps them from being
 *    stored anyway, because a copy of the parent's value stops following the parent.
 *
 * ### What the git window does *not* store
 *
 * The remote's address. It is git's own fact (`remote.origin.url`, and *Set the address* is the verb
 * that changes it), and a second copy is a thing that drifts. What the vault keeps is the host the
 * token is for, the token, and the name a commit is made as.
 *
 * ### What left this file (2026-11-10)
 *
 * The model, the effort, the three compaction numbers and the shape editor. `ConnKind.LLM` is gone:
 * every one of those fields belongs to a **request form** (`Schema`) — the address, the path, the body,
 * the numbers — so a window that also stored them per scope was a second answer to "what is this chat
 * asked with". The numbers survive where they always were as facts: in the form's `target`/`forget`/
 * `trim`, and in the general settings as their root.
 */
data class ConnectionDraft(
    val kind: ConnKind,
    /** The host the token is for (`gitlab.com`). */
    val address: String = "",
    /** The token. **Blank is allowed for a model server and refused for git.** */
    val secret: String = "",
    /** The name a commit is made as — git's only. */
    val user: String = "",
    /**
     * **May the agent spend this on its own requests to this host?**
     *
     * **Off unless ticked**, and it is the only way a credential the app itself uses becomes nameable by
     * `auth: git` ([Connection.nameable]). A window that defaulted it on would be handing an agent a
     * token because a row exists, which is the opposite of a grant.
     */
    val nameable: Boolean = false,
) {

    /**
     * Why this cannot be saved, or null when it can — **naming the field and what to do**.
     *
     * Every branch here is a sentence a person reads on the window, which is why the checks are in one
     * function rather than spread through the composable: the order they are asked in is the order
     * they are fixed in.
     */
    fun problemSaid(): String? {
        val address = this.address.trim()
        if (address.isEmpty()) {
            return "Say which host this token is for — for example gitlab.com. A project on another " +
                "host needs its own credential, and this is where it is kept."
        }
        // **A host, not an address.** The remote's address is git's own fact; pasting one here would
        // store a second copy of it, and the copy would be the one that went stale.
        if ('/' in address.substringAfter("://", "")) {
            return "That is an address, not a host: \"$address\". This window keeps the host a token " +
                "is for; the address belongs to the project's own remote, which is where it is set."
        }
        if (hostOfAddress(address).isEmpty()) {
            return "That does not name a host. Write one, for example gitlab.com."
        }
        if (secret.isBlank()) {
            return "A git credential is a token. Paste it — a host that wants none cannot accept a push."
        }
        return null
    }

    /**
     * The pair to store, or null when this window is not setting one.
     *
     * **Null when the address is blank**, which is the whole of "the pair is atomic": a scope with a
     * token of its own and no address keeps the address it inherits, and its token with it.
     */
    fun connection(): Connection? {
        if (address.isBlank()) return null
        return Connection(
            address = hostOfAddress(address),
            secret = secret,
            user = user.trim(),
            nameable = nameable,
        )
    }

    /**
     * **This draft with everything that did not change removed.**
     *
     * The window opens on the values in force, so a person who changes the token and presses Save has a
     * draft holding the whole inherited credential — address and user too. Writing that down would copy
     * the parent's values into this scope, where they would stop following the parent: the opposite of
     * "change only what you need".
     *
     * So the save path stores the difference. **A field typed with exactly the value it inherits is
     * treated as unchanged**, which is the only rule available once the text is prefilled — and it is
     * the safe one, because the alternative freezes a value nobody meant to pin.
     */
    fun changesAgainst(inForce: ConnectionDraft): ConnectionDraft = ConnectionDraft(
        kind = kind,
        address = changed(address, inForce.address),
        secret = changed(secret, inForce.secret),
        user = changed(user, inForce.user),
        nameable = nameable != inForce.nameable && nameable,
    )

    private fun changed(mine: String, theirs: String): String =
        if (mine.trim() == theirs.trim()) "" else mine

    companion object {
        /**
         * A window opened on a scope, showing **the values in force** rather than the scope's own.
         *
         * A person changing only the token should see the address and the user that are in force, not
         * two empty boxes they have to reconstruct.
         *
         * **The secret is never filled in.** It is not read back into a field by policy — a window that
         * showed it would be a screenshot away from a leak — so an empty key box means "keep the one
         * that is here", and the sentence beside it says whether one is.
         */
        fun opening(
            kind: ConnKind,
            at: String,
            user: String = "",
            nameable: Boolean = false,
        ): ConnectionDraft = ConnectionDraft(
            kind = kind,
            address = at,
            secret = "",
            user = user,
            nameable = nameable,
        )
    }
}
