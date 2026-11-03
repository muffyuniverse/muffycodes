# Subagents — a counterproposal

Status: **counterproposal.** Nothing here is built. The brief is good and most of it is right; this
document says where the brief as written would produce something the app cannot honestly do, and what
to build instead.

---

## 0. The brief, restated as facts so we can disagree with them

1. Subagents exist, N per project, N from a settings field.
2. Their messages are delivered to the project's chat agent **as tool results**.
3. A subagent's own model / provider / effort, copied from the chat agent's, in a **separate block**
   in Settings.
4. A separate **idle timeout** per subagent, after which it is stopped.
5. The chat agent can **write to** subagents.
6. Subagents get a **limited set of tools** and their **own** system/project prompts.
7. Changing N adds/removes each subagent's `agent/` subfolder (session + context logs), and stops
   them; they start only when the chat agent gives one a task.
8. Investigate **safegit** so agents working on one project cannot race.

Every one of those is buildable. Three of them, as written, would build something the rest of this
codebase spent six rounds making impossible. Those are §1, §2 and §6.

---

## 1. "Messages directed to the chat agent as tool results" — the one that cannot be a *tool result*

This is the load-bearing decision in the brief and it is the one I would change.

**What a tool result is, in this app.** `AgentTools.run` is synchronous: the model asks, the app
does the work, the app answers, the turn continues. `AgentTools.forConversation` then bounds the
answer so it fits the conversation. Every tool in the list is a *call the model made and is waiting
on*, and the whole turn's accounting (`ContextBudget`, `MAX_REQUESTS`, the loop breaker, the trace's
`CALL`/`GOT` pair) is built on that shape.

A subagent is not that. A subagent is **another agent with its own session, its own clock, its own
provider timeout, and its own log** — the things §7 explicitly gives it. So its message arrives:

- *minutes* after the call that spawned it, not in the same turn;
- possibly while the chat agent is idle, or stopped, or while the app is not running at all;
- from a process that may have been killed and restarted in between;
- and it may arrive **twice** (a retry) or **never** (a crash), and the chat agent must be able to
  tell the difference.

If that lands as a *tool result*, then the tool call that spawned it must stay open for minutes to
hours. That breaks, in order:

| what breaks | why |
|---|---|
| `MAX_REQUESTS` / the loop breaker | the turn is "in flight" the whole time; the backstop counts a request that is not running |
| `ContextBudget` | the context is measured at the *start* of a turn, so a result arriving an hour later is measured against a conversation that has since been compacted |
| the trace's `CALL`/`GOT` pairing | `GOT` is written when the tool returns; a result that outlives the turn has no turn to return into |
| the provider timeout | `settings.responseMs` is an *idle* timeout on the socket; a held-open call has no socket |
| the app being closed | there is nothing to return to — the turn is gone, and Android does not owe the app a process |

And the sentence a model reads would be a lie: `0\n<subagent's answer>` says *this call is done*,
when what actually happened is *a different agent finished something you asked for earlier*.

**What to build instead.** Deliver subagent messages as **`TOLD` rows in the chat agent's own log**,
which is the mechanism the app already has for exactly this:

> `AgentLog` already has a `TOLD` kind — a row that enters the working context and is *not* something
> the model asked for. It is how the app says "the person spoke" and "you are over the context
> limit". A subagent's report is the third member of that family: **something happened that you did
> not ask about in this turn and need to know before your next one.**

Concretely:

- `subagent` tool: *spawn* one with a task, and *write* to one. Returns at once — `0\nstarted #2 on
  "count the tests in app/"` — because the model asked for the work to start, not for its result.
- The subagent runs its own turns, on its own worker, with its own session and its own clock.
- When it has something to say, it writes a `TOLD` row into the **chat agent's** log, addressed
  (`from #2`). The chat agent reads it at the start of its next turn, exactly as it reads anything
  else in its context.
- If the chat agent is idle, the report is a reason to wake it — the runner already has the
  machinery (`nextPollAt`, `AgentRunner.tick`), and a `TOLD` row is the only new trigger.

**This is strictly less code than the tool-result version, and it is the only one that survives the
app being closed.** The brief's own §7 (session and context logs per subagent) is already the design
that makes this work; delivering through the log is what those files are *for*.

**One thing the brief is right about and I want to keep:** the chat agent should see subagent output
**as a result-shaped thing**, not as a person's message. That is a `TOLD` row whose body is written
like a tool result (`0\n…` / `1\n…`), which gets the model's attention without pretending a call is
open. I'd write it as its own row kind (`SUB`) rather than overloading `TOLD`, so `forget` and the
compaction rules can treat it precisely — but the *delivery path* is the log, not the return value.

