# The plan — every report, every decision, and why

This replaces six documents. They were a conversation (`PLAN-bug-reports-v149` and five
`-response` files), and a conversation is not a specification: the decisions were scattered across
them, and they disagreed wherever the operator overruled me. This is the one place the state of
the work lives.

Source: `trace.log (1).txt`, builds **103, 104, 129, 149** — 38 reports, each read against the
tree.

---

## Part 1 · The 38 reports

### Closed — 20, each with the line that closes it

| # | Build | Report | Closed by |
|---|---|---|---|
| 1, 2, 7 | 103–104 | sync grey and inactive; new files and folders too; always shows 1 | `6f734cb` — the count was an id collision. `GitService.kt:83` counts **paths, not commits**, so a batch that moved three files is 3 |
| 8 | 104 | the sync button itself shows a number, remove it | `BrowserScreen.kt:511` — the comment names the report; the button says "Sync" |
| 3, 4 | 103–104 | + menu overlaps rows; align right, width just past the widest word | `BrowserScreen.kt:215` — `Surface` with elevation, `IntrinsicSize.Max`; the report is quoted |
| 5 | 104 | adding/deleting a project shows only after leaving the menu | `AppViewModel.kt:1966` — the report is quoted |
| 6 | 103 | too much space between account and buttons | `SettingsScreen.kt:160` — `spacedBy(2.dp)`, report quoted |
| 9 | 129 | move agent block up, key first field | `SettingsScreen.kt:208,238` — both quote the report |
| 10 | 129 | add/remove model buttons into the model window | `ModelSection.kt:85` quotes the report |
| 11 | 129 | why a 96k trim limit — follow the model | `ContextBudget.kt:49,65` — 96k is only `UNKNOWN_CEILING`, used when no window was passed |
| 12 | 129 | where is max session log size | `SettingsScreen.kt:438` — "The record" section, `MAX_SESSION_MB = 512` |
| 13 | 129 | what is agent pace description | `SettingsScreen.kt:309` quotes the report |
| 14 | 129 | merge account and projects folders | `Workspace.kt:45` — **one folder per project, at the root.** There is no `projects/` wrapper |
| 15 | 129 | battery/notification descriptions, minimally | `SettingsScreen.kt:302` |
| 17 | 129 | rename `GitHub/` to `<project>_repository` | `ProjectGit.kt:36` — `repoDir = projectDir`. **The level it would rename is gone** |
| 20 | 129 | merge project and repo folders | same line — the merge happened |
| 22 | 149 | lock changes only show after re-entering | `BrowserScreen.kt:670` — the redraw |
| 25, 26, 27, 29 | 149 | `find` should be `grep`; grep+from/to compose; are they separate engines; grep gives only lines | `2b98531`, `bf60d60` — one selector, and the answer shape follows the pattern |
| 35, 36 | 149 | what does the context "read" button do; "900k of 1000k" | `7e4ed5a` — button gone, one line |

**Two of these closed themselves.** #17 and #20 were answered by the folder merge *deleting* the
level they were about. Worth remembering: a report can become moot, and "closed" is not always
"fixed".

**#37 was not a defect, and I said it was.** I claimed the prompt lied about timeouts. It does
not: `Timeouts.kt:112` says a direct call past the limit *is* stopped, which is what the prompt
says. "showing first n of m" appears nowhere in the current prompt — it was removed. I reverted
the prompt edit I had written, uncommitted.

### Open — 18

| # | Build | Report | State |
|---|---|---|---|
| 16 | 129 | hidden files visible, read-only; system lock a distinct non-interactive colour | **next up** (step 3) |
| 18 | 129 | chat icon inside the project | needs a look at what the row shows |
| 21 | 149 | gitignore as a per-row button, not a 3-dot item | **next up** (step 4) |
| 23 | 149 | "see changes" as an enterable list; editor diff mode; view/edit/diff buttons | step 7 |
| 24 | 149 | remove "n to send"; make it "see n changes" as a button | step 4 |
| 28 | 149 | is "wait for the model" different from "stop after"? | **a question, not a bug** — needs an answer in the UI |
| 30, 34 | 149 | where is the system project with global settings; the prompt is outdated | **done** — step 1, `203c605` |
| 31 | 149 | remove `ask`/`conclude`; the message box ends with a plain message | step 5 |
| 32 | 149 | chat is many bubbles, not one box | step 6 |
| 33 | 149 | token column out; footer with time, date, id; time column back in session log | step 6 |
| 37 | 149 | is "showing first n of m" stale? | **closed** — it is gone, and the prompt is accurate |

---

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
