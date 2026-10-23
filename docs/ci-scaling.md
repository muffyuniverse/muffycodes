# Making the pipeline stay fast as the code grows

`docs/ci-performance.md` records what a run costs today and why. This file is
about tomorrow: the shape of a pipeline whose test time does not grow with the
codebase, and an honest account of what has already been tried.

## The property we want

Test time should grow with **what changed**, not with **how much exists**. A
thousand tests in a module you did not touch should cost nothing to run.

There is a second property that turns out to matter just as much and is easier to
forget: **the answer must be true.** A pipeline that is fast because it silently
stopped testing is worse than a slow one, and looks identical from the outside.

## Where it stands, measured

| tier | how often | measured |
|---|---|---|
| pull request, one test class | most often | **58s** |
| pull request, seven classes | often | **91s** |
| development push | occasionally | **66s** documents only · **136s** with an app change |
| main, full release | rare, by hand | **146s** |
| main, nothing in the app changed | — | **59s** |

For comparison: 226s and five jobs at the start of this pass.

Of the 59 seconds of a one-class pull request:

| | |
|---|---|
| job setup, checkout, the two gates | 8s |
| Gradle setup and cache restore | 6s |
| Robolectric runtime restore | 3s |
| Gradle configuration and start-up | ~13s |
| compiling the test classes | 10s |
| **running the tests** | **8s** |
| teardown | ~5s |

**Running the tests is the smallest item on the list.** That single line
determines most of what is and is not worth doing, and it is why the rest of this
document is mostly about fixed costs.

## What works

**Targeting, with a fallback that cannot be wrong.** `tools/changed-tests.sh`
maps a changed file to the classes that exercise it and falls back to *everything*
whenever it cannot place a file: an unknown path, a change to the build itself, an
empty change. Unknown means full, never nothing. Measured, it is worth about
32 seconds between a narrow and a wide change.

**Sharding, with a split that is verified.** `tools/test-shard.sh` deals classes
across runners by a stable hash of the name — so sibling classes do not land
together — and `--verify` proves the split is complete and disjoint before it is
trusted. It is on by default at two shards; a shard with no share of the change
stops before installing a toolchain.

**Running the checks beside each other.** The build, the tests and lint all start
within three seconds of the push. The jobs ask different questions and none of
them needs another's answer to begin. This was worth 100 seconds on its own,
because the build used to start at exactly the moment the tests ended.

**The gate moved rather than removed.** GitHub cannot cancel a running job because
a sibling failed, so delivery waits on `needs:` (nothing ships on a red suite) and
the build, immediately before handing anything on, asks the API whether the suite
passed and fails without producing an artifact if it did not. Verified with a
deliberately broken test.

**Measuring every run.** The tests report whether they still scale, the build
reports where its time went, and both are compared against budgets in
`tools/scaling-budget.txt` and `tools/build-budget.txt`. Advisory, never failing.
Every real improvement in this document was found by reading one of those tables
rather than by reasoning about the build.

## What does not work, and was removed

Worth recording, because each was plausible and each is the obvious next idea.

**The emulator tier.** Six minutes a release, gating nothing, proving almost
nothing the feature tests did not. Removed. Appearance is a person's job, on a
real install.

**Sharding before the suite is slow.** At nine classes and six seconds, two
runners cost 38s and 32s where one cost 21s: each runner pays setup and
compilation again. The lesson is not "sharding is bad" but "sharding divides
execution and duplicates everything else".

**Writing a Gradle cache entry the next run cannot use.** A release job that was
read-only was reading entries nothing wrote, so release-only tasks could never
hit — that half was a real defect. Writing them was the wrong fix: two runs later
the count was identical (14 restored, 31 executed, same 50s build) and the write
cost seven seconds a run. Every expensive release task is keyed on the sources or
on the classes built from them, so on a commit that changes sources it can never
hit.

**Cheap lint on development.** `lintDebug` was expected to be nearly free because
the test job had already compiled the debug variant. It measured **111s** — more
than `lintRelease` on main at 98s. Gradle's cache entries here are keyed per job
(`gradle-home-v2|…|lint[…]`), so the lint job cannot restore the test job's
compile; it compiles debug itself. The hypothesis was wrong for a reason that is
invisible in the workflow file and obvious in one log line.

**Merging lint into the build.** Two jobs that both need the release variant
look like one job waiting to happen. Measured against the alternative: merged
comes to about 94s, lint alone to 87s, because the build's 13s cache restore
would then sit on lint's clock as well. Two runners is the cheaper arrangement.

**Comparing class names without normalising them.** This one was a defect rather
than an idea, and it is the most serious thing found in this pass. See below.

## The bug that matters most

The selector names a class as `tools/test-map.txt` does — `HistoryFeatureTest` —
and the shard script names it as a file path becomes a class —
`com.example.notes.HistoryFeatureTest`. `pick-tests.sh` compared them literally.
Nothing ever matched, so every shard concluded it had no share of the change,
stopped before Gradle, and reported success. **Every narrowed pull request had
been running zero tests and going green.**

