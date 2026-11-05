# The LFS client we have to write ourselves

**Status:** planned, not started. Supersedes the "LFS is removed" conclusion of
[PLAN-lfs-2026-11-05.md](PLAN-lfs-2026-11-05.md) §0, for the reasons measured there and re-measured here.

---

## 0. Why this exists

The operator's requirement: **a picture must live on GitHub with the rest of the project**, and **history
must not be rewritten**. Those two together leave exactly one design — git-LFS — and JGit cannot drive it
against a private repository. So the client is ours.

### The measurement that forced it

Run against a real GitHub repository holding a real LFS object (`b3ed289d…`, 30 bytes, uploaded through
the batch API and verified).

**Public repository — the download works:**

```
before checkout: size=127      ← the pointer
after checkout:  size=30       ← the bytes
still a pointer? false
lfs objects downloaded: 1
```

**Private repository — the same clone silently keeps the pointer:**

```
clone with a CredentialsProvider, then reset --hard:
before checkout: size=127
after checkout:  size=127      ← still the pointer
still a pointer? true
lfs objects downloaded: 0
```

No exception, no warning, exit status success. A person gets a 127-byte text file where their drawing
should be.

### Why it happens

`LfsConnectionFactory` has one authentication path and it is SSH (`git-lfs-authenticate`). Over HTTPS it
sets `Accept`, `Content-Type` and `Accept-Encoding` — and nothing else. `Protocol.HDR_AUTH` is the constant
`"Authorization"`, and its only producer is `HDR_AUTH_SSH_PREFIX = "Ssh: "`. The `CredentialsProvider`
given to `clone` authenticates git's transport; the LFS connection never sees it.

GitHub's side, measured by curl:

```
POST .../info/lfs/objects/batch   anonymous   → 401 "Requires authentication"
POST .../info/lfs/objects/batch   -u token    → 200
```

