# The agent chat — fifth round: two files, one stream

Status: **round 5, agreed, and built.** The record below is that round unchanged; what the code
now does, and the one place it does not yet match, is §4. This answers the clarification after
round 4: the
session log and the working context are **two files with the same structure, both fed by the
same events**, and `wait` **is** re-sent every four minutes.

Rounds 1–4 are kept as `-v1/-v2/-v3/-v4-annotated`. Round 4 said "one file" and "an idle agent
makes no request". Both were wrong. This is the corrected model.

---

## 0. The two files

```text
files/accounts/<login>/
  system_prompt.txt            how to use this app: the tools, the rules, the shape of a turn
  model_conf.json
  projects/shopping/
    project_prompt.txt         how to work in this project
    agent/
      session.log              the evidence: every event, app-truncated at a generous limit
      context.log              the working context: the same rows, minus what the agent cleared
    GitHub/                    the worktree; his edits become commits here
```

**Same structure, same rows, one event written to both places.** A `CALL`, its `GOT`, a `TOLD`,
a `NOTE` — each is appended to `session.log` *and* to `context.log`, in the same five columns as
the trace (`time id kind name detail`).

What differs is only **what is kept**:

| | `session.log` | `context.log` |
|---|---|---|
| what is in it | every event | every event, minus what the agent cleared out |
| who shrinks it | the app | the agent (its compaction edit), and the app at the hard limit |
| how | whole messageboxes, oldest first, one at a time, at a generous limit | the agent edits unrelated rows out; the app trims at the hard limit |
| fed to the model | no | **yes** — this is the context |
| rendered by the box | yes | no |

The request is ① + ② + **the rows in `context.log`**. Nothing else.

**A property worth keeping deliberately:** `session.log` minus `context.log` is exactly the
agent's judgement about what did not matter. Diff the two files and you see what he threw away.
That is an auditable decision rather than a hidden one, and it is why the same-row rule is worth
its cost.

---

## 1. `wait`, which is re-sent every four minutes

The agent calls `wait`; the turn ends there. Four minutes later the app **sends the request
again**. That is a real request, and it happens every four minutes for as long as the agent is
idle.

**What differs is where the rows go.** The repeated `wait` call is appended to `session.log`
every time — fifteen rows an hour, which is why that file's limit is generous and why the app is
the one that truncates it. It is appended to `context.log` **once**, the first time. The
identical repeats are not.

So:

```text
session.log   … 13 CALL wait  {}          14:03:33
                13 GOT  wait  0            14:03:33   (written when it ends)
                14 CALL wait  {}          14:07:33
                15 CALL wait  {}          14:11:33
                16 CALL wait  {}          14:15:33
                …fifteen an hour

context.log   … 13 CALL wait  {}
                13 GOT  wait  0 still waiting
                ← and nothing more until the operator speaks
```

**And the reason the rule exists is not tidy bookkeeping — it is the cache.** Each row carries a
timestamp. If every poll appended `14:11:33 CALL wait`, the request would differ from the last
one by that timestamp, and a provider's implicit cache matches on the longest common *prefix*:
one differing row at the end costs nothing, but a context that grows by a row every four minutes
means the request is never byte-identical, and the model's view of "where am I" drifts as
identical lines pile up. Serving the same prefix with the same request is the cheapest thing
this design can do. **Keeping the repeated `wait` out of `context.log` is what keeps the poll
identical.**

Therefore: **nothing in the request may carry a clock**, and the wait's result is a constant
("`0` still waiting"), written once. The poll is the same bytes, a new HTTP request.

### What that costs

The idle poll is not free, and my round-4 "zero" was wrong. Fifteen polls an hour, ~30k-token
context, all of it a prefix hit:

| | per poll | per hour | per day |
|---|---|---|---|
| cached read, 30k | $0.00009 | $0.0014 | **3.2 ¢** |
| the reply (a `wait` call, ~40 tokens out) | negligible | — | — |

**About three cents a day per idle agent.** The 50× risk is unchanged: it is the *rewrite* of
`context.log` that costs, not the poll. Appending is a hit; rewriting the middle is a full
re-read. Which is why the prompt layers sit above the rows (§2.1) and the agent is told to clear
out, not to annotate.

---

## 2. What this fixes in the design

### 2.1 Placement, unchanged and now more clearly earned

① then ② then the rows. The agent's compaction edit rewrites only `context.log`, so the
invalidation stops at the first record and never reaches the instructions. The app's trim removes
whole messageboxes from the top of `context.log`, oldest first, one at a time, never the current
one.

And the rule that makes a compaction edit safe: **the agent may not remove the newest `TOLD`
record** — the task it is working on. Everything else in `context.log` is his to delete.

### 2.2 Being on

A foreground service, `specialUse` with the declared subtype (`dataSync` is capped at six hours
in any 24 on Android 15+, which is the system deciding for the operator).
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with the reason spelled out, because in Doze the network
is suspended for apps that are not exempt, and every poll would then fail against a pinned
provider with no fallback.

