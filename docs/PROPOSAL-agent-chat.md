# The agent chat — fourth round: one record file, and an agent nothing can wake

Status: **for discussion. Nothing built.** This answers the ten `@` comments in
`docs/PROPOSAL-agent-chat-v3-annotated.md`. Rounds 1–3 are kept beside it.

Three of your corrections were not corrections — they were me inventing a mechanism and then
analysing its cost. There is **one record file**, not a log plus a working context; `wait` is
not written repeatedly; and an idle agent makes **no request at all**, so the 50× cost table I
built was about a design nobody asked for. §2 says what each removal actually decides, and §3
has the one thing your two answers now say differently.

---

## 0. What your answers changed

| your correction | what the design is now |
|---|---|
| *log and working context are the same thing — same records, the context is just kept small by removing useless and old ones* | **one file.** `agent/session.log` is both what the box renders and what the model is fed. There is no `context.md`, and no seam. |
| *`wait` is not written repeatedly — just when it starts* | `wait` **ends the turn**. One record at the start, nothing during, and the result is written when the wait ends. |
| *the only invalidation by design is the agent writing its own compaction edit* | correct, and §2.1 answers "what else could cause it": nothing, once the prompt layers sit above the records |
| *decide technicality yourself* (wake lock vs late waits) | decided: **no wake lock** — §2.2, because there is nothing to wake for |
| *the next user message closes a box* | the box's boundary is **the operator's message**, not the agent's |
| *autotrim is one messagebox at a time* | trimming removes **whole messageboxes**, oldest first, never the current one |
| *two separate* (DeepInfra) | **both** `deepinfra/fp8` and `deepinfra/fp8/flex` ship in the file, as two entries |
| *retries until reconnect (1, 2, 4, 4… s)* | retry forever with a 4-second cap; a blip is **idle with a sub-label**, never `stopped` |
| *you are the agent, decide what is better* (`detail` encoding) | decided: `\n`/`\\` escaped — §2.5 |
| *`conclude` means idle* | `conclude` → **idle**. No notification. |

Settled and not raised again: JSON config written by forms, providers are tags, `only` + no
fallbacks, `—` for every missing number, absolute token limits clamped on selection, a key per
account in the same encrypted store, a session per project, save never syncs, no reasoning
traces, `reason`, a result for every call, the lock in the funnel and read-only, `ask`/`conclude`
as the two terminators, three states, the script tests.

---

## 1. The design, as it now stands

### 1.1 One record file

```text
files/accounts/<login>/
  system_prompt.txt            ① how to use this app: the tools, the rules, the shape of a turn
  model_conf.json
  projects/shopping/
    project_prompt.txt         ② how to work in this project
    agent/session.log          ③ the records — the box renders them, the model is fed them
    GitHub/                    the worktree; his edits become commits here
```

**③ and the transcript are the same file.** What the model is sent is ① + ② + the records in
③, and the records stay small because useless and old ones are removed — by the agent (its
compaction edit) and by the app (the hard-limit trim). There is no second document and no
window-within-a-document: one stream, three ways it shrinks (§1.6).

**The box is a UI grouping over that stream**: from one operator message to the next. It is not
a context boundary and nothing is rebuilt at it.

The records, same five columns as the trace (`time id kind name detail`):

```text
14:02:11.410    7 TOLD    operator  rename milk.txt to dairy.txt in GitHub/
14:02:19.883    8 CALL    tree      {"path":"GitHub","depth":2}
14:02:19.940    9 GOT     tree      0\nGitHub/\nGitHub/milk.txt
14:03:04.512   10 CALL    edit      {"path":"GitHub/milk.txt","to":"GitHub/dairy.txt"}
14:03:04.671   11 GOT     edit      0\nrenamed, 1 commit
14:03:20.006   12 NOTE    reason    the README still links to milk.txt on line 3
14:03:33.140   13 CALL    wait      {}
```

`NOTE` is the `reason` tool — his findings, in the record, so they survive a trim that removes
the turn they came from.

### 1.2 The box

