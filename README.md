# muffycodes

just another ai app

---

# Muffy Codes — an Android notes app

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

There is a second workflow, `screenshot-tour.yml`, that walks through the app
on an emulator and takes a picture of every step. The pictures are put in the
run, so they can be looked at and downloaded.

The tour is a list of steps in a plain text file, so it can be changed without
touching any code (`tools/default-tour.txt`):

```
tap    X Y   [name]        a tap on a point of the screen
taptext  words [name]      a tap on the text that is shown
swipe  X1 Y1 X2 Y2 [ms] [name]
text   words              typing into the field that is open
key    KEYCODE            a key, e.g. BACK, ENTER
shot   name               a picture without touching the screen
wait   seconds            a pause
```
A tap on a point is simple but breaks when the layout moves. A tap on a text
keeps working, because the app is asked where that text is, so both kinds are
allowed in the same tour. The points are written for a screen of 1080 by 1920
and are scaled to the screen that is really used.

The tour runs when it is asked for by hand, once a week, and whenever the app
or the tour itself is changed. There is also `look-at-app.yml`, which writes
down the names that are on the screen at every step, so that a tour can be
written with the names the app really shows instead of guesses.

### Checking what was drawn

The names and the places of the things on a screen are read from the app
itself, and that is what the tour taps and what it checks. What the names
cannot say is whether anything was actually **drawn**: the list of names has no
colours in it, so a theme that never applied, an icon that failed to draw and
text the same colour as its background all look perfectly fine to it.

`tools/check-pixels.py` reads the pictures and asks three questions that can be
answered without knowing what the app is meant to look like:

- is the picture **flat**? (nothing was drawn)
- does text **stand out** from its background? (at least 3:1)
- do the **themes differ** from each other? (only asked with `--themes`)

The checks are on properties, never on exact colours, so a different emulator,
processor or font cannot make them fail for the wrong reason.

The theme tour (`tools/theme-tour.txt`) is what makes the third question
answerable: it takes a picture of **the same screen in every theme**, so any
difference between those pictures is the theme and nothing else. It runs in the
same emulator session as the main tour, into a folder of its own.

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
