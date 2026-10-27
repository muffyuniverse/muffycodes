# Counter-proposal 2 — the system project, no hidden files, and closing reports in the log

Answers read from `docs/PLAN-bug-reports-v149-response.md`. Three of the four settle cleanly. One
of them (`@ ok, clean up`) is a bigger instruction than it looks, and I want to check I have read
it right before I do it.

---

## 1 · The system project — your shape, and it is cheap

> "no need for shared root. if currently everything is per account then it should be so, and we
> already have local project type, system can be a special local project that cant be removed and
> is created when you add an account. Will that change a lot?"

**No. It changes less than any other option, and it is the one I should have proposed.**

What it reuses:

- `createLocalProject` (`AppViewModel.kt:1755`) already makes a folder plus a repository with no
  remote. The system project is that call with a reserved name.
- The editor already opens any text file. `system_prompt.txt` becomes a file in a project folder,
  which is the same thing every other file is.
- `ProjectGit` already refuses to push when there is no remote (`tokenProvider = { null }`), so
  "never leaves the phone" is not new code either.

What it adds — **four things, and I want to name them because three of them are new rules:**

1. **A reserved name.** `system` (or `_system` — see the naming question below). Created by
   `signIn` (`AppViewModel.kt:2586`) the first time an account exists, so every account has one
   and a person never has to make it.
2. **A guard on delete.** `deleteProject` (`AppViewModel.kt:1943`) refuses this name. One
   `if`, plus a sentence saying why.
3. **A guard on rename.** Same shape — otherwise "delete" is reachable by renaming it first and
   making a new `system`.
4. **The prompt reads from it.** `AgentPrompt.system(accountDir)` (`AgentPrompt.kt:138`) reads
   `<account>/system_prompt.txt` today. It would read `<account>/system/system_prompt.txt`.

That is the whole change. **It is smaller than the linked-folder idea and it needs no symlink, no
app root, and no reserved browser path.**

### The one thing I need to ask, because it is a naming rule and not a preference

`Workspace.cleanName` refuses names starting with a dot (`Workspace.kt:575`) and the browser hides
dot-files (`Workspace.kt:152`). So a system project **cannot** be called `.system` — it would be
invisible, which is exactly what you said you do not want.

So it is a **visible** project with a plain name. Two candidates:

- **`system`** — reads well in the list, and a person typing it gets "that name is taken".
- **`System`** — same, and matches how a person capitalises a heading.

I propose **`system`, lowercase**, because every other project name in this app is lowercase and
a capitalised one would be the only exception. But the guard has to be case-insensitive either
way, or `System` and `system` are two projects and one of them can be deleted.
@ lowercase, and make it so .files are visible in the app - there should be no hidden files - even system ones should be readable (but system locked: no delete, move, clone, rename, edit etc - only read). And if system files are inside github projects then they should be gitignored. And maybe I dont understand what gitignore does - it does commit locally but doesnt push to remote, right? or if it doesnt commit at all then I was misinformed and we instead need syncignored file and mechanism that prevents certain files from being pushed to remote only.
---

## 2 · No hidden files — this is the larger half, and I want to be sure I read it right

> "I just dont want any hidden files - there should be locked and gitignored files, but not hidden
> ones."

Two of the three files that make this work today are hidden:

| File | Why it is hidden today | If it becomes visible |
|---|---|---|
| `.muffy/` | the app's state: locks, pins, history length | a folder a person can open. Its files are the app's own JSON — readable, not editable |
| `.gitkeep` | gives an empty folder a version | a zero-byte file in every empty folder, which is noise |
| `agent/` | **already visible** — it is a plain name | unchanged |

**`.gitkeep` is the problem.** It exists because git cannot record an empty folder, so without a
marker an empty folder would not survive a version (`Workspace.kt:811`). If it stops being
hidden, every folder a person makes starts with a stray `.gitkeep` in it.

Options, and this is the one place I want your call:

- **(i)** keep `.gitkeep` hidden and make only `.muffy/` visible — a smaller change, and the
  keeper stays an implementation detail
- **(ii)** show both, and accept the empty-file noise
- **(iii)** show both, and stop keeping empty folders in git — a folder with nothing in it simply
  is not a version, and the promise "any change can be taken back" loses that one case

I recommend **(i)**: your sentence is about *files you might want to see*, and a marker whose only
job is to make git notice a folder is not one of those. But it is your rule, so it is your call.
@ ok, i

### What "locked" and "gitignored" become

You said the distinction should be **locked** and **gitignored**, not hidden. Both exist:

- the lock is real already (`locked.json` in `.muffy/`, `Workspace.isLocked`)
- gitignore is real already (the `Add to .gitignore` item, `BrowserScreen.kt:819`)

What is missing is that **a gitignored file does not look different from any other file.** If the
rule is "there are locked files and gitignored files", then a person needs to see which is which —
which is report #16, still open. So this and #16 are the same change, and I will do them together.
@ locked files have lock button, syncignored have syncignore button.

---

