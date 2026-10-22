# Design change proposal v2 — Projects, accounts, and a key that cannot leak

Status: **for review** · nothing built yet · v1 with your annotations kept as
`PROPOSAL-projects-and-key-v1-annotated.md`.

---

## 1. What your answers changed

| # | v1 said | your call | effect |
|---|---|---|---|
| 1 | deleting a project always deletes the repo too | **local only** when you delete the row; full delete only via Settings | a project can be "unlinked" by deleting its row, and pulled back later |
| 2 | files are `.md` | **any extension**, `.md` default | loses the front-matter header — see §8 |
| 3 | five sync states on the row | **no states**: every commit is a sync | the whole state table is gone |
| 4 | Settings → **Track** | Settings → **Pull**, overwriting local every time | remote is authoritative for Pull |
| 5 | conflict → abort, report, offer override | **local always wins**, GitHub is the projection | Sync force-pushes. No conflict screens at all |
| 6 | public or private | **public only**, with licence selection | no private option anywhere |
| 7 | colliding names get `(2)` | **clear error**, no suffixes | simpler and honest |
| 8 | old nested repos set aside | **drop** | one less thing to explain, and less to keep |
| 9 | one account | **several stored, one active**, per-account folders | account switch swaps the visible projects |
| 10 | key needs `repo` + `delete_repo` | **confirmed, we delete repos** | unchanged |

Two consequences fall out that are worth naming up front, because they remove
work rather than add it:

- **No conflict handling.** Sync is commit → force push. It cannot fail on a
  conflict, which is what "should always succeed" requires.
- **No commit dialog.** Commit messages are written by the app during sync, so
  the commit button and its message box both disappear.

---

## 2. The model

```text
Account ── has many ──▶ Project ── has many ──▶ Folder ── has many ──▶ File
```

- **Account** — a GitHub sign-in. Several can be stored; one is active. Each has
  its own hidden folder, so switching accounts hides the others' projects
  without deleting anything.
- **Project** — one **public** GitHub repository plus its local folder. Always
  connected: it either was created with its repository or was pulled from one.
  There is no "local-only project", which is why creating needs an account.
- **Folder** — a directory inside a project. Nests freely. Local organisation,
  carried into git as real directories.
- **File** — anything with any extension. `.md` is the default.

Project names are unique inside an account. A duplicate is refused with a plain
message — no `(2)` suffixes.

---

## 3. Disk layout

```text
files/
  accounts/
    muffyuniverse/                 ← one folder per stored account
      account.json                 login, commit email, encrypted key
      projects.json                the projects of this account
      projects/
        muffycodes/                ← the git worktree, one repo per project
          .git/
          LICENSE                  seeded by GitHub at creation
          README.md                seeded by GitHub at creation
          Shopping/                a folder is a real directory
            Milk.md
            Photo.png              any extension
          .muffy/index.json        pinned flags — see §8, gitignored
    someoneelse/                   invisible while another account is active
      ...
```

**Files are named after their titles.** `Shopping/Milk.md`, not
`Shopping/8f1c….md` — the repository is meant to be read on GitHub.

---

## 4. Screens

### 4.1 Projects (was Files)

- Title **Projects**. One row per project of the **active** account, nothing else.
- Each row: project name, the repository name under it, and **how many files
  have changes waiting** (`3 changed`). That count is the only signal — many
  changed files means it is time to open it and sync.
- Tapping a row opens the project's folder tree.
- `+` creates a new project (§5.1).
- The row's menu has **Delete**, which removes it from this phone only. The
  repository stays on GitHub and can be pulled back from Settings.

### 4.2 Inside a project

- The normal title and actions stay.
- A **second, slim row** sits under the top bar on every screen inside the
  project — folder tree, file editor, history: the project's name on the left,
  the **sync button** on the right. The sync button is never hunted for.
- The old commit button is gone. Everything else stays: folders, files, search,
  pinning, version history and restore.
- Search spans **all projects of the active account** and shows the project name
  on each result.

### 4.3 Settings

- The **"Git projects" box is removed** entirely, together with its per-folder
  Attach / New repo / Clone / Detach row.
