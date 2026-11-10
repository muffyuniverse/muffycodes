# What was removed on 2026-11-09: LFS, and the maintenance pass that existed for it

**Commit:** `9f89648d` — *"LFS is removed: a picture is bytes in the commit, not a pointer beside it"*
**2,274 lines deleted, 94 added, 17 files.**

**The operator's decision:** assets live in releases, so the git filter goes. The same decision that produced
`PLAN-portable-2026-11-08` — *the app relies solely on the git protocol and HTTP tokens* — because **LFS is
not the git protocol**: it is a second protocol bolted beside it, with its own server, its own endpoint
discovery, and its own credentials. A project that needed it was a project this app could not carry to an
arbitrary host.

**This document is the record, so that "attach it back" is a decision somebody can make from evidence rather
than by re-discovering the shape.** The code is not commented out: it is deleted, and git holds it.

---

## 1. What was removed

| removed | lines | what it was |
|---|---|---|
| `LfsClient.kt` | 290 | the transport: batch requests, upload/download actions, its own auth |
| `LfsStore.kt` | 163 | the local object store under `.git/lfs/objects` |
| `LfsSync.kt` | 194 | the pointer↔bytes reconciliation around a push and a pull |
| `Maintenance.kt` | 231 | a periodic sweep whose **only** job was pruning `.git/lfs/objects` |
| **main total** | **878** | |
| `LfsClientTest.kt` | 196 | |
| `LfsSyncTest.kt` | 275 | |
| `MaintenanceTest.kt` | 411 | |
| **test total** | **882** | |

and, in files that stay:

- **`GitService`** — the six-function LFS family (`registerLfs`, `enableLfs`, `ensureLfsAttributes`,
  `ensureLfsAttributesIfMissing`, `ensureLfsAttributesIfMissing`'s `LFS_ATTRIBUTE` constant,
  `ensureLfsAttribute`), and **the filter toggle wrapped around every push**.
- **`ProjectGit`** — the `.gitattributes` written into every new project, and **the picture-first ordering in
  `sync`/`push`**.
- **`AppViewModel`** — the `housekeeping` seam, the `maintenance-due` marker, and the daemon pass that read it.
- **`Workspace`** — the `.gitattributes` seeding.
- **`app/build.gradle.kts`** — the two JGit LFS artifacts and Gson.
- **`tools/test-map.txt`** — three lines.

## 2. Why the ordering mattered, and what it hid

LFS required bytes to arrive **before** the ref, or the host refused the push (GitHub's `GH008`). So `sync`
and `push` were written picture-first, and that ordering was load-bearing for a reason nothing in the app
could state plainly: *the filter is a second transport and it can fail independently of the push*.

**It did fail, and it took every push with it.** The LFS pre-push hook asked for a connection it could not
make on Android, so **a project of text files could not be synced** — a failure with no relationship to its
cause, in the one act the app exists to perform.

## 3. What became the opposite assertion

Two refusals existed only because of the filter, and are now the reverse:

| was | is |
|---|---|
| a binary is a pointer, so it has no restorable version | **a binary has versions**, because its bytes are in the tree — `restore` writes them back |
| bringing a deleted picture back is a sentence about permanence | it is ordinary work |

`ProjectGitTest` proved *"a push works with the filter on"*. It now proves **"a push needs no filter at
all"**, with the old bug kept in the comment as history.

## 4. What is lost, stated honestly

- **A repository holding many large binaries grows without bound.** Git keeps every version of every byte,
  so a 40 MB video committed ten times is 400 MB of history, and a clone pays all of it. LFS existed to make
  that cheap; without it, the answer is *do not put large binaries in the repository*.
- **The app cannot enforce that.** Nothing refuses a large file, and nothing warns. The mitigation is
  structural rather than a rule: assets belong in **releases**, which is the operator's decision and which
  `git {op:"tag"}` plus `http` now reaches on any host.

## 5. What replaced it

- **`http`** — one complete request document, with `{{secret:…}}` and `{{file:…}}` expanded for the request's
  own host. An asset upload is a request the agent writes, not a verb the app has.
- **`git {op:"tag"}`** — a version's portable marker. What a host builds from a tag is that host's business.
- **`archive`** — vendoring is two visible steps (`unpack` moves a fetched file into the project), which is
  how a dependency lands without a second transport.

The universal test the removal was decided by: **can the git protocol do this?** LFS could not, and a
project that needed it could not travel. Releases can, because they are HTTP, and HTTP is the other protocol
the app is allowed to speak.
