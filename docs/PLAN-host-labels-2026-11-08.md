# PLAN — the host in the label, and the host as data

**Written 2026-11-08.** The operator's decisions, quoted because they are the whole specification:

> *"I propose to use composite only for presentation, account name and other names stay as they are"*
>
> *"I think that we need to show host suffix all the time for clarity."*

So: **stored names do not change.** The folder stays `accounts/<login>`, a project's folder stays its bare
name, `nameProblem` is untouched, `Worktrees`, agents, paths and traces all keep the plain names. And the
**host is a field on the account** — data, not decoration — because it is the fact that decides whether the
account's token may be offered to a remote.

`login@host` and `name@host` appear **only in what a person reads**.

## 1. What exists, measured

| fact | where | shape |
|---|---|---|
| an account is `login` + commit identity + `dev` | `AccountStore.Account`, written to `.muffy/account.json` | no host anywhere |
| the account's folder is the login | `AccountStore.dirOf(login) = accounts/<login>` | a host could collide here — see §5 |
| the token is a bare string | `Credentials.token(login)` | no host, no username |
| "is this the account's own host?" | `GitCredentials.isAccountHost` → `Secrets.isGitHubHost` | **a constant**, which is the last hardcoded host in the credential path |
| a project's remote | `ProjectGit.remoteUrl(project)` → git config `remote.origin.url` | per project, correct, host-agnostic |
| a per-project fact on the list | `changesByProject: Map<String, Int>`, filled by `refreshChanges()` on a worker | **the pattern to copy** — not a read during recomposition |
| project rows | `BrowserScreen` `:944`, and its second line at `:960` | `Text(name, …)` |
| account rows | `BrowserScreen:287` | `Text(account.login, …)` |
| the accounts empty state | `BrowserScreen:267` | *"An account is a GitHub identity"* — becomes false |
| the chat/notification identity | `AgentSession.notice()` `:594`, `"$project · $branch"` | no host |

**The line that decides the design:** a project's host is only knowable from its repository, so it must be
collected with the other per-project facts and cached — never resolved while a row is being drawn.

## 2. The label, in one place

A new `Labels.kt`, no Android, tested alone — the same reason `GitCredentials` and `TokenScopes` are separate
files: it is a rule about strings, and it is the part that can be wrong.

```text
Labels.account(login, host)  →  "someone@github.com"      // always, per the operator
Labels.project(name, host)   →  "shopping@github.com"     // always, when there is a remote
                             →  "shopping"                // a local project: there is no host to show
Labels.hostOf(url)           →  "github.com" | null      // reuses Secrets.hostOf, so no second parser
```

Three rules it enforces, each of which a call site would otherwise get wrong:

1. **The host is shown always** for an account and for a published project. Not "when it is ambiguous" —
   the operator asked for clarity, and a label that changes shape depending on its neighbours is a label
   nobody can learn to read.
2. **A local project has no host, so it has no suffix.** `.muffy`'s own folder is `system`, which is not a
   project and never gets one either. Adding `@` with nothing after it would invent a host.
3. **The dev account is labelled with where its work actually is.** `DiskGitHub.cloneUrlFor` answers a
   `file:` URL, so `Secrets.hostOf` is empty and the honest label is `test-account@this phone`. A dev
   account claiming `github.com` would be a lie the person could act on.

## 3. The account's host becomes data

1. `Account(login, commitName, commitEmail, dev, host = "github.com")` — a default, not a migration: every
   account that exists was made by GitHub's door, so the default is a statement of fact rather than a
   compatibility shim (§13b.5b).
2. `AccountStore` writes `host` and reads it with the existing `text(json, key)` helper, whose blank
   fallback is `"github.com"` — a file written before the field existed reads as what it is.
