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
| `GitHub.kt` | 1762 | the whole client: identity, repos, licences, CI, environments, secrets | **deleted** |
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
| `pullRepository(repo)` | clone from a chosen `Repo` | the URL field, same code |
| `renameProject` | `PATCH /repos/…` — renames on the host, repoints the remote | rename the folder only; the remote is the person's to change |
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

| # | Step | Why here |
|---|---|---|
| **V1** | **The account is name + host + credential** (`PLAN-host-labels` §8, S1–S6) | everything below needs an account that is not GitHub's |
| **V2** | **`Secrets.transportProblem`** (SSH refusal) + `GitService.clone` losing its own host guard and taking a decided token (U1–U3) | the last GitHub-shaped guard in the clone path |
| **V3** | **Add a project by URL, checked with `ls-remote`** + `createLocalProject` reachable from the `+` (U4–U6) | this is the feature the operator asked for |
| **V4** | **The picker leaves**: `listRepositories`, `pullRepository(repo)`, `AddProjectDialog`'s list | the list is the vendor feature; the URL replaces it |
| **V5** | **A release is a tag**: `git {op:"tag"}`, `ReleaseDialog` keeps the field, no dispatch | replaces the biggest remaining GitHub feature |
| **V6** | **The CI surface leaves**: `ci`, `runs`, `artifact`, `CiSetup`, `Workflows`, `ReleaseKey`, `Signing`, `TokenScopes`, `exportArtifact`, `checkSigning`/`fixSigning`/`SigningRow`, `renameProject`'s host call | deletion, after V5 so nothing is lost that a tag now covers |
| **V7** | **`GitHub.kt` and `githubFor` are gone**; the tests that pinned their behaviour are deleted with their subjects, not adapted | the file cannot linger as a "just in case" |

**V6 is the big one and it is last on purpose.** Every one of those features is currently reachable by a
press, so deleting them first would take working buttons away while the replacement was unbuilt.

## 7. The one decision left: the device flow

`DeviceFlow.kt` is GitHub's OAuth. It is the only way to get a token without asking a person to make one, and
it is **not in the path of any universal feature** — it only produces a token, which the account then stores
like any other.

- **Keep it** as one door of `+ Add` — *"sign in with GitHub"* beside *"a key and a host"*. Cost: a GitHub
  file the app does not need; benefit: no token-typing for the common case. Nothing else depends on it.
- **Delete it** for a single door. Cost: a GitHub user must make a classic token by hand (the app already
  supports pasting one).

**Recommendation: keep it**, on the grounds that it is a *credential acquisition* method rather than a
feature, it is already tested, and deleting it makes the app worse for the one host it works with today — but
this is the operator's call, and the plan works either way.

## 8. What the app is after this

- **Accounts** are `login@host` with a credential. Any host.
- **Projects** are folders with a repository. A remote is a URL the person supplies; the app checks it with
  `ls-remote` and remembers it.
- **Branches, history, editor, agent, search, LFS** — unchanged, because none of them ever asked a host
  anything.
- **Releases** are tags.
- **Everything a host's API offers** — repository listing, creation, CI, artifacts, environments — is the
  agent's business through `http`, and the person's through the host's own website.

**The honest sum:** about 3,466 lines and 20 call sites leave, two features are replaced by git itself
(clone-by-URL, tag-as-release), and one convenience (the repository picker) is lost for a field that works
everywhere. Nothing that the git protocol can express is lost at all.