```text
┌──────────────────────────────────────────────┐
│ ←  shopping                    reset   stop  │
├──────────────────────────────────────────────┤
│  ‹ 3 of 6 ›                          »       │  turns, in this messagebox
│                                              │
│  TOLD  rename milk.txt to dairy.txt…         │
│        tree      0 · GitHub/ GitHub/milk.txt │
│        edit      0 · renamed, 1 commit       │
│  NOTE  the README still links to milk.txt…   │
│  idle  since 14:03 · waiting for you         │
├──────────────────────────────────────────────┤
│ [ tap to write ]                      send   │
└──────────────────────────────────────────────┘
```

- Navigate turns inside the messagebox; no way back to an earlier one (the file is the history).
- Consecutive idle-check rows never appear, because there are none (§1.4).
- `CALL`/`GOT` are one line each; tapping expands the turn.
- The box stops at **¾ of the window** and scrolls. The composer is `imePadding`-padded (as the
  settings screen already is), so the keyboard lifts the content and never covers the field.

### 1.3 The tools

`tree` · `read` · `edit` · `create` · `delete` · `move` · `clone` · `history` · `restore` ·
`reason` · `ask` · `conclude` · `wait`.

- **`ask`, `conclude`, `wait` end the turn**, and are refused if called with anything else
  (`1 "… ends the turn; call it alone"`, with the other calls' results in the body).
- `ask` → the session needs the operator. `conclude` → finished. `wait` → nothing to do; go idle.
- A writing tool goes through the same funnel as the screen, commits at once, `unsentCount + 1`,
  never pushes.
- `tool_choice` is `auto` — measured: DeepSeek's endpoint declares
  `supports_tool_choice: {auto: true, function: false}`, so the forced form would be refused by
  one of the four pinned providers.
- A result's first line is `0` or `1`. Truncated → `1`, body, "showing first N of M".
- The operator's message: during a turn it cancels the request and seals the running tool
  `1 interrupted by you`, travelling with that result; while idle it starts the next turn.
- Reading and writing: everything in the project that is not system and not locked. Never
  another project's folder, never `.git/`, never `.muffy/`, never the prompt files.

### 1.4 `wait`, and what an idle agent costs

**`wait` ends the turn.** One `CALL wait` record when it starts. Nothing while it waits. When it
ends, its result is written — `0 operator spoke` or `1 stopped`. If nothing ever ends it, the
record stands unanswered and the box shows `idle since 14:03`, which is the truth.

**An idle agent makes no request.** The 4-minute interval is the app's own poll of local state —
has the operator typed, is the network back, was the session stopped — and it does not call the
model. So the idle cost is **zero**, not the 3.2¢ a day I computed for a design that sent a
heartbeat every four minutes. The consequence is in §3, and it is the one thing left to decide:
**nothing except the operator can wake the agent.**

### 1.5 Three states

| state | meaning | notification |
|---|---|---|
| **idle** | in `wait` · concluded · reconnecting after a network failure | none. Sub-labels: `waiting for you`, `finished`, `reconnecting (4 s)` |
| **asked** | called `ask` | now, then **every interval** (default 1 h) until answered |
| **stopped** | `stopped by you` · `the phone stopped the app` | only for a cause the operator did not choose, then every interval until resolved |

**Retries never produce `stopped`**: 1 s, 2 s, 4 s, 4 s… until it reconnects, which is a blip the
app handles rather than a session the operator has to notice. The bar and the box show
`reconnecting` with the current delay so it is visible without being an alarm.

The reminder is one notification per session, **replaced in place** with its count
(`3rd reminder · shopping: the agent asked something`), with **Open** and **Stop** as its two
actions. A tray with 24 copies of one sentence is a tray nobody reads.

In the app, a strip above the file list while any session exists: `2 sessions · 1 needs you`.

### 1.6 How the records shrink — three mechanisms, one at a time

1. **The agent's compaction edit** — when over the trigger, a system message each turn: *"Clear
   up your context: edit out the records that do not relate to your current task."* The agent's
   judgement, which is what was asked for.
2. **The app's trim** — over the hard limit (off by default, an absolute token count clamped to
   the pin), the app removes **whole messageboxes, oldest first**, one at a time, and records
   `trimmed 2 messageboxes from before 12:40`. It never removes the current one, and never
   leaves a half box.
