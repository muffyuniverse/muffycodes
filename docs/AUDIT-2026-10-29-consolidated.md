# Consolidated: what was planned today, what was built, what is left

2026-10-29, written at the end of the day. This supersedes `AUDIT-2026-10-29.md` as the current
state of the work, and it is the file to read first. Where the two disagree, this one is right —
and §2 lists every disagreement, because **the design changed twice after that audit was written**
and a document that says "CLOSED" about a rule that has since been replaced is worse than no
document.

Three sources are compared, and the comparison is the point:

- **planned** — `PLAN-trace-2026-10-29.md`, `PLAN-chat-turns-and-pages.md`, and the operator's
  messages of the day;
- **reported** — `trace.log (2).txt` (dev-3a70b7d, 13:17–16:26), 27 reports plus 27b;
- **built** — the code at `56b1e99`, checked by reading it rather than by reading a plan that says
  it was done.

---

## 1. The day in three arcs

The day had three distinct bodies of work, and the third one rewrote part of the first two's
vocabulary. That is the reason this audit exists.

| Arc | When | What it settled |
|---|---|---|
| **A. The trace reports** | 06:00–16:30 | 27 reports, plus the chat rebuild and the tool-schema bug |
| **B. Chat as messages** | 14:00–15:30 | the unit is a **burst**, pages inside a message, not one bubble per act |
| **C. Context compaction** | 18:00–20:45 | one **target**, two **triggers**, and `forget` gated on the trigger |

Arc C is what makes Arc A's audit stale in two places, and §2 is exactly that list.

---

## 2. What today's later work SUPERSEDED

This is the section the operator asked for, and it is deliberately first.

| Earlier statement | Where | Superseded by | Evidence |
|---|---|---|---|
| "the app's trim is a **last resort** that must never fire first; a hard limit below the trigger is **raised** to it" | `ContextBudget.Limits.init`, `ContextBudgetTest` | **A trim below the forget point is a mode, not a mistake** — the agent is simply never asked | `cbe0284` — `hardAt` is now `hard.inForce`, the raise is gone |
| "autotrim is **one messagebox at a time**" | operator's words, quoted in `AgentLog.trimContext` | **One pass to the target** — walk whole bursts from the newest, stop where the next would not fit | `cbe0284`, `6761db5` |
| "**two** limits, both token counts" (`trigger`, `hard`) | `ContextBudget` header, `PLAN-trace` §3.2 | **Three numbers** — a shared `target`, plus the two triggers | `cbe0284` |
| the clear-up line names **the trigger** as the size to reach | `clearUpLine` before `cbe0284` | It names the **target**, which is a different, lower number | `cbe0284` |
| "a `clearedUp` flag, measured by watching the row count" | `Agent.work` before `1acb57b` | **The size alone** — the flag was a second answer to a question the measurement already answers, and it cost two file reads per turn | `1acb57b` |
| "the clear-up line says the size **when this turn began**" | `clearUpLine` | "**is** about N tokens" — the number is this request's own measurement | `1acb57b` |
| audit R4: "the context's **four numbers**" as row counts | `AgentLog.Fate` | **Not yet superseded in code** — see §4, this is the one real gap | — |

**Two supersessions are invisible from the audit's own table**, and that is the failure mode worth
naming: R11 says the trim "works per burst", which was true and is still true of *burst integrity*,
but the audit's phrasing also carried "one burst per call", which is now false. A reader trusting
the table would test the wrong thing.

---

## 3. The reports, re-checked against the code today

All 27 plus 27b, verified by reading the source at `56b1e99`, not by trusting the earlier audit.

