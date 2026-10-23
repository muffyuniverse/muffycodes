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
| **whether it looks right** | **the operator** | **a real install on a real phone** |

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
