# Agent owns the push; the operator gets a tracked/untracked switch

**Date:** 2026-11-05 · **Status:** plan, awaiting go-ahead · **Supersedes:** the unsync half of
`PLAN-binaries-2026-11-05.md` and the row described in `PLAN-lfs-client-2026-11-05.md` §6.

---

## 0. Why

The operator, 2026-11-05:

> "we have sync button inside sync row in every project that operators can use to see changes and push
> to main. I think that we can remove this functionality and transfer it to agent so that it can
> determine when to push and to which branches (but still within their project). And leave to
> operator only the lock and sync buttons that work per file (they are already implemented). I want to
> change sync row icon to become gitignore icon, so that it just adds or removes a file or folder to
> gitignore … And I want for all files created by operator to be locked and gitignored on creation."

Then, settling the three open questions:

> "1. ignored files are not changes. 2. a. 3. locked doesnt mean blind, it means readonly."

So: **the agent decides when to push and to which branch; the operator has no push button.** The
operator's per-file controls become a **lock** and a **classic gitignore toggle**. Operator-created
files are locked and ignored from birth; agent-created files are tracked and unlocked.

---

## 1. The measurement that shapes this

**Two mechanisms currently keep a file off GitHub, and one of them is the reason `sync` force-pushes.**

| | Mechanism | What it does |
|---|---|---|
| **Unsync** | `Workspace.unsynced()` → `unsentFrom` → `ProjectGit.sync` builds a **derived tree** without those paths, then `pushWithout(omit)` → `pushDerived` → `setForce(true)` | the file stays committed locally but is absent from the pushed tree |
| **Gitignore** | `.gitignore` line + `git rm --cached` | the file leaves the index and is not committed at all |

`pushDerived` (`GitService.kt:1648`) pushes `commit:refs/heads/main` for a commit that is on **no local
branch**. That is why it must force: GitHub's `main` and the phone's `main` are different histories by
construction.

**This is the shape the operator is rejecting**, and rejecting it removes the force-push from the
operator's path — which is exactly what the agent's push needs, because an agent that force-pushes can
discard a commit from the phone.

**So the two halves of this plan are one change.** Classic gitignore is not a smaller feature than
unsync; it is the same feature with one concept instead of two, and it deletes:

- `Workspace.unsynced()`, `unsyncPaths`, `unsync(file, kept)`, `Unsync` enum, the `UNSYNCED_FILE` flag
- `Workspace.unsentFrom(project)`
- `ProjectGit.sync`'s `omit` argument, `GitService.pushWithout`, `GitService.pushDerived`
- `AppViewModel.changesOf`'s dependence on unsent paths
- the `Sync` button and `onRowSync` wiring

**Kept and reused:** `AppViewModel.ignoreInGit` already writes the `.gitignore` line, already handles
folders with a trailing slash, already untracks with `git rm --cached` so an already-committed file
stops being sent, and already commits the change. It is most of the new icon already.

---

## 2. Decisions, settled

| # | Question | Answer |
|---|---|---|
| 1 | Are ignored files counted as "changes"? | **No.** They are not changes. The row may say how many are ignored, as a different fact with a different word. |
| 2 | Does the agent push on its own, or when it decides? | **(a) it decides** — a `push` tool it chooses to call. |
| 3 | Are operator files locked *and* ignored? | **Yes**, but **locked means read-only, not hidden.** The agent can still `read` and `search` them; it cannot `write`, `delete` or `move` them. |
| 4 | Branches | **`main` everywhere.** Each project is its own repository root (`repoDir(project) = projectDir(project)`, projects are siblings one level under the account), so one project = one repo = one `main`. Branches would let one writer diverge from itself. |

### The consequence of (2) that must be stated

With no operator push button and an agent that pushes when it decides, **a person's own edit can sit
unsent indefinitely.** The row's count is the only signal. That is the operator's explicit choice; the
plan keeps the count honest (it counts unsent *tracked* work) and adds no button.

---

## 3. What the agent gets

Three new tools. All confined to the project by the existing `projectDir` resolution that every tool
already uses.

### `state` — read, no arguments

Answers: how many tracked paths are unsent, how many commits ahead/behind `origin/main`, and whether a
merge is in progress. This is what "determine when to push" is decided from, so it must not require a
network call to answer the local half.

### `pull` — fetch, then merge

`git fetch` then merge `origin/main` into `main`. **Merge, never rebase** — rebase rewrites commits,
which is the standing rule this project does not break.

- Clean merge → answers the paths that came in.
- Conflicts → answers the **conflicted paths**, and stops. The agent resolves them with `read`
  (conflict markers are visible) and `write`. No new tool is needed for resolution: the markers are
  text and `write` edits text.
- Diverged with no common ancestor → refuses and says so rather than inventing a strategy.

### `push` — fast-forward only

**Never forced.** A non-fast-forward answers as a **conflict, not a failure**, naming the paths, so the
agent's next move is `pull` and not a retry.

Order of operations is the one LFS already established and must not change:
1. `LfsSync.push` — the picture bytes, **before** the ref (GitHub refuses a tree naming an object it
   does not hold, `GH008`).
2. `GitService.push(force = false)`.
3. `LfsSync.missing` — a pointer left in the worktree is reported, never silent.

**Refused outright:**
- a push when the working tree has uncommitted changes (commit first; a push that carries half a
  change is the failure this app keeps fixing)
- a push to any branch but `main` — there is one branch and the tool does not take a parameter for it.
  The operator's "to which branches" is answered by *the project's*, which is `main`.