- The **GitHub account** box keeps the connected state and the commit email, and
  becomes the account and project cockpit:

  | control | what it does |
  |---|---|
  | **Sign in** | adds an account; it becomes active |
  | **Switch** | picks which stored account is active; the Projects screen follows |
  | **Forget** | removes the account's credentials and hides its folder; nothing on GitHub is touched |
  | **Pull** | lists the account's public repositories and pulls any one in, **overwriting the local copy if it exists** |
  | **Delete** | lists this account's projects; deleting one removes the local folder **and** the GitHub repository |

---

## 5. The four project actions

### 5.1 Create (Projects → +)

1. Ask for a name and a licence.
2. `POST /user/repos` with `auto_init` and `license_template`.
3. **GitHub seeds `LICENSE` and `README.md` and makes an initial commit.**
4. Clone that repository in, so the project exists in both places at once.

I checked this against your account before writing this: creating with
`license_template: gpl-3.0` produced a repository holding `LICENSE` (35,149
bytes), `README.md`, and one commit `Initial commit` on `main`. There are 13
licences to choose from via `GET /licenses`, and `gpl-3.0` is there — the
sensible default, since `muffycodes` already uses it.

Because GitHub writes the licence, the app never writes a `README`, and the
project is synced from the first second — which is what makes "all connected by
default" true without any extra machinery.

### 5.2 Sync (the button inside a project)

1. Commit everything that changed, with a message the app writes.
2. **Force push** to the remote branch.

Local wins, always. It cannot fail on a conflict, so it does what you asked: the
phone and GitHub end up holding the same thing, and the phone is the one that
decided.

### 5.3 Pull (Settings → Pull)

The account's public repositories, newest first, each showing its name and when
it last changed. Picking one:

1. If the project already exists locally, its folder is **replaced**:
   fetch, `reset --hard` onto the remote branch, and remove untracked files.
2. If it does not exist, it is cloned in and appears on the Projects screen.

Remote is authoritative here, and that is the point of a separate button: it is
how you recover a project whose local copy you deleted, or undo a bad local
state.

`clean` respects `.gitignore`, so `.muffy/index.json` (pinned flags) survives a
Pull. Nothing of yours is silently thrown away except the project's working
files, which is exactly what "overwriting the local version" means.

### 5.4 Delete (Settings → Delete, and the row menu)

Two different actions, deliberately:

| where | what dies |
|---|---|
| the row menu on the Projects screen | the local folder only. The repository stays, and Pull brings it back |
| Settings → Delete | the local folder **and** the GitHub repository |

Settings → Delete confirms by naming both: *"This removes the folder from this
phone and the repository `muffyuniverse/shopping` from GitHub."* A repository
already deleted on GitHub is not an error — the local side still goes.

---

## 6. The key

Unchanged from v1, which the annotations did not object to, and now with
`delete_repo` confirmed as required. In short:

1. **Keep the key out of the address.** JGit takes it separately
   (`setCredentialsProvider`); I tested clone and push against the real
   repository this way and `.git/config` kept a clean URL.
2. **Scrub every message and every exception** before it is shown or stored —
   this is the live leak in `GitService.kt:132` and `:134`, which print
   `"Cloned $remoteUrl"` with the key inside it.
3. **Encrypted with an Android KeyStore key**, `setUnlockedDeviceRequired`, out
   of auto-backup, decrypted per operation and never held in a field.
4. **Never on screen and never logged.** Masked field, `FLAG_SECURE` on the
   connect screen, a `Secret` type whose `toString()` is `***`.
5. **Only ever talk to `github.com` and `api.github.com`** — check the host
   before attaching the key to any request or clone.
6. **A regression test that greps the whole project directory and the app data
   directory for the key after a clone and a push** and fails if it finds it.

### 6.1 The token now in use (verified 2026-10-22)

Scopes: **`repo`, `delete_repo`, `workflow`, `audit_log`, `write:packages`,
`delete:packages`**.

The dangerous one is gone — `admin:public_key`, which let its holder install SSH
keys on the account, was the account-takeover route. `admin:org_hook` and
`admin:repo_hook` are gone too.

Every operation the app performs was exercised against the real account with
this token and all of them work:

| operation | result |
|---|---|
| list repositories (Pull screen) | OK |
| create public repo with `license_template` | OK — `LICENSE` + `README.md` + `Initial commit` |
| clone it | OK |
| commit and push | OK |
| push a `.github/workflows/` file | OK — needs `workflow`, held |
| delete the repository (Settings → Delete) | OK — HTTP 204, then 404 |

