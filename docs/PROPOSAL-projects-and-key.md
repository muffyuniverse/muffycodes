# The design — projects, accounts, files, and history everywhere

Status: **settled and being built**. This is the reference the code answers to.
Where an earlier proposal disagrees, this document is right.

## 1. What the answers settled

| the call | what the app does |
|---|---|
| "ok app's own files should be invisible" | a single rule, §3: **everything you made is visible; everything the app made is not.** The project folder shows your files; `project.json` and the app's other bookkeeping go back to being hidden. |
| "we have move functionality for files and folders that are user generated" | moving is an ordinary action on **any user-made file or folder**, and app-made things cannot be moved, renamed or deleted at all |
| "app-wide git that autocommits every change outside project repos … any file or folder state can be rolled back, not just inside a repo (← and →)" | **§4 is new**: the account's whole tree is a local git repository, committed automatically, so your local files and even a deleted folder have history and the arrows work everywhere |

---

## 2. The shape — the account is the list of projects, and a project *is* the repository

```text
files/
  accounts/
    muffyuniverse/                 the ACTIVE account — the root, and the list of projects
      .muffy/                      invisible; the app's own bookkeeping
        pins.json                  the pinned flags
      model_conf.json              invisible; the model the agent talks to
      .gitignore                   invisible; github's, once a project is cloned into it
      Shopping/                    a PROJECT — and at the same time the REPOSITORY
        .git/                      the history, and what sync pushes
        .git/info/exclude          invisible; git's own local list — keeps the app's two folders out
        agent/                     invisible; the record — what the model was sent and answered
        LICENSE                    seeded by GitHub
        README.md                  seeded by GitHub
        Milk.txt
        Photos/
          Shop.png
      Work/                        another project — its own repository, its own history
    someoneelse/                   invisible while another account is active
```

- **There is no `projects/` folder and no `repo/` folder.** The root of an account
  *is* its list of projects, and a project's folder *is* its repository: its
  `.git` and the person's files live together, so the thing on
  GitHub is the folder they are looking at.
- **A project is a folder that holds a repository.** That is the definition, and it
  is enforced: a folder at the root with no `.git` is only a folder, and the app
  does not draw a project row for it.
- **A project's name is its repository's name.** Renaming renames the repository
  on GitHub too.
- **The app's own things live inside the project and are kept out of git**:
  `agent/` holds the record, `.muffy/` the flags. Two lines in git's own
  `.git/info/exclude` — the per-clone list that is never committed and cannot be
  pushed — are what make that true. They are a rule of this phone's app, not a fact
  about the person's repository, so the person's `.gitignore` is untouched and a
  project that has been running an agent still has nothing to send.
- **Files are pristine** — nothing is added to what you type. A file's identity
  is its path; renaming renames the file and git sees a rename.
- **The extension is just the part of the name after the dot.** Default `.txt`.
- **One name shape, for everything a person makes:** lowercase letters and
  digits, joined by single underscores — `My Shopping List` is written
  `my_shopping_list.txt`, and an imported `Holo and Lawrence, Kraft - Spice and
  Wolf.jpeg` arrives as `holo_and_lawrence_kraft_spice_and_wolf.jpeg`. The
  extension survives, lowercased. A name here is a path component in a git
  repository and in the folder tree that *is* the account, so the rule lives in
  one function (`Workspace.cleanName`) and every way in — typing, renaming,
  importing a file or a whole folder — goes through it. When it changes a name it
  says so, because a file that arrives under an unexpected name reads as a file
  that did not arrive.
- **A project's folder is the exception**, and deliberately: it is named after its
  repository, which may already exist on GitHub. The app never renames somebody's
  repository on the phone.
- **A local project** is the same thing with no remote: its own folder, its own
  repository, its own versions — and nothing to push, which is why Sync is not
  offered on it.

---

## 3. The rule that decides what is visible

> **Everything you made is visible. Everything the app made is not.**

| | visible on screen | can be renamed / moved / deleted |
|---|---|---|
| your files and folders, anywhere in a project | **yes** | **yes** |
| a project's folder — its row is the project list | yes | renamed with the project, never moved or deleted as a file |
| `agent/` (the record), `.muffy/`, `.git/`, `.gitignore`, `.git/info/exclude` | **no** | **no** |
| `LICENSE`, `README.md` | yes — GitHub created them, but they are files of yours to handle | yes |

- Hidden things are hidden by **name and by rule**: the app's own files live
  inside `.muffy/` and `.git/`, and the app never draws them.
- Nothing the app owns appears in a file list, a search result or a move
  picker, and no command can target one. That is a guarantee, not a convention.

### Moving

Moving is an ordinary action on any file or folder **you** made — and only on
those. There is no "move into the repository" and no "keep only on this phone" any
more: a project's folder *is* its repository, so any folder the picker offers is
inside one.

