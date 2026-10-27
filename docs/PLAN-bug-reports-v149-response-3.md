# Counter-proposal 3 — gitignore does not mean what the button says, and that is the defect

Five answers. Four settle cleanly. **One of them uncovered a real bug in the app as it stands**,
and it is the answer to the question you asked — so it goes first.

---

## 1 · Your question: what does gitignore actually do?

> "maybe I dont understand what gitignore does - it does commit locally but doesnt push to remote,
> right? or if it doesnt commit at all then I was misinformed and we instead need syncignored file
> and mechanism that prevents certain files from being pushed to remote only."

**You were not misinformed, but the half you were unsure about is the half that matters.** I tested
it rather than answering from memory:

```text
after commit, tracked files:        .gitignore  kept.txt     (ignored.txt absent)
```text

- **gitignore is not a commit filter. It is an "untracked" filter.** It only stops git from
  *starting* to track a path.
- **Git has no "commit locally, don't push" mechanism.** A commit is a whole tree; push sends
  commits. There is no per-file push filter, and there is no way to build one — a commit is
  identified by its content hash, so a commit that excludes a file is a *different commit* from
  one that includes it. "Syncignored" would have to mean "the commit on the phone is not the
  commit GitHub has", which is not a thing git can represent.

So gitignore means exactly: **never in the repository at all** — not in a local commit, not in a
push. Which is what the button says ("so it stops being synced"), so the words are right.

### The defect: a file already tracked ignores the ignore line

This is the part that is broken **today**, in the app, not in my plan. Measured:

```text
notes.txt is tracked. Add "notes.txt" to .gitignore. Commit.
  is it still tracked?      yes
  edit it and commit:
  content in HEAD:          v2      <- the change was committed anyway
```text

`gitignore` never untracks. And `ignoreInGit` (`AppViewModel.kt:1139`) writes the line and
commits — it never runs `git rm --cached`. So:

- **Every file a person has saved is already tracked.** `saveEditor` commits on every save
  (`AppViewModel.kt:1637,1648`).
- Therefore "Add to .gitignore" on any file the person has edited **writes a line, commits it,
  says "Ignoring x" — and the file keeps being committed and pushed.** The message is false.

The fix is one step, and it is the step the button has always needed:

```text
git rm --cached notes.txt   (the file stays on disk)
```text

Verified:

```text
tracked files now:   .gitignore
content on disk:     v2         <- local, editable, never pushed again
```text

**This is a real defect and I will fix it as its own commit**, ahead of everything else — it is
the difference between the button working and the button lying, and it is unrelated to the
redesign.
@ ok, so file/folder is either tracked both locally and remotely or ignored by all. ok.

---

## 2 · No hidden files — accepted, with one consequence you should see

> "make it so .files are visible in the app - there should be no hidden files - even system ones
> should be readable (but system locked: no delete, move, clone, rename, edit etc - only read).
> And if system files are inside github projects then they should be gitignored."

So `.muffy/` becomes visible, and its files are **readable and system-locked**. That is a clean
rule and it removes a whole class of "where did my setting go".

Three things follow that I want to name:

1. **`.gitkeep` stays hidden** (your `@ ok, i`). It is the one exception, because showing it puts a
   stray file in every empty folder. So the rule is "no hidden files, except the marker that makes
   git notice an empty folder" — one exception, written down.
2. **A system-locked file is a new lock kind.** Today `isLocked` is the person's lock (a toggle
   they own). System-locked is the app's: the person cannot unlock it, and the refusal says why.
   That is a second flag, not a second code path — `coreProblem` already answers "That belongs to
   the app" (`Workspace.kt:596`), and this gives it a visible row instead of an invisible file.
3. **`agent/` is already gitignored** — it is in `APP_IGNORES` (`Workspace.kt:803`), written into
   `.git/info/exclude`, not the person's `.gitignore`. That distinction is worth keeping: the
   app's own lines should not appear as a change in the person's repository.
   @ right

---

## 3 · Sync-ignored files get a button, not an icon

> "locked files have lock button, syncignored have syncignore button."

Then the row has two toggles with two meanings, and they are not the same act:

- **lock** — "do not change this" (the person's, reversible)
- **syncignore** — "do not send this" (the person's, reversible, and it must untrack)

Both are per-row buttons, both are toggles, and both must be **visible on the row** rather than in
the 3-dot menu — which is report #21, and this is where it lands.

One thing the button needs that the menu item does not: **a way back.** Today "Add to .gitignore"
is one-way — the file is written and committed, and nothing in the app removes the line. A toggle
that cannot be un-toggled is a trap, so the same button removes the line (and, if the person asks,
brings the file back into the repository).
@ right. but I am still confused, there is only gitignore, there is no push/syncignore in git, right? so untracked means untracked both locally and remotely?

---

## 4 · Diff right-hand field: unset

> `@ unset`

Accepted. A file with no synced version opens with the right field empty, and **Done stays greyed
until both are set**. That is honest and it needs no rule about "the first version".

I will make the emptiness say why — "this file has never been synced" beside the field — because a
greyed button with no sentence is the thing this app keeps fixing.
@ how do you distinguish sync hashes? by commit message?

---

## 5 · Closed reports: replace `open` with `closed` by hand

> "you just replace open with closed by hand when you are done with the report so that it doesnt
> dangle."

Understood, and it changes the mechanism completely. **No new line, no tap in the app.** I edit
the `open` field of the report line in the trace the person sent me, so a report that is answered
stops reading as open.

Two things about doing it by hand that I want to be explicit about, because they are edits to a
record:

1. **I edit the copy in the transcript I am given, not a file on the phone.** The app never
   rewrites its own log — that property stays. When you send me a trace, the report lines in it are
   what I correct.
2. **The field I change is the state column** (`open` → `closed`), and nothing else — not the
   words, not the time, not the id. So the line still reads as one line in the same format.

And I keep doing it in `docs/PLAN-bug-reports-v149.md`, which is where the 38 are already marked.
@ why do you have transcript if thera is an original log?

---

## What this leaves, and the order

**First, before any redesign — the ignore defect.** It is a lie in a button that exists today:

1. `ignoreInGit` untracks the file (`git rm --cached`), so "Ignoring x" becomes true.
2. Its test: a tracked file, ignored, edited, committed — the commit does not hold the edit.

**Then the redesign, in the order we agreed:**

1. **The system project** — `system`, lowercase, local, created with the account, delete and rename
   guarded, prompt read from `<account>/system/system_prompt.txt`.
2. **No hidden files** — `.muffy/` visible and system-locked, `.gitkeep` the one exception.
3. **Per-row lock and syncignore buttons** — including the way back, which does not exist today.
4. **Remove `ask`/`conclude`** and the state, notification and indicator with them.
5. **Chat** — bubbles, footer id, token column out, session-log time column in.
6. **Diff mode** — two hash fields, right unset when there is no synced version, Done greyed.

## Two things I need

- **The syncignore way back:** when the person un-ignores a file, should it be re-added to the
  repository (so its current content is pushed), or just stop being ignored (so it is pushed on
  the next change only)? I propose **re-add on un-ignore**, because a person who turns the switch
  off expects the file to be in the repository again.
- **The system project's name guard:** confirm `system` lowercase, and that the guard is
  case-insensitive — otherwise `System` is a second project that can be deleted.
