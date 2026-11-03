# Subagents — the audit

Status: **built.** Nine steps, each with its tests, each green on CI. This document is what an audit of
the whole feature found: every element, the test that would fail if it broke, the trace line it writes,
and the things that are **not** covered — stated rather than left to be discovered.

The design is `COUNTERPROPOSAL-subagents.md` → `-2` → `-3`. This is what happened when it was built.

---

## 1. What was built, in order

| # | step | commit | what it made |
|---|---|---|---|
| 1 | `RepoLocks` | `fb0d681` | one `ReentrantLock` per repository, held by every `ProjectGit` write |
| 2 | `reason` removed | `75482a4` | the tool and its spec; the **reader** kept |
| 3 | `Scope` | `b9b8f6a` | the account or one project, enforced in `resolve`/`mayWrite` |
| 4 | the missing-commands paragraph | `07bb26a` | generated from the two tool lists |
| 5 | `SUB` + the `subagent` tool | `d67ce6f` | the row kind, the registry, spawn/write/list |
| 6 | `SubagentRun` | `9942ff6` | one subagent's turn, its own record, its report |
| 7 | wake on `SUB` | `4bab4b7` | a report wakes the chat agent, once |
| 8 | the settings block | `792bd9a` | count + the one warm ceiling; `stopped` as a third state |
| 9 | stale-read | `a88a440` | the trace line and the prompt paragraph |

Two of those — steps 1 and 2 — were worth landing whether or not subagents were ever built, which is
why they came first.

---

## 2. Every element, its test, and its trace

### Step 1 — `RepoLocks`

| element | the test that fails if it breaks |
|---|---|
| a hold is exclusive | `RepoLocksTest.a hold is exclusive, so a second writer waits rather than overlapping` |
| one lock per repository, not per object | `…two ProjectGit objects over one project share the lock` |
| the holder is named and forgotten | `…a hold says who holds it, and forgets afterwards` |
| a failing writer releases | `…a writer that fails still releases the lock` |
| a nested hold restores the outer name | `…a nested hold keeps naming the outer holder when it ends` |
| a real commit holds it | `…a real commit through ProjectGit holds the lock and reports in-flight` |
| a slow hold is traced | `…a hold that is too slow is traced, and a quick one is not` |

Traces: `repo-wait`, `repo-hold` — separate lines because they are separate faults.

**What this fixed, and it was a bug before subagents existed.** `retryingLock` said *"every write is
made on one worker thread"*; there are two, and `inFlight = true` was passed **nowhere**. The first of
two writers to hit a lock deleted the other's live `index.lock` and retried.

**And I had `inFlight` backwards on the first attempt.** It reads like "a write is in flight, be
careful" and means "a writer of ours is holding the repository, so a lock failure is NOT a corpse —
leave it alone". Passing `true` from inside the hold disabled stale-lock recovery, which
`ProjectGitTest.a lock left by a killed app does not lose the change` caught. The hold must pass
`false`.

### Step 2 — `reason` removed

| element | the test |
|---|---|
| the tool is gone, and the call is refused as unknown | `AgentTest.the reason tool is gone…` |
| an old `reason` row is still read as the reply's paragraph | `…a reason row already in a log is still merged…` |
| the tool list is 22 | `AgentToolsTest.there is no wait tool…` |

The reader is kept **on purpose**: every log written before this change has `reason` rows in it, and a
log the app can no longer parse is worse than a command nobody can call.

### Step 3 — `Scope`

| element | the test |
|---|---|
| its own project is readable | `ScopeTest.a scoped agent reads its own project` |
| another project is not, and the refusal names it | `…cannot read another project…` |
| **`tree ../` is refused** | `…cannot list another project…` |
| **`search` does not cross** | `…a scoped agent's search does not reach another project` |
| writing across is refused, and nothing is written | `…cannot write into another project` |
| `restore` across is refused | `…cannot restore a version of another project's file` |
| the account root is outside | `…the account's own folder is outside a project's scope` |
| two boundaries keep two sentences | `…a path outside the account is refused by the account's rule` |
| the chat agent is unchanged | `…the chat agent still reaches the whole account` |