A move is the one command that can cross from one repository to another, so **both
sides are committed**: the destination records the file as tracked, and the source
records that it is gone. Committing only the destination is how GitHub ends up
holding a copy this phone no longer has.

| where it went | which history records it |
|---|---|
| within one project | that project |
| between two projects | both of them, one each |
| a folder outside every project | nothing — there is nothing there of a person's to version |

---

## 4. The history — one per project, and none above them

**Every project's folder is a git repository, and every change you make inside it is
committed to that repository automatically.** It is the same repository sync pushes,
which is why the arrows and GitHub's history are the same list and cannot disagree.

There is no account-level history. There used to be one — an account-wide repository
that versioned everything outside a project — and it was removed with the two-level
layout: once every folder under the account is a project, everything a person can
touch is already inside a repository, and the second history had nothing left to
cover. One history per project, one answer to "where did this version come from".

### What it covers

| | which history keeps it |
|---|---|
| every file and folder inside a project | **that project's repository** — the one sync pushes |
| the app's own things: `agent/`, `.muffy/`, `.git/` | none — they are in git's local exclude list and are not the person's work |
| a project's own name | none — the folder is named after the repository, and renaming renames the repository on GitHub |

### When it commits

- **when the editor saves** — saving is a button, and pressing it writes the text
  and commits it as one version. Consecutive changes within a few seconds fold into
  one, because a commit takes everything that is waiting
- **when the tree changes** — creating, renaming, moving, importing or deleting a
  file or a folder. Creating an *empty* folder is not a version: git stores
  content, so there is nothing to commit until something is in it, and the app says
  nothing rather than inventing a marker file in the person's repository
- **when a project arrives** — the clone is a repository already, so its history
  starts with whatever the repository held. The app's two exclude lines are written
  after the clone (git will not clone into a non-empty folder) into a file that is
  not part of the repository, so a project that has just arrived has nothing waiting

A save's commit says one word — `Saved` — because the person never types it and
every row of a file's history would otherwise repeat the same sentence; nothing on
the screen shows it (`appVoice`). A change to the tree says what happened, in the
same plain style as sync's: `Created Drafts/`, `Moved Shopping-list.txt into
Drafts/`, `Deleted Drafts/`. Empty commits are never made. The one message a
history ever *shows* is the note typed when syncing.

### What it buys you

- **The arrows work everywhere you can go.** Every file in a project has versions
  and a restore, not only the ones Git already tracked.
- **A deleted folder is no longer gone.** Deleting `Drafts/` commits the deletion,
  so the folder and everything in it can be brought back.
- **A deleted file is no longer lost either.** Its row is gone, so the add button
  offers **Restore deleted…**: the files the folder's own history holds and the
  folder no longer does, put back byte for byte — a version of a picture is not
  text, and writing a UTF-8 reading of it back would destroy the picture.
- **Folder organisation is versioned.** Renames and moves are reversible, which is
  exactly where people regret things most.
- **It is invisible until wanted.** No status, no count, no badge. The project
  row's `3 changed` still means "unsynced to GitHub" and nothing else, because a
  change is committed the moment it happens and being committed is not being sent.

### What it costs, stated plainly

- **Disk.** Each commit stores only what changed, but an image edited ten times is
  stored ten times, and a project's `.git` grows with it. For notes this is small;
  it is not free.
- **One repository per project.** Two projects cannot share a history, and a file
  moved between them has a version in each — which is the honest record of a move,
  not a duplicate.
- **What is not in a project is not versioned.** The account root holds projects and
  nothing else, so there is nothing there for a history to protect.

---

## 5. Accounts — Add, Switch, Remove

The active account is shown **above** the controls:

```text
  Signed in as
  muffyuniverse
  muffyworld365@gmail.com          ← the commit identity, editable

  [ Add ]    [ Switch ]    [ Remove ]
```

- **Add** — sign in with a key; that account becomes active.
- **Switch** — a short list of the accounts stored on this phone; picking one
  makes it active and the whole screen follows.
- **Remove** — one command, whose dialog carries the choice:

  > **Remove muffyuniverse from this phone?**
  > ( • ) **Keep its projects here** — signing in again brings them back
  > (   ) **Delete its projects from this phone too** — the repositories stay on
  >       GitHub, and Add brings them back
  >
  > `[ Cancel ]` `[ Remove ]`

  Either way the account's key is gone from this phone. The difference is whether
  the local tree — and with it every project's history — is deleted or merely left
  unreachable until the same account signs in again.

An account stores its own **commit identity** (name and email, editable,
defaulting to the GitHub login and its address).

---

## 6. The Projects screen

One row per project of the active account:

```text
┌──────────────────────────────────────────┐
│  muffyuniverse                       (⌄) │   account — tap to Add / Switch / Remove
├──────────────────────────────────────────┤
│  Shopping                             ⋮  │
│  3 changed                               │
│  ─────────────────────────────────────   │
│  Recipes                              ⋮  │
│  up to date                              │
└──────────────────────────────────────────┘
                                            (＋)
