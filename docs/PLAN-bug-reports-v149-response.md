# Counter-proposal — the answers, and where I disagree

Read against `docs/PLAN-bug-reports-v149.md` and your inline `@` answers. Seven answers, of
which **one I have to push back on**, two I can sharpen, and one I can size.

---

## Q1 · The linked system folder — the plan does not survive its own write path

You asked: *"making linked folders in each account's system folder will be good — so that it is
still one per app, but it can be edited from any account. Can it be done here simply?"*

**Not simply, and not this way.** I tested it rather than reasoning about it:

```
two links -> one file
after write-beside-and-rename in A:
  is accA still a link?  False
  shared file now says:  shared prompt
  accA now says:         EDITED in account A
  accB still says:       shared prompt
```

The app writes every document through `Workspace.write` → `put` → `Beside.write`
(`Workspace.kt:287,302`; `Beside.kt:13`). That helper writes `name.new` beside the target and
**renames it over** the target. `rename` replaces a symlink — it does not follow it.

So on the first save from account A: A's link becomes a real file, B still points at the shared
file, and the shared file was never written. **The link breaks silently, on the first edit**, and
the person's mental model ("one prompt, edited from anywhere") is wrong from then on. There is no
error to see, and no way to notice except by editing from B and finding it did not change.

That is not a bug I can fix by making `Beside.write` follow links: following a link on rename is
not possible (a rename cannot target the link's destination without knowing it, and then the
atomicity the helper exists for is gone — the write becomes a copy that can be interrupted).

### What I propose instead — one real file, read through the account

The system layer stays **one file, at the app's root**, and each account reaches it by *path*, not
by link:

```
<root>/system/system_prompt.txt        <- the only copy, the person's file
<root>/<account>/                      <- projects live here
```

`AgentPrompt.system(accountDir)` currently reads `<accountDir>/system_prompt.txt`
(`AgentPrompt.kt:138`). It would take the root instead and read the one file. The **browser** gets
a synthetic entry — a folder called `system/` shown at the top of every account's project list —
whose files resolve to that root folder. It is a path the browser is allowed to open, not a
filesystem object that has to survive a rename.

Cost: one resolver branch in `Workspace`, and the browser needs to know one reserved name. That is
smaller than it sounds, because the browser already asks `Workspace` for every path it lists.

**But this needs your decision, because it changes a fact about the app:** today `accountDir`
*is* the app's filesystem (`Workspace.kt:45`). Introducing an app-level root above the accounts
is a real structural change, and it is the same question as Q2.

### The three options, restated with the new information

| | Where the prompt lives | Editing from A then B | Structural cost |
|---|---|---|---|
| **(a)** | `_system/` inside each account | two copies — the report's problem returns | none |
| **(b)** | one file at the app root, browser shows it in every account | works, one copy | an app root above accounts |
| **(c)** | a real visible project named `system` | works if it is a normal project, but it syncs and chats | none — it is already a project |

**My recommendation is (c), not (b).** It needs no new structure: the browser already lists
projects, the editor already opens any text file, and `system_prompt.txt` would simply live in a
project folder the person can open. The cost is that it becomes a project — it can be synced,
chatted at, deleted — and each of those is a question ("should the prompt be in git?"). But it is
one folder the person already understands, and it needs no reserved name, no app root, and no
resolver branch.

**If you want one prompt shared by every account with no project attached, it is (b)** and I will
build the app root. Say which.
@ no need for shared root. if currently everything is per account then it should be so, and we already have local project type, system can be a special local project that cant be removed and is created when you add an account. Will that change a lot? I just dont want any hidden files - there should be locked and gitignored files, but not hidden ones.
---

## Q2 · Per app — settled, with one consequence to name

Per app. Then the consequence: **the system prompt is not in the account, so deleting an account
does not delete it, and creating a new account inherits it.** That is what "one per app" means,
and I want it said out loud because it is a data-loss question in both directions.
@ per account as is.

---

## Q3 · Message = end of turn — accepted, and it is simpler than I feared

`Agent.kt` already treats "the model stopped calling tools" as a turn end. Removing `conclude`
does not need a new ending; it removes one of two. The reply is the message.

One thing I will change with it: `AgentTools.kt:149,150` registers `ask` and `conclude` with key
specs. Removing them means removing those specs, the branch that reads their answers, and every
sentence in the prompt that mentions them. Let me check how much prompt text names them before I
promise a size.