3. **The agent may not remove the newest `TOLD` record** — the task it is working on. Everything
   else in the file is his to edit. Without that one rule, a compaction edit can delete the
   instruction and the turn afterwards has no job.

The trigger and the hard limit are the operator's numbers, absolute tokens, both shown with the
ceiling they are clamped against (which moves 6× between tags — `cloudflare` leaves 105k of
input where `deepseek` leaves 655k). Two values are kept: the number asked for, and the one in
force, so a visit to a small model does not destroy the setting.

### 1.7 Being on

A foreground service, `specialUse` with the declared subtype (not `dataSync`, which Android 15+
caps at six hours in any 24), started by the first session and stopped by the operator.

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with the reason spelled out, because in Doze the network
is suspended for apps that are not exempt and a pinned provider with no fallback then fails.
**No wake lock** (§2.2). Two holes remain and must be visible: a force-stop in the system
settings, and OEM battery managers — the row says `stopped · the phone stopped the app`, and
state is rebuilt from disk so a session resumes rather than dying quietly.

---

## 2. What the corrections decide

### 2.1 Answering "what else could invalidate it": nothing — with one placement rule

You asked what other than the compaction edit could partly invalidate the context. Strictly: the
app's trim, which removes records from the top — same class, same frequency, also by design. And
appending is not invalidation at all: a provider's implicit cache keeps the longest common
prefix, so a new record at the end is charged as new tokens and everything before it is a cache
hit. That is the normal turn, and it is why this design is cheap.

The one rule that follows is about **order**, and it costs nothing to obey: ① and ② go *above*
the records. Put them below, and every compaction or trim invalidates the prompt layers too, so
the instructions are re-read at full price on the most expensive turn. Above, the invalidation
stops at the records.

So: `①②` then the records, always, and compaction rewrites only the records.

### 2.2 No wake lock, because there is nothing to wake for

The choice I put to you last round — exact waits (wake lock) or late waits — was a false one
once the idle agent stopped making requests. A wake lock exists to run a turn while the screen
is off, and an idle agent runs no turns.

- **No wake lock.** The service holds the session and waits for the operator. The battery cost of
  an idle agent becomes the cost of a notification, which is nothing.
- `specialUse` + the battery exemption are still needed, for one reason only: so that the
  operator's message is delivered and the turn that follows is not killed mid-flight.
- Doze delays the *delivery* of a turn nobody is waiting for, and a delay on a turn triggered by
  the operator is invisible — they are looking at the screen when they sent it.

I also dropped the "exact vs late" setting from the settings screen: there is no correct choice
for the operator to make, which is the test for whether a setting should exist.

### 2.3 `wait` as a terminating tool fixes a protocol hazard and a UI problem at once

If `wait` returned "continue waiting" and the model called it again every four minutes, the
record file would gain ~60 tokens a turn forever — and the box would fill with identical rows.
Terminating it removes both, and it is *more* honest: an agent with nothing to do is not
thinking every four minutes, it is waiting.

The dangling-call consequence is real and already covered: a `wait` that never ends leaves an
unanswered `CALL`, which is legal in the file (it is the record of being idle) but must not be
legal in a request. So the request is only built when there is something new — and if the app
died mid-wait, the call is sealed on open with `1 the app was closed before this answered`.

### 2.4 Retry-until-reconnect moves one failure out of the operator's life

`stopped` is for things a person must deal with. A five-second network blip is not one, so the
retry ladder (1, 2, 4, 4… s, forever) keeps the session `idle · reconnecting` and the operator
is not notified. What that costs: a session pinned to a provider that is genuinely down retries
every four seconds for as long as the phone is on. That is a cheap request (no model call, just
a failed connection attempt) but it should stop being noisy in the log — the log gets the first
attempt and then **one line when it reconnects**, with the number of tries, not one line per
attempt.

