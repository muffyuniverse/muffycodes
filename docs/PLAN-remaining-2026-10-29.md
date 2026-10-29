# Plan: the four things left

2026-10-29, 20:50. Companion to `AUDIT-2026-10-29-consolidated.md`. That file says what is true;
this one says what to do about the small amount that is not.

**Nothing here is a feature.** All four items are the debt of a day that changed its mind twice:
one live inconsistency on a screen, one unpinned fix, and two documents that now lie about their
own currency. They are ordered by what a person would notice first.

---

## 1. `Fate` reports tokens as well as rows

**The defect.** `AgentLog.Fate` carries four `Int?`s — row counts — and `FateLine` renders
`"at 12 rows, to 9 (3 taken out)"`. Every other size in the app is now tokens: the session limit,
the memo, the trigger, the target, the ceiling. The settings screen shows both sentences eight lines
apart, in two units, with no conversion offered.

**Why it was right, and why it stopped being right.** R4 (`90c67c2`) was built when the design
talked about rows, and rows are what the markers record: `inContextBefore` derives the count from
the file's own order, and `droppedCount` reads the number the act wrote. That derivation is still
correct and should not be replaced.

**What to add, not replace.** Every row carries its `size`, so the token total at the marker's
moment is the sum of the `size` of the rows before it that were `in` — the same sum
`contextTokens()` computes, taken over a prefix. That is one more field per act:

```kotlin
data class Fate(
    val compactedAt: Int? = null, val compactedTo: Int? = null,
    val trimmedAt: Int? = null, val trimmedTo: Int? = null,
    val compactedAtTokens: Long? = null, val compactedToTokens: Long? = null,
    val trimmedAtTokens: Long? = null, val trimmedToTokens: Long? = null,
)
```

`FateLine` then says `"at 49k tokens (12 rows), to 24k (9 taken out)"` — **the token number first
because it is the unit the limit above it is in**, with the row count kept in parentheses because it
is the number a person can check by counting lines in the file. Neither number is dropped: they
answer different questions, and the row count is the one that can be verified by hand.

**A test.** The fate of a context whose rows are known sizes must report the sum, and a trim of a
whole burst must reduce it by exactly that burst's cost. This is the assertion the current `Fate`
has no equivalent of, because a row count cannot be wrong by a little.

## 2. Pin the trim's `target - fixed` arithmetic

**The fix that is unpinned.** `56b1e99` made the trim aim at `target - fixed`, where `fixed` is
everything in the request that is not a row. Before that, the app trimmed rows to the target and
then added the instructions on top, so the request landed above the number it had just decided to
reach.

**No test asserts this**, and it is exactly the kind of arithmetic that a later refactor reverses
without noticing — the parameter is still called `target` in `trimContext`, which reads as the whole
request's budget even though the caller now subtracts first.

**What to write.** One test at the `Agent` level: a context over the trim point, a target below what
the instructions alone cost, and an assertion that the **resulting request** is smaller than it was
and that the trim did not refuse. Plus a rename of the parameter — `trimContext(rowsBudget:)` —
because the current name is what made the bug possible.

## 3. Point the stale plan at the consolidated audit

`PLAN-trace-2026-10-29.md` §2 says "open" about seven items that closed hours ago. **A banner is
already added**; what is left is deciding whether the table should be rewritten or deleted. It
should be **deleted** rather than corrected: the reasoning that produced each item is worth keeping
and the state column is not, and the consolidated audit already carries the state. Keeping two
tables that must agree is the exact failure this day produced.

## 4. `AUDIT-2026-10-29.md` already points here

Done in the same commit as this plan. Nothing left.

---

## Order, and why

1. **§1 — `Fate` in tokens.** It is the only item a person can see. Everything else is invisible
   from the app.
2. **§2 — pin the trim arithmetic**, because it is a correctness claim with no test, and a silent
   reversal would put the request above its own limit again.
3. **§3 — delete the stale table.** Cheap, and it removes the trap for the next reader.

§1 and §2 are independent and can go in either order; §3 is documentation and can ride along.

## What is NOT in this plan, deliberately

- **No new compaction features.** The design is settled (`DESIGN-compaction.md`) and shipped. The
  temptation to add a "compact now" button or a per-project target is not a report and not a bug.
- **No re-derivation of the row counts in `Fate`.** They are derived from the file's order, which is
  stable because the file is only ever appended to; replacing that with a stored counter would be
  the running-total mistake the size memo exists to avoid.
- **No change to `forget`.** It refuses under the trigger and then does as it is told. The target is
  advice in the ask; the loop enforces it by asking again.