Upstream: [eclipse-jgit/jgit#11](https://github.com/eclipse-jgit/jgit/issues/11) is **open** with no fix
version, reporting the same `rc=401`. It cross-references Eclipse bug 575780, *"Builtin LFS doesn't work on
GitHub with private repositories if use https authentication"*.

### The rule that follows

**A silent failure is worse than a missing feature.** Every step of this design must be able to say that it
failed. A pointer left in the working tree is an error the app reports, never a picture that will not open.

---

## 1. What is already built and reusable

Nothing here is thrown away; the LFS work was removed from the *wiring*, not from the design.

| Piece | State | Reuse |
|---|---|---|
| `LfsStore.kt` | deleted at `d24622d`, recoverable | **Restore whole.** `reachable()`, `sweep()`, `sizeOf()`, `POINTER_LIMIT` are correct and tested. |
| `GitService.registerLfs()` | removed at `d24622d` | **Restore.** Registers the filter; harmless on a phone that cannot. |
| `GitService.enableLfs(repo)` | removed at `d24622d` | **Restore**, but it is not sufficient — see §2. |
| `GitService.ensureLfsAttributes(dir)` | removed at `d24622d` | **Restore** (fixed form at `f4981f7`). This is what makes a clone elsewhere know which files are pointers. |
| `LFS_ATTRIBUTE` | removed at `d24622d` | **Restore.** Built from `Workspace.IMAGE_EXTENSIONS`, one line per format, `-text`. |
| `ProjectGit` init/clone wiring | removed at `d24622d` | **Restore**, then extend with the download step. |
| JGit `6.10.1` + `gson` dependencies | removed at `d24622d` | **Restore.** `Protocol.gson()` is usable, and `HttpConnection.setRequestProperty` is public — which is the hook we need. |
| OSGI packaging excludes | **kept** at `d24622d` | Already correct; a signed jar cannot be merged into an APK. |
| `HistoryPrune.keepLatest` / `collect` | present | **Not used by this design.** The rewrite goes; see §6. |

The parts JGit gives us and we should *not* rewrite: `LfsPointer` (parse/format), `CleanFilter` (write the
pointer, move the bytes into the store), `LfsBlobLoader` (read from the store).

---

## 2. What has to be written

### 2.1 `LfsClient` — the HTTP half (new file, `LfsClient.kt`)

A small, synchronous client over `HttpConnection`, authenticated with the token the app already holds.
It replaces JGit's connection factory for **both** directions.

```
LfsClient.forRemote(remoteUrl): String        // → "https://github.com/<owner>/<repo>.git/info/lfs"
batch(operation, objects): Response           // POST objects/batch, Authorization: Basic
upload(href, headers, file)                   // PUT the bytes to the signed S3 URL
download(href, headers, target)               // GET the bytes, write atomically
verify(href, headers, oid, size)              // POST verify — GitHub needs it before the object exists
```

**Auth form matters.** Measured both ways against GitHub: `Basic base64("x-access-token:<token>")` answers
`200`. The `Authorization` header must go on the **batch** request only — the upload/download URLs are
pre-signed S3 links and carry their own signature; adding ours to them is not merely useless, it is a
signature mismatch.

**Redirects must be followed on download.** S3 answers the GET with a redirect to the object; JGit sets
`setInstanceFollowRedirects`, and so must we, or the download returns a redirect body as if it were a
picture.

### 2.2 `LfsSync` — the flow (new file, `LfsSync.kt`)

Four verbs, each of which either succeeds or names what failed.

**`push(repo, token): Result`** — after the ordinary `git push` has sent the tree:

1. Walk the pushed commits' trees, collect every pointer oid (`LfsStore.reachable` gives this shape).
2. `batch("upload", oids)` → for each object GitHub does **not** already have, `upload`, then `verify`.
3. **Order matters.** GitHub's pre-receive hook rejects a push whose tree references an object it does not
   have (`GH008`). So the objects must be there *before* the ref moves — meaning this runs **before**
   `push`, not after.

**`pull(repo, token): Result`** — after `fetch`, before checkout:

1. Read the pointers in the fetched tree.
2. For each oid whose file is missing from `.git/lfs/objects`, `batch("download", oids)` then `download`.
3. **Only then** check out. This is the step JGit skips on a private repo, and skipping it is the whole bug.

**`missing(repo): List<String>`** — the check that makes a silent failure impossible: every path whose
working-tree file is still a pointer. Called after any checkout; a non-empty answer is reported.

**`prune(repo): Long`** — `LfsStore.sweep`. Deletes stored objects no commit points at, returns bytes freed.

### 2.3 Where the picture is a pointer, not bytes

The clean filter writes the pointer and moves the bytes into `.git/lfs/objects`. This is what makes "delete
the picture" mean something: the pointer leaves the tree, and `prune` removes the bytes. **No commit is
rewritten.**

Consequence to state in the UI: **deleting a picture cannot be undone by history**, because history never
held the bytes. That decision is already made (`46abf0c`, `deleteWarning`), and this design keeps it honest.

---

## 3. The order of operations (this is the part that is easy to get wrong)

```
save a picture
  └─ clean filter → pointer in the tree, bytes in .git/lfs/objects
       └─ commit
            └─ sync:
                 ├─ LfsSync.push      (objects to GitHub, verified)
                 └─ git push          (the tree that names them)

receive a picture
  └─ fetch
       └─ LfsSync.pull             (missing objects downloaded)
            └─ checkout             (smudge finds them locally)
                 └─ LfsSync.missing (must be empty; report if not)
```

`LfsStore.sweep` runs where `Maintenance` already runs — at app start — and **only** after a push, so bytes
that have not reached GitHub yet are never deleted locally.

---

## 4. What must be true before this is called done

Each of these is a test that fails if the behaviour breaks, per the project rule.

1. **A pointer is what git stores.** Commit a PNG, read the blob at `HEAD` — it is text starting
   `version https://git-lfs.github.com/spec/v1`. The bytes are in `.git/lfs/objects/<oid>`.
2. **The batch request carries the token.** Against a stub server: the `Authorization` header is
   `Basic …` on the batch call, and **absent** on the upload/download call.
3. **A private repository downloads its bytes.** The measured failure, as a regression test: clone, pull,
   checkout — the file is not a pointer.
4. **A missing object is reported, not shown.** With the batch call stubbed to 401, `missing()` names the
   path and the sync reports it; the screen never shows a broken picture with no explanation.
5. **Objects are pushed before the ref.** With the upload stubbed to fail, `push` does not move the ref.
6. **A deleted picture's bytes go.** Save, push, delete, `prune` → the object is gone and the freed count is
   positive; a still-referenced object is untouched.
7. **No commit id changes.** The sha of `HEAD` before and after a picture save is the one the commit made —
   and `git log` after a sync shows the same ids as before the push. (This is the property the rewrite could
   not have.)
8. **Text is untouched.** A text file's blob is its text, not a pointer; `.gitattributes` is the only file
   this feature adds.

**The honest limit:** 1, 5, 6, 7 can run in JVM tests against a local bare repository and a stub HTTP
server. 3 and 4 need GitHub, so they are gated behind an environment variable and skipped when absent —
which means **CI cannot prove the private-repo download works**; only a real run can. That is stated here
so nobody reads a green build as proof.

---

## 5. Steps, in order, each independently shippable

| # | Step | Ends with |
|---|---|---|
| 1 | Restore `LfsStore.kt` and its tests from `d24622d^` | store logic back, still unwired |
| 2 | Restore dependencies, `registerLfs`, `enableLfs`, `ensureLfsAttributes`, `LFS_ATTRIBUTE`, packaging excludes | a picture is stored as a pointer locally |
| 3 | `LfsClient` — URL discovery, batch, upload, download, verify, with a stub-server test | the HTTP half exists and is tested offline |
| 4 | `LfsSync.push` before `push`, wired into `ProjectGit.sync` | objects reach GitHub before the ref |
| 5 | `LfsSync.pull` after `fetch`, before checkout | **the measured bug is fixed** |
| 6 | `LfsSync.missing` after every checkout, reported through `Notices` | no silent pointer |
| 7 | `prune` at app start, after a successful push | deleted pictures release their bytes |
| 8 | Delete the rewrite: `HistoryPrune.keepLatest`, `treeWith`, `expireReflog`, `collect`; restore `prune`'s caller to nothing; drop `Maintenance.freed` | history is never rewritten |
| 9 | `docs/PLAN-lfs-2026-11-05.md` §0 rewritten: JGit's client is unusable on a private repo, ours replaces it | the record matches the code |
| 10 | `tools/test-map.txt` rows for `LfsClient`, `LfsSync` | the map check passes |

**Step 8 is the one that removes the risk the operator was worried about.** Until it lands, the rewrite is
still in the tree; it should not be left there "just in case".

---

## 6. What this design does *not* do

- **It does not rewrite history.** No commit is rebuilt, no id changes. A second device pulls and merges
  normally.
- **It does not make force-push unnecessary.** `sync` still force-pushes for `unsent` (the derived tree).
  That is a separate debt, unchanged by this plan.
- **It does not guarantee GitHub forgets a deleted object immediately.** The project stops referencing it and
  our copy is deleted; GitHub expires unreferenced objects on its own schedule. Stated plainly so the UI
  never promises more.
- **It does not work without network for the download half.** A picture whose bytes were never fetched is a
  pointer, and the app says so rather than pretending.

---

## 7. Risks, honestly

| Risk | What we do about it |
|---|---|
| **CI cannot test against private GitHub** | Steps 1, 5, 6, 7 test offline against a bare repo and a stub; the private-repo case is a manual gate documented in §4. |
| **A 401 during upload leaves the tree unpushed** | Step 4 order: objects first, ref second; a failed upload does not move the ref, so the next sync retries rather than pushing a tree GitHub will reject. |
| **S3 redirects** | Follow redirects on download; assert the response is the object's size, not a redirect body. |
| **Token scopes** | LFS needs `repo` write, already granted. Nothing new. |
| **Storage quota** | GitHub LFS is billed per GB. `LfsStore.sizeOf` already exists to report it; the cost sheet shows it. |
| **A pointer committed before the filter was on** | `missing()` reports it; the bytes are still in the tree, so it can be healed by a re-commit rather than lost. |
| **The 200 s command budget** | The probes exceeded it; use `nohup` for anything that clones. |

---

## 8. The one thing to decide before starting

**Where does the token come from at the moment of the LFS call?** The app holds it in `CredentialStore`,
and `ProjectGit` already receives a `tokenProvider`. The plan assumes `LfsClient` takes a
`() -> String?` and never reads storage itself, so it stays testable — the same seam `tokenProvider`
already is. If that assumption is wrong, step 3 changes shape, so it is worth confirming before it starts.
