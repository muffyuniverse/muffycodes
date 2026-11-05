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

### The second measurement: the filter breaks every push

Found while wiring it up, and it is not about pictures at all.

**With `filter.lfs.useJGitBuiltin` enabled, every push throws.** A probe against a bare repository, with
one text file and no pictures:

```
without BuiltinLFS.register():  PUSH OK
with    BuiltinLFS.register():  PUSH FAILED: NullPointerException
  Cannot invoke "SshSessionFactory.getSession(...)" because the return value of
  "SshSessionFactory.getInstance()" is null
  at org.eclipse.jgit.util.SshSupport.runSshCommand(SshSupport.java:71)
  at LfsConnectionFactory.getSshAuthentication(LfsConnectionFactory.java:186)
  at LfsConnectionFactory.discoverLfsUrl(...)
  at LfsPrePushHook.call(LfsPrePushHook.java:102)
```

`LfsPrePushHook.call()` asks for a connection **before** it checks whether it has anything to upload, and
`SshSupport` dereferences a session factory that nothing on Android configures. A project of text files
cannot be synced at all.

**The hook short-circuits on an empty pointer set** (`findObjectsToPush().isEmpty()`), so the fix is to
turn the filter off for the duration of a push and restore it in a `finally`. Both push paths do this.

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

## 5. Delivery

**All ten steps are done and CI is green** (run `42057895480`, 904 tests, 0 failures, APK built).

| # | Step | State | Note |
|---|---|---|---|
| 1 | `LfsStore` restored | **done** | `c14963f` |
| 2 | Dependencies, `registerLfs`, `enableLfs`, `ensureLfsAttributes`, `LFS_ATTRIBUTE` | **done** | `68a77b5` |
| 3 | `LfsClient` with a local-server test | **done** | `48bf8b5` |
| 4 | `LfsSync.push` before the push | **done** | `76d2396` |
| 5 | `LfsSync.pull` before checkout | **done** | `76d2396` |
| 6 | `LfsSync.missing` reported after a pull | **done** | `76d2396` |
| 7 | `LfsStore.sweep` at app start | **done** | `c14963f` |
| 8 | The rewrite deleted — `HistoryPrune` gone | **done** | `76d2396` |
| 9 | This document | **done** | |
| 10 | `tools/test-map.txt` rows | **done** | |

### What reality contradicted

Five things the plan got wrong, recorded so the next reader does not re-derive them.

1. **The filter broke every push, not just picture pushes.** §0 now carries the measurement. The fix
   is in `GitService.push`/`pushWithout`: the filter is off for the duration and restored in a
   `finally`. This was not foreseeable from the plan and would have shipped as "sync is broken".

2. **The stored file is named by the whole oid, not its tail.** JGit writes
   `objects/a3/be/a3bea3f9…` — the fan-out folders *repeat* the prefix. `LfsStore.sweep` concatenated
   them, produced a 68-character name, matched nothing, and **deleted every stored picture on every
   pass**. Three retention tests caught it (`778cb46`).

3. **An empty reachable set is not proof that nothing is used.** A repository whose filter was never
   registered stores pictures whole, so its trees hold no pointers while its store is full — and the
   sweep would read that as "everything is dead". It now answers `-1` and `Maintenance` reports that it
   did not run (`328efde`).

4. **`reachable` must walk `HEAD`, not a branch name.** `fullBranch` answers a short name in some
   states, `findRef` on it returns null, and the walk never started (`1c84977`).

5. **A project with no pictures must not need an address or a token.** Both guards checked the
   endpoint first, so every text project's sync failed with a sentence about pictures it does not have.
   Both now answer early when there is nothing to move (`9863a57`).

### The manual gate — **closed, measured**

Run on a private repository (`muffyuniverse/lfs-gate`, now archived) through the same sequence the app
uses. The picture was 26 bytes, the pointer in the tree was 127.

```
1. pointer in tree, bytes stored: true
2. upload needed: false (GitHub already has it)
4. push refs/heads/main -> OK
5. clone left a pointer: true
7. bytes arrived: true size=26
   identical to what was saved: true
```

And GitHub's own view of the project afterwards — the full project, picture included:

```
.gitattributes   milk.txt   page.png
```

with `page.png` on the server being the pointer text, `version https://git-lfs.github.com/spec/v1`,
whose oid matches the bytes that came back on the clone.

**Three things this run proved that CI cannot:**

1. **The batch call authenticates with `Basic`** on a private repository — the step JGit skips.
2. **`verify` is required.** GitHub answers an upload with a signed URL, and the object does not exist
   for a download until `verify` is called on it: without it the download batch answers
   `404 Object does not exist on the server`.
3. **A push with the filter off succeeds**, which is the workaround for the `LfsPrePushHook` NPE.

**One thing it corrected in this plan.** The probe first reported `push: ok` over a repository that
received nothing, because it trusted the absence of an exception. The ref update's own status is the
verdict — `refs/heads/main -> OK` — and `GitService.push` already reads it that way.

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
| **CI cannot test against private GitHub** | Steps 1, 5, 6, 7 test offline against a bare repo and a stub; the private-repo case is a manual gate documented in §4. `MUFFY_LFS_TEST_TOKEN` is set as a repository secret for the run that does test it. |
| **The filter breaks every push** | Measured, and guarded: `GitService.push` and `pushWithout` switch `filter.lfs.useJGitBuiltin` off for their duration. A test pushes a text-only project with the filter on. |
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
