# The audit: branches, the system folder, and the eleven things that were wrong

**Written 2026-11-07, after the work landed, and extended the same night as the audit found more.**
Every claim below is re-checked against the code at `be28ed4`, and each fix names the commit and the
test that holds it. §2 is what reading the code found; §2b is what writing a test that walks the whole
loop found afterwards — and it found more, which is the useful thing to know about this file. Nothing here is recalled from a
plan; the plans are the next file over, and this one is what is actually true.

The scope was the operator's instruction: *"do the full audit, add falsification tests and find gaps
and wire the elements together, fix as many potential bugs as you will be able to find."* Branches
(§13d) and the system folder (§13f) were the two things finished that night, so they are the audit's
subject — plus every element that was built and called by nothing.

## 1. The one defect that made the rest invisible

**An agent on a branch read and wrote `main`'s files, and said nothing.** `AgentTools.projectDir` was
`ws.projectDir(project)` — the project's root, which is main's working tree — and the session's branch
reached its notification, its key and its diary but never its tools.

Both halves are worse than they look:

- The screen said `feature`, the file list said `feature`, and the files were main's.
- Everything a person did *around* the tools was correct, so the pieces agreed with each other and
  only their subject was wrong.

Fixed in `30a5530` by making `projectDir` answer `ws.treeOf(project, branch)`. That was one line
because **`AgentTools` never calls `ws.fileAt`** — every tool composes `File(projectDir, path)` and
every answer is `file.relativeTo(projectDir)`. Measured: zero `fileAt(` call sites in the file.

Held by `AgentTest`: *a write on a branch lands in the branch's tree and leaves main alone*, *a read on
a branch answers the branch's text, not main's*, *a commit on a branch moves that branch and leaves
main's history where it was*.

## 2. The eleven, each with what it would have done

| # | Defect | What a person would have seen | Fixed | Held by |
|---|---|---|---|---|
| 1 | Agent tools resolved main's tree | Edits on a branch landing in main, unnoticed | `30a5530` | `AgentTest` ×3 |
| 2 | `ProjectGit` committed in the root, not the write's tree | A save reported success with **no version behind it** — the one promise the app makes | `1846abd` | `BranchFeatureTest` |
| 3 | `AgentTools.projectOf` took the first path segment | Every write in a worktree refused: *"`notes.txt` belongs to `.muffy`, another project"* | `00deea2` | `AgentTest` |
| 4 | `Worktrees.rename`/`removeAll` called by nothing | A rename orphaned every branch tree with pointers to paths that no longer exist; a delete left them for ever | `1d8d2c5` | `WorktreesTest` |
| 5 | `deleteRecursively` follows symlinks | Deleting a project would have **emptied the account's `system` folder** through the link | `e7b0d65` | `WorkspaceTest`, `WorktreesTest` |
| 6 | `ensureIgnore` began `if (!git.isDirectory) return` | Both exclude writers were silent no-ops in every worktree, where `.git` is a file | `e7b0d65` | `WorkspaceTest` |
| 7 | The link had no exclusion in older projects | The project reports a change nobody made; a commit puts a symlink in the repository | `3e9f168` | `WorkspaceTest` |
| 8 | `isProjectFolder` said `\|\| isSystem(...)` | The "dangling" project: a row promising a history, a status and a remote that did not exist | `e7b0d65` | `WorkspaceTest`, `ProjectsFeatureTest` |
| 9 | `startRelease` called by nothing | The app could not publish a release from inside it at all | `b8aa5f8` | `ProjectsFeatureTest` |
| 10 | `closeBranches` does not leave the branch; `activeBranch` is ambient | Open a branch, go up, open another project → **an empty project** | `92ca31b` | `WorkspaceTest` |
| 11 | `dropBranch` deleted the ref only | A fork point: a folder git lists, `branches()` keeps offering, paths resolve into | `5dbb4ed` | `BranchesScreenTest` |

Three smaller ones, fixed in the same passes and worth naming because each was a *whole class* of
defect rather than an instance:

- **`startRelease`'s `githubFor(login) ?: return`** — a press that did nothing at all. Every refusal in
  this app says a sentence; this one said nothing (`1d8d2c5`).
- **Housekeeping iterated project folders only** — each tree keeps its own `agent/`, so a marker
  written into a branch's tree was one the pass never saw, and the app reported "nothing to do" for
  ever while that branch's index grew (`7772903`).
- **The index rebuild cleared main's index only**, leaving every branch holding vectors from the model
  the person had just replaced (`3304256`).

And three paths that asked *the project* where the *tree* was meant: the ignore marks on rows, the
`.gitignore` the ignore button writes, and the file the changes list opens (`3304256`).

### 2b. The second wave — found by writing the walk-through

`BranchFeatureTest` was written *after* the table above, and it found six more in an afternoon. They
are listed separately because that is the honest shape of the finding: the first eleven were visible
by reading the code, and these were not.