3. `GitCredentials.isAccountHost(host, remoteUrl)` takes the host instead of consulting
   `Secrets.isGitHubHost`. That constant then survives only where GitHub's **API** is genuinely what is
   being called (`listRepos`, `createRepo`, workflow runs, `cloneUrlFor`) — functions that are GitHub's, not
   git's, and which a non-GitHub account will not use at all.
4. `ProjectGit.credentialFor` passes the account host down. One extra parameter, and the decision stops
   being a constant.

**A test that fails without (3):** an account whose `host` is `gitlab.com`, given a `github.com` remote and
no `git` secret, must answer *"no secret for github.com"* rather than offering the account's token. Today it
offers the token, because `isGitHubHost` says yes and the account's host is unrecorded.

## 4. Wiring the surfaces

| surface | change | why it is worth it |
|---|---|---|
| accounts list (`BrowserScreen:287`) | `Labels.account(...)` | the row that says who you are should say where |
| project list (`BrowserScreen:944`) | `Labels.project(name, hostByProject[name])` | two hosts can hold the same repository name |
| `hostByProject` | filled in `refreshChanges()`'s worker alongside `changesByProject`, from `ProjectGit.remoteUrl` | the same pass, the same thread, no per-row I/O |
| branches header (`BranchesScreen`) | the project title gains the host | it is the second screen a person sees the name on |
| chat header / `AgentSession.notice()` | `"$project@$host · $branch"` | two worktrees on two hosts would otherwise be two identical notifications |
| accounts empty state | *"An account is an identity on a git host"* | the current sentence becomes false the moment a second host exists |
| traces (`Trace.note`, `Trace.tap`) | **unchanged** | a trace line names a folder, which is what a diagnostic needs; the host is in the row a person reads |

`AgentSession` does not currently know a host, so `notice()` needs one — the cheapest honest source is the
project's remote at the moment the session is created, or `Labels` called by whichever layer builds the
notification. Decided here: **`AgentService.notify` composes the label**, because it already has a
`Workspace` and the notification is the only consumer.

## 5. The collision that presentation cannot fix

Two accounts with the same login on different hosts both map to `accounts/<login>`. One folder, two
identities: the second sign-in would adopt the first's workspace, its projects and its history — data
mixing, not a display problem.

So: **refused in a sentence at add time.** *"An account called `someone` already exists here, for
github.com. A second host needs a different name."* Storage stays plain (the operator's rule), and the
refusal names the action that works. `AccountStore.dirOf` keeps answering one folder per name; nothing
silently merges.

## 6. Order

1. **`Labels.kt` + tests** — the rule first, because five surfaces will call it and a rule written after
   five call sites is five rules.
2. **`Account.host`** — field, store, default, `GitCredentials.isAccountHost`, the falsifying test in §3.
3. **`hostByProject`** in the refresh pass, and the five surfaces wired.
4. **The collision sentence** at add time.

Steps 1–2 are the whole of the operator's two sentences. Step 3 is where the label becomes visible. Step 4
is the one thing that must be in place *before* the PAT door ships, so the door has a refusal to give.

## 7. What this deliberately does not do

- **It does not enable a second host.** Nothing here can add an account whose host is not `github.com`;
  that is the PAT door (§8), and until it exists the suffix reads `@github.com` on every row — which is the
  operator's own instruction, and honest: it is where the work is.
- **It does not touch stored names**, so `Worktrees.rename`, `fileAt`, the agent's paths and the worktree
  layout are all unchanged. `@` never becomes a folder name.
- **It does not parse its own labels.** A repository can legitimately be called `repo@gitlab.com`, so the
  label is for reading; nothing may split it.
- **It does not change `nameProblem`.** `@` is already a legal character there, and every other rule
  (slashes, Windows reserved names, length) still applies to the name itself.

## 8. Step 5 — the PAT door: an account is tied to a host

**The operator's decision, 2026-11-08:** *"when you add an account you define what host it is tied to so
that it is part of account's identity"*, and the accounts screen is *"a list of name@host choices to tap"*.

