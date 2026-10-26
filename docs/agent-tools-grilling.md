# Grilling: the logs, the code, and what still disagrees

*Everything I found by reading the two debug logs against the tree at `4d1c825`. Nothing has been
changed since. Each item is a claim with evidence, a proposal, and a line to answer on.*

## How to answer

Every item ends with a line that is only an `@`. Write after it:

```text
@ yes                       the item is right, do what it proposes
@ no — <why>                the item is wrong, or the act should be different
@ later                     true, not now
@ <anything else>           your own instruction; it wins over my proposal
```

`grep -n '^@$' docs/agent-tools-grilling.md` lists everything still unanswered. The IDs are stable —
quote `A1`, `B3` in a reply.

## What was read

| file | what it holds |
| --- | --- |
| `MuffyCodes log/trace.log.txt` | build 103, one session: project rows, copy, move, create, rename, history, sync |
| `MuffyCodes log/trace.log (1).txt` | build 103 → 104 → 129: adding and deleting projects, sync, the settings round, model and key |

**Twenty-four `REPORT` lines** in total, which are the operator's rulings. Fifty-one of the
sixty-six items below are that list cross-checked; the rest are contradictions I found between the
code and its own documents.

Method: every claim carries `file:line` at HEAD. I did not run the app — there is no JVM in this
container, and CI is the only compiler.

| id | what | how bad | cost |
| --- | --- | --- | --- |
| A1 | two background calls of one tool share a result file | returns the wrong data | 1 line |
| A2 | `read`'s description describes the range backwards | a model asks for the wrong lines | 1 line |
| A3 | `delete`'s description is narrower than the tool | the model under-uses it | 1 line |
| A4 | Settings still promises truncated results | says the opposite of the code | 1 line |
| A5 | a refusal is measured against the limit it reports | the cause is eaten by the complaint | 3 lines |
| A6 | unknown arguments are ignored (`lines` included) | a typo means "everything" | 1 test |
| A7 | `moveSelected`'s doc argues against its body | a reader is told the wrong rule | 2 lines |
| A8 | a batch is pre-checked, not all-or-nothing | files move and are never committed | a decision |
| B1 | the 96k floor is fixed on the screen, not in the runner | trims a big model at 96k | 2 lines |
| B2 | "N to send" counts commits, not changes | a ten-file batch reads "1" | a decision |
| B3 | the session-log limit has no field | prose only | a decision |
| B4 | the agent block is not under the account | two blocks between them | a move |
| B5 | one label still says "agent" | wording | 1 line |
| B6 | "local · this phone only" survives | wording | a decision |
| C1 | the handover ruling was superseded | none | — |
| C2 | the `GitHub/` rename became moot | none | — |
| D1 | four comments describe the erased handover | a debugger reads a lie | 4 edits |
| D2 | `LONG_DIR` names a deleted concept | a name | 2 lines |
| D3 | the plan speaks in the present about deleted code | history vs. reference | a decision |
| D4 | one comment still explains `maxResult` by truncation | a lie | 1 line |

---

## A. What I broke in the change I just shipped

### A1. Two background calls of the same tool write the same file

The dispatch hands `background` **only the arguments** (`AgentTools.kt:218`), so the inner call is
built with whatever `id` the model typed, defaulting to `"0"` (`AgentTools.kt:253`). The result file
is named `tool-id` (`Background.kt:71`). So two backgrounded `read`s both write
`agent/background_results/read-0.txt` — and the comment directly above that line promises the
opposite ("two commands run in the same second cannot overwrite each other's result",
`Background.kt:63-69`).

The consequence is not cosmetic: the first call's answer is replaced by the second's, and the agent
reads it as the answer to its own question. Nothing in the record shows the swap.

**Proposal:** pass the outer call into `background` so the inner id is the outer id — one line plus
its signature — and a test that two backgrounded reads of two files keep two results.

@ ok

### A2. `read`'s description says the range backwards

`"`from` and `to` are line numbers, both included; `to` alone means the end."` (`AgentTools.kt:106`).
It should say the opposite: `from` defaults to 1 and **`to` absent** means the end
(`slice`, `AgentTools.kt:451-465`). A model that follows the description writes `{"to":200}` meaning
"200 to the end" and silently gets lines 1–200 — the most expensive kind of wrong answer, because
nothing refuses.

