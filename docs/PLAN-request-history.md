# The request history, and who owns which number

Status: **agreed 2026-10-28**, partly built — see §7. This file records what was accepted and nothing else. Where a
proposal was dropped, it says so and why — a plan that silently keeps a rejected idea is how a
defect gets built twice.

---

## 1. What the stats table is

> **REVISED 2026-10-28, by the operator, superseding everything below this box:** the table is
> **two pickers (start and end) and ONE column** — the results for the selected interval, at
> **per-hour granularity**. **No checkboxes, and no five columns.**
>
> The operator's words: *"I think I was dumb with insisting on one picker — we need 2 pickers for
> starting and ending, and just 1 result column for the selected interval with per hour granularity.
> and no checkboxes."*

### The table, as revised

| | |
|---|---|
| **start** | a date and a time, picked by the first picker |
| **end** | a date and a time, picked by the second picker |
| **column** | **one**. Every number in §1a, summed over `[start, end]` |
| **granularity** | **per hour** — the interval is measured in hours, not in days/months/years |

So the questions "how many requests", "what did it cost", "what was the cache share" have one
answer each, over one interval. Choosing 00:00 on 1 October to 23:00 on 31 October is a month of
requests; choosing 09:00 to 17:00 on one day is a workday.

**What this deletes:** the five nested spans (hour/day/month/year/total), the anchor, the checkbox
row, and the `Span`/`Container` machinery that existed only to express "one instant seen five
ways". A column labelled with its interval replaces all of it.

**What survives from the sections below**, because it is about the log rather than the table: the
pyramid (§2), `span`/`requests`/`cost reported` (§3), and who owns which number (§4). The hours are
still what the log keeps, which is exactly what a per-hour interval reads.

### The rejected history, kept so it is not rebuilt

Four shapes were proposed and built before this one, and every one of them was an attempt to ask
several questions of one instant:

| rejected | why |
|---|---|
| rolling window ("the last 24 hours") | a window that moves; cannot show October by itself |
| one axis at a time | the person chooses a granularity before seeing anything |
| a picker per axis | switching axis changed *when* was being asked about |
| **five columns from one pick** | one instant does not describe an interval. Reading 2025 means picking 23:00 on 31 December and trusting four nested cut-offs to describe a year — when the honest way to say "2025" is to pick its first and last hour |
| checkboxes for which columns to draw | there is no longer more than one column |
| three fixed columns (hour/day/month) | the first build; had the same fault at a smaller scale |

**The lesson worth keeping:** every rejected design made the *instant* carry the meaning, and each
needed a rule to decide where a column started and stopped. Two pickers remove the question — the
person states the interval, which is the thing they actually know.

---

## 1a. The rows — unchanged by the revision

The operator's list (L28419). These are the metrics, and they are now the **rows of a single
column**:

| row | what it is |
|---|---|
| `requests` | count |
| `input` | absolute |
| `output` | absolute |
| `cache` | **absolute + share in one cell** — `24.0k (91.7%)` |
| `cost $` | sum, the usage itself |
| `per M in` | cost per million prompt tokens |
| `per M out` | cost per million completion tokens |
| `per request` | cost per request |