So `+ Add` gains a second door beside *Sign in with GitHub*:

| field | rule | why |
|---|---|---|
| **name** | `Workspace.nameProblem` **and** not already a login on this phone | it is the account's folder (`accounts/<login>`), so it obeys the folder rules; a duplicate is refused in a sentence (§5) |
| **host** | a hostname: no scheme, no path, no spaces; a pasted URL is refused with *"the host is `gitlab.com`; the address goes in the project, not here"* | this is what a credential binds to, and a person will paste a URL into it |
| **git username** *(optional)* | free text, blank means "the token is the username" | the one non-universal part of cloning over HTTPS (see §9); it is **data on the account**, not a table of hosts in the code |
| **key** | non-empty, shown as dots, never traced | — |

**What cannot be checked, and is therefore said rather than faked.** GitHub's door validates a key against
`GET /user` and its `x-oauth-scopes` header. No other host has an equivalent, and there is no git command
that answers "is this key valid" without a repository to ask about. So the door says, in its own
supporting text: *"the key is tried the first time it is used"*. The refusal then comes from the first
clone, as a sentence that names the account, the host and which of 401 / 404 / unreachable it was — not as
a red line in a dialog that pretends to have checked.

### 8.1 The steps

| # | Step | Where |
|---|---|---|
| **S1** | `Account(login, commitName, commitEmail, dev, host = "github.com", gitUser = "")`; `save`/`read` round-trip both (`text()`'s blank fallback is the default, so a file written before the field existed reads as what it is) | `AccountStore.kt` |
| **S2** | `GitCredentials.isAccountHost(host, remoteUrl)` replaces `Secrets.isGitHubHost` **in the credential path only**; `ProjectGit.credentialFor` passes `account.host` down | `GitCredentials`, `ProjectGit` |
| **S3** | `githubFor` answers **null** unless the account's host is GitHub's (`github.com`), so a GitLab account does not get an `HttpGitHub` pointed at `api.github.com` and a 401 with no explanation. Ten call sites, each of which must then say *"this account is on `<host>`; that is GitHub's API"* instead of *"sign in first"* | `AppViewModel` |
| **S4** | `AppViewModel.addHostAccount(name, host, gitUser, key, onDone)` — no `identity()`, no `TokenScopes`, `Trace.note("host-account", "added · $host")`. It saves the account, stores the key at **account scope**, and sets it active | `AppViewModel` |
| **S5** | The dialog: `+ Add` offers two rows; the host door has the four fields above and the sentence from §8. Field shapes so a pasted URL into **host** is refused before anything is saved | `Dialogs.SignInDialog` + a new `AddHostAccountDialog` |
| **S6** | The labels: accounts row, project rows, branches header, chat and notification identity read `Labels` (§2). With one host on the phone every row reads `@github.com`, which is the operator's instruction and also true | five call sites |

**S4's scope decision, and it matters:** the key is stored **at account scope**, not project scope, because
a clone happens before a project exists — a project-scoped secret could never be present at the moment it
is first needed. The store's narrowest-first lookup still lets a project override it later.

**S6 is what a person sees, and S1–S3 are what makes it true.** None of them can be skipped: without S3 a
GitLab account gets GitHub's API silently; without S2 its own token is offered to the wrong host.

### 8.2 What an account on another host cannot do, said in words

These stay GitHub's, because they *are* GitHub's API and not git:

| feature | answer for an account on `gitlab.com` |
|---|---|
| add a project by choosing one from a list | the list is GitHub's; that account gets the URL field (§9) |
| create a project on the host | not offered; a project is made locally and given a remote |
| release, CI runs, artifacts, signing key | *"that is GitHub's API; this account is on `gitlab.com`"* |
| `TokenScopes` check | not run at all — it reads a header only GitHub sends |

## 9. Adding a project by URL — and how universal that really is

**The operator's question:** *"which is a part of git protocol and can work universally, right?"*

**Yes for the clone itself, and no for two of the things around it.** Measured, not assumed:

| part | universal? |
|---|---|
| cloning over HTTPS with a credential | **yes** — JGit's transport knows nothing about hosts |
| cloning from a local path | **yes** — and it is how the whole suite talks to a "remote" |
| **SSH** (`git@host:path`, `ssh://…`) | **no in this app**: `org.eclipse.jgit.ssh.*` is not a dependency, so there is no `SshSessionFactory` and the call fails with a null-pointer-shaped error. It must be **refused in words**, before the credential decision — and it does not fall out for free, because `Secrets.hostOf("git@host:path")` answers `host` quite happily |
| **the username form** | **no**: GitHub/Gitea take token-as-username, GitLab documents `oauth2:<token>`, Bitbucket uses `x-token-auth` or a user. Handled as **data**: the URL's userinfo (`https://x-token-auth@host/…`) or the account's `gitUser`; blank means token-as-username |
| **listing a host's repositories** | **no, and it cannot be**: there is no git command for it. `GET /user/repos` is GitHub's; GitLab uses `/api/v4/projects`, Gitea `/api/v1/user/repos`, Bitbucket `2.0/repositories/{workspace}` — different envelopes, clone fields, auth headers and paging. So the *picker* is GitHub-only and everything else uses the URL |
| branches, history, commits, LFS pointers | **yes** — refs and objects, no host involved |

### 9.1 The steps

| # | Step | Where |
|---|---|---|
| **U1** | `Secrets.transportProblem(url)` — *"this app sends over HTTPS only. Use `https://<host>/<owner>/<repo>.git`"* — asked by `cloneInto` and `makeRemote` **before** `GitCredentials`, so no token is ever considered for an address this app cannot speak to | `Secrets.kt` |
| **U2** | `GitService.clone` loses its own `isGitHubHost` refusal and takes an already-decided token like `push`/`pull`/`sync` do (P1 §4). This is the last GitHub-shaped guard in the tree | `GitService` |
| **U3** | `ProjectGit.cloneInto` asks `credentialFor(project, url)` — the same decision as the other three verbs. Note the ordering: the project folder does not exist yet, so only the account and shared scopes can answer, which is why S4 stores at account scope | `ProjectGit` |
| **U4** | `AppViewModel.addProjectFromUrl(url, name, onDone)`: name prefilled from the URL's last segment, through `nameProblem` and the existing "already a project on this phone" refusal (the same one `pullRepository` uses), then `cloneInto` | `AppViewModel` |
| **U5** | `AddProjectDialog` gains the URL field and the name field. For a GitHub account the list stays and the URL is the second way in; for any other account the list is absent and the URL is the only way | `Dialogs` |
| **U6** | `makeRemote(project, url)` (a local project getting a remote) asks U1 and U3 too, so all **four** ways a URL is typed share one decision: clone, add-remote, pull, push | `ProjectGit`, `AppViewModel` |

**The name is asked for, not assumed**, because `nameProblem` refuses slashes and the remote may be
`my/repo`; the last segment is a prefill a person can correct. And a name already on this phone is refused
rather than replaced — the rule `pullRepository` already follows, for the same reason: deleting a project
to import a second copy is one act the person already knows.

### 9.2 What this makes possible, and what it does not

- **Possible:** an account on any host; a project cloned from any HTTPS address, including a self-hosted
  server, with that account's key; every existing feature (history, branches, agent, LFS, search) working
  unchanged, because none of them asks a host anything.
- **Not possible, and now said out loud rather than discovered:** browsing a host's repository list,
  creating a repository on the host, releasing, reading CI — all GitHub's API, and each answers with the
  account's host named.
- **Not solved:** a project whose remote needs a username the person did not set and that is not
  token-as-username. The symptom will be a 401 at the first clone; the message names the account, the host
  and the `git username` field, which is the actionable form.
