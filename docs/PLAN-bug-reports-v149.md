# The 38 reports, checked against the code

Source: `trace.log (1).txt`, builds **103, 104, 129, 149**. Every `REPORT` line was extracted
with its build, then each claim was read against the current tree at `1dba1c6`.

The point of this pass is not to plan work. It is to **not plan work that is already done** —
and, where a report is closed, to be able to say *which line closes it*.

---

## Closed — 14 of 38, with the line that closes each

These are not "probably fixed". Each one has code that cites the report, or a structure that
makes the report impossible.

| # | Build | Report | Closed by |
|---|---|---|---|
| 1 | 103 | sync grey and inactive, new change not reported | `6f734cb` — the count was an id collision; `GitService.kt:83` counts **paths, not commits**, so a batch that moved three files is 3, not 1 |
| 2 | 103 | same broken sync on new files and folders | same; a new file is a new path, and the path count does not care how it appeared |
| 7 | 104 | sync always shows 1 however many changes | `GitService.kt:45,83` — the string is built and parsed in one breath, and counts paths |
| 8 | 104 | sync button itself shows a number, remove it | `BrowserScreen.kt:511` — the comment names the report; the button says "Sync" and nothing else |
| 10 | 129 | add/remove model buttons into the model window | `ModelSection.kt:85` cites the report; the buttons are in the dialog, and Settings keeps only the reset |
| 11 | 129 | why a 96k trim limit, it should follow the model | `ContextBudget.kt:49,65` — 96k is `UNKNOWN_CEILING`, used **only** when no window was passed; the window travels with the choice |
| 12 | 129 | where is max session log size | `SettingsScreen.kt:438` — a section called "The record", and the text explains that clearing up and the record cap are different acts. `MAX_SESSION_MB = 512` |
| 13 | 129 | what is agent pace description | `SettingsScreen.kt:309` cites the report |
| 14 | 129 | merge account and projects folders | `Workspace.kt:45` — **one folder per project, at the root.** There is no `projects/` wrapper to merge away |
| 17 | 129 | rename `GitHub/` to `<project>_repository` | `ProjectGit.kt:36` — `repoDir = workspace.projectDir`, so there is no `GitHub/` level. **The rename is moot**: the level it would have renamed is gone |
| 20 | 129 | merge project and repo folders, add a gitignore row button | The merge half is `ProjectGit.kt:36` (done). The row-button half is #21 below |
| 9 | 129 | move agent block up, key first field | `SettingsScreen.kt:208,238` — both cite the report |
| 6 | 103 | too much space between account and buttons | `SettingsScreen.kt:160` cites the report; `spacedBy(2.dp)` |
| 15 | 129 | battery/notification descriptions, minimally | `SettingsScreen.kt:302` — the switch, interval and ceiling are named together |

**Two of these were closed by a change that made the report's premise vanish** (#17, #20). That
is worth saying plainly: the folder merge did not "answer" the rename, it deleted the thing that
would have been renamed.

---

## Open — 24 of 38

### Group A · The four questions, and one stale sentence

| # | Build | Report | What is actually wrong |
|---|---|---|---|
| 28 | 149 | is "wait for the model" different from "stop after"? | **A question, not a bug.** Needs an answer in the UI, not code |
| 37 | 149 | "showing first n of m" — stale prompt? | **Yes, stale.** `AgentPrompt.kt:81` says a command past the limit is "stopped, and says so". The code writes the result to a file and returns the path. The prompt contradicts the tool |

**#37 is the one real defect in this group.** The prompt tells the model a lie about its own
tools, and it is one sentence.

### Group B · Small UI — six reports, six screens

| # | Build | Report | Where |
|---|---|---|---|
| 3 | 103 | + menu options need backgrounds, they overlap file rows | `BrowserScreen.kt:222` has a `Surface` with elevation — **needs checking against the report, not assumed** |
| 4 | 104 | + menu: align right, width just past the widest word | same menu |
| 5 | 104 | adding/deleting a project shows only after leaving the menu | `AppViewModel.refreshChanges` / project list recomposition |
| 16 | 129 | hidden files visible but read-only, system lock a distinct non-interactive colour | `BrowserScreen.kt:819` region — lock rendering |
| 21 | 149 | gitignore as a per-row button, not a 3-dot item | `BrowserScreen.kt:819` — confirmed still in the menu |
| 22 | 149 | lock button changes only show after re-entering the screen | `7f540ca` claims this; **needs verification, may be closed** |
@ from 103 and 104 builds are probably closed already

### Group C · Medium — one feature each

