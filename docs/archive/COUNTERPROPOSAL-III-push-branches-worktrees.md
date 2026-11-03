# Counterproposal III — the annotations make it smaller

All three of your notes remove work rather than add it, which is a good sign: the design is settling
toward fewer states and fewer triggers, not more. Taking them in order, then the gaps that open up.

---

## 1. "remove the folder when his branch is merged in main — no loss. And no retired state; delete the folder when the limit is decreased"

Accepted, and it deletes more than it adds. Three states become two, and one of the two is not a state at
all:

| before | after |
|---|---|
| live | live |
| stopped (asleep, keeps context) | stopped (asleep, keeps context) |
| **retired** (id taken away, folder kept) | **deleted** — the folder is gone |

**Retired was the only state whose whole purpose was to preserve something for later.** It existed because
the count could shrink and an id that came back would silently attach a report to a different agent's
work. But if the folder is deleted outright, the id cannot come back either — and the id counter is what
actually prevents that, not the `retired` flag. So the flag was carrying no weight the counter was not
already carrying.

**Deleting on merge, not on idle, is the part that removes a whole class of bug.** "First idle is
removed" meant a subagent could be deleted while its work was unmerged — which is exactly the silent loss
I flagged in §2 of the first counterproposal. Deleting on merge means the folder disappears at the one
moment its contents are provably redundant: the commits are in `main`.

**Two things the rule needs to say, or it will delete work:**

1. **"Merged" must be asked of git, not remembered.** `RevWalk.isMergedInto(branchTip, mainTip)` — a
   `merged=true` field written when the merge tool ran would go stale the moment a person merged by hand
   or rebased `main`. The deletion check asks the repository, every time.
2. **A subagent with uncommitted changes is not deleted, even if its branch is merged.** A merged branch
   plus a dirty worktree means the agent wrote something after its last commit. That is the one case where
   "the work is in main" is false while the branch says otherwise — and it is the case that loses
   somebody's last five minutes.

So the deletion rule is: **branch merged into `main` AND worktree clean → delete the folder.** Anything
else → keep it and say why in Settings ("merged, but 3 uncommitted files").

**What the limit decrease does.** Your note separates the two cases and I think it is right: a limit
decrease deletes the folder regardless of merge state, because the person asked for fewer subagents and
the app must obey. But that is a **destructive act with no undo**, so it is the one place I would add
friction: the count change says which ids will be deleted and whether any has unmerged work, and refuses
if one does — unless the person says so twice. `RepoLocks` protects against concurrent writers, not
against a person meaning it.
@ no need to say anything, just next merged subagent gets deleted untill the amount of subagents is within the set limit. and settings stay simple, I strike down my proposal on complicating it with agent lists, just one number which is the limit for all projects - write it down so it is clear.

## 2. "on next instruction from chat agent after merge, main is force pushed into the subagent's worktree so it starts in a fresh clone"

Accepted — and it is simpler than what I proposed, because it drops the "eager mark" too. The sequence on
**write to a subagent** becomes:

1. If the subagent's branch is merged into `main` → the folder should already be gone (§1); refuse the
   write and say so.
2. Otherwise, **reset the worktree to `main` and re-apply its own commits** — which is what "starts
   working in main fresh clone" means in git terms: `git rebase main` inside the worktree.
3. Conflict → refuse the write, do not wake the subagent, name the files. The chat agent either resolves
   them or gives up on that task.

**"Not between instructions without merge"** is the clause that makes this cheap: nothing happens while
the subagent sleeps, so there is no N-rebases-per-save problem and no rewriting pushed history while
nobody is looking. The worktree is current exactly when it is about to be used.

**One correction to my own earlier text:** I said "force merge". `git rebase` is not a force merge, and
the difference matters — a merge would put `main`'s commits *beside* the subagent's, leaving a history
that says both happened independently. A rebase puts the subagent's work *on top of* current `main`, which
is what "starts working in a fresh clone" describes. Rebasing does rewrite the subagent's commits, so its
pushed branch needs `--force-with-lease` afterwards — never a bare force.

**And the state this creates that needs saying out loud:** after a rebase, the subagent's branch has
diverged from its remote. If a CI run was started for the old tip, that run's result describes code that
no longer exists. The run should be marked superseded, or the app should simply not trust a result whose
commit is not the current tip.