The probe repository was deleted afterwards; the account holds only `muffycodes`.

Three scopes are still there that the app cannot use: `audit_log` (reads the
account's security audit log — an information leak), and `write:packages` plus
`delete:packages` (publish and delete packages). They are worth removing, but
they are not in the same class as `admin:public_key` was, so this is tidying
rather than urgent.

`workflow` was kept deliberately. GitHub rejects a push server-side if a commit
touches `.github/workflows/`, and `muffycodes` holds three workflow files, so
without it a sync of *this* project could fail. The cost is that a leaked token
could rewrite `build-and-release.yml` in order to read the signing secrets, so
the trade is understood rather than accidental.

---

## 7. Migration from today's app

Nothing is deleted until the new layout has been read back once.

1. Read the old tree with the existing reader, which already understands every
   earlier format.
2. Each **first-level folder becomes a project**, keeping its name, and its
   repository if it had one — so the history is kept.
3. Nested folders and files move inside it as real directories, files named
   after their titles.
4. Top-level files (outside any folder) go into a project created for them.
5. **Old repositories attached to nested folders are dropped**, as you asked.
   The files are kept; only the inner `.git` goes. These are the only git
   histories the migration loses, and the report says which.
6. One project per account means the migrated projects land in the account that
   is signed in at the time.

---

## 8. The one thing your answers forced that I had to decide

**Files on disk must be pristine — no header injected into them.**

v1 put a small header (`id`, `title`, `pinned`, `updatedAt`) at the top of every
file. Your two answers — *any extension* and *GitHub is the projection* — make
that impossible:

- a `.png` or `.json` cannot carry a YAML header without ceasing to be valid;
- a `README.md` and `LICENSE` seeded by GitHub would be mangled on the first
  sync, and the licence file would stop being a licence.

So I propose:

- **The file on disk is exactly what you typed.** Nothing is added.
- **The file's name is its title** (your answer), so no title needs storing.
- **`pinned` and `updatedAt` live in `.muffy/index.json`**, keyed by path,
  gitignored. They are UI state, so being device-local is correct.
- **A file's identity is its path**, so the old stable `id` goes away entirely.
  Renaming renames the file, and git tracks the rename.

This makes the repository genuinely a projection — you can read it on GitHub, and
a `LICENSE` stays a `LICENSE`.

The cost: pinning does not travel between phones, and renaming a file in the app
is a git rename rather than a metadata edit (which is what you want to see on
GitHub anyway).

**If you would rather pinned state synced**, say so and `.muffy/` gets committed
instead of ignored. Nothing else changes. This is the only open question.

---

## 9. Risks I am accepting because you asked for this behaviour

Named plainly so nobody is surprised later:

1. **Sync force-pushes.** Two phones on one project: the second to sync silently
   replaces the first's commits. Nothing warns, because you asked for local to
   win every conflict.
2. **Pull overwrites the local working copy.** Unsynced edits in the project are
   lost. Since the point of the button is "make local match remote", I propose
   Pull asks once when the project has unsynced changes — a yes/no, not a merge
   screen. Tell me if you would rather it never asks.
3. **`delete_repo` is destructive.** A mis-tap on Settings → Delete destroys a
   repository. The confirmation names the repository, and that is the only guard.

---

## 10. Order of work

1. **Key safety** — credentials provider on the clone path, the scrubber, host
   check, `FLAG_SECURE`, backup exclusion, the leak test. Independent of
   everything else and it stops the live leak, so it can ship on its own.
2. **Accounts** — per-account folders, sign in / switch / forget, the encrypted
   key per account.
3. **Storage 2.0** — projects as worktrees, real directories, titles as file
   names, pristine files, paths as identity, migration and its tests.
4. **Projects screen** — rename, project rows with the changed count, create
   with licence, delete-here, the slim project row with sync inside a project.
5. **Sync** — auto-commit message, force push.
6. **Pull and full Delete** — the remote list, overwrite-in, delete both sides.
7. **Settings** — drop the Git projects box, add the account controls, Pull and
   Delete.

Steps 1 and 3 cannot be done halfway; the rest can land in that order without
breaking anything.
