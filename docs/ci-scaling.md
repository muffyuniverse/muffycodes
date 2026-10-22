# Making the pipeline stay fast as the code grows

`docs/ci-performance.md` records what a run costs today and why. This file is
about tomorrow: the shape of a pipeline whose test time does not grow with the
codebase.

## The property we want

Test time should grow with **what changed**, not with **how much exists**. A
thousand tests in a module you did not touch should cost nothing to run.

That is not what happens by default. Gradle compiles the whole module before
running one test, so a one-line change costs a whole-module compile. Sharding
splits the tests but not the compile. Neither makes the pipeline flat.

## What makes it flat, in order of how much it buys

### 1. Split the module (the only lever that shortens compilation)

The app is one Gradle module, `:app`. Every change recompiles all of it.

Split it and each part compiles on its own. A change to the git layer no longer
recompiles the screens:

```
:app                  (the Android application, thin)
:core:model           Folder, Note, BackTarget — plain data
:core:logic           FolderTree, NoteCodec — pure functions, the bulk of the tests
:core:storage         FolderStorage, NoteFile, NoteStore
:core:git             GitService, GitProjects, GitHubClient
:core:ui              themes, dialogs, shared composables
:feature:notes        the list and editor screens
:feature:settings
```

Three things get flatter at once:

- **Compile time** is proportional to the changed module and what depends on it,
  not to the whole app. `:core:logic` compiles in about a second; `:app` does not.
- **Test selection becomes exact by construction.** `:core:logic`'s tests live
  beside `:core:logic`, so "which tests did this change touch" stops being a
  hand-maintained table (`tools/test-map.txt`) and becomes the module graph. The
  table exists because the code is not structured; once it is, the table can go.
- **`--parallel` starts paying.** With several modules, Gradle builds them at the
  same time within one job. It is passed today and does nothing, because there is
  one module.

The cost is real: build files multiply, and a module needs its own dependencies
declared. The right moment is when a change routinely touches unrelated code —
which is the moment the compile stops feeling like it belongs to the change.

**Rule of thumb: split when a one-line change's compile is more than about three
times the tests.** Today the compile is about 20s and the tests 6s, so the ratio
is already 3:1 and the split is worth doing soon.

### 2. Sharding (splits the tests, not the compile)

Already built and verified — `tools/test-shard.sh`, driven by the repository
variable `TEST_SHARDS`. It is deliberately **off** today, because at nine test
classes it measured as a loss: one runner took 21s of Gradle work where two took
38s and 32s, since each runner pays setup and compilation again.

Turn it on when a single shard's work exceeds the setup, which is roughly:

> when the whole suite's execution passes **about 40 seconds** of real test time
> — measured today at 6 seconds for 55 tests, so somewhere past 300 tests.

Set `TEST_SHARDS=[0,1]` and measure with `tools/test-shard.sh --of 2 --verify`
first. Keep `--verify` in the habit: it is what proves the split is complete and
disjoint, so a split can never quietly leave tests unrun.

Two constraints found the hard way, both now encoded:

- **A shard is an index, never a diff.** A set chosen per change changes the
  command line, a changed command line is a different configuration-cache entry,
  and the run trades a cache hit for a moment of narrowness.
- **Each shard needs its own cache key.** A matrix is one job definition, so all
  shards write to the same key and only one entry survives.

### 3. Test impact analysis at module granularity

`tools/changed-tests.sh` already decides *whether* there is anything to do, and
it is worth keeping — a document-only change takes 10 seconds instead of 43.

What it does not do well is decide *which* tests, because with one module the
answer is almost always "most of them". After the module split, it becomes
useful: a change to `:core:git` runs `:core:git`'s tests and the integration
tests that name it, and nothing else. That is where selective testing actually
saves time — at scale, where "only the affected module's tests" is a real
fraction rather than a rounding error.

## What is already native, and free

The repository is public, and for a public repository GitHub Actions charges
nothing for standard runners, artifact storage or cache storage. Larger runners
are billed even for public repositories, so the shape to aim for is **more small
standard jobs, not fewer big ones** — which is exactly what sharding is.

In use now:

| capability | where |
|---|---|
| concurrency cancellation | a second push stops the first run |
| matrix sharding, ready to switch on | `TEST_SHARDS` |
| build + dependency + Kotlin-DSL caching | `gradle/actions/setup-gradle` enhanced provider, free for public repos |
| configuration cache, exact-key | per shard, and it now survives being read back |
| least-privilege permissions | stated on the workflow and on every job |
| actions pinned to commits | every action in all three workflows |
| build provenance attestation | the published APK is signed to its commit |
| Dependabot for Gradle and for Actions | `.github/dependabot.yml` |
| structured dispatch inputs | the emulator workflows take choices, not typed strings |
| artifacts with retention | test reports on failure, APKs, screenshots |

## What not to do

Worth writing down because each is the obvious next move and each is wrong here.

- **Do not shard before the suite is slow.** Measured loss at this size, and the
  setup cost is paid once per runner.
- **Do not narrow tests by what changed *and* keep the configuration cache.**
  They fight: the narrow run changes the command line and misses the cache.
- **Do not make lint partial.** It analyses the whole project by design; run over
  a few files it loses the checks that need more than one file to be right.
- **Do not put the cache behind a fallback key.** A fallback hands over an entry
  made by a different build script, and the configuration cache keeps references
  to classes compiled from that script. It loads, and then dies looking like a
  broken build.
- **Do not use larger runners.** They are billed even for public repositories,
  and horizontal sharding on standard runners is free.

## When this stops being enough

The next real wall is **Robolectric**. It is the only test framework here that
loads an Android runtime, and it is the part that will get slow first as screens
multiply. The answers, in order:

1. Keep pure-logic tests as plain JVM tests — they are 100 times faster and they
   are already the majority. `:core:logic` and `:core:model` need no Android at
   all.
2. Give Robolectric tests their own module and their own shard, so they never
   make the fast tests wait.
3. Only then look at test distribution across machines, which is a paid product
   and should be the last resort rather than the first.

The end state is a pipeline where adding a module adds a runner and adds no
time: the change compiles its module, runs its tests, and the rest of the
codebase is not involved at all.

A single sentence, added only to a document.