`search` needed its own check: it is the one tool that never asks `resolve` for a path.

### Step 4 — the generated paragraph

| element | the test |
|---|---|
| generated from two lists | `AgentTest.what an agent lacks is generated…` |
| an agent with everything is told nothing | `…an agent with every tool is told nothing…` |
| it sits before the project heading | `…the missing-commands paragraph is in the system layer…` |

### Step 5 — `SUB` and the tool

| element | the test |
|---|---|
| ids never come back | `AgentSubagentsTest.ids are issued in order and never come back` |
| a retired one keeps its place and task | `…a retired subagent stays retired…` |
| per-project ids | `…each project has its own ids` |
| an unissued id is null | `…an id this project never issued is null…` |
| lowering retires oldest-idle | `…lowering the limit retires the first idle…` |
| a busy one is never retired | `…a busy subagent is never retired…` |
| raising creates nothing | `…raising the limit creates nothing` |
| two instances share the file | `…two instances over one account see the same subagents` |
| unparseable file loses nothing | `…a file that will not parse is not a reason…` |
| start answers at once | `SubagentToolTest.starting a subagent answers at once…` |
| the ceiling refuses with a sentence | `…the ceiling is a promise…` |
| retired write names its folder | `…writing to a retired subagent is refused…` |
| an unissued id is not "retired" | `…writing to an id that never existed…` |
| a report is a `SUB` row, as a user message | `…a report reaches the chat agent as its own row…` |
| it does not merge into the assistant | `…a report is not merged into the assistant's own paragraph` |
| a big report names its size | `…a big report says its own size…` |

### Step 6 — `SubagentRun`

| element | the test |
|---|---|
| its own record, not the chat agent's | `SubagentRunTest.a subagent writes its own record…` |
| the record is inside the project | `…so the existing exclude keeps it out of git` |
| it cannot cross projects | `…cannot read another project…` |
| it can write its own | `…can read and write its own project` |
| its words become a result-shaped report | `…what it says becomes a report…` |
| silence still reports | `…a subagent that says nothing still reports…` |
| a failure reports as `1` | `…a failure is reported too…` |
| a gone project does not run | `…a project that is gone does not run…` |

### Step 7 — wake on `SUB`

| element | the test |
|---|---|
| a report wakes, once | `AgentRunnerTest.a subagent report wakes the chat agent, once` |
| two reports are one wake | `…two reports arriving together are one wake` |
| a stopped session is not woken | `…a stopped session is not woken by a report` |
| it does not wait for the interval | `…a report wakes a session even when no ping is due` |

### Step 8 — the block

| element | the test |
|---|---|
| the block exists with its two rows | `SettingsScreenTest.the subagents block is its own…` |
| the ceiling is also the stop clock | `…says the ceiling is also the stop clock` |
| off by default, and says so | `…subagents are off by default…` |
| asleep is not retired | `AgentSubagentsTest.a subagent can be asleep without being retired…` |
| sleeping keeps its place in the count | `…sleeping a subagent does not change its place…` |
| a sleeping one is still a retirement candidate | `…a sleeping subagent is not a candidate for retirement…` |
| lowering retires, folder stays | `AgentRunnerTest.lowering the count retires the excess, and the folder stays` |

### Step 9 — stale-read

| element | the test |
|---|---|
| a write after a changed read is noticed | `ScopeTest.a write after a read of a file another agent changed is noticed` |
| no read means no claim | `…a write with no read before it is not called stale` |
| an unchanged file is silent | `…a write after a read of an unchanged file…` |

---

## 3. The bugs the audit and the tests found in my own work

These are the useful part, because each was invisible until something asked.