---

## Q4 · "lost" — I will do exactly that, and here is what disappears

`ask` was the only thing that put a session into a "needs you" state. Removing it means:

- the notification "the agent is asking you something" goes
- `AgentSession.State.ASKED` (or its equivalent) goes, or becomes unreachable
- the paused-session indicator on the project row goes

**I will delete them rather than leave them unreachable.** A state nothing can enter is the kind
of thing that comes back as a mystery six months later. If any of those three turns out to be the
only reason the person knows a session is waiting, that is a loss you have accepted — I am
naming it so it is a decision and not a surprise.
@ ok, clean up

---

## Q5 · Two hash fields, tapped — accepted, and here is the rule I will hold

Left field: tap → ordered list of every commit. Right field: tap → commits **older than the
left one**. Default: left = HEAD, right = the last synced hash.

Two things I need to add to that, because the report does not say:

1. **What if there is no last synced hash?** A project that has never been pushed has no "last
   sync". The right field then has no default. I propose: right = the first commit, so the diff is
   "everything since the beginning". The alternative is to leave it empty and show nothing until
   both are chosen.
2. **What if the left field is changed to something older than the right?** The rule "right is
   older than left" then has no candidates. I propose: changing the left field resets the right to
   the newest commit older than the new left. That keeps the invariant true instead of showing an
   empty list.
   @ can setting left one just reset the right one and diff renders only when both left and right are set? And diff mod button opens a selection window where you select left and right and then click done (which is greyed out untill both are set). And not every commit but every commit where the file was chaged (like with history versions).

---

## Q6 · Read-only diff, and the selection question — sized

**Diff is read-only: yes, and it is nearly free.** `EditorScreen.kt:299` already renders text
read-only when `writing && !readOnly` is false; diff is a third branch of the same `when`.

**Copy/paste selection: yes, and it is small.** The reason it does not work now is that reading
mode renders a plain `Text` (`EditorScreen.kt:332`). Compose text is not selectable unless it is
wrapped in `SelectionContainer`. That is one import and one wrapper around the reading branch —
the `Text` and the `MarkdownBody` both. It gives the Android text-selection handles (drag to
select, Copy from the toolbar), which is exactly what you asked for.

One caveat, stated now: **`MarkdownBody` may not survive wrapping.** Markdown is rendered as
composed spans and links; a `SelectionContainer` around it makes the *rendered* text selectable,
which is usually what a person wants, but link taps and selection gestures compete. I will wrap
the plain-text branch first (where copy/paste matters for the edit workflow) and try the markdown
branch second, and if they conflict I will tell you rather than ship a gesture fight.

---

## Q7 · Order — I accept yours with one swap

Your order (from the answers) is: A → D1 → C → B → D2 → C23. I propose **one swap: D2 before
C**, for a reason that is not preference:

**The system project is where the prompt lives, and D1 (removing `ask`/`conclude`) edits the
prompt.** If I remove those tools first, I edit `SYSTEM_DEFAULT` in code; then D2 moves that text
into a file the person owns, and every phone that already has a `system_prompt.txt` keeps the
old text — the one still telling the model about `ask`. Doing D2 first means the prompt is a file
before I change what it says, and the change reaches the phone that already has one.

The catch: D2 needs Q1 settled. So:

1. **A** — the stale "stopped at the limit" sentence (minutes, real defect)
2. **D2** — the system project, once you pick (a)/(b)/(c)
3. **D1** — remove `ask`/`conclude`, and the prompt text that names them
4. **C** — chat bubbles, footer id, session-log time column (downstream of D1)
5. **B** — the small UI reports
6. **C23** — diff mode and the changes screen

If you would rather have visible UI wins first, B can go anywhere — it touches nothing else.

---

## What I am doing while you decide

- **A** — the stale prompt sentence. No decision needed.
- **The two red tests** from `9539dad`, which are mine: a test that leaned on the reader's
  `mkdirs()` side effect, and a tick test that asserted before the main thread had run. Both are
  the kind of thing the fix was supposed to expose.
- **Verifying #3 and #22** rather than assuming they are still broken.

## What I will not start without an answer

- **D2** (needs Q1: (a), (b), or (c))
- **C23** (needs Q5's two gaps: no-last-sync default, and left-changed-invalidates-right)