```

- The second line is **`N changed`** — what the project has that GitHub does not —
  or **`up to date`**.
- **Tapping a row enters the project** — the folder view of §3, with the slim row
  on top.
- **No separate Pull or Delete screens exist.** They live here.

### The 3-dots menu on a row

| item | what it does |
|---|---|
| **Sync** | pushes — the same action as the button inside, so you never have to enter a project just to push it |
| **Rename…** | renames the project **and its repository on GitHub** (the folder's name and the repository's are one name) |
| **Delete…** | one command; the dialog carries the choice, below |

> **Delete `muffyuniverse/shopping`?**
> ( • ) **From this phone only** — the repository stays on GitHub, and the add
>       button brings it back
> (   ) **Everywhere** — the copy here and the repository on GitHub. Cannot be
>       undone
>
> `[ Cancel ]` `[ Delete ]`

A repository already deleted on GitHub is not an error — the local side goes.

### The add button

`＋` opens **one window with a list**, not a dialog of choices:

```text
┌──────────────────────────────────────────┐
│  ＋ New project…                          │   ← always first
├──────────────────────────────────────────┤
│  recipes            last changed 3 days  │
│  shopping           last changed today   │  ← on this phone
│  thesis             last changed 2 weeks │
└──────────────────────────────────────────┘
```

- **`＋ New project…`** asks for a name and a licence (13 from `GET /licenses`,
  default `gpl-3.0`), creates the repository with `auto_init` +
  `license_template`, and clones it in. GitHub writes `LICENSE` and `README.md`;
  the app never writes a README.
- **＋ New local project…** makes one with no remote: its own folder, its own
  repository, its own history, and nothing to push — which is why Sync is not
  offered on it.
- **Everything below it** is the account's public repositories, newest-changed
  first, with the ones already here marked. Picking one pulls it in: not here →
  cloned; already here → asks once, *"Replace the copy on this phone?"*, then
  fetches and resets to the remote branch. **The app's own folders survive** —
  `agent/` and `.muffy/` are ignored by git, so the reset cannot reach them.

---

## 7. Inside a project

Every screen carries a **slim row** under the top bar:

```text
muffyuniverse · Shopping              3 changed   [ Sync ]
```

- The old commit button, its badge and the `git:` subtitle are gone.
- Folders nest freely; the editor, pinning and the version history stay — and
  history now covers local files too (§4).

### The editor's row, left to right

```text
[<-]  milk.txt            History  [ Save ]  ...
```

- **`<-` leaves and discards.** Saving is not a pause in typing any more, so back
  means back: what was typed and not saved does not reach the file.
- **The name is the title, and the title is editable.** Renaming is not a separate
  command in a menu — changing a word you are already looking at should not need a
  window. It commits on "done" and on leaving the field, never on a keystroke,
  because the name rule would otherwise rewrite the name under the finger.
- **History** opens the versions, where `<-` leaves them alone and **Select** puts
  one back — and returns to the file, because a person who has found the version
  they wanted has finished with the history.
- **Save** is a button with a background, because it is the action of the screen.
  It is disabled until something has changed.
- **Search is scoped to this project** — one button in the top bar, covering both
  the tracked and the local parts of the project. There is no search across
  projects: the Projects list is that index already.

---

## 8. Sync

**A sync is a push, and nothing else.** Every change in the project is already
committed when it happens — a save, a rename, a move, an import, a deletion — so by
the time Sync is pressed there is nothing left to decide about.

1. Commit anything that somehow slipped past — a file half-written by another app,
   a deletion made outside this app. This is a guard, not the design: a push that
   silently leaves a change behind is worse than a commit nobody asked for.
2. **Force push.** Local always wins; it cannot fail on a conflict. The message is
   written from **the names that changed** — `Milk.txt, Shop.png`, `+3 more` past a
   few.

The history's arrows move between **changes**, not between pushes: a version exists
the moment you save, and an undo does not wait for a network. The app's own folders
are not part of the push — they are not the person's work, and git is told so in the
project's `.git/info/exclude`.

**Online is required.** With no network, sync does nothing and says so plainly —
nothing lands half-done, and the changed count stays as it was. A repository
deleted on GitHub gets the same plain message, with a hint that it is gone.

---

## 9. Files

- Default extension `.txt`; the extension is just the part of the name after the
  dot, and **an extension is also how the app decides what a file is**:
  - **text** (`.txt`, `.md`, code, anything not listed below) → opens in the
    editor, no preview of any kind
  - **images** (`.png`, `.jpg`, `.jpeg`, `.gif`, `.webp`, `.bmp`) → shown as the
    picture, not editable; rename, move and delete only
  - **anything else** → named as unknown; rename, move and delete only
- A file on disk is exactly what you typed — no headers, no front matter.

---

## 10. Settings — accounts, themes, and how much history is kept

```text
  Signed in as
  muffyuniverse
  muffyworld365@gmail.com

  [ Add ]   [ Switch ]   [ Remove ]

  ──────────────────────────────────────────

  Theme                                Light ▸ Dark
  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐
  │Milk│ │Cara│ │Gold│ │Brow│ │ …  │     ← a scrollable row
  └────┘ └────┘ └────┘ └────┘ └────┘
       ↑ tap a swatch to select it
