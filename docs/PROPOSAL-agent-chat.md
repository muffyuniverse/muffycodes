# The agent chat — counter-proposal, and the questions the draft leaves open

Status: **for discussion. Nothing is built.** This answers `docs/proposal-operator-10`.
Where I disagree I say why and give the number that made me disagree; where the draft is
silent I ask. Nothing here is a refusal — most of the mechanics map onto things the app
already does, and I say which ones.

Read §8 first if you want to answer only the decisions.

---

## 1. What I checked before writing this

Every number below was measured today, not remembered. The API calls are the two surfaces
an app can actually reach: `/api/v1/models` and `/api/v1/models/<id>/endpoints`.

| claim in the draft | what I found |
|---|---|
| `deepseek/deepseek-v4.1-flash-1017` | exists · created **2026-10-17** · context **1,048,576** · max output 393,216 (top provider's figure) · **no `expiration_date`** |
| providers `deepseek`, `deepinfra`, `decart`, `cloudflare` | all present, but **DeepInfra is three separate endpoints**: `deepinfra/fp8/flex` ($0.0784 in / $0.2352 out per M), `deepinfra/fp8` ($0.0980 / $0.2940), `deepinfra/fp8/priority` ($0.1470 / $0.4410). Decart is `decart/fp4`. DeepSeek is `deepseek`. The model has **34 endpoints under 29 names**. |
| "effort level, left is min available, right is max available" | the API declares it: `supported_efforts: ["max","high","low","none"]`, `default_effort: "high"`. It is **per model**, not global. |
| Intelligence Index | exists: `benchmarks.artificial_analysis.intelligence_index` = **39.5** for this model. `coding_index` and `agentic_index` are **null**. Across the catalogue only **286 of 467** models have an index at all. |
| terminal-bench 4.0 | **does not exist anywhere in the API.** The only benchmark families are `artificial_analysis` and `design_arena` (arena elo). |
| provider "tps", latency | exist as percentile objects and are often **null**: `{"p50":94,"p75":64,"p90":24,"p99":6}` for `throughput_last_30m`. Take `.p50`. |
| provider "input, output, cache price" | all exist, per endpoint, including `input_cache_read`. |
| provider "cache hit rate", "token share" | **do not exist in either surface.** What exists is `supports_implicit_caching` (a boolean) and, per workload, `request_count`. |
| "max input length" per model | `max_prompt_tokens` is **null on all six endpoints of this model**. |

And the app, from the code:

| what the draft assumes | what is true today |
|---|---|
| a place to keep a model key | **there is none.** `CredentialStore` holds one encrypted token per GitHub login. §11's guarantees are all about that key. |
| "agent is always on" | no `<service>` in the manifest, no WorkManager, nothing that survives the screen. `minSdk 26`, `targetSdk 37`. |
| "lock should still be enforced" | `isLocked` lives in `AppViewModel` and is asked at four *call sites in the UI*; `Workspace.write`, `delete`, `move` never ask. It also compares the **exact path**, so a locked folder does not protect its children. |
| "an editable file at account root" | `files/accounts/<login>/` is app-private storage. On `targetSdk 37` **no file manager on the phone can see it**. |
| an HTTP stack for chat | `HttpURLConnection` + `org.json`, nothing else. No OkHttp, no serialization library, **no YAML parser**. |
| history/restore tools | already built and better than the draft asks: `ProjectGit.versions / paths / bytesAt / restore`, and restore is an ordinary commit. |
| "each edit means save, sync is for operator" | already the app's rule: every change commits at once, `unsentCount` says what GitHub has not seen. |
| tests | the whole suite runs against `DiskGitHub` and **never opens a socket**. |

---

## 2. What I agree with, without argument

- **A session per project**, opened from inside the project, with the project named in the
  chat's top bar. It is the only shape that matches §2: a project is a folder, not a chat.
- **The agent's words and the app's story in one place.** The chat's turns go into the same
  trace log (§G1) — the operator reads that file, and an agent whose work is invisible in it
  is an agent nobody can audit.
- **Save, not sync.** `edit` → commit → `unsentCount + 1`; the push stays the person's act.
  This is already how the editor behaves and it needs no new mechanism.
- **A tool result for every call, always, with a status.** A silent tool is a hung agent.
- **No reasoning traces.** `include_reasoning` is a supported parameter, and excluding it
  keeps the visible context clean.
- **`wait`, then a count, then investigate if it moved.** That is the right loop for a
  background worker: it costs one number per four minutes instead of a token stream.
- **The `reason` command.** Reasoning that the person can read and that survives a reset is
  worth more than reasoning the API hands back once.
- **Files the person can edit and lock.** The agent's diary is the steering wheel; the fact
  that the draft wants it as a file rather than a hidden database is the best idea in it.
- **`tree`, `read`, `edit`, `create`/`delete`/`move`/`clone`, `history`, `restore`.** These
  map one-to-one onto functions that already exist (`Workspace.list/read/write/copy/delete`,
  `ProjectGit.versions/paths/bytesAt/restore`), which is a good sign for the design.

---

## 3. Contradictions — things that cannot all be true at once

### C1. Three settings fields **and** a file that holds "active effort level"

The draft asks for a model field, an effort field and a provider field in Settings, *and* a
`model_conf` file at the account root that holds the active effort. That is two stores for
the same three values, and the second one is hand-editable — so they will disagree the first
time somebody edits the file.

**The call I would make: the file is the only store. The Settings fields are editors of it.**
Pressing "Select" in the picker writes the `active:` block of the file; the Settings row shows
what the file says. One truth, no reconciliation code.

Three consequences worth stating out loud:

- **The choice is per account**, because the file is. Switch account and the model changes.
  I think that is right — the model is the agent that works in *that* account's projects,
  and it is the same reasoning as "the token is per account".
- **Every config edit becomes a commit in the account's history** (§4 of the main design), so
  a bad edit is undoable with the arrows. That is a feature, and it is free.
- **`model_conf` must never be rewritten wholesale by the app**, or the person's comments and
  ordering die on first save. The app owns exactly one block, marked by lines it wrote itself
  (§6). Everything else is the person's text, read-only to the app.

### C2. "An editable file at account root" is not editable on Android

The account root is app-private. Nothing on the phone can open it — not a file manager, not
Downloads, not the operator's editor of choice. So "users then can add or remove models" has
no door. Three possible doors:

| door | what it costs |
|---|---|
| **a Settings button that opens the file in the app's own editor** | cheapest, and the editor already exists: it locks, it commits, it is undoable. It also means the file gets a history. |
| export to Downloads and re-import | two more steps and a merge problem nobody wants |
| show it in the file tree | it is config, not a document, and §3 says the tree is what you made |

**Recommended: the app's editor, from Settings.** "Edit model_conf" and "Reset to the
catalogue". The file does not appear in the tree. If you want it in Downloads too, that is a
separate copy step and a second truth — I would not.

### C3. YAML, in an app with no YAML parser

There is no YAML library in this project, and a hand-rolled YAML is the classic trap:
quoting, anchors, multi-line scalars, flow style, and a person's innocent `- id: x` indented
by three spaces. Three ways out:

| way | what it costs |
|---|---|
| **a declared subset, checked by a test** | maps, lists of maps, scalars, `#` comments. No anchors, no flow style, no multi-line strings, no tabs. Anything outside the subset is refused **with the line number** and the app keeps the last good config. The app ships a file in this subset, so the shape is demonstrated, not just documented. |
| JSON | zero dependencies (`org.json` is already there) and guaranteed correct — but a config people edit by hand really wants comments, and JSON has none |
| add SnakeYAML / Kaml | a real parser, but new weight, new upgrade surface, and a new failure mode on a phone build |

**Recommended: the subset**, with the refusal message being the important part — "line 14:
`active` must be a mapping, keeping the file from before". A config parser that silently
falls back to defaults is a config parser that eats the person's work.

### C4. Two of the six provider stats do not exist

"Cache hit rate" and "token share" are not in either OpenRouter surface. The draft is asking
the app to display numbers it cannot fetch. Options:

| option | what it shows |
|---|---|
| **ours, labelled as ours** | cache hit rate = our own responses' `prompt_tokens_details.cached_tokens / prompt_tokens` for that provider; token share = our tokens through that provider ÷ all providers. Empty (as `—`) until used. |
| drop them | the row has four honest numbers instead of six, two of which are `—` |
| fetch a third source | nobody has published one we can call |

**Recommended: ours**, with a caption on the row saying these two are *this phone's* history,
not the world's. It is also the only version that needs no network: "updated on entering the
selection menu" then means four numbers refreshed and two numbers read from disk.

The same test applies to **terminal-bench**: it does not exist in the API. Either drop it, or
put a small frozen table of scores for the models in the catalogue inside the app with the
date it was taken. I would drop it — a stale number that looks live is worse than no number.

And for the three that *do* exist but are often null (`intelligence_index` 39% of the
catalogue, `max_prompt_tokens`, `latency`/`throughput` on a quiet provider): **`—`, never 0,
never invented.** That is the "silent failure is two bugs" rule applied to a stats row.

### C5. "A provider" is not one thing — and "(not priority)" is the tell

The draft lists `deepseek, deepinfra (not priority), decart, cloudflare`. That parenthesis is
the whole problem: DeepInfra is three endpoints at three prices, and one of them is literally
called `/priority`. So the identity of a provider entry is the **tag** (`deepinfra/fp8`,
`decart/fp4`, `deepseek`, `cloudflare`), not the name — the name is what a person reads.

What the picker should do with three DeepInfra rows: **show three rows**. Each has a different
price and the same name; hiding two of them would be picking for the operator without saying
so. Then "(not priority)" has an exact answer: `deepinfra/fp8/priority` is not in the file.
But `deepinfra/fp8` and `deepinfra/fp8/flex` remain, and **`/flex` is cheaper than DeepSeek
itself** ($0.0784 vs $0.1500 per M input). Which one did you mean?

Also: the model-level context and max-output are the *top provider's* figures. Cloudflare
advertises 943,718 output tokens against the model's 393,216. So the numbers shown must be
**the provider's** once a provider is chosen, and the model's only as a summary — otherwise
the picker promises a limit the chosen provider will refuse.

### C6. "Set active effort level" meets an effort ladder that varies by model

`supported_efforts` is per model. So a file that says `effort: max` is a *request*, not a
setting: pick a model with only `low`/`none` and the value has to be clamped. What should
happen, and should the app say it?

**Recommended:** clamp to the nearest supported value, write it back to the file, and say so
in the log ("effort: max → high, this model's highest") — because a silent clamp is a
decision the person made that the app quietly overruled. The picker's slider is built from
`supported_efforts`, so "left is min available, right is max available" is exactly what the
API says rather than a ladder we invented.

### C7. "Always on" needs a service, and the draft does not mention one

An app process that is not in the foreground gets killed. A four-minute wait inside an
Activity is a four-minute wait that will not finish the first time the screen goes off.

| shape | what it gives you | what it costs |
|---|---|---|
| **foreground service, permanent notification** | what the draft asks for: turns happen while the phone is in a pocket | a notification whenever any session is on; a `foregroundServiceType` declaration; battery; a stop button that must actually stop |
| only while the app is open | the simplest thing that works, zero battery | "always on" becomes "on while you are looking" — and the `wait` becomes pointless, because you would look anyway |
| WorkManager wake-ups | survives death | a 15-minute floor, which cannot express a 4-minute wait, and jitter that makes "if it changed, investigate" arrive late |

**Recommended: the foreground service**, because it is the feature being asked for, and the
notification is the honest price. The global switch in Settings then *is* the service's
switch, and its "N sessions active" count is the notification's subtitle. If you would rather
not have a notification at all, then the right answer is the second row — and the `wait` tool
should be described as "check again in four minutes if you are still looking".

### C8. "User message interrupts and is sent immediately, tool results come on second turn"

The second half is exactly right and the first half is not expressible. An assistant message
that carries `tool_calls` must be followed by a `tool` message for each call before any new
`user` message, or OpenAI-compatible providers reject the request. So a user message cannot be
sent *immediately* mid-turn. What can happen immediately:

1. the in-flight request is cancelled;
2. the tool that was running gets its result anyway (`1 interrupted`) — a tool call with no
   result is an illegal sequence, and worse, an unanswered call in the session file;
3. the next request carries the tool result **and** the user's message, in that order.

That is the draft's own "tool results come on second turn", so the mechanism stands; the words
need to be "the turn is cut short immediately", not "the message is sent immediately".

### C9. Two mechanisms delete the same text

"Limits on amount of tokens in those files (older get removed after hitting the limit)" and
"compaction trigger … agent gets a system message … every turn until the file is below the
threshold" are both about the same file and both remove text. If both are live, the trimming
happens before the agent ever gets the chance to compact — and the agent's judgement is the
one you actually asked for.

**Recommended order, stated in the file's own header:** over the *trigger* → the system
message, every turn, as drafted. Over the *hard limit* → the app trims oldest-first and says
so in the log. Never silently, and never before the agent has had a turn to fix it.

### C10. "Each tool call has to have a response with 0 on empty success and 1 on empty failure"

This defines the empty case only, and a model cannot be asked to follow one convention for
empty answers and a different one for full ones. Make it one rule:

> **The first line of every tool result is `0` or `1`. The body follows and may be empty.**

Then "empty success" is `0` with nothing after it, and there is no second case to get wrong.
Two sub-questions the draft raises by omitting them: what is the status when the answer was
**truncated** by the response-length limit (I would say `1`, with the body and "showing first
N of M" — an error with no body spends a whole turn teaching nothing), and what is the status
for a **cancelled** tool (`1`, "interrupted by you").

---

## 4. Gaps — where the draft says nothing and something has to be decided

### G1. **The model key has no home.** (the largest gap)

The chat cannot make a single request without an OpenRouter key, and the app has exactly one
key today: the GitHub token, encrypted, per login, never in a URL, never screenshotted,
scrubbed from messages, kept out of backups. The draft's files are all about *models*; none of
them is about the key.

**Proposed:** a second secret in the same store, behind the same door — typed in a screen that
refuses screenshots, encrypted with the same Keystore key, scrubbed from every message and
exception, excluded from backups, and **one per phone rather than one per account** (the model
is a paid subscription of the person, not of a GitHub account). Chat with no key says so and
offers the screen, instead of failing with a 401 eight seconds later.

### G2. What the chat shows when a turn is in flight, and what happens if the app dies mid-turn

On restart, a session may contain a `tool_call` with no result — an illegal sequence, and a
request that will be rejected forever. **Proposed:** on open, any dangling call is sealed with
`1 the app was closed before this answered`, appended once, and that is the end of it.

### G3. Cost is uncapped

A loop that wakes every four minutes, reads files and reasons is a token furnace with no
brake: the operator's own default is `effort: high` on a model chosen for being cheap, which
is exactly the combination that gets left running overnight. **Proposed:** a daily cap in
Settings (in tokens or money) with the same counter idiom as everything else — it increments
per turn and the session stops with a message when it is reached. And a line in the doc:
excluding reasoning traces **saves context, not tokens** — the model still generates and bills
them.

### G4. `wait` can wake itself

"On end of wait the agent receives amount of changes in the project; if the amount changes it
investigates." The agent's own edits are changes. If a commit for an edit the agent made lands
*after* the wait begins (the app commits on a worker), the wait returns "1 changed" and the
agent investigates its own work — forever, once per edit. **Proposed:** the wait's baseline is
taken after the app's own writes have settled (the `Trace.awaitWrites()` idiom, already used
for exactly this reason), and the returned number is "changes since you last looked", computed
from the same `unsentCount` the row shows, so it survives the history prune.

### G5. What the agent may touch, and what it may never touch

The draft says "within his project", which is right, and does not say the exceptions. Against
the code today, `Workspace.write` checks nothing at all — no lock, no hidden name, no
structural folder. So an edit tool built on it could write `.muffy/locked.json`, or a lock
file, or `.git/`. **Proposed three hard refusals**, in one place: `.muffy/` and `.git/` and
the lock/pin files are never writable by anyone but the app; the agent may not edit a locked
path (see G6); and the agent may not delete the folder its own session lives in.

### G6. The lock is a rail, not a wall — fix it before the agent exists

Two holes, both real today and both made worse by an agent:

- enforcement is at four UI call sites, not in the funnel — so any new caller (the agent, a
  future importer, a test) bypasses it;
- a lock covers one exact path, so **a locked folder does not protect its children** — the
  person can edit a file inside a locked folder right now.

**Proposed:** move the check into `Workspace.touchedProblem` *and* `Workspace.write`, and make
a lock cover descendants (`path == locked`, or `path` starts with `locked + "/"`). One funnel,
so "the agent honours locks" needs no agent code at all. This is a bug fix, not a feature, and
it should land before the chat does.

### G7. Where the session and the working context actually live

"Both are kept in project folder as editable files." §3 of the main design says everything
the app makes is hidden, and the app made these. But a diary the person cannot read is not the
diary the draft describes.

**Proposed:** `<project>/agent/` — `session.log` and `context.md` — **visible**, because they
are the steering wheel, and **outside `repo/`**, so they are versioned by the account's local
history and never pushed to GitHub. That also makes the lock meaningful and the "reset
context" undoable. Whether `system_prompt` sits at the account root (draft) or beside the
project's agent folder is a question: one prompt for every project, or a default with a
per-project override?

**And the session file's shape:** I would use the same five fixed columns as the trace
(`time id kind name detail`, with `kind` ∈ TOLD/SAID/CALL/GOT/NOTE) rather than JSON, for one
reason: you read the trace, so you can read this, and the app gets a parser it already trusts.
The cost is that a hand-edited `CALL` line must still carry valid JSON in `detail`, and a line
that does not is refused with its line number — the same rule as C3.

### G8. Tests, or the loop is only testable with money

The suite never opens a socket. If the model client is a concrete HTTP call, then none of
this — the loop, the tools, the compaction, the limits, the status protocol — can be tested in
CI, and the only way to find a bug is to pay for a turn and watch a phone. **Proposed: one
interface from the first commit** (`ModelTransport`, with `DiskModel` for tests and the real
client behind it), exactly the shape `DiskGitHub` already has. This is the single most
important structural decision in the whole feature, and it is cheap if it is made first.

Two smaller gaps in the same family: every new tap needs a word in `tools/check-taps.py`
(the check fails otherwise, which is the point), and the trace needs to record a turn as
something greppable, so the live log stays the instrument you have been using all session.

### G9. "By default we use openrouter router catalogue" vs a provider field

If the app always sends a `provider.order` pin, OpenRouter's own routing and fallback stop
being used; if it never sends one, the provider setting is decoration. **Proposed:** no pin
when no provider is named in the file (that *is* "the router catalogue"), an explicit pin when
one is named (`provider.order` + `allow_fallbacks: false`, so a pinned-but-down provider fails
loudly instead of silently going somewhere else). Which of the two is the default you want?

---

## 5. The shape I would build

```text
files/accounts/<login>/
  model_conf.yaml              hand-edited catalogue + the app-owned `active:` block
  system_prompt.txt            fed at startup and on every context reset
  projects/<project>/
    agent/
      session.log              the conversation, five fixed columns, same shape as the trace
      context.md               the working context the agent may edit (the compaction target)
    repo/                      everything GitHub sees; the agent's edits are commits here
```

The loop, in words:

1. **A turn** = read `session.log` + `context.md` → build messages → one request → the reply
   is either tool calls or a message.
2. **Every tool call gets its result**, first line `0` or `1`, always. The result is appended
   to `session.log` as a `GOT` line before the next request is built — so a crash cannot lose
   a pair that a request already referenced.
3. **A tool that writes** (`edit`, `create`, `delete`, `move`, `clone`, `restore`) goes through
   the same `Workspace` funnel as the screen, so locks and the hidden-name rule apply to the
   agent by construction (G6), and commits at once — `unsentCount + 1`, no push.
4. **A `wait`** sleeps, then returns one number: changes not made by the agent, since it last
   looked (G4). Zero means sleep again. Non-zero means investigate — with `tree` and `read`,
   not with a guess.
5. **A user message** cuts the turn short (C8) and is delivered with the pending tool result.
6. **Over the compaction trigger** the system messages say "compact" every turn until the file
   is under it; over the hard limit the app trims and says so (C9).

What I would **not** build: a second settings store, a second log format, a per-agent copy of
the lock logic, or a background mechanism that does not show itself in the notification bar.

---

## 6. The file, as I would ship it

Real values, measured today. `active:` is the only block the app writes; the comment above it
is written by the app's writer, and it rewrites nothing between those two markers.

```yaml
# model_conf.yaml — the models the agent may use, and the one it uses now.
# Edited by hand. The app reads all of it and rewrites only the block below
# the "written by the app" marker, so your comments and ordering are safe.
#
# Shape (a fixed subset of YAML: maps, lists, plain values, # comments —
# no anchors, no inline braces, no multi-line strings, no tabs):
#   active:   model, provider, effort
#   models:   a list; each entry has an id and the providers it may use

# ---- written by the app -------------------------------------------------
active:
  model: deepseek/deepseek-v4.1-flash-1017
  provider: deepseek
  effort: high
# ---- end of the block written by the app --------------------------------

models:
  - id: deepseek/deepseek-v4.1-flash-1017
    providers:
      # the provider's own service, the cheapest DeepInfra tier, Decart, Cloudflare.
      # leave /priority and /flex out unless you want to pay for a tier.
      - deepseek
      - deepinfra/fp8
      - decart/fp4
      - cloudflare
```

What the picker shows for that model, from today's data — and note which columns are `—`:

| | value | source |
|---|---|---|
| input / output modalities | text+image → text | `architecture` |
| released | 2026-10-17 | `created` |
| context | 1,048,576 | `context_length` |
| max input | `—` | `max_prompt_tokens` is null |
| max output | 393,216 (provider's figure when a provider is chosen) | `max_completion_tokens` |
| Intelligence Index | 39.5 (coding / agentic: `—`) | `benchmarks.artificial_analysis` |
| terminal-bench 4.0 | `—` — not in the API | — |

| provider (tag) | in /M | out /M | cache /M | tps (p50) | cache hit | share |
|---|---|---|---|---|---|---|
| `deepinfra/fp8/flex` | $0.0784 | $0.2352 | $0.0024 | `—` | `—` | `—` |
| `deepinfra/fp8` | $0.0980 | $0.2940 | $0.0029 | `—` | ours | ours |
| `decart/fp4` | $0.1365 | $0.5460 | $0.0027 | `—` | ours | ours |
| `cloudflare` | $0.1000 | $0.6000 | $0.0030 | `—` | ours | ours |
| `deepseek` | $0.1500 | $0.6000 | $0.0030 | `—` | ours | ours |

`tps` was null for all of them when I looked (the model is ten days old and the throughput
feed had no data on that endpoint). `—` is the honest answer; a 0 would read as "stopped".

---

## 7. Order of work

Four waves, each of which is useful and testable on its own. Nothing in wave 1 needs a key,
a network, or a service, which is why it goes first.

1. **The lock funnel and the config** — G6 (lock in the funnel, a lock covers descendants),
   then `model_conf.yaml` + the subset parser + the picker with live stats (C1–C6, G9).
   At this point the app can *say* which model the agent would use, with no agent.
2. **The client and one turn** — the key (G1), `ModelTransport` + `DiskModel` (G8), a chat
   screen, `message` and `read`/`tree` only. The first end-to-end turn, and every test offline.
3. **The writing tools and history** — `edit`, `create`, `delete`, `move`, `clone`, `history`,
   `restore`, all through the existing funnel, commits with no push. Sessions and working
   context on disk (G7), and the trace recording turns (G1 of §4).
4. **Always on** — the foreground service, `wait`, the global switch, the caps (G3), the
   compaction loop and the hard limit (C9).

That order is deliberate: everything that can be settled without money and a pocketed phone is
settled first, so the expensive parts are built on something already proven.

---

## 8. The decisions I need

Answer with the letter; "1b" is enough.

1. **Where the model choice lives** — (a) the file only, Settings edits it *(recommended)* ·
   (b) Settings only, the file is the catalogue · (c) both, kept in step.
2. **How the file is reached** — (a) a Settings button that opens the app's editor
   *(recommended)* · (b) a copy in Downloads, re-imported · (c) shown in the file tree.
3. **The config format** — (a) a declared YAML subset with line-numbered refusals
   *(recommended)* · (b) JSON · (c) a real YAML library.
4. **The two stats that do not exist** — (a) our own, labelled as ours *(recommended)* ·
   (b) drop them · (c) another source, named by you.
5. **terminal-bench** — (a) drop it *(recommended)* · (b) a frozen table in the app, dated ·
   (c) a source, named by you.
6. **DeepInfra** — `deepinfra/fp8` *(recommended, the middle tier)* · `deepinfra/fp8/flex`
   (cheapest, $0.0784/M in) · both · neither.
7. **Provider identity in the file** — (a) tags (`deepinfra/fp8`) *(recommended)* · (b) names,
   and the picker settles the tier when several exist.
8. **A model whose ladder lacks the active effort** — (a) clamp, write it back, say so in the
   log *(recommended)* · (b) refuse the model change until the effort is chosen.
9. **Always on** — (a) foreground service with a permanent notification *(recommended)* ·
   (b) only while the app is open, and drop `wait` · (c) WorkManager.
10. **The lock** — (a) move it into the funnel and make a folder's lock cover its children
    *(recommended, and it is a bug fix)* · (b) leave it in the UI and give the agent its own
    copy of the check.
11. **The session file's shape** — (a) the trace's five columns *(recommended)* · (b) JSON
    lines · (c) plain prose, unparseable.
12. **The agent's own changes in `wait`** — (a) excluded, baseline after writes settle
    *(recommended)* · (b) included, and the agent learns to ignore its own noise.
13. **The caps** — (a) a daily token cap in Settings *(recommended)* · (b) none, trust the
    person · (c) a per-session turn cap instead.
14. **Where `system_prompt` lives** — (a) account root, one for every project *(as drafted;
    recommended)* · (b) per project, with the account's as the default.
15. **The model key** — (a) one per phone, in the same encrypted store as the GitHub token
    *(recommended)* · (b) one per account · (c) typed per session.