---

## 4. What the operator gets

### The row

```
muffyuniverse · shopping          3 changes        🔒   🚫
```

- **`Sync` button: deleted.** The count stays; it is information, not an action.
- **The gitignore icon** replaces the sync icon, **per file and per folder**, and toggles
  **tracked ↔ ignored**. Classic semantics: an ignored path is in `.gitignore` **and** out of the
  index. Nothing is derived, nothing is force-pushed.
- **Identical for text and binary.** `ignoreInGit` never looks at the entry kind, and LFS makes that
  worth a test rather than an assumption: an ignored picture must not be uploaded either. (It will not
  be — `LfsSync.push` walks pointers *in the tree*, and an ignored file has no tree entry.)

### Creation defaults

| Created by | Locked | Ignored | Tracked |
|---|---|---|---|
| **operator** | yes | yes | no |
| **agent** | no | no | yes |

**How the code knows who created it:** a typed parameter, not a boolean and not a time window.
`write`/`delete`/`move` gain `createdBy: CreatedBy` (`AGENT` or `OPERATOR`), set by the caller
(`AgentTools` passes `AGENT`; the editor and the browser pass `OPERATOR`). A boolean would be a flag
that silently mis-files a file; a time window would mis-file a person's save that lands during a run.

**What must be said in the UI:** an operator-created file is **not on GitHub**. This app has spent
considerable effort making "saved" mean "synced"; this change deliberately breaks that for new
operator files. The row must say it — the gitignore icon is the answer to "why is this not on GitHub",
and a file that is ignored must not be described as up to date.

---

## 5. Steps

| # | Step | Ends with |
|---|---|---|
| 1 | `ignoreInGit` gains **un-ignore** (remove the line, `git add` the path back), with the same folder rule and the same commit | the toggle works both ways |
| 2 | Test: an ignored **picture** is not uploaded and is not in the tree — text and binary behave identically | the LFS interaction is proven, not assumed |
| 3 | Delete the unsync machinery: `unsynced()`, `unsyncPaths`, `unsync()`, `Unsync`, `unsentFrom`, `omit`/`pushWithout`/`pushDerived` | one mechanism, no derived tree, no operator force-push |
| 4 | `changesOf` counts unsent **tracked** paths only; ignored paths are reported separately | "3 changes" never counts something that will not be sent |
| 5 | `CreatedBy` threaded through `write`/`delete`/`move`; operator creation locks + ignores, agent creation tracks | the two defaults |
| 6 | `state` tool | the agent can decide *when* |
| 7 | `pull` tool — fetch, merge, name conflicts | the agent can resolve a divergence |
| 8 | `push` tool — LFS first, fast-forward only, rejection named as a conflict | the agent owns delivery |
| 9 | The row: delete `Sync` and `onRowSync`; the icon becomes the gitignore toggle | no operator push |
| 10 | `AgentPrompt.kt` — "the person pushes" is now false; say what is true | the prompt does not lie to the model |
| 11 | `tools/test-map.txt` rows for the three tools | the map check passes |

**Step 10 is not cosmetic.** Line 135 of the prompt currently reads *"Nothing you do is pushed to
GitHub; the person pushes."* Leaving it after this change would tell every agent that it cannot do the
thing the whole plan is about.

---

## 6. Acceptance tests

Each fails if the behaviour breaks.

1. **Ignoring a tracked file removes it from the tree** — commit, ignore, sync, assert the path is
   absent from the pushed tree and the file is still on disk.
2. **Ignoring a picture uploads nothing** — the same, with LFS registered, asserting no object is
   added to the store and no upload is attempted.
3. **Un-ignoring restores it** — the path returns to the index and the next push carries it.
4. **An ignored file is not a change** — `changesOf` does not count it.
5. **Operator-created is locked and ignored; agent-created is neither** — one test, both callers.
6. **Locked is readable** — the agent can `read` and `search` a locked file and is refused `write`,
   `delete`, `move`. (Partly exists; extend it to say *readable*.)
7. **`push` refuses a non-fast-forward as a conflict** — names the paths, does not force.
8. **`pull` reports conflicts and merges cleanly otherwise.**
9. **`push` sends pictures before the ref** — the existing LFS order test, now reached through the tool.
10. **No `Sync` button in the row**, and the count never includes ignored paths.

---

## 7. Non-goals

- **Branches.** `main` only. No branch tool, no branch parameter.
- **A force-push path for the agent.** The operator's own force-push disappears with `pushDerived`; if
  a repair is ever needed it is a separate, deliberate act.
- **Changing `gitignore` semantics.** No negation patterns, no per-file rules beyond one path per line.
- **A person's push button anywhere.** Explicitly removed.

---

## 8. Risks

1. **"Saved" stops meaning "on GitHub" for new operator files.** The largest behavioural change here,
   and the one most likely to surprise. Mitigation: the row must say it, and the prompt must not claim
   otherwise.
2. **A person's edit can sit unsent indefinitely** while the agent decides. Accepted by the operator;
   the count is the signal.
3. **Deleting the derived-tree push is a large deletion.** `pushWithout`/`pushDerived` are reached from
   `sync` only, but `ProjectGitTest` covers the omit path and must be rewritten, not deleted, so the
   *replacement* is tested.
4. **Conflict resolution by `write`** means an agent could resolve a conflict wrongly and push it.
   Nothing prevents that; the merge commit and the push are both visible in history, which is the
   mitigation the app already relies on.
