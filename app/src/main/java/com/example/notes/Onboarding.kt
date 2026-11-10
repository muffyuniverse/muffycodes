package com.example.notes

/**
 * **The pages a person reads before they have an account — and the walkthrough of the app.**
 *
 * Written as files, not as Compose, for the reason the README is: a page that lives in a folder is a
 * page that can be read again, cloned and edited, and the one document that explains the app is not
 * hidden inside it. It also means a typo in a sentence is a one-line fix in a repository rather than a
 * rebuild.
 *
 * ### The order is the journey (§3.11.6)
 *
 * The operator, 2026-11-09, in their own words: *"user reads onboarding, then he adds an account, then
 * he adds projects to that account, then he enters a branch (main for example), then enters a chat,
 * then he encounters a popup that asks him to select a request form … We can explain how to set up the
 * model in the onboarding so that new operators knew what is where and how it works, one onboarding
 * file at a time."*
 *
 * So one page per step of that walk, in the order the steps happen, each naming the place the next act
 * lives. A person can stop reading at any point and do the step; the pages are re-readable for ever
 * because they are files in `shared/`.
 *
 * **Two things are deliberately not pages**: the form picker (it teaches where it interrupts, which is
 * the only moment a person needs it) and the minting window (its own fields are its explanation).
 *
 * ### Why there is no longer a "connect" page
 *
 * The last page used to offer two `app:` links — `connect-github` and `connect-openrouter` — which
 * made the app's own folders into a control panel and put the two connections in the order a
 * *developer* needs them rather than the order a person meets them. Accounts come first, keys are
 * minted inside Secrets, and both have their own doors. The page is gone with the links (F3's
 * decision, unchanged), and so is the machinery that drew a tick beside each.
 *
 * **Every page is Markdown the app can already render**, including the images: a `![](shot.png)` is
 * resolved against the page's own folder, so a screenshot written beside it appears.
 */
object Onboarding {

    /**
     * The folder, inside the app's own `shared/` — so the pages exist before any account does.
     *
     * **A folder rather than a list in code**, because a folder is what the editor, the browser and
     * the swipe gesture already understand. Nothing new had to be taught to any of them.
     */
    const val DIR = "onboarding"

    /**
     * **The revision of the pages below.**
     *
     * Bumped when a page's text changes, so an install that already has the folder gets the new
     * words. The README is written once and never again, because it is a document a person edits;
     * onboarding is the opposite — a page whose wording turns out to be confusing is a page that
     * *must* reach everyone, including people who installed before it was fixed.
     *
     * The number is compared with the one recorded in settings. A person's own notes belong in their
     * own files, not in these.
     */
    const val REVISION = 4

    /** A page: its file name, and its text. The name's leading number is the order. */
    data class Page(val name: String, val text: String)

    /**
     * The pages, in order.
     *
     * **The numbers are in the names** so the folder's own listing sorts them, which is the order the
     * swipe gesture moves in. Nothing sorts these separately: a page added to the folder by hand sits
     * where its number says.
     */
    fun pages(): List<Page> = listOf(
        Page("01-what-this-is.md", WHAT),
        Page("02-an-account.md", ACCOUNT),
        Page("03-a-project.md", PROJECT),
        Page("04-a-branch.md", BRANCH),
        Page("05-the-model.md", MODEL),
        Page("06-a-key.md", KEY),
        Page("07-nothing-is-hidden.md", HIDDEN),
    )

    private val WHAT = """
# A notebook that is also a workshop

Muffy Codes is a place to write, and a place for an agent to write with you.

Everything you make here is a **file in a git repository**. Not a note in a database, not a row in
somebody's service — a file, with a history, that you can clone, read, and take with you.

That is the whole idea. Everything else is what it makes possible.

---
""".trimIndent()

    private val ACCOUNT = """
# An account is where your files live

An account is a host and a key. GitHub is where most people start, because its sign-in window fills in
the name, the host and the key for you — but **any host that speaks git works**: a server you run
yourself, a work one, another service. That is why the `+` on the accounts screen has two doors:

- **Add GitHub account** — its window makes the key, because GitHub pairs a device code with one.
- **Add custom account** — you type the host and the key, for everything else.

Nothing about the app is GitHub-shaped. The account is the host and the key; the sign-in is one way of
arriving at it.

---
""".trimIndent()

    private val PROJECT = """
# A project is a repository

Every project is a repository, and every save is a commit — so the history you see in the editor is the
same history the host shows. Nothing is uploaded when you press something: the work *is* the upload,
and a sync is only the last step.

Make one with **+ Add**, or clone one that already exists on your host. A project holds its own files
and its own record of what the agent did, and neither leaves the other's folder.

**Your writing outlives the app.** Uninstall it, and your files are still there, in the account you
already trust.

---
""".trimIndent()

    private val BRANCH = """
# A branch is a second tree

`main` is the project's own folder — you never have to choose it, because a project that had to be told
its own name would be a project you could not open.

A branch is a second copy of the tree that shares the project's history, so two lines of work can go on
without one undoing the other. Its row is below the project's, and its own files, its own conversation
and its own record live in its own folder.

**Switching is opening.** Tapping a branch shows its files; tapping the project shows `main`.

---
""".trimIndent()

    private val MODEL = """
# The agent needs a model, and a request form says which

The agent does not know how to ask a server for an answer. **A request form is the file that tells it**:
which address, which model, how hard to think, and which secret to send.

Forms for chat live in `shared/requests/chat/`. The app ships one, and the format is explained field by
field in `shared/schemas/chat/openrouter.jsonc` — copy it, change what you want, and the copy is a
form. A form is a JSON file with comments, so it can explain itself.

**The choice is per chat.** Open a chat and press the form's name in the top bar; a chat that has not
chosen one opens the picker by itself, because a chat with nowhere to send is a field you can type into
and nothing that will happen.

---
""".trimIndent()

    private val KEY = """
# A key is a secret

A form names a key; it never holds one. The value lives in the vault, in **Settings → Secrets**, and it
is bound to the host it was made for — so naming it cannot send it anywhere else.

**Minting is the shortcut.** The Secrets block has a button that opens OpenRouter's own sign-in and
writes the key it makes into the vault, so you never copy one by hand. Adding one by hand is the same
act, for a server whose key you already have.

**A secret has a scope**, and the scope is the grant:

| scope | who may spend it |
|---|---|
| **undefined** | nobody yet — stored, not available to an agent |
| **general** | any account, any project, any branch |
| **account** | the agents of one account |
| **project** | the agents of one project |
| **branch** | one branch's agents |

A key that was just minted has no scope, and that is deliberate: a key that arrived usable-by-everything
would be a permission nobody gave.

---
""".trimIndent()

    private val HIDDEN = """
# Nothing is hidden

Every request the agent makes is recorded — the model, the tokens, and what came back. The table is in
this app, not in a dashboard somewhere.

**The key is yours.** You connect your own account, and the work is billed to it. This app never sees
your password and never holds a key it did not mint with your permission.

And the log of what happened is a file. If something goes wrong, you can read it, or hand it over.

---

**You can read all of this again at any time.** These pages are a folder in the app's own `shared/`,
and the browser will open them whenever you want — they are there before the first account is.
""".trimIndent()
}
