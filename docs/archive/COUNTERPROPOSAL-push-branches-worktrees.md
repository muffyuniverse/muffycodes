# Push, branches and worktrees — a counterproposal

Your design, as I understand it, in one paragraph:

> Every subagent gets a git branch and a worktree named after its id, inside its own `agent/` folder.
> That folder is the root of its world — its tools cannot read or write outside it. CI reads the caller's
> id and pushes that caller's work to a branch named after the id, then runs the pipeline against that
> branch, so the chat agent on CI pushes to `<its-id>` rather than to `development` and no naming
> conflicts arise in a repository that already has branches. Locally the chat agent works on `main`; only
> a person pressing Sync pushes local `main` to remote `main`. Every change to local `main` rebases the
> subagent branches so their worktrees stay current, and conflicts are the subagent's problem to solve,
> not the app's. The chat agent needs a command to merge a subagent's branch into local `main`.

I think the shape is right and three of the five parts are already half-built in this repository. What
follows is where I think it breaks, in the order the breakage would bite.

---

## 1. What is already here, so we are not designing from nothing

| your part | what exists now | gap |
|---|---|---|
| subagent has a branch | nothing — every agent writes into the same folder | all of it |
| subagent's folder is its world | `Scope.Project(name)` refuses any path outside the project; `AgentTools.insideWorktreeOrAgent` refuses `agent/` and dot-names | scope is **the project**, not a worktree, and it is a path check, not an OS boundary |
| CI runs on the caller's branch | `build-and-release.yml` has one trigger set, no caller input at all | all of it |
| local agent on `main` | the app has no concept of branches; `ProjectGit` commits to whatever `HEAD` is | no branch is ever named |
| rebase subagents on `main` change | nothing | all of it |
| chat agent merges a subagent | `subagent` tool has `do` / `task` / `id` — no `merge` | one verb |

So this is not a small change: it is the first time the app has a concept of *a branch*, and that concept
has to exist in `ProjectGit`, in `Scope`, in `AgentTools`, in the workflow file, and in the subagent
record. That is the cost, stated plainly, before the arguments.

---

## 2. The gap that matters most: `agent/` is inside the project, so a worktree there is inside git's own tree

You want the worktree in the subagent's `agent/` folder. Today `agent/` is the one folder git is told to
ignore — `APP_IGNORES = ["agent/", ".muffy/", "requests/"]` is written into `.git/info/exclude`, and
`insideWorktreeOrAgent` refuses writes into it precisely because it is the record, not the code.

A worktree at `agent/<id>/` is therefore:

- **invisible to git in the parent** — correct, and the reason it is a tempting place;
- **its own working tree with its own `HEAD`** — which is what a worktree is;
- **excluded from every backup, export, and sync path** the app has, because those all skip `agent/`.

That last one is the real cost. A subagent's uncommitted work would live in a folder the app treats as
disposable bookkeeping. If the app ever prunes `agent/`, or a person deletes it to reset a stuck agent,
the work goes with it. **A worktree is not bookkeeping.** I would put them at
`<project>/.muffy/worktrees/<id>/` and keep `agent/` for the record only — or, better, at
`<account>/.muffy/worktrees/<project>/<id>/`, outside the project entirely, so a subagent's tree cannot
be reached by a path that starts inside the project.

