# How long a run takes, and why

Measurements from 2026-10-22, on the public-repository runner: **4 processors,
15.6 GB**, `ubuntu-latest`. Every number below was read from a real run, not
estimated.

## Where it started, and where it is

| | wall clock |
|---|---|
| One job, this container's settings, Robolectric downloading its runtime every run | **1m 55s** (tests + release) · ~5m 24s with the emulator tour |
| + the runtime cache keyed by a version instead of a file hash | 1m 24s |
| + the release built beside the tests; cache cleanup disabled | 1m 05s |
| + tests forced to really run; lint on a runner of its own | **1m 14s** |

The last row is *slower than the one before it*, and that is the honest
answer: two runs before it had warmer caches, and the row before it was not
running the tests at all (see below). The number that matters is the one
measured on the same warm cache.

## What is left, measured

Three jobs run at the same time on main, and the run is as long as the slowest
of them plus publishing:

| job | time |
|---|---|
| Release checks (`lintVitalRelease`) | 50s |
| Build the release | 38s |
| **Test** — the critical path | **58s** (25s setup + 29s work) |
| Publish | 13s |
| **total, wall clock** | **74s** |

On a pull request only the Test job runs, so it is about 58s.

## The four things that actually mattered

**1. The Robolectric runtime (about 60 seconds).** It is a jar of well over a
hundred megabytes, downloaded on demand into `~/.m2`. The cache key was a hash
of `build.gradle.kts`, so every edit to that file made a new key, the cache was
never found, and the runtime was downloaded again. A version-shaped key fixed
it: the tests went from 70s to 18s.

**2. `cache-cleanup: never` (28 seconds).** The `setup-gradle` action's post
step otherwise starts the runner's own Gradle — a *different version* — in a
fresh daemon just to prune the cache. It cost more than the tests themselves.

**3. Moving `lintVitalRelease` (17 seconds).** Android attaches it to
`assembleRelease` whether or not it is wanted, and it was 42% of the release
build. The test job finishes long before the release job, so the check runs
there instead and still gates publishing.

**4. Building the release beside the tests (about 20 seconds).** The release
cannot be *published* before the tests pass, but it can be *built*. The
publishing job waits for both.

## The settings that were strangling the runner

`gradle.properties` carried this container's limits — one worker, no parallel
work, no incremental compilation — and every run read them, so the runner was
held to the limits of a small phone container. They now live in this
container's own Gradle home, outside the repository. The project says only what
is true of the project.

## The tests were not running at all

Worth its own section, because it was the worst problem found and it was not a
performance problem.