- **Per million, not per billion**, agreed at L29550 after a revert (*"I later reverted to per
  million for every token ratio"*). An earlier note calling "per billion built as per million" a
  defect was wrong.
- **Cache is a share of input, not a row beside it** — `cached_tokens` is a subset of
  `prompt_tokens`, so a third absolute would count the same tokens twice.
- **Precision**: percentages to **three** decimals where it makes sense (L28419, L28438), cost and
  the two rates to **five**. `StatsFormat.share` renders three and the rest render five, both
  pinned in `RequestStatsTest` — the format had no test at all, which is how it stayed at one
  decimal while this line said three.
- **`—` is not zero**: a span with no requests has no average, which is a different fact from an
  average of zero.

---

## 2. The log keeps every nonempty hour, forever

The pyramid. **Coarser levels are an addition, never a replacement for the hour rows.**

| level | written for | kept | derived from |
|---|---|---|---|
| raw requests | the open hour only | deleted once rolled up | — |
| `hour` | **every nonempty hour** | **forever** | the raw rows (it *is* the record) |
| `day` | every complete day | forever | hour rows |
| `month` | every complete month | forever | day rows |
| `year` | every complete year | forever | month rows |

**There is no `total` level, and there must not be.** The total column (§1) is a *sum of year rows*,
computed when the table is drawn. A stored total row would be a second, contradictory fact about the
same money the moment any hour is re-rolled — the local-time defect with a currency sign. Total is a
column, never a row.

Decided:

- **Hour aggregates only for nonempty hours.** No marker rows, no "write one at midnight": the raw
  rows are themselves the list of nonempty hours, so a closed hour exists iff it has rows.
- **`requestDays` bounds the history** and already defaults to `0` = keep everything. With the
  pyramid that is ~1.37 MB/year (8,760 hour rows at 150 bytes) against ~420 MB/year of raw at
  10k requests/day, so **keep everything stays the default**. One knob, not two: raw retention is
  internal and fixed (the open hour).

The invariant that makes it correct, and the one a test must pin:

> **An aggregate row exists only for a CLOSED unit.** A closed day is complete by definition, so it
> can be summed; an open day cannot, so the read descends into the level below.

Read cost for one pick, worst case: ≤11 month rows + ≤30 day rows + ≤23 hour rows + the raw rows of
the open hour. The 10k raw rows are touched only for the open hour (~416), never for the month.

The `total` column reads every `year` row — one row per year, so a decade is ten rows. Its open chain
is the same one the month and year columns share, computed once per draw rather than three times.

### The hazard to write down

**Never sum across two levels of different sizes.** A day row *and* its hours would double count. The
read must produce a **disjoint decomposition**: coarsest complete units that partition the span, plus
exactly one descending partial chain. The arithmetic will happily produce a plausible wrong number.

### Why keeping hours is what makes it safe

The window semantics in §1 were corrected *twice* during this work. If aggregates replaced the hour
rows, every historical row would be frozen under an abandoned rule and would need a migration. Because
hour rows survive, `day`/`month`/`year` can be **rebuilt from them**. The pyramid is a cache with a
declared derivation, so the derivation can change.

---

## 3. Schema

### `requests.log` gains (in front of the existing columns)

| column | meaning |
|---|---|
| `span` | `raw` · `hour` · `day` · `month` · `year` |
| `requests` | how many requests this row covers. **1 for `raw`** |
| `cost reported` | how many responses in it actually reported a cost |

### `cost reported` is a count, and `cost` is a sum

`Stats.cost` is a sum; `costReported` is the **denominator** for the average:

```kotlin
val perRequest = if (costReported == 0) null else cost / costReported
```

Summing costs and dividing by `requests` averages reported costs over rows that never reported one,
and returns a plausible lie. It also collapses the **dash vs zero** distinction: `costReported == 0`
is "nothing reported" (`—`), `$0.00` is "reported as free". `Stats` already sums both correctly; the
file needs both columns so a rollup row can carry them.

Together with `span` and `requests` these are the **only** new columns. Without `requests` every
rollup's `per request` is silently wrong; without `cost reported` the average has no denominator.

`utc` changes meaning for aggregate rows: not "a request happened then" but **"this span begins
then"**. Every comment and test asserting the old meaning moves.

### Aggregate by UTC hour, not local

Two local hours are both `01:00` on the clock-back day, and compaction is destructive, so the shape
would be frozen at compaction time. Store instants; derive local at draw time — the same rule already
set for the log.

---

## 4. Who owns which number

Two files, two questions, no third join key.

| file | owns | aggregated? |
|---|---|---|
| `requests.log` | **money** — what a span cost | yes, freely |
| `session.log` | **identity** — which bubble, what it said | never |

`session.log` gains **two columns**, `output` and `thinking`, written when the response arrives. This
keeps the one-to-one that matters: **one model request produces exactly one `CALL` or `agent` row**,
so the numbers land on a bubble without a join.

Decided, and why the session log is the right home:

- The bubble footer must **reconstruct on reopening the chat**, from a file, after the process is
  gone. Live view-model state cannot do that.
- `AgentLog.Row.size` is already a per-row token count and is already drawn (`Line.tokens`), so the
  per-bubble context size needs **no new column**. But `size` is what a row costs *in context*, not
  what the model **billed** — a budget trim re-sends the context, so the same row is billed several
  times while existing once. That is why `output`/`thinking` are separate columns rather than reused
  from `size`.
- **A stall has no session row.** Its output was billed and its accounting never arrived;
  `requests.log` records `said`/`thinking` for exactly this case. It stays there.

Dropped: a `session` column in `requests.log`. It was proposed and then dropped in favour of the two
session-log columns, because the footer case is the live defect and it needs no join.

**The one cost, named:** the billed figure then exists in two files. Difference from the local-time
defect we deleted — local time was *derivable*, so it could contradict itself; a billed cost arrives
once from the provider and has two consumers. The rule:

> `requests.log` is authoritative for money. `session.log` is authoritative for which bubble.
> If they disagree, the request log wins and the session row is wrong.

`forget()` only flips rows to `OUT`, never deletes them, so forgetting a bubble keeps its money
attached.

---

## 5. Order of work

> **SUPERSEDED 2026-10-28 by §1 as revised.** Steps 4 and 5 below describe the five-column design and
> **must not be built**: there are no five spans, no anchor, and no checkboxes. They are kept only so
> the reasoning is not lost, and because steps 1–3 and 6 still stand. What actually happened is in §7.

Each step is independently shippable and testable.

1. ~~**Stream the log and read it off the main thread.**~~ **Done.** `readLines()` materialised the
   whole file as strings *and* one object per row, inside `remember`, on the main thread. At 10k/day
   that is ~50 MB peak at 30 days and ~600 MB at a year. Streaming was also a **prerequisite for the
   compactor**, which reads the same file.
2. ~~**Add `span`, `requests`, `cost reported`**~~ **Done**, appended rather than prepended.
3. ~~**Compactor**, on the existing session tick, off the UI thread~~ **Done**, plus `prune` moved
   into it (it was re-reading the whole log on every write).
4. **Reader: the five spans (§1) as a disjoint decomposition** — **NOT DOING.** There are no five
   spans. The disjointness rule survives because it is about *the log*, not the table: the coarsest
   stored level speaks for its unit, so a rebuilt month is not added to the hours beneath it. The
   table asks one interval of that structure, not five.
5. **Checkboxes right of the date picker** — **NOT DOING.** There is one column, so there is nothing
   to check.
6. ~~**The picker's four granularities.**~~ **NOT NEEDED, decided by the operator 2026-10-28.**
   The requirement came from the per-axis design, where the picker's granularity *was* the question.
   With two pickers the question is an interval, and "October" is expressible as its first and last
   hour, so a month-only or year-only mode would be convenience rather than a requirement — and
   convenience that adds a second way to say the same thing.
7. ~~**`session.log` `output`/`thinking` columns** and the `Bubble` wiring~~ **Done.** The footer is
   fed by `stream.shape(elapsed)`.

## 6. Not doing

- **A database.** JetBrains' Kotlin SQL options are **Exposed** (JDBC — Android ships no `java.sql`,
  so it cannot run) and **Xodus** (a KV store, no SQL aggregation). SQLDelight is Cash App; Room is
  Google. Compaction already bounds the scan, so there is no index to exploit: read is ~64 aggregate
  rows plus one open hour. The honest argument for SQLite is **transactions** for compaction, not
  speed. If compaction proves fragile, SQLite is the right upgrade and a span-keyed row migrates 1:1.
