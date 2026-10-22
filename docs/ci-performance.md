# How long a run takes, and why

Measurements from 2026-10-22, on the public-repository runner: **4 processors,
15.6 GB**, `ubuntu-latest`. Every number below was read from a real run, not
estimated.

## Where it started, and where it is

| | wall clock |
|---|---|
| One job, container-limited settings, Robolectric downloading its runtime on every run | **~5m 24s** (with the emulator tour) / **1m 55s** (tests + release) |
| + runtime cache keyed by a version instead of a file hash | 1m 24s |
| + release built beside the tests instead of after them; cache cleanup disabled | 1m 05s |
| + release lint moved to the job that has time for it; stale runs cancelled | **~1m 16s** |

The last two are not comparable by themselves: the number went *up* from 65s to
76s because that run had less warm cache. The stable figure is the critical
path, below.

## What is left, measured

Per job, on a warm cache:

| | Test job | Release job |
|---|---|---|
| machine setup, checkout, Java | 7s | 7s |
| set up Gradle (restores caches) | 9s | 12s |
| the work | 36s (lint) | 36s (build) |
| hand-over / publish | — | 8s |

The two jobs run at the same time, so the wall clock is the slower of them plus
publishing: **about 55–65 seconds**.

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

## What was checked and found not to help

Each of these was tried or measured, not guessed about:

| idea | verdict |
|---|---|
| **Prime the Gradle distribution** | already done. `gradle-wrapper-zips-v1` is a cache hit, restored in 6–7s per job. Nothing to prime that is not already primed |
| **Shard the tests** | actively worse. The suite takes 16s and setting a job up costs 17s, so two shards each pay the setup to halve the work: slower, and twice the runners. Worth revisiting only if the suite passes about two minutes |
| **`maxParallelForks` above the core count** | no effect here. The tests are `FROM-CACHE` whenever the app did not change, and when they do run they are 16s of a 55s path. The bottleneck is lint, not test parallelism |
| **The configuration cache across runs** | never hits. It needs the project's `.gradle` directory to survive between runs, which means caching build state tied to an absolute path — the kind of cache that goes stale and lies. It is left on because it costs nothing within a job |
| **Remote build cache** | not worth it at this size. It needs somewhere to run, and a whole run is now about a minute |
| **Dedicated larger runners** | available for public repositories, and they would cut compile and lint time. Against that: queue times are less predictable on them, and the current cost is already near the floor of *setup*, not of work |

## The floor

Roughly **17 seconds of unavoidable per-job setup** (machine, checkout, Java, and
restoring the caches) plus the longest single piece of work. With lint at 36s
and the build at 36s running in parallel, a run cannot get much under a minute
without dropping a check or spreading the work over more runners than the saving
is worth.

Further gains from here are seconds, not minutes. If they are wanted, the honest
options are: accept the lint cost less often (only on main), or stop caching
test results so the tests always really execute.