| # | Defect | What a person would have seen | Fixed |
|---|---|---|---|
| 12 | `Background` remembered `project.name` — the tree's folder name, which is the *branch's* | A background command on a branch **never woke its agent**: the completion was filed under `feature` and the tick asked for `Shopping` | `e6392c5` |
| 13 | The strip's count and the changes window read main's repository | **"up to date"** beside a branch with five unsent files; a changes window that was empty and named files that do not exist there | `3592c58` |
| 14 | The ignore marks, the ignore button and the changes list all used the project's root | Marks on the wrong paths, an ignore line written into a document the person was not looking at, and a change opening main's copy of the file | `3304256` |
| 15 | The index rebuild cleared only main's index | Every branch kept vectors from the model the person had just replaced, so a search there compared two spaces | `3304256` |
| 16 | `deletedIn`/`restoreDeleted` used main's history | A branch offered back files it never had, and a restore committed to main | `dad38e6` |
| 17 | `dropBranch` deleted the ref only, and left the session | A fork point git still lists, and a conversation whose `put` could recreate the folder through `AgentLog.open` | `5dbb4ed`, `ed3d7c9` |
| 18 | `linkSystem` returned before writing the exclusion | A project whose link was made before its repository **reported a change nobody made, for ever**; a commit would have put a symlink in the repository | `f32d505` |
| 19 | `fileAt` tested only `activeBranch`, not whether that branch exists for *this* project | Open a branch, go up, open another project → **an empty project** | `92ca31b` |
| 20 | Nothing on screen said which branch was open | Editing `feature`'s copy under a header naming only the project | `92ca31b` |
| 21 | `Worktrees.rename` repointed one level of listing | A branch called `feature/x` kept pointers to paths that no longer exist | `f49492e` |
| 22 | Two presses began `githubFor(login) ?: return` | `startRelease`, `exportArtifact` and `fixSigning` did **nothing at all** with no account behind them | `1d8d2c5`, `be28ed4` |
| 23 | The `downloads` listing said it was the folder | Without a storage permission the store answers only this app's own rows — so a screenshot from another app was *absent* from a list that claimed to be complete | `8b4fe01` |

**The pattern worth naming:** every one of these is a place where a *name* was used as a key, or a
*project* was asked for something only a *tree* knows. The branch made both wrong without changing
either line, which is why they were invisible until a test walked the whole loop.

## 3. The system folder

The operator's ask: *"a folder at the app root and for each worktree a symlink to it … (similar to
downloads)."* Measured before it was written, because two of the four facts decide the design:

| question | measurement |
|---|---|
| Relative link from main's tree | `<account>/<project>/system` → `../system` |
| From a branch's tree | `../../../../system` from `.muffy/worktrees/<project>/<branch>` — and **one level deeper** for `feature/x`, so the depth is computed with `Path.relativize`, never counted |
| `.gitignore` form `system/` | **Matches nothing.** A symlink is not a directory. `system` and `/system` both work. |
| JGit honours `<common>/info/exclude`? | **Yes**, in main's tree **and** in a linked worktree: `untracked=[]`, `ignored=[system]`, `isClean=true`. Real git agrees. |
| Can Android make the link? | `minSdk = 26`; `Files.createSymbolicLink` is API 26, and the tests already used it |

`info/exclude` rather than `.gitignore`, because `.gitignore` is **tracked content**: a line there is
a change on every branch, appears in every clone's history for ever, and reaches people who never had
the folder.

## 4. What the audit found sound, and said so

Checked and left alone, so a later reader does not re-open them:

- `AgentService`'s notification id and Stop action were already keyed by session key, so two branches
  are two notifications — verified in the code, not assumed (`AgentService.kt:208`).
- `AgentSessions.read` sorts `main` first, which is why a branch's session never shadows it.
- `SemanticSearch` already refuses a path whose canonical form leaves the project, so the `system`
  link cannot leak the account's prompt into a project's index.
- `RepoLocks` is keyed by the **common** directory, so two worktrees of one repository really do
  exclude each other — the bug it exists to prevent does not return under branches.
- `SearchIndex` lives at `<tree>/agent/index.db`, so it is per worktree by construction, and `agent/`
  is already excluded.

## 5. What is still open, ranked by what it costs if it stays

1. ~~**§13e — Downloads as a folder, via SAF.**~~ **DONE, THE OTHER WAY (2026-11-09):** the three verbs
   and `PhoneDownloads` were **removed** rather than made true — 860 lines — because the `MediaStore` read
   they depend on only ever answers the app's own rows (see `PLAN-universal-2026-11-06` §13e.3).
   `DownloadsSink` — the app writing its own `trace.log` — stays, and so do `Export`/`Imports`.
   **What this leaves open instead:** a `search.db` under `agent/` from a build before the removal is
   orphaned, since the index that owned it is gone (`docs/REMOVED-2026-11-09-semantic-search-and-scanner.md`).
2. **`MediaStore` has never run on a device.** Still true, and it is now only the trace log. `save-picture`
   and the export/import buttons are the other untested-on-a-device paths.
3. **`push`/`pull` have never run against a real remote.** Every test uses `DiskGitHub`, which is real
   git on disk — the JGit code is exercised, the network and the key are not.
4. **§13b.6 token separation** needs a GitHub App registration; it cannot be built from inside.
5. **`main` cannot build a signed release** — `MUFFY_KEYSTORE_BASE64` is not a valid keystore. The
   operator's to fix, and it is why the publish button is proven only as far as a dispatch.
6. **`LiveHttpTest` is skipped unless `MUFFY_LIVE_HTTP=1`** — `http` has still never made a real
   request in CI.
7. **A branch's tree is made on the UI path.** The first tap on a branch does disk work inside a tap
   handler; on a large repository that is a visible pause with no loader.

## 6. How this was verified, since CI is the only compiler

- **`tools/check-*.py` before every commit**, and CI after: 1004 tests, green at `5dbb4ed`.
- **Every measurement in §3 was made before the code was written**, on this machine, with the app's
  own JGit 7.8.1 and with real `git` for the `.gitignore`-form question.
- **The test report is now readable.** The pipeline's sealing was removed earlier the same day
  (`802427b`), which is why §2 could be assembled from assertion messages instead of guessed at from
  line numbers — three of the eleven were diagnosed from a report in a single round.
