# Design change proposal — Projects, and a key that cannot leak

Status: **for review** · nothing here is built yet.

---

## 0. What I understand you to be asking for

Right now the app has a *Files* screen with folders that nest without limit, and
any folder can separately become a git project. That was the right shape when
git was an extra. It is the wrong shape now that the app is meant to be a mirror
of your GitHub account, because "which of these folders is a repository" becomes
a question the user has to answer, and a nested folder with its own repository
cannot be part of its parent's repository.

You want one level of git and no ambiguity:

1. **A project is the unit.** The top screen becomes **Projects**. Every row on
   it is a project root folder. Nothing else lives there.
2. **A project exists in two places or not at all.** Creating a project creates
   the repository on GitHub *and* the folder on the phone. So creating needs a
   connected account.
3. **Inside a project** the folder tree keeps nesting as deeply as you like. That
   nesting is local detail inside one repository, not more repositories.
4. **Sync replaces commit.** One button, and afterwards the phone and GitHub
   hold the same thing.
5. **Pull-in.** A list of the account's repositories that are not on the phone
   yet, so one can be *tracked* (cloned in as a project); and a list of projects
   that exist in both places.
6. **Delete means both.** Removing a project removes the local folder and the
   GitHub repository.
7. **Settings loses the per-folder "Git projects" box** and the account box gains
   **Track** and **Delete**.
8. **The key must not leak**, and it needs enough power to do all of the above:
   create, read, write, push, pull, delete.

---

## 1. The key

### 1.1 What can honestly be promised

An app cannot protect a secret from a phone it does not control. If the phone is
rooted or the app's data is extracted with the key store unlocked, the key can be
read. Anyone who says otherwise is selling something.

What an app *can* do is shrink the circle: keep the key encrypted and bound to
this device, never write it anywhere a copy can survive, never put it on the
screen or in a log, never send it to a host that is not GitHub, make it as narrow
as the job allows, and make it revocable in one tap. That is the design below.

### 1.2 Three leaks that exist in the code today

These are real, and I found them by reading the code after your message. All
three come from one mistake: putting the key into the repository address.

- `GitHubClient.kt:104` `authenticatedCloneUrl()` turns
  `https://github.com/owner/repo.git` into `https://ghp_…@github.com/…`.
- `GitService.kt:132` then reports success as `"Cloned $remoteUrl"` — so the key
  is **printed on the screen**, and `NotesViewModel` keeps that string in
  `gitMessage`, which is what the snackbar shows.
- `GitService.kt:134` reports failure as `e.message ?: "Could not clone $remoteUrl"`
  — so a failed clone can put the key on the screen too, and JGit's exception
  text often contains the address it failed on.

