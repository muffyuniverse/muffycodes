# Answers to the inline annotations

Four of your notes change the design. One was a question, and it has a real answer.

---

## §2 — "we can actually give the chat agent ability to read git commit log… also show in settings a list of subagents"

Accepted, and it splits into three separate things that I had conflated:

**(a) The chat agent reads history through tools, not by reading `.git`.** A model reading
`.git/objects` would have to decompress zlib and parse packfiles — it cannot, and telling it to try is
how a turn gets spent on a stack trace. But it does not need full git: the app already has
`GitService.log()` and `RevWalk`, so **`history` grows a mode that answers "which commit changed this
file, and what did it do"**, and **a new `diff` tool answers "what is in `muffy/agent/7` that is not in
`main`"** — file list, then hunks on request. That is a *subset*, and it is enough: the two questions
you named are "what commit included what" and "what is different between main and a subagent", and both
are answered without handing a model a shell.

**So: our subset is enough. Full git CLI is not needed and would be worse** — a shell is unbounded, and
this app's whole safety story is that every act is a named tool. What I would add is narrow and
purposeful:

| new | answers |
|---|---|
| `history {path, commits: true}` | which commit last changed this file, and what it did |
| `diff {from: "main", to: "muffy/agent/7"}` | the files that differ, and the hunks |
| `subagent {do:"merge", id}` | §9, below |

**(b) The subagent list belongs in Settings, and it needs four facts per row**: id, status
(working / asleep / retired), **whether its head is merged into local `main`**, and which project it
belongs to. Merged-ness is a real git question — `RevWalk.isMergedInto(branchTip, mainTip)` — not a
flag the app writes, because a flag would go stale the moment a person merges by hand.
@ or maybe actually not to overcomplicate subagent management, and just remove agent's folder when his branch is merged in main (instead of when he is idle) - this way there will be no loss. And we dont need retired state, we should rather fully delete this subagent's folder when subagent's limit is decreased because it is not needed anymore and keeps no new data.

**(c) Adding and removing subagents from Settings** is the operator-facing half of what the `subagent`
tool does. It has to be the *same* code path, or a person's "remove" and the agent's "retire" will
disagree about what a retired id means.

**What I got wrong in §2, and you corrected it:** I argued for putting worktrees outside `agent/`
because `agent/` is disposable bookkeeping. Your note implies the answer: **a worktree the person can
see in Settings and whose unmerged work they are asked to merge is not bookkeeping.** It is a first-class
thing with a lifecycle. So the fix is not to move it out — it is to stop treating `agent/` as disposable:
`agent/<id>/worktree/` is fine, provided nothing prunes a folder that holds a worktree with unmerged
commits, and Settings is where a person sees that it exists.

I would still split it: `agent/<id>/` for the record (logs, context), `agent/<id>/worktree/` for the
tree. One folder per subagent, two kinds of content, and a prune that refuses when the worktree has
unmerged commits.

## §3 — "for chat agents whole account is readable, and their particular project is editable. for subagents only their worktree is readable and editable"

Accepted, and this is a **two-dimensional** rule that the current `Scope` cannot express. Today `Scope`
is one axis — `Account` or `Project` — and both read and write use it. What you have described is a
matrix:

| | read | write |
|---|---|---|
| chat agent | whole account | its project |
| subagent | its worktree | its worktree |

The chat agent's read/write split is **already what the code does** (`isMine` decides write; `search`
reaches everywhere) but it is not *stated* anywhere as one rule, so a new tool could get it wrong. I
would make `Scope` carry both answers:

```kotlin
sealed interface Scope {
    fun mayRead(file: File): Boolean
    fun mayWrite(file: File): Boolean
}
```

with `Chat(project)` = read-anything, write-inside-project; `Worktree(root)` = both inside-root. Then a
new tool cannot be added without confronting the question, which is the point.

**And the refusal must say which axis failed.** "You cannot read that" and "you cannot write that" are
different sentences, and a model told the wrong one will try the other and then give up.

