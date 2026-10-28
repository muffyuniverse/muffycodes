# The request history, and who owns which number

Status: **agreed 2026-10-28**. This file records what was accepted and nothing else. Where a
proposal was dropped, it says so and why — a plan that silently keeps a rejected idea is how a
defect gets built twice.

---

## 1. What the stats table is

Pick **one instant**. It belongs to **five nested spans at once**, and each is a column:

| column | from | to (pick = 12:45 on 25 Oct 2026) |
|---|---|---|
| hour  | `12:00`             | `12:45` |
| day   | `00:00` on the 25th | `12:45` |
| month | `1 Oct 00:00`       | `25 Oct 12:45` |
| year  | `1 Jan 00:00`       | `25 Oct 12:45` |
| total | the oldest row      | `25 Oct 12:45` |

Five cumulative windows, all ending at the same instant, each nested inside the next.
**Everything is clock time** — not elapsed milliseconds, not a rolling window.

**Three were built first and five were agreed**: the year and total columns only became computable
when the pyramid (§2) existed, because they need complete-month and complete-year rows to sum. The
first two builds drew three because there was nothing coarser to read.

**Which columns are drawn is chosen by checkboxes to the right of the date picker**, one per span,
all five on by default. Hiding a column must not change any other column's number: the checkbox is a
drawing rule over a fixed computation, never a different computation.

### 1a. The rows

The operator's list (L28419), in this order. These are the **usage axis**; the spans above are the
**time axis**.

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

- **Per million, not per billion.** Agreed at L29550 after a revert: *"I later reverted to per
  million for every token ratio."* An earlier recording of "per billion built as per million" was
  wrong and is not a defect.
- **Cache is a share of input, not a row beside it** — `cached_tokens` is a subset of
  `prompt_tokens`, so a third absolute would count the same tokens twice.
- **Precision** (L28419, L28438): percentages to **three** decimals where it makes sense, cost and
  the two rates to **five**. The current `StatsFormat.share` renders **one** decimal (`%.1f%%`) and
  is therefore not yet to spec; cost/`perMillion`/`perRequest` are already `%.5f`.
- **`—` is not zero** (agreed earlier): a span with no requests has no average, which is a different
  fact from an average of zero.

Consequences, each pinned by a test:

- Month = `1 .. n`, where n is the picked day. A pick on the 31st is the whole month.
- Year = `1 Jan .. the pick`; total = `the oldest row .. the pick`. Both read their complete coarser
  rows and descend only for the open chain.
- A request after the pick is in no column: at 12:45 the afternoon had not happened.
- At 00:00 the hour and day columns are **empty** (no elapsed time yet); the month, year and total
  still hold their earlier spans. Deliberate; do not special-case it.
- The columns **overlap** — an hour is part of its day, which is part of its month. That is the
  information, not double counting, and it is why the five can be read side by side at all.

Rejected and built first, recorded so they are not rebuilt:

| rejected | why |
|---|---|
| "the last 24 hours / 30 days / 12 months" | a window *ending* at the pick; a month of columns that cannot show October alone |
| one axis at a time (24 hours **or** 31 days **or** 12 months) | makes the person choose a granularity before seeing anything |
| a picker per axis | switching axis changed *when* you were looking at |
| three fixed columns | true only while hour/day/month were all the pyramid could compute; year and total need complete-month and complete-year rows (§2) |
| closing the span (`12:00–12:59`) | counts time that has not happened |
| a boundary exception (pick at 12:00 → whole hour) | the columns then disagree with each other |

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

Each step is independently shippable and testable.

1. **Stream the log and read it off the main thread.** `readLines()` materialises the whole file as
   strings *and* one object per row, inside `remember`, on the main thread. At 10k/day that is ~50 MB
   peak at 30 days and ~600 MB at a year. Streaming is also a **prerequisite for the compactor**,
   which reads the same file. No data loss; it tells us what remains painful once the catastrophe is
   gone.
2. **Add `span`, `requests`, `cost reported`** to `RequestLog`/`RequestStats`, with `raw`/1/`0|1` for
   existing lines and a test that old lines still parse.
3. **Compactor**, on the existing session tick, off the UI thread: group remaining raw rows by UTC
   hour → emit one `hour` row per **closed nonempty** hour → drop that group. Then cascade
   `hours → day → month → year`, each only for closed units. Rewrite is temp + rename under the same
   lock as appends; raw rows dropped only after the aggregate is durable.
4. **Reader**: the five spans (§1) as a disjoint decomposition (§2) — a test that a span is never
   counted twice, and a test that a rebuilt month equals the sum of its hour rows. `year` sums month
   rows; `total` sums year rows; neither exists as a stored row.
5. **Checkboxes** right of the date picker, one per span, all five on by default. A checkbox changes
   which columns are drawn and **no column's number**: hiding `day` must not move `month`.
6. **The picker's four granularities.** The operator asked for a picker over **hour, day, month and
   year** (L28599). Today there is one Material `DatePicker` (a *day*) plus hour/minute steppers, so
   picking *year only* or *month only* is impossible. The anchor is one instant either way; the
   question is only how much of it the picker lets a person set directly.

7. **`session.log` `output`/`thinking` columns** and the `Bubble` wiring — the footer currently
   cannot render: `Bubble.thinking`/`output` are drawn (`AgentChatScreen.kt:445`) but **no constructor
   sets them**, and `Streamed`'s numbers are view-model state discarded at turn end.

---

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