---

## 2. "N subagents per project, from a settings field" — the number is a *count*, not an *identity*

The brief says: change N, and the subagents' `agent/` subfolders are added or removed, and the agents
are stopped.

**What is wrong with the obvious reading.** If subagent *k* is "the k-th", then lowering N from 3 to
2 and raising it back to 3 produces **a new #3 with no memory** — and worse, a report from the old #3
that arrives afterwards lands in the new #3's folder, attributed to work it never did. Two agents
sharing one name is the same class of bug as two writers sharing one `index.lock`.

**What to build.** The setting is **how many may exist**, not which ones do:

- A subagent has an **id that is never reused** (a counter in `.muffy/subagents.json`, or the log's
  own `nextId` — the app already mints ids this way and never reuses them).
- The count is a **ceiling on live subagents**. Starting one past the ceiling is refused with a
  sentence, not silently dropped: `1\nthree subagents are already running; stop one first`.
- **Lowering N does not delete a folder.** It stops the *youngest* finished subagents until the
  number fits, and leaves every folder alone. A folder is a record; a record is deleted when the
  person says so, which is the rule the app already applies to every project file.
- **Raising N creates nothing.** A subagent's folder is made when it is first started, by the same
  "a reader must not create what it reads" rule this codebase just spent a day fixing
  (`AgentLog.open` / `AgentLog.append`). A settings change must not be a folder-creating event: that
  is precisely the bug that made the tick flake for two days.

If the operator wants "exactly N, and the folders go with them", that is a different feature —
**and it needs to say what happens to a subagent mid-task when the number is lowered.** My
recommendation: lowering stops the *idle* ones only, and refuses to lower below the number of
subagents currently working, saying so. That is the same shape as `Stop all agents` refusing while a
turn is running, which the app already does.

---

## 3. "Limited set of tools" — the boundary is not the list, it is the *project*

This is the part of the brief I would tighten hardest, because a limited tool list reads like a
sandbox and is not one.

`tree` accepts `../`, which lists **another project** — deliberately, and the tool's own description
says so. `search` searches **every project in the account**. `read` follows both. So a subagent given
`{read, tree, edit}` still reads the whole account and writes into any project it can name.

If the intent is *"a subagent works on the project it was made for"*, the limit has to be a **scope
argument threaded through the tools**, not a shorter list:

