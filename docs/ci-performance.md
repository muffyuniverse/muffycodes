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


## Growing without getting slower

The suite takes six seconds and the configuration phase is what a run really
costs, so today the whole suite runs on one runner. That is a statement about
today, not a design, and the design is what matters once there are dozens of
modules and hundreds of tests.

Three things are in place so that growth is paid for with runners rather than
with time.

**A shard is an index, never a diff.** `tools/test-shard.sh --shard i --of n`
prints the gradle arguments for runner `i`. It is deliberately not driven by
what changed: a set chosen per change changes the command line, a changed
command line is a different configuration-cache entry, and the run would trade a
cache hit for a moment of narrowness. Each shard's command line is identical from
run to run, so the cache holds one entry per shard and reuses them all.

**The split is balanced and complete.** Classes are ordered by a POSIX hash and
dealt out in turn, which measured at 3/2/2/2 for four shards where hashing each
class into a bucket measured at 2/0/6/1 — a runner waiting with nothing to do.
`--verify` checks that every class lands in exactly one shard, so the split can
never quietly leave tests unrun.

**Scaling is a repository setting.** The test job reads the variable
`TEST_SHARDS`, which holds a list of shard indexes and defaults to `[0]`. Setting
it to `[0,1,2,3]` turns one runner into four. No workflow edit, no reshaping of
the tests, and the shard count is not baked into any test.

`--parallel` is passed as well. It does nothing with one module and costs
nothing, and it means the runners will use modules as soon as they exist rather
than after somebody remembers to add the flag.

**Each shard needs its own cache key, and this was found the hard way.** A
matrix is a single job definition, so every shard writes to the same key and
only one entry survives; the others restore a cache holding somebody else's
entry, miss, and recompute. Two consecutive runs both reported a miss in both
shards until the shard index was added to the key. With it, both shards report
`Reusing configuration cache.` from the second run onwards.

**And sharding is a loss at this size, which is why it is off.** Measured on the
same nine test classes: one runner takes 21s of Gradle work, while two runners
took 38s and 32s, because each shard pays the setup, the Gradle start-up and the
compilation again. The work is too small to divide and the fixed cost is paid
twice. Sharding pays when the work dwarfs the setup, which it does not yet. The
machinery is in place and tested for the day that changes.

### What is worth doing first when it does get slow

In order, because each is worth more than the next:

1. **Split the module.** The app is one module; the tests for the folder tree,
   the file format and git sit beside the screens. Separate modules get separate
   `testDebugUnitTest` tasks, Gradle runs them in parallel, and a change to one
   does not recompile the others. This is worth more than sharding because it
   also shortens compilation.
2. **Then shard.** `TEST_SHARDS=[0,1]`, measured with `--verify` first.
3. **Only then** think about narrowing by what changed. It is the weakest of the
   three: it saves test execution, which is the smallest part of a run, and it
   gives up the cache to do it.

## One cost per branch, measured

| branch | what runs | measured |
|---|---|---|
| pull request, words only | the decision, and nothing else | **10s** |
| pull request, one source file | that file's tests | **53s** |
| pull request, unclassifiable or build change | every test | **49–53s** |
| development | every test, and the release APK as an artifact | **2m 09s** |
| main | every test, the deep checks, the release, the publish | 1m 45s–3m |

Development builds an APK and keeps it as an artifact but publishes nothing;
main is the only branch that publishes. Both were run to confirm it, and a
development push left `Publish` and `Deep checks` skipped with `release-apk`
(7,462,352 bytes) attached to the run.

## The floor, taken apart

The floor used to be described as seventeen seconds. Measured step by step on a
run that does real work:

| step | time |
|---|---|
| Set up job | 2–4s |
| Check out | 1–3s |
| Work out which tests are needed | 1s |
| Set up Java | 0–1s |
| Set up Gradle (restores the cache) | 6–9s |
| Robolectric runtime cache | 0 or 4s |
| Gradle's own start-up inside the test step | ~7s |

Roughly ten to fifteen seconds of unavoidable provisioning, of which the Gradle
cache restore is the largest single item.

**The way to lower it was not to make it cheaper but to stop paying it.** The
decision now runs first, and when there is nothing to run, Java, Gradle, the
Robolectric cache and the tests are all skipped — which is why a
words-only pull request takes ten seconds instead of seventy-four. Nothing is
installed to do no work.

## Nothing above this directory

A development branch is pushed on every glance at a build, so it is worth
knowing what it does *not* do: it does not publish, it does not run the deep
checks, and it does not make a version. It builds the APK, tests it, and keeps
the artifact.


## What a fifty second pull request is made of

Measured from the log of a run that changed one Kotlin file, with timestamps
taken between task completions rather than guessed:

| | time |
|---|---|
| Set up job, check out, decide | 3s |
| Set up Gradle (restores its cache) | 6s |
| Remember the runtime, remember the project | 1s |
| **Run the tests: 43s** | |
| — Gradle start, and reading the configuration cache | **4.4s** |
| — the small and cached tasks | 4s |
| — **compileDebugKotlin** | **17.3s** |
| — compileDebugUnitTestKotlin | 2.5s |
| — **testDebugUnitTest, the whole suite** | **13.2s** |
| after the job (caches are saved) | 5s |

The tests are not what a pull request costs. Compiling the module is, and
starting Gradle is the second most expensive thing in the run.

### The configuration cache, and what it was worth

It was not working, and the cause was in this repository: a `doFirst` block that
touched `logger` captures the build script itself, which the cache cannot store.
With the message moved to configuration time the cache stores and reuses,
measured across two runs, and the configuration phase fell from **14.8s to
4.4s**.