- **Two retention knobs.** One question too many; `requestDays` bounds history, raw is internal.
- **Per-request detail beyond the open hour.** Rolled up and gone: model, duration and finish reason
  per row. Nothing displays them today. If "which model cost most last month" is ever wanted, the
  aggregate row needs a small capped model→cost map.

---

## 7. What is built, and what is not

> **REWRITTEN 2026-10-28.** The previous version of this section was written against the *five-column*
> design and was wrong in both directions: it listed the five spans and the checkboxes as built (both
> are deleted), and it listed three things as not built that are done. A checklist that describes a
> rejected design sends the next person to build it.

### Built and green

1. **Streamed read, off the main thread.** `RequestStats.read` uses `useLines`; the screen reads
   through `AppViewModel.readRequestStats` on the worker and says "reading" until it arrives.
2. **The three log columns.** `span`, `requests`, `cost reported` — **appended, not prepended** (the
   plan said prepended; the instant is the first cell, so prepending would have made every line
   already on a phone unparseable). An old line reads as `raw`/1.
3. **The rollup arithmetic** as pure functions: `rollup`, the four `*Floor` functions in UTC, and
   `closable`, which computes each unit's end by asking for the next unit rather than assuming 30
   days — so February closes on its own length.
4. **The compactor** (`RequestLog.compact` + `rollUp`): closed hours rolled, raw rows dropped,
   `day`/`month`/`year` rebuilt from hours every time. One `writeWhole` for every rewrite, shared
   with `prune`; an unparseable line is written back verbatim. Called from `tickAgents`, throttled
   to ten minutes, on the worker.
