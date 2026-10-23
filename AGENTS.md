# How this project is tested

**The law, as the operator set it on 2026-10-22.**

## The container writes code. GitHub builds and tests it.

This container exists to research and to write code. That is all.

- **Do not build the app here.** A local build proves nothing about the app.
- **Do not try to run the app here.** It cannot run: this is arm64 under
  proot, with no KVM, and Robolectric's TLS library is not published for
  aarch64.
- **A local run is never evidence.** Never report work as verified, tested or
  done on the strength of something that ran locally.

**Everything is built and tested by GitHub workflows.** Testing here is not
merely discouraged, it is unnecessary: the whole workflow leans on the remote
tests. The local machine runs nothing — the Android toolchain is not installed
here at all. There is no `gradlew` to reach for, no SDK, no JDK for it. This is
not a compromise; it is the design. A build on this machine would be slower
than GitHub's, would prove nothing about the app, and would contend with
everything else the container is doing.

## `ci` is how you test

Because nothing builds here, "does it work?" is answered by one command:

```sh
ci                 # push if there is anything to push, then watch and report
ci --watch-only    # just watch the run already going
```

`ci` hands the branch to GitHub, waits, and — when a run fails — goes into the
failing job's log and prints the block that says what went wrong, not merely
which step failed. Its last line starts with `RESULT:`, so a script can act on
it. Run it in the background and read the answer later:

```sh
nohup ci > /tmp/ci.log 2>&1 &
```

It is the only test runner there is. `tools/watch-run.sh` remains the
detached, fire-and-forget variant of the same idea.

## `bundle` is how the phone gets the build

A build only exists on GitHub until it is delivered. `bundle` brings it to
the phone's Downloads folder — the APK, the list of changes and the pictures:

```sh
bundle             # the latest release, with everything on it now
bundle --dev       # the newest development build
```

`ci` runs it on its own when a build passes on `main` or `development`, so a
successful run means the phone already has it. The renders of a real install
land on a release minutes later, in a run of their own; running `bundle`
afterwards grows the folder with them.

## The loop

1. Write the code.
2. Push a branch and open a pull request. The workflows run the tests; a pull
   request never publishes a release.
3. Read the CI result with `ci`. Fix, push again.
4. Merge to `main` only when the tests pass. `main` publishes the signed APK.

A pull request is the unit of verification. Nothing else is.

## Who tests what

| kind of thing | who | where |
|---|---|---|
| pure logic: parsing, file names, paths, tree maths, sync, migration | tests | in the workflow, as fast JVM unit tests |
| whether a screen offers what it should | Robolectric tests | in the workflow, on the CI's x86_64 |
| **everything that needs a signed-in account** | **feature tests + an emulator walk** | **the test account (below) and a debug build of the same tag** |
| **whether it looks right** | **the operator** | **a real install on a real phone** |

Most of the app only happens to somebody who has signed in, and a test run must
never hold a GitHub key. So the app asks a `GitHub` for what it needs, and there
are two: `HttpGitHub` (GitHub over HTTPS) and `DiskGitHub` — a folder of **real
bare repositories** on the phone. The second one is not a stand-in for git: every
clone, commit and force-push is real, through the same JGit code. What is left
out is the network and the key.

A **debug build** can sign in as `test-account`; a release build cannot, and a
build that may not keep one also refuses to act as one. So:

- the feature tests (`*FeatureTest`) drive the **real ViewModel** with the test
  account, and are written as the promise they check, not as the class they touch;
- the emulator job builds the debug APK **of the same tag as the release** and
  walks the account features for real, arriving as `render-walk-*`.

If a change makes a feature test fail, the app is wrong — not the test. That is
the whole point of the seam: it is the same code the release ships.

## What the pictures are for

Two kinds of picture are made, and neither of them decides anything:

- **The tour on the JVM** runs with every change and ships with every build,
  beside the APK and the list of changes. Its pictures are drawn by the tests,
  on the plain JVM, and they say one thing only: **the feature works — the
  screen is there and it renders.** They say nothing about beauty, spacing or
  colour, because a JVM draws none of those the way a phone does.
- **The renders of a real install** are made by an emulator, from the APK that
  was actually published, and they arrive on the release afterwards —
  asynchronously, and for the operator's eyes last. This is where appearance
  lives, and only a person can judge it.

So there is **no visual gating in CI**:

- no picture comparison, no pixel thresholds, no contrast checks
- no "the themes must differ" assertions
- nothing that fails a build because a colour changed, and no build that waits
  for the renders

An automated check of appearance can only ever assert what the author already
believed. If it is written to catch a theme that failed to apply, it will not
notice that the theme is ugly, that the spacing is cramped, or that the result
is not what was wanted. That is a job for a person pressing buttons on the
thing itself — and it is the operator's job here, deliberately.

## Why this is the right split

An automated check of appearance can only ever assert what the author already
believed. If it is written to catch a theme that failed to apply, it will not
notice that the theme is ugly, that the spacing is cramped, or that the result
is not what was wanted. That is a job for a person pressing buttons on the
thing itself — and it is the operator's job here, deliberately.

Automated tests are kept for the one thing they are genuinely better at:
answering a question with a yes or a no, quickly and repeatably.