- `AgentTools` takes a `scope: Scope` (`Account` for the chat agent, `Project(name)` for a subagent).
- `tree`'s `../` is refused in `Project` scope, in words (`1\nthat is another project; you are
  scoped to groceries`).
- `search`'s `in` defaults to the scoped project; searching elsewhere is refused the same way.
- `read`/`edit`/`create`/`move`/`restore` refuse a path that resolves outside the scope.
- The refusal is a **rule in `AgentTools`**, not a filter on `specs()`, so it holds for every tool
  including ones added later — and it is testable without a model.

**Which tools I would actually give a subagent, and why:**

| tool | in? | reason |
|---|---|---|
| `read`, `tree`, `search` | yes, scoped | its whole job is looking at the project |
| `edit`, `create`, `move` | yes, scoped | it must be able to produce work |
| `reason` | yes | it is a thinking agent |
| `forget` | yes | it has its own context to keep |
| `history`, `restore` | **no** | version archaeology is the chat agent's job; a subagent restoring a version is a change nobody asked for |
| `background`, `query`, `sql` | **no** | results go to files the chat agent then has to find; two agents writing CSVs is two answers to one question |
| `browse`, `fetch`, `mcp` | **no** | network surface × N agents is N times the key, the cost and the blast radius; a subagent that needs the web asks the chat agent |
| `shot`, `look`, `ci`, `runs`, `artifact`, `downloads`, `archive` | **no** | app-and-world operations, all of them the chat agent's to decide |
| `subagent` | **no** | no recursion. One level, and the reason is cost: N agents each spawning N is N² bills for work nobody asked for |

That is 9 of 22 tools, and every one of them scoped.

---

## 4. Safegit — the brief is right to worry, and the race is already there

**The finding first, because it changes the priority of everything else in this document.**

`GitService.retryingLock`'s own comment says:

> *"Every write in this app is made on one worker thread, in order, so when a command fails on a lock
> there is no command of ours holding one: what is on disk is the corpse of a process Android killed
> mid-write."*

**That is already false today, before any subagent exists.** There are two workers:

- `AppViewModel.worker` — `Executors.newSingleThreadExecutor()`, runs `saveEditor`, project creation;
- `AgentRunner.worker` — a second `Executors.newSingleThreadExecutor()`, runs every tool call,
  including `AgentTools.commit` → `ProjectGit.commitOutcome` → `GitService.commitAll`.

So an editor save (view-model worker) and an agent's `edit` (runner worker) can commit to the same
repository at the same time. `retryingLock` defaults `inFlight` to `false`, and **`inFlight = true` is
passed nowhere in this codebase** — verified, not assumed. So the first of the two to hit a lock
clears the other's `index.lock`: `clearStaleLock` deletes it and retries. That is not a race that
corrupts the repository; it is a race that *deletes a live lock*, which is worse, because it looks
like success.

Subagents make this N+1 workers instead of 2. **It must be fixed before the first subagent runs, not
after.**

### What to build: three layers, cheapest first

**Layer 1 — a per-repository mutex in-process (the fix for what exists today).**

```kotlin
object RepoLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    fun forRepo(dir: File): Any = locks.computeIfAbsent(dir.absolutePath) { Any() }
}
```

Every `ProjectGit` write (`commitOutcome`, `sync`, `init`, `restore`) wraps its JGit work in
`synchronized(RepoLocks.forRepo(repoDir(project)))`. This is the same shape as `AgentSessions.lockFor`
— a per-path lock shared by every instance, which is exactly the bug `AgentSessions` documents having
already solved once. **It also lets `retryingLock`'s comment become true**, because `inFlight` can
then be passed honestly.

Cost: one `synchronized` per commit, no new threads. A commit is milliseconds.

**Layer 2 — the lock is taken *around the unit of work*, not around each JGit call.**

`commitAll` calls `stageEverything`, then `status`, then `commit`, each wrapped separately in
`retryingLock`. With a mutex above, that is fine. Without one, the window between them is where
another writer slips in and commits *your* staged tree under *its* message. The mutex must be at
`commitOutcome`'s level, and it must cover the whole `open → stage → status → commit → prune`
sequence, not the individual JGit calls.

**Layer 3 — what "safegit" should NOT be.**

I would **not** build: a git server, a worktree per agent, a branch per agent, or an operation queue
with priorities. Each of those is a real solution to a real problem in a different app:

- **worktree/branch per agent** — the app's own rule is that a project's repository *is* the project's
  folder, with one history, and "where did this file's versions come from" has one answer. N branches
  makes that question N answers, and the history screen would have to grow a branch picker.
- **a queue with priorities** — two agents waiting on one repository is not a scheduling problem, it
  is a *contention* problem, and the mutex solves it in the time it takes to write a commit. A queue
  would add a way for a commit to be refused that nobody asked for.
- **an external git daemon** — there is no process model in this app, by design (`Timeouts`: *"Nothing
  in this app is a subprocess"*).

**The one thing from the safegit family I would take: an owner on the lock.** If a mutex is held for
more than a few seconds, the app should write one trace line naming *who* held it (`agent #2`,
`save`, `sync`) — because the failure mode of a mutex is a stall, and a stall with no name is a bug
report nobody can act on. This is `Deadline`'s lesson applied to a lock: the thing that can hang must
say what it was doing.

### And one more, which the brief does not mention

**Two agents editing one file is not a git race and a mutex will not fix it.** Agent A reads
`notes.txt`, agent B reads `notes.txt`, both write, the second commit silently wins. Git cannot help
here: there is no conflict, because there was never a merge. The app's answer should be the one it
already uses for people — **`read` before you change one, always** — plus, for subagents, a **write
claim**: a subagent that has opened a file for editing records it in `.muffy/subagent-claims.json`, and
another subagent's `edit` on that path is refused with the name of the holder. That is cheap, it is
honest, and it is the only thing that prevents the loss.

---

## 5. The settings block — mostly right, two corrections

The brief wants a **separate block** that **copies the chat agent's settings**. Both are right, and
the copying needs one rule to not become a trap:

- The subagent block holds **model, provider, effort** — three rows, the same three as `ModelSection`.
- **Copying happens once, when the block is first used** (or when the person presses *Copy from the
  chat agent*), **not continuously.** A live mirror means a person who changes the chat model to test
  something silently changes N subagents' model too, and the only way to find out is a bill. A copy is
  a decision; a mirror is an accident.
