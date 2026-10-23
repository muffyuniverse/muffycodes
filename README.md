# Muffy Codes — an Android notes app

just another ai app

A small, dependency-light note-taking app written in **Kotlin** with **Jetpack Compose**
and **Material 3**, with **git built in**: every folder can be its own repository,
and the history of a file can be browsed from inside the app.

## Features

### Files and folders

- **List screen** — every file as a card, pinned files first, then most recently
  updated. Each card shows the title, a two-line preview of the body and the
  last-modified time.
- **Editor screen** — title field plus a multi-line body field. Saving happens
  automatically when you leave the screen (back arrow or check mark).
- **Rename** — file row → ⋮ → Rename opens a small dialog to change the name
  without opening the file.
- **Pin / unpin** — tap the star on a file to keep it at the top of the list.
- **Delete** — the bin icon in the editor toolbar removes a file, after a confirmation.
- **Search** — the field at the top searches every file in the app, not just the
  current folder. Results show the folder path they came from.
- **Nested folders** — a folder can hold sub-folders and files, to any depth.
- **Create, rename, move, delete folders** — a folder can never be moved into
  itself or one of its own sub-folders, which would create a loop. Deleting a
  folder removes it, its sub-folders and **all the files inside them**.
- **Navigation** — tapping a folder opens it. The back arrow in the top bar and the
  system back button both move up one level, and the breadcrumb in the title bar shows
  where you are. Going up from a top-level folder returns you to the main screen.

### Git, built into the app

The app talks to git directly, so no extra tools are needed on the phone.

- **Every folder can be its own project** — a folder holds a git repository, and the
  files inside it are the tracked files. Different folders can point at different
  GitHub repositories.
- **Commit button** — the top bar has a commit button showing how many changes are
  waiting. It stages everything in the current folder (including deletions) and
  commits it with the message you type.
- **Your repositories** — in the settings you can create a new repository on GitHub
  and attach it to a folder, or clone an existing one into a folder.
- **Push and pull** — the current folder can be pushed to its remote and pulled again.
- **The project is shown in the top bar** — the name of the project the current
  folder belongs to appears in small text under the title, next to the breadcrumb.
- **Version browser** — the clock button in the top bar opens the history of the
  current folder; the ⋮ menu of a file offers "Version history" for that file. The
  **back and forward arrows** in the top bar walk through the versions the way a
  browser walks through pages: back steps to the previous version, forward returns to
  a newer one. An old version of a file can be restored, left unstaged so you can
  review it before committing.

