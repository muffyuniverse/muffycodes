# The agent chat — third round: the corrections, and what they broke

Status: **for discussion. Nothing built.** This answers the thirteen `@` comments in
`docs/PROPOSAL-agent-chat-v2-annotated.md`. Round 1 and round 2 are kept beside it.

Your corrections were mostly *simplifications*, and three of them removed a mechanism I had
built an argument on top of. §2 is what each removal broke and what to do about it. §3 is the
last four questions; two of them you have not answered yet.

---

## 0. What your answers changed

| your correction | what the design is now |
|---|---|
| *the message command is the finishing turn of the box, not one of its calls* | a box ends with a **terminating turn**, rendered as the agent's message, never as a `CALL`/`GOT` pair |
| *a permanent bar showing how many sessions are on, and reminders until a problem is resolved* | a strip in the app, and a notification that **repeats** until it is dealt with |
| *no window of the log in the context — the working context already holds what he did; he clears it himself, the app truncates the log* | the request is **the three layers plus this burst**. Nothing else, ever. |
| *not "compact" — clear out the records that do not relate to the current task. And limits are tokens, not fractions* | the instruction is a clear-up, and both limits are **absolute token counts**, clamped on selection |
| *let it wait until the next user message, no investigation* | `wait` is a **keep-alive**: it returns "continue waiting" and nothing else |
| *it must never stop until the operator removes it* | the service runs until switched off — and §2.5 says what Android requires for that sentence to be true |
| *`ask` and `conclude` instead of `message`; `off` merges into `stopped`; waiting is `idle`* | two terminators, three states, and the notification follows the state |
| *no stepping back to an earlier burst — each shows its own sequence* | the box is **this conversation**; history is the file |
| *the session log does need a limit, a generous one* | it is trimmed by the app — at a **burst boundary**, so a box is never cut in half |
| *config is restored from a backup, not from history* | "Reset" writes the copy shipped in the APK |
| *all files in the project that are not system or locked* | the agent's read scope, exactly |
| *the scripted tests are good* | §3 of round 2 stands as the test plan |

Settled and not raised again: JSON config with forms; tags are providers; no fallback, no
autorouting, only the pin; `—` for every number the API does not have; the effort ladder clamps
down and the operator may raise it; a key per account in the same encrypted store; a session per
project; save never syncs; no reasoning traces; `reason`; a result for every call; the lock
moves into the funnel and locked means read-only.

---

## 1. The design, as it now stands

### 1.1 The config

`files/accounts/<login>/model_conf.json`, shipped on first run, edited only by forms, committed
to the account's history like any other write.

```json
{
  "version": 1,
  "active": { "model": "deepseek/deepseek-v4.1-flash-1017",
              "provider": "deepseek", "effort": "high" },
  "models": [
    { "id": "deepseek/deepseek-v4.1-flash-1017",
      "providers": ["deepseek", "deepinfra/fp8", "decart/fp4", "cloudflare"] }
  ]
}
```

