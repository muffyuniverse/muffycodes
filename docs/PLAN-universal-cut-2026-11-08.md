# PLAN — the universal cut: what the git protocol can do, and everything else leaves

**Written 2026-11-08**, from the operator's decision:

> *"I dont need anything github specific - if listing of account repos is vendor specific then we dont need
> this, we should rely on what git protocol allows, and in that case we can ask user to add remote urls on
> his own via + button, and we just check with git ls-remote command that is part of the protocol. Same for
> other features."*

This is the direct successor to `PLAN-portable-2026-11-08.md` (which named the two boundaries) and
`PLAN-host-labels-2026-11-08.md` (which makes the account carry its host). **This file is the removal list**,
and it supersedes the "typed GitHub tools become the first user of `http`" phase: they are not re-homed, they
are **deleted**, and an agent reaches a host's API with `http` and a vault secret if it needs to.

## 1. The test — one question per feature

> **Can the git protocol do this?**

- **Yes** → it belongs in the app, and it works on every host that speaks git.
- **No** → it is a host's API. It leaves the app. The agent may still do it with `http` plus a secret bound
  to that host; the *person* does it on the host's own website.

That is the whole rule. It replaces "which hosts support X", which was the question that made the app
GitHub-shaped in the first place.

## 2. What the protocol can and cannot carry — measured