**This is the one place I would change the letter of your design**, because the failure it prevents is
silent data loss.
@ we can actually give the chat agent ability to read git commit log and see what commit included what (do we need to give a special command or agent can just search for it and read it in .git. also show in settings a list of subagents with their id, current status, whether its head is merged in local main and what project they belong to, and with ability to add new subagents and remove old ones. This way user can monitor what subagents have unmerged work and ask chat agent to investigate and merge if needed. and chat agent can see diffs between main and subagent's branch (we also need to give him that, and that is why I ask you whether we should give him full git cli or our subset is enough.

## 3. "Its tools cannot read or write outside it" cannot be enforced by the tools

This is the most important gap, and it is not a detail. The current `Scope` is a **path check inside the
app**: `Scope.Project` refuses a path outside the project. That works because every tool goes through
one door.

It will not hold for a worktree, for three reasons:

1. **`search` deliberately reaches outside.** The tool description says so in its own words: *"Searches
   every project in the account, not just this one."* A subagent whose world is its worktree must not
   have that tool, or must have a scoped variant — and the current `specs()` takes `scope` but does not
   use it to remove `search`.
2. **`browse` and `fetch` reach the network**, which is outside every folder. Fine, but it means "root of
   his world" is about the filesystem only, and the prompt has to say which.
3. **A worktree is a second checkout of the same repository.** If the subagent's tools resolve
   `../src/main/...` from inside its worktree they land in *its own* `src/`, which is correct — but if
   the app ever hands it an absolute path, or the project folder, the boundary is gone and nothing
   notices.

So: the boundary must be **a root the tool layer is constructed with**, not a predicate each tool is
trusted to call. Today `Scope.Project` is close to that; it needs to become `Scope.Worktree(root)` where
`root` is the worktree, and every path-taking tool must resolve against `root` and refuse a result that
escapes it after canonicalisation. That is a mechanical change, but it is the difference between a rule
and a sandbox.

**And say the honest thing in the prompt**: this is a guard against a mistake, not against a determined
agent. There is no OS-level jail here.
@ ok, implement this, but remember that for chat agents whole account is readable, and their particular project is editable. for subagents only their worktree is readable and editable.

## 4. CI cannot know "the caller's id" — this is the largest hole

You want CI to *"look at the id of the caller and push his work to his branch"*. CI has no caller. A
workflow run is started by a push, a dispatch, or a schedule — there is no process, no session, and no id
in the payload. The only things a run knows are the ref, the commit, and the inputs someone sent.

So "the caller's id" has to become something CI can read. Three ways, and they are not equivalent:

**(a) The branch name is the id.** A subagent pushes to `agent/<id>`; CI triggers on `agent/**` and reads
the branch name. This is what you actually want, and it needs no new mechanism — but it means **the push
happens from the phone, not from CI**, which contradicts the next sentence.

**(b) CI is told the id by `repository_dispatch`.** The app calls the API with
`{"event_type": "agent", "client_payload": {"id": "7", "branch": "agent/7"}}`, and the workflow reads
`github.event.client_payload.id`. This works today with no new workflow syntax. **But it needs a token
with `repo` scope to call the dispatch endpoint from the phone**, and the run then starts on the *default
branch's* workflow file, not on the subagent's — which is the trap in the next paragraph.

**(c) The id is an input to `workflow_dispatch`.** `gh workflow run --ref agent/7 -f id=7`. Simple, and
`--ref` is what makes the run use the subagent's code.

I would use **(c)**, because it is the only one where the run's code and the run's identity come from the
same ref. It also has a property you want: a person can start it by hand for a branch that has no phone
attached.
@ ok c

## 5. "Run the CI on his branch codebase" is true for the *code* and false for the *workflow*

This is the trap I would most want you to see before we build anything. **A GitHub Actions workflow always
comes from the ref the run is started on — for `push` and `workflow_dispatch` with `--ref`, that is the
subagent's branch. But for `repository_dispatch` and for `pull_request`, the workflow file comes from the
default branch.**

So under (b) the subagent's code would be tested by *the old pipeline*, and a change to the pipeline in a
subagent's branch would be silently ignored. Under (a) and (c) it works as you expect.

That single fact decides the mechanism, and it is why I am not offering (b).

## 6. Rebasing every subagent on every `main` change is where this design will hurt most

*"Every change in local main rebases subagents' branches so that their worktrees stay up to date. And
conflicts are subagent's problem to solve."*

The second sentence is right — an agent with its own worktree is the only party that can resolve a
conflict in it. The first sentence is where I would push back, on three counts:

1. **A rebase is not free, and it is not local-only.** Rebasing rewrites every commit's hash. A subagent
   that has pushed `agent/7` and is rebased now has a branch that has *diverged from its own remote*, so
   the next push needs `--force-with-lease`. The app already has `pushForce` for one narrow case; this
   would make forced pushes routine, and a force-push to the wrong ref is how work disappears.
2. **Every save on `main` would trigger N rebases.** The chat agent commits often — `commitOutcome` runs on
   every save. Ten subagents means ten rebases per save, each a lock, each a chance to fail, and the
   operator sees "busy" for a reason they did not cause.
3. **A conflict is not the subagent's problem until the subagent is awake.** An asleep subagent cannot
   resolve anything. So a rebase that conflicts has to leave the branch where it was, record that it is
   behind, and tell the *chat* agent — otherwise the subagent wakes into a broken tree and the failure
   looks like its own.

**What I would do instead:** rebase **lazily, when the subagent is about to work**, not eagerly when
`main` moves. Same guarantee — its worktree is current when it matters — without N rebases per save and
without rewriting pushed history while nobody is looking. If a rebase conflicts at that moment, the
subagent is awake, has its context, and can resolve it, which is exactly the division of labour you asked
for.
@ we can force merge main into subagent's worktree when chat agent writes to him.

Keep an eager *mark*: when `main` moves, note each branch's distance from it, so the chat agent can see
"subagent 7 is 12 commits behind" without a rebase having happened.

## 7. "Only the user, by pressing Sync, pushes local main to remote main" — one exception already exists

This is already almost true, and the exception is worth naming because it is the thing that will make the
rule look broken. `createProject` **pushes** the initial commit for a repository it just created, and
`sync` is what the Sync button calls. Both go through `ProjectGit.sync`. If the rule is "only a person
presses Sync", then `createProject`'s push is a violation that exists today.

It is a defensible one — the app just created the repository and the person pressed Create — but the rule
as stated does not permit it. Either the rule says *"only a person's act pushes, and Create is one"*, or
`createProject` stops pushing. I would keep the push and write the rule as: **the app pushes only on an
act a person took; the agent never pushes.**
@ create project is initiated by user so it fits

## 8. `agent/<id>` collides with a repository that already has branches — but not the way you fear

You said the naming avoids conflicts in a repository with a pre-existing structure. It mostly does, and
the exception is the one that will bite: **a project that already has a branch called `agent/7`** is
unlikely, but a project that already has a *tag* or a *remote branch* with that name is possible, and so
is a subagent id reused after retirement. `Sub.retired` exists, and a retired id keeps its number — so
`agent/7` is never reused within one phone. It can still collide across phones.

I would namespace it once: `muffy/agent/<id>`, so the app's branches are one prefix a person can list and
delete in one command, and a collision with somebody else's `agent/7` is impossible.
@ ok

## 9. The merge command is the part I would design most carefully

*"chat agent needs a command to merge subagent branches into local main."* This is the only place where
two agents' work meets, so it is where a mistake is expensive. What it needs to answer:

- **Which branch, and which commit?** `muffy/agent/7`, at whatever `HEAD` is — but a subagent may have
  pushed a *later* commit than its local branch, or be mid-rebase. Merging a branch that is moving is how
  a merge records a state nobody saw.
  @ merge happens after subagent reports back to chat agent and is based on chat agent's reason.
- **Fast-forward or a real merge?** A fast-forward loses the fact that the work came from a subagent. A
  merge commit keeps it, and the history then says which agent wrote what — which is the thing this whole
  design is for. **Merge commit, always**, with the subagent's id in the message.
  @ real merge.
- **What if it conflicts?** The chat agent is on `main` with the person watching; it can resolve, but
  resolving on `main` is riskier than resolving in a worktree. I would refuse and say which files, and
  offer to have the *subagent* do it — the same division you asked for in §6.
  @ yes, refuse on conflict, so that chat agent can write back to subagent to resolve the conflict himself.
- **Does it push?** No. It merges into local `main`; a person's Sync pushes. That keeps §7 intact.
@ agents need to push to their branches to run ci on them, how otherwise they will test teir own work.
- **Is it one tool or two?** `subagent {do:"merge", id:7}` is enough, and it should refuse while the
  subagent is working — merging a tree somebody is writing to is a race the lock does not cover.
  @ merge should be one tool

## 10. Smaller things that will otherwise surface as bugs

- **`ProjectGit` has no branch argument anywhere.** `sync`, `commitOutcome`, `push` all act on `HEAD`. The
  change is not just "name a branch": every one of those calls needs to know *which* tree it is in, which
  means the project folder stops being the only root the git layer knows.
- **JGit worktrees.** `org.eclipse.jgit 6.10` supports worktrees, but `ProjectGit` opens one
  `Repository` per project and holds it under a lock (`RepoLocks`). A worktree is a *second* repository
  handle onto the same object store. Two handles and one lock is fine; two handles and a lock keyed by
  folder is not — `RepoLocks.hold(repoDir(project), …)` would not serialise a subagent writing in its
  worktree against the chat agent writing in `main`. **The lock has to key on the common git directory,
  not the working folder.**
- **`APP_IGNORES` and the worktree.** `agent/` is excluded from the repository; a worktree inside it is
  invisible to the parent's git, which is right — but `.muffy/` is excluded too, so a worktree at
  `.muffy/worktrees/` has the same invisibility with a folder whose purpose is *app state*, which is more
  honest than the record's folder.
- **The subagent's prompt has to change.** Today `AgentPrompt.readScoped` builds the project layers. A
  worktree-rooted subagent needs to be told its root, that it is on a branch, and that it must not expect
  to see the chat agent's uncommitted work — because it will not.
- **What the subagent's `tree`/`search` return.** If its root is the worktree, `tree` shows a fresh
  checkout, not the project it remembers. A subagent woken after a rebase sees a different folder. That is
  correct and will read as a surprise unless the prompt says so.

---

## What I would build, in order

1. **`Scope.Worktree(root)`** and a worktree per subagent at `<project>/.muffy/worktrees/<id>/`, created
   on first use, with `Scope` refusing every path that canonicalises outside it. Remove `search` from a
   worktree-scoped subagent or scope it to the root. *No CI yet.* This alone makes "his folder is the root
   of his world" true.
2. **`ProjectGit` learns a branch**: worktree-scoped commits and reads, and `RepoLocks` keyed on the
   common git dir. *Still no CI.*
3. **Lazy rebase on wake**, with a recorded distance-from-`main` so the chat agent can see it. A conflict
   leaves the branch alone and tells the chat agent.
4. **`subagent {do:"merge", id}`**, merge commit, never pushes, refuses while the subagent is working.
5. **CI: `workflow_dispatch --ref muffy/agent/<id> -f id=<id>`**, with the existing pipeline running
   unchanged on that ref, and the release/publish steps gated to `main` exactly as they are now. This is
   the last step because it is the only one that cannot be tested without a real remote.

## The two questions I would ask before starting

- **Does a subagent push at all, or does only the chat agent?** If CI is started by a dispatch, the phone
  must already have pushed the branch — so *something* pushes from the phone, and it is either the
  subagent's own act or the chat agent's on its behalf. That decides where the "never push" rule lives.
- **What is the unit of a subagent's work — a branch, or a worktree?** They can drift: a worktree can be
  switched to another branch, and a branch can be checked out in two worktrees (git refuses, but the
  app's model has to know which one it means). I would say **the worktree is the unit and the branch is
  its name**, and refuse to create a second worktree on a branch that already has one.