`GitProjects.kt:190` does try to clean up afterwards
(`setRemoteUrl(repo, remoteUrl)` with the comment "The token must never be left
in the remote URL on disk"), which is good — but it only runs when the clone
succeeded, it happens *after* the key has already been through a URL, and it
does not touch the message that was shown to the user.

So the answer to "is there a way to secure this" is: yes, and the first step is
to stop using the address as a place to carry the key.

### 1.3 The seven layers

**1 · Do not put the key in the address at all.**
JGit takes the credentials separately:

```kotlin
git.cloneRepository()
    .setURI("https://github.com/owner/repo.git")   // clean
    .setCredentialsProvider(
        UsernamePasswordCredentialsProvider(token, charArrayOf()))
```

I tested this against the real repository before writing this proposal: the clone
succeeds, the push succeeds, and `.git/config` keeps the clean address with no
key in it. Same for fetch and push, which already have a `credentials(token)`
helper in `GitService.kt` — only the clone path is wrong.

**2 · Scrub the address on the way out, and keep scrubbing.**
Every message and every exception text passes through one function before it is
shown or stored:

```text
https://ghp_…@github.com/…   →   https://github.com/…
https://x-access-token:…@…   →   https://…
github_pat_…                 →   ***
```

Belt and braces: after any clone or push, read back `remote.origin.url` and
rewrite it if it holds a key. Then a regression test greps the whole `.git`
directory — and the app's data directory — for the key and fails if it finds it.
That test is the thing that stops this class of bug from coming back.

**3 · Keep it encrypted, and make the ciphertext useless off this phone.**
This is already done (`CredentialStore.kt`, AES/GCM with a key in the Android
KeyStore). Harden it:

- `setUnlockedDeviceRequired(true)` on the key (Android 9+): the ciphertext
  cannot be decrypted while the phone is locked.
- StrongBox when the phone offers it.
- Exclude the stored blob from Android auto-backup. A KeyStore key never leaves
  the device, so a backup of the blob is already useless — excluding it just
  stops it drifting into a Google account.
- Decrypt per operation and let the plaintext go. Never keep it in a field.

**4 · Never on the screen, never in a log.**
- The key field is masked and is never shown again after it is saved; the screen
  says "Connected as muffyuniverse" and nothing else.
- `FLAG_SECURE` on the connect screen, so a screenshot or the recent-apps
  thumbnail cannot catch it.
- Introduce a small `Secret` type whose `toString()` is `***`, and pass it around
  instead of `String`, so an accidental string template cannot leak it.
- The app currently logs nothing, which is the right amount. Keep it that way.

**5 · Only ever talk to GitHub.**
Before the key is attached to any request or any clone, check the host is
`github.com` or `api.github.com`. Otherwise refuse. Without this, a project whose
address was edited to point somewhere else — or a "Track" of a hand-typed
address — would hand the key to whoever owns that host.

**6 · Make the key as narrow as the job.**
The token you sent has eleven scopes:
`admin:org_hook, admin:public_key, admin:repo_hook, audit_log, delete:packages,
delete_repo, gist, notifications, repo, workflow, write:packages`.

`admin:public_key` lets its holder install SSH keys on the account, which is
account takeover by another route. `admin:org_hook` and `audit_log` are worse
than anything the app can use. The app needs exactly two:

| scope | why |
|---|---|
| `repo` | read, write, create, push, pull, clone — public and private |
| `delete_repo` | the Delete button |

So: **rotate the key you sent, and create one with only those two.** Two options:

- *Classic token, `repo` + `delete_repo`* — works today, no code needed, never
  expires, all-or-nothing across every repository on the account.
- *Fine-grained token* — "All repositories" with **Contents: read/write**,
  **Administration: read/write**, **Metadata: read**. Strictly narrower, but it
  expires (up to a year) and the app must handle expiry kindly.

I recommend the classic two-scope token now, because it needs no code and the
difference in blast radius between it and a fine-grained one is small once
`admin:public_key` is gone. Fine-grained can come later as a supported option.

**7 · Make revoking trivial.**
"Sign out" wipes the encrypted blob, scrubs any address that still holds a key,
and clears the in-memory copy. Settings shows when the key was last used, and a
line on how to revoke it on GitHub. Revocation is the real safety net: a key that
can be killed in ten seconds is a much smaller problem than one that cannot.

Later, if you want to stop typing long-lived keys into a phone at all, the same
app can use GitHub's **device flow** (enter a short code on github.com, the app
receives a revocable token) or a **GitHub App** with one-hour installation tokens.
Both remove the "long-lived key on the phone" problem entirely. I would not build
that now — it needs a registered OAuth app — but the storage layer above does not
change if we do.

---

## 2. The new shape

### 2.1 Terms

- **Project** — one GitHub repository plus one folder on the phone. The unit of
  everything: create, track, sync, delete.
  @ project can be deleted locally when you delete it as a file (but it gets deleted fully +remote only via delete button in settings)
- **Folder** — a directory inside a project. Nests freely. Purely local
  organisation, carried into git as directories.
- **File** — a note. A markdown file inside a folder.
@ should be possible to set any extention, but md is default

### 2.2 Data model

```text
Project(id, name, owner, repo, remoteUrl, createdAt, lastSyncAt)
Folder(id, name, parentId, projectId)     parentId null = project root
Note(id, title, body, pinned, updatedAt, folderId)
```

`Project.name` is the local display name and defaults to `repo`. Renaming a
project renames the local folder only — renaming the GitHub repository is a
separate (deferred) action.

### 2.3 Layout on disk

```text
files/
  projects.json                     the list of projects
  projects/<project-id>/            ← the git worktree, one repo per project
    .git/
    README.md                       written once at creation
    Shopping/                       a folder is a real directory
      Milk.md
    Work/
      Plans.md
```

This replaces today's flat `files/folders/<uuid>/` with `name` and `parent`
sidecar files. Two reasons:

1. The repository is now looked at **on GitHub**. UUID-named directories holding
   `name` and `parent` files are unreadable there; real directories with real
   names are not.
2. Nested folders inside one repository become nested directories in git, which
   is how git expects a tree to look.