| # | Build | Report | Shape |
|---|---|---|---|
| 18 | 129 | chat icon inside the project | `BrowserScreen.kt:591` has a Chat item — **is it in the menu or on the row?** The report wants it visible |
@ this likely closed
| 23 | 149 | "see changes" should be a list you can enter; editor needs a diff mode; view/edit/diff as separate buttons | `BrowserScreen.kt:374` opens a dialog. Wants a screen + an editor mode machine |
@ yes, currently editor can only switch between view and edit.
| 24 | 149 | remove "n to send", put it in the button as "see n changes" | `BrowserScreen.kt:495` still says "$changes to send" as text beside a "See changes" button |
| 25 | 149 | `find` should be `grep`; grep and from/to must compose | **Done** — `2b98531` |
| 26 | 149 | grep answers leak line numbers into edit | **Done** — `bf60d60` |
| 27 | 149 | are grep and find separate engines? | **Done** — one selector, `2b98531` |
| 29 | 149 | grep only gives lines, why not precise selection | **Done** — the answer shape now follows the pattern |
@ and there is lines flag for grep to show line numbers, right?
| 30 | 149 | where is the system project with global settings | `AgentPrompt.kt:194` reads a system layer from `accountDir`, but there is **no folder the person can edit** |
| 31 | 149 | remove `ask`/`conclude`; message box ends with a plain message | `AgentTools.kt:149,150` — both still registered |
| 32 | 149 | chat is many bubbles, not one box | `AgentChatScreen.kt` |
@ yes, how chat is currently presented is wrong, every burst should be a paged bubble that ends with message and every page is a separate toolcall. operator's messages each is a separate bubble. And give to agent and operator bubbles (or rather rows - each bubble is a full screen width row) of different colour so that it is easy to distinguish what is written by whom. 
| 33 | 149 | remove the token column; footer with time, date, id; add time/date back to session log | `AgentChatScreen.kt:214,327` — tokens are still the column |
@ understood that you need to just remove token per message column from chat screen? not size from logs.
| 34 | 149 | system prompt is outdated — another reason it should be a file in the system project | same as #30 |
| 35 | 149 | what does the context "read" button do, what are the numbers | **Done** — `7e4ed5a` |
| 36 | 149 | context 900k of 1000k — does that mean it is full? | **Done** — `7e4ed5a` |

So Group C is **eight** real items, not fifteen: four are closed, three are the same system-project
change seen from three angles.

### Group D · Structural — two, and they overlap

1. **Remove `ask`/`conclude`** (#31) — the tools, their key specs, the machinery that reads
   their answers, and whatever the prompt says about them.
2. **A system project** (#30, #34) — a folder the person can open and edit, holding the global
   prompt, so it stops being hidden and stops going stale.

These are the two that change the app's shape rather than its surface, and both have design
gaps I cannot seal alone. They are the questions below.

---

## Questions I need you to seal

**Q1 · Where does the system project live?**
`Workspace.kt:45` says the account directory is one folder per project at the root. A system
project is not a project — it has no repository, no sync, no chat. Options:
- **(a)** a reserved name at the root, hidden from the project list (`_system/`)
- **(b)** outside the account, at the app's own storage root — one for the whole app, not per account
- **(c)** a real project named `system`, visible, editable like any other, synced if the person wants

I lean (b): the prompt is about the app, not about an account, and (a) makes a folder that looks
like a project but refuses every project act.
@ b, I thought that making linked folders in each account's system folder will be good - so that it is still one per app, but it can be edited from any account. Can it be done here simply?

**Q2 · Is the system prompt per account or per app?**
Follows from Q1, but it is a separate decision: if two accounts share one prompt, editing it in
one changes the other. If it is per account, the "same prompt for everything" idea is lost.

**Q3 · When `ask` and `conclude` are gone, how does a turn end?**
The report says "message box ends with plain message". Right now a turn ends when the model
calls `conclude` (a result the app reads) or stops calling tools (a plain message). Removing
`conclude` leaves only the second — so **is a turn that ends with text but no tool call
successful, and does it get shown as the reply?** I believe yes, but this is the load-bearing
assumption and I will not build on a guess.
@ message = end of turn (message bubble).

**Q4 · `ask` removal — what happens to the question it used to carry?**
If a model wants to ask the person something, after this change it writes it as ordinary text.
Does the app treat that as "needs you" (a notification, a paused session), or is it just a
message the person sees when they open the chat? The old `ask` made it a distinct state; that
state has to go somewhere or the feature is lost.
@ lost

**Q5 · The diff mode — two commits, chosen how?**
The report says "by default between HEAD and the last synced hash". That is a default, not a
chooser. **Can the person pick the two commits, and if so, from where?** The history screen
already lists commits; my assumption is "tap one, it becomes the left side of the diff", but
that is a design decision, not an inference.
@ when you tap left hash field you can select from ordered list of hashes, when you tap right hash field you can select from the list of hashes older than the one selected on the left. by defalt those are head on the left and last sync on the right for the project.

**Q6 · "See changes" as a screen — what can you do on it?**
The report says "a list of changed files that I can enter". Entering a file shows its diff
(presumably). **Can you also commit, discard, or ignore from that screen** — or is it read-only
and every act stays where it is now?
@ I would like to integrate android keyboard text selection of the in-app text so that I can copy it and then paste what i need in edit mode. Is it a difficult feature? I want diff mode to be readonly.

**Q7 · Order.**
I propose: **A** (the stale prompt, minutes) → **D1** (remove ask/conclude, because it changes
what the chat is) → **C** (chat bubbles, footer, session-log time column — all downstream of D1)
→ **B** (small UI) → **D2** (system project, needs Q1/Q2) → **C23** (diff mode, needs Q5/Q6).
The alternative is B first because it is visible and cheap. **Which do you want?**

---

## What I will not do without asking again

- **#3 and #22 are marked "needs verification"** above. I have not proven they are still
  broken; #22 in particular may be closed by `7f540ca`. I will check both before touching
  them, and if they are closed I will say so rather than "fix" them.
- **#18 (chat icon)** — I do not know whether the report means "there is no chat button" or
  "there is one but it is in the menu". The code has a Chat item at `BrowserScreen.kt:591`.
  I will look at what the row actually shows before changing it.