**`provider` is a tag** — `deepinfra/fp8`, not `deepinfra` — because OpenRouter's routing docs
say a base slug matches *every* endpoint of that provider ("dropping the tier never matches a
tier endpoint"). A name would quietly turn your pin back into autorouting inside one provider.
The request is therefore `"only": ["<tag>"], "allow_fallbacks": false`, and never `order`,
`sort`, `models`, `:nitro`, `:floor` or `service_tier`.

**Add model** is two steps: type an id → **Fetch** → the app calls
`/api/v1/models/<id>` (refuses: "OpenRouter does not have that model") and then
`/api/v1/models/<id>/endpoints`, and lists every endpoint as
`tag — in/out price — tps — tools`. An endpoint without `tools` is not selectable, because a
provider that cannot call a tool cannot run this agent. **Remove model** refuses the active one.
**Reset** writes the copy in the APK's assets and says so.

### 1.2 The picker

Tap a row → a sheet: the candidates with their stats, **Select** and **Cancel**. Prices and tps
are re-read when the sheet opens. Every missing number is **`—`, never 0** — and on today's data
that is most of the cells: `tps` is null for all four pinned endpoints, `max_prompt_tokens` is
null for all six endpoints of this model, and the model has no `terminal-bench` figure and no
`coding_index`/`agentic_index`. `—` is the answer, not a broken row.

### 1.3 The box

```text
┌──────────────────────────────────────────────┐
│ ←  shopping                    reset   stop  │
├──────────────────────────────────────────────┤
│  ‹ 3 of 6 ›                          »       │  this conversation's turns
│                                              │
│  TOLD  operator  rename milk.txt to dairy…   │
│        tree      0 · GitHub/ GitHub/milk.txt │
│        edit      0 · renamed, 1 commit       │
│  ──────────────────────────────────────────  │
│  ask   The README still links to milk.txt.   │  ← the terminating turn
│        Fix that too?                         │     (never a CALL/GOT pair)
├──────────────────────────────────────────────┤
│ [ tap to write ]                      send   │
└──────────────────────────────────────────────┘
```

- **A box is one conversation**: from an operator message to the next terminating turn. The
  navigator counts turns *inside it* — "3 of 6" means this conversation, not the session.
- **Three ways a box ends**: `ask`, `conclude`, or the app sealing it (`1 …` on the turn that
  could not finish). There is no cross-burst navigation; an earlier burst is read in
  `session.log`. That is now a deliberate limit, not a gap.
- `CALL`/`GOT` render as one line each — status and the first line of the body. Tapping expands
  the turn.
- **Consecutive `wait` turns collapse to one row** — "idle since 14:02 · 37 checks" — or an
  idle agent fills the box with hundreds of identical rows. The log keeps every one.
- The box **stops at ¾ of the window and scrolls**; the composer is `imePadding`-padded (the
  settings screen already is), so the keyboard lifts the content and never covers the field.

### 1.4 The loop and the tools

`tree` · `read` · `edit` · `create` · `delete` · `move` · `clone` · `history` · `restore` ·
`reason` · `ask` · `conclude` · `wait`.

- **Terminating tools are called alone.** `ask`, `conclude`, `wait` — anything else in the same
  reply is refused with `1 "…is a terminating tool; call it alone"` and the other calls' results
  in the body. It also fixes a real protocol hazard: these endpoints never emit two calls, so a
  reply that does is a sign the model is improvising.
- **A writing tool goes through the same funnel as the screen** — locks, hidden names and the
  structural folders are enforced there, not in the tool — commits at once, `unsentCount + 1`,
  never pushes.
- **`tool_choice` is `auto`, always** — measured: DeepSeek's endpoint reports
  `supports_tool_choice: {auto: true, function: false, required: false}`, so the forced form
  would be refused by one of the four pinned providers.
- A user message during a turn cancels the request, seals the running tool `1 interrupted by
  you`, and travels with that result in the next request. A user message while the agent is in
  `ask` or `idle` simply starts the next burst.
- A tool result's first line is `0` or `1`, always. Truncated → `1` with the body and "showing
  first N of M". Cancelled → `1 interrupted by you`.

### 1.5 What each request is made of

```text
① system_prompt.txt      how to use this app: the tools, the rules, the shape of a turn
② project_prompt.txt     how to work in this project
③ agent/context.md       what he did before — his own notes, cleared up by him

+ this burst: the assistant/tool exchanges since the operator's last message
+ the seam: the question he asked and the answer he was given
```

**No window of `session.log`. ③ is the memory.** The two consequences are in §2.1 and §2.3: the
burst is the only thing carried between requests, and the seam is needed for "yes" to mean
anything.

### 1.6 Three states, and what tells a person

| state | what it means | notification |
|---|---|---|
| **idle** | in `wait`, nothing to do | none — a healthy agent must be silent or the notification becomes wallpaper |
| **asked** | called `ask`; waiting for the operator | now, then **every interval** (Settings, default 1 h) until answered |
| **stopped** | not running: `stopped by you` · `error` · `loop` | only for the causes you did not cause, then every interval until resolved |

The reminder is **one notification per session, replaced in place** with a reminder count
(`2nd reminder · shopping: the agent asked something`), not a new notification every hour — a
tray with 24 copies of the same sentence is a tray nobody reads. Its two actions are **Open**
and **Stop this session**, which are the two ways to resolve it.

In the app, a strip above the file list, shown only while any session exists:
`2 sessions · 1 needs you`. The strip and the notification are the same state read twice, once
inside the app and once outside it.

### 1.7 Being on

A foreground service, `specialUse`, started when the first session is turned on and stopped when
the last one is stopped. It is the operator's switch: nothing in the app or the system turns a
session off on its own. §2.5 is the part that is not automatic.

### 1.8 The two limits

Absolute token counts, as you said, clamped by whatever is pinned:

| pinned to | context | its max output | input ceiling |
|---|---|---|---|
| `deepinfra/fp8` | 1,048,576 | 131,072 | 917,504 |
| `deepinfra/fp8/flex` | 1,048,576 | 131,072 | 917,504 |
| `decart/fp4` | 1,048,576 | 262,144 | 786,432 |
| `deepseek` | 1,048,576 | 393,216 | **655,360** |
| `cloudflare` | 1,048,576 | 943,718 | **104,858** |

**trigger** (clear-up message) and **hard** (the app trims) are the operator's numbers.
`hard` is off by default. Both are shown with the ceiling they were clamped against, and
§2.4 is the one thing about the clamp that can lose your setting.

Over the trigger, the system message says, every turn: *"Clear up your context: edit out the
records that do not relate to your current task."* It also names the size and the limit, because
"you are over" without the numbers teaches nothing.

Over the hard limit the app trims `context.md` from the top of the notes and says so in the log.
Never silently.

### 1.9 `context.md`

```markdown
<!-- task — never trimmed by the app; the agent may rewrite it -->
Rename milk.txt to dairy.txt in GitHub/, and keep the history.

<!-- notes — appended at the bottom; the app trims from the top of this block -->
- 14:02 milk.txt is the only file in GitHub/
- 14:05 the README links to milk.txt on line 3
```

Empty task, operator's message copied in verbatim by the app — their words, not the agent's, and
the one thing that must survive a clear-up. Then the agent's notes. The app trims the oldest
notes first, which is why notes append at the bottom.

### 1.10 `session.log`

The same five columns as the trace — `time id kind name detail` — so the parser, the rotation
idiom and the reading habits are already there.

```text
14:02:11.410    7 TOLD  operator  rename milk.txt to dairy.txt in GitHub/
14:02:19.883    8 CALL  tree      {"path":"GitHub","depth":2}
14:02:19.940    9 GOT   tree      0\nGitHub/\nGitHub/milk.txt
14:03:04.512   10 CALL  edit      {"path":"GitHub/milk.txt","to":"GitHub/dairy.txt"}
14:03:04.671   11 GOT   edit      0\nrenamed, 1 commit
14:03:20.006   12 CALL  ask       {"question":"The README still links to milk.txt. Fix that too?"}
```

Trimmed by the app at a generous size — **at a burst boundary**, keeping the newest complete
bursts that fit half the limit, with a line that says what went (`trimmed 214 lines from before
12:40`). The trace already does exactly this at 512 KB keeping 256 KB; this reuses it instead of
inventing a second trimmer.

---

## 2. What the corrections broke

Nine items. The first two are the ones that would have cost a rewrite if they had surfaced
during the build.

### 2.1 With no log window, "yes" answers nothing

Round 2 sent the last N turns. You removed them, correctly — ③ already holds what happened. But
the burst boundary now removes something else: the agent's own last words.

```text
burst 1   … CALL ask {"question": "The README links to milk.txt. Fix that too?"}
          ← the burst ends here; the request that follows carries ① ② ③ only
operator  "yes"
burst 2   messages = ① + ② + ③ + user("yes")
```

A bare "yes" with nothing it answers. The agent must either find its own question in ③ (only if
it wrote it down) or guess.

**Proposal — the seam, two messages, not a window:** the request after an `ask` carries that
question as the assistant turn before the operator's message. It is the smallest possible carry,
it comes from the log the box already renders, and it needs no token accounting because it is
capped at one message.
@ I dont understand, log and working context are same things!!! they are fed same records and the difference is that context is kept small by removing useless/old records.

### 2.2 A burst that waits forever, and the guard I proposed that would have killed it

`wait` as a keep-alive means a burst does not end until the operator speaks. Its transcript grows
by ~60 tokens a turn (a call and a result), 15 turns an hour — 900 tokens an hour, ~22k a day.
With `hard` at 96k the burst would seal on its own after about a day, and then there is nothing
pending: the agent gets a system line *"you are still idle; continue"* and a fresh burst whose
memory is ③.

Two things this decides:

- **The burst seals at a share of the window** (25%), and the seal is one system line, not a
  summary — the app must not start composing the agent's memory for it.
- **The loop guard I proposed is wrong as written.** "Stop after 12 turns with no `message`" would
  have stopped every healthy idle agent 48 minutes in. Counted properly: **20 consecutive turns
  that are neither `wait` nor terminating and changed no file** → the next tool call is refused
  with `1 "20 calls, no change, no question — call ask or conclude"`. An idle agent never trips
  it, and a spinning one trips in minutes.
  @ wait is not written repeatedly (just when it starts) - then context stays the same - what is even the reason to spam it with same messages?!

### 2.3 The two limits clamp — and the clamp must not eat your number

Clamping on selection is right, and it has one trap: if the clamp *writes back*, then switching to
a small model and back destroys the 96k you set. Keep **two values**: the number you asked for,
and the effective one (`min(asked, ceiling)`) that the agent is held to. The picker shows
`"96k asked · 88k in force (this pin allows 105k, minus 17k for its 943k-token reply)"` — and the
clamp also has to re-run when the **tag** changes at the same model, which is the case that makes
the ceiling move by 6×.

### 2.4 Idle costs three cents a day — unless the agent writes to `context.md` while waiting

Measured on the pinned endpoints' own prices (cache read $0.0024–$0.0030 per million):

| | per wake | per hour (15 wakes) | per day |
|---|---|---|---|
| cached read, 30k context | $0.00009 | $0.0014 | **3.2 ¢** |
| uncached, same context | $0.0045 | $0.0675 | **$1.62** |

**50×.** Implicit caching keeps the longest common prefix, and ③ sits before the burst — so
**every rewrite of `context.md` invalidates the whole request**. An agent that appends a note on
every `wait` turn turns a 3¢ day into a $1.60 day and gains nothing.

So: writing to ③ on a turn that only called `wait` is a defect, and it is measurable — the app
knows whether the file changed and whether the turn was work, so it can write a `NOTE` line the
day it happens. And the instruction that follows from it: `wait` changes nothing; notes are for
things that happened.
@ first see answer above, then the only time by design when context is PARTLY invalidated is asked to write in a single turn its own compaction edit. I dont understand what else in my design can cause it.

### 2.5 "Never stop" is a permission, not a service type

You said the operator decides, not Android. Android agrees — **for apps it has been told to
trust.** Concretely:

- **`dataSync` cannot carry this.** Android 15+ caps a `dataSync` foreground service at 6 hours in
  any 24. That is the system deciding, exactly as you said it must not. It has to be `specialUse`
  (with the declared subtype).
- **A service is not enough on its own.** In Doze the CPU may be held awake by a wake lock but
  **network access is suspended** for apps that are not exempt. A 4-minute wake that cannot reach
  OpenRouter is a 4-minute wake that fails, and with no fallback (by your pin) it fails into
  `stopped error`. So "never stop" requires **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** and the
  operator granting "Unrestricted" once, in the system settings — the app asks, with the reason
  spelled out ("otherwise Android suspends the agent's network while the screen is off").
- **Two holes cannot be closed by any app**: a force-stop from the system settings kills the
  service until the app is opened again; and several OEM battery managers ignore every rule.
  Both must be *visible* — the session's row says `stopped · the phone stopped the app`, and the
  agent's state is rebuilt from disk when the service starts, so a session resumes rather than
  dying silently.
- **Battery is real and must be stated in the settings screen**: a wake lock never released plus
  a network wake every four minutes. I would offer **"exact" (wake lock) and "when the phone
  wakes" (no lock)** as the one choice here, because the second makes the wait late and the first
  costs a night of battery — and you should be the one to pick, per the same principle.
  @ decide technicality yourself

### 2.6 A box with no terminating turn

`ask` and `conclude` end a box; `wait` does not; and a failure has no terminator either. So the
sealed case needs its own rendering or a stopped session looks like a box that is still thinking:
the last row reads `stopped · the pinned provider refused (503) after 3 tries`, in the box, not
in a dialog, and the top bar's state is `stopped`.

This is also where `conclude`'s notification lives, and I think it needs one: `conclude` while the
phone is in a pocket is the only signal that the work is finished, and it is not a reminder — one
notification, once, no interval. → question 4.
@ then next user message closes it

### 2.7 Trimming must not cut a burst in half, and the reader must not trust the tail

The trim happens at a burst boundary (operator message or terminating turn). If no boundary fits,
it trims everything and says so. And the box reads a file the app is appending to: **the last line
without a trailing newline is ignored** (still being written), and a line that does not parse is
shown as `GOT 1 line 18 could not be read` rather than dropped — the trace's own rule.
@ yes, autotrim is one messagebox at a time

### 2.8 `detail` cannot be JSON, or the file stops being greppable

You want the first four columns as captions and `detail` as the body. A body has newlines, and a
line-oriented file has none. Two candidates:

- **escape `\n` and `\\` inside `detail`** — the file stays one line per event, `grep 'CALL  edit'`
  still works, and reading it with your eyes still works;
- JSON-encode `detail` — correct and unreadable: every quote in a tool's argument becomes `\"`
  and the file you have been reading all session stops being readable by hand.

**Proposed: the escape.** `CALL`'s detail is a JSON object (tool arguments), `GOT`'s is `0` or `1`
then `\n` then the body, `ask`/`conclude` are plain text. One escaping rule for every kind.

### 2.9 Killing the change count loses the original intent, and it should be written down

Round 1's `wait` returned "the project changed by N" and the agent investigated. You replaced it
with "just wait for me". That means **a change nobody told the agent about is never noticed** — a
sync that pulls somebody else's commits, an edit made in the app while the agent idles. If you
want the agent to see those, you say so; there is no event that wakes it.

I think that is the right call for an agent whose job is your instructions, and I want it recorded
as a decision rather than discovered later as a bug. If you ever want it back, the cheap form is
not a count but a single line in the *wake* request ("2 files changed while you waited", from the
same `unsentCount` the row shows) — which costs one line per wake and no investigation loop.

---

## 3. Still undecided

1. **DeepInfra, one tag** — `deepinfra/fp8` ($0.0980/$0.2940 per M) or `deepinfra/fp8/flex`
   ($0.0784/$0.2352, cheaper than DeepSeek itself, a lower service tier). Shipped default: I
   would take **`/flex`**.
   @ two separate
2. **Retries on the pinned provider** — 3 tries with backoff on 429/5xx (same endpoint, so not a
   fallback) or none, fail straight to `stopped error`. I would take the retries: with Doze and a
   mobile network, a single blip otherwise ends a session you are not looking at.
   @ retries untill reconnect (1, 2, 4, 4... sec intervals)
3. **The `detail` escape** (§2.8) — `\n`-escaped, JSON-encoded, or no newlines at all.
@ you are the agent, decise on what is better for agents.
4. **A `conclude` notification** — one, once (§2.6), or silence because it is not a problem.
@ conclude means idle
---

## 4. Order of work

1. **The lock, properly** — into the funnel, a locked folder covers its children, locked means
   read-only. First because everything after it inherits it.
2. **The config, the forms and the picker** — no agent yet. At the end the app knows exactly what
   its pin is and why, and can say so.
3. **The door and one burst** — the key, the model call behind one interface, the scripted
   implementation, `read`/`tree`/`ask`, the box rendering from `session.log`.
4. **The writing tools and the memory** — `edit`/`create`/`delete`/`move`/`clone`/`history`/
   `restore`, the three layers, `context.md` with its two zones, the two limits, the clear-up loop,
   the seam.
5. **Being on** — the service, the exemption request, `wait`, the reminder ladder, the strip, the
   loop guard, the burst seal. The only wave that needs a phone in a pocket.

Waves 1–4 are provable in CI and cost nothing to run. Wave 5 is where the battery and the money
are, which is why it is last.
