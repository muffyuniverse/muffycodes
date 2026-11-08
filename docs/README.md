# The documents, and which one is true

Every file here is dated by the day it was written, and a document that says "OPEN" is a claim with
a timestamp. This page exists because that costs a reader real time: six of these files once
described work as outstanding that had shipped hours later, and the only way to tell was to check
the code.

**Start with the audits.** They are the only files that claim to know the state of anything.

| Read this | For | State |
|---|---|---|
| `AUDIT-2026-11-07-branches-and-the-system-folder.md` | What was actually wrong with branches and the system folder, the eleven defects, and what is still open | **The current audit.** |
| `AUDIT-2026-10-29-consolidated.md` | Every report from the 2026-10-29 trace, re-checked against the code, plus what was superseded and why | **Current.** Supersedes the file below it. |
| `AUDIT-2026-10-29.md` | The same reports as first written, at 18:27 | Historical. Its table is accurate; its "two limits" framing was replaced. Carries a banner saying so. |
| `audit-agent-chat.md` | Every element of the agent chat, the test that covers it and the trace line it writes | Historical. Its "what is open" section is now "what became of each". |

## The plans, all of them finished

These were written before their work landed, so they read in the future tense about things that are
in the app. **None is a work list.** Each now says so at the top, and each item carries its commit.

| File | What it planned | State |
|---|---|---|
| `PLAN.md` | The notes app, end to end | **Complete.** Every item struck through with its commit; streaming was the last. |
| `PLAN-trace-2026-10-29.md` | What the 2026-10-29 trace asked for | **Complete.** §3's four decisions were all made and built the same day. |
| `PLAN-chat-turns-and-pages.md` | The chat as bursts and pages | **Complete.** |
| `PLAN-chat.md` | The chat rebuild | **Complete.** Every step marked. |
| `PLAN-request-history.md` | The request log and its roll-ups | **Complete.** |
| `PLAN-streaming.md` | Streamed replies | **Complete.** Parser, wiring, timeouts, live footer, `exclude: false`. |
| `PLAN-bug-reports-v149*.md` | The v1.4.9 report rounds | Historical, kept for the reasoning. |
| `PLAN-remaining-2026-10-29.md` | What was left on that day | Historical. |
| `PLAN-universal-2026-11-06.md` | **`http`, git and the layers that stay** — what makes the app portable to any git and any OpenAI-compatible provider, what is deleted, what is kept and why, and the two live bugs found on the way. | **Not started.** Parts 1–2 are a live bug each; parts 3–6 are the feature work. |
| `PLAN-universal-cut-2026-11-08.md` | **The removal list**: the one test (can the git protocol do it?), what 3,466 lines and 20 call sites are GitHub's alone, adding a project by URL checked with `ls-remote`, a release as a tag, and the seven steps V1–V7. | **Landed.** Every step is green. Accounts are name + host + key on any host through one save; the SSH refusal comes before any credential; a project is added by an address checked with `ls-remote`; the repository picker, the CI surface, the signing key, `GitHub.kt` (all but one question) and the second delete choice are gone; a release is a tag; and the project verbs are add, set the address (which renames the folder after it) and go local. |
| `PLAN-host-labels-2026-11-08.md` | **Accounts on any host, and the host in the label**: `login@host` in everything a person reads, stored names untouched, `Account.host`/`gitUser`, the PAT door (S1–S6), and adding a project by remote URL (U1–U6) — with what is universal about that and what is not. | **Landed through the universal cut** (it became its annex A: S1–S6 and U1–U6 are steps V1–V3 of `PLAN-universal-cut`). What survives as a separate document is the reasoning, not a to-do list. |
| `PLAN-portable-2026-11-08.md` | **The portable rule**: two boundaries (the app owns the device, the token owns the remote), what it strikes down (host-specific gates, Apps, main-as-trunk-only), and the phases P0–P3. | **P0 and P1 landed.** The write boundary is a canonical test in the funnel; a project on any host authenticates from the vault, and no host is named in the code. P2 was **replaced, not done**: the typed GitHub tools were deleted and the agent reaches any host with `http` and a secret bound to it, which is the same rule P2 was reaching for with less code. |

## The design notes

| File | About |
|---|---|
| `DESIGN-compaction.md` | **The app measures; the agent decides.** Why the clear-up is a request to the model rather than a trim, why `forget` is refused below the trigger, and why one bulk call. |
| `agent-context-and-loop.md` | The one-record model: what is in the context, what a turn is, what the loop guarantees. |
| `agent-tools-*.md`, `PROPOSAL-*.md`, `proposal-operator-*` | The rounds of negotiation that produced the tools and the chat. Kept because the *reasons* are not recoverable from the code. |
| `ci-scaling.md`, `ci-audit-2026-10-23.md`, `visual-review.md` | How the build is shaped and what it costs. Deferred ideas in these are ideas, not bugs. |

## The rule this page is enforcing

**A status belongs in exactly one place.** When a plan finishes, its items get their commits and the
file says it is history; the audit is where a reader goes for the current state. Two tables that
must agree is what produced the confusion in the first place — `PLAN-trace-2026-10-29.md` deleted its
own state column for exactly this reason, and left a note where the column had been.