**Proposal:** `"`from` and `to` are line numbers, both included; `from` starts at 1 and `to` left out
runs to the end."`

@ ok

### A3. `delete`'s description is narrower than the tool

The spec says "Delete one file or an empty folder" (`AgentTools.kt:132`); the tool removes any folder
and everything in it (`Workspace.kt:556-580`), and the reference doc says so
(`agent-tools-review.md:22`). No test calls the `delete` tool on a non-empty folder, which is why
nothing caught it.

**Proposal:** "Delete a file, or a folder and everything in it.", plus one test — the shape that is
already asserted for `Workspace.delete` but not for the tool.

@ ok

### A4. Settings still promises a truncated result

`"A result longer than the length is cut and says how much it showed."` (`SettingsScreen.kt:452`).
`trim()` returns an error naming the size and the limit (`AgentTools.kt:842-846`), and the prompt
says a result is never cut short (`AgentPrompt.kt:68`). The screen is describing the build before
last.

**Proposal:** `"A result longer than this is refused, and says its size and how to narrow it."`

@ ok

### A5. A refusal is measured by the limit it is complaining about

`refusal(said)` goes through `trim()` (`AgentTools.kt:794`), so an error message that is itself
longer than the limit is replaced by "That result was N characters and the limit is M…". The cause
disappears and the answer becomes about the size of the complaint. Only reachable with a very long
refusal today, but the failure mode is exactly backwards: the bigger the problem, the less is said.

**Proposal:** `refusal()` bypasses the limit; only results are measured. A refusal is capped by its
authors, not by the operator's number.

@ I think that if the error is too big then the initial command should run in background. no divergence - more simplicity and uniformity.

### A6. Unknown arguments are silently ignored

`slice()` reads `from` and `to` and nothing else (`AgentTools.kt:451`), so:

```text
read {"from":10,"lines":40}     means 10 to the end, not 40 lines
read {"form":10,"to":40}        means the whole file
```

The old `lines` argument is the dangerous one: it was in the shipped description, a model that
learned it will keep sending it, and its meaning flipped from "how many" to "nothing at all".

**Proposal:** reject any key a command does not take, naming the ones it does — the same rule every
other refusal in this app follows. `lines` gets its own sentence for one release ("use `to`").

@ any wrong key should error command out before any other part of it is called, error contains instruction on how to use this command.

### A7. `moveSelected`'s doc argues against its body

`"The files are moved one at a time so that a single refusal … does not take the rest with it"`
(`AppViewModel.kt:1152-1157`), while the body pre-checks and moves nothing when one name is taken
(`:1169`). Two rules, one function; the doc is the one a reader meets first.

**Proposal:** replace the doc block with the rule the body follows, and keep the note about commits
happening once.

@ ok

### A8. "All or nothing" is a pre-check, not a transaction

After the name and lock check pass, the first mid-flight failure returns `false` with the earlier
files already moved (`AppViewModel.kt:1174-1181`) or copied (`:1214-1221`) — and because it returns
before `refresh()` and before any commit, **those files are on disk and in no repository.** That is
precisely the state this app exists to make impossible to miss.

What actually runs is: check every name → move one at a time → stop at the first real failure.

**Proposal (choose one):**

1. **Undo:** on failure, move the already-moved files back. Honest, but a second failure during the
   undo leaves a mess, and a cross-project move cannot always be undone.
2. **Commit what happened:** on failure, commit the files that did move and say
   `"3 of 5 moved; "x.txt" is blocked"`. The screen tells the truth and the history agrees with it.
3. **Keep the pre-check only**, and change the design's sentence from "all or nothing" to "checked
   first, then stopped at the first refusal".

I would take 2 — it never loses work, and it is the only one that leaves the history matching the
disk.

@ 2 + precheck

---

## B. Rulings from the logs that are not done

### B1. The 96k floor is fixed on the screen and not in the runner

Ruling: *"why there is 96k limit on trim? it should be only capped by active model context size"*.

