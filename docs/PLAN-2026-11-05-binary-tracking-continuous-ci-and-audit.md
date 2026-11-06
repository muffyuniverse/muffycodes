# Plan — binary tracking, a continuous runner, and an audit of today

**Written 2026-11-05 for autonomous execution.** Three pieces of work, in the order they must
happen, with the evidence each rests on and the measurements that decide the shape. Where a number
appears it was measured today, not estimated; where something is unmeasured it says so.

---

## 0. What was measured before planning

Everything below is from the repository and from GitHub, not from memory.

| Fact | Value | How |
|---|---|---|
| Repository visibility | **public** | `gh api repos/muffyuniverse/muffycodes` |
| Actions minutes on a public repo | **free, unmetered** | GitHub billing rules |
| Current workflow jobs | `test` (2 shards), `lint` ("Deep checks"), `build-release`, `deliver` | `build-and-release.yml` |
| `TEST_SHARDS` variable | **not set** → default `[0,1]` = 2 shards | `gh api .../actions/variables` |
| Green run, wall clock | **6 min** | run `42099569937` |
| — Test (0) | 3 min 13 s | `gh api .../runs/<id>/jobs` |
| — Test (1) | 3 min 10 s | |
| — Build the release | 3 min 25 s | runs in parallel with tests |
| — Deliver | 3 min 15 s | waits for both |
| Queue wait (created → started) | **~2 s** | same run |
| GitHub-hosted job ceiling | **6 h** | GitHub docs |
| Self-hosted job ceiling | **5 days** | GitHub changelog 2024-04-04 |
| `schedule:` cron minimum | **5 min** | GitHub docs |
| Scheduled workflows | **disabled after 60 days of no repo activity** (public) | GitHub docs |
| `Entry` (the row model) | **carries no size** — `name`, `file`, `kind` only | `Workspace.kt:19` |
| LFS coverage | `LFS_ATTRIBUTE` built **only** from `IMAGE_EXTENSIONS` | `GitService.kt:148` |
| `createdByOperator` callers | **none in the app** — tests only | `grep -rn` |

Two of those rows are the reason this plan exists in this shape. **The repo is public, so a
continuous runner costs nothing in minutes** — the objection to it is not price, it is that a
long-lived job cannot be reasoned about the way a short one can. And **`Entry` has no size**, so
the size caption is not a UI tweak: it is a field that has to be computed and carried first.

---

## 0b. What wiring it up taught, before the rule was written down

The first implementation of §1 ignored **every** file a person made, reading the operator's rule as
"operator files are ignored". Fourteen tests failed, and the reason is the most important finding in
this plan:

> **An ignored file is not committed.** `stageEverything` runs `git add .`, which honours
> `.gitignore` — so an ignored file has **no version, no undo, and no history**. Ignoring is about
> not sending to GitHub, but git cannot commit-and-not-push, so ignoring *also* means not versioning.

"Every change is a version" is the promise this app exists for. A person's note with no history is
a worse product than a note that reaches GitHub.

**So the rule is narrower than it first read, and the operator's own words support it:** *"unknown
should be gitignored by default … same as with pictures"* — pictures are **tracked**. What is held
back is only what the app cannot classify.

| Creation | Locked | Ignored | History |
|---|---|---|---|
| Text file, by the person | yes | **no** | yes |
| Picture, by the person | yes | **no** | yes (LFS) |
| `.zip`/`.pdf`/`.mp4`, by the person | yes | **yes** | none until tracked |
| Anything, by the agent | no | only if `UNKNOWN` | yes unless `UNKNOWN` |

That table is the whole of §1. Everything below assumes it.

---

## 1. Part one — binary tracking: UNKNOWN ignored by default

### 1.1 The rule the operator set

> "unknown should be gitignored by default and go to lfs when user switches them to tracked, same
> as with pictures, and that applies to both user and agent additions"

Read precisely, this is **two** changes and one removal:

1. **A file the app cannot classify (`EntryKind.UNKNOWN`) is ignored on creation**, whoever created
   it. `.zip`, `.pdf`, `.mp4`, `.docx`, `.apk` — anything not in `IMAGE_EXTENSIONS` or
   `TEXT_EXTENSIONS`.
2. **Tracking one is what sends it to LFS.** The switch already exists (`setIgnored(file, ...,
   inside = false)` → `GitService.track`). What is missing is that **tracking a binary must also
   add an LFS attribute line for its extension**, so the bytes go to LFS rather than into ordinary
   history.
3. **No LFS budget analytics.** Explicitly out of scope. Do not build a usage screen.

### 1.2 What this changes about the app's promise, and why it is now acceptable

The earlier objection to ignoring operator files was that it breaks *"the remote must hold the full
project, binaries included"* — a file saves, is on the phone, and is not on GitHub, with no action
that makes it go.

**That objection does not apply to this rule**, and the difference is worth writing down because it
is the whole justification:

- The rule covers **only what the app cannot open**. It is not a judgement about importance; it is
  the app saying "I do not know what this is, so I will not decide for you."
- A text file and a picture are both **classified**, and both keep their current behaviour. A note
  the person writes is still tracked. A picture the person draws is still tracked and still LFS.
- The state is **visible and reversible** — the row's ignore mark, and the same switch pictures
  already use.

So the promise holds for everything the app understands, and the gap is exactly the set of files it
does not.

### 1.3 The one thing that must not happen

**The agent must never report "pushed" over an ignored file it did not send.** Today `state` counts
unsent *tracked* work. If a binary is ignored, `push` will correctly leave it behind — and the
agent, reading `state`, will see nothing pending and conclude the project is up to date. That is a
silent no-op wearing a green tick.

**Requirement:** `state` must answer with a second number — how many files are ignored — and name
the largest by path. The agent's prompt must say what to do with it: mention it to the person,
never silently ignore it. This is not analytics; it is the sentence that stops a lie.

### 1.4 Steps

| # | Step | File | Proof |
|---|---|---|---|
| 1.1 | ~~Add `shouldIgnoreOnCreate`~~ **DONE** — true only for `UNKNOWN` | `Workspace.kt:495` | tested |
| 1.2 | ~~Wire into both doors~~ **DONE** — `createdByOperator` locks always, ignores only `UNKNOWN`; `createdByAgent` tracks unless `UNKNOWN` | `Workspace.kt` | tested |
| 1.3 | ~~Call `createdByOperator` from the real operator doors~~ **DONE** — `createFile`, `createFolder`, `createImage`, `importFiles`, `importFolder` | `AppViewModel.kt` | door tests, not function tests |
| 1.4 | ~~LFS attribute for a tracked binary~~ **DONE** — `GitService.ensureLfsAttribute`, called before the commit | `GitService.kt`, `AppViewModel.kt` | `ensureLfsAttribute` tested through the track path |
| 1.5 | ~~`state` reports the ignored count and the largest~~ **DONE** | `AgentTools.kt` | test: an ignored 4 KB `.mp4` makes `state` say so |
| 1.6 | ~~Prompt line~~ **DONE** — a file that is ignored is not sent; do not call the project "on GitHub" | `AgentPrompt.kt` | the rule is in the prompt |
| 1.7 | ~~The `UNKNOWN` row gate~~ **DONE** — the gate is gone, so a binary row carries the switch that would send it | `BrowserScreen.kt` | `BrowserChromeTest`: a `.pdf` row offers it |

