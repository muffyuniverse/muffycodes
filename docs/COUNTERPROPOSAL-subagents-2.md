# Subagents — round 2

Status: **settled enough to build.** Round 1 (`COUNTERPROPOSAL-subagents.md`, commit `452e5b3`) argued
three changes to the brief; the operator answered each one inline. This round records what was decided,
what the decisions cost, and the order to build them in.

Where an answer **reversed** my position, the reversal is stated as a reversal. Where an answer
**confirmed** the brief against my objection, the objection is dropped and the consequence is written
down — because a consequence that arrives later as a surprise is the thing this document is for.

---

## 0. What was decided

| # | question | decision |
|---|---|---|
| 1 | how a subagent's message reaches the chat agent | **a `SUB` row**, shaped like `TOLD` — approved |
| 2 | lowering the count | **remove the first idle subagent**; a write to a removed id **throws** |
| 3 | which tools a subagent gets | `ci`, `background`, `look` **stay**; **`reason` is removed from the app entirely** |
| 4 | two agents editing one file | **instruct agents not to trust old memory**; grep over line numbers |
| 5 | the settings block | **set up separately**, sharing only the model/provider list; **idle timeout = the keepalive timeout**; subagents **are** kept warm |
| 6 | prompts | shared layers, **and the system prompt must say which commands a subagent does not have** |
| 7 | does a report wake the chat agent | **yes — the same way a background completion does** |
| 8 | model/provider per subagent | **all subagents share one setting** |
| 9 | are subagents in the project list | **no — they get logs, not chats** |

Three of those reverse what I proposed, and §5, §7 and §9 are where the cost lands.

---

## 1. Delivery: a `SUB` row — confirmed

Settled as written in round 1 §1. The delivery path is the chat agent's **log**, not a tool result;
the row is its own kind (`SUB`), shaped like `TOLD` so the model reads it as *something that happened
and needs attention* rather than as a person speaking.

What that means concretely, and it is now the whole mechanism:

- `subagent {id, task}` returns at once — `0\nstarted #2 on "count the tests"` — because the model
  asked for the work to start, not for its result.
- The subagent runs its own turns, on its own worker, with its own session and its own clock.
- Its report is a `SUB` row **in the chat agent's log**, named `#2` so a conversation with three
  subagents is readable.
- The chat agent reads it at the start of its next turn, exactly as it reads anything else in its
  context, and `forget` can name it by id because it is a row like any other.

**`SUB` rows are born `in`, not `app`.** They are the conversation's content — the thing the model
must act on — and the operator's rule for `app` is *"app errors … should go into session log only"*,
which this is not. A subagent's report is closer to a background completion than to an error, and
background completions arrive as a real turn (§7).

---

## 2. The count: a ceiling, and a removal that is honest about being one

The operator's rule, which is better than my "refuse to lower" proposal because it never leaves a
person stuck with a number they cannot set:

> *when amount of running subagents is above the limit then the first idle subagent is removed, and
> if chat agent tries to write to an agent (by id) who has been removed then he gets exception.*

So lowering N **does** remove, and what it removes is the **first idle** subagent. A subagent that is
working is never removed by a settings change; the count settles when one finishes.

**Two consequences that must be built, not discovered:**

1. **Ids are never reused.** If a removed `#2` could come back as a new `#2`, then a report the chat
   agent already read, or a task it already wrote, would silently attach to a different agent's work.
   The counter lives in `.muffy/subagents.json` and only ever goes up.
2. **A write to a removed id is an exception the model can act on, not a crash.** The tool answers
   `1\nno subagent #2: it was removed when the limit was lowered. Start one with subagent {task}` —
   which is the app's existing shape for a refusal (`refusal(...)`, status `1`). "Exception" here
   means *the call fails and says why*; a Kotlin exception reaching the turn would end it with a
   sentence nobody wrote.

**Removal deletes the folder.** This is the one place I would still push back, and it is a small
thing: the operator's rule says *removed*, and I read that as the subagent, not its record. Deleting
`agent/<id>/` destroys the only account of what it did — and the chat agent's `SUB` rows quote it, so
a person reading the chat finds references to a log that is gone. **Recommendation: stop it, mark it
removed, leave the folder.** If the operator wants the folder gone too, it is one line and a
different sentence in the settings row; I am flagging it rather than deciding it.

---

## 3. Tools: three restored, and `reason` removed from the whole app

### 3.1 `ci`, `background`, `look` stay

Accepted, and the reasons are good ones I had wrong:

- **`ci`** — a subagent checking whether a push is green is the archetypal subagent task, and it is
  read-only against GitHub.
- **`background`** — a subagent that cannot background a long command has to block, and blocking is
  the thing the background tool exists to avoid.
