# Every report and its state

**The rule this file exists for:** a report is not done when a commit lands. It is done when **the row
saying so is here.** Twice now a batch of reports sat unread while I wrote code beside them, and once I
wrote a summary that silently omitted a whole build's worth. A ledger that is checked before every release
is the fix.

States: **CLOSED** (a commit answered it), **OPEN** (no commit yet), **ASKED** (needs an operator
decision), **NOT-A-BUG** (nothing needed, with the reason), **LATER** (deliberately deferred, with why).

Sources are the trace logs in `/root/Downloads/MuffyCodes log/`. Report numbers are the ordinal within that
log, in the order filed.

---

## build 1285 — the tool API (17 reports)

Full analysis in `/root/Downloads/PROPOSAL-tool-api-2026-11-04.md`. **All 17 were open as of 2026-11-04.**

| # | Report | State |
|---|---|---|
| 22 | "1 session" row is still visible — remove it | **OPEN** · not a tool report; belongs to the project-screen batch |
| 23 | clone/move to Downloads as a choice | **PLANNED** step 4 · ⋮ gains "Copy to Downloads" |
| 24 | search regex or text? if reranked why regex? | **PLANNED** step 5 · semantic, account-wide; three catalogues already exist (40 embedding, 12 rerank) |
| 25 | `paths` 10–100 instead of ≥1, default 10 | **SETTLED** · operator: "keep 100 max"; spec text will state 1 minimum. §2 |
| 26 | `tree`: rename `name`/`grep` → `name`/`content` | **PLANNED** step 2 |
| 27 | why does `tree` have `from`/`to` | **PLANNED** step 2 · dropped |
| 28 | `history` does not need `from`/`to` | **PLANNED** step 2 · dropped |
| 29 | merge `create`+`edit` into `write` | **PLANNED** step 1 |
| 30 | remove `from`/`to` from commands | **PLANNED** step 2 · kept on `read` only |
| 31 | remove `at` | **PLANNED** step 1 · dies with the merge |
| 32 | what does `replace` mean on `write`? | **SETTLED** · option A (whole-file); **`replace` is deleted** — `text` is the new text in every shape |
| 33 | regex selection is per-symbol? | **SETTLED** · `multi: true` granted; it carries **no cap of its own** — `forConversation` already bounds every result |
| 34 | `move` needs no `name` | **PLANNED** step 2 |
| 35 | remove `from`/`to` **only if** regex can select lines | **SETTLED** · `multi` granted; `from`/`to` stay on `read`/`write` only |
| 36 | `fetch` up to 100 MB | **PLANNED** step 3 · `LIMIT_BYTES` is 20 MB |
| 37 | split `downloads` into `download`/`upload` | **PLANNED** step 4 · three tools, `download` capped 100 MB |
| 38 | `archive`: `-u` flag + one path | **PLANNED** step 3 · `unpack: true`, output beside input |
| 39 | prompt still tells about `ask`/`conclude` | **CLOSED** `989c502` · brief rewritten, test reads the live brief |

---

## build 1282 / 1264 — UI and drawing (17 reports)

| # | Report | State |
|---|---|---|
| 1264·1–5 | github stuck waiting · classic token only · centred sign-in · openrouter primary · ticks inline | **CLOSED** `f178651`, `a2f45c6` |
| 1264·6–11 | system project menu · subtree locked · clone-only menu · one "New project" · `muffycodes_data` name · hide `+` | **CLOSED** `513f52e` (10 needed nothing) |
| 1282·12 | changes open in diff with head→last-sync preselected | **CLOSED** `e800169` |
| 1282·13 | diff editor `from`/`to` do not respond | **CLOSED** `b0dcc2b` |
| 1282·14 | eraser icon · one colour button · wheel · stroke size field · no pan/zoom with a tool · tap to deselect · saved strokes invisible in draw | **CLOSED** `522f1a0` + `b272537` (wheel replaced by swatches) + `2b37941` |
| 1282·15 | zoom-out drifts to upper left · min 30% | **CLOSED** `467ca12`, `8716568` |
| 1282·16 | max zoom 300% | **CLOSED** `8716568` |
| 1282·17 | picture briefly shown as characters · cannot swipe from view mode | **CLOSED** `ae87eab` |
| 1282·18 | no start/stop button | **CLOSED** `379f9f6` |
| 1282·19 | reasoning must always be returned | **CLOSED** `ed0525a` |
| 1282·20 | fullscreen closes · scroll jumps to start | **CLOSED** `a964691` |
| 1282·21 | last agent message vanished from chat but is in the log | **CLOSED** `5a5b211` |

---

## build 191 — settings, themes, chat (28 reports)

**All closed before 2026-11-01.** Spot-checked 2026-11-04: `MILK`/`BROWNIE`/`CHOCOLATE` themes exist in
`AppTheme.kt`, the send button is gone from the input row, the bug-report window reaches the chat screen.
The one-line-per-report detail is in `docs/PLAN-2026-10-30-trace-191.md`.

---

## What I am doing about the omission itself

The two failures share a cause: I read the log by slicing it — `sed -n '145,215p'` — and a slice has no
memory of what it skipped. Every report is now extracted in one pass with
`awk '$0 ~ /REPORT/ && $0 !~ /report-opened/'`, and this file is the place the answer goes, not a commit
message.

Two things I will do every time a new trace appears:

1. **Extract every report at once**, not in slices, and add a row for each before touching any code.
2. **Mark the row in the same commit** that answers it, so the ledger and the code cannot drift.

A report with no row here is a report I have not read.
