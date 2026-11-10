# What was removed on 2026-11-10: local projects, "keep local", and the second way in

**Commit:** `477a4d54` — *Only remote projects; a project that cannot reach its host says so*

**The operator's decisions, in their words:**

> *"when you add the address you clone from remote, when you change the address you keep the history and
> collisions are resolved by the agent on push. no extra dialogue needed"*

> *"user can set no remote url, but then his agent will not be able to push changes, and project should
> display that there is no connection"*

> *"if project's remote doent answer (there should be a check when you enter the project), then
> notification should tell that remote no longer responds and ask to set up the correct url"*

**This is the record of what went, and of the one question that replaced it.** The code is deleted, not
commented out; git holds it.

---

## 1. What was removed

| removed | where | what it was |
|---|---|---|
| `NewProjectDialog` | `Dialogs.kt` | a window with one field, beside the add window's two |
| `Pending.NewProject`, `NewProjectUI` | `MainActivity.kt` | the second door into making a project |
| `AddProjectDialog.onNewProject` | `Dialogs.kt` | the `＋ New project…` row above the address field |
| `isLocalProject` | `AppViewModel` | the app's answer to "can this project send anything?" |
| `keepProjectLocal` | `AppViewModel` | synced → local, as a person's act |
| `KeepLocalDialog` | `Dialogs.kt` | the window that promised nothing was deleted |
| `Pending.KeepLocal` | `MainActivity.kt` | its door |
| the row's `Go local…` item | `BrowserScreen.kt` | the same act, from the ⋮ menu |
| `ProjectGit.makeLocal` | `ProjectGit.kt` | `git remote remove origin` |
| `GitService.removeRemote` | `GitService.kt` | the same, one layer down; left with **no caller at all** |
| `ProjectGit.isLocal` | `ProjectGit.kt` | `remoteUrl(project).isNullOrBlank()`, and nothing else |
| `ProjectGit.makeRemote` | `ProjectGit.kt` | renamed `setRemote`: it is no longer "publish", because publishing is gone |
| the local-commit exception in `ProjectGit.stage` | `ProjectGit.kt` | *"a local project commits on save, because it has no push"* |
| 3 tests, 2 scenario pictures | tests | the keep-local window, and the new-project window |

**Net:** 322 lines deleted, 305 added across 12 files — the additions being the one replacement question,
its notice, and the tests for both.

---

## 2. Why each one went

**"New project" and "Add from an address" were one window.** Both asked for a name; only one asked for an
address. Once an address is optional they are the *same* window, and keeping two entry points for it is
the duplication this codebase has been removing all year. There is now one door, and it is the `+` button.

**The app cannot create a repository on a host, and should not try.** Making one is a host's own API call
(`POST /user/repos`), not git protocol — and the portability rule is that the app speaks only the
protocol. So a project arrives by cloning, which is also what makes its history the host's history rather
than a second, unrelated one that would have to be reconciled later.

**"Keep local" was a workflow for a state that is now a fault.** The operator's sentence settles it: a
project with no address is allowed, but it *cannot send*, and the app must say so. That is one fact about
a project, not a mode with its own verbs, its own window, and its own promise that nothing is deleted. The
verbs are gone; the fact is `unreachableOf`.

**The local-commit exception was the exception that proved the rule was wrong.** *"We commit on push"* is
right for every project with a remote. A local project had no push, so its history would never grow — and
that was the argument for committing on save. With no local projects as a kind, the rule is simply the
rule, and `ProjectGit.stage` stages.

---

## 3. What replaced it: one question

```kotlin
fun unreachableOf(project: String): String? =
    hostSaidNo[project]
        ?: if (remoteUrlOf(project).isNullOrBlank()) {
            "No address is set, so nothing can be sent from this project."
        } else {
            null
        }
```

Two cases, and the cheap one needs no network: **no address** is read from the repository's config, so the
project list can say it for every row without touching a socket. **An address that did not answer** is
remembered from the last `ls-remote`, which runs when a project is entered (`enterBranch`) and when an
address changes (`setAddress`).

**Everything that used to ask `isLocalProject` asks this instead**, and that is the point — there is one
answer, so there is nothing for a second one to disagree with:

| reader | what it does with the answer |
|---|---|
| the project row | shows **no connection** where the count was |
| the strip (`ProjectSlimRow`) | becomes a notice with a **Set the address…** button |
| `sync` | refuses with the reason, in the reason's own words |
| `canPublish` | a release needs a host that answers |
| `Scenario.sendIfWaiting` | does not press Sync on a project that would refuse |

**Only failures are stored.** Absence means *nothing is wrong* — either the host answered or nobody has
asked yet — and both draw the same way, the ordinary count. A remembered "it answered" from ten minutes
ago would be exactly the lie the check exists to stop; a remembered failure is what the person needs to
see until something retries.

---

## 4. What a person sees

**A project whose remote has gone** (deleted, renamed, moved — measured with a real bare repository that is
then removed):

```
┌─ groceries ────────────────────────────────────┐
│ The remote at /…/vanishing-remote did not       │
│ answer. <why ls-remote said so>   [Set address…]│
├─────────────────────────────────────────────────┤
│ main          · 12 files                        │
└─────────────────────────────────────────────────┘
```

**The project still opens.** Its files are on the phone and are not the problem; refusing to open a folder
because a server is unreachable would take a person's own work away from them for a reason that is not
about the work. What the notice stops is the *claim* — the strip and its "up to date" — not the folder.

**Changing the address keeps the history**, and no window asks anything: the new address is written, the
new host is asked about itself, and the next push carries this phone's history there. If the two histories
disagree, the agent resolves it on the push, where a person can watch it happen. There is no clone and no
question, because there is nothing to choose: the work on this phone is the work.

**Making a project with no address** still works, and the sentence says what it costs:
*"Made "scratch". It has no address, so nothing can be sent from it until you set one."*

---

## 5. What this deliberately does not do

- **No "push mine over theirs".** A new address whose history is unrelated makes the next push fail with a
  remote's non-fast-forward refusal. That is the agent's problem to resolve, per the operator, and adding
  a force-push button would be a second, more destructive answer to the same question.
- **No check on every screen.** The host is asked when a project is entered and when an address changes.
  A poll would be a network call per redraw for an answer nobody asked for.
- **No remembering across launches.** `hostSaidNo` is in memory. Reachability is a fact about *now*; a
  stored "it was down yesterday" would be a claim about today.
