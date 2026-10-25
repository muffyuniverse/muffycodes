# The agent chat — second counter-proposal

Status: **for discussion, nothing built.** This answers the twenty `@` comments in
`docs/PROPOSAL-agent-chat-v1-annotated.md`. Where I still disagree I say why and give the
number; where your answer opened a new hole I point at it; where you settled something I do
not raise it again.

New since the first round, and worth reading first: **§2.1 — the pin you asked for is
mechanically possible, and there is a catch that decides the shape of the config file.**

---

## 0. What your answers settled

| § | your answer | what it now is |
|---|---|---|
| C1 | *good* | the file is the only store; Settings is an editor of it |
| C2 | *accept, plus add / remove / restore buttons with a form that fetches from OpenRouter and errors if the model or provider is not found* | **forms are the door.** The file is a store, not an interface. This is a simplification and I take it. |
| C3 | *json, people use forms* | **JSON.** No YAML subset, no hand-written config parser, no anchors to explain. |
| C4 | *then no need for the undeclared stats* | drop **cache hit rate**, **token share**, **terminal-bench**. |
| C5 | *show different tags as separate choices* | a provider entry is a **tag**; three DeepInfra rows are three rows |
| C6 | *moves down to a supported one, the operator can raise it* | clamp **down**; report it |
| C7 | *foreground service — max resiliency* | a service, not an Activity (§2.4 asks what "max" means) |
| C8 | *in-flight generation is cancelled, right order* | accepted as written |
| C9 | *hard limit is a reserved option, much higher than the trigger, below the model's context* | accepted, with two corrections (§2.3) |
| C10 | *agree* | first line `0`/`1` on every tool result; truncated and cancelled are `1` |
| G1 | *yes, same as with the GitHub account* | the model key sits in the same encrypted store, **per account** |
| G2 | *can the box show tool calls, with `<`/`>` between turns, `>>` to latest, ¾ of the screen, a composer below that the keyboard does not cover* | new screen, specified in §1.3 |
| G3 | *no cap; hourly notification of what agents are waiting, interval in Settings* | accepted; §2.5 makes "waiting" mean something specific |
| G4 | *the agent gets a response for each call, so `wait` must be called alone and answers an error if it is not* | accepted; §2.2 keeps one arithmetic correction |
| G5 | *locked files should be read only* | lock = read-only to the agent, and to the app |
| G6 | *yes, fix it properly* | the lock moves into the funnel and covers descendants |
| G7 | *three prompt layers: system (how to use the app), project (how to work in it), work context (what he did before)* | **three**, composed (§1.6) |
| G7 | *first four columns as captions, detail as the body — this data renders the chat* | the session log **is** the transcript (§2.7) |
| G8 | *don't quite understand, but it needs to be testable — prepared scenario tests like the tours* | §3, in plain words, with the file format |
| G9 | *no fallback, no autorouting — only the selection pin* | always pinned; §2.1 |

Settled, and I will not raise again: a session per project opened from the project; save
(never sync); no reasoning traces; `reason`; a result for every call; the numeric tools list
including `history`/`restore`; a config an operator can reset.

---

## 1. The design, as it now stands

### 1.1 The config file — JSON, written by forms