The git work is done by [JGit](https://www.eclipse.org/jgit/), a pure-Java git
implementation, so there is no native code to build and no separate git binary to
install on the phone.

### Appearance

- **Settings** — the gear icon in the top bar opens the settings screen.
- **Five themes** — Milk, Caramel, Gold, Brownie and Dark Chocolate. Each is a full
  Material 3 colour scheme with its own light and dark variant, so the palette runs
  from the pale Milk theme to the near-black Dark Chocolate one.
- **Dark variant** — a switch next to the theme list turns the chosen theme into its
  dark version. The setting is remembered across restarts.

### Storage

- **Offline** — files and folders are kept in the app's private storage. No network
  is needed to use the app; only the git features use the internet.
- **One file per note** — every file is a small markdown document with a small
  header. That is what lets git track each file on its own, so the history of a
  file is the history of that file.
- **Safe writes** — every file is written to a temporary file and renamed over the
  old one, so an interrupted write cannot corrupt it.
- **Upgrade-safe** — the old single-JSON layout is migrated automatically on the
  first launch of this version, and the old file is kept as a backup.

## Installing

The APK is signed with a release key, so it installs directly:

```bash
adb install -r Notes-1.3.apk
```

Or copy the file to the phone and open it (you may need to allow "install from
unknown sources" for your file manager).

Minimum Android version: **Android 8.0 (API 26)**. Target: **Android 15 (API 35)**.

> API 26 is needed because the git implementation uses `java.nio.file`.

## Using git from the app

1. Open **Settings** (the gear icon) and tap **Connect GitHub**.
2. Paste a [personal access token](https://github.com/settings/tokens) that has the
   `repo` permission. The token is encrypted with a key from the Android keystore
   and kept only on the device.
3. Find the folder you want to turn into a project and tap **New repo** (to create a
   repository on GitHub and attach it) or **Clone** (to bring an existing repository
   into the folder).
4. The commit button in the top bar now shows the changes waiting in that folder.

## How it is built

| Piece | Choice |
| --- | --- |
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose (BOM 2024.12.01), Material 3 |
| Architecture | Single-activity, `AndroidViewModel` + Compose `State` |
| Storage | One markdown file per note, atomic writes |
| Git | JGit 6.10 (pure Java, no native code) |
| GitHub | The REST API, called directly over HTTPS |
| Min / target SDK | 26 / 35 |
| Build | Gradle 8.13, AGP 8.7.3, JDK 17 |

### Source layout

```text
app/src/main/java/com/example/notes/
├── Note.kt              the note data class
├── Folder.kt            the folder data class
├── FolderTree.kt        tree rules: descendants, path, cycle guard, back target
├── NoteFile.kt          the on-disk format of a single file
├── NoteCodec.kt         the old JSON format, and its migration
├── FolderStorage.kt     the on-disk layout: one directory per folder
├── NoteStore.kt         the Android-facing storage wrapper
├── GitService.kt        git itself: commit, log, tree, restore, push, pull
├── GitProjects.kt       the git side of a folder
├── GitHubClient.kt      the GitHub REST API
├── CredentialStore.kt   the encrypted GitHub token
├── ProjectStore.kt      which project each folder is attached to
├── NotesViewModel.kt    state, folder CRUD, moves, navigation, git
├── NotesListScreen.kt   the list UI: folders, files, search, commit button
├── NoteEditorScreen.kt  the editor UI
├── VersionBrowserScreen.kt  the history browser with back/forward arrows
├── Dialogs.kt           name, rename, commit, confirm and move-target dialogs
├── AppTheme.kt          the five themes, light and dark
├── ThemePreferences.kt  remembers the chosen theme
├── SettingsScreen.kt    the settings screen
└── MainActivity.kt      activity, navigation and dialog wiring
```

The tree rules (`FolderTree.kt`), the file format (`NoteFile.kt`), the storage
layout (`FolderStorage.kt`) and all of the git work (`GitService.kt`,
`GitProjects.kt`) are free of Android dependencies, so they are unit-tested on a
plain JVM.

### Building it yourself

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon assembleRelease
```

The output lands in `app/build/outputs/apk/release/app-release.apk`.

The release build is signed with the key described in `keystore.properties`, which
is **not** committed. Copy it from the machine that built the app, or create your
own:

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

`local.properties` holds machine-specific settings (the SDK path) and is not
committed either. A build on the continuous integration takes the key from the
environment instead: `MUFFY_KEYSTORE_BASE64` (the key as base64),
`MUFFY_KEYSTORE_PASSWORD`, `MUFFY_KEY_ALIAS` and `MUFFY_KEY_PASSWORD`.

### The build on GitHub

Every push to `main`, and every pull request, is built and tested by
[GitHub Actions](.github/workflows/build-and-release.yml):

1. the unit tests run
2. the release is built and signed
3. the signature and the alignment of the APK are checked
4. a release is published with the APK attached, and a tag (`v1`, `v2`, …)

A pull request is built and tested, but never published. A release can also be
made by hand from the **Actions** tab, with a version that is chosen there.

The version code goes up with every build, so each release installs over the one
before it. The build **refuses** to make a release when there is no signing key,
rather than quietly producing an APK that cannot be installed.

### Pictures of the app

The test run draws the screens it builds and keeps the pictures. There is no
device and no emulator: a screen is built on the JVM, drawn to a bitmap and
written to a file, which costs milliseconds instead of the six minutes a real
Android machine costs to boot.

The tour walks **the whole app**, including the half that needs an account. It
signs in as the test account — a debug-only account whose GitHub is a folder of
real git repositories — makes a project, writes into its worktree, syncs it and
opens the history. The pictures arrive with every bundle, beside the APK and the
list of changes.

They answer one question: **is the feature there and does the screen render.**
They do not answer "does it look right" — a JVM draws its own fonts and spacing,
and that question belongs to your eyes on a real install, which `bundle` puts in
the Downloads folder for you.

### The suite has a budget

Adding tests must not add wall time in proportion: compilation is shared, a
runner is paid for once, and a small test costs a fraction of a second. Every run
checks that this is still true and prints a table — the suite's total, the average
test, the largest class's share, and whether sharding would pay. The numbers it is
held to live in `tools/scaling-budget.txt`, next to the reason for each one.

It never fails a build. A slow suite is still a suite that passed, and a check
that blocks on speed only teaches people to skip tests. It says what drifted and
what to do: split the class that has become the suite, narrow a test that grew
into a scenario, fix a wait generous enough to hide a hang, or turn sharding on.

### Looking at the app is a person's job

Nothing here checks how the app looks. Colours, spacing and anything drawn by
hand are judged by installing the app on a real phone, which is what actually
finds a visual problem — an automated comparison can only ever assert what its
author already believed, and would never say that a screen is ugly, cramped or
not what was wanted.

So the pictures the tour takes are **for your eyes only**: they are tooling, run
by hand when you want them, and they never fail a build.

### Tests

```bash
./gradlew --no-daemon testDebugUnitTest
```

54 tests cover the file format (round-trip, tricky bodies, missing headers), the
storage layout (folders as directories, deletions, flat layout), the folder tree
rules (descendants, paths, cycle detection, back navigation), the git behaviour
(committing, history per file, restoring an old version, reading a tree at an
old revision, remotes) and the theme definitions.

## Notes on the signing key

The keystore and its passwords are **not** in this repository. If you publish the
app, use your own key and keep the password out of the source.
