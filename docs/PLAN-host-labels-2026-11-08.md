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

## 8. The next plan, named so it is not lost

The PAT door — *name + host + key*, checked by `ls-remote` rather than by an API — is what makes a
non-GitHub account exist. It needs, in order: `Account.host` (this plan), a name field that `nameProblem`
validates, `GitService.clone` moving onto `GitCredentials` (the last GitHub-shaped guard), and
paste-a-URL add. Recorded here rather than drafted, because it depends on step 2 of this plan landing.