5. **The reader uses the aggregates.** Coarsest stored level speaks for its unit; every level
   beneath a covered unit is silent. Containment, not equality, and the covered set is built once
   per draw rather than scanned per row.
6. **Two pickers, one column, per hour** (§1 as revised). `Interval` is `[fromMs, toMs)` with `toMs`
   an **exclusive boundary**: "from 19 to 20" is one hour, and a single instant is the hour it falls
   in. Snapping is outward at both ends. The minute stepper is gone — the table counts whole hours,
   so a minute was never choosable.
7. **Retention bounds the hour rows only** (was listed as not built). `prune` keeps every coarser row
   regardless of age; the hours are the only level where age costs anything. `prune` also rides along
   with `compact` rather than running on every write, which was a real performance bug.
8. **`session.log` `output`/`thinking` and the footer** (was listed as not built). `chatLive` is fed
   by `stream.shape(elapsed)` at `AppViewModel.kt:2727`, and `shape` renders elapsed, characters,
   thinking, calls and tokens.
9. **Percentages to three decimals** (§1a). `StatsFormat.share` renders `%.3f%%`; cost, per-request
   and the two rates render five. Pinned by tests in `RequestStatsTest`, which is where the gap
   survived unnoticed — the format had no test at all.

### Not built

**Nothing.** Every item in this plan is either built or explicitly not needed. The one open piece of
work in the request-history area is the bubble footer's persistence, which is tracked separately
because it is a `session.log` concern rather than a stats one.

10. ~~**The picker's four granularities** (§5 item 6)~~ — **NOT NEEDED** (operator, 2026-10-28). See
    §5 item 6: with two pickers a month is expressible as its first and last hour, so a month-only
    mode would be a second way to say the same thing.

### Three traps found while building, worth keeping

Each produced a plausible wrong number rather than an error, and each was caught only by a test
that asserted a specific figure:

- **`unitEnd("raw", …)` fell through to a year.** A raw row's unit was named by its span, `raw`
  matched none of the cases, and the else branch returned a year — so no hour could ever cover a
  raw row and every rolled hour was counted twice.
- **The compacted aggregate was unreadable.** `parse` refused `kind = "all"`, so correct rows were
  written and silently ignored on the way back, and the table fell back to raw rows that had been
  deleted.
- **A test fixture that never created the condition it asserted.** "An open hour is untouched" was
  tested with `now` exactly on the hour, so "a minute ago" was already closed — it would have
  passed whether or not the code respected an open unit.

### Three more, found after

- **`compact()` never added its fresh hour rows to the list `rebuild` reads.** The rows were written
  and had no parent, so no day, month or year was ever derived from a just-rolled hour.
- **A guard that would have emptied the pyramid.** Rebuilding only units whose start was at or after
  the oldest surviving hour sounded careful and was backwards: a month starting on the 1st routinely
  holds hours from the 3rd, so *its start being earlier than the oldest row means the log began
  late*, not that rows were lost. Every real unit failed it. The protection is **ordering** —
  `rebuild` before `prune` — not a filter.
- **An interval end treated as an hour instead of a boundary.** Both picks were contained, so
  19-to-20 was two hours and the `To` button read `19:00` while covering through `19:59`. An end is
  where the interval *stops*; `hours` is now `(to - from) / 3600000`, one number that cannot
  disagree with either endpoint.
