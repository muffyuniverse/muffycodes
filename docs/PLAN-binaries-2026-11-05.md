# Binaries: one version, forever — **SUPERSEDED**

> **This plan was not built.** It designed a history rewrite to drop old binary blobs. The operator then
> chose LFS, which removes the need for the rewrite's *purpose* while keeping its mechanism for the store:
> see `docs/PLAN-lfs-2026-11-05.md`. Kept because §1 (why a smudge destroys work under a full-replacement
> pull) and §3 (the two mistakes in the first rewrite attempt) are still the reasons the final design has
> the shape it has.

**What this is.** Non-text files in a project — a drawing, a photo, a dropped `.pdf` — are **tracked** by
git, pushed and pulled like anything else, and **kept at exactly one version**: the newest. Text keeps its
history untouched.

**Where it came from.** The operator, 2026-11-05:

> *"make sure that we dont version non text files and git keeps only their latest version"*

and then, correcting the shape twice:

> *"we do need to keep binary files both locally and remotely, but just their latest versions"*
>
> *"when you repull a remote project local project is removed and remote one is cloned cleanly - no
> resolution, full replacement"*
>
> *"b, when binary is changed - and you are right that most of binaries are never changed"*

---

## 1. Why the obvious design is wrong

The obvious design is a **smudge**: after staging, put the *old* blob id back into the index and commit
that, so the tree stays small while the new file sits on disk. It is the standard way to do this, and it
is wrong in this app, for a reason that only shows up when you follow the bytes.

I built it and tested it (`/tmp/sem3`):

1. The person draws on a page and saves. Disk holds `DRAWING-2`; the smudged tree still says `DRAWING-1`.
2. They press Sync. **The push sends the tree**, so GitHub receives `DRAWING-1`. `DRAWING-2` never leaves
   the phone.
3. They pull. This app's pull is a full replacement — the folder is deleted and re-cloned — so
   `DRAWING-2` is **gone**, having never been anywhere else.

> A tree that disagrees with the working copy is safe only where a pull merges. This app's pull replaces.

There is a second defect, found the same way: after a smudge the index disagrees with the working tree, so
git reports the file as ` M` **forever**. This app commits with `git add .` first, so the next save
re-stages the real bytes and the freeze silently lapses — I watched commit 3 come back unfrozen.

**So the tree must always tell the truth, and the *history* is rewritten afterwards.**

---

## 2. The design

```
save:
  1. commit as today                      the new blob is in the tree — pushable, pullable, honest
  2. if a non-text path changed:
       rewrite every commit holding that path so its tree points at the NEWEST blob
  3. mark the project for collection      old blobs are now unreachable; Maintenance gc's them
```

**Why this satisfies each requirement:**

| requirement | why it holds |
|---|---|
| tracked, locally and remotely | the newest blob is in `HEAD`'s tree, so it is pushed and pulled like any file |
| latest version only | every older commit is rewritten to point at the newest blob, so the older blobs are referenced by nothing |
| not pruned away | the newest blob is in `HEAD`'s tree, which no prune removes; the sweep keeps every blob a surviving commit reaches |
| deletion is complete | deleting stages a `DELETE`, the path leaves the tree, no commit references the blob, `gc` collects it |
| text history unchanged | each rebuilt commit is the original tree with **one entry** replaced |

**And why "only when a binary changed":** a project whose binaries never change — most projects, and most
projects' binaries — never enters step 2 at all. The caller asks only when the commit's own diff contains a
non-text path.

---

## 3. The algorithm, correctly

The version I started to write was wrong twice, and both mistakes are the kind that look finished:

1. **It never moved the branch ref.** The rebuilt commits were written as unreachable objects; `HEAD` still
   pointed at the old chain, and `gc` would have collected the *rewrite* rather than the originals.
2. **It pointed rebuilt commits at their original parents.** Even with the ref moved, the old blobs would
   have stayed reachable through the old parents, so the collection would free nothing.

The correct shape, which is `HistoryPrune.prune`'s shape with a different transformation:

```
newest = blob of `path` in HEAD's tree          (null → nothing to do)

touching = commits reachable from the ref that hold `path` with a blob != newest
           oldest first

if touching is empty → 0                        (the common case: binary never changed)

inserter = repo.newObjectInserter()
parent   = the original parent of the OLDEST touched commit    ← not "nothing"

for commit in touching (oldest first):
    tree = commit.tree with `path` → newest     (DirCache in core, one PathEdit)
    rebuilt = commit with tree, same message/author/committer, parent
    parent  = inserter.insert(rebuilt)

inserter.flush()
move the branch ref to `parent`, force
```

**The `parent` seed is the subtlety.** A rebuilt commit must keep the *original* parent of the oldest
commit it replaces, or the rewrite would truncate the history at the first changed binary. Every commit
after it is then chained onto the rebuilt predecessor, which is what makes the old chain unreachable.

