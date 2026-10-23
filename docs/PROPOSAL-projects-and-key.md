# The settled design — projects, accounts, and a key that cannot leak

This is the agreed form of `PROPOSAL-projects-and-key-v2.md`, with every
question answered inline by the operator on 2026-10-23. It replaces the
proposal as the reference for implementation. Where this document and the
proposals disagree, this one is right.

Status: **settled · implementation in progress**.

---

## 1. The shape of the thing

The app is a **filesystem rooted at the active account**, and a project is a
folder in it that contains a git worktree.

```text
<active account>/                      ← the root of the displayed filesystem
  Shopping/                            ← a PROJECT: a wrapper, not part of any repo
    repo/                              ← the git worktree — the only thing GitHub sees
      LICENSE                          seeded by GitHub at creation
      README.md                        seeded by GitHub at creation
      Milk.txt
      Photo.png                        any extension
    .muffy/                            ← project-local state, never committed
      index.json                       pinned flags and friends
  (future kinds of folders live here too)
```

- **The account is the root**, not a hidden parent. The main screen shows the
  active account's folder with projects under it. Other kinds of folders can
  appear under the account later.
- **A project is a wrapper** — an intermediate folder that composes the git
  worktree with project-related files and folders that stay local and never go
  into the repository. Only the codebase goes into the repo. Everything the app
  needs but the repository must not carry (pinned flags and the like) lives in
  the wrapper.
- **File titles are file names.** `Shopping/Milk.txt`, not an opaque id. The
  file on disk is exactly what you typed — no headers, no front matter. A
  file's identity is its path; renaming renames the file and git tracks the
  rename.
- **The extension is the part of the name after the dot, ordinary and nothing
  more.** Default for new files: **`.txt`**.
- **There is no preview of anything** — not markdown, not code. Everything is
  the editing screen. Images show as the picture and are not edited; other
  binaries say what they are and offer rename/delete.

## 2. Accounts

Several accounts can be stored; **one is active** and its folder is the root.

| control | what it does |
|---|---|
| **Sign in** | adds an account; it becomes active |
| **Switch** | makes another stored account active; the root follows |
| **Sign out** | stops using the account and hides its side of the phone; signing in again brings it back |
| **Forget** | deliberately removes **all local files associated with the account** — the destructive one |
| **Pull** | lists the active account's public repositories and pulls one in, **overwriting the local copy if it exists** |
| **Delete** | lists the account's projects; deleting one removes the local wrapper **and** the GitHub repository |

Each account stores its own **commit identity** — a name and an email,
editable, defaulting to the GitHub login and its address. Commits made while an
account is active are written as that account's identity.

Hiding an account's side of the phone is **Switch**'s and **Sign out**'s job.
**Forget** is the button that destroys local files; it is named and confirmed
as such.

## 3. Projects

- One project = one **public** GitHub repository + its local wrapper. Always
  connected: created with its repository, or pulled from one.
- **The project name is the repository name.** Renaming a project renamesits
  repository on GitHub as well. One name everywhere.
- Project names are unique inside an account. A collision is a plain error —
  no `(2)` suffixes.
- Creation: name + licence (13 from `GET /licenses`, default `gpl-3.0`),
  `POST /user/repos` with `auto_init` + `license_template`, then clone the
  seeded repository in. GitHub writes `LICENSE` and `README.md`; the app never
  writes a README.

## 4. Sync, Pull, Delete

- **Sync** — the one button, in the slim row. Commits **everything that
  changed in the worktree**, with a message the app writes from **the names
  that changed** (`Milk.txt, Photo.png`, `+3 more` past a few), then
  **force-pushes**. Local always wins. There is no commit dialog and no sync
  states: every commit is a sync.
- **Online required.** Sync with no network does nothing and says so plainly —
  uniformity gives simplicity. Nothing lands half-done. A repository deleted
  behind your back gets the same plain message with a hint that it is gone.
- **Pull** — remote is authoritative: fetch, `reset --hard`, clean. It asks
  once before overwriting a project with unsynced changes — a yes/no, not a
  merge screen.
- **Delete from the row menu** — removes the local wrapper; the repository
  stays on GitHub and Pull brings it back.
- **Delete from Settings** — removes the local wrapper **and** the GitHub
  repository. Confirmed by naming the repository.

## 5. Screens

- **The root** — the active account's folder: projects as subfolders, and room
  for other kinds of folders later.
- **Inside a project** — every screen inside a project carries a **slim row**
  under the top bar: **account name, project name, the number of unsynced
  changes (from `git status`), and the sync button.** The old commit button,
  its badge and the `git:` subtitle are gone. Folders, files, search, pinning,
  the version history with its back/forward arrows and restore all stay.
- **Search** spans all projects of the active account, showing the project name
  on each result.
- **Settings** — the account cockpit of §2. The old "Git projects" box with
  Attach / New repo / Clone / Detach is gone.

## 6. The key

Unchanged from the proposal, and order-of-work item 1:

1. The key is never in an address. JGit takes it separately
   (`setCredentialsProvider`).
2. Every message and every exception is scrubbed before it is shown or stored.
3. Encrypted with an Android KeyStore key, `setUnlockedDeviceRequired`, out of
   auto-backup, decrypted per operation, never held in a field.
4. Never on screen and never logged. Masked field, `FLAG_SECURE` on the
   connect screen, a `Secret` whose `toString()` is `***`.
5. The app only ever talks to `github.com` and `api.github.com` — the host is
   checked before the key is attached to any request or clone.
6. A regression test greps the project directory and the app data directory
   for the key after a clone and a push, and fails if it finds it.

Two scopes: `repo` and `delete_repo`. A token with more is refused advice:
rotate it.

## 7. What is deliberately not here

- **No migration.** The app is rapidly developed, not maintained; no backward
  compatibility with earlier storage formats is needed. The new storage starts
  empty and the old files are not carried over.
- No sync states, no conflict screens, no commit dialog, no front matter in
  files, no previews, no private repositories.

## 8. Order of work

1. **Key safety** — independent, ships alone, closes the live leak.
2. **Accounts** — per-account folders, sign in / switch / sign out / forget,
   the encrypted key and commit identity per account.
3. **Storage 2.0** — the account-rooted tree, project wrappers, the worktree
   inside, titles as file names, pristine files, paths as identity, local-only
   companion state.
4. **The root screen and the slim row** — projects as subfolders, changed
   counts from `git status`, sync button placement.
5. **Sync** — auto messages from changed names, force push, online-required
   failures.
6. **Pull and the two Deletes.**
7. **Settings** — the account cockpit.

Steps 2 and 3 are the load-bearing pair; everything else lands on top of them.