## §6 — "we can force merge main into subagent's worktree when chat agent writes to him"

Accepted, and it is better than my lazy-on-wake, because **the trigger you named is the moment the
subagent is about to be given work.** That is exactly when its tree must be current, and the chat agent
is the one who knows it — so the app does not have to guess.

What I would keep from my version: **the merge is forced, but a conflict is not forced through.** Your
earlier rule — conflicts are the subagent's problem — means the sequence on `write to subagent` is:

1. `main` → worktree, fast-forward where possible, real merge otherwise;
2. **conflict → the write is refused**, the subagent is not woken, and the chat agent is told which files
   conflict;
3. the chat agent either resolves them by writing to the subagent's tree, or gives up on that task.

Step 2 is the one worth stating: **a refused write is much better than a subagent woken into a
half-merged tree**, because the second one fails later, in a context that has already cost tokens, and
the failure looks like the task was too hard.

`--force` is only needed when the subagent has already *pushed* its branch and the merge rewrites it.
That is a real case now that agents push (§9), so the branch must be treated as rewritable up to the
moment it is merged — and **`pushForce` must use `--force-with-lease`**, never a bare force, or a phone
with a stale view deletes somebody's commits.

## §9 — "agents need to push to their branches to run ci on them, how otherwise will they test their own work"

You are right, and it kills the sentence I wrote. The rule cannot be "the agent never pushes" — a
subagent that cannot push cannot run CI, and a subagent that cannot run CI cannot test its own work,
which is most of the reason to have it.

So the rule has to be about **which ref**, not whether:

> **An agent may push only its own branch, and never `main`.** The chat agent may push its own branch; a
> subagent may push its own. Only a person's Sync pushes `main`.

That is enforceable in one place — the push takes a ref, and the ref must equal the caller's branch — and
it is checkable in the trace: every push names its ref, so "who pushed what" is a line, not an inference.

And it means `createProject` (§7, which you confirmed) is consistent: it pushes `main` **because a person
pressed Create**. The rule reads: *a push to `main` requires a person's act; a push to any other ref
requires only that it is the pusher's own branch.*

## §9 — merge: one tool, after the report, real merge, refuse on conflict

All three accepted, and they settle the open question in that section: **the merge is based on the chat
agent's reason**, which means it takes a message. So:

```
subagent { do: "merge", id: 7, reason: "the retry fix, which I have reviewed" }
```

The `reason` is not decoration — it becomes the merge commit's message, and it is the only place the
*why* of a merge is recorded. A merge with no reason is a commit that says what happened and not why,
which is the thing this project's history is otherwise careful about.

Refusing on conflict is right, and it pairs with §6: the chat agent is told which files, and writes back
to the subagent to resolve them. That is the same division of labour in both directions.

---

## What I would still push back on, having read your notes

**One thing.** In §6 you said *"force merge main into subagent's worktree when chat agent writes to him"* —
and I accept it — but there is a case the rule does not cover: **the subagent is asleep, and `main` moves
a lot while it sleeps.** Nothing merges, so its branch falls further behind, and the eventual forced merge
is large and conflicts on more files than it had to. That is not a bug in your rule, it is a cost of it,
and the mitigation is the eager *mark* I suggested — Settings shows "subagent 7 is 23 commits behind", so
a person can see a stale branch before it becomes a painful merge. **Cheap, and it makes the cost
visible rather than surprising.**
@ can we just make it so that on next instruction from chat agent after merge subagent's (not between instructions without merge) main is force pushed into subagents worktree so that it starts working in main fresh clone.

**And one question I would ask before building the CI half:** when a subagent's CI run fails, who is told?
The run belongs to `muffy/agent/7`, and the phone is not watching. If the answer is "the subagent, next
time it is woken", then a failed run is invisible for as long as the branch is asleep — which is the
same class of problem as the log that recorded its ending and not its start. I would have the run's
conclusion written into the subagent's record when the app next sees it, and shown in Settings beside
"unmerged".
@ subagents should have their own background commands that wake them up so that they can work on their tasks untill finished.