The Settings line now reads the catalogue's window (`SettingsScreen.kt:366-381`), and the model row
too (`ModelSection.kt:87-95`). The runner does not: `AgentRunner.kt:131` and `AppViewModel.kt:2038`
call `ContextBudget.ceiling(provider)` with no window, so any pin that is not in the five-entry
`CEILINGS` table (`ContextBudget.kt:40-46`) is trimmed at `UNKNOWN_CEILING = 96_000`
(`ContextBudget.kt:49`) — while the screen promises the model's real size. The default pin
(`deepseek`, 655 360) is in the table, which is why this has not bitten yet.

**Proposal:** read the catalogue in both places, the way the screen does. The number the agent is
held to and the number on the screen become the same call.

@ context ceiling is is limited by the selected model/provider's max context, and selected by operator (0;limit). session ceiling is not limited (0;inf). and max message size is a separate one and limited by max input window (0;limit).

### B2. "N to send" counts commits, not changes

Ruling: *"sync always shows 1 to send even when where are many changes"*.

The counter is incremented once per commit (`ProjectGit.kt:135`, `:349`) and added to the total as
`ahead` (`GitService.kt:85`). Saving five files one at a time reads "5 to send" — right. Moving ten
files as one batch reads "1" — because that is one commit. So the same complaint survives in the
batch case, and the doc calls the number "how many changes this project has" (`Workspace.kt:692`).

**Proposal (choose one):**

1. Count **changed paths** per commit (`GitService.changedPaths` is already called there) — the row
   then says "10 to send" for a ten-file batch, and "1" for one save.
2. Keep counting commits and rename the row to what it is.

I would take 1: the person is looking at files, and the number should be files.

@ 1, and I want to the left of sync to add a button "see changes" and for a window with a list of files being opened when you touch it, and you can touch every of the listed files and see them inside the editor (and when you exit editor you return back to the window of changed files). And I want preview to show diffs between head and latest synced commit (deletions in read, additions in dark green, unchanged in text color of the theme) - only for text files.

### B3. The session-log limit has no field

Ruling: *"where is max session log size? because context trim just switches row out of context, and
session log limit removes them altogether"*.

They are named apart in prose (`SettingsScreen.kt:476-488`) and the number is the constant
`AgentLog.SESSION_LIMIT`, with no row to change it. The question was "where is it".

**Proposal:** a `LengthField` under "The record", keyed like the others, defaulting to the current
constant; `0`/blank keeps everything.

@ ok

### B4. The agent block is not directly under the account

Ruling: *"move agent block up, right below signed in as block"*. **Theme** (`SettingsScreen.kt:250`)
and **Change history** (`:282`) still sit between them; the comment at `:296-310` claims the move
happened.

**Proposal:** account → AGENT → Theme → Change history → The record → Running/Background/Reminders.
The agent block is the one people come to Settings for.

@ ok, and separate the blocks (preferably with horizontal lines or any standard compose bestpractice).

### B5. One label still begins with "agent"

Ruling: *"remove agent from every field title except the title of the block"*.
`"Check an idle agent every (seconds)"` (`SettingsScreen.kt:401`) survives.

**Proposal:** `"Check on it every (seconds)"`.

@ ok

### B6. "local · this phone only" is still there

Ruling: *"still have "local" word under filenames, should we really keep it?"* — a question, never
answered. The row is `isLocalProject(name)` → `"local · this phone only"` (`BrowserScreen.kt:444`).

Now that the project folder *is* the repository, the survivor is the one case where a project has no
remote. Two readings: keep it (it explains why Sync is missing), or drop it (the absence of Sync
already says so, and the row exists to answer "how much is unsent").

**Proposal:** drop the word and say the useful half — `"this phone only · no GitHub"`. Or nothing at
all, if you would rather the row only ever show a count.

@ nothing

---

## C. Rulings that a later ruling replaced — listed so they are not re-opened

### C1. "A command that takes longer… will be returned on one of the next turns"

Ruling 13 in the logs asked for the **handover**. It was built and then erased: `b0c4b15` deleted
`LongRunner`, the pool and the protocol, and `e11f164` made the limit default to zero. The rule now
is *stopped, and says so* (`Agent.kt:66-70`, `AgentPrompt.kt:78-81`, `SettingsScreen.kt:414-428`).
The code matches the later ruling; nothing to do.