## 3 · Q5 — your rule is better than mine, with one addition

> "can setting left one just reset the right one and diff renders only when both left and right
> are set? And diff mod button opens a selection window where you select left and right and then
> click done (which is greyed out untill both are set). And not every commit but every commit
> where the file was changed (like with history versions)."

Yes to all three. The third one is not a preference, it is **already the code**: `GitService.versionsOf`
(`GitService.kt:812`) walks `git.log().addPath(path)`, which is exactly "every commit where this
file changed". The diff picker reuses it, so the list a person sees is the same list the History
screen shows — one answer, not two.

Two things your rule needs that it does not yet say:

1. **What the right field defaults to when the file has no "last synced" version.** A file added
   after the last push has no synced commit. I propose: right = the file's **first** version, so
   the diff is "everything this file has ever been". If that is wrong, the alternative is to
   leave it unset and Done stays greyed — which is honest, and your rule already covers it.
   @ unset
2. **Changing the left field resets the right.** You said that, and I will make it a rule in the
   code rather than a habit in the screen: the right list is computed *from* the left, so it
   cannot contain something the left rule forbids.
   @ ok

---

## 4 · `@ ok, clean up` — I want to check what you mean before doing it

That comment sits under **Q4 · "lost"**, where the subject was: removing `ask` deletes the
"needs you" state, the notification, and the paused indicator.

"Clean up" reads to me as **"yes, delete all three rather than leave them unreachable"** — which
is what I proposed. But it could also mean "clean up the docs/plan", or "clean up the report
list".

**I am going to assume it means the former: delete the state, the notification, and the paused
indicator along with `ask`.** If you meant something else, say so before I start D1 — this is a
deletion, and a deletion on a wrong reading is the expensive kind.
@ delete all three rather than leave them unreachable

---

## 5 · Closing reports in the trace log — this is the new instruction, and here is how I read it

> "ensure that closed reports are marked as closed in trace log"

I read this as: **a report a person wrote should be able to end.** Today a `REPORT` line is a
checkpoint with no counterpart — the person says "sync is grey", and the log never records that it
was answered. That is why 20 of the 38 reports in the file you sent were already fixed: nothing in
the log says so, so every pass over the log re-reads them as open.

The mechanism I propose, in the log's own vocabulary:

```
16:03:20.001  149 open    0 REPORT   report  changed a file, saved but sync is still grey
...
17:41:02.553  153 open    0 NOTE     fixed   sync counts paths, not commits (6f734cb)
```

A **`NOTE fixed`** line, written by the app, naming the commit. It answers nothing (id 0, like
every NOTE), it is in the same stream, and the pairing is by *what* the person said — a reader
can see "that report, then later this fix".

**Three things this needs that I will not invent:**

1. **Who writes it.** The app cannot know which report a commit closes — that is a judgement. So
   either the person marks it, or a build ships with a list of closed reports in it. I propose the
   second: a small file the app reads at startup, holding the commits since the last build, and
   the app writes one `NOTE fixed` per commit. That is mechanical and honest, and it means the
   log says what changed between the version a report came from and the version now running.
2. **What "closed" attaches to.** A report line has no id. If a fix closes three reports, it is
   three lines or one line naming three. I propose **one line per report it closes**, because a
   reader searching for their own words should find a line right after them.
3. **That it does not rewrite history.** The log is append-only (`AgentLog` and `Trace` both say
   so). "Marking as closed" must be **a new line**, never an edit of the report line. If you meant
   "edit the report line to add a state", say so — but it breaks the one property the log has.

**The alternative reading** is that you mean the *report list I am keeping* — `docs/PLAN-bug-reports-v149.md`
— should mark closed ones as closed. It already does, and I will keep it that way.

**Both are cheap. I will do both unless you say otherwise**: the doc keeps its table, and the app
gains the `NOTE fixed` line.
@ you just replace open with closed by hand when you are done with the report so that it doesnt dangle.

---

## Order, updated for your answers

1. **The system project** (D2) — now unblocked, and it is the smallest of the four decisions.
   Includes: reserved name, delete guard, rename guard, prompt reads from it.
2. **The prompt cleanup** (D1) — remove `ask`/`conclude` and the state/notification/indicator with
   them. Now that the prompt is a file, the edit lands where the person can see it.
3. **No hidden files** — `.muffy/` visible, `.gitkeep` per your call (i)/(ii)/(iii), gitignored
   files shown distinctly (this is #16 and #21 together).
4. **Chat** — bubbles, footer id, token column out, session-log time column in.
5. **Diff mode** — the picker with two hash fields and Done greyed until both are set, reusing
   `versionsOf`.
6. **The `NOTE fixed` line** in the trace.

## What I still need from you

- **`.gitkeep`**: (i) stays hidden, (ii) show it, (iii) stop keeping empty folders
- **`@ ok, clean up`**: confirm it means "delete the state, notification and indicator"
- **The closed-report line**: confirm "a new `NOTE fixed` line, never an edit of the report line"