**Step 1.7 is a real conflict to resolve, not a formality.** The lock/ignore marks are currently
gated on `kind != UNKNOWN`. Under the new rule, an `UNKNOWN` file is the one most likely to be
ignored — so the mark must draw for it. The gate exists because a binary "has no lock to offer in a
menu that would not let you edit it anyway"; that reasoning was about the **lock**, and it is
correct. The **ignore** mark is a different question and must not inherit the exclusion.

### 1.5 What this does not do

- Does not move existing binaries. Only creation.
- Does not add a size screen or a quota warning.
- Does not change how pictures are handled.

---

## 2. Part two — the row caption: size and path, selectable

### 2.1 What was asked

> "a small size and path caption for file/folder rows. and make it selectable so that operator can
> copy the path to clipboard"

### 2.2 The blocker that makes this more than a caption

**`Entry` has no size.** Adding a caption means adding a field, and a field means a cost:

- `Workspace.list()` builds every `Entry`. Sizing every row means a `stat` per row on the listing
  path, which runs on every refresh.
- **Folders have no size of their own.** A folder's size is the sum of everything under it — a walk
  per folder row. A project with `node_modules`-sized trees would make opening a folder slow.
- The listing is already doing a `status()` call for `ignoredPaths`, so one more pass is not free
  but is not catastrophic; it must be measured, not assumed.

### 2.3 Steps

| # | Step | Note |
|---|---|---|
| 2.1–2.7 | **ALL DONE** | `Entry.size` / `Entry.children`, `Workspace.bytesFor`, `Entry.caption()`, `SelectionContainer` around the caption only | `WorkspaceTest` — the awkward byte values and the caption's shape |

**The one measurement the plan demanded before coding 2.3 was not needed in the end**, and the
reason is worth recording: the decision it was meant to settle — "do not walk a folder per row" —
was made from the code rather than a stopwatch. `list()` already stats each child, so `length()` for
a file is free; a folder is counted with the `listFiles()` the listing was already close to doing.
A walk was never on the table, so there was nothing to measure.

### 2.4 The decision to make before coding 2.3

Measure first: how long does `Workspace.list()` take on the largest real project in the test corpus,
with and without folder sizing. If folder sizing doubles it, show child count instead of bytes for
folders and say so in the caption (`12 items`). **A caption is not worth a slow folder.**

---

## 3. Part three — the continuous runner

### 3.1 What was asked

> "a polling loop inside a runner that monitors changes, lets say in the target repo, and on new
> commit it imports, builds, tests and exports artifacts … a runner that uses the github secret to
> run without interruption until it hits github timeout — when we just restart the workflow"

### 3.2 The limits that constrain the design

Measured above:

- **6 hours** per GitHub-hosted job. A loop must exit *before* the ceiling or the run is
  `cancelled` and its artifacts are lost.
- **5 days** on self-hosted. Not available here — there is no self-hosted runner, and provisioning
  one is a different project.
- **cron minimum 5 minutes**, and scheduled runs can be delayed at busy times.
- **A scheduled workflow is disabled after 60 days without repository activity.** A loop that runs
  but never commits is exactly that case — so the loop must touch the repo, or the schedule dies.

### 3.3 The three designs, and the honest comparison

| | **A. Long job with an internal loop** | **B. Cron every N minutes** | **C. `workflow_run` chain (self-retrigger)** |
|---|---|---|---|
| Latency to a new commit | poll interval (30 s) | up to N min | ~seconds |
| Wasted runs | none | one per tick, always | none |
| Survives 6 h ceiling | no — must stop at ~5 h 40 m and restart | inherently | inherently |
| Cost on a public repo | free minutes, but **a runner held for 6 h** | free, bursty | free, bursty |
| Concurrency risk | the loop is one consumer; a second push mid-build must be handled | GitHub's `concurrency` group | same |
| Failure mode | a hung build holds the runner until the ceiling | a missed tick delays everything | **a failed run breaks the chain silently** |
| Restart mechanism | self-retrigger before exiting | cron | `workflow_run` on completion |

**Recommendation: B, with C as the refinement — not A.**

The reason is not cost. It is that **A's failure mode is the worst of the three**: a job that holds
a runner for six hours and then dies takes with it the knowledge of what it was doing, and the
"restart" is a second run that has to rediscover the state. B and C are both stateless per run:
each run asks "what is the newest commit I have built?" and acts.

But the operator's actual goal is not "a long job" — it is **"on a new commit, import, build, test,
export artifacts, so I can audit and iterate without waiting for me to push."** That goal is served
by **B**, and B has a property A does not: **it cannot silently stop.** A dead chain (C) and a dead
loop (A) both stop producing artifacts with no signal; a cron that fails is visible in the Actions
list.

### 3.3b Measured — two facts that change the design

**1. A `workflow_dispatch` on the same ref CANCELS the in-progress push run.**

The workflow has `concurrency: group: ${{ github.workflow }}-${{ github.ref }}` with
`cancel-in-progress: true`. The group keys on **workflow + ref**, not on the event — so dispatching a
run on `development` while a push run is going on `development` **kills the push run**. Measured
today: dispatch `42116224461` cancelled `42116103750`, which was the run for my own commit.

**This is the single biggest hazard in the whole watcher design.** A watcher that dispatches a build
on every tick would cancel the real build on every tick, and the two would starve each other. Any
watcher must either:

