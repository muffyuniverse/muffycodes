# Archive — proposals set aside

Nothing here is planned work. These are kept because the reasoning is worth being able to find again,
not because any of it is going to be built.

## subagents — removed 2026-11-03

**`COUNTERPROPOSAL-subagents*.md` and `AUDIT-subagents.md`.** Four rounds of design and an audit of
the implementation, all for a feature that was built and then removed: subagents with their own ids,
records, warm ceilings, reports and reset.

The audit is the most useful file here, because it is a list of thirteen bugs in the author's own
work — the ones that only appear when an agent has an id, a context and a lifecycle of its own.

**Why it went:** the operator's words, above. And the evidence is in these files: three counterproposal
rounds to make two agents share one project safely, and the feature still had a state machine
(`live` / `stopped` / `retired`), a warm ceiling that was also a stop clock, and a report protocol.

## push/branches/worktrees — set aside 2026-11-03

**The three `COUNTERPROPOSAL-*-push-branches-worktrees.md` files.** They designed a world where every
subagent had its own git branch and worktree, CI ran per-subagent on that branch, and the chat agent
merged branches back into local `main`.

**The operator's decision:** *"Remove subagents — all what is already implemented… I believe that we don't
need them, they introduce too much complexity. It will be better if we focus on improving efficiency and
speed and multitasking of the chat agent."*

That is the right call, and the proposals themselves are the evidence for it. Three rounds of
counterproposal produced, between them: two-dimensional scope, per-worktree locking keyed on the common
git directory, lazy rebase with conflict refusal, a merge tool with a reason, a wake loop with a budget,
a superseded-run rule, and a deletion rule that must ask git rather than remember. **Every one of those
was a new mechanism whose only purpose was to keep two agents from damaging each other** — and none of
it made a single note get written faster.

The complexity was not accidental; it was the cost of the feature. Removing the feature removes the cost.

**What survives, and is not in this folder:** the subagent code was deleted along with the feature. The
`Scope` type stays, because the chat agent is still bounded by "read the whole account, write your own
project" — that rule never depended on there being a second kind of agent.