**No permanent wake lock**, decided. The poll is a request whose answer is a `wait` call; if Doze
defers a poll while the screen is off, the agent is idle either way and nothing is lost. A wake
lock held forever buys exact four-minute spacing on work that does not exist, and costs a night
of battery. So the "exact vs late" setting does not exist: there is no correct choice for the
operator to make.

Two holes remain and must be visible rather than silent: a force-stop in the system settings,
and OEM battery managers. The session's row says `stopped · the phone stopped the app`, and state
is rebuilt from disk when the service starts, so a session resumes instead of dying quietly.

### 2.3 States

| state | meaning | notification |
|---|---|---|
| **idle** | polling in `wait` · concluded · reconnecting | none. Sub-labels: `waiting`, `finished`, `reconnecting (4 s)` |
| **asked** | called `ask` | now, then every interval (default 1 h) until answered |
| **stopped** | stopped by the operator · stopped by the phone | only for a cause the operator did not choose |

`conclude` → idle, no notification. Retries are 1, 2, 4, 4… seconds until reconnect, so a network
blip stays `idle · reconnecting` and never becomes something to resolve. The log records the
first attempt and one line when it reconnects with the try count, not one line per attempt.

### 2.4 One consequence to keep in mind, not to decide now

A `wait`ing agent is woken by the timer only to poll. Nothing in the design tells him the project
changed — a sync pulling someone else's commits, an edit made in the app, a file changed by
another device. His next turn after the operator speaks will see those changes if he looks.

That is consistent with everything else here: the agent is woken by the operator, and its job is
the operator's instruction. Recorded so that it is a decision and not a surprise.

---

## 3. What is left

Read, not decided:

- `session.log`'s generous limit — the app truncates whole messageboxes, oldest first. Round 2's
  figure was 512 KB keeping 256 KB for the trace; the session file is larger and should be told
  when the format is written.
- Whether `context.log`'s rows are the *same ids* as `session.log`'s. They should be, so the diff
  is exact; an id that appears only in `session.log` is a row the agent cleared.

Everything else — the config, the forms, the picker with tags and `—`, the pin, the tools, the
lock, the record format, the three states, the reminder ladder, the trim rules, the test scripts
— is settled as written in the rounds this document corrects.

---

## 4. State as built (checked against the code)

Everything in this round is built and green except what §4.2 names. Where the code has answered a
question §3 left open, the answer is given with what pins it.

### 4.1 The two questions in §3

- **`session.log`'s limit** — 4 MB, keeping the newest 2 MB (`AgentLog.SESSION_LIMIT`): whole
  messageboxes, oldest first, one trim per call, and **one marker row carrying the running total**
  rather than one marker per trim. Each trim used to write its own marker; the model never sees
  those rows, but the size counts them, so a context trimmed often filled with invisible rows and
  was then trimmed for them. (`a trim in one file does not let the other file's numbering start
  again` pins both halves.)
- **Whether the two files hold the same ids** — they do, and it is not optional: one counter per
  project, seeded from the higher id in *either* file, so an id never means two rows and never
  goes backwards — including across a trim, because the session drops from the oldest end and the
  highest id survives. Being per project rather than global is deliberate and safe: a request is
  built from one project's record, so `call_<id>` is unique in the only scope an endpoint checks.

### 4.2 The one place the audit property leaks

"Diff the two files and you see what he threw away" is true of the agent's clears. It is not yet
*exactly* true of the app's own rows, because **each trim writes its marker into one file only** —
the session's into `session.log`, the context's into `context.log`. So an id that appears only in
`session.log` is either a row the agent cleared or the app's session marker, and an id only in
`context.log` is the context's marker.

Two ways out, not decided: write the marker to both files (an app act recorded once, in both), or
define the audit as the diff *ignoring `NOTE trimmed`*. The second costs nothing and loses nothing;
the first makes the files agree row for row.

### 4.3 Still missing

- **The battery-optimisation ask.** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared, and the
  manifest says it is "asked for in one place" — and nothing asks: no screen fires
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. §2.2's second hole is therefore open on every
  phone, for as long as Doze is willing to suspend the network under a poll.

### 4.4 Corrections made while building, each now one rule in one place

- The session file has **one lock per file**, not one per object: it has two writers by design (the
  screen and the runner's worker), and a `put` that read the file, changed its key and wrote it
  back silently undid the other writer. The record's file had the same fault and the same fix.
- **The operator's stop is the ending's business, not each caller's.** The screen and the runner
  each carried a copy of "a stop is not undone by a turn that was in flight", and the copies
  disagreed — the runner let a harder ending overwrite the person's stop. It is now
  `End.onto(session, now)`, and one test pins the case that told the copies apart.
- An outage is **bracketed**: one `stopped` row at its first failure, and one `retry` row —
  "reconnected after N tries" — when it comes back, in the session only, so the poll that follows
  is byte-identical to what it would have been.