- **`look`** — looking at a picture is reading, and a subagent that can read a project but not a
  screenshot of it is scoped by accident rather than by rule.

**What this costs, stated so it is not a surprise:** `background` writes a result file into the
project, and a subagent's result file is now a file the chat agent will find by `tree` and not know
why it is there. The file is named by the call, so the name is the explanation — but the chat agent's
prompt should say *a result you did not ask for may be a subagent's*, or the chat agent will read it
as its own unfinished work. That is one sentence in `AgentPrompt`'s tools paragraph.

### 3.2 `reason` is to be removed — and this is the largest change in the round

The operator's instruction: *"I think that reason is useless command and should be removed because we
now return reasoning to provider and that is enough."*

The premise is right, and the code already half-agrees with it. There are **two** ways a thought is
recorded today, and only one of them is the provider's:

| written by | row | context | read back as |
|---|---|---|---|
| `AgentLog.thinking(of, text)` | `NOTE name=thinking`, `ctx=APP` | never | `reasoning_content` on the turn it belongs to |
| `reason` tool → `log.note("reason", text)` | `NOTE name=reason`, `ctx=IN` | yes | **prose**, appended to the assistant's own paragraph |

The first is the provider's reasoning coming back the way a thinking-mode endpoint demands
(`400 The reasoning_content in the thinking mode must be passed back`). The second is the model
writing a paragraph to itself that then **counts against its own context budget** — so a model that
thinks a lot in the open makes its own compaction arrive sooner. The operator is right that the first
makes the second redundant.

**What removal actually touches** (12 references, three of them real behaviour):

1. `AgentTools.specs` — the spec, and the `reason` entry in the argument allow-list.
2. `AgentTools.run` — the `"reason" -> reason(args)` branch and the private `reason()` function.
3. `Agent.messages` — the `"agent", "reason" ->` branch, which appends the body to the reply's
   paragraph. **This one matters**: leave it and a `reason` row in an old log still merges into the
   assistant turn, which is right for old logs and harmless for new ones. I would **keep** this
   branch for the sake of logs already on people's phones, and remove only the *tool*. A tool that
   can no longer be called needs no path for new rows, but a reader that stops understanding old
   rows is a log that half-parses.
4. Tests: `AgentToolsTest`, `AgentTest`, `AgentSessionsTest` each assert the tool or its row.
5. `AgentChat.kt` — the page/rendering path that shows a `reason` row.

**The judgement call, and my recommendation:** remove the **tool** and its spec; **keep** the reader
branch and the row kind, because every log written before today has `reason` rows in it and a log the
app can no longer read is worse than a command nobody can call. Then the prompt's paragraph about
thinking says the one true thing — *your reasoning is returned to the provider; you do not need to
write it down* — and the model stops spending context on it.

**This is not a subagent change and should not ride along with one.** It is a behaviour change for
every existing chat agent, and it wants its own commit and its own test run, or a regression in the
chat will be blamed on the subagent work.

---

## 4. Two agents editing one file — the instruction, and the limit of it

The operator's answer: *"we should instruct agents not to rely on old memory because files can be
changed in-between by other agents. And that is also why grep is important — it is more stable than
line numbers."*

Accepted, and it is the right shape for this app: **a rule in the prompt, not a lock in the
filesystem.** A claim file would be a second source of truth about who is editing what, and the app's
rule everywhere else is that the project's state is the project.

**What goes in the prompt** (one paragraph, in the shared layer so both kinds of agent get it):

> Files may change between your reading of them and your writing. Do not rely on what you remember of
> a file, and do not rely on a line number you were given earlier — another agent may have written to
> it since. **Read before you change one**, and prefer `grep` to a line range: a pattern finds the
> text wherever it moved, and a line number finds whatever is there now.

**The limit, stated plainly because the prompt cannot fix it:** an agent that reads, thinks, and
writes has a window, and an instruction narrows the window without closing it. Two agents writing the
same file in that window lose one write, and the commit history will show two commits with the second
winning. The prompt makes this rare; nothing in this design makes it impossible. The thing that would
make it impossible is a claim the writer must hold, which the operator has declined — and that is a
reasonable trade, but it should be *known* rather than assumed solved.

**The trace should record it when it happens**, because a lost write with no record is the bug report
nobody can answer: when a subagent's `edit` writes a file whose mtime is newer than the last `read` it
did of that path, one `Trace.note("stale-read", …)` line. Cheap, and it turns "my change vanished"
into a list.

---

## 5. Settings: set up separately, kept warm, and the two clocks

This section reverses my round 1 §5 almost entirely. Recording the reversal because the reasoning
matters more than the conclusion.

### 5.1 Separately, sharing only the list

> *"we dont copy subagents from chat agents — we select and set up them separately (but they do share
> model and provider list)"*