**File names become the title.** `Shopping/Milk.md` rather than
`Shopping/8f1c….md`. The `id` stays in the file's header so history and
identity survive renames, and the header remains the authority: if a file is
renamed on GitHub, the app renames it back to match the title on next load
(counting as a local change).

Titles that collide get a suffix (`Milk (2).md`); characters that cannot appear
in a file name (`/ \ : * ? " < > |`) are replaced; an empty title becomes
`Untitled`.

### 2.4 What this drops

Folder order is already alphabetical today (`FolderStorage.kt:64` sorts by name),
and notes sort by pinned then time, both of which survive as file names and
header fields. So no ordering is lost.

---

## 3. Screens

### 3.1 Projects (was Files)

- Title **Projects**, one row per project, nothing else in the list.
- Each row: project name, a sync line — `in sync` / `2 to push` / `3 to pull` /
  `not connected` — and the repository name underneath.
  @ all should be connected by default, and just sync button to push (should always succeed)
- Tapping a row opens the project's folder tree.
- The `+` makes a **new project** (needs an account, see 5.1).
- A project's menu has **Delete** and *nothing that creates a nested project*.
 @ see above

### 3.2 Inside a project

- The top bar keeps its normal title and gains a **second, slim row**: the
  project's name on the left, and on the right the **sync button** with the
  state next to it. The row is present on every screen inside the project —
  folder tree, file editor, history — so the sync button is never hunted for.
- The existing commit button disappears; sync replaces it.
- Everything else stays: folders, files, search, pinning, versions.
- Search now spans **all projects** and shows the project name on each result.

### 3.3 Settings

- The **"Git projects" box is removed** entirely, together with the per-folder
  Attach / New repo / Clone / Detach row that went with it.
- The **GitHub account** box keeps: connected state, the account name, Sign out,
  the commit email — and gains:

  | button | what it opens |
  |---|---|
  | **Track** | the list of repositories on the account; those not yet on the phone can be pulled in |
  | **Delete** | the list of synced projects; choosing one deletes it here and on GitHub |
  @ instead of track we can set pull button that pulls a project from remote every time (overwriting the local version if it exists)

---

## 4. Sync

### 4.1 What the button does

1. **Commit** whatever is waiting, as the configured author.
2. **Pull** the remote branch, rebasing the local commits on top.
3. **Push** if anything is left to send.

So one press leaves the phone and GitHub holding the same tree, which is what
you asked for: the app's state is the account's state.

### 4.2 States shown on the row

| state | meaning |
|---|---|
| `in sync` | same commit here and there |
| `N to push` | local commits not on GitHub yet |
| `N to pull` | GitHub has commits the phone does not |
| `not connected` | local project with no repository yet |
| `offline` | the last check could not reach GitHub |
@ every commit is on sync, non of above statuses add any utility

The state is refreshed when a project is opened and when sync is pressed.

### 4.3 When the two sides disagree

Text files can usually be merged, but a silent merge that guesses wrong is worse
than a clear refusal. Proposed behaviour:

- Pull with rebase. If it applies cleanly, all good — no extra screens.
- If it conflicts, **abort the rebase, keep the local commit, change nothing on
  the remote**, and say plainly which files conflict and that the remote was not
  touched. The work on both sides is still there.
- Offer one explicit escape hatch on that message: *overwrite GitHub with my
  version*. It is destructive and says so.

This keeps the common case silent and never loses anyone's writing without being
told to.
@ too difficult, github is literally the projection of the local projects, they should win every conflict

---

## 5. The four project actions

### 5.1 Create a project (Projects → +)

1. Ask for a name, and public or private.
2. `POST /user/repos` on GitHub.
3. Create the local worktree, write `README.md`, commit, push `main`.
4. The project appears on the Projects screen.

Requiring an account is what makes rule 2 true — there is no local-only
project — so with no account connected, `+` offers to connect instead of
creating something that cannot be synced.
@ only public projects, with licence selection.

### 5.2 Track a project (Settings → Track)

The account's repositories, newest first, each showing:

- name, private or public, when it last changed;
- **Synced** if a project already points at it;
- **Track** otherwise, which clones it in and makes it a project;
- if the name matches a local project that has no repository, offer **link**
  rather than making a second copy.

