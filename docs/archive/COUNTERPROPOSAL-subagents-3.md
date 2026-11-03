# Subagents — round 3: settled

Status: **settled.** Rounds 1 and 2 (`COUNTERPROPOSAL-subagents.md` @ `452e5b3`,
`COUNTERPROPOSAL-subagents-2.md` @ `e846bde`) argued the shape; every question is now answered. This
round records the three last answers, the consequence each one has that is **not** obvious from the
answer itself, and the build order. It is short on purpose — the reasoning is in rounds 1 and 2 and
does not need repeating.

---

## 1. The three answers

| # | question | answer |
|---|---|---|
| 1 | does lowering N delete the folder? | **folder stays, id inactive** |
| 2 | is the keepalive ceiling the stop clock? | **one number** |
| 3 | does `SUB` carry the whole report? | **whole report** |

Each one is right, and each one has a cost that arrives a few weeks later if it is not written down
now. Those are §2, §3 and §4.

---

## 2. "Folder stays, id inactive" — what "inactive" has to mean, exactly

The record survives and the id does not come back. That is the decision. What it does **not** settle
is the difference between *inactive* and the two things it is not:

| state | can be written to? | can be started again? | appears where? |
|---|---|---|---|
| **live** | yes | n/a | `SUB` rows, `subagent {list}` |
| **inactive** (removed by the count) | **no** | **no** — a new one gets a new id | named in the refusal, and its log is on disk |
| **stopped** (idle timeout reached) | **no** | **yes** — same id, same context | `SUB` rows, `subagent {list}` |

**Stopped and inactive are different, and the difference is the whole point of keeping the folder.**
A stopped subagent is asleep and can be woken with its context intact — that is what "keeps context
between tasks" bought us in round 2 §5.2. An inactive one has been retired by the count, and waking it
would put the count back over the limit. So:

- `subagent {id: 2, task: "..."}` on an **inactive** id refuses: `1\nno subagent #2: it was retired
  when the limit was lowered. Its record is at agent/2/. Start a new one with subagent {task}` —
  the path is in the sentence because that is the only way a person can find it.
- The same call on a **stopped** id wakes it. Same id, same log, its context still there.
- `subagent {list}` shows live and stopped, with the retired ones counted but not listed: `3 live,
  1 retired`.

**One thing this forces, which is easy to miss:** the id counter and the "inactive" set live in
`.muffy/subagents.json`, and that file is written by the settings change *and* by the runner. It is
the same lost-update hazard `AgentSessions` documents — two writers, each reading the whole file and
writing it back. It must use the same `lockFor` shape, and the read-modify-write must be **inside**
the lock, not around it. That is a solved problem in this codebase and the solution should be copied,
not reinvented.

**And a retired subagent's folder is never deleted by the app.** It can be deleted by the person, from
the project folder, the way any file can. That is consistent: a record is deleted when somebody says
so.

---

## 3. "One number" — so the settings row has one meaning and two jobs

Confirmed: the keepalive ceiling **is** the idle timeout. One row, one number, and reaching it stops
the subagent.

**The consequence that must be built, not discovered:** the ceiling now does two different things
depending on *what the agent is doing when it is reached*.

| when the number is reached | what happens | why |
|---|---|---|
| the subagent is **idle**, waiting for a task | **stop it** — the session ends, the context stays on disk | it has been waiting as long as the person allowed; the next task wakes it |
| the subagent is **working** (a turn is running) | **let the turn finish**, then stop | a turn killed mid-write is the half-file the app spends effort preventing |

That is not a compromise, it is the same rule the app already applies to `Stop all agents` refusing
while a turn runs. **A stop is never a kill.** The trace should say which happened
(`subagent-stop` with `after turn` / `while idle`) so a report of "it stopped in the middle" can be
checked against what actually happened.

**The cost, and it belongs in the settings row's own words rather than a document:** N subagents ×
one ping per four minutes × the ceiling. At the defaults (N=3, 60 min) that is up to 45 pings an hour
if all three sit idle — each reading a full prompt at cache price. The chat agent's ceiling was
justified at *"about twelve pings, which is fifty minutes"*. Three of those is three times the
arithmetic for a fraction of the value, and the row should say so where the number is typed.

---

## 4. "Whole report" — the decision is right, and it moves one risk somewhere else