**Can** (JGit's transport, host-agnostic, already used):

| | |
|---|---|
| `clone`, `fetch`, `push` | the three the app uses, with a credential |
| `ls-remote` | **branches, tags and commit ids without cloning** — `org.eclipse.jgit.api.LsRemoteCommand` is in the 7.8.1 jar, verified |
| objects, refs, history, merge | everything the app's own features read |
| LFS | over HTTP, endpoint derived from the remote URL (`LfsClient.endpointOf`) — the *path convention* is git-lfs's, not GitHub's |

**Cannot** — no git command exists for any of these, on any host:

| | why |
|---|---|
| list the account's repositories | `ls-remote` needs a repository to ask about; there is no "list" verb in the protocol |
| create / delete / rename a repository | server-side administration |
| CI runs, job logs, artifacts | a host's automation, not git |
| releases, tags-as-releases, signing keys | a host's publishing, not git |
| environment secrets | a host's configuration store |

**So the app's feature list becomes:** projects (a folder + a repository), branches, history, editor, agent,
search, LFS, and **a remote URL per project that the person supplies and the app checks**.

## 3. The inventory — 3,466 lines and 20 call sites

| file | lines | what it is | verdict |
|---|---|---|---|
| `GitHub.kt` | 1762 | the whole client: identity, repos, licences, CI, environments, secrets | **deleted** except `identity()` (`GET /user`), which the device door keeps (§7.1) |
| `Workflows.kt` | 386 | GitHub Actions files, approvals, reach | **deleted** |
| `ReleaseKey.kt` | 388 | sends a project's signing key to a GitHub environment | **deleted** (the key file stays; sending was the GitHub part) |
| `DeviceFlow.kt` | 343 | GitHub OAuth device flow | **keep or delete — the one decision, §7** |
| `LfsClient.kt` | 290 | LFS over HTTP as the protocol defines it | **kept** |
| `CiSetup.kt` | 190 | creates workflows, environments, secrets on GitHub | **deleted** |
| `Signing.kt` | 184 | secret names, environment restrictions, delete-secret | **deleted** |
| `Release.kt` | 120 | dispatch the release workflow, name rules | **replaced** by a **git tag** (§5) |
| `TokenScopes.kt` | 93 | GitHub's `x-oauth-scopes` check | **deleted** (it reads a header only GitHub sends) |

Call sites in `AppViewModel` (each is a feature a person can press):

| call site | GitHub thing | universal replacement |
|---|---|---|
| `listRepositories` → `AddProjectDialog`'s list | `GET /user/repos` | **the URL field** (§4) |
| `createProject` | `POST /user/repos`, then clone | **`createLocalProject`**, which already exists and is tested but has **no UI caller** — then the URL field |
| `makeRemote` (already built) | nothing GitHub — it takes a URL | kept, and gains the `ls-remote` check: *add on remote* means **point at one**, not create one (§8.1) |
| `deleteProject(…, everywhere)` | `DELETE /repos/…`, already removed on 2026-11-08 | one choice, plus **Go local** (`git remote remove`), which is the universal half (§8.2) |
| `pullRepository(repo)` | clone from a chosen `Repo` | the URL field, same code |
| `renameProject` | `PATCH /repos/…` — renames on the host, repoints the remote | **the address is the verb**: the person renames on the host, pastes the new URL, and the folder follows what `ls-remote` accepted (§8.3) |
| `addProjectToGitHub` | `POST /user/repos` for a local project | the URL field, or nothing — a local project is a valid project |
| `exportArtifact` / `onRowExport` | `GET …/actions/artifacts` | deleted; `http` for an agent, the host's site for a person |
| `startRelease` / `ReleaseDialog` / `canPublish` | dispatch `build-and-release.yml` | **`git tag` + push** (§5) |
| `checkSigning` / `fixSigning` / `SigningRow` | environment secrets | deleted |
| `githubFor` (`:506`) and its ten uses | "do I have an API client" | deleted with the client |
| agent tools `ci`, `runs`, `artifact` | 4 tool bodies, ~450 lines | deleted; `http` + a vault secret is the replacement |

**What is *not* in this list and matters:** the account, the workspace, the worktree layout, the agent, the
index, `GitCredentials`, `RepoLocks`, `LfsClient`, `Worktrees`, `Workspace`. **None of them asks a host
anything** — that is why the cut is 6% of the app rather than half of it.

## 4. Adding a project: the URL, checked by the protocol

The operator's exact proposal, and it is the right one:

1. `+` → **Add a project** → one field: the remote URL, and a name (prefilled from the URL's last segment).
2. **Check it before the clone**, with `LsRemoteCommand` on that URL plus the account's credential:
   - reachable and authorised → the branches it reports are shown, then `cloneInto`
   - `401`/`403` → *"`gitlab.com` refused the key for this account. The key is tried here, not when the
     account is added."*
   - `404` → *"there is no repository at that address"* — which is the sentence a mistyped path needs
   - unreachable → the network's own words
3. **A local project is the other half of the same button** — `createLocalProject` already exists, is
   tested, and has no caller. "New project" makes the folder and the repository; the URL is how it gets a
   remote, now or later.

`ls-remote` is also the **only honest way to validate a key** for a host with no user endpoint, which is why
it moves from "nice check" to "the check": it answers *"can this credential read this repository"*, using
the protocol, on every host.

**Refused before it starts:** an `ssh://` or `scp`-style `git@host:path` address — this app packages no SSH
transport, and `Secrets.hostOf` happily answers a host for it, so the refusal must be explicit and must come
before any credential is chosen.

## 5. A release becomes a tag

**`git tag` + `git push --tags` is the universal release.** Every host understands a tag; what a host then
*does* with it (a release page, a build, an artifact) is that host's business.

So `git` gains a `tag` op — create a tag at `HEAD`, optionally annotated, then push it (the push already
exists and is refspec-bound). `ReleaseDialog`'s version field stays; what changes is that the number becomes
a tag name, and there is no dispatch.

**What is lost, said plainly:** the app can no longer produce a signed APK of the *user's* project, and it
cannot tell you what a host built from your tag. That was GitHub Actions wearing a feature's clothes. An
agent that wants a build dispatches it with `http` and a secret for that host.

## 6. The steps, in order

**Progress, 2026-11-08:** V1, V2, V3, V4 and V5 are landed and CI-green; V6 is half done (the
agent's `ci`/`runs`/`artifact` tools are gone, the CI/signing files and the view model's actions
are not yet); V7 has not started. The `**state**` column below is the truth, not the plan.

| # | Step | Why here | State |
|---|---|---|---|
| **V1** | **The account is name + host + credential** (`PLAN-host-labels` §8, S1–S6) | everything below needs an account that is not GitHub's | **landed** |
| **V2** | **`Secrets.transportProblem`** (SSH refusal) + `GitService.clone` losing its own host guard and taking a decided token (U1–U3) | the last GitHub-shaped guard in the clone path | **landed** |
| **V3** | **Add a project by URL, checked with `ls-remote`** + `createLocalProject` reachable from the `+` (U4–U6) | this is the feature the operator asked for | **landed** |
| **V4** | **The picker leaves**: `listRepositories`, `pullRepository(repo)`, `AddProjectDialog`'s list | the list is the vendor feature; the URL replaces it | **landed** |
| **V5** | **A release is a tag**: `git {op:"tag"}`, `ReleaseDialog` keeps the field, no dispatch | replaces the biggest remaining GitHub feature | **landed** |
| **V4b** | **The two doors become one process** (§7): `acquire(): Result<String>` with a device implementation and a typed one; `addAccount(login, host, gitUser, token)` is the single save; `signInWithToken` and `signIn` collapse into it | a convenience on top of the shared process, not a second process | **landed** (as step 4 of V1) |
| **V6** | **The CI surface leaves**: `ci`, `runs`, `artifact`, `CiSetup`, `Workflows`, `ReleaseKey`, `Signing`, `TokenScopes`, `exportArtifact`, `checkSigning`/`fixSigning`/`SigningRow`, `renameProject`'s host call | deletion, after V5 so nothing is lost that a tag now covers | **half** — the tools have gone; the files have not |
| **V6b** | **The address is the verb** (§8.3): *Set the address* runs `ls-remote`, then renames the folder and every branch's tree to the URL's last segment when the name differs. `renameProject` becomes this and nothing else | renaming stops being an app operation and becomes a verified consequence of the address | **landed** |
| **V6c** | **Go local** (§8.2): `GitService.disconnect` + one project action + its sentence | the universal half of "remove on remote", and a state the app already models | **landed** — `GitService.removeRemote` already was it; the sentences now name the address instead of GitHub |
| **V7** | **`GitHub.kt` and `githubFor` are gone — except `identity()`**, which moves into the device door because *a door knows its own host* (§7.1); the tests that pinned the deleted behaviour are deleted with their subjects, not adapted | the file cannot linger as a "just in case", and the one call that survives has a home | not started |
| **V8** | **`deleteProject` becomes one choice**, with the sentence naming the host and saying nothing there was touched (§8.2) | the second choice stopped being an act on 2026-11-08, and is now a sentence on every host | not started |

**V6 is the big one and it is last on purpose.** Every one of those features is currently reachable by a
press, so deleting them first would take working buttons away while the replacement was unbuilt.

## 7. The device flow: a convenience on top of the same process — **settled**

**The operator, 2026-11-08:** *"keep github device flow as a separate door option - but it still should be
funneled through the same process as any other registration (it is just a convenience on top of the shared
process)."*

So the door is **kept**, and its job is narrower than it looks: it acquires a token. It does **not** create an
account, does not decide scopes, and does not know what a project is. What it replaces is the person typing a
key.

The shared process, which every door goes through:

| # | step | what it means |
|---|---|---|
| **1** | **a name** | the account's folder (`accounts/<login>`), checked by `Workspace.nameProblem`, and refused in a sentence if a login of that name already exists (§`PLAN-host-labels` §5) |
| **2** | **a host** | the hostname the credential binds to. For GitHub's door it is `github.com` — the same field, defaulted rather than assumed |
| **3** | **a credential** | *or* a **key acquisition**, which is the only thing a door may differ in. `acquire(): Result<String>` has two implementations: the device flow, and "the person typed one" |
| **4** | **one save** | `AppViewModel.addAccount(login, host, gitUser, token)` — the same function for both doors: `accounts.save(Account(…, host, gitUser))`, `credentials.setToken(login, token)`, `adoptProvisional(login)`, set active, `forgetBranches()`, `backToMain()` |

### 7.1 The GitHub door fills every field it can know — and therefore keeps one API call

**The operator's refinement, 2026-11-08:** *"can device flow be used to fill in name and host as well? we already
know that github flow is about github, and name can be requested in one line with the token - we can automate
this window so that for github device flow fills in all the account registration fields and for other hosts user
does it manually."*

Yes, and it is the argument for what a door *is*: **a door knows its own host.** So for GitHub's:

| field | where it comes from | the manual door |
|---|---|---|
| **host** | `github.com` — the door's definition | typed |
| **name** | `GET /user` → `login` — *"one line with the token"* | typed, `nameProblem`-checked |
| **commit name / email** | the same response (`name`, `email`, else `noreplyEmail`) | typed, optional |
| **credential** | the device flow | typed |

So the GitHub door is **two presses and no fields**: start, approve, done. The other door asks for four.
Both then take the identical step 4.

**This is a correction to the inventory in §3:** `GitHub.kt` cannot be deleted wholesale, because
`identity()` (`GET /user`) is what a door needs to know who it just signed in. It is **not** the app's GitHub
client — it is the door's own lookup, and it moves into `DeviceFlow`/the door adapter when the client goes. It
is the *only* survivor: `listRepos`, `createRepo`, `renameRepo`, `cloneUrlFor`, `licences`, `runs`, `jobs`,
`logs`, `artifacts`, `environments` and the secret calls all go.

**What disappears:** `signInWithToken` and `signIn` collapse into step 4, because they are the same act with a
different way of getting the key. `TokenScopes.judge` disappears with `TokenScopes` (V6) — no other host sends
`x-oauth-scopes`, so a check that only one door can perform is not part of the shared process. And step 3's
device implementation keeps GitHub's own consent screen as its scope check, which is where that check belongs:
GitHub asks the person, at the moment the token is minted.

**What does not change:** the device flow's polling, its outcome handling, and the fact that a refusal keeps the
dialog open with the reason in it. Those are the parts that were hard to get right and they are already right.

## 8. Adding, removing and renaming a project — what actually breaks

This is the section the operator asked for, and it is the most consequential part of the cut, because it is
where a *folder* and a *repository* stop being the same thing.

### 8.1 Adding — solved, and there is a seam worth naming

| case | universal answer |
|---|---|
| a **remote** project | the URL field, checked with `ls-remote`, then `cloneInto` (§4) |
| a **local** project | `createLocalProject` — exists, is tested, and has no UI caller today |

So nothing is missing. What is worth seeing first: **a local project is a project, and a remote project is a
clone of one.** The folder is the repository in both cases; the difference is whether `origin` exists. That is
already the app's model (`isLocalProject` = "no remote"), so the two add paths converge immediately after
creation and nothing downstream cares which one was used.

**The seam:** a local project has no `origin`, so it has **no host either**, and therefore no `name@host`
label. Under the operator's "show the host always" rule it must render as `scratch` with no suffix — which is
correct rather than an exception: there is no host to name. The moment a URL is added it gains both.

**Precondition, and it is not optional:** the name is a folder name, so `nameProblem` decides it — the remote
may be `my/repo` and a slash cannot be a folder. The last segment is a prefill the person can correct, not an
answer.

**"Add on remote" has two meanings, and only one of them is git's.**

| | possible? |
|---|---|
| **create a repository on the host** | **no** — `POST /user/repos` and its cousins. Every host has its own form of it, none of them is git |
| **point a project at a remote that already exists** | **yes** — `remote.origin.url` + the fetch refspec, then `ls-remote` proves the address and the credential, then `push` |

The second is a complete replacement for the first, because **every host has a web page for making a
repository** and a person is already looking at it. So the flow is: make it there, paste the URL, the app
verifies and takes over — with the licence choice, the description and the visibility happening where the host
puts them, which is also where those questions have real consequences.

`ProjectGit.makeRemote(project, url)` already exists for exactly this (a local project gaining a remote). It
gains the same `ls-remote` check as the clone, so a mistyped or unauthorised address is refused before the
project starts claiming it is published.

### 8.2 Removing — the biggest change in the whole cut, and it is a *good* one

Today `deleteProject(project, everywhere)` does two things at once:

1. **always**: `credentials.forgetProjectSecrets`, `Worktrees(ws).removeAll(project)`, `ws.delete(projectDir)`
2. **when `everywhere` was chosen**: `api.deleteRepo(...)` — and that was **already removed** on 2026-11-08
   (§`PLAN-portable` §2), because `delete_repo` was the one irreversible capability in the app. The remaining
   sentence already says the repository is still there.

So removing a project **already means removing it from this phone**, and the dialog's second choice is a
sentence rather than an act. After the cut it becomes one choice, and the sentence gets better: *"`shopping` is
gone from this phone. Its repository is at `gitlab.com` and nothing here touched it."* — which is true on every
host, and was only ever approximately true for GitHub.

**The part to be careful about:** deleting the folder removes the only copy of anything never pushed. The app
already refuses to pretend otherwise — `deleteProject` is guarded against `system`, and unsent work is counted
on the row — but the cut must not lose the ordering that makes it safe: **secrets, then worktrees, then the
folder**, because the reverse leaves a window where the folder is gone and the secrets are not.

**"Remove on remote" also has two meanings, and one of them is git's.**

| | possible? |
|---|---|
| **delete the repository on the host** | **no** — and it is already gone from the code: `delete_repo` was dropped on 2026-11-08 because it was the one irreversible capability in the app. It stays a sentence |
| **disconnect: the project stops having a remote** | **yes** — `git remote remove origin`. Universal, reversible, and it is a state the app already has a name for |

So the project actions become three, and each says exactly what it did:

| action | what it does | what it says |
|---|---|---|
| **Go local** | removes `origin` (and its fetch refspec); the repository and every version stay | *"`shopping` is local now. It still has its versions; it no longer has an address."* |
| **Remove from this phone** | what `deleteProject(…, everywhere = false)` does today | *"gone from this phone. Its repository is at `gitlab.com` and nothing here touched it."* |
| **Set the address** | the URL field for a project that has one, or a new one | verified with `ls-remote` first |

`GitService` needs one new function beside `setRemoteUrl` — `disconnect(repo)`: `unsetSection("remote",
"origin")` and save. `ProjectGit.isLocal` already answers `true` for a project with no remote, so every screen
that says "local" keeps working with no further change.

**Why this is the right split:** deleting a person's work is the app's own business (the folder is here); deleting
a *repository* is a server-side act with no undo, and the host's own UI has it behind a confirmation the person
cannot misunderstand.

### 8.3 Renaming — **dropped as an operation**, and replaced by "the address changed"

**The operator's decision, 2026-11-08:** *"your option 3 on rename is good one, user renames remote and only then
renames local to the name remote holds."*

So renaming is not a verb in the app any more. It is a **consequence** of the address changing, and the order
is the opposite of the one the code has today:

| # | who | what |
|---|---|---|
| 1 | **the person** | renames the repository on the host — its own website, its own confirmation |
| 2 | **the person** | pastes the new URL into the project (*Set the address*) |
| 3 | **the app** | `ls-remote` with that project's credential — the address works, or a sentence says why |
| 4 | **the app** | derives the name from the URL's last segment, checks `nameProblem`, refuses a clash, then renames the folder and every branch's tree |

**Local always follows remote, and the app never guesses a URL template.** That is the whole gain: today
`renameProject` computes the new address with `api.cloneUrlFor(...)`, which is GitHub's convention for
composing a URL — GitLab's and Gitea's differ, and a table of them is exactly what this cut removes.

**What is deleted:** `api.renameRepo(...)` and the `cloneUrlFor` follow-up. **What stays, unchanged:**
`ws.rename(projectDir, clean, tidy = false)` and `Worktrees.rename(project, clean)` — the delicate half (git's
four bookkeeping files, nested branch names, the shared lock) that never asked a host anything.

**The name after a rename is derived, not typed** — from the URL the person just supplied, which is also what
makes "the local name and the remote name agree" true by construction rather than by discipline. If they want a
different local name from the remote's, they keep the current name and simply change the address.

**A guard worth writing down:** if the derived name equals the current one, nothing is renamed — only the
address changed, which is a normal thing to do (a repository moved to another owner or host). The sentence then
says so rather than reporting a rename that did not happen.

**What must never happen:** silently rewriting `origin` and leaving a project that cannot be pushed. That is the
outcome that keeps a row saying "up to date" while nothing can be sent.

### 8.4 What the cut must not lose

- **`Worktrees.rename`** stays exactly as it is — it is the part of renaming that is delicate (git's four files,
  nested branch names, the common directory) and it never asked a host anything.
- **The ordering in `deleteProject`** stays: secrets, then worktrees, then folder.
- **`isLocalProject`** keeps its meaning, and gains a second one: a project with no remote has no host, so it
  has no label suffix.
- **The `system` folder** is still not a project, still cannot be deleted or renamed, and still carries no host.

## 9. What the app is after this

- **Accounts** are `login@host` with a credential. Any host.
- **Projects** are folders with a repository. A remote is a URL the person supplies; the app checks it with
  `ls-remote` and remembers it.
- **Branches, history, editor, agent, search, LFS** — unchanged, because none of them ever asked a host
  anything.
- **Releases** are tags.
- **Everything a host's API offers** — repository listing, creation, CI, artifacts, environments — is the
  agent's business through `http`, and the person's through the host's own website.

- **Projects have three verbs, all git's**: add (clone a URL, or make one locally), *Set the address*
  (`ls-remote`, then the folder follows the name the remote holds), and *Go local* (`git remote remove`).
  Creating and deleting a **repository** stay the host's own business — every host has a page for both, and
  deleting one has no undo.

**The honest sum:** about 3,466 lines and 20 call sites leave; three features are replaced by git itself
(clone-by-URL, tag-as-release, and *Go local*); renaming becomes a consequence of the address rather than a
verb; and one convenience — the repository picker — is lost for a field that works everywhere. Nothing the git
protocol can express is lost at all, and the one API call that survives (`GET /user`) survives because a door
must know who it just signed in.

**What is lost and cannot be replaced:** the app can no longer create a repository on a host, delete one, or
say what a host built from your tag. Each is a sentence naming the host and what to do there.