Repositories with no commits yet cannot be cloned usefully, and are shown as
such with the suggestion to create them in the app instead.
@ only public projects, no name mismatch can happen - created projects are synced at birth (see above) - only invalid repetative name (and no (2) names - we should show a clear error message). when githab account is changed local projects are switched as well (we should keep projects in invisible per account folders: account/projects/files, so that when you switch out of account its projects are still on the system but unavailiable and become availiable once you switch back).

### 5.3 Delete a project (Settings → Delete, and the project's own menu)

One confirmation naming both things that die:

> Delete **Shopping**?
> This removes the folder from this phone **and** the repository
> `muffyuniverse/shopping` from GitHub. This cannot be undone.

Delete = local folder, remote repository, and the entry in `projects.json`.
A repository that is already gone remotely is not an error — the local side is
still removed and the result says so.
@ see answers above

### 5.4 Sync (inside a project)

Section 4.

---

## 6. Migration from today's app

The storage layout and the model both change, so this needs care. Nothing is
deleted on the first run after the update.

1. **Read the old tree** with the existing reader (`FolderStorage`, `NoteCodec`),
   which already understands every earlier format.
2. **Every first-level folder becomes a project**, keeping its name. Nested
   folders and their files move inside it as real directories, with files named
   after their titles.
3. **A project's repository is the old first-level folder's repository**, if it
   had one — so its history is kept. Repositories that lived on *nested* folders
   cannot be part of their parent's repository by definition, so they are moved
   aside to `files/legacy-repos/<folder-id>/` rather than deleted, and the
   migration report says so.
4. **Files at the very top level** (outside any folder) go into a project called
   **Notes**, created for them.
5. **`projects.json`** is written from what was found, and the old directories
   are left untouched until the new layout has been read back correctly once.
6. A one-time report is offered: what became what, and where the set-aside
   repositories are.

An old project with no repository is `not connected` and gets connected by
creating a repository for it or by linking an existing one — the same Track
screen, with the link option.

---

## 7. Decisions I need from you

| # | question | my recommendation |
|---|---|---|
| 1 | **File names on GitHub**: title-named files (`Milk.md`) or keep ids? | title-named — the repository is meant to be read |
@ title is file name
| 2 | **Sync** really is commit + pull + push, not push only? | yes, two-way — that is what "same state" needs |
| 3 | **Conflict**: refuse and report, or auto-merge text? | refuse and report, with an explicit "overwrite GitHub" |
| 4 | **Delete**: always both, or a checkbox for the GitHub side? | always both, with a confirmation that names the repository |
| 5 | **Old nested repositories**: set aside (safe) or drop (simple)? | set aside |
@ drop
| 6 | **The key**: rotate to `repo` + `delete_repo` now? | yes — the token you sent has eleven scopes, three of them dangerous |
@ but we need to delete repos as well
| 7 | **One account**, or several later? | one now; the model has room for more |
@ one active, several in store (every new account should make a folder for itself). And we can use sign-in, switch, forget - account buttons.
| 8 | Should the Projects row show sync state, or only inside a project? | both — it is the cheapest way to see which project needs attention |
@ in can show how many changed files staged - many files on stage means we need to sync (human logic)

---

## 8. How it will be tested

- **Unit**: the address scrubber; title→file-name (collisions, illegal
  characters, empty, very long); folder tree ↔ directory tree round trip;
  sync state from two commit graphs; migration from each old layout.
- **The leak test**: clone and push against a real repository with a fake key,
  then grep the project directory and the app data directory for it — must find
  nothing. Also assert the clone message contains no key.
- **The tour** (already working in CI): the Projects screen, creating a project,
  the sync row, Track and Delete. These need a key in CI, which is the one place
  I would not put yours — a dedicated throwaway account is the right answer, and
  I would want your agreement before wiring it.
- **On the phone**: install over the current version and confirm the migration
  keeps every file, then create, sync, track, and delete one real project.

---

## 9. Order of work

1. **Key safety** — clone through the credentials provider, the scrubber, the
   leak test, `FLAG_SECURE`, backup exclusion. Small, and it stops the bleeding.
2. **Storage 2.0** — projects as worktrees, real directories, titles as file
   names, migration and its tests.
3. **Projects screen** — rename, project rows, create project, the project row
   with sync inside a project, delete.
4. **Sync** — commit + pull + push, states, conflict reporting.
5. **Track** — the remote list, clone-in, link-to-existing, delete on both sides.
6. **Settings** — drop the Git projects box, add Track and Delete.

Steps 1 and 2 are the ones that cannot be done halfway, and 1 is independent of
everything else — so it can ship on its own while you look at the rest.