The report goes into the `SUB` row entire, not a summary with a pointer.

**Why this is the better choice than my proposal:** a pointer makes the chat agent spend a `read` to
learn what the subagent already knew, and a `read` is a request. The whole report costs tokens once;
a pointer costs a request *and* the tokens. It also means the report is in the record verbatim, which
is the app's rule for everything the agent decided.

**The risk it moves, stated plainly:** a tool result passes through `AgentTools.forConversation`,
which is *"the one place the result limit applies"* — over the limit, the body is written to a file
and the answer is the path. A `SUB` row does not go through that, so **a subagent's report can be
larger than any single row the app has ever written.** Two things follow:

1. **A report bigger than the model's input window cannot be sent at all.** The request fails at the
   endpoint, not in the app, and the failure arrives as a provider error rather than as a refusal the
   app wrote. That is the worst shape a failure can take in this app — the rule everywhere is *"a
   failure must say what happened"*.
2. **The app already has the answer, and it is not `forConversation`.** `Agent.request()` builds the
   clear-up line from `opening.biggest` — the largest rows, named by id — and tells the model to
   `forget` them. So an oversized `SUB` row is *visible* (it will be the biggest row), *nameable* (by
   id), and *removable* (by `forget`). The mechanism exists; it just has to be reachable.

**What to build, then:** not a size cap on the report, but a **check when the subagent writes it**. If
the report is larger than the chat agent's context trigger, the `SUB` row says so in its first line —
`0\n[this report is about 40k tokens; your context is 60k]` — so the chat agent knows to `forget`
something before it grows. That is one sentence, and it turns "the endpoint refused a request" into
"the app told the model what it was carrying."

**The alternative I considered and reject:** truncating the report and writing the rest to a file. It
is the `forConversation` shape, and it would work — but it makes the chat agent's knowledge depend on
a `read` it may never do, and the operator's decision was *whole report*. The first line is the
honest version of the same warning.

---

## 5. The build order, final

Unchanged from round 2 except that nothing is left open, so this is the list to work from.

| # | step | notes |
|---|---|---|
| **1** | **`RepoLocks` mutex + honest `inFlight` + a trace line naming the holder** | the race is real today (§6) |
| **2** | **Remove the `reason` tool**, keep its reader | own commit, own test run |
| **3** | `Scope` on `AgentTools`, enforced in `run` not `specs` | the security boundary |
| **4** | The missing-commands paragraph, **generated** from `specs(Account) - specs(Project)` | needs §3 |
| **5** | `SUB` row kind + `subagent` tool (spawn / write / list) + the inactive and stopped refusals | §2's three states |
| **6** | Subagent turns on the runner: own session, own clock, **stop at the ceiling — after the turn if one is running** | §3 |
| **7** | Wake on `SUB`, batched, sentence-only | three lines on `woke()` |
| **8** | Settings block: model/provider/effort + the one ceiling, with the ping arithmetic in the row | §3's cost |
| **9** | The stale-read trace line + the "do not trust your memory" prompt paragraph | the only mitigation for lost writes |

Steps 1 and 2 are worth landing **whether or not subagents are ever built**. Step 1 is a live bug;
step 2 is a change to every chat agent that has nothing to do with this feature.

---

## 6. The finding that still outranks the feature

`GitService.retryingLock` says *"Every write in this app is made on one worker thread, in order."*
There are two — `AppViewModel.worker` and `AgentRunner.worker` — and the runner's runs every tool
call, including `AgentTools.commit` → `ProjectGit.commitOutcome` → `GitService.commitAll`.
`inFlight = true` is passed **nowhere in this codebase** (verified). So the first of two writers to hit
a lock calls `clearStaleLock`, which **deletes the other's live `index.lock`**, and retries.

Not corruption. Worse in one way: it looks like success. Subagents make it N+1 workers.

The fix is one `synchronized` around the whole `open → stage → status → commit → prune` sequence —
not around each JGit call, which leaves the window between them open — keyed by `repoDir` the way
`AgentSessions.lockFor` is keyed by file. A commit is milliseconds.

**And the lock must name its holder** when held too long. A mutex's failure mode is a stall, and a
stall with no name is a bug report nobody can answer. `Deadline`'s lesson: the thing that can hang
must say what it was doing.