1. **`inFlight` backwards** (§2, step 1). Caught by an existing test; would have disabled recovery
   from a killed process's `index.lock` — every future save in that project refused, for ever.
2. **A nested hold erased the outer holder.** `commitOutcome` holds and `commitEverything` nests; the
   inner exit's `remove` left the outer hold unnamed, so a later stall would report *nobody* holding
   the repository.
3. **`append`'s project guard was one level too shallow.** It found the project by taking the folder
   above the file's parent — the project for `agent/session.log`, `agent/` for `agent/2/session.log`.
   Every subagent row was refused, **silently**, because a row that cannot be written is deliberately
   not an error. Three tests failed at once from this.
4. **`AgentLog.open` appends `agent/`**, so a subagent's record would have landed at `agent/2/agent/`.
   `openAt` exists for this.
5. **`(the model said nothing)` was a string literal**, so my `isEmpty()` check was a branch that
   could never run. It is `Agent.EMPTY_REPLY` now.
6. **`fitTo` had no production caller.** The count could be lowered and nothing would be retired —
   the operator's rule implemented, tested, and wired to nothing.
7. **`stale-read` could not see a change made inside one second**, because a phone's filesystem may
   stamp modification times to the second. The mark is length **and** time now.
8. **A test built a new `AgentTools` per call**, so the read memory was discarded between the read and
   the edit — a test that would have passed with the feature completely absent.
9. **`edit` refuses two selectors**, and `text` means "write the whole file", so `{"at":…,"text":…}`
   is a call meaning two edits. An insertion is `replace`.
10. **A trailing lambda bound to the wrong parameter** in `SubagentRun.run` — `task` is last, so the
    report sink kept its default and the test asserted nothing.
11. **`@RunWith` fully-qualified without an import** is not a thing Kotlin allows.
12. **`Seen` collided with `Look.Seen`.**
13. **`the system message is the same bytes`** was one byte too strict: the clear-up line is rebuilt
    per request from the context as it is *now*, so it may legitimately differ. What the cache needs
    is that everything before that line is identical, and that is what it now holds.

---

## 4. What is NOT covered, stated plainly

1. **`OpenRouterDoor` / the network.** Unchanged by this work, and still the one path no test drives.
2. **A subagent's own keepalive.** The ceiling **stops** a subagent (`retireOverdue`); nothing pings
   it. The operator said subagents *are* kept warm because they keep context between tasks — so a
   subagent's context goes cold between tasks and the next task pays a cache write. **This is the one
   place the built behaviour is narrower than the decision**, and it should be either implemented or
   the decision revisited. It is not a silent omission: `subagent-stop` fires when the ceiling is
   reached, and nothing fires for a ping that never happens.
3. **The `subagent` tool cannot see a subagent's live state.** `list` shows live/retired from the
   registry; whether a turn is running right now is the runner's in-flight set, which the tool does
   not have. So `list` can say `live` about a subagent whose turn is on the worker.
4. **A report is not bounded on the way in.** By the operator's decision the whole report goes in;
   the mitigation is that the row names its own size. A report bigger than the model's window still
   fails at the endpoint rather than in the app.
5. **`SUB` rows are not drawn as their own thing in the chat.** `AgentChat` has a branch for `NOTE`;
   a `SUB` row falls through to the generic note rendering. It is visible and it is in the record, but
   it does not say "a subagent reported" on screen.
6. **Two agents editing one file is still possible.** The prompt says not to trust memory, and
   `stale-read` records when it happened. Nothing prevents it, which was the operator's call.

---

## 5. The three things I would do next, in order

1. **A subagent's keepalive** (§4.2) — the one place the build is narrower than the decision.
2. **`SUB` in `AgentChat`** (§4.5) — a row that says what it is, on the screen as well as in the log.
3. **`list` showing live turns** (§4.3) — it needs the runner's in-flight set, which means the tool
   needs a way to ask it.

None of the three is a defect in what exists; each is a piece of the decision that has no
implementation yet.