### 2.5 Decided: `detail` is `\n`- and `\\`-escaped

The file stays one line per record, so `grep 'CALL  edit'` works and reading it by eye works.
JSON-encoding `detail` was the alternative and it makes the file unreadable — every quote in a
tool's arguments becomes `\"`, and this is a file the operator reads. One escaping rule for every
kind: `CALL`'s detail is a JSON object, `GOT`'s is `0`/`1` then `\n` then the body, `NOTE`,
`ask`, `conclude` are plain text.

### 2.6 Decided: both DeepInfra tags ship

`deepinfra/fp8` ($0.0980/$0.2940 per M) and `deepinfra/fp8/flex` ($0.0784/$0.2352, a lower
service tier, cheaper than DeepSeek itself) are two entries in `models[].providers`, and one of
them is `active.provider`. Two rows in the picker, so the choice between them is visible rather
than made for you. The shipped default remains **`deepinfra/fp8`** for the *default* — `/flex` is
a discount tier with no latency or availability figures published on this model today (tps is
null on both), and a default should be the boring one; `/flex` is one tap away and labelled with
its price.

### 2.7 The box needs no "no terminator" case, because the box is the operator's

You are right that the next user message closes it, which removes the case I was solving. What
remains is only display: the last row of a box can be `idle since 14:03 · waiting for you` or
`stopped · the phone stopped the app`, and the top bar carries the state. No dialog, no special
end-of-box rendering.

---

## 3. The one thing your answers now say two ways

Round 3 you said: *"just let it wait until the next message from user, no investigation (**in the
same 4 min intervals, with message "continue waiting"**)"*. Round 4 you said: *"wait is not
written repeatedly (just when it starts) — then context stays the same — **what is even the
reason to spam it with same messages?!**"*

Those two cannot both be how the loop works, and the difference is not cosmetics:

| reading | what happens every four minutes | idle cost | what can wake the agent |
|---|---|---|---|
| **A — the poll is local** (what round 4 says) | the app checks its own state; **no request** | **zero** | only the operator |
| **B — the poll is a turn** (what round 3 said) | one request whose only new content is "continue waiting" | ~3.2 ¢/day cached | the operator, or the timer |

I have written this document as **A**, because it is what your objection describes and because
it is the better design: B buys nothing an idle agent needs, and a context that is rewritten by
its own heartbeat is the one thing that makes the cheap design expensive.

**But A has a consequence that must be stated, because it is not reversible later without
noticing:** a `wait`ing agent is woken by nothing except a person. A sync that pulls somebody
else's commits, an edit made in the app, a file changed by another device — none of them reach
him. §2.9 of round 3 recorded that as your call and I still think it is right for an agent whose
job is your instructions. It does mean the operator cannot use the agent as a watcher, and that
is worth knowing before someone asks for it.

→ if you meant **B**, say so and I will write the heartbeat variant (it is one paragraph, and
the cost is the table above).

---

## 4. Order of work

1. **The lock, properly** — in the funnel, a locked folder covers its children, locked means
   read-only. First, because everything after it inherits it.
2. **The config, the forms, the picker** — no agent. It ends with the app able to say exactly
   which pin its agent would use, and why.
3. **The record file and one turn** — the key, the model call behind one interface with a
   scripted implementation, `read`/`tree`/`ask`, the box rendering the records, the messagebox
   grouping, `<`/`>`/`»`.
4. **The writing tools and the shrinking** — `edit`/`create`/`delete`/`move`/`clone`/`history`/
   `restore`, `reason`, the three prompt layers, the compaction message and the agent's own edit,
   the app's messagebox trim, the two limits and the clamp.
5. **Being on** — the service, the exemption, `wait` as a terminator, the retry ladder, the
   states, the reminder ladder and the strip, the loop guard. The only wave that needs a phone in
   a pocket.

Waves 1–4 are provable in CI and cost nothing to run. Wave 5 is where the battery and the money
are, which is why it is last — and with an idle agent that makes no requests, both are now
almost nothing.