Gradle was reporting `:app:testDebugUnitTest FROM-CACHE`: instead of running
anything, it handed back the results of an earlier run. The verification gate
was therefore answering "did this commit pass?" with a question about a cache
entry. It is sound in principle — the cache key covers what the tests read — but
it hides everything the cache does not know it depends on, which is exactly what
the screen tests depend on (the machine's own state).

The tests are now always really run, at a measured cost of about 16s. That is
the price of the answer being about the code.

## What was checked and found not to help

Each of these was tried or measured, not guessed about:

| idea | verdict |
|---|---|
| **Prime the Gradle distribution** | already done. `gradle-wrapper-zips-v1` is a cache hit, restored in 6–7s per job. Nothing to prime that is not already primed |
| **Shard the tests** | actively worse. The suite takes 16s and setting a job up costs 17s, so two shards each pay the setup to halve the work: slower, and twice the runners. Worth revisiting only if the suite passes about two minutes |
| **`maxParallelForks` above the core count** | no effect here. The tests are `FROM-CACHE` whenever the app did not change, and when they do run they are 16s of a 55s path. The bottleneck is lint, not test parallelism |
| **The configuration cache across runs** | never hits. It needs the project's `.gradle` directory to survive between runs, which means caching build state tied to an absolute path — the kind of cache that goes stale and lies. It is left on because it costs nothing within a job |
| **Remote build cache** | not worth it at this size. It needs somewhere to run, and a whole run is now about a minute |
| **Forcing the tests to run** | done, and it is not an optimisation but a correction: they were being replaced by cached results |
| **Dedicated larger runners** | available for public repositories, and they would cut compile and lint time. Against that: queue times are less predictable on them, and the current cost is already near the floor of *setup*, not of work |


## A hazard with a date on it: the runner image is changing now

`ubuntu-latest` is moving from Ubuntu 24.04 to **26.04 between 19 October and
19 November 2026** — during that window two runs of the same commit can get
different images. Checked against the published image manifest, the things that
matter here:

| | 24.04 | 26.04 | effect on this project |
|---|---|---|---|
| default JDK | 17 | **25** | none: the workflow pins `java-version: 17`, and both images carry 17 |
| Android SDK | present | present, `ANDROID_HOME=/usr/local/lib/android/sdk` | none |
| build-tools | 34, 35 | 34, 35.0.0, 35.0.1, 36, 37 | none: the workflow no longer names a version |
| preinstalled Gradle | 8.x | 9.8.1 | none: the wrapper decides the version |

Two changes were made because of this:

- **The build-tools version is no longer written into the workflow.** It used to
  say `35.0.0`, which happens to survive 26.04 — but naming a version that the
  image chooses is a break waiting for a date. The newest present is used, which
  is safe because `apksigner` and `zipalign` read APKs of any age.
- **Pinning Java 17 is now load-bearing, not just tidy.** The default becomes 25,
  and the screen tests run on Robolectric, which supports a narrower range of
  JDKs than Gradle does.

Not changed, on purpose: the actions stay on `@v4`. Their own documentation
still recommends v4 even though newer tags exist, and GitHub currently forces
those versions onto Node 24 where they work — our releases have been succeeding
on them. Jumping six major versions for a deprecation warning would trade a
working pipeline for an untested one.


## Running only what a change can have broken

A pull request runs the test classes its change touches; main runs all of them.
The mapping is plain text in `tools/test-map.txt`, one line per test class
naming the sources it exercises.

The safety rule is the fallback, and it is the whole point of the design. The
full suite runs when:

- a changed file is on no line of the table (so a file added tomorrow cannot
  slip through a gap),
- the build itself changed,
- the change is empty, or nothing under `app/src` changed at all,
- the table is missing.

Unknown means full, never nothing. That is the opposite of the usual way test
selection fails, which is to read "I could not match this" as "this affects
nothing" — the same mistake the picture checks made in a different form: a gate
that looks like a gate but is not one.

Verified end to end rather than assumed: a change to `FolderTree.kt` alone made
the workflow report `tests: FolderTreeTest`, and the run passed with only that
class executed.

**Lint is deliberately not selective.** Android Lint analyses the whole project
by design; run over a handful of files it loses exactly the checks that need
more than one file to be right, such as a resource that nothing refers to any
more. It also already costs no wall clock, because it has a job of its own
running beside the others. Making it selective would buy nothing and lose
something.


## What a pull request costs, by kind of change

Measured on the same day, same repository:

| the change | what runs | wall clock |
|---|---|---|
| words only (a document, a picture, the tooling that takes pictures) | nothing: `NONE` | **24s** |
| one source file | that file's tests, and lint | **74s** |
| anything unclassifiable, or a change to the build | everything | **74s** |
| a change to a workflow | everything, because a workflow is validated by running it | **77s** |

The words-only case was 74s before, for an app that was byte for byte the one
that had already passed. Now it is 24s, and the 24s is the floor: two jobs
starting, deciding there is nothing to do, and reporting.

## The floor, and what is above it

For a pull request that really changes the app, the wall clock is no longer the
tests. It is the release checks:

| | time |
|---|---|
| lint (`lintVitalRelease`) | **60s** |
| tests, whole suite | 20s (of which about 9s is the tests actually running) |
| setup in each job | ~13s |

The tests are cheap because they are small; lint is expensive because it reads
the whole project by design. They run side by side, so the pull request takes
as long as lint plus setup.

### Can lint be skipped for a smaller change?

Not for a *small* one, and the distinction matters. The cost of a change is not
proportional to its size: deleting one line that a resource depends on is a
vital lint failure and a crash at run time. A rule that says "the diff is
short, so skip the check" would skip exactly the changes that are most likely to
break something quietly.

What *can* be skipped is a change that cannot reach the app at all — which is
what the words-only rule above does, and it is a statement about the files, not
about their size.

## The floor

Roughly **17 seconds of unavoidable per-job setup** (machine, checkout, Java, and
restoring the caches) plus the longest single piece of work. With lint at 36s
and the build at 36s running in parallel, a run cannot get much under a minute
without dropping a check or spreading the work over more runners than the saving
is worth.

Further gains from here are seconds, not minutes. If they are wanted, the honest
options are: accept the lint cost less often (only on main), or stop caching
test results so the tests always really execute.