## 3. "subagents should have their own background commands that wake them up so they can work on their tasks until finished"

This is the answer to the CI question I asked, and it is a bigger answer than the question: it turns a
subagent from *something you write to* into *something that runs until it is done*.

**What exists today:** `background {tool, args}` runs one tool call and writes its result to a file, so
the conversation is not blocked. That is a single call, not a loop — nothing wakes a subagent up.
@ look deeper, we surely set it up so that a background command notifies when it is finished. We already should have this in some form, we just need to generalize it so that it is scoped to the id of the caller.

**What this needs, stated precisely, because "wake them up" can mean three different things:**

1. **A subagent that is mid-task keeps going.** Its turn ends, the model asks for another tool, the runner
   runs it — that already happens within one turn. The gap is *between* turns: a subagent that says "I
   need to wait for CI" stops, and nothing resumes it.
2. **A subagent waiting on a CI run needs something to wake it when the run finishes.** That is a poll,
   and it is the same shape as the device-flow poll: an interval, a deadline, and a cancellation that is
   checked before each wait. It must be **foreground-only or explicitly bounded**, because a poll that
   runs while the phone is asleep is a battery cost and a token cost for a run nobody is watching.
3. **A subagent that has *finished* must stop being woken.** "Until finished" needs a definition, or a
   subagent that believes it is nearly done polls for ever. I would say: it is finished when it reports
   back to the chat agent, and the wake loop stops at the same moment. A budget is the backstop — N wakes,
   then it stops and says so.

**The cost that has to be faced:** every wake is a model call, and the phone pays for it. The app already
learned this with the chat keepalive (a 4-minute ping, measured at $0.000018 each, in a 5–45 minute idle
band only). A subagent wake loop is the same economics with a worse failure mode, because a subagent that
polls a CI run every minute for an hour is 60 calls to answer a question that arrives once. **The wake
should be event-driven where it can be** — the app knows when the CI run's conclusion changes, because it
is the thing that asked GitHub — and interval-driven only as a fallback.

**And the thing this makes possible, which I would build in this order:** with a wake loop, a failed CI run
is not invisible. The subagent is woken by the failure, reads the log, and either fixes it or reports back.
That is the whole loop you described in the first message — *"agents need to push to their branches to run
ci on them, how otherwise will they test their own work"* — and it only closes if something wakes them.

---

## What is now settled, and what is left

**Settled:**

- Branch `muffy/agent/<id>`, worktree at `agent/<id>/worktree/`, record beside it.
- `Scope` is two-dimensional: chat = read account / write its project; subagent = read+write its worktree.
- An agent pushes only its own branch; only a person's Sync pushes `main`.
- CI via `workflow_dispatch --ref muffy/agent/<id> -f id=<id>`.
- Rebase onto `main` when the chat agent writes, never eagerly; conflict refuses the write.
- Merge is one tool, a real merge commit, with the chat agent's reason as its message; refuses on conflict.
- Two states, not three: live and stopped. **The folder is deleted when its branch is merged and its
  worktree is clean**, and unconditionally when the count is decreased.
- Subagents wake themselves to work a task to completion.

**Left, and I would not build past these without an answer:**

1. **What does a wake budget look like in the operator's terms?** "N wakes or T minutes, then stop" needs
   two numbers, and the app should show the subagent when it is close to the end rather than stopping it
   silently.
   @ dont we already define how much subagent can wait untill it is stopped.
2. **Does a stopped subagent's worktree get rebased while it sleeps?** Your rule says no — only on the
   next instruction. That means a subagent asleep for a week has a week-old tree, which is correct and
   will be surprising. Settings should show the distance from `main` for a stopped subagent, because that
   is the number that predicts a painful merge.
   @ no, just after reporting (messaging without toolcalls to the chat agent) he starts waiting, and after timeout he is stopped. On next message from chat agent his worktree is rebased, and on merge his worktree is cleared.
3. **A CI run started for a tip that was then rebased.** The result describes code that no longer exists.
   I would record the commit in the run's record and treat a result for a stale commit as "no result".
   @ you know, lets make it so that subagents have to ...