It was found by running a real one-class pull request and noticing that
`Run the tests` took 0s inside a 13-second run. Reading the code would not have
found it: both halves were individually correct, and the defect lived in the seam
between two files that each did their own job properly.

Two things came out of it, and both are now permanent:

- `pick-tests.sh` compares names without their package, and a selection that
  cannot be placed **exits 3** rather than being answered with "nothing to do" by
  every shard at once.
- `pick-tests.sh --self-test` checks the selector against answers that are not in
  doubt, and runs on the first shard of every run. It found a second, smaller bug
  in its own first version within a minute of being written.

The general rule: **the only way to know a targeting system works is to watch it
run a change whose answer you already know.** A green run is not evidence.

## What is left to push, and what it is worth

Ranked by measured or well-estimated gain, with the honest note on each.

| idea | gain | verdict |
|---|---|---|
| Configuration cache on narrowed runs | ~6s of Gradle | **done**; see below |
| Drop lint from development | ~66s on a dev push | **done**, on the operator's call |
| Merge delivery into the build job | ~8s | **rejected** — it would serialise the build behind the tests |
| Faster Gradle start-up | up to 13s | not available: no daemon survives one job |
| Fewer artifacts for a pull request | 2–4s | noise; skip it |

**The configuration cache on narrowed runs.** Passing `--tests` changed the
command line, and the command line is an input the cache is keyed on, so every
narrowed run recomputed the build. The selection now travels in
`MUFFY_ONLY_TESTS` and is applied in a `doFirst` block at execution time, where
an environment variable is not a cache input.

Measured on one branch, three consecutive runs of the same one-class change:

| | total | Gradle |
|---|---|---|
| first run, cold | 68s | 39s |
| second, warm | 63s | **33s** |
| third, warm | **58s** | — |

"Reusing configuration cache" now appears in the log. The honest reading: the
six seconds are real in Gradle's clock but the run total is inside the ±6s of
run-to-run variance at this size. The value is that it **stops getting worse** —
before this, *every* narrowed pull request missed, permanently, because the key
was shared with builds that pass a different recording flag.

That sharing was a second defect, found only because the change was probed
rather than trusted: main runs with `-Pmuffy.shots` and a pull request does not,
so the two want different cache entries under the same key — and an entry, once
written, can never be replaced. The event is now part of the key.

**Merging delivery into the build job was rejected.** It looks like two jobs
collapsing into one, but delivery must wait for the tests and lint, so folding it
into the build would put the build behind both — turning a 3-second start into a
70-second one. Removing a job is worth 45 seconds; this would add 60.

**Lint no longer runs on development**, on the operator's call. It had never
failed there, the branch exists to hand somebody a build to look at, and the
cheap variant measured *slower* than the deep one.

What removing it is actually worth depends on the run, and the first measurement
was misleading. Development went 141s → **66s** on a documents-only push, but
that run built nothing. Measured again with a real change to the app: **136s**
against 141s before. Lint was running *beside* the tests, so it never sat on the
critical path — the minute it looked like it was saving was the build, which had
only been skipped because no code had changed.

The honest accounting: lint on development cost about five seconds of wall clock
and bought a second opinion nobody ever acted on. Removing it is justified by
"the branch exists to hand out a build", not by a minute. Two measurements were
needed to learn that, and the first one was quoted before the second existed.

## The floor

Add up what a one-class pull request cannot avoid:

| | |
|---|---|
| job setup, checkout, gates | 8s |
| Gradle setup and cache restore | 6s |
| Robolectric runtime | 3s |
| Gradle configuration and start-up | 13s |
| test compilation | 10s |
| test execution | 8s |
| teardown | 5s |
| **total** | **~53s** |

The pipeline is at 59s. **It is about six seconds from its floor**, and that six
is the configuration cache. Everything else on the list is either GitHub's own
cost per job or work that has to happen.

This is the most useful conclusion in the document: for a codebase this size,
**there is nothing large left to win.** Further effort should go into the
property below, which does not show up until the code gets bigger, or into not
spending anything at all.

## What happens at 10x and 100x

The model. Let:

- **F** — fixed cost per job: setup, checkout, cache restore, Gradle start-up,
  teardown. Measured at about **45s** and does not depend on the codebase.
- **C** — compilation: app code plus test sources.
- **T** — test execution.
- **S** — shards.

One shard costs `F + C + T`. With `S` shards each shard pays `F` and `C` again,
because every shard compiles the same sources, so a run costs:

```
F + C + T/S
```

Today F ≈ 45s, C ≈ 10s, T ≈ 8s, which is the 59s measured. Sharding gains nothing
yet because T is one eighth of the total — halving it saves four seconds and
spends a runner.

**At 10x the tests** (T ≈ 80s, C ≈ 40s), two shards go from 165s to 125s and four
shards to 105s. Sharding starts paying. This is the regime where the existing
machinery earns its place.

**At 100x the tests** (T ≈ 800s, C ≈ 300s), the arithmetic turns against it:

| shards | one shard each | |
|---|---|---|
| 4 | 45 + 300 + 200 | 545s |
| 8 | 45 + 300 + 100 | 445s |
| 16 | 45 + 300 + 50 | **395s** |

Sixteen runners buy 150 seconds, and the floor is `F + C ≈ 345s`. **Sharding has
run out.** It divides execution, and execution has stopped being the problem.

The answer is to make `C` divisible too, which means the test sources have to be
more than one module: each shard compiles only its share, so `C` becomes `C/S`.

| shards | with test sources split | |
|---|---|---|
| 8 | 45 + 38 + 100 | 183s |
| 16 | 45 + 19 + 50 | **114s** |

That is the difference between 395s and 114s, and it is the single most important
structural fact about this pipeline at scale. Splitting the application module has
been the standing recommendation for other reasons — shorter compiles, a module
graph that replaces the hand-maintained test map — but this is the reason that
does not go away.

### The other axes

**Application code at 10x.** The release build's Kotlin compile is 21–38s today
for one module. Split into modules it compiles in parallel within one job and, on
a change, only the affected part. Same lever, second payoff.

**Lint at 10x.** Already 98s and whole-program by design, so it grows faster than
the code. It is on main only, which is the right place for an expensive whole-tree
check, and main is rare. At 10x it becomes the release latency rather than the
pipeline's, which is acceptable; at 100x it needs to run per module, or the change
has to be to stop running it on every release and run it on a schedule instead.

**Robolectric.** The genuine scaling risk, because it is the only framework here
that loads an Android runtime, and the scenario tests draw real screens. The order
of answers: keep the majority of tests as plain JVM tests; give Robolectric tests
their own module and their own shard so they never make the fast tests wait; only
then consider distributing tests across machines, which is a paid product and
should be last.

**Scenarios.** Ten today, drawn from real screens. They are written to be an
explanation for a person, not a test, and their cost is the cost of the app's own
screens. If they ever become the slowest thing, the right move is to draw them
from a scheduled run rather than from every push — they prove the app looks
right, which does not change as often as the app does.

## The selector is for pull requests, and only for pull requests

`tools/test-map.txt` narrows a run. It is used on **pull requests only**;
`main` and `development` run `changed-tests.sh --all`, which does not read the map
at all. That is a decision about the shape of the risk, not a convenience.

The map has one failure that cannot be checked away: a line that is *valid but
incomplete* — the source is named, so there is no full-suite fallback, but the
test that would have caught the breakage is missing. `tools/check-test-map.py`
proves inventory (every class named, every source accounted for, nothing stale);
it cannot prove that a class really exercises the files its line claims.

Where that risk is allowed to land is the whole point. On a pull request it can
only cause an incomplete run *before* a merge. On `main` the full suite always
runs, so a merged defect is caught before any version is spent. On a branch that
hands out builds, the full suite means the build someone is about to install was
tested by everything.

The alternative — running the full suite on every pull request — was measured at
**+32s** (58s → 90s) and declined. If the map ever causes a defect that reaches
`main` undetected, this is the decision to revisit, and the map's narrowing is
what to delete first.

## Caches are scoped to the ref that made them

Worth knowing before reading too much into a "warm" number. GitHub scopes an
action cache to the ref that wrote it, and a pull request's cache belongs to that
pull request's merge ref.

So the configuration-cache saving is real but narrower than it looks: **the first
run of a new pull request starts cold**, and the reuse is between runs of the same
request. The measured 39s → 33s of Gradle was run 1 → run 3 of one branch, which is
exactly the case that does hit. A push to `main` and a pull request also cannot
share an entry, because a build records pictures (`-Pmuffy.shots`) and a request
does not, so they are different configuration inputs — that is why the event is in
the key, and why sharing it would have locked one of them out permanently.

None of this makes the cache wrong. It makes "warm" a word that needs a subject.

## The strategy, in five rules

1. **Measure the run, not the build file.** Every real win here came from a
   profile or a job timeline. Two confident hypotheses (`lintDebug` is cheap,
   writing the cache pays) were both wrong and both would have shipped.
2. **Divide the work before adding machines.** Execution is the only thing
   sharding divides; compilation and setup come back per runner. When shards stop
   paying, the answer is a smaller unit of compilation, not a bigger matrix.
3. **Keep the fixed cost fixed.** `F` is 45 of the 59 seconds. Anything that adds
   a job adds 45 seconds of somebody's clock; anything that removes one removes
   that much. This is why merging delivery was worth 23 seconds and why a fifth
   job is a serious proposition.
4. **Never let "nothing ran" look like "everything passed".** Unknown falls back
   to full; a selection that cannot be placed is an error; the selector tests
   itself on every run; and the JUnit results are compared against the classes that
   were asked for, because every guard before that one was about *deciding* what to
   run and none of them looked at what came back. Speed that quietly stops testing
   is not speed.
5. **Spend on the frequency you have.** A pull request happens many times a day
   and must be a minute. Main happens when a release is wanted and can be three.
   Optimising the rare tier to the detriment of the common one is backwards —
   which is exactly what the main-only deep lint does deliberately.