`files/accounts/<login>/model_conf.json`. Shipped on migration, rewritten only by the forms
(AppEditor-style: committed to the account's history, so a bad edit is two arrow presses away).

```json
{
  "version": 1,
  "active": {
    "model": "deepseek/deepseek-v4.1-flash-1017",
    "provider": "deepseek",
    "effort": "high"
  },
  "models": [
    {
      "id": "deepseek/deepseek-v4.1-flash-1017",
      "providers": ["deepseek", "deepinfra/fp8", "decart/fp4", "cloudflare"]
    }
  ]
}
```

- **`provider` is a tag, not a name** — `deepinfra/fp8`, not `deepinfra`. §2.1 is why.
- **Exactly one active provider.** Not a list. "No autorouting" means the request names one.
- **`models[].providers` is an allow-list for the picker**, not a routing order. Its only job
  is to stop the picker offering 34 endpoints the operator has never heard of.
- **Read on every turn**, so an edit takes effect on the next turn with no restart.
- **A file that will not parse is refused**, not replaced: the app keeps the last good copy in
  memory, and the chat says "model_conf.json line 7: expected `,`" — with the position, because
  JSON's errors are cryptic and the operator is the one who typed it.

Three buttons, as you asked: **Add model** · **Remove model** · **Restore the default config**.
The add form is two steps, because the second step is what makes the tag real:

1. type the model id → **Fetch** → the app calls `/api/v1/models/<id>` and refuses with
   "OpenRouter does not have that model" if it is not there;
2. the app calls `/api/v1/models/<id>/endpoints` and lists **every endpoint as
   `tag — in/out price — tps — tool support`**, with the ones that cannot do tool calling
   greyed out and unselectable (a provider that cannot call a tool cannot run this agent, and
   finding that out eight seconds into the first turn is a worse way to learn it);
3. tick the tags to allow → **Save** writes the model into `models[]`.

Remove refuses the active model, with one sentence: "That is the model in use — pick another
first." Restore writes the shipped file and says so.

### 1.2 The picker

Tapping the model, provider or effort row opens a full-height sheet: a scrolling list of
candidates, each row a line of stats, **Select** and **Cancel** at the foot. Prices and tps are
re-fetched on open (`/models` and `/models/<id>/endpoints`), cached in memory for the session.

Model row: modalities in and out separately · released · context · max input · max output ·
Intelligence Index. Provider row: input, output, cache price per million, tps. Effort row: the
model's own ladder, left to right, min to max.

Every number is **the endpoint's**, once a provider is chosen; the model's figures are the top
provider's and are labelled as a summary. And every missing number is **`—`, never 0** — which
matters, because as of today `tps` is null for all four pinned endpoints of our model and
`max_prompt_tokens` is null for all six of its endpoints. **The picker will show `—` in a lot
of cells, and that is the truthful answer, not a broken screen.** I would rather say this now
than have it read as a bug later.

### 1.3 The chat screen

```text
┌─────────────────────────────────────────────┐
│ ←  shopping                   reset   off   │   project it belongs to; two buttons
├─────────────────────────────────────────────┤
│  ‹ 3 of 7 ›                          »      │   turn navigator: < > between turns
│                                             │
│  CALL  tree                                 │   ① the turn's calls, one line each
│  GOT   0  repo/  repo/Milk.txt  …           │      what was asked, what came back
│                                             │
│  CALL  edit                                 │
│  GOT   0  repo/Milk.txt — 1 line changed     │
│  … (scrolls inside the box)                 │
│                                             │
│  SAID  I changed the milk line.             │  ← the `message` call ends the box
├─────────────────────────────────────────────┤
│ [ tap to write ]                    send     │   composer, always here
└─────────────────────────────────────────────┘
```

- **A box is a burst**: from an operator message (or a wake) to the next `message` call. The
  next operator command opens the next box.
- The box **stops at ¾ of the window height and scrolls**; the composer never moves (it is
  `imePadding`-padded, as the settings screen already is, so the keyboard pushes the content
  and not the field).
- `<` and `>` step between turns **inside** the box, `»` jumps to the newest turn. **§2.6 asks
  what happens at the ends and how you reach an older box** — that is the one part of your
  description I could not settle by reading it.
- **`GOT` never prints the whole tool response** — one line: status, and the first line of the
  body. A `read` of a fifty-line file would otherwise push the conversation off the screen.
  Tapping a turn expands it (the full body is in the log).
- This screen renders **only** from `agent/session.log` (§2.7), so a chat you can see is a
  chat you can reconstruct from the file, and vice versa.

### 1.4 The loop

One turn = read the three prompt layers + the log window → one request → the reply is either
tool calls or a message. Every call gets a result before the next request is built, first line
`0` or `1`. A writing tool goes through the same `Workspace` funnel as the screen (so locks and
hidden names apply by construction, G6), commits at once, `unsentCount + 1`, **never pushes**.

Tools: `tree` (depth, path, name filter, content filter) · `read` (path, filter, range) ·
`edit` (path, filter-based replace, or whole-file) · `create` · `delete` · `move` · `clone` ·
`history` (list versions → pick) · `restore` · `message` · `reason` · `wait`.

Rules the loop enforces, not the prompt:

- **`wait` must be alone.** Called with anything else it returns immediately with `1` and
  "do not call wait with other tools", and the body carries the other tools' results so
  nothing is lost. (§2.2 has the one thing this does not fix.)
- **A dangling call is sealed on open**: `1 the app was closed before this answered`.
- **A user message cuts the turn short**: the request in flight is cancelled, the running tool
  is closed with `1 interrupted by you`, and the next request carries that result *and* the
  message — the legal order.
- **The tool timeout** is 4 minutes by default, `wait` excluded, and a response longer than the
  configured limit is truncated with `1` and "showing first N of M characters".

### 1.5 Being on

A **foreground service** with a permanent notification, started when any session is on. The
Settings switch is the service's switch; its "N sessions on" count is the notification's
subtitle. §2.4 is the question I need answered before this can be built: "max resiliency" has
three grades and they cost different things.

The hourly notification (interval in Settings, default 1 hour) is **not** a copy of the
service notification. It says what **needs you**: sessions that called `message` and are now
idle, sessions that stopped on an error, and sessions that are off. A session sitting in a
`wait` is working as designed and is not mentioned — otherwise the notification fires every
hour forever for a healthy agent, which is how a person learns to ignore notifications.

### 1.6 The three prompt layers, and what the context actually is

```text
files/accounts/<login>/
  system_prompt.txt           ① how to use this app: the tools, the rules, the shape of a turn
  model_conf.json
  projects/shopping/
    project_prompt.txt        ② how to work in this project: what it is, what good looks like
    agent/
      session.log             the record; also what the box renders
      context.md              ③ what he did before: his own notes, compacted over time
    GitHub/                   the worktree; his edits become commits here
```

**The context sent with every request is ① + ② + ③ + a window of the session log** — the last
turns, not the whole file. That distinction does work in §2.7: it is why `session.log` can be
append-only and unbounded while the *context* stays bounded.

Every layer is editable and lockable. A lock makes it **read-only** — to the agent and to the
app (G5).

**The limits, expressed as fractions so they cannot be wrong**: `trigger` = 25% of the
effective window, `hard` = 60%, both shown in absolute tokens for the current model *and*
provider, both overridable with a number. §2.3 is why a fraction and not a number.

Over the trigger: the system message says "compact" every turn until the context is under it.
Over the hard limit: the app trims the oldest turns and says so in the log. Never silently.

---

## 2. Where the answers opened something new

Ten items. The first three are arithmetic; the next three are Android; the last four are the
log's own shape.

### 2.1 The pin works — and it decides that a provider is a **tag**, not a name

Your answer was "no fallback, no autorouting, only the selection pin". I checked whether that
is expressible, and it is, exactly. From OpenRouter's routing docs:

> To target a **specific** variant or region, use the full slug including the suffix (e.g.
> `"google-vertex/us-east5"` or `"deepinfra/turbo"`). […] Every endpoint slug has the shape
> `provider[/variant][/region][/tier]` […] **dropping the tier never matches a tier endpoint.**

So the request is pinned with `"only": ["deepinfra/fp8"], "allow_fallbacks": false` — no
`order`, no `sort`, no `models` array, no `:nitro`, no `:floor`, no `service_tier`.

**And here is the catch, which is why your answer to C5 and your answer in the add form pull
in opposite directions.** You said *show different tags as separate choices*, and the add form
*asks for provider names*. Names and tags are not the same thing:

| what is written | what OpenRouter does with it | is that a pin? |
|---|---|---|
| `deepinfra` | matches `deepinfra/fp8`, `deepinfra/fp8/flex`, `deepinfra/fp8/priority` — **whichever it likes** | **no. That is autorouting inside a provider.** |
| `deepinfra/fp8` | one endpoint: $0.0980 in / $0.2940 out per M | yes |
| `deepinfra/fp8/flex` | one endpoint: **$0.0784 / $0.2352** — cheaper than DeepSeek itself | yes |
| `deepinfra/fp8/priority` | one endpoint: $0.1470 / $0.4410 | yes — this is the one you asked to leave out |

A name is a base slug, and a base slug is a *group*. Writing a name would quietly undo the
thing you just demanded. So: **the add form asks for tags, and the file stores tags.** The
`deepinfra (not priority)` in the original draft then has an exact mechanical answer:
`deepinfra/fp8` is in the shipped file and `deepinfra/fp8/priority` is not. That leaves
`deepinfra/fp8` and `deepinfra/fp8/flex` — **and only one of them can be active.** ← question 1.

Two consequences worth stating, because they are the price of a pin:

- **A pinned provider that is down stops that session.** There is no fallback, by your order.
  The turn fails, the session goes quiet, and the hourly notification is how you find out.
  I propose **retrying the same endpoint** with backoff (3 tries) — that is not fallback, and
  it converts a ten-second blip from "a session you have to notice" into nothing.
- **`parallel_tool_calls` is false on all four endpoints**, and DeepSeek declines a forced
  `tool_choice` (`function: false`; `auto: true`). So the loop sends `tool_choice: "auto"` and
  must still handle two calls in one reply, because the contract allows it even where this
  endpoint does not do it. It also makes "wait must be alone" cheap to check.

### 2.2 `wait` alone does not fix the arithmetic — the number must be a delta

Your answer, *the agent receives a response for each call so he is informed*, is true and does
not touch the problem. The problem is what the number counts, not whether the agent is
confused.

The agent never pushes (`sync` is yours). So during one burst `unsentCount` does this:

```text
turn 1  edit milk.txt      unsent = 1
turn 2  wait               "1 changed"   → investigates, correctly
turn 3  edit milk.txt      unsent = 2
turn 4  wait               "2 changed"   → investigates
turn 5  edit milk.txt      unsent = 3
turn 6  wait               "3 changed"   → investigates
turn 7  wait               "3 changed"   → "unchanged", sleeps
```

Two edits in a burst and *every* wake after the first reports "changed". The total only ever
grows, so "if the amount changes it investigates" is true for the rest of the burst, and the
agent re-reads its own work once per edit. **The fix is one word: the wait returns the delta
since the previous `wait`** (`unsentCount − the count at the last wait`). It still needs no
baseline-side cleverness, and it makes "unchanged" mean what you meant: nobody else touched
the project.

Kept from your answer, exactly as you wrote it: **`wait` alone, or an immediate `1` with
"do not use wait with other tools"** and the other tools' results in the body. And the check is
free here, since these endpoints never emit two calls anyway.

### 2.3 `hard < the model's context` cannot be checked once, because a provider changes it by 6×

*Hard limit is a reserved option, much higher than the trigger, but below the max context
length of the selected model.* The intent is right and the comparison is against the wrong
quantity, twice:

**First, input and output share the window.** The limit is on the *context file*, which is
input. What must fit is input **plus** the reply, so the ceiling is
`context − maxOutput − the prompt layers − the tool schema` — not the context. For our one
model, pinned four different ways, on the assumption that input and output share the window
(the usual case):

| pinned to | context | its max output | input ceiling left | over the model's own figure |
|---|---|---|---|---|
| `deepseek` | 1,048,576 | 393,216 | **655,360** | — |
| `deepinfra/fp8` | 1,048,576 | 131,072 | 917,504 | — |
| `deepinfra/fp8/flex` | 1,048,576 | 131,072 | 917,504 | — |
| `decart/fp4` | 1,048,576 | 262,144 | 786,432 | — |
| `cloudflare` | 1,048,576 | **943,718** | **104,858** | — |

**The same model, the same file, and a safe limit of 655k becomes an unsafe 655k by swapping
one tag for another.** 6.2× smaller ceiling on Cloudflare. So the hard limit cannot be a
number stored once; it has to be re-derived whenever the model *or the tag* changes.

**Second, both limits are really about the window**, and the window is per model-and-tag. So:

> **Store both limits as fractions** — `trigger` 25%, `hard` 60% of `effective window`, where
> the effective window is `context − maxOutput − layers`. The settings screen shows the two
> absolute numbers for whatever is pinned ("40k / 96k of 655k") and lets the operator type
> absolutes instead, which are then converted back and re-checked on every model change.

That answers "below the model's context" without a rule that can go stale, and it answers
"much higher than the trigger" by construction. Reserved option: **`hard` is off (0) on a new
install**, and the compaction message is the only mechanism until the operator turns it on.

### 2.4 "Max resiliency" is three different promises, and Android allows parts of each

I need one word, because they are not the same service and they cost different things.

| grade | what survives | what it costs |
|---|---|---|
| **A. set and forget** | screen off, app swiped away (`START_STICKY`), reboot (boot receiver), low memory | a `foregroundServiceType`; on Android 15+ **`dataSync` is capped at 6 hours in any 24** — so "always on" would silently stop overnight; `specialUse` has no cap but needs a declared subtype and a lint exemption |
| **B. it comes back** | the above, plus: after a force-stop, on next launch | a persisted "should be on" flag and a resume on app start — the phone still has to be opened |
| **C. it must never stop** | also an aggressive OEM battery killer | a **partial wake lock held forever**: hours of screen-off CPU, and a flat battery by morning |

Some honest limits that no grade removes:

- **`POST_NOTIFICATIONS` is a runtime permission on API 33+ (this app targets 37).** Denied,
  there is no notification — so the service and the hourly notice both vanish *silently*. That
  must be a visible state in Settings ("notifications are off, so the agent cannot tell you
  anything"), not a debug session later.
- **Doze moves a 4-minute `wait`.** Without a wake lock, a wait that expires while the phone is
  asleep runs when the phone next wakes. I propose **no wake lock**: the agent is told the real
  elapsed time ("waited 4 min · actually 51 min"), and the loop is still correct, just later.
  Grade C buys exactness and pays for it in battery.
- **The boot receiver is not free either**: `dataSync` cannot start from `BOOT_COMPLETED` on
  recent Androids, so grade A + reboot forces `specialUse`.

→ question 2. My recommendation: **grade B with `specialUse`**, no wake lock.

### 2.5 "Agents are waiting" needs a definition, or the notification is noise

You replaced the cost cap with an hourly notification. Good — and it only works if "waiting"
means *waiting for you*. As it stands, an agent that is on and working sits in a `wait` most of
the time, so "notify what agents are waiting" would fire every hour, forever, for a healthy
system. Then it gets muted, and the one notification that mattered is muted with it.

**Propose: notify only on the three states that need a person.**

| state | in the notification | why it needs you |
|---|---|---|
| **asked** | "shopping: the agent asked something" + the `message` text, first line | it stopped and is waiting for your answer |
| **stopped** | "shopping: the pinned provider refused (503), 3 retries" | the pin has no fallback, by design |
| **off** | "shopping: the agent was turned off while a turn was open" | something ended a session you did not end |
| *(in a `wait`, nothing to do)* | — silently, as designed | — |

And on cost, since the cap is gone: **the idle loop is cheap, and that is why your answer is
defensible.** A wake every four minutes = 15 requests an hour; with a ~30k-token context that is
~$0.07/hour uncached and **~$0.0014/hour** on cached reads — which is the case here, since all
four pinned endpoints declare `supports_implicit_caching`. In money: roughly **3 cents a day to
keep an agent awake**. The risk was never the idle wake; it is a *stuck* agent doing work every
four minutes. So the only brake I would still add is free and is not a budget: **stop the
session after 12 consecutive turns with no `message` call**, and say so — that is a loop,
whatever it is spending.

One design rule falls out of this and belongs in the doc: **the beginning of the prompt must
never change** (system layer, then project layer, then context), because a prefix that changes
invalidates the cache and multiplies the idle cost by 50. Compaction rewrites the *middle*; it
must not touch the first two layers.

### 2.6 The box's ends: what the four buttons do at the first turn and the first burst

Your description settles everything except two edges, and the second one decides whether the
box is a viewer or a page.

- At turn 1, is `<` **disabled** or does it wrap to the newest?
- `»` jumps to the newest turn *of this burst*. **Is there any way back to the previous
  burst?** If not, the box is a one-way viewer and an older burst can only be read in the log by
  hand — which for a chat that renders *from* the log is a strange gap.

Propose: `<` disabled at the first turn (a wrap makes "turn 3 of 7" lie), `»` disabled at the
last, and **`»` at the last turn moves to the last turn of the previous burst** — labelled
"turn 4 of 4 · earlier burst". One button, no new control, and the whole history is reachable.
→ question 3.

### 2.7 The log *is* the transcript, which makes five columns a contract, not a record

*First four as captions, detail as the body — this data renders the chat.* That is the strongest
statement in your answers and it has three consequences I need to get right now, because they
are format decisions and formats are expensive to change later.

**a. A body cannot contain a newline.** The log is line-oriented: `detail` is "the rest of the
line". A paragraph is not a line. Options: escape newlines as `\n` (readable-ish, one rule); or
allow a continuation convention (a following line starting with a marker — more readable, one
more parser state); or forbid newlines (markdown collapses, every message becomes one long
line). **Propose `\n` escaped, one rule for every kind**, and `detail` is a JSON string so the
escaping is already written and standard: `"…line one\nline two…"`. One rule also means a
`GOT` body, which genuinely is JSON sometimes, needs no second convention.

**b. `name` becomes a caption a person reads, so it cannot be a slug.** The trace's vocabulary
is lowercase hyphen slugs, checked by `tools/check-taps.py`. A caption is "edited",
"read 3 files", "asked". So the session log gets **its own small caption table, checked by its
own check script** — the same discipline, a second list — and `CALL` names are the tool names,
which are already a closed set. If I skip this, the captions drift into "read file" / "Read
file" / "read 3 files" within a month, and the box looks broken.

**c. A half-written line must not break the box.** The app appends while the box may be reading.
So the reader **ignores a last line with no trailing newline** (it is still being written) and
**ignores a malformed line with a visible `GOT 1 line 18 could not be read`** rather than
dropping it silently — the rule the trace already lives by.

**d. And the thing your limit wording cannot cover: `session.log` has no token limit.**

You said *limits on tokens in those files, older get removed*. For `context.md` that is exactly
right. For `session.log` it cannot be: it is the transcript the box navigates, so trimming it
deletes the chat. And it grows fast — a wake every four minutes with a `wait` is ~360 log lines
a day, ~70 KB, which is nothing on disk, and grows without bound if you ever leave an agent on.

Resolution, and it is why §1.6 separates them: **the model's context is derived** — ① + ② + ③
+ the last N turns — so `session.log` can be append-only and complete while the *request* stays
bounded. The token limit is on `context.md` and on N, never on the transcript.

### 2.8 Effort "moves down" has no down when the new ladder is above it

*If the model doesn't declare the previously selected effort, it moves down to one that is
supported.* Fine for `max → high`. Not fine for `low → high`: a model whose ladder is
`["high","max"]` has nothing below `low`, so the value must move **up** to `high` — the thing
your parenthetical reserves for the operator. Proposal, one sentence:

> take the highest supported effort **at or below** the current one; if there is none, take the
> **lowest supported**. Say it in the log either way ("effort: low → high, this model's lowest").

Also worth knowing: this model's ladder is `["max","high","low","none"]` and **`none` is a real
position on it** — so the slider's left end is not "minimal effort", it is "no reasoning at
all". The label should say so, because a person sliding left for "cheap" gets a materially
different agent.

### 2.9 Two restores, one word

The vocabulary check will catch this in CI, but it is cheaper to decide now: **"restore model
config"** (a button) and **"restore version"** (a file's history) are different acts with the
same verb. And the agent's `restore` tool is the second one. Propose the button reads
**"Reset the model config"**, and `check-taps.py` gains one word, so a future "restore" cannot
mean two things.

### 2.10 What nobody has said yet: which project the agent reads *about*, and what it may read

*Within his project* is right and needs three exceptions written down, because the prompt layers
and the session file are not where they appear to be:

- **writing**: `agent/` and `GitHub/` only, minus locked paths, and never `.git/`, `.muffy/`, or
  the lock and pin files;
- **reading**: the above, **plus the three prompt files** — otherwise it cannot read the
  instructions it is given;
- **never**: another project's folder. Two projects, two agents, two diaries; an agent that can
  read `../shopping` is an agent whose "what did I do before" is somebody else's work.
  → question 4, because this one is a judgement call and not a consequence.

---

## 3. The tests — "prepared scenario tests", concretely

You said you did not follow the transport idea. Here it is without the jargon, because the
whole test plan rests on it.

**The app asks the model one question through one door.** `ask(messages, tools) -> reply`.
In the shipped app that door is an HTTPS call to OpenRouter. **In a test the door is a file**:
a script of what the model answers, in order. Nothing else changes — same loop, same tools,
same files, same commits.

`app/src/test/resources/scripts/an-edit-then-a-wait.txt`:

```text
--- 1
call tree  {"path": "GitHub", "depth": 2}
--- 2
call read  {"path": "GitHub/Milk.txt"}
--- 3
call edit  {"path": "GitHub/Milk.txt", "find": "two litres", "replace": "three litres"}
--- 4
call wait  {"minutes": 4}
--- 5
say        I changed the milk line, and nothing else touched the project.
```

Three things this buys, and one it does not:

- **the whole loop runs in CI**, with no key, no network, no money, and no waiting four minutes
  (the scripted `wait` returns at once);
- **it is deterministic**, so "the tenth turn is where it broke" is reproducible;
- **the same script is a scenario for you**: one scripted burst → one folder of pictures with
  prose, exactly like the tours you already have — a box with four turns, a box at ¾ height
  scrolling, the composer under a raised keyboard, a locked file refused, a `wait` returning a
  delta. `tools/scenario-report.sh` already assembles that report and already refuses to
  mention tests, classes or milliseconds;
- and it does **not** prove a real model behaves. That is the phone's job, and the trace log
  already records a turn as one greppable line, so the live log stays the instrument you have
  been using.

The cost of adopting this is one decision made once: the model call sits behind an interface
from the first commit, with a scripted implementation beside the real one — the shape
`DiskGitHub` already has, which is why the whole suite can run without a socket today.

---

## 4. What I need answered

Six. Everything else is settled enough to build on.

1. **DeepInfra, one tag**: `deepinfra/fp8` ($0.098/$0.294) · `deepinfra/fp8/flex`
   ($0.0784/$0.2352, cheaper than DeepSeek itself, a lower service tier) · both in the file,
   with one active. For the shipped default I would take **`/flex`** — the same tag question you
   answered for the picker, applied to the default.
2. **Resiliency grade**: A (set and forget, `dataSync`, dies at 6 hours) · **B (comes back,
   `specialUse`, no wake lock)** · C (wake lock, exact waits, flat battery).
3. **The box's ends**: `<` disabled at turn 1 (or wrap)? — and does `»` at the last turn step
   back to the previous burst (or is an older burst log-only)?
4. **May the agent read another project's folder?** I say no.
5. **The log's `detail` encoding**: `\n`-escaped JSON string for every kind (recommended) · a
   continuation-line marker · forbid newlines.
6. **Retries on a pinned provider**: 3 tries with backoff on 429/5xx (recommended — the same
   endpoint, so not a fallback) · none, fail immediately and notify.

---

## 5. Order of work, revised

1. **The lock, properly** (G6, G5): into the funnel, descendants covered, locked = read-only,
   a test that a lock refuses a write *and* a child write. A bug fix, and it must land first —
   everything after it inherits it.
2. **The config and the picker** (no agent yet): `model_conf.json`, the three forms, the add
   form's two-step fetch-and-validate, the live stats with `—` where the API is silent,
   fractions for the two limits. At the end of this wave the app knows exactly which pin its
   agent would use and why, and the chat button can say "not configured yet".
3. **The door and one turn**: the model key in the credential store, the model call behind an
   interface, the scripted implementation and the script format (§3), a chat screen that
   renders from `agent/session.log` with `message` and `read`/`tree` only. Every test offline.
4. **The writing tools**: `edit`, `create`, `delete`, `move`, `clone`, `history`, `restore` —
   all through the funnel, all commits with no push; the session log's caption table and its
   check; the three prompt layers; the limits and the compaction loop.
5. **Being on**: the service, `wait`'s delta, the "needs you" notification, the loop guard, the
   global switch. This is the only wave that needs a phone in a pocket to be proved.

Waves 1–4 are testable in CI and cost nothing to run. Wave 5 is where the money and the battery
are, which is why it is last.
