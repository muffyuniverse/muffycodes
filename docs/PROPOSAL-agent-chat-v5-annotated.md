# The agent chat — fifth round: two files, one stream

Status: **for discussion. Nothing built.** This answers the clarification after round 4: the
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
