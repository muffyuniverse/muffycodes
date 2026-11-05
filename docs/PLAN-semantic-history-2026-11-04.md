# Plan — semantic search over history, and the housekeeping that never ran

Two questions produced this plan, and both changed the design:

> *"can you tell me how we index files, do we index only by path or also by @commithash? and why not the latter?"*
>
> *"git gc never runs? so we actually never prune anything, just remove commits?"*

The first found that the schema already carries a `version` column and **nothing ever writes it**. The second found
that `HistoryPrune.prune` moves a ref and never reclaims a byte, and that the function written to reclaim it
(`HistoryPrune.collect`) has **never been called** — in main or in a test.

The operator's decisions, in their words:

> *"no commit id caching - unreliability of commits on pruning is the reason why we use blob hashes in the first
> place. and pruning does need to remove rows that are unreachable via commits, and for blobs that havent yet been
> garbage collected by git … if we prune the storage rows then the situation when we get unresolvable blob hash
> shouldnt happen, right?"*
>
> *"can we run gc when app is started"*

---

## 1. What is actually in the code (verified, not assumed)

| claim | where | state |
|---|---|---|
| The index is keyed by **path and version**, both in one table | `SearchIndex.create`: `chunks(path, version, hash, start, end)`, `PRIMARY KEY(path, version, start)` | built, tested (15 tests) |
| `version` is `''` for the current file and a sha for a saved one | `SearchIndex.put(path, commit, …)`, `nearest(history)` = one clause `c.version = ''` | built |
| A hit already knows how to *show* a version | `Hit.shown()` → `path@<sha7>` | built |
| …but **only the current file is ever written** | the one call site is `index.put(rel, "", pieces)` in `SemanticSearch.catchUp` | **this is the gap** |
| A version's chunks are deduplicated by **content** | `vectors(hash, vector)`, `put` skips hashes already present and returns how many it embedded | built, tested |
| `search {history}` in semantic mode is **refused** | `AgentTools.searchTool`: "`history` is not indexed — only the current text of each file is" | truthful today; this plan makes it false |
| Pruning rebuilds the chain **with identical trees** | `HistoryPrune.prune`: `builder.setTreeId(commit.tree)`, then `updateRef` | built |
| Pruning deletes nothing | same function: old commits become unreachable and stay in `.git/objects` | **the leak** |
| `gc` exists and is never called | `HistoryPrune.collect` → `Git(repo).gc()`; zero callers in `main`, zero in tests | **dead code** |
| History length **bounds what git shows, not what the phone stores** | `SettingsStore.historyLength`, default 0 = no limit | in force |
| `restore` can bring back a **deleted** file | `GitService.restore`: `if (found == null) { target.deleteRecursively(); return }` | built |
| The history screen shows a **date and the typed note** | `HistoryScreen`: `Date(timestamp)`, `message` (only the sync note is not app-voice) | built |
| `nearest` is a full table scan, cosine in Kotlin | `SearchIndex.nearest` — no `sqlite-vec` on android-arm64 | known cost |
| Version lookups today are **per path** | `GitService.versionsOf` → `git.log().addPath(path)`, one walk per path | the cost to replace |
| A reset to a force-pushed remote strands commits **with no prune** | `ProjectGit.replaceWithRemote` → `GitService.resetToRemote` (fetch + hard reset), 3 UI callers | **second cause** |

---

## 2. The decision: the row key is the **blob**, not the commit

A version row's `version` column holds the **blob id** (content sha) of the file at that version. `''` still means
the current file.

Why not the commit id — three reasons, in order of severity:

1. **Prune rewrites history by construction.** Every surviving commit gets a *new* sha on every save that prunes.
   Commit-keyed rows would dangle in bulk, repeatedly, not once.
2. **Our prune is not the only thing that strands commits.** `replaceWithRemote` hard-resets to `origin/<branch>`;
   a force-pushed, rebased or recreated remote invalidates local commit ids with **no prune to hook**. This app's
   projects are largely cloned from GitHub, so it is not hypothetical.
3. **A commit id is an event, a blob id is content** — and content is what an index is about. The dedupe that
   makes history affordable (`vectors` keyed by text hash) is the same idea one level up: identical content saved
   twice is **one** row set, not two.

What the key choice costs, stated plainly: **"which save" is no longer stored.** It becomes a question asked at
read time (§3) — and that is exactly the trade the operator named: commits are not trustworthy enough to store,
content is.