```

- **Themes are a scrollable row of swatches** — tap one to select it; the
  selected swatch is the framed one. Beside the row, one **light/dark switch**.
- The old "Git projects" box is gone, and so is Pull, Delete and Sync — they live
  where the projects are.

---

## 11. The key — already built and released

Order-of-work item 1 is done, green and shipped as **v47**: the key travels as
credentials and never inside an address · messages and exceptions are scrubbed
before they can be shown or stored · only `github.com` is ever given the key · a
repository already holding a leaking address is healed when opened · the screen
where a key is typed refuses screenshots · the encryption key needs an unlocked
phone · backups cannot carry the tokens · a test runs a clone and a push with a
fake key and fails if git wrote it down in any file.

The history of §4 is **the project's own repository**: it has no second remote and
no second key, so it adds no way for anything to leave the phone.

---

## 12. Not here, on purpose

No migration — the app is rapidly developed, so the new storage starts empty and
nothing old is carried over. No sync states, no conflict screens, no commit
dialog, no front matter in files, no previews of text, no private repositories,
no search across projects, no history pruning.

---

## 13. Order of work

1. **Accounts** — `accounts/` folders, Add / Switch / Remove with its two
   outcomes, the key and commit identity per account.
2. **Storage** — the account root *as* the projects list, a project's folder *as*
   its repository, hidden `.muffy/` and `agent/`, titles as file names, `.txt`
   default, extensions deciding how a file opens, paths as identity, and the
   visibility rule of §3 enforced in one place.
3. **The history** — each project's repository committing on every save and every
   tree change, with the app's own folders ignored, and the arrows and restore
   reading through it.
4. **Screens** — the root, the project list with rows, `⋮` and `＋`, the project
   folder view with the slim row and the crosses of §3.
5. **Sync** — messages from changed names, force push, online-required failures,
   reachable from both the slim row and the row's `⋮`.
6. **Settings** — Add / Switch / Remove with the active account above them, the
   theme swatch row, and the two history lengths.

Steps 1–3 are the load-bearing ones; 4–6 land on top of them.

---

## 14. The history length (added at build time)

**One setting, one sentence:** how many changes are kept in a project's history. It
applies **at the next change** rather than when the number is typed — a history that
shortened itself the moment a number was typed would destroy commits before their
replacements existed. There is one history per project and no other, so the number
bounds every history there is.

| it bounds | what that means |
|---|---|
| each project's repository | its commits, shortened after the next change |

An empty field means **keep everything**, which is the default. When a length is
set, the oldest commits are dropped by rebuilding the chain from the newest ones
— each rebuilt commit carries the identical tree, so not one byte of the working
copy changes, and only how far back the history goes.

Pruning happens on the same background worker as the commit that triggered it,
so a save never waits for it. It is the one place where history is destroyed on
purpose, and it destroys only the oldest end of it.

## 15. The test account (how the second half is tested at all)

Most of this design only happens to somebody who has signed in. A test run must
never hold a GitHub key, so for a long time none of it was tested — and on a real
install it could only be reached by hand.

The app now asks a **`GitHub`** for what it needs: who the key belongs to, the
account's repositories, and making, renaming and deleting one. There are two of
them.

| | talks to |
|---|---|
| **`HttpGitHub`** | GitHub itself, over HTTPS |
| **`DiskGitHub`** | a folder of **real bare git repositories** on the phone |

The second one is not a stand-in for git. Every clone, commit, force-push and
reset is a real one, through the same JGit code, against a real repository; the
refs that move are real refs. What it leaves out is the network and the key.

A **debug build** can sign in as the test account, whose GitHub is that folder.
A **release build cannot**: the gate is read from the build type, and a build that
may not keep a test account also refuses to *act as* one — a stored account that
claims to be one is treated as an ordinary account and talks to GitHub.

With it, the promises above are walked by 8 feature files that drive the real
ViewModel — visibility, files, accounts, projects, sync, history, retention and
key safety — and by the screenshot tour, which signs in as the test account, makes
a project, writes a file into it, syncs it and opens the history, drawing every
screen on the way.

Every one of those steps goes through the code the release ships. One switch is
different, and the switch cannot be reached from a release.