@ right

### C2. "Rename `GitHub/` into `<project>_repository`"

Ten minutes later the same session asked for the merge that removed the folder. Moot.

@ right

---

## D. Wording that still describes erased things

### D1. Four comments describe the handover

| where | says |
| --- | --- |
| `AgentRunner.kt:149-151` | "finishes beside the conversation… come back for it with the same arguments" |
| `AppViewModel.kt:2049-2051` | "the same registry the polls use… collected by their next message" |
| `SettingsScreen.kt:391-394` | "past it the agent is told the command is still running" |
| `SettingsStore.kt:311` | "how long a command may run before a handover" |

Plus the test name `SettingsScreenTest.kt:172` ("the command handover is off…"), which asserts
something about a mechanism that no longer exists.

The first is the poll path — the comment a person debugging a stuck agent reads first, and it
describes behaviour that cannot happen.

**Proposal:** say what the setting does now: the command is stopped, its result says so, the turn
carries on. Rename the test to `a command limit is off on a phone that never chose one`.

@ set default to 180 sec

### D2. `LONG_DIR` names a deleted concept

`AgentTools.kt:90` / `Background.kt:71`. It survived because the folder is still the right one —
only the name is a relic of `LONG`.

**Proposal:** `RESULTS_DIR`, or just `AgentLog.DIR` at the one use.

@ `RESULTS_DIR`

### D3. The plan speaks in the present about deleted code

`docs/plan-commands.md:49-59`: "`take(20)` is still in the tree", "`MAX_SCAN`", "`LONG`". All three
are gone, and the file opens "First, what is already true". The repo's own rule is that a reference
describes the code (`2118d35`).

**Proposal:** one line at the top — *"Historical: what was true when this plan was written. The
reference is [agent-tools-review.md]."* — rather than editing its findings into the past.

@ ok

### D4. A comment still explains `maxResult` by truncation

`AppViewModel.kt:2045`: *"a person who is told "showing first 20000 of 40000" should be able to raise
the 20000"*. That sentence is why the field exists; the field now reports a refusal instead.

**Proposal:** keep the field, rewrite the reason: the limit is a conversation budget, so it is a
field rather than a constant.

@ ok

---

## E. Decisions I should not make for you

These are the ones behind the items above where the act itself is a choice. Answer the item instead
if you prefer; these exist so the choice is visible.

**E1 — Batch failure shape.** A8, option 1, 2 or 3.

@

**E2 — "to send" means commits or files.** B2, option 1 or 2.

@

**E3 — Is the session-log limit a setting?** B3.

@

**E4 — Does "this phone only" stay on a project row?** B6.

@

**E5 — Should unknown arguments be refused everywhere?** A6. The trade: a strict tool tells a model
its typo, and can also frustrate a model that read an older description.

@

---

## F. Looked at, and left alone on purpose

So they are not re-raised later:

| thing | why it stays |
| --- | --- |
| `AgentPrompt`'s `PREVIOUS_SYSTEM` entries still saying "showing first N of M" | They must stay byte-identical to what shipped; that is how an unedited default is recognised (`AgentPrompt.kt:257-266`) |
| `docs/agent-context-and-loop.md:57` "the box and `read` take **lines**" | Still true — `from`/`to` are line numbers, and the box has none of its own |
| `ObservabilityScreen.kt:118` `take(20)` | A short list of recent failures on a screen, not a tool result |
| `Dialogs.kt:569` quoting the report that contains "clone" | A record of what was reported, not a description of the app |
| `AgentTest.kt:453` asserting "still running; call again" is absent | The test that caught the handover, kept as a tripwire |
| `Workspace.freeName` surviving for imports | The person never chose those names; only copy stopped numbering |

---

## G. If you answer nothing else

My order, because it is the order of damage: **A1** (wrong data, silently), **A2** (wrong request,
silently), **A8** (moved files that no repository knows about), **A4/A5** (the app describing
itself wrongly to the person and to the model), then B1, then the wording in D.

@
