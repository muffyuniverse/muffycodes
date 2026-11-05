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
| 1.4 | Tracking a binary adds an LFS attribute line for its extension | `GitService.kt`, `Workspace.kt` | test: track a `.pdf`, assert `.gitattributes` names `*.pdf filter=lfs` |
| 1.5 | `state` reports the ignored count and names the largest | `AgentTools.kt` | test: an ignored 2 MB file makes `state` say so |
| 1.6 | Prompt line: an ignored file is not sent; say so rather than assume | `AgentPrompt.kt` | prompt contains the rule |
| 1.7 | Row mark already exists (`ignored: Boolean`) — confirm it draws for `UNKNOWN` | `BrowserScreen.kt` | the `entry.kind != EntryKind.UNKNOWN` gate at line ~959 **excludes** unknown rows; verify this is still right |

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
| 2.1 | Add `size: Long?` to `Entry` — `null` for a folder that was not sized | nullable is the honest type: "not computed" ≠ "empty" |
| 2.2 | Files: `File.length()`, already a `stat` the listing effectively does | cheap |
| 2.3 | Folders: **do not** walk by default. Show the child count instead, or size only the open folder | a walk per row is the thing that makes this slow; measure before choosing |
| 2.4 | Format helper — `Bytes` to `1.4 KB` / `12.4 MB`, one function, tested with the awkward values (0, 1023, 1024, 1 GiB) | extract as a function per project rule |
| 2.5 | Caption under the name: `12.4 MB · groceries/notes/milk.txt` | the path is what the person copies; the size is what they read |
| 2.6 | `SelectionContainer` around **only** the caption, so the row's tap still opens and long-press still selects | selecting the whole row would break the gestures |
| 2.7 | Test: the caption reads back the exact path; a folder row says its own path with a trailing slash | |

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
| 3.1 | Measure: dispatch with no change, record cost | numbers in the plan |
| 3.2 | Extract `test` + `build-release` into a reusable workflow | the existing workflow calls it and stays green |
| 3.3 | Watcher workflow: cron + newest-commit check + call | a tick with no change costs < 30 s |
| 3.4 | Fix or measure the `event_name` cache split | cache hit rate before/after |
| 3.5 | Measure runner-minutes/day and shard sizing | numbers in the plan |
| 3.6 | Document the failure modes and how a person notices | a section in the plan |

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

**Recommendation:** the facade is worth building for **the app's own CI** (the app is going to be
open source anyway — it is public today). It is **not** safe as the default for a **user's private
project**, because item 3 cannot be closed: a failing test in a public repo prints private text to a
public log. For user projects the honest options are (a) leave them public-only, or (b) run their CI
in *their* private repo and pay the minutes.

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