- run under its **own concurrency group** (`concurrency: group: watcher-${{ github.ref }}` on the
  watcher workflow, which is separate from the build workflow's group), and *call* the build through
  `workflow_call` — a called workflow does not create its own concurrency group — or
- pass a distinct `ref` (a tag, or a `refs/heads/watch` branch) so the groups differ.

**2. An unchanged run is not free, and it is not cheap either.** A dispatched run with no code change
still ran Deep checks, both test shards and the release build — about 4 minutes of wall clock across
6 jobs — before failing on a compile error that had nothing to do with the dispatch. So a polling
design that "runs the workflow and lets it decide" costs a full pipeline per tick. **The
newest-commit check must happen before the build jobs exist**, which is what §3.4's `newest` job is
for: a tick with nothing new must cost one API call and one short job, not a pipeline.

### 3.3c Measured — the cron cannot live where the work is

**GitHub's own documentation, verbatim: "Scheduled workflows will only run on the default branch."**
This repository's default branch is `main`; the work happens on `development`. So a `watch.yml`
committed to `development` **never fires on its schedule** — the file is invisible to the scheduler,
and `gh workflow run watch.yml` answers `404 Not Found` for the same reason: it resolves workflows
on the default branch.

This was found by trying it, not by reading: the file was committed and pushed correctly
(`git ls-tree origin/development` shows it), and the dispatch still 404'd.

**The options, and what each costs:**

| Option | How | Cost |
|---|---|---|
| **A. Watch from `main`** | Put `watch.yml` on `main`, have it poll `development` and dispatch the build on `development` | `main` gains one file that is not a release. The build workflow already triggers on `development` pushes, so the dispatch target is unchanged |
| B. Make `development` the default | One repository setting | Changes what a clone gets, and what the "default" means for every other tool — too large a change for a watcher |
| C. Poll from outside | A machine somewhere runs `gh workflow run` | Needs a machine that is always on, which this project does not have |

**Recommendation: A.** It is one file on `main` whose job is to look at another branch — which is
exactly what a watcher is. The plan's earlier assumption ("put it next to the work") was wrong and
is recorded here rather than quietly corrected.

### 3.3d Measured — the watcher, working

`watch.yml` is live on `main` and registered (`Watch  .github/workflows/watch.yml  active`).

**A tick with nothing new, measured end to end:** created 03:41:50, finished 03:41:58 — **8 seconds**,
one job, `Build the new commit` **skipped**. The log says:

```
newest:     7e4fa0df03ac92795f595c82fe3b32fac08442a8
last built: 7e4fa0df03ac92795f595c82fe3b32fac08442a8
Nothing new — the newest commit is already built. One API call, no pipeline.
```

That is the number the design was built for. The alternative measured earlier — dispatch the build and
let it decide — was about **4 minutes** of wall clock across six jobs per tick.

**Cost per day at a 10-minute cadence: 144 ticks × 8 s ≈ 19 minutes of runner time, all of it one
short job.** The repository is public, so those minutes are unmetered; the figure matters only if it
ever goes private, and then it is still small next to one push-triggered pipeline.

### 3.3e Found while testing the watcher — `main` cannot build a release

Pushing the watcher to `main` started `main`'s build, and it failed:

```
Execution failed for task ':app:packageRelease'
  KeytoolException: Failed to read key from store "/tmp/muffy-release…jks":
  Tag number over 30 is not supported
```

**"Tag number over 30" is not a Kotlin or Gradle message** — it is a DER/JKS parsing error, which
means the bytes in the `MUFFY_KEYSTORE_BASE64` secret are not a valid keystore. The value is wrong,
not the code.

**It is pre-existing and unrelated to today's work:** the previous `main` run (`42088683442`) had
exactly the same shape — `Test (0)` and `Test (1)` **success**, `Build the release` **failure**.
`development` is green throughout (`42157173047` success), because a development build makes a debug
APK and needs no release keystore.

**What this means for the watcher, which is the reason it matters here:** the watcher watches
`development`, and `development` builds fine — so the watcher works. But **`main` cannot produce a
release until the keystore secret is fixed**, and that is a person's job: it needs the real
keystore, which is not in this repository by design. It is recorded as an **open defect**, not
something to work around by changing the workflow.

### 3.3f Measured — both halves of the watcher

**Half one, nothing new** (run `42158094877`): 8 seconds, `Build the new commit` **skipped**, log says
`Nothing new — the newest commit is already built.`

**Half two, something new** (run `42159332560`):

```
newest:     7ec72c0e6ce68236ca959a9e1ce8332952b5dbbd
last built: 7e4fa0df03ac92795f595c82fe3b32fac08442a8
Started a build for 7ec72c0e…
```

and a `workflow_dispatch` build appeared for exactly that sha. Both jobs succeeded.

**And a third thing this proved, which was already designed and is now measured:** `d9ad267` and
`7ec72c0` are docs-only commits, and **no build ran for either** — `docs/**` is in the workflow's
`paths-ignore`, so the push created no run at all. The watcher then found those commits "not built"
and dispatched a build for them, which is *correct behaviour* rather than a duplicate: the watcher's
question is "has this commit been built", and the answer for a docs-only commit is honestly no. A
person who wants docs-only commits left alone would need the watcher to ask "does this commit touch
anything the build reads", which is a `paths-ignore` check the API does not offer — so the watcher
builds them, at the cost of one pipeline per docs push. **Recorded as a known cost, not fixed.**

### 3.4 What to build

**Phase 1 — measure the baseline (no workflow change).**

The question that decides everything else: **what does a run cost when nothing changed?** Measure
by dispatching the current workflow twice with no commit between. Record: queue time, total, and
which jobs did real work. That number is the price of a polling design.

**Phase 2 — a watcher workflow, cron-driven, that builds only what is new.**

- `schedule: cron '*/10 * * * *'` — every 10 minutes. Not 5: the minimum interval is where GitHub's
  scheduler is most congested, and a 10-minute tick that actually fires beats a 5-minute tick that
  arrives at 11.
- Job 1, `newest`: ask the API for the newest commit on `development`; compare against the last
  commit this watcher built, recorded in a small state file committed to the repo (which also keeps
  the 60-day rule from firing) or in an Actions cache. Exit early if unchanged — **the whole point
  is that an unchanged tick costs one API call, not a build.**
- Job 2, `build`: only when changed, reuse the existing `test` + `build-release` jobs by extracting
  them into a **reusable workflow** (`workflow_call`), so there is one definition of "build" and
  not two that drift.
- Artifacts: uploaded with a name that carries the commit, so the audit is `commit → artifact`.

**Phase 3 — measure what it saves, then resize the shards.**

The current 2 shards give 3 min 13 s of test time. The plan's sharding question is only answerable
after Phase 2 gives real numbers, because a watcher changes the workload from "one run per push,
read by a person" to "one run per change, read by an agent". Measure:

- wall clock per run, before and after;
- **runner-minutes per day** at the watcher's actual cadence — the number that matters if the repo
  ever goes private;
- cache hit rate for `config-cache-v3-tests-<shard>` — a watcher run every 10 min keeps the
  configuration cache warm in a way a push-driven run cannot, so **the cache key may need to stop
  including `github.event_name`**, which currently splits the cache between push and schedule and
  would throw away exactly the warmth the watcher creates.

**That last point is a concrete prediction to test, not a guess.** `config-cache-v3-tests-${{ matrix.shard }}-${{ runner.os }}-${{ github.event_name }}-${{ hashFiles(...) }}`
— a scheduled run has a different `event_name` than a push, so it starts with a **cold cache every
time**. Phase 2 must either fix the key or measure the cost of not fixing it.

### 3.5 What must not be built

- **No self-hosted runner.** Out of scope; it changes the security model of the whole project.
- **No 6-hour loop (design A)** unless Phase 2's numbers show the cron design cannot keep up. The
  operator proposed A; this plan proposes B and says why, and the operator can overrule it with one
  sentence.
- **No secrets in the watcher beyond what the existing workflow already uses.** The "github secret"
  the operator mentions is the `GITHUB_TOKEN` Actions provides; it needs no setup and no storage.

### 3.6 Steps

| # | Step | Proof |
|---|---|---|
| 3.1 | ~~Measure a no-change dispatch~~ **DONE** — ~4 min across six jobs | §3.3b |
| 3.2 | Extract `test` + `build-release` into a reusable workflow | **NOT DONE, and not needed** — see below |
| 3.3 | ~~Watcher: cron + newest-commit check + dispatch~~ **DONE and live on `main`** | 8 s per empty tick; builds a new commit when there is one |
| 3.4 | ~~The `event_name` cache split~~ **DONE** — the key names "is this a PR", which is the only thing the build varies on | `build-and-release.yml` |
| 3.5 | Runner-minutes per day | **DONE** — 144 ticks × 8 s ≈ 19 min/day, one short job each |
| 3.6 | Failure modes | **DONE** — §3.6 below |

**Why 3.2 was dropped.** The plan proposed extracting the test and build jobs into a `workflow_call`
workflow so there would be one definition of "build". The watcher instead **dispatches the existing
workflow**, which achieves the same single definition with no refactor at all — the build is the
build. A reusable workflow would have been a second way to say the same thing, and the risk of
breaking a working 1483-line pipeline to avoid a dispatch was not worth it. Recorded because the
plan said otherwise.

---

## 3b. Part three-and-a-half — the public facade repository

### 3b.1 What was asked

> "rename muffycodes into pechka-ai and move it to private repo, and use public repo for workflows
> and artifacts … when you add a github account to pechka it automatically creates an opensource
> facade repo to use for workflows, and when users add their projects allow them to both add
> existing private projects and create new private projects (now only public are allowed) … add
> private (no licence) option as first inside window where they choose project licence"

### 3b.2 Measured — this works, and here is exactly how far

| Question | Answer | How measured |
|---|---|---|
| Can a workflow in a **public** repo check out a **private** repo? | **Yes**, with a PAT in a secret | `actions/checkout` `token:` input; docs |
| Does the private repo need to be public? | **No** — the token carries the access | same |
| Are a public repo's **Actions minutes** free? | **Yes, unmetered** | GitHub billing |
| Can the artifact **list** be read without auth? | **YES — HTTP 200**, no token | `curl` on run `42108218327` → `total_count: 1` |
| Can the artifact **bytes** be downloaded without auth? | **NO — HTTP 401** | `curl -L .../artifacts/<id>/zip` → 401, 120 bytes |
| Are workflow **logs** readable by anyone? | **Only by a logged-in GitHub account** — any account | GitHub docs, verbatim |

**So the facade is real, and the boundary is sharper than expected:** the *existence, names and
sizes* of artifacts in the public repo are world-readable, but the *contents* are not. What is
world-readable is metadata — and metadata is not harmless: an artifact named
`test-report-milk.txt-failure` tells a stranger that a private project has a file called
`milk.txt`. The plan below closes that.

### 3b.3 The design that keeps user code private

**Two repositories, and the private one is the only one that ever sees code.**

- `pechka-ai` — **private**. Holds the app, the user's projects, everything.
- `pechka-ai-ci` — **public**, created per account. Holds **nothing but the workflow file**:
  a `workflow_dispatch`/`repository_dispatch` receiver that checks out the private repo with a
  token, runs the build, and uploads artifacts.

The public repo's own contents are therefore one YAML file and a README. **No user code is ever
committed to it** — it is checked out, built, and discarded with the runner.

**The leak that must be closed by construction, not by care:**

1. **Artifact names must not carry file names.** Name them `report`, `apk`, `logs` — never
   `test-report-milk.txt`. A name is public metadata; a name is user data.
2. **Job and step names must not carry file names either**, for the same reason — they are public.
3. **Workflow logs are readable by any logged-in account.** A test failure prints the file path and
   the assertion text. So either the private run's logs must not be the public run's logs, or the
   tests must not print content. **This is the hard one and it is not solved by the facade** — it
   is a property of running the tests in a public repo at all.
4. **Secrets are safe.** A public repo's secrets are still secret; the risk is *echoing* them, which
   is why the app's own rule ("never print the token") is not a style rule.

**Recommendation:** ~~the facade is worth building for **the app's own CI** only, because item 3
cannot be closed.~~ **SUPERSEDED — see §3c.** Item 3 *can* be closed: the build's output is redirected
to a file and printed only as ciphertext under a secret-held key, so a public log carries no
sentences. §3c is the design, and it makes the facade safe for a user's private project, not just for
the app's own CI.

### 3b.4 What to build, in order

| # | Step | Depends on |
|---|---|---|
| 3b.1 | Rename the repo to `pechka-ai` and make it **private** — do this **last**, after the facade exists, or CI stops | 3b.2 |
| 3b.2 | Create the public facade repo by hand once, and prove a build runs from it | nothing |
| 3b.3 | Move the workflow YAML into the facade; the private repo keeps the code | 3b.2 |
| 3b.4 | Only then: the app creates a facade repo on account-add (`POST /user/repos`, `private: false`) | 3b.3 |
| 3b.5 | **Private option in the licence window**, listed first, meaning "no licence, private" | independent |

**3b.5 is the small one and can ship first.** The licence list comes from
`GitHub.licences(token)` (`GitHub.kt:196`); a private project has no licence and no public
visibility, so the option is a distinct first entry rather than a licence value. The window is
`Dialogs.kt:481`.

### 3b.5 The one thing to check before believing this

**A public repo's `GITHUB_TOKEN` cannot read a private repo.** The facade needs a **separate PAT**
stored as a secret in the *public* repo — and that PAT is a standing credential to a private
repository, sitting in a repository the world can see the settings of. It must be a fine-grained
token scoped to **one repository, read-only**, and it must be rotated. This is a real, if small,
increase in attack surface, and it is the price of the design. Say so in the plan rather than
discovering it later.

---

## 3c. Encrypted logs — the leak in §3b, closed

### 3c.1 §3b was wrong, and this is the correction

§3b concluded that the log leak *"cannot be closed"*: a failing test in a public repo prints private
text to a public log, so the facade is unsafe for a user's project. **That reasoning was wrong**, and
the operator found the hole in it.

The claim was that the decryption key would have to be in the runner's environment, and therefore in
the log. But the key is read from `env:` mapped to a **secret**, and GitHub masks a secret's exact
value in output. **The key never appears.** The plaintext never reaches the runner's output stream
either, because it is redirected to a file before anything is printed.

Measured on this machine:

```
plaintext → openssl enc -aes-256-cbc -pbkdf2 -pass env:LOG_KEY → base64
          → openssl enc -d                                    → exact original
✓ 9ms
```

Nine milliseconds. The cryptography was never the obstacle.

**So the facade is safe for a user's project after all**, and §3b's recommendation ("app's own CI
only") is superseded. What follows replaces it.

### 3c.2 The idea, in one sentence

The build writes its output to a file, encrypts it with a key the app generated, and prints only the
ciphertext. The app decrypts it when it reads the log.

### 3c.3 Why the shape is this small

`GitHub.logs(token, ref, run)` returns `Map<String, String>` — job name to raw log text — and it is
**the only place this app reads a log** (`GitHub.kt:605`, verified by grep). Every consumer, the
screens and the agent alike, goes through it.

So decryption belongs in **one function**, and every reader becomes correct without being touched.
That is the whole reason this is a small change rather than a refactor.

### 3c.4 The five pieces

**1. The key.** Generated by the app (`SealedBox.random(32)`, already verified in `SealedBoxTest`),
kept in the app's own store (`CredentialStore.secret`/`setSecret`), and written to the repository as
an **environment secret** — not a repository secret.

Why the environment, specifically: `Workflows.kt` already reasons that *"secrets stored in an
environment are only available to workflow jobs that reference the environment"*, so a workflow that
names no environment cannot read the key at all. That reuses machinery that exists and is tested. It
also means **the key is unreachable from any workflow the person has not locked** — the same
approval rule, applied to a new kind of secret.

**`putRepoSecret` does not exist in `GitHub.kt` and must not be added.** The environment scope is the
right one, and `putEnvironmentSecret` is already there with the sealed-box body written.

**2. The workflow.** Three steps replace one:

```yaml
environment: ci                      # on the job — without this the secret is unreachable
...
- name: Run the tests
  env: { LOG_KEY: ${{ secrets.MUFFY_LOG_KEY }} }
  run: |
    ./gradlew ... > /tmp/out.txt 2>&1 || true
    printf 'MUFFYLOG1 ' > /tmp/out.enc
    openssl enc -aes-256-cbc -pbkdf2 -pass env:LOG_KEY -base64 -A \
      -in /tmp/out.txt >> /tmp/out.enc
- run: |
    echo "::group::build output (encrypted)"
    cat /tmp/out.enc
    echo "::endgroup::"
```

`-A` keeps the base64 on one line, so the blob is a single token that survives log-line processing.

**3. The marker.** `MUFFYLOG1 ` on the front. Without it, "this log is not encrypted" and "this log is
encrypted and I cannot read it" are the same bytes to a reader. The version number is there so a
future format change is a refusal rather than a wrong answer.

**4. The decryptor.** In `logs()`, and it must obey the project rule — **a failure says what
happened**:

| Situation | What `logs()` returns |
|---|---|
| A blob, and the key works | the plaintext |
| No marker | the text unchanged (so unwrapped jobs and old runs still read) |
| A marker, no key stored | a sentence naming that the key is missing |
| A marker, wrong key | a sentence saying the key did not open it — **never** gibberish |
| A marker, truncated | a sentence naming the blob as incomplete |

Never a silent no-op, and never a partially-decrypted body presented as the log.

**5. What stays public, and cannot be encrypted.** Branch, commit title, actor, job names, step names,
durations, artifact names and sizes. That is why §3b's rule about artifact and step names carrying no
file names **still stands** — encryption of the log does not cover the run list.

### 3c.5 The honest costs

| Cost | Why it matters |
|---|---|
| **A hang, an OOM kill, or a cancelled run emits no blob** | And that is when the log is most wanted. `cancel-in-progress` is on, so this is a real case, not a hypothetical |
| **Masking is exact-string matching** | The *ciphertext* never contains the key, so that direction is safe. The risk is a step that `echo $LOG_KEY`, or transforms it, prints a form GitHub does not recognise. The key's safety rests on nobody transforming it |
| **Run metadata stays public** | `repository_dispatch` payloads, branch names and commit titles are visible in a public repo whatever the log holds |
| **One more moving part** | A build that used to fail loudly now fails with an encrypted body, so a broken `openssl` step makes the failure unreadable as well as red |

**The alternative that avoids all four** is a self-hosted runner: minutes are not billed and logs follow
the repository. It needs a machine with the Android SDK, and the only always-on computer here is the
phone. Recorded as the option not taken, not as a bad idea.

### 3c.6 Tests, each with what fails if it is removed

| # | Test | Fails if removed |
|---|---|---|
| 1 | A sealed blob decrypts to exactly its text | the reader shows ciphertext |
| 2 | A log with no marker passes through unchanged | every old run and every unwrapped job becomes unreadable |
| 3 | A blob with the wrong key answers a sentence, not bytes | a person reads base64 and calls it a crash |
| 4 | A truncated blob says it is incomplete | a half-decrypted body is presented as the log |
| 5 | No stored key, marker present → names the missing key | the app blames the build for the app's own gap |
| 6 | The key never reaches a trace or a log | the whole scheme is defeated by the app's own diagnostics |
| 7 | The workflow check refuses an encrypt step with no `environment:` | the secret is silently unreachable and every run is unreadable |

Test 7 belongs in `tools/check-workflow.py`, which now reads every workflow in the directory.

### 3c.7 Order, and why

1. **The marker, the decryptor, and tests 1–6.** Offline, no GitHub, no infrastructure. **This is the
   valuable half** — it can be built and proven today, and it is what makes a log readable at all.
2. **Key generation and storage** (`SealedBox.random`, `CredentialStore`).
3. **Writing the key as an environment secret** (`putEnvironmentSecret`, which exists).
4. **The workflow steps**, plus test 7 in the checker.
5. **The facade repository**, then rename to `pechka-ai` and make it private — **last**, or CI stops.

Step 1 is deliberately first because it is the only step that is fully testable without a device or a
second repository, and because the rest of the plan is worthless if the reader cannot read.

### 3c.7b What is built, as of now

| Step | State |
|---|---|
| **1. Marker, decryptor, tests 1–6** | **DONE** — `EncryptedLog.kt` + `EncryptedLogTest` (11 tests), CI green at `92b2b97` |
| **2. Key generation** | **DONE** — `EncryptedLog.newKey()`, base64 so no shell or YAML rule can need quoting |
| **3. Key storage and use** | **DONE** — `KEY_NAME` in the account's secret store; `runs` opens the log with it, CI green at `96033b0` |
| **4. The workflow steps** | **NOT STARTED** — nothing encrypts yet, so nothing needs a key. The reader is live and passes plain logs through unchanged |
| **5. The facade** | **NOT STARTED**, and correctly last |

**Two real bugs were caught by CI rather than by reading, and both are worth recording:**

1. **`PBEKeySpec`'s last argument is a length in BITS, not bytes.** Passing `KEY + IV` asked for 48
   *bits* — six bytes — and every derivation threw `IndexOutOfBoundsException` while every constant in
   the file read as though it were right. `openssl -pbkdf2` derives 48 *bytes*, so the number must be
   `(KEY + IV) * 8`. A length unit that is silently wrong is exactly the shape of bug a format test
   exists to catch, and [the format matches what openssl writes] did catch it.
2. **The blob was found by position, not by shape.** `read()` took the first non-blank line after the
   marker. The runner writes its own lines and this workflow does not control their order, so a
   runner message arriving first was handed to the base64 decoder as if it were ciphertext. The blob
   is now found by shape — one long unbroken run of base64 — and every other line is kept as plain
   text, which is what they are.

**Step 4 is deliberately still open.** Turning encryption on before the key exists would make every
build log unreadable, so the order is: the reader first (live now, harmless), then the key, then the
encryption. That is the reverse of the usual "turn it on and see", and it is the reverse because the
failure mode of getting it wrong is a log nobody can read.

### 3c.7c Done by hand, and what it proved

A throwaway branch, a deliberate failing test, and a real dispatch. The test asserted:

```kotlin
assertEquals(
    "the probe's expected sentence, which must never appear in a public log",
    "expected-value-that-must-stay-private",
    "actual-value-also-private",
)
```

**The log.** The step redirected the build's output to a file, sealed it, and printed only:

```
MUFFYLOG1 U2FsdGVkX19S1D9GGSTB…
```

`grep -c "expected-value-that-must-stay-private"` over the **whole public log** → **0**. The blob was
23 KB; decrypting it with the environment key printed the build's output, including
`EncryptedLogTest > PROBE a deliberate failure … FAILED`. **So the scheme works exactly as designed.**

**The artifacts — and this is the part that changes the design.** The `test-report` artifact from the
same run contains:

```xml
message="org.junit.ComparisonFailure: the probe's expected sentence, which must never appear in a
public log expected:&lt;[expected-value-that-must-stay]-private&gt; but was:&lt;[actual-value-also]-private&gt;"
```

**The private sentence is in the artifact, in plain text.** Encrypting the log while uploading the
report achieves nothing at all.

And the artifact is not private: **download requires any logged-in GitHub account** —
`401` anonymous, `200` with a token. Not anonymous, but "anybody with a GitHub account" is not a
boundary worth the name. Measured, and corroborated by `actions/upload-artifact#51`.

**A third leak the probe found by accident.** The blob was 23 KB, and most of it was Gradle's own
download chatter. That is harmless — but it proves the redirect covers *everything* the step prints,
which is the property the scheme depends on.

### 3c.7d What this means: the log is the smaller half

| What leaks | Encrypted by the log scheme? | Fix |
|---|---|---|
| The build's stdout/stderr | **Yes** — measured, 0 occurrences in the public log | the redirect + seal, done by hand and working |
| The JUnit XML report | **No** | seal the report before `upload-artifact`; the app unseals it |
| Stack traces and `file:line` in that report | **No** | same |
| `system-out` captured by JUnit | **No** | same |
| Artifact **names** and sizes | **No** — public metadata | already a rule: never a file name |
| Job and step names, branch, commit title | **No** | already a rule |

So `artifacts` must unseal too, and the report must be sealed before it is uploaded. **The same
`EncryptedLog` reader serves both**, because a sealed file and a sealed log are the same bytes: marker,
`Salted__`, salt, ciphertext.

**The report needs a marker in its own right.** A sealed `.zip` is not readable by a person either, so
the artifact carries `report.zip.enc` alongside a one-line `report.zip.enc.txt` saying it is sealed —
otherwise "the report is encrypted" and "the report is empty" look the same to whoever downloads it.

### 3c.7e Built and proven, end to end

Run `42180308449`, all five jobs green:

```
Test (0): success   Test (1): success   Build the release: success
Deep Checks: skipped                Deliver: success
artifacts: build-bundle 18.4 MB · scenarios 850 KB · dev-apk 17.5 MB
```

- **The `scenarios` artifact is sealed** — it downloads as `scenarios.zip.enc` + `.enc.txt`, and
  `find -name "*.png"` inside it returns nothing. The screenshots are not in the clear.
- **The bundle `Deliver` assembled holds real pictures** — `scenarios/01-first-open/after.png` and so
  on, because that job opens the seal. So the sealing costs the person nothing: what they download is
  the report they always got.
- **The log is sealed** — `MUFFYLOG1 U2FsdGVkX1+…`, and the private sentence appeared zero times in the
  whole public log on the probe run.

**Five bugs, all caught by CI rather than by reading.** Three are worth naming because each was
invisible in the code that produced it:

1. **`PBEKeySpec` takes BITS.** `KEY + IV` asked for 48 *bits* — six bytes — and every derivation threw
   while every constant read as though it were right.
2. **`zip.readBytes()` reads the whole stream, not the entry.** A `ZipInputStream` runs past the end of
   the current entry into the rest of the archive, so the note file came back glued to the ciphertext.
   The limit is the entry's declared size; `read()` also overruns, so stopping at the stream's end is
   not the same thing.
3. **A sealed file's plaintext is base64 of its bytes.** `openssl -base64` writes base64 and the file is
   that base64 decoded — so `openBytes` decrypted correctly and then decoded a second time. **Found by
   decrypting the test-report artifact of my own failing run with the key**, which is the scheme
   working as intended: the failure was readable only to someone holding the key.

**Two jobs needed `environment: ci` and the checker refused both edits** — `test` and `deliver`. Each
time the shape was the same: the seal would have run under an empty password, every artifact unreadable,
and nothing reporting a problem. The rule written in §3c.6 paid for itself twice within the hour.

### 3c.8 What not to do

- **Do not encrypt the artifacts.** Their bytes already need authentication (401 measured); only their
  names are public, and names are fixed by rule.
- **Do not add `putRepoSecret`.** Environment scope is the safer one and the machinery exists.
- **Do not try to encrypt the runner's own lines** — the checkout, setup and runner messages are
  emitted by processes this workflow does not own. Wrap the steps that run *our* code.
- **Do not use the key to encrypt anything but the log.** A key reused for a second purpose is a key
  whose leak costs more than one thing.

### 3.6 Failure modes, and how a person notices

| What breaks | What it looks like | How it is noticed |
|---|---|---|
| The cron stops firing | Commits land, nothing is built | The Actions tab shows no `Watch` runs for hours. **A scheduled workflow is disabled after 60 days with no repository activity** — a repository nobody touches is exactly the case, and GitHub says so on the workflow |
| A tick is delayed | A build starts later than ten minutes | GitHub's scheduler is congested at the top of the hour; the delay is documented and nothing is lost |
| The watcher dispatches but the build fails | Red run in the build workflow, named by sha | The `Deliver` job never runs, so no artifact appears — which is the same signal as "not built yet", and the reason the build's own failure must be read |
| The watcher itself fails | A red `Watch` run | Its only job is one API call; a failure there means the token or the API, not the code |
| Someone pushes twice quickly | The second push cancels the first run (`cancel-in-progress`) | Correct — the first run's commit is superseded, and the watcher builds the newest |
| **`main` cannot build a release** | `packageRelease` fails on the keystore | §3.3e. Not a watcher problem; a person must fix the secret |

## 3d. The runner question: why not poll every few seconds?

The operator asked: *"I heard you saying something about 5 min cron job, why 5 min? why not to use
continuous poll that checks every couple seconds for changes?"* and *"if cron is slow, can we implement
our own loop inside the runner?"*

### 3d.1 The premise is wrong, and measuring it settles the question

**A push already starts a build in about five seconds.** Measured, four pushes in a row:

```
commit 08:07:49 → run created 08:07:54    (+5s)
commit 08:04:01 → run created 08:04:06    (+5s)
commit 07:57:46 → run created 07:57:51    (+5s)
commit 07:52:09 → run created 07:52:15    (+6s)
```

That is GitHub's `push` event, which is a **webhook** — GitHub tells the runner, the runner does not
ask. There is nothing to poll for on a push, and no loop can beat a notification that already arrives in
five seconds.

### 3d.2 And the cron is worse than advertised, measured

`watch.yml` asks for `*/10 * * * *`. What actually ran:

```
07:50:35  schedule
06:37:18  schedule      ← 73 minutes later
05:37:45  schedule      ← 60 minutes later
04:39:53  schedule      ← 58 minutes later
```

**Three ticks in three hours, not eighteen.** That is the documented behaviour — GitHub's own docs say
scheduled runs *"can be delayed during periods of high loads"* and *"if the load is sufficiently high
enough, some queued jobs may be dropped"* — and it is far worse in practice than the 5-minute minimum
suggests.

**So the watcher's cron is not a 10-minute cadence. It is an hourly one, unreliably.** That is the
opposite of what it was built for.

### 3d.3 Why not a loop inside the runner

The operator's second question, and it is the right instinct. Three measured limits decide it:

| Limit | Value | What it means for a loop |
|---|---|---|
| `GITHUB_TOKEN` rate limit | **1,000 requests/hour/repository** | a poll every 2 seconds is 1,800/hour — **the loop exhausts its own budget in 33 minutes** and then 403s for the rest of the hour |
| GitHub-hosted job ceiling | **6 hours** (self-hosted: none) | the loop dies every six hours and something must restart it |
| Workflow run ceiling | 72 hours | the same, later |

A 30-second poll is 120 requests/hour — affordable, and it would beat the hourly cron by 120×. But the
loop still dies at six hours, and **a dead loop is invisible**: it looks exactly like "no commits
happened". The cron's worst property is that it is late; the loop's worst property is that it can stop
without saying so.

### 3d.4 What is actually needed

**Nothing, for the push case.** A push builds in five seconds already. The watcher was built for a
problem that the `push` trigger had already solved — it only ever helps when a push *fails to create a
run at all*, which happens when `paths-ignore` skips a docs-only commit.

**The honest options, in order of cost:**

1. **Delete the watcher's cron and keep it as a manual dispatch.** It costs nothing, and a person who
   suspects a missed build can press it. The five-second push trigger is the real mechanism.
2. **Keep the cron at `*/10`, knowing it is hourly in practice.** Cheap, and eventually catches a
   missed build. Do not believe the schedule.
3. **A 30-second loop in a 6-hour job, restarted by a cron.** Fast and reliable, but two mechanisms
   where one would do, and the loop's silence is its worst failure.
4. **A self-hosted runner on the phone.** No job ceiling, no billing, logs follow the repository. Needs
   an Android SDK on the device.

**Recommendation: 1, plus keeping the push trigger as the primary.** The measured five seconds makes
every polling design a slower, more fragile copy of a webhook.

**The one thing that would change this:** if a build ever needs to start from something *other* than a
push — a schedule, a webhook from another service, a person's request — then a queue is needed, and the
watcher becomes the only way to drain it. That is not today's shape.

### 3d.5 Is the runner stateful? No — and the operator's idea was about something else

The operator's clarification: *"my initial idea with polling was about statefull runners."*

**Every GitHub-hosted job starts on a fresh virtual machine.** Nothing on disk survives between runs.
What survives is only what is explicitly saved and restored, and this pipeline already does that:

| What | Where it lives | Measured |
|---|---|---|
| Gradle's configuration cache | `actions/cache` → `.gradle/configuration-cache` | **"Configuration cache entry reused."** |
| Robolectric's runtime jars | `actions/cache` → `~/.m2/repository` | 32 "Cache restored" lines in one run |
| Gradle's own build cache | `actions/cache` | **31 tasks `FROM-CACHE`, 8 `UP-TO-DATE`, 20 executed** |

So the answer is: **stateless machine, stateful build** — and the state is already there. A stateful
runner would add a machine that persists, which would save the *restore* time, not the compile time.

**What a persistent machine would actually save, measured on a cache-hit run:**

```
Build the release   170s
Test (0)            112s
Test (1)            152s
Deliver              11s
```

Of a 112-second Test job, 31 of 51 tasks are already `FROM-CACHE`. The remainder is: Java setup,
Gradle distribution, checkout, the cache restore itself, and 20 tasks that genuinely execute.

**So the saving a persistent runner offers is the fixed setup — tens of seconds — not the work.** That
is a real saving, and it is the reason self-hosted runners exist. But it is not the saving the polling
idea was aiming at, because the thing that makes a build slow here is *executing 20 tasks*, and a
persistent disk does not make those tasks faster.

**Where a persistent runner would genuinely win:**

1. **No six-hour job ceiling**, so a long-running loop becomes possible at all.
2. **No per-minute billing**, so a private repository stops costing money.
3. **The Android SDK and emulator stay installed** — `setup-java` and the SDK download are pure
   overhead today, and on a phone they would be paid once.
4. **Logs follow the repository**, which removes the entire encryption problem in §3c for the app's own
   CI.

**Where it loses:** it needs a machine with an Android SDK that stays awake. The only always-on
computer in this picture is the phone, and running the build on the device being built is a different
problem — one that also makes the phone hot and slow for the person using it.

### 3d.6 What this means for the polling question

The original idea — poll every couple of seconds from a stateful runner — would have been the right
design **if the runner were stateful**. On GitHub-hosted runners it is not, so the poll would be buying
nothing that the webhook does not already deliver in five seconds, while spending the API budget that
§3d.3 measured.

**The idea becomes right again on a self-hosted runner**, and there it is not needed either: a
self-hosted runner *is told* by GitHub when a job is queued. Polling is what you do when you cannot be
told. Both GitHub-hosted and self-hosted runners can be told.

### 3d.7 What setup costs, measured to the step

A Test job, cache-hit, timed from the run's own log:

```
Set up job                                            3.2s
Check out the code                                    1.5s
six check scripts                                     1.1s
Set up Java                                           0.1s
Set up Gradle                                        11.6s   ← the big one
Remember the runtime the tests need                   2.1s
Remember how the project is put together              1.0s
──────────────────────────────────────────────────────────
setup, job start to tests start                      20.7s
the tests themselves                                 78.3s
checks and teardown after                            10.3s
```

**So the fixed cost is 20.7 seconds, and 11.6 of it is `Set up Gradle`** — which is the wrapper
downloading and verifying the Gradle distribution, then restoring the configuration cache.

**A persistent runner saves that 20.7 seconds, and only that.** On a 112-second job it is 18%. It does
not touch the 78.3 seconds of tests, which is the actual work. **On the 170-second release job it is 12%.**

This is worth having and it is not transformative. Stated plainly because the polling idea was
motivated by the belief that setup dominates, and it does not.

### 3d.8 Is polling the only way to tell a runner about a job? **No — and no polling is involved anywhere.**

**The runner does not poll for work, and neither does the workflow.** The mechanism is a **long poll**:
the runner application opens an HTTPS connection to GitHub and **holds it open for 50 seconds**. If a
job arrives during those 50 seconds, GitHub answers on that already-open connection. If nothing
arrives, the connection times out and the runner opens another.

So the runner is *told*, and it is told within milliseconds of a job being queued — because the
connection is already open and waiting. The 50-second timeout exists only so a silent connection does
not stay open for ever; it is not a poll interval, and it does not add latency to a job.

```
GitHub-hosted runner   →  same mechanism: GitHub owns both ends and pushes the job
self-hosted runner     →  long poll, 50-second connection, told within milliseconds
```

**Three consequences, and they matter for the design:**

1. **There is nothing to poll.** A loop that asked "is there a job" every two seconds would be asking a
   question GitHub already answers unprompted. It would also spend the 1,000-requests-per-hour budget
   that §3d.3 measured, for information that arrives free.
2. **A self-hosted runner would be no faster to start than a hosted one.** The job is delivered in
   milliseconds either way; the difference is only what is already installed on the disk.
3. **The `push` → run-created gap of 5–6 seconds** measured in §3d.1 is not the runner asking; it is
   GitHub's own dispatch, before any runner is involved.

**The one case where polling is genuinely the only way:** a machine that cannot receive an inbound
connection *and* cannot run the runner agent — for example a build that must be started by something
outside GitHub entirely. That is not this project.

## 4. Part four — the audit of today's work

The operator asked: *"audit that the functionality that we implemented today fully implemented,
wired, tested, traced and fix any gaps."*

Today's work is: the agent-git plan (11 steps) and the naming change. **Known gaps found while
grounding this plan, before any audit pass:**

| # | Gap | Severity | Evidence |
|---|---|---|---|
| A1 | **`createdByOperator` has no caller in the app.** The rule "operator files are locked + ignored" does not fire. | **High** — a delivered feature that does nothing | `grep -rn createdByOperator app/src/` → tests only |
| A2 | **`state`, `pull`, `push` have no trace line.** Every other tool that changes something records one. | Medium | `grep -c "Trace\." AgentTools.kt` → 4, none for these |
| A3 | **`tools/check-taps.py` vocabulary** may not list the three new tools, so the vocabulary gate cannot catch A2 | Medium | grep found no `state`/`pull`/`push` entries |
| A4 | **The `UNKNOWN` mark gate** excludes the rows that will now be ignored most | Medium | `BrowserScreen.kt:959` |
| A5 | **`push`/`pull` have never run against a real GitHub remote.** Offline suite only. | Medium | no integration test; `lfs-gate` is archived and the token is write-refused there |

### 4.1 Audit method — one row per feature, four columns

For each of the 14 items delivered today, record: **implemented** (the code exists), **wired**
(something in the app calls it), **tested** (a test fails if it breaks), **traced** (the log says it
happened). A feature is only "done" with all four.

The list to walk:

1. `ignoreInGit` two-way toggle · 2. `GitService.track` · 3. `GitService.isIgnored` ·
4. `ignoredPaths` + row state · 5. unsync machinery deleted · 6. `state` tool · 7. `pull` tool ·
8. `push` tool · 9. creation defaults (operator/agent) · 10. Sync button removed (row + menu) ·
11. `SyncDialog` deleted · 12. prompt corrected · 13. `cleanName` removed · 14. `nameProblem` added

### 4.2 The table, filled in

Every row was checked by grep against the repository, not from memory. **Wired** means something in
the app calls it; **tested** counts test references; **traced** means the log records the act.

| # | Feature | Implemented | Wired | Tested | Traced | Verdict |
|---|---|---|---|---|---|---|
| 1 | `ignoreInGit` two-way toggle | yes | yes (1) | yes (7) | yes — `ignore`/`track` via a ternary, which the checker's TERNARY rule does read | **done** |
| 2 | `GitService.track` | yes | yes (4) | yes | via #1 | **done** |
| 3 | `GitService.isIgnored` | yes | yes (1) | yes | n/a (a question, not an act) | **done** |
| 4 | `ignoredPaths` + row state | yes | yes (2) | yes | n/a | **done** |
| 5 | unsync machinery deleted | yes | — | — | — | **done** (`cleanName` residue is one comment in a test) |
| 6 | `state` tool | yes | yes | yes | **deliberately not** — a question is not an act; the comment says so | **done** |
| 7 | `pull` tool | yes | yes | **was 0 → now 1** | yes (`Trace.note`) | **gap found and fixed** |
| 8 | `push` tool | yes | yes | yes (3) | yes (`Trace.note`) | **done** |
| 9 | creation defaults | yes | **was 0 → now 4 doors** | yes (door tests) | via #1 | **gap found and fixed** |
| 10 | Sync removed (row + menu) | yes | — | yes (absence asserted) | n/a | **done** |
| 11 | `SyncDialog` deleted | yes | — | n/a | n/a | **done** |
| 12 | prompt corrected | yes | yes | yes | n/a | **done** |
| 13 | `cleanName` removed | yes | — | yes | n/a | **done** |
| 14 | `nameProblem` added | yes | yes (9) | yes (19) | n/a | **done** |

**Three real gaps were found, and all three were invisible to the tests that existed:**

1. **`createdByOperator` had no caller.** The rule was written, documented, and tested in isolation,
   and nothing in the app invoked it. Fixed: four doors now call it (`createFile`, `createFolder`,
   `createImage`, `importFile(s)`, `importFolder`).
2. **`pull` had no test at all.** Fixed: two tests, one for the quiet answer and one for the ignored
   count in `state`.
3. **An assertion that could never pass.** `hold(...) == null` compares an enum to null; the
   compiler folded it away and the test proved nothing. Fixed to `Hold.NONE`.

**One gap that cannot be closed here:** `push`/`pull` have never run against a real GitHub remote —
the offline suite is all that exists, and the fine-grained token is write-refused on the one repo
that was available for probing. It is recorded as **unproven**, not as done.

### 4.3 Steps

| # | Step |
|---|---|
| 4.1 | Fill the 14×4 table; every empty cell is a finding |
| 4.2 | Fix A1–A4; A5 gets an integration test that runs against a real remote or is explicitly recorded as unproven |
| 4.3 | Add the three tool names to `check-taps.py` vocabulary and a `TraceCoverageTest` case each |
| 4.4 | Update `tools/test-map.txt` for every source/test pair touched |
| 4.5 | Record the table in the plan, so the next reader sees what was checked |

---

## 5. Order of work, and why

1. **§4 audit first.** It is cheap, it may change what §1 has to touch (A1 and §1.3 are the same
   call site), and finding a gap after building on it is the expensive order.
2. **§1 binary tracking.** Small, and it depends on A1 being fixed.
3. **§2 the caption.** Needs the size measurement in 2.4 before the folder decision.
4. **§3 the runner.** Largest, slowest to verify, and its numbers are only meaningful once the
   suite is settled.

## 6. Verification rules for autonomous work

- **CI is the only compiler.** Nothing is "done" until a run is green.
- `python3 /tmp/qc.py` must print `clean` before any commit.
- **One commit per step**, each with the reason the change was needed — not a list of files.
- **A test that would pass without the change is not a test.** Every new behaviour gets a test that
  fails when the behaviour is removed; where that is impossible, say so in the plan.
- **When a measurement contradicts this plan, the plan is wrong** — record the correction in place,
  as was done for the LFS gate.
- Do not add a switch for a debug aid. Do not add analytics the operator did not ask for.

## 7. What is explicitly out of scope

- LFS budget/usage analytics (operator: "no lfs budget analitics needed").
- Self-hosted runners.
- History rewriting.
- Any change to how pictures are stored.
- A 6-hour loop, unless §3's measurements justify it.


---

## 8. What shipped, what was found, what is open

### 8.1 Shipped

| Piece | State |
|---|---|
| **A binary waits; a note does not** | A file the app cannot classify is ignored on creation, whoever made it; a text file and a picture are tracked and keep their history. Tracking a binary writes its LFS line before the commit. |
| **The person's creation default actually fires** | `createdByOperator` was dead code; five doors call it now (`createFile`, `createFolder`, `createImage`, `importFiles`, `importFolder`). |
| **The row caption** | `12.4 MB · /data/user/0/…`, selectable on its own so the row's tap and long-press still work. A folder says `12 items`, never a byte count. |
| **The watcher** | Live on `main`. An empty tick costs 8 seconds and one job; a new commit is built. |
| **The cache key** | Names "is this a PR", which is the only thing the build varies on — so a dispatched build can restore the entry a push build wrote. |
| **Private projects** | The licence window offers "Private (no licence)" first and starts on it. |
| **The workflow checker** | Reads every file in `.github/workflows/`, proven by catching a planted error in `watch.yml`. |
| **The stale lock cache** | Removed. It was stale by construction — `AppViewModel.workspace` builds a new `Workspace` on every access, so a cache could never be shared. |
| **`pull`'s missing test** | Written. |

### 8.2 Found, and worth more than the features

1. **An ignored file is not committed.** `git add .` honours `.gitignore`, so ignoring also means no version and no undo. This is why the rule is narrow — the broad version was implemented first and fourteen tests said no.
2. **A `workflow_dispatch` on the same ref cancels an in-progress push run.** Measured: my dispatch killed my own commit's build.
3. **A scheduled workflow only runs on the default branch.** Measured: `watch.yml` on `development` 404s on dispatch. It lives on `main` now, watching `development`.
4. **A companion member is not reachable by callable reference.** `Workspace::bytesFor` compiles as "inapplicable candidate". Third time today the companion has cost a compile.
5. **`main` cannot build a release** — the keystore secret is invalid (`Tag number over 30`). Pre-existing, unrelated, and a person's to fix.
6. **A test that could not pass**: `hold(...) == null` compares an enum to null; the compiler folded it away.

### 8.3 Open, and not pretendable

| # | Open | Who |
|---|---|---|
| 1 | **`MUFFY_KEYSTORE_BASE64` is not a valid keystore.** `main` cannot produce a release until it is. Needs the real file. | the person |
| 2 | **`push`/`pull` have never run against a real GitHub remote.** The offline suite is all there is; the one available repo was write-refused. | needs a writable test repo |
| 3 | **`MediaStore` import is untested on a device.** No device here. | the person, on the phone |
| 4 | **The facade repository is researched, not built.** §3b has the measured facts and the design; the leak (public logs of private test failures) is real and unsolved. | a decision, then work |
| 5 | **`sweep`'s cost on a large project** is unmeasured — it walks every tree at app start. | needs a real project |
| 6 | **A docs-only push costs a pipeline**, because the watcher honestly answers "not built". | known cost, accepted |

### 8.4 The rule this session kept proving

**A feature is not done until something fails when it is removed.** Three of the six findings above were features that looked finished — a function with no caller, a tool with no test, an assertion that could not fail. Each was invisible to the tests that existed, and each was found by asking "what would break if this were deleted" rather than "does this work".