### Why not "rewrite the rows on prune" instead

It is a local change, and it was the other candidate. Rejected because:

- the mapping exists only inside `prune`, which runs **inside `RepoLocks.hold`** via `commitOutcome` — the git layer
  would be writing a second SQLite database while holding the repo lock, and a crash between the two leaves rows
  pointing at ids that no longer exist;
- it runs **after every save** that prunes, so the sweep becomes part of the save path;
- it covers **only our own prune**, so cause (2) above — the force-push — is untouched.

Blob keys delete the class of problem for one column's worth of change.

---

## 3. Reading a hit: blob → commit, at read time, never cached

**No caching.** A cached commit id is the same unreliability we just removed, stored somewhere else; and verifying a
cached id costs the same tree lookup as resolving it. The row's blob *is* its content, so the only thing a commit
adds is "when", which is precisely the fact we refuse to store.

The answer to a version hit is **`path@<commit sha7>`** — the same id space as today, so `read` and `restore` are
untouched and a model never has to know two kinds of id.

Resolution rule: **the newest commit whose tree maps this path to this blob.** Matching is on the pair
`(path, blob)`, because a blob can appear at another path in another commit — content addressing does not know
about names.

### One walk, not one per hit

Naively: for each hit, walk commits newest-first and `TreeWalk.forPath` until it matches. That is
`hits × commits` tree walks. Instead:

1. collect the **distinct** `(path, blob)` pairs from the answer (usually far fewer than 30);
2. walk history once, newest-first, taking each commit's **diff against its parent** — which is exactly the set of
   `(path, blob)` pairs that commit introduced;
3. resolve any pending pair the diff contains, and drop it from the pending set;
4. stop when the pending set is empty, history ends, or the `Deadline` expires.

That is one pass over changed files across history — the same walk the indexer needs (§4), so it is written once as
`GitService.walkChanges(repo, deadline) { commit, changes -> }` and used by both.

### When no commit reaches the blob

Two cases, and they are different:

| case | what happens |
|---|---|
| **stranded, content still on disk** (prune or reset happened, `gc` has not run) | the blob loads but no commit names it. **Drop the row and say so** — the answer gains a note: *"N indexed version(s) are no longer in this project's history and were dropped"* |
| **gone** (`gc` has run) | the row is already swept by §5 before that gc. Belt and braces: if a load fails, drop the row the same way |

**So a blob id never reaches the model.** The fallback is not "answer with a blob sha", it is "the row was already
dead". That keeps one id space and one rule.

---

## 4. Writing version rows

Today `catchUp` walks the working tree and writes `version = ''`. It gains a history pass:

- `walkChanges` over the newest commits, bounded (`VERSIONS_PER_PATH = VERSIONS_CONSIDERED` = 40, the constant the
  regex-mode history already uses), deadline-checked per commit;
- for each `(path, blob)` the *current* row already covers, skip (the working tree is the newest version);
- otherwise read the blob, `Chunker.split`, `index.put(path, blob, chunks)` — which **embeds only text it has not
  seen**, so a version that only edits one paragraph pays for ~2 chunks (§ evidence: `SearchIndexTest` asserts
  exactly that);
- record `note(path, mtime, INDEXED)` only for current-file rows; version rows are not in `files`.

Cost, honestly: **tree reads are the expense, not embeddings.** A thousand-save project walks a thousand trees per
pass; the vectors are deduplicated. The bound is `paths × 40` rows considered, plus the tree reads to get them.

**No watermark.** A "last indexed commit" marker is a commit id — the thing we decided not to trust. Re-walking the
newest N commits each pass is idempotent (hash dedupe makes it free in requests) and survives any rewrite.

This makes `search {history}` + `mode: "semantic"` answerable, so its refusal is deleted and a test pins the new
answer instead.

---

## 5. Housekeeping: sweep, then `gc`, at app start

Two facts force this order and this moment:

- **`gc` deletes the very objects a sweep needs to identify dead rows**, so the sweep must run first;
- **`gc` is proportional to the whole repository**, which is why prune's own comment forbids it on the save path.

### When it runs

At app start, and only when something asked for it. Both stranding causes leave a marker:

- `ProjectGit.prune` writes `agent/maintenance-due` with the reason (`"prune"`);
- `ProjectGit.replaceWithRemote` writes it too (`"pull"`) — the force-push case, which no prune covers.

