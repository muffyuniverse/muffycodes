# The plan — every report, every decision, and why

This replaces six documents. They were a conversation (`PLAN-bug-reports-v149` and five
`-response` files), and a conversation is not a specification: the decisions were scattered across
them, and they disagreed wherever the operator overruled me. This is the one place the state of
the work lives.

Source: `trace.log (1).txt`, builds **103, 104, 129, 149** — 38 reports, each read against the
tree.

---

## Part 1 · The 38 reports

The numbering is the log's own: the order the reports appear in `trace.log (1).txt`. **33 are
marked `closed` in the log and 5 are open**, and the count in this document is taken from that
file rather than remembered — an earlier draft of this plan said 20 and 18, which was wrong, and
the three it had lost (#9, #20, #21) were the three that were not about code I had touched.

### Closed — 33

| # | Build | Report | Closed by |
|---|---|---|---|
| 1 | 103 | changed a file, saved, but sync is still grey and inactive | `6f734cb` — the count was an id collision, not a stale redraw |
| 2 | 104 | same broken sync on new files and folders; guess deletions and moves too | `GitService.kt:83` counts **paths, not commits** |
| 3 | 104 | between the user account and the buttons below it there is too much space | `SettingsScreen.kt:160` — `spacedBy(2.dp)`, report quoted |
| 4 | 104 | the + menu's options overlap the file rows | `BrowserScreen.kt:215` — `Surface` with elevation |
| 5 | 104 | adding or deleting a project shows only after leaving the menu | `AppViewModel.kt:1966` — report quoted |
| 6 | 104 | the + menu is ugly; align right, just past the widest word | `BrowserScreen.kt:215` — `IntrinsicSize.Max` |
| 7 | 103 | sync always shows 1 to send even when there are many changes | `6f734cb` — same id collision as #1 |
| 8 | 104 | why does the sync button itself show a number? remove it | `BrowserScreen.kt:511` — the button says "Sync" |
| 10 | 129 | why a 96k trim limit? it should follow the active model's context | `ContextBudget.kt:49,65` — 96k is only `UNKNOWN_CEILING` |
| 11 | 129 | where is the max session log size? | `SettingsScreen.kt:438` — `MAX_SESSION_MB = 512` |
| 12 | 129 | what is the agent pace description? | `SettingsScreen.kt:309` quotes the report |
| 14 | 129 | move add/remove model into the model window; keep only reset in settings | `ModelSection.kt:85` quotes the report |
| 15 | 129 | move the agent block up under "signed in as"; key first | `SettingsScreen.kt:208,238` — both quote the report |
| 16 | 129 | merge the account and projects folders so the account shows projects | `Workspace.kt:45` — **one folder per project, at the root** |
| 18 | 129 | rename `GitHub/` to `<project>_repository` | `ProjectGit.kt:36` — **the level it named is gone** |
| 19 | 129 | battery/notification descriptions, minimally | `SettingsScreen.kt:302` |
| 22 | 129 | merge the project and repo folders; add a row button for gitignore | `ProjectGit.kt:36` — the merge happened; the row button is #26 |
| 27 | 149 | the lock button's change shows only after re-entering the screen | `BrowserScreen.kt:670` — the redraw |
| 31 | 149 | the agent says grep is called `find` | `2b98531` — one selector, named grep |
| 32 | 149 | grep passed into edit carries its line numbers | `bf60d60` — `stripNumber` |
| 33 | 149 | are grep and find separate engines? | `2b98531` — there is one selector |
| 34 | 149 | why does grep only give lines? why is find crippled? | `bf60d60` — the answer shape follows the pattern |

| 9 | 129 | the openrouter key is not passing through | `74b4c0d` — `headers()` is now a question a test asks, and a key with a line break is refused rather than sent |
| 13 | 149 | a result that overruns the timeout blips; instead tell the agent it will arrive on a later turn | `3f0cc25` — the foreground hands the work to `background`, and the turn is told the path |
| 24 | 149 | what does the context "read" button do, and what are the numbers beside it? | the button and the numbers no longer exist |
| 25 | 149 | "context 900k of 1000k" — is the context full? | gone with #24 |
| 35 | 149 | where is the system local project with the global settings? | step 1 — `203c605` |
| 37 | 149 | the system prompt is outdated; it should be an editable file in the system project | step 1 — `203c605`, `0285bc0`; one path, no fallback |
| 38 | 149 | is "showing first n of m" a stale part of the system prompt? | the phrase is not in the prompt, and over-limit results go to a file whose path is returned |

| 26 | 149 | gitignore as a per-row button, not a 3-dot item | `494b7f0` — its own button beside Lock |
| 28 | 149 | make hidden files visible but read-only, with a system lock in a distinct colour | `cd87b3c`, `156a2ea` — `.muffy/` is a row, locked in tertiary, no menu |
| 30 | 149 | remove "n to send"; make it "see n changes" as a button | `494b7f0` — one button, no separate caption |

| 23 | 149 | how is "wait for the model" different from "stop command after"? | `96eef42` — two named groups, one unit, every row saying what it bounds; the ack/idle split is `PLAN-streaming.md` |

**Two closed themselves.** #16 and #18 were answered by the folder merge *deleting* the level they
were about. A report can become moot, and "closed" is not always "fixed".

### Open — 5

| # | Report | What it needs |
|---|---|---|
| 17 | where is the chat icon within the project? | look at what a project row shows |
| 20 | block the agent settings together, rule them off, drop "agent" from every label but the block title | step 7 |
| 21 | the "signed in as" block still has gaps between rows | step 7 |
| 29 | "see changes" should be an enterable list; editor needs a diff mode with a two-commit picker; view/edit/diff as separate modes | step 6 |
| 36 | chat as many bubbles; remove `ask`/`conclude`; message ends with a plain message; drop the token column; footer with time, date, id; time column back in the session log | step 5 |

## Part 2 · The decisions, and the reasons

Each of these was asked, answered, and is now a rule. The reason is here because a rule without
one gets re-litigated.

### The system project

- **A local project named `system`, lowercase, one per account**, made with the account. It holds
  what belongs to the whole account rather than to one project — the system prompt first.
  *Reason:* the report said it — "system prompt is outdated. Another reason why it should be an
  editable file inside system project instead of a hidden one". A hidden file is one nobody edits.
- **It cannot be deleted, and cannot be renamed in either direction.** *Reason:* letting another
  project take the name leaves two folders called `system`, and the delete guard then refuses both.
- **The guard is case-insensitive.** *Reason:* a filesystem that folds case would otherwise offer a
  second `System`, and the one property this project must have is that there is exactly one.
- **It is local** — no remote, nothing pushed. *Reason:* settings that belong to a phone should not
  travel to a repository.
- **The prompt has exactly one path** — `<account>/system/system_prompt.txt`. *Reason:* a fallback
  to the old root path is compatibility machinery for a release nobody runs. Two places to look are
  two places to disagree.
- **Rejected: symlinks into a shared folder.** Measured, not reasoned: every save goes through
  `Beside.write`, which renames over the target, and a rename **replaces** a symlink rather than
  following it. The first edit from one account silently turns its link into a real file and leaves
  every other account on the old text.

### gitignore, sync-ignore, and what "untracked" means

- **There is no push-ignore in git, and there cannot be.** A commit is a whole tree; a commit
  without a file is a *different* commit. "Syncignored" cannot mean "the phone's commit minus a
  file".
- **gitignore is an *untracked* filter.** It stops git from *starting* to track a path and does
  nothing to a path already in the repository. Measured: a tracked file, ignored, edited, committed,
  still had its edit in the commit.
- **Therefore ignoring a file must untrack it** (`git rm --cached`), keeping the file on disk and
  editable. **Fixed in `b31198e`** — before it, "Add to .gitignore" wrote a line, committed it, said
  "Ignoring x", and x went on being pushed. Every file a person has saved is already tracked,
  because a save commits, so the button was wrong for exactly the files people use it on.
- **Untracked is not deleted.** Measured: after untracking and pushing, a fresh clone has no file in
  its tree, but `git log --all -- <path>` still finds the commit and `git cat-file` still prints the
  contents. So the button says, when the path was ever pushed: *"Ignoring x. Its earlier versions
  stay on GitHub — ignoring stops it being sent from now on; it does not take back what was sent."*
  *Reason:* a person who ignores a file because it holds a key has not removed the key from GitHub.
  Only rewriting the remote's history could, and that is a force-push that destroys everyone else's
  copy.
- **The warning is shown every time, not only for secret-looking names.** *Reason:* the app cannot
  judge what is a secret. A name list (`*.key`, `.pem`, `.env`) would be right about the names
  someone thought of and silently wrong about every other file.
- **Un-ignoring re-adds the file** to the index, so its current content is pushed. *Reason:* a
  toggle whose off-state does nothing visible is a lie.
- **The app's own ignore lines live in `.git/info/exclude`, the person's in `.gitignore`.**
  *Reason:* a person opening `.gitignore` should see only their own decisions.

### No hidden files

- **`.muffy/` becomes visible**, and its files are **system-locked**: readable, not editable, and
  the person cannot unlock them. *Reason:* the operator's rule — there should be locked files and
  ignored files, not hidden ones.
- **`.gitkeep` stays hidden — the one exception.** *Reason:* it exists so git can record an empty
  folder, and showing it puts a stray file in every folder a person makes.
- **A system lock is a second lock kind**, not a second code path: the person's lock is a toggle
  they own; the system's is the app's.

### Diff mode

- **Two hash fields.** Left: every commit. Right: only commits older than the left. Default left =
  HEAD, right = `refs/remotes/origin/<branch>`.
- **Right is unset when the file has no synced version**, and Done stays greyed until both are set.
  *Reason:* honest, and it needs no rule about "the first version".
- **Changing the left resets the right.** *Reason:* the right list is computed *from* the left, so it
  cannot contain something the left forbids.
- **The list is `versionsOf`** — `git.log().addPath(path)`, every commit that changed the file.
  *Reason:* it is already what the History screen shows; one answer, not two.
- **The ref is resolved when the picker opens, never remembered.** *Reason:* a shortened history
  re-ids every commit (`GitService.kt:452`), so a remembered hash is a position that moves.
- **Diff is read-only.** Reading mode gets `SelectionContainer`, so Android's text selection and
  Copy work — that is the copy/paste path into edit mode.

### Chat and turns

- **A message ends a turn.** With `ask` and `conclude` gone, the turn ends when the model stops
  calling tools, and its text is the reply.
- **`ask`'s "needs you" state is deleted**, with its notification and its paused indicator.
  *Reason:* the operator said "lost", and a state nothing can enter comes back later as a mystery.
- **Every burst is a paged bubble ending with a message**; every page is one tool call; the
  operator's messages are one bubble each. Agent and operator rows are different colours.
- **The per-message token column goes.** The log's sizes stay. **The footer shows time, date and
  id**, and the session log gets its time and date column back.
- **A `system` row is a project row.** No special casing in the list.

### The trace log

- **A closed report is `REPORT  closed` instead of `REPORT  report`** — edited by hand in the log,
  changing only that field. *Reason:* the log is append-only and the app never rewrites it; the
  operator asked for the state replaced, not a second line added.
- **The log has no report-state column** — its rows are `time id kind name detail`, and the `state`
  column added this session means *app shown/hidden*. The name field is where a row's verdict goes.
- **Back up before editing, verify reversibility after.** The log is outside the repo and cannot be
  restored from git.

---

## Part 3 · What is done, and what is next

### Done

| What | Commit |
|---|---|
| Ignoring a file untracks it, so "Ignoring x" is true | `b31198e` |
| The system project exists, is guarded, and holds the prompt | `203c605`, `0285bc0` |
| 22 reports marked `closed` in `trace.log (1).txt` | — (the log is outside the repo) |
| This plan, replacing six conversation documents | — |

### Next, in order

1. **The ignore button's two sentences** — short when the path was never pushed, long when it was.
2. **No hidden files** — `.muffy/` visible and system-locked; `.gitkeep` the exception.
3. **Per-row lock and syncignore buttons**, both toggles, both with a way back; syncignore's off
   re-adds the file. This is #21 and #16 together.
4. **Remove `ask`/`conclude`** and the state, notification and paused indicator with them.
5. **Chat** — bubbles, footer, token column out, session-log time column in.
6. **Diff mode** — the picker, `versionsOf`, right unset, Done greyed.
7. **#18** (the chat icon) and **#28** (the wait/stop question), once looked at.

### Not a bug report: the streaming work

`docs/PLAN-streaming.md` records the change that the timeout question opened up. It is not one of the
38 — it came out of R23 while answering it.

**What it is:** the app sends `stream: false`, so the provider says nothing until the answer is
complete. That makes a dropped request and a diligent model the same silence, and one number has to
serve both. Streaming splits it into an **ack** timeout (silence before the first chunk — retry is
free) and an **idle** timeout (silence between chunks — the stream died).

**What it buys the footer:** the mechanism was already proven watching a kimi audit — every 30
seconds, `30s answer=0c thinking=12060c`, 38 lines over 19 minutes. The message bubble's footer can
show elapsed time, thinking produced, answer produced and tool calls assembled, live, while the
model works. Measured detail: those were **characters**, not tokens; token totals arrive only in the
final `usage` block, so a per-chunk token count would be an estimate dressed as a measurement.

**Not a progress bar.** Elapsed and produced, never a percentage — there is no denominator.

### Known and not yet fixed

- `RetentionFeatureTest.kt:73`, `HistoryFeatureTest` folder restore, `LongRunner.collect` "watcher
  removes nothing", `AgentTest > an unknown revision is refused` — pre-existing, to re-check when
  touched.
- No test asserts each of the 88 tap names is *reachable from the screen*; `check-taps.py` proves
  names and pairings only.

---

## Part 4 · What I got wrong, kept on purpose

A plan that records only the conclusions teaches nothing. These were mine:

1. **I said the timeout prompt was a lie.** It was not — `Timeouts.kt:112` is accurate for the path
   it describes. I had written the edit and reverted it before committing.
2. **I said an ignored file "stops being sent".** True going forward, false about the past: every
   version already pushed stays readable. The operator's question exposed it.
3. **I proposed symlinked system folders.** Measured, and it breaks on the first save.
4. **I wrote a fallback path for the prompt** — compatibility machinery for a release nobody runs,
   in the same session I was told not to.
5. **I counted "20 of 38 closed" twice** when the number was 22, because an editor save reverted a
   paragraph I had added.
6. **I wrote a test against `ProjectGit` when `scene.repo()` returns `RepoHistory`.** CI found it at
   compile time; the container has no Kotlin compiler, so CI is the compiler.