The cache lives in the project's `.gradle` directory, which a fresh checkout does
not have, so it is carried between runs explicitly. Its key is the build files
alone: the cache says how the project is put together, not what the sources say,
so a change to a Kotlin file still reuses it.

It is deliberately absent from the release build. That invocation carries a
version code and name that differ every time, so its entry could never be reused
and storing it would be a cost with no payoff.

### Why the whole suite no longer runs

This section used to say the opposite, and the reason it changed is the suite.

When it was written the whole suite took six seconds and asking for a subset cost
a fresh configuration-cache entry worth about fifteen. Narrowing lost nine
seconds, so every test ran and the command line stayed identical.

Both figures have moved. The suite is now around fifty seconds, because it
covers the accounts, the projects, the sync, the history and the retention rules
rather than a list of notes. The configuration cache still misses on a changed
`--tests` argument, and it is still worth about fifteen seconds — against a
selection that routinely comes back with five classes out of sixteen.

So the selector decides again, and `tools/pick-tests.sh` intersects what it chose
with the shard each runner is given. The safety property is unchanged and is the
whole design: the selector **falls back to everything** whenever it cannot be
certain — a changed file that is on no line of `tools/test-map.txt`, a change to
the build itself, an empty change. Unknown means full, never nothing.

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

## Checking in parallel, and what replaced the gate

The jobs used to run in a line: tests, then the release build, then the bundle,
then the publish. The measurement that ended that is in the job timings of any
run from before this change: the build's clock began at 57–67s — not when its
runner was free, but exactly when the tests finished.

| run | tests end | build starts | whole run |
|---|---|---|---|
| 39992226128 | 62s | 65s | 201s |
| 39991167742 | 55s | 58s | 164s |
| 39990381077 | 55s | 57s | 226s |

The two jobs ask different questions and neither needs the other's answer to
begin. A build asks whether the code compiles and an APK comes out; the suite
asks whether it behaves. So they start together, and so does lint, which is the
same shape of expense: sixty seconds of checking that says nothing about whether
the tests will pass.

| | before | after |
|---|---|---|
| whole run | 164–226s | **118–125s** |
| build starts | 57–67s | 3s |
| lint starts | 58s | 3s |

### "Start early and stop them if it fails" — what is actually possible

GitHub Actions **cannot cancel a running job because a sibling failed**. There is
no such input; `fail-fast` spans the legs of one matrix job and nothing else.
Cancelling the run from inside it would cancel the job reporting the failure too,
and turn a failure into a cancellation, which is a worse answer.

So the gate moved house rather than disappearing. It used to be *wait for the
tests*; it is now *ask about them*:

- `bundle` and `publish` still list the tests in `needs`, so they never start on
  a red suite. That is where delivery happens, so nothing ships either way.
- the build, before anything leaves it, asks the API whether the suite passed and
  **fails and hands nothing on** if it did not. Verified with a deliberately
  broken test: the job stopped at that step, `app-release` was not uploaded (the
  run's only artifact was the test report), `bundle` and `publish` were skipped,
  and no release was cut.

What that costs is a build paid for on a red run. What it saves is the whole
test duration on every green run. And the red answer stays fast, because `ci`
stops waiting the moment a job fails instead of waiting for the run to close —
59 seconds in the probe above, against the ~105 the run needed.

Two traps found while making that work. GitHub serves a job's log only once the
job has finished, so a verdict that arrives early arrives without its reason; `ci`
waits for the failing job, not for the run. And the runner echoes each step's
script into the log in cyan, where it contains every word the excerpt greps for —
"error:", "Could not", the message the step would print on failure. The excerpt
drops those lines before matching, or it quotes the script back as if it were the
outcome.

## The tail, and what the pipeline is actually limited by

After the head was parallelised, the tail was two jobs — one assembling the
bundle and handing it to the run as an artifact, then one taking that artifact
back down and publishing it. The second job's first act was to download what the
first had just uploaded: a nine-megabyte round trip between two machines that
never needed to be two machines. That became one job, `Deliver`.

| | before | after |
|---|---|---|
| bundle + publish | 24s + 13s + a scheduling gap = **~39s** | **16–20s** |

The APK download inside it measured **1s**, not the 11s it had taken as a
separate job — an artifact fetched moments after it was written is served from
the run's own runner rather than from cold storage. That is most of the saving.

### Where the pipeline's clock actually is

Measured on one green run after the merge:

| job | starts | ends | |
|---|---|---|---|
| tests (2 shards) | 3s | 57s | |
| build the release | 2s | 64s | |
| **deep checks (lint)** | 3s | **90s** | ← the critical path |
| deliver | 92s | 112s | |

The build is **62s**, which is below the test floor — the question this was
asked to answer. What replaced it is lint: `lintVitalRelease` is 76s of work in
its own job, and it now sets the floor for the whole pipeline. Removing it would
put the run at about 82s instead of 112s, which is what it costs.

It is worth saying why lint cannot simply be moved somewhere cheap. It needs the
release variant compiled, so it pays the same 21s Kotlin compile the build pays —
and merging the two jobs does not help, because the build's Gradle cache restore
(13s) would then sit on lint's clock as well. Measured, the merged job comes out
at about 94s against lint's 87s alone. Two runners are the cheaper arrangement.

### One correction: writing a cache the next run cannot use

A run of this log's earlier advice turned the release job's Gradle cache
read-write, on the reasoning that the test job only writes debug entries. The
diagnosis was right and the conclusion was wrong. Two runs later the count is
identical either way — 14 tasks from cache, 31 executed, the same 14 — and the
build is the same 50 seconds. Writing cost seven seconds at the end of every run
to save a 144MB entry nothing restores, because every expensive release task is
keyed on the sources or the classes built from them.