**Cost.** One tree build per *touching* commit — not per commit. `DirCache.newInCore` + a recursive
`TreeWalk` per rewritten commit is the same order of work `prune` already does on every save that prunes.

---

## 4. The work, in order

Each lands with the test that fails if it breaks.

1. **`HistoryPrune.keepLatest(repo, path)`** — §3. Returns commits rewritten, 0 when nothing changed.
2. **`ProjectGit.commitOutcome`** — after `commitEverything`, if the commit wrote **and** its diff held a
   non-text path, call `keepLatest` once per such path, then `Maintenance.mark` ("a picture changed").
   The diff is the one `commitEverything` already has, so nothing is walked twice.
3. **`HistoryScreen`** — for a non-text path, drop *"It can still be put back."* Restoring would hand back
   the frozen version, and the screen would be offering something that reverts the person's work.
4. **Tests** — §6.
5. **Docs** — this file, and `docs/PLAN-semantic-history-2026-11-05` cross-referenced for the index side.

---

## 5. What this deliberately does not do

- **No smudge, ever.** §1 is why, and it is the whole reason for the post-commit rewrite.
- **No change to the pull.** Full replacement stays exactly as it is; the tree is truthful, so it works.
- **No change to `historyLength`.** Binaries are outside the count — "keep all versions" was never a
  promise about PNGs, and making the storage behaviour depend on an unrelated setting would be a second
  rule for a person to hold.
- **No indexing of binaries.** Already shipped in `19e987e`: the history walk now filters on
  `ws.kindOfPath` rather than guessing with `looksBinary`, which a PNG survives.
- **No change to what counts as a binary.** `EntryKind.TEXT` is the line; `IMAGE` and `UNKNOWN` are both
  frozen. A `.zip` or `.pdf` someone dropped in is treated the same way, which is the same trade.

---

## 6. The tests

| test | fails if |
|---|---|
| a binary saved twice leaves one blob in the repository | the rewrite does not run, or runs before the ref moves |
| **the newest bytes are what `HEAD` holds** | anyone reintroduces a smudge — this is the test that would have caught it |
| a text file's history is untouched | the rewrite replaces the whole tree instead of one entry |
| a binary that never changed costs no rewrite | `keepLatest` is called on every save |
| deleting a binary leaves no blob after collection | the delete does not un-reference the frozen blob |
| a prune keeps the frozen blob | the rewrite leaves it reachable only from a commit the prune drops |
| a pull restores the newest bytes | the tree was written from the disk rather than the commit |
| a rewrite that cannot run leaves the commit as it was | a failure here is silent data loss, not a slow save |

---

## 7. Open questions — three, and I am not deciding them for you

**7.1 Does a rewrite touch `refs/remotes/origin/*`?** After a rewrite, the phone's `HEAD` has different
commit ids from the remote's. The next sync force-pushes, so the remote ends up matching — but until then,
"what is unsent" (`unsentPaths`, a diff against `origin/<branch>`) reports **every file in the project** as
changed, because every commit id moved. Two options: force-push immediately after a rewrite, or exclude
`origin/*` from that comparison while a rewrite is pending. I lean to the first — it is one push and it
makes the phone and GitHub agree again — but it means a save can trigger a network call, which nothing on
the save path does today.

**7.2 Does the rewrite respect `historyLength`?** A project with 1 000 commits and one changed binary means
1 000 tree rebuilds on a save. With a limit set, pruning first bounds that. With "keep all", it does not.
I would **rewrite only the commits that a prune would keep** — i.e. cap the walk at `historyLength` when it
is set, and at all commits otherwise — but that leaves the oldest commits still pointing at old blobs, so
the storage win is partial. The alternative is unbounded work on a long history. Which is worse: a slow
save on a 1 000-commit project, or old binary versions surviving in the oldest commits?

**7.3 What does the person see?** The changes screen will not list a changed binary as "modified" once the
rewrite runs (the tree matches the commit). So "what am I about to send" would not mention that a page was
redrawn, even though the file is on disk and will be pushed. I would show it with a marker — *"latest
only"* — rather than hide it, but that is a new row state on a screen that currently has three.

---

## 8. What I would answer if asked

- **7.1** — force-push after the rewrite, because a stale `origin/*` makes the change list lie about every
  file, and that is a worse bug than a save that sometimes reaches the network. It also matches what the
  operator wants from a pull: the phone and GitHub hold the same thing.
- **7.2** — cap the walk at `historyLength` when set. A save that takes ten seconds on a long history is a
  save a person notices; old versions of a PNG in the oldest commits are invisible and harmless. With the
  limit unset, rewrite everything once — the cost is paid once and never again, because the next save finds
  nothing to do.
- **7.3** — the marker. A file that is on disk and will be sent is a change; a screen that does not say so
  is the screen being wrong.