- The **idle timeout** is a fourth row in that block, in the same unit and shape as the existing
  `foreground` / `background` timeouts (`0` = no limit), because it is the same kind of number and a
  person who has learned one row has learned all of them.
- **`keepalive` must not apply to subagents.** `warmMinutes` exists because a chat agent waiting for a
  *person* should keep its cache hot. A subagent waiting for a *task* has nothing to keep warm, and N
  subagents each pinging every four minutes is N times the cost for zero benefit. A subagent that
  finishes goes idle and its clock stops; that is the idle timeout's whole purpose.

That last point is the one I would put in bold in the settings screen's own words: a subagent is
**not** kept warm.

---

## 6. Prompts — "their own" needs to say *which file*

`AgentPrompt` has three layers today: `system` (the account's, in the system project, editable),
`project` (the project's `project_prompt.txt`), and the built-in fallbacks. "Subagents get their own
system/project prompts" is ambiguous in a way that will be resolved wrongly by whoever builds it
first, so:

- **System layer:** one per account, shared, *unless* the operator wants subagents to have a different
  disposition (terser, more literal, no chat pleasantries). I would give them a **fourth file**,
  `system/subagent_prompt.txt`, rather than a per-subagent one: N copies of a system prompt is N
  places to edit and N ways to drift, and the difference between subagents is their *task*, not their
  *character*.
- **Project layer:** shared with the chat agent — it is the same project, and a subagent that did not
  know the project's rules would be a second set of rules for one folder.
- **Task:** the fifth thing, and it belongs in the **spawn call**, not in a file. `subagent {task:
  "..."}` is the subagent's opening message, and it is what makes two subagents different.

So: **two shared layers plus a task**, not N×2 files. If the operator wants a per-subagent brief, that
is `agent/<id>/prompt.txt`, and I would say no to it in v1 — it is the same argument as the system
prompt, one level down.

---

## 7. What I would build, in order

| # | step | why first |
|---|---|---|
| 1 | `RepoLocks` mutex + honest `inFlight` + a trace line naming the holder | the race exists today with 2 workers; subagents make it N+1 |
| 2 | `Scope` on `AgentTools` (`Account` / `Project`) | the security boundary, and it is testable with no model and no clock |
| 3 | `SUB` row kind in `AgentLog`, written by a subagent into the chat agent's log | the delivery path; everything else is plumbing |
| 4 | Subagent identity: never-reused ids, a ceiling, folders made on first use | fixes the "lower N then raise it" bug before it exists |
| 5 | `AgentRunner` runs a subagent's own turns; idle timeout stops it | reuses the clock machinery that already exists |
| 6 | Settings block: three copied rows + idle timeout; **no keepalive** | last, because it is the only part that is only a screen |

Steps 1–2 are worth doing **even if subagents are never built**, and I would argue for landing them
first for that reason alone.

---

## 8. The questions I cannot answer from the code

1. **Should a subagent's report wake an idle chat agent?** I have assumed yes (§1). The alternative —
   the report waits in the log until the person next speaks — is cheaper and quieter, and it makes
   "the agent did something overnight" impossible. The operator's call.
2. **What happens to a subagent mid-task when N is lowered?** I propose: refuse to lower below the
   number working, saying so. The alternative is killing work in progress, which the app has never
   done to a person.
3. **Does a subagent count against the one-model rule?** The brief gives it its own model, provider
   and effort, which is N more ways to spend money. If the intent is "same model, cheaper effort",
   that is one row (`effort`) and not three.
4. **Should subagents be visible in the project list?** A subagent with its own session and its own
   `agent/` folder is a thing that exists, and the app's rule so far is that everything that exists is
   reachable. If it is not in the list, it needs to be in the chat agent's page stream instead.

---

## 9. Summary — the three changes I am arguing for

1. **Deliver through the log (`SUB` row), not as a tool result.** A tool result is a call the model is
   waiting on; a subagent is another agent with its own clock. The log is the mechanism that already
   exists for "something you did not ask for happened".
2. **The setting is a ceiling, not an identity.** Never-reused ids, folders made on first use, lowering
   N stops idle subagents and deletes nothing.
3. **The tool limit is a scope, not a shorter list.** `tree ../` and `search` already reach the whole
   account; a subagent's boundary has to be enforced in `AgentTools`, not in `specs()`.

And the finding that outranks all of them: **the git race is already real** — two workers, and
`clearStaleLock` will delete a live lock. That is a bug today, not a subagent problem, and it should be
fixed whether or not this feature is built.