So: a **Subagents** block in Settings with its own model, provider and effort rows, defaulting to
nothing rather than mirroring the chat agent. What is shared is the **catalogue** — the list of models
and providers to choose from — not the choice. That is a cleaner split than mine and it removes the
"live mirror changes N agents' bill" hazard I was worried about, because there is no mirror at all.

**All subagents share one setting** (answer to question 8). So the block is *one* set of rows, not a
per-agent editor. N agents, one configuration — which also means a subagent's model cannot drift from
its siblings', and the settings screen has one place to look.

### 5.2 The idle timeout **is** the keepalive timeout

> *"idle timeout is exactly keepalive timeout (as we have for chat agent already), just for subagents.
> And subagents do need keepalive because they keep context between tasks."*

This reverses round 1's "no keepalive for subagents", and the operator's reason is the one I had
missed: **a subagent keeps its context between tasks.** That is the entire point of giving it a log
and a session. A subagent that goes cold between tasks pays the full prompt again on every task, which
is the cost the keepalive exists to avoid — and it is *more* justified here than for a chat agent,
because a subagent's context is a body of work rather than a conversation.

So the row is `warmMinutes`' twin: **the same unit, the same shape, the same meaning, a different
owner.** `subagentWarmMinutes`, `0` = no ceiling. Two rows that look identical and are not, which is
worth one sentence under the block: *a chat agent is kept warm while a person thinks; a subagent is
kept warm between tasks.*

**What this costs, and it should be a number the person sees:** N subagents × one ping every four
minutes × the warm ceiling. At the defaults (N=3, 60 min) that is up to 45 pings an hour if all three
are idle — each one reading a full prompt at cache price. The chat agent's own ceiling was justified
at *"about twelve pings, which is fifty minutes"*; N of those is N times that. I would put the
arithmetic in the settings row's own words rather than a document, because it is the row's whole
cost model.

### 5.3 Two clocks, and which one stops a subagent

There are now two timeouts and they do different things, so the design has to say which wins:

| clock | what it measures | what happens |
|---|---|---|
| **keepalive ceiling** (`warmMinutes`) | how long it may be kept warm | stops pinging; the session stays alive, cold |
| **idle timeout** (the brief's field) | how long it has had nothing to do | **stops the subagent** |

If the operator means these to be *one* number — and "idle timeout is exactly keepalive timeout"
reads that way — then a subagent whose keepalive ceiling is reached is **stopped**, not merely left
cold. That is a defensible rule and it is simpler: one row, one number, and reaching it ends the
subagent. I would build that, because two clocks with one name is how a person sets a number and gets
a different behaviour.

---

## 6. Prompts: shared layers, and the missing-commands paragraph

Confirmed: shared system and project layers plus the task in the spawn call. The addition is the
operator's, and it is the one that makes a limited tool set honest:

> **The system prompt must say which commands a subagent does not have.**

This is right and it is not decoration. A model that does not know `browse` is unavailable will try
it, get a refusal, and spend a turn discovering what a sentence could have told it. So the system
prompt's tools paragraph becomes two paragraphs for a subagent:

- the tools it **has**, described as they are today;
- the ones it **does not**, in one sentence with the reason — *"You have no `browse`, `fetch` or
  `mcp`: the web is the chat agent's to reach. Ask it in your report."*

**Built from the list, not written by hand.** `AgentTools.specs(scope)` already knows which tools
exist; the sentence should be generated from `specs(Account) - specs(Project)` so a tool added later
cannot be missing from the sentence. A hand-written list is a list that drifts, and this is exactly
the kind of drift nobody notices until a model asks for something the prompt promised.

**The `SUB` row also needs to be described** in the chat agent's system prompt, or the chat agent will
read a subagent's report as a person's message. One sentence: *a `SUB` row is a subagent's report; it
is not the person, and you may reply to it by writing to that subagent by id.*

---

## 7. A report wakes the chat agent — the machinery already exists

> *"should wake up, same as with background task completions."*

Good, and it is the cheapest decision in the round because `AgentRunner` already has exactly this:

```text
tick → a background command finished → wake(session, now, woke(finished)) → dispatch(...)
```

`dispatch` claims the session, builds the agent, and runs one real turn on the worker; `woke()` is the
sentence the turn carries. A subagent's report is the same shape:

```text
tick → a SUB row arrived → wake(session, now, told(report)) → dispatch(...)
```

**What differs from a background completion, and it must be handled:**

1. **A `SUB` row is already in the log.** A background completion is a *file* that the wake sentence
   points at; a subagent's report is a *row* the agent will read anyway. So the wake sentence must not
   repeat the body — it says *"subagent #2 has reported; its report is in your context"* and lets the
   log do the work. Otherwise the report is read twice and paid for twice.
2. **Two reports arriving together are one wake.** `woke()` already takes a `List<Background.Done>`
   for exactly this reason; the same batching applies, or three subagents finishing in a minute cost
   three turns.
3. **A wake for a session that is already running is dropped**, which `dispatch` already does by
   claiming the session. A subagent's report that lands mid-turn is read next turn, which is correct
   and needs no extra code.

---

## 8. Subagents are not in the project list — what that means for reachability

> *"Subagents get their logs but not chats."*

Understood, and this is the decision with the least obvious consequence, so it is worth stating.

A subagent has a session, a log and a `SUB` thread in the chat agent's conversation — and **no screen
of its own**. It is not a row in the project list and it is not a chat. What a person can see of it:

- its reports, as `SUB` rows **in the project's chat**, which is where they arrive;
- the `subagent` tool calls in the chat's own pages, which say what was started and to whom;
- its `agent/<id>/session.log` **on disk**, which is readable if somebody opens the project folder —
  the same way `agent/session.log` is readable today.

**That is consistent with the app's rule** — everything that exists is reachable — as long as the
`SUB` row names the subagent and the chat shows the spawn. What would break the rule is a subagent
whose work left no mark a person could find; the `SUB` row is that mark, so **the report is not
optional and not silent.** A subagent that finishes with nothing to say must still write a `SUB` row
saying so, or the app has work with no trace.

---

## 9. What to build, in order

Two of these are worth landing whether or not subagents are ever built, and they come first for that
reason.

| # | step | why here |
|---|---|---|
| **1** | **`RepoLocks` mutex + honest `inFlight` + a trace line naming the holder** | the race is real **today** with two workers (§10); subagents make it N+1 |
| **2** | **Remove the `reason` tool** (spec, dispatch, tests) but **keep its reader** | unrelated to subagents, changes every chat agent, wants its own run |
| **3** | `Scope` on `AgentTools` (`Account` / `Project`), enforced in `run` not `specs` | the security boundary; testable with no model |
| **4** | The missing-commands paragraph, **generated from `specs(Account) - specs(Project)`** | needs §3 done, and stops models asking for what they cannot have |
| **5** | `SUB` row kind + `subagent` tool (spawn, write, list) + the refusal for a removed id | the delivery path and the identity rules |
| **6** | Subagent turns on the runner: own session, own clock, **keepalive and stop at the ceiling** | reuses `dispatch`; needs §5's one-clock decision |
| **7** | Wake on `SUB`, batched, sentence-only | three lines on top of `woke()` |
| **8** | Settings block: model/provider/effort + the warm ceiling, with the cost arithmetic in the row | last, because it is only a screen |
| **9** | The stale-read trace line, and the prompt paragraph about not trusting memory | small, and it is the only mitigation for lost writes |

---

## 10. The finding that outranks the feature

Unchanged from round 1, and it is still the first thing to fix.

`GitService.retryingLock`'s own comment says *"Every write in this app is made on one worker thread, in
order, so when a command fails on a lock there is no command of ours holding one."* **There are two
workers** — `AppViewModel.worker` and `AgentRunner.worker` — and the runner's runs every tool call,
including `AgentTools.commit` → `ProjectGit.commitOutcome` → `GitService.commitAll`. And
`inFlight = true` is passed **nowhere in this codebase** (verified, not assumed).

So the first of two writers to hit a lock calls `clearStaleLock`, which **deletes the other's live
`index.lock`**, and retries. That is not corruption; it is worse in one way — it looks like success.

Subagents make it N+1 workers. The mutex is one `synchronized` per commit around the whole
`open → stage → status → commit → prune` sequence (not around each JGit call, which leaves the window
between them open), keyed by `repoDir` the way `AgentSessions.lockFor` is keyed by file. A commit is
milliseconds, so the cost is nothing.

**And the lock must say who holds it** when it is held too long — one `Trace.note` naming `agent #2` /
`save` / `sync`. A mutex's failure mode is a stall, and a stall with no name is a bug report nobody
can answer. That is `Deadline`'s lesson: the thing that can hang must say what it was doing.

---

## 11. What is left open

1. **Does lowering N delete the subagent's folder?** I recommend stopping and marking, leaving the
   record. The operator's word was "removed"; this is the one place I would ask before building,
   because it destroys the only account of what that subagent did.
2. **Is the keepalive ceiling the stop clock, or is the idle timeout a second number?** I read
   "exactly" as *one number* (§5.3) and would build one. Two clocks with one name is how a person
   sets a number and gets different behaviour.
3. **Does the `SUB` row carry the subagent's whole report, or a summary plus a pointer?** The log
   already has `forConversation`'s rule for results too big to send — the same rule applies, and I
   would reuse it rather than invent a second.