At start, for each project with the marker: long operation, so

| guard | why |
|---|---|
| **off the main thread** (daemon) | `gc` is seconds to minutes |
| **`RepoLocks.tryHold`**, not `hold` | the point is not to block; it is the "future caller that must not block" the lock was written for. A project mid-commit is skipped and retried on the next start |
| **skip projects whose agent is `running`** | `tryHold` is a point-in-time check, and a `gc` holding the lock for a minute would stall an agent's next commit behind it. A switched-on agent means "not now" |
| **one project at a time** | the same rule the runner follows: serial is predictable |
| **sweep, then collect, then delete the marker** | so a crash mid-way re-runs both, and both are idempotent |

### The sweep

Walk every commit reachable from `HEAD`; collect the set of blobs in their trees (the prune guarantees identical
trees, so this is the surviving content exactly, and after a reset it is whatever `HEAD` now reaches). Then:

```sql
DELETE FROM chunks WHERE version != '' AND version NOT IN <reachable>
```

followed by the orphan-vector cleanup `forget` already performs. Current-file rows (`version = ''`) are **not**
touched: a prune says nothing about the working tree.

Bounded by the same `Deadline` discipline as everything else, and its result goes where the index queue's does —
an `appNote("maintenance", …)` row and, when it reclaimed anything, the notification.

---

## 6. What this deliberately does not do

- **No second id space.** Blob ids are an implementation detail of the table; answers carry commit ids.
- **No commit-id caching**, per the operator, for the reason above.
- **No deletion rows.** A version where the file did not exist has no blob — absence is a property of a tree, not of
  an object. "Bring back a deleted file" stays the history screen's job (`restore`'s `found == null` branch).
- **No `gc` on the save path**, and none triggered by a search.
- **No reindex on model change here** — that is already built (a warning, then every index emptied and queued).
- **No change to `read`/`restore`.** They keep taking commit shas, because that is what answers now carry.

---

## 7. The work, in order — **all seven delivered**

Each item lands with the test that fails if it breaks. **What shipped, and where:**

| # | item | landed | its test |
|---|---|---|---|
| 1 | `GitService.walkChanges` | `818682c` | `ProjectGitTest`: newest-first, the root commit lists everything, a deletion is absent |
| 2 | `SearchIndex` version writes + `sweep` | `818682c` | `SearchIndexTest`: a version survives a rewrite of the current file, a sweep keeps the current row and drops the rest |
| 3 | index the history, bounded and deduped | `818682c` | `SemanticSearchTest`: a deleted line is found, one edited paragraph does not re-embed the file, a second pass embeds nothing |
| 4 | resolve blob → commit, drop what nothing reaches | `818682c` | `ProjectGitTest`: the newest commit wins, an unreachable blob resolves to nothing. `SemanticSearchTest`: a dead row is left out and counted |
| 5 | un-refuse `history` + `semantic` | `818682c` | `SearchTest`: the refusal is gone and the missing-setting refusal is what remains |
| 6 | the marker, and `collect` wired at app start | `818682c`, `1b1a285`, `5c862f0` | `MaintenanceTest` (9), `IndexQueueTest`: a pull asks for a pass and leaves a marker |
| 7 | docs and the ledger | this file, `REPORTS-LEDGER.md` row 24 | — |

**Three things the build corrected in the plan, kept here because they were wrong and the reasons are not
obvious:**

1. **`bytesAt` cannot read a blob.** It takes a *revision* and walks a tree to find the file in it, and a blob id
   is not a tree — so every saved version read as `null` and was skipped without a word. `bytesOf` opens the
   object by its own id (`51eddbd`).
2. **`Maintenance` asked `projectNames()`, which means "folders that are repositories".** A folder with a marker
   and no `.git` was therefore never looked at and its marker would sit there for ever (`51eddbd`).
3. **The housekeeping pass is not a thing a test should start.** It was spawned by every `AppViewModel`
   construction, so a shard built hundreds of threads that walked temp folders and ran `gc` — correct once per
   launch, wrong hundreds of times in a shard. It is a constructor seam now (`fb53ddf`).

**And one defect the plan created and the suite caught:** the index queue writes a bookkeeping row into the
project's own log, and `AgentChat.bursts` starts a burst at whatever row is first — so that row became a turn
nobody had, and the chat box counted one more than there was (`d624fd6`).

### What is still open, and it is not a gap in this plan

