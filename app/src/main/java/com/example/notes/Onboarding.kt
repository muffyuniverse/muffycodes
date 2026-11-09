package com.example.notes

/**
 * The pages a person reads before they have an account.
 *
 * **Written as files, not as Compose**, for the reason the README is: a page that lives in a folder is
 * a page that can be read again, cloned and edited, and the one document that explains the app is not
 * hidden inside it. It also means a typo in a sentence is a one-line fix in a repository rather than a
 * rebuild.
 *
 * **One capability per page**, which is the operator's own correction: *"onboarding screens just show
 * one capability set at a time instead of a wall of text"*. A wall of text is a thing people scroll
 * past; a page with one idea and one picture is a thing they read.
 *
 * **The order is the order a person needs them.** What this is, then the two things that make it work
 * (a place for the files, a mind to write them), then the agent, then the fact that the work is
 * theirs — and only then the page that asks them to connect, which is the last one because it is the
 * only one that can fail.
 *
 * **Every page is Markdown the app can already render**, including the images: a `![](shot.png)` is
 * resolved against the page's own folder, so a screenshot written beside it appears. The pages ship
 * without pictures today and gain them by dropping a file in.
 */
object Onboarding {

    /**
     * The folder, inside the system project.
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
    const val REVISION = 1

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
        Page("02-your-files-live-in-github.md", FILES),
        Page("03-a-mind-that-writes.md", MIND),
        Page("04-the-agent.md", AGENT),
        Page("05-nothing-is-hidden.md", HIDDEN),
        Page("06-connect.md", CONNECT),
    )

    private val WHAT = """
# A notebook that is also a workshop

Muffy Codes is a place to write, and a place for an agent to write with you.

Everything you make here is a **file in a git repository**. Not a note in a database, not a row in
somebody's service — a file, with a history, that you can clone, read, and take with you.

That is the whole idea. Everything else is what it makes possible.

---
""".trimIndent()

    private val FILES = """
# Your files live in GitHub

Sign in with GitHub and this app becomes a folder in your account.

Every project is a repository. Every save is a commit, so the history you see in the editor is the
same history GitHub shows. Nothing is uploaded when you press something — the work *is* the upload,
and a sync is only the last step.

**Your writing outlives the app.** Uninstall it, and your files are still there, in the account you
already trust.

---
""".trimIndent()

    private val MIND = """
# A mind that writes

The agent is not a chatbot in a panel beside your notes. It has the same tools you do: it can read
your files, write them, search them, and run things.

Ask it to draft a page and it makes a file. Ask it to fix one and it edits that file, in a commit you
can read afterwards and undo.

**It works while you are not looking.** Long jobs keep running with the screen off, and the record of
what it did is a file you can read.

---
""".trimIndent()

    private val AGENT = """
# What it can do

- **Write and edit files** across every project, with every change a commit.
- **Read the web**, and show you the page it read.
- **Look at pictures** — screenshots, photographs, and drawings you make with a pen.
- **Search your own writing**, by meaning rather than by exact words.
- **Run workflows** on GitHub, and read back what they said.

It can also be told what to do once and remember it: a project's prompt is a file, and so is every
skill you teach it.

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
""".trimIndent()

    private val CONNECT = """
# Two connections, and then it works

The app needs two things: **a place to keep your files**, and **a mind to write them**.

Both are your own accounts. Neither is ours.

- [Connect GitHub](app:connect-github)
- [Connect OpenRouter](app:connect-openrouter)

A personal access key is accepted inside each of those windows, for a provider that will not answer.

When both are connected this page closes on its own.

---

**You can read all of this again at any time.** These pages are a folder in your system project, and
the browser will open them whenever you want.
""".trimIndent()
}