| # | Report | State | How it was verified |
|---|---|---|---|
| 1 | Text selectable | **CLOSED** | `SelectionContainer` in trace, stats, editor, history, report window, both chat sides |
| 2 | Why sign in for a local project? | **CLOSED** | provisional `~local` account |
| 3 | Remove "sign in to begin" | **CLOSED** | README opens once, from a posted call |
| 4 | Context block: four fields | **CLOSED, but see §4** | `Fate` + `FateLine` exist and render; they count **rows**, and the unit elsewhere is now tokens |
| 5 | Rename command-execution timeouts | **CLOSED** | "While the app works on this phone" |
| 6 | Rename model-response timeouts | **CLOSED** | "While it talks to the model" |
| 7 | Three fields: connection/stalling/reconnect | **CLOSED** | present in the model block |
| 8 | Shorter descriptions | **CLOSED** | rewritten |
| 9 | "The record" → "Session log" | **CLOSED** | no user-facing "The record"; the two remaining hits are doc comments |
| 10 | Session limit in tokens, drop MB | **CLOSED** | `sessionTokens` summed from the `size` column; MB field gone |
| 11 | Delete the oldest bubble's full span; cache sizes | **CLOSED** | burst-integrity preserved; memo keyed by length **and** mtime |
| 12 | Two horizontal lines under Reminders/Running | **CLOSED** | — |
| 13 | Debug icon on stats | **CLOSED** | — |
| 14 | "What it costs" → "Account statistics" | **CLOSED** | string absent |
| 15 | Better stats icon; gitignore → **unsync** | **CLOSED** | bar chart drawn; unsync rebuilt |
| 16 | Lock/unsync colour states | **CLOSED** | `Workspace.Hold` / `Unsync` |
| 17 | Locked file still offers Move | **CLOSED** | — |
| 18 | No sync bar on a local project | **CLOSED** | `isLocalProject` guard |
| 19 | Local projects get "add to GitHub" | **CLOSED** | publish / keep-local, both directions |
| 20 | Footer hour:minute, id first | **CLOSED** | — |
| 21 | Remove "1 session bar" | **CLOSED** | string absent |
| 22 | Key set but "no OpenRouter key" still shows | **CLOSED** | `1053cf7` |
| 23 | Provider Add/Remove lists endpoints | **CLOSED** | `5c0d370` — they were opening the *model* dialogs |
| 24 | Effort popup needs one select | **CLOSED** | `b672628` |
| 25 | Reset at the bottom of settings | **CLOSED** | `reset()` calls the same setters, `DEFAULT_*` constants |
| 26 | Themes truly different; Chocolate | **CLOSED** | five hue families, closest pair 79 apart |
| 27 | 400 on every message | **CLOSED** | `spec()` wraps examples in a real schema |
| 27b | Chat as messages with pages | **CLOSED** | burst unit, active-page height, pager, fullscreen |

**Nothing in the report list is open.** The one qualification is R4's *unit*, which is a new defect
rather than an unbuilt report — see §4.

---

## 4. The one real defect found by this audit

**`AgentLog.Fate` counts rows; every other size in the app is now tokens.**

```kotlin
data class Fate(
    val compactedAt: Int? = null,   // rows in the context when the agent last forgot
    val compactedTo: Int? = null,   // rows that act took out
    ...
)
```

and `FateLine` renders `"at $at rows, to ${at - to} ($to taken out)"`.

That was right when R4 was built (`90c67c2`), because the design then talked about rows. Since then
R10 made the session limit tokens, R11 made the memo a token sum, and today's compaction work made
the trigger, the target and the ceiling tokens. **A person now sees "at 12 rows, to 9" on the same
screen as "forget at 49k · trim at 98k · both to 24k".** Two units, one block, no conversion offered.

The fix is not a rewrite of `fate()`: the markers record what they recorded, and `inContextBefore`
derives the row count from the file's own order. What is needed is **the token total at that moment
too**, which is recoverable — every row carries its `size`, so the sum of the rows before the marker
is the number the person wants, computed the same way `contextTokens()` computes it.

**This is the highest-value item left**, because it is a live inconsistency on a screen the operator
reads, not a missing feature.

---

## 5. What is genuinely left

Short, and ordered by what a person would notice first.

| # | Item | Why it matters | Size |
|---|---|---|---|
| **1** | `Fate` reports tokens as well as rows | Two units on one screen, §4 | small |
| **2** | A test that the trim's `target - fixed` arithmetic holds | `56b1e99` fixed it; nothing pins it | small |
| **3** | `PLAN-trace-2026-10-29.md` §2 table is stale | It says "open" about seven items that are closed | small |
| **4** | `AUDIT-2026-10-29.md` should point here | Two documents claiming to be the truth | trivial |

Nothing else. There is no unfinished report, no TODO in `main/`, and no TEMPORARY commit left in
the tree — all four `TEMPORARY:` commits were probes and their findings were kept, not their code.

---

## 6. What the day actually taught, in four sentences

**The audit was written before the design finished, and that is the reusable lesson**: a document
that says "CLOSED" is a claim with a timestamp, and the later a reader arrives the more of it has
quietly expired. Both supersessions in §2 were invisible from the table itself.

**Four tests passed while the app was wrong** (`AUDIT-2026-10-29.md` names them), and today added a
fifth: the "says nothing under the target" assertion checked for the absence of a phrase that no
longer existed anywhere, so it would have passed whatever the code did. It was found only because
the wording around it changed.

**Two real bugs were found by tests going red for arithmetic reasons, not design reasons** — the
target passed as a row budget, and a trigger of one token having no integer below it. Both were
caught because the tests asserted *numbers*, not the presence of code.

**The operator's corrections were all about authority, not mechanism**: who may compact (the app
asks, the agent chooses), how often (until the size says so, then stop), and in what unit (the one
the model is billed in). The mechanisms followed.