- **`search {history}` indexes the newest `VERSIONS_PER_PATH` (40) commits of the whole project, not 40 per
  file.** That is the bound the text-mode history already uses, so the two modes agree; a file untouched for
  longer than the window has no indexed version, and the answer says what it searched rather than pretending.
- **The history half runs on every pass**, including the ones a commit and a clone ask for, not only when a
  search asks for versions. It is idempotent and cheap after the first pass (hash dedupe), but the first pass
  over a long history is the slow one — bounded by the queue's 15-minute stop, which says what is left.

1. **`GitService.walkChanges(repo, deadline) { commit, changes -> }`** — one newest-first walk yielding
   `(CommitInfo, List<Change(path, blobId, kind)>)` via `DiffFormatter` against the parent. Tested against a
   fixture repo: the first commit lists everything, a later one lists only what it changed.
2. **`SearchIndex` version writes and a `reachable` sweep.** `put` keeps its shape (`version` is the key);
   add `sweep(reachable: Set<String>)` returning how many rows and vectors went. Tests: a version row survives a
   re-index of the current file; `sweep` removes unreachable version rows and leaves current rows alone; orphan
   vectors are collected.
3. **Index the history** in `SemanticSearch.index()`/`catchUp` — bounded, deduped, deadline-checked. Tests: a line
   that was deleted is found by `mode: "semantic"` with `history: true`; a version that only edits a paragraph
   embeds fewer chunks than it has; a second pass over an unchanged history embeds nothing.
4. **Resolve blob → commit** in `SemanticSearch.search` using the same walk, and **drop unresolvable rows** with
   the note. Tests: a hit answers `path@sha7` and `read` accepts it; after a prune the surviving version still
   resolves; a row stranded by a reset is dropped and counted in the note.
5. **Un-refuse `history` + `semantic`** in `AgentTools`, replacing the refusal with the answer, and say in the
   tool spec that it searches saved versions. Tests: the refusal test becomes an answer test; the spec text is
   asserted to mention versions.
6. **The marker, and `HistoryPrune.collect` wired at app start.** `prune` and `replaceWithRemote` write
   `agent/maintenance-due`; a `Maintenance` object runs sweep → collect per project under `tryHold`, skipping
   running agents, and deletes the marker. Tests: the marker appears after a prune and after a pull; maintenance
   does nothing without a marker; a held lock skips; a running agent skips; the marker survives a failure so the
   next start retries.
7. **Docs and the ledger** — this file, and a note in `docs/PLAN-tool-api-2026-11-04.md` that step 5's
   "semantic" half is finished here.

---

## 8. Where the cost lands

| act | when | cost |
|---|---|---|
| a version indexed | a commit, a pull, a clone, a search — the four existing triggers | tree reads; embeddings only for text never seen |
| resolution | every semantic history answer | one diff walk, capped by the `Deadline` |
| the sweep | app start, only with a marker | one history walk |
| `gc` | app start, only with a marker | proportional to the repository; the reason it is not on the save path |

`nearest` remains a full scan. Version rows multiply it, so the retention bound (`40` versions per path) is what
keeps that honest — and it is a constant, not an accident.

---

## 9. Risks

- **A long history makes the first index slow.** The queue's pass is bounded at 15 minutes and says what is left, so
  this is a slow first search, not a failure — but it is a real first-run cost on a repository with a thousand
  saves.
- **`gc` at start competes with the agent.** Held off by both `tryHold` and the running-agent check; if both fail,
  the marker stays and the next start retries.
- **A table full of versions inflates every semantic answer's scan.** The bound is `paths × 40`; if a measurement
  says otherwise, the answer is a `version` index or a two-table split, not a smaller bound.
- **Two rows can describe the same text at the same path** (the same blob saved twice) — by design: one vector, two
  rows. Anything that counts "versions" must count rows, not vectors.

---

## 10. The operator's answers (2026-11-04, before implementation)

1. **Byte size beside `mtime`** — yes. The `files` row stores `size` as well, and a file counts as unchanged only
   when both match. It closes the half of the mtime gap that a restore or a `cp -p` opens, for one column and no
   extra read.
2. **No migration, and no backlog pass.** The app is in development with no users, so nothing on any phone needs
   reclaiming: `gc` runs only where a marker says something was stranded. A project that was pruned before this
   shipped keeps its dead trees, and that is acceptable because there is no such phone.

Both are now part of the work below, not questions.
