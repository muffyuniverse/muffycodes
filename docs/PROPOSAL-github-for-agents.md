# Letting the agent use GitHub: workflows, artifacts, Downloads, and a vault

*Proposal, 2026-10-31. Written to be refused as much as accepted. Every GitHub fact below was measured
against the real API from this machine today, and every Android constraint was checked against this
repo's manifest and the platform docs — not estimated.*

---

## 0. The short answer

Four things are asked for, and they are **not one feature**. They have different risk, different
answers, and one of them is a security boundary rather than a convenience:

| What | Verdict | Why |
|---|---|---|
| **Run workflows, read runs, fetch artifacts** | **Yes** — and it is the one that recreates today's loop | The agent already edits files; what it cannot do is *see what CI said*. This closes that. |
| **Artifacts → the app's own filesystem, opened in-app** | **Yes, and it is the real prize** | No permission, no Files app, no leaving the app. An APK in the app's folder can be installed from inside it. |
| **Any file or folder → Downloads from the 3-dot menu** | **Yes** | `MediaStore` already does this for the trace file. It generalises to a tree. |
| **A secrets vault the agent can *use* but never *read*** | **Yes, and it is the hardest part** | The operator's own reasoning is the specification: *"otherwise an agent can just print a secret inside a runner and export value as artifact even if he gets only the key."* That is exactly right, and it rules out the obvious design. |

**And the token question: accept both.** Classic is the shortest path; fine-grained is the safer one and
can do everything this app does — I checked, after first claiming the opposite. Details in §2, including
the correction.

---

## 1. What exists today

Measured, not remembered:

- `AgentTools` has **thirteen tools** and not one of them touches git or GitHub: `tree`, `read`, `edit`,
  `forget`, `create`, `delete`, `move`, `history`, `restore`, `background`, `reason`, `browse`, `shot`.
  The agent edits files; **the person pushes**. The prompt says so in as many words: *"Save is a commit
  on the phone. Nothing you do is pushed to GitHub; the person pushes."*
- `ProjectGit.sync(project, note)` commits everything, commits a marker, prunes, then pushes a tree
  with the unsynced paths omitted. All of it exists and all of it is the person's button.
- `GitHub.kt` speaks five API calls: identity, list repos, licences, create, rename, delete. It has a
  private `get(method, path, token, body)` that already sets `Accept: application/vnd.github+json`,
  `X-GitHub-Api-Version: 2022-11-28` and a `User-Agent`. **Every new endpoint below is one more call
  through that function** — no new HTTP plumbing.
- `DownloadsSink` writes `trace.log` into `Download/MuffyCodes/` through `MediaStore`, with **no
  permission**, because on Android 10+ writing your own file into a shared collection needs none.
- `Secrets.scrub` already masks `gh[pousr]_…` and `github_pat_…` shapes, so both token types are
  recognised as credentials by the code that exists.
- The sign-in dialog says: *"The key needs the **repo** and **delete_repo** scopes."* Those are classic
  PAT scopes. Fine-grained tokens have no scopes; they have per-repository permissions.

---

## 2. The token question — classic or fine-grained

The operator asked: *"we require a token of github but which: classic or fine grained? it isnt
mentioned but for full repo access we should accept only classic, or am I wrong?"*

### I got this wrong first, and the correction is the useful part

My first draft of this section said **classic only**, on the reasoning that `POST /user/repos` and
`DELETE /repos/{owner}/{repo}` cannot be scoped to a fine-grained token on a personal account. I checked
that against GitHub's own generated permission tables rather than against my memory, and **it is false**:

```
Repository permissions for "Repository creation"
    | POST /user/repos                          | write | PAT | ✓ |
Repository permissions for "Administration"
    | DELETE /repos/{owner}/{repo}              | write | PAT | ✓ |
    | PATCH  /repos/{owner}/{repo}              | write | PAT | ✓ |
```

Both are listed for fine-grained PATs, with the permissions named. `POST /user/repos` also appears in
the *endpoints available for fine-grained personal access tokens* list.

**So the answer to the operator's question is: you are not wrong that classic is the simpler fit, but
"we should accept only classic" would be a mistake.** Fine-grained can do everything this app does
today — it just needs the permissions named, and there is no scope string to paste.

### What each needs, exactly

| Capability | Classic | Fine-grained (repository permission) |
|---|---|---|
| Push commits | `repo` | **Contents: read and write** |
| **Push to `.github/workflows/*.yml`** | `repo` + **`workflow`** | **Workflows: read and write** |
| Trigger a workflow / read runs | `repo` (`workflow` to dispatch) | **Actions: read and write** |
| Download artifacts and job logs | `repo` | **Actions: read** |
| Rename a repository | `repo` | **Administration: read and write** |
| Delete a repository | **`delete_repo`** | **Administration: read and write** |
| Create a repository | `repo` | **Repository creation: read and write** |

**The one place classic is genuinely different is `.github/workflows/`.** A classic token needs the
`workflow` scope to add or update a workflow file — GitHub's own words: *"Grants the ability to add and
update GitHub Actions workflow files. Workflow files can be committed without this scope if the same
file (with both the same path and contents) exists on another branch."* Fine-grained has the equivalent
as **Workflows**, so neither type avoids the decision; both make you opt in.

### The recommendation

**Accept both. Say which one was pasted, and what it cannot do.**

The prefix answers it with no round trip, and `Secrets.TOKEN_SHAPE` already matches both:

- `github_pat_…` → fine-grained
- `ghp_…` → classic

Then:

1. **At sign-in**, a fine-grained key gets a sentence naming the permissions it needs, phrased as the
   GitHub UI names them ("Contents: read and write, Actions: read and write, Workflows: read and write,
   Administration: read and write") rather than as scopes, which a fine-grained token does not have.
2. **A missing capability is refused at the button, not by a 403.** GitHub answers a fine-grained token
   without the permission with `Resource not accessible by personal access token`, which names neither
   the permission nor the cure. The app knows which act needs which permission, so it should say so.
3. **Update the dialog's text.** It currently says *"The key needs the **repo** and **delete_repo**
   scopes"* — true only of a classic token. It should offer both routes, because a person who wants a
   token that cannot delete their repositories should be able to have one and still use the app.

**What I would still tell the operator plainly:** a classic token is the shortest path — one token, two
scopes, everything works. A fine-grained token is the safer one and now costs only a list of
permissions. Neither is refused.

---

## 3. The GitHub tools — what the agent gets

Four tools, not one. A single `github` tool with a `do` key would be a command language invented inside
a command language, and the existing `KEYS` table — which is what makes an unknown key a *refusal*
rather than a guess — would have nothing to check.

### 3.1 `ci` — what happened, and what is in it

```
ci(path: "workflows/build-and-release.yml", ref: "development", event: "workflow_dispatch",
   inputs: {...}, wait: true, timeout: 900)
```

Two jobs in one tool, because they are one question — *what did my change do?*:

- **No `event`** → **read**: list the recent runs for this repository (or this workflow), newest first,
  with status, conclusion, head SHA and run id. This is what answers "did my push go green".
- **`event: "workflow_dispatch"`** → **run**: `POST /repos/{o}/{r}/actions/workflows/{file}/dispatches`.
  Note the path takes the **file name** (`build-and-release.yml`), not the workflow id — a detail worth
  putting in the example, because it is the one everybody gets wrong first.
- **`wait: true`** → poll `GET /repos/{o}/{r}/actions/runs/{id}` until it leaves `queued`/`in_progress`,
  bounded by `timeout`. The existing `Deadline` already does this shape.

**The wait is the dangerous part and it is not a new kind of danger.** The app already has a
`background` tool whose whole design is "a command that outlasts the wait is restarted in the
background and the turn is told where the result will be". CI is exactly that: **a `ci` call with
`wait: true` is handed to `background` like any long command**, and the turn gets the run id
immediately with the outcome arriving later. Nothing new is invented; the existing rule is reused,
which is the only way a second mechanism of "work continues after the turn" stays honest.

### 3.2 `runs` — the logs of one run, as text

```
runs(id: 41175547433, job: "Test (0)", grep: "FAILED", from: 1, to: 40)
```

`GET /repos/{o}/{r}/actions/runs/{id}/jobs` then
`GET /repos/{o}/{r}/actions/jobs/{job_id}/logs` (which 302s to a signed URL — see §5, the same trap as
artifacts).

**A log is enormous and this is where the app's existing tools pay off.** A job log is 20–60 KB of
timestamps and ANSI colour codes. Returning it whole would eat the context in one call. The existing
`read` tool already has `from`/`to`/`grep` and a `MAX_RESULT` ceiling that writes the overflow to a
file — so **`runs` should write the log into the project under `agent/ci/` and answer with a path plus
the matching lines**, exactly as a large `read` does. The agent then greps it with the tool it already
knows. No new result shape.

### 3.3 `artifact` — list and fetch

```
artifact(run: 41175547433, name: "dev-apk", into: "agent/ci", unzip: true)
```

- `GET /repos/{o}/{r}/actions/runs/{id}/artifacts` → id, name, `size_in_bytes`, `expired`.
- `GET /repos/{o}/{r}/actions/artifacts/{id}/zip` → **302 to a pre-signed blob URL.**

**The 302 is the single most important finding in this proposal, and it is a trap:**

```
GET .../artifacts/13033181102/zip   with Authorization: Bearer <token>
  → 302  https://productionresultssa30.blob.core.windows.net/...

GET <that URL>  WITH the Authorization header forwarded
  → 401  (397 bytes)

GET <that URL>  WITHOUT it
  → 200  13,871,548 bytes
```

Azure rejects a request that carries a bearer token it did not issue. `HttpURLConnection` **follows
redirects by default and keeps the request headers**, so the obvious implementation downloads a 401 body
and, if it is not checked, writes a 397-byte "APK". **The download must set `instanceFollowRedirects =
false`, read `Location`, and fetch it with no `Authorization` header.** This is measured, not guessed,
and it is the kind of bug that ships as "the APK is corrupt".

### 3.4 `downloads` — out of the app and into the phone

```
downloads(path: "build/app/outputs/…/app-release.apk")     // one file
downloads(path: "agent/ci/dev-apk")                        // a folder
```

This is the operator's *"add an option for files and folders to be uploaded to the downloads via 3dot
icon"*, and it is the same act from two directions — a tool for the agent, a menu item for the person.
Both call one function. **A person's menu item and an agent's tool that do the same thing must be the
same code**, or one of them will be right and the other will drift.

---

## 4. Inside the app's own filesystem — the best of both worlds

The operator's insight, and it is the part worth building first because it needs **no new permission,
no new dependency, and no new API**:

> *"if we can upload artifacts directly inside the muffycodes filesystem and open them without exiting
> — then it will be the best of two worlds! imagine clicking an apk to install from within the app
> itself"*

**An artifact fetched into the project is a file in the app's own folder.** Everything the app already
does to files then applies to it: it appears in the browser, it has history, it can be copied, it can be
opened. The chain the operator describes — *fetch → appears in the tree → tap → install* — is not a
feature to be built on top; it is what falls out once §3.3 writes into the workspace instead of into a
cache.

### Installing from inside the app

Two platform facts, both checked:

1. **`REQUEST_INSTALL_PACKAGES` is required**, and the user must also grant "install unknown apps" to
   *this* app. The manifest has neither the permission nor a `FileProvider` today, so both are new.
2. **The signing certificate must match.** The dev and release APKs already share
   `A6:45:2F:A1:55:84:…:7A:84`, which is the whole reason an update can install over the running app.

**What can go wrong, said plainly:** an APK that does not share the certificate fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` and there is nothing the app can do about it. That is not a bug to
fix, it is the platform protecting the person — and the app should say so in words ("this build is
signed with a different key, so it cannot replace the one installed") rather than showing the code.

**And it must never install itself silently.** `PackageInstaller` can install without a prompt only
with a permission no ordinary app has. What is available is *open the system installer with this file*,
which is a prompt the person answers. That is the correct amount of friction for replacing the app they
are looking at.

### Opening other artifacts without leaving

A `.png` fetched from CI should open in the app's own viewer. The app already renders images, text and
Markdown in the browser and editor — so **"open" is the browser's existing preview, keyed on the
artifact's type**, not a new screen. A `.zip` is the one that needs thought: unzipping into a folder
beside it is the honest behaviour, and it makes a whole test-report tree browsable.

---

## 5. The secrets vault — the hard one

The operator's requirement, in their words:

> *"we probably need a secrets vault kv storage with interface in settings so that agents could use
> commands that use secrets (but never direct access, otherwise an agent can just print a secret inside
> a runner and export value as artifact even if he gets only the key)"*

**This is the correct threat model and it kills the obvious design.** The obvious design is: store
secrets in the keystore, give the agent a tool to read one, let it pass the value to a command. That
design is defeated by exactly the attack described — read the secret, `reason` it into the
conversation, or `browse` to a URL containing it, or write it into a file that gets committed. **Any
tool that returns the value is a tool that leaks it**, and no amount of "don't print it" in the prompt
is a control.

### The rule: the value never enters the conversation, the process, or a file

A secret is referenced **by name** and substituted **at the point of use, by the app**:

```
ci(path: "…/deploy.yml", event: "workflow_dispatch", inputs: {"target": "prod"},
   secret: "DEPLOY_TOKEN")
```

The app reads the vault, and — this is the whole design — **the substitution happens inside the app's
own HTTP client, in the header, on the way out**. The value is never:
- in the tool's return string,
- in the conversation,
- in a file in the project,
- in a log line (the app's own `Secrets.scrub` runs on every answer already).

### Why "commands that use secrets" needs a narrower reading

The operator wrote *"agents could use commands that use secrets"*, which suggests passing them to a
shell. **On this phone there is no shell** — `background` runs app-internal work, not a command line.
So the only real consumers of a secret are:

1. **A GitHub workflow input** (§5 above) — `workflow_dispatch` inputs are *not* secret; they are
   visible in the run. **So a secret must NOT be passed as a dispatch input.** It must go to a
   repository *secret* (`PUT /repos/{o}/{r}/actions/secrets/{name}`, which needs the repository's public
   key and libsodium sealed-box encryption) — or better, **the workflow reads it from `secrets.*` and
   the app never touches it at all.**
2. **An HTTP request the agent asks for** — a header, on the way out, never in the answer.

**This is the design's most important consequence: the vault's job is to *put* a value somewhere, never
to hand one back.** So the tool surface has no "get" at all:

| Act | Tool | Returns |
|---|---|---|
| Store a secret | *settings UI only, never a tool* | — |
| Use a secret in a request header | `browse` with `secret: "NAME"` | the page, scrubbed |
| Set a repository secret for CI | `secret(name, value: from vault)` | "stored" — never the value |
| List names | `secrets()` | **names only**, never values |

**Listing names is safe and necessary**: an agent that cannot see which secrets exist cannot use any of
them, and a name is not a credential.

### Storage

`EncryptedSharedPreferences` / the keystore, like the GitHub token already is (`CredentialStore` says
the key "never leaves the keystore, so the token cannot be read without the device"). The vault is the
same mechanism with a map instead of one value, plus `FLAG_SECURE` on the settings screen that shows it
— **which the app already does** for `showSettings` and the two key dialogs.

**The one thing the vault cannot protect against** and should say so: an agent that is *allowed to use*
`DEPLOY_TOKEN` in a header can point that header at a server it controls. That is why every use is
**named in the trace** (`secret used: DEPLOY_TOKEN for POST api.example.com`) and why the vault's UI
shows *where each secret has been used* — a permission you cannot audit is a permission you cannot
revoke with any confidence.

---

## 6. What this costs, and what it risks

**The honest risks, because a proposal that lists none is a sales pitch:**

1. **`workflow` scope is the ability to rewrite CI.** An agent that can push to `.github/workflows/`
   can make the build run anything, including something that exfiltrates every secret the workflow can
   see. **Mitigation: the app's lock.** `agent/` is already git-excluded and `.github/` can be added to
   the app's own ignores so a workflow change is *the person's act* — and if the agent must be able to
   do it, it must be a separate, loudly-named permission in settings, off by default.
2. **Artifacts are code.** A downloaded APK is an executable, and an agent that can fetch one and open
   it is an agent that can ask the person to install something. The system prompt is the only defence,
   and it is a weak one. **Mitigation: an artifact is never auto-opened**; it lands as a file, and
   installing is a tap the person makes.
3. **A 302 with a token attached leaks the token to a third-party host.** This is the §3.3 bug in its
   most serious form: forwarding `Authorization` to `blob.core.windows.net` hands GitHub's token to
   Microsoft's storage. It is refused (401), but the *token was sent*. **`instanceFollowRedirects =
   false` is a security control here, not an optimisation**, and `Secrets.isGitHubHost` should be the
   guard that makes it structural rather than remembered.
4. **CI runs cost money and time**, and an agent that can dispatch a workflow can start a build on every
   turn. **Mitigation: a rate limit per turn** — one dispatch per turn, named in the trace — the same
   way `MAX_REQUESTS` bounds the model.

**Rough size**, from what the existing code looks like: `GitHub.kt` grows by ~250 lines (six endpoints,
one redirect-aware download); `AgentTools.kt` by ~300 (four tools, `KEYS`, `specs`); a `Vault.kt` of
~120; `Downloads.kt` generalisation ~80; the 3-dot item and its dialog ~60; tests ~600. **About a
week of the pace this repo has been moving**, with the redirect bug and the vault the two places it
could go wrong.

---

## 7. What I would build first, and why

**Order matters, and the order is chosen so each step is useful alone:**

1. **Artifact → workspace → tap to open** (§3.3 + §4). No permission, no vault, no new risk. It makes
   the app able to *receive* what CI produced, which is the half of the loop that does not exist.
2. **`ci` read + `runs`** (§3.1, §3.2). The agent can now *see* whether its change passed, which is
   what "recreate the workflow" mostly means.
3. **`downloads` + the 3-dot item** (§3.4). Small, independent, useful on its own.
4. **`ci` dispatch** (§3.1). The agent can start a build. **This is where a rate limit belongs.**
5. **The vault** (§5), last, because it is the one that needs the most care and benefits from the
   audit trail the earlier steps create.

**Step 1 alone answers the operator's best sentence** — *"clicking an apk to install from within the
app itself"* — and needs nothing from GitHub but one download.

---

## 8. The operator's answers, and what they change

All four questions were answered inline. Three confirm a direction; **one reverses mine, and it is the
one that changes the shape of the whole thing.**

### 8.1 Push: person → `main`, agent → `development`

> *"can we make it so that person pushes to main, while agent pushes to dev?"*

**Yes, and it is nearly free — the workflow already implements it.** Measured from
`.github/workflows/build-and-release.yml`:

| Ref | tests | release build | deep checks (lint) | publishes a release |
|---|---|---|---|---|
| `refs/heads/main` | ✓ | ✓ | ✓ | **✓** |
| `refs/heads/development` | ✓ | ✓ | ✗ | ✗ |
| `workflow_dispatch` on **any** ref | ✓ | ✓ | **✓** | ✗ |

So `development` is already exactly the lane the operator described: tested, built, an APK uploaded as
`build-bundle` for seven days, and **nothing published**. The agent pushes there and the person's `main`
stays the only door to a release.

**The mechanism is already in `GitService.pushWithout`, which pushes by explicit refspec:**

```kotlin
.setRefSpecs(RefSpec("${commit.name}:refs/heads/$onto"))
```

`onto` is `remoteBranch(repo, local) ?: local` — so **the destination branch is a parameter, not a
constant.** The change is: `ProjectGit.sync` keeps `onto` for the person's button; the agent's push
passes `"development"` explicitly. One argument.

**Three things to get right, and they are the reason this is not a one-liner:**

1. **The agent must never be able to name `main`.** Not "should not" — *must not*. A refspec built from a
   tool argument is one typo or one prompt injection from a release. The destination should be a
   constant in the tool, not a key: `push(branch)` does not exist; there is `push()` and it goes to the
   agent's branch. If the person's branch is `main` and the agent's is `development`, the app can even
   refuse to start the agent push when they are the same branch.
2. **The agent's commits are on the phone's `HEAD`, which is the person's branch.** Pushing `HEAD`'s
   derived commit to `refs/heads/development` is what `pushWithout` already does for the omitted-paths
   case, so the technique exists — but the *local* branch and the *remote* branch now differ on purpose,
   and `remoteBranch(repo, local)` would answer `main`. The agent's push must not consult it.
3. **`development` must not be a protected branch**, or the agent's push fails with a 422 that reads like
   a bug. If it *is* protected, that is the person's decision and the app should say so in words.

**What this does not give the agent: `main`.** Which means it cannot publish, cannot tag, and cannot
overwrite what a person approved. That is the right shape — and it makes the agent's lane *safe to be
generous in*, which matters for the next answer.

### 8.2 `.github/` is writable, and the runner is the sandbox

> *"writable, this is our version of 'bash' command - agent executes scripts and deploys artefacts there.
> We should turn it into a powerfull tool with root permission to the agent (because github runner is the
> ideal sandbox)."*

**Accepted, and the operator is right about the important part: the runner is the sandbox.** This app has
no shell — that is why `background` is not a shell — so "run this program" has no honest in-app answer.
A GitHub runner *is* the answer: a disposable Ubuntu machine that executes a script and hands back its
output, its exit code and its files. `workflow_dispatch` + `runs` + `artifact` is a shell with a
two-minute latency and a much better blast radius.

**But two things must be said plainly, because they are not what "sandbox" suggests:**

1. **The runner is a sandbox for the *machine*. It is not a sandbox for the *repository*.** A workflow
   file is not data the runner processes — **it is the thing the runner obeys.** So "the agent can write
   `.github/`" does not mean "the agent can run code safely"; it means "the agent can change what this
   repository's CI does, for every future push, including the person's". That is a *persistent*
   capability, where running a script is a *transient* one.

2. **This directly contradicts §5, and the contradiction has to be resolved rather than papered over.**
   §5's whole design is that a secret's value never reaches the conversation, a file, or a process. An
   agent that can write `.github/workflows/*.yml` can write:

   ```yaml
   - run: echo "${{ secrets.ANYTHING }}" > out.txt
   - uses: actions/upload-artifact@…   # …and hand it back to itself
   ```

   — which is **precisely the attack the operator named** in the vault requirement: *"an agent can just
   print a secret inside a runner and export value as artifact even if he gets only the key."* The vault
   as designed does not stop this, because the secret is not in the vault's gift — it is in
   `secrets.*`, and the workflow is what reads it.

**So §5's guarantee needs one more clause, and it is the honest version of the operator's own idea:**

> **An agent-authored workflow may not reach repository secrets.** Either it runs with no `secrets.*`
> at all, or the app pins the workflow it will dispatch to a **hash approved by the person**, and refuses
> to dispatch a file that changed since. The agent can then write `.github/` freely — it just cannot make
> the *secrets* flow to a file it wrote.

That is a real restriction and it is worth naming as one. It is also the only version of "root permission
to the agent" that survives the operator's own threat model.

**A third, smaller consequence:** the person's `main` still publishes. If `.github/` is agent-writable,
then the agent's change to a workflow takes effect **on the person's next push to `main`** — the
sandbox's output is reviewed by nobody. A workflow change should therefore be visible as a *change* (it
already is: it is a file in the tree, it has history, it appears in `changes waiting`) and the app should
call it out by name, because a person who sees "3 changes waiting" will not notice that one of them is
the build.

### 8.3 Both directions, and the `+` menu already has half of it

> *"both, and I want a full circle, to upload and to download (including folders - and we already do this
> partly in + command menu button)."*

**Correct — the inbound half exists and is well built.** `Imports.filesInto` / `Imports.folderInto` take
system-picker `content://` URIs and copy them into a folder: folders are followed to `MAX_DEPTH = 32`,
capped at `MAX_FILES = 5_000`, names are cleaned, collisions are numbered, and **nothing runs on the UI
thread** (a 40 MB APK copied inline would be an ANR). The outbound half is `DownloadsSink`, which writes
`trace.log` into `Download/MuffyCodes/` through `MediaStore` with no permission.

**So "the full circle" is: `Imports` in, `Downloads` out, and the same two acts as agent tools.** The
work is smaller than it sounds:

- **Out, for a folder**, is the one genuinely new piece. `MediaStore` writes one file at a time, so a
  folder becomes *n* inserts, which is why it wants a progress count and a cap. A tree that fails
  halfway must say which files landed rather than claiming success.
- **In, from Downloads**, already works — but a file picked *by the agent* (an artifact) does not come
  through a picker. That is why §3.3 writes straight into the workspace and then §8.3 offers it outward:
  the agent's inbound path and the person's inbound path share the *destination*, not the *source*.

### 8.4 Fine-grained tokens, with capabilities the tools check

> *"accept and make sure that app tools can handle scoped permission"*

**Accepted, and the second half is the real requirement.** "Handle a scoped permission" means the app
must know, per act, which permission it needs — and answer before GitHub does:

| Act | Permission | What the app says without it |
|---|---|---|
| push commits | Contents: write | "This key cannot write files in `owner/repo`." |
| push `.github/workflows/` | **Workflows: write** | "This key cannot change workflow files." |
| dispatch / read runs | Actions: write / read | "This key cannot run workflows." |
| artifacts, job logs | Actions: read | "This key cannot read what the run produced." |
| rename / delete repo | Administration: write | "This key cannot delete repositories." |
| create repo | Repository creation: write | "This key cannot create repositories." |

**A fine-grained token's actual grants are readable from the API**, which makes this check real rather
than guessed: `GET /repos/{owner}/{repo}` returns a `permissions` object (`admin`, `maintain`, `push`,
`pull`, `triage`), and a failed call returns `X-Accepted-GitHub-Permissions` naming what it wanted. So
the app can answer *"this key can push but cannot run workflows"* from one request instead of from a
sentence in a dialog. **The permission table above is the fallback for when the header is absent.**

The dialog's text then stops being a scope list at all. It becomes: *paste a key; the app will tell you
what it can do* — which is true for both token types and is the only version that stays correct when
GitHub adds a permission.

---

## 9. What I would build first, now that §8 is answered

The answers reorder the plan. **§8.2 is the one that must come first, because it is the one that can
hurt**: a writable `.github/` with `secrets.*` reachable is the operator's own attack, and building the
runner-as-shell before the guard would be building the vulnerability.

1. **`ci` read + `runs`** (§3.1, §3.2) — read-only, no `.github/` write, no secrets. This is where the
   agent learns to *see* CI, and it is safe today.
2. **The workflow guard** (§8.2) — dispatch pinned to a person-approved hash, or no `secrets.*`. Small,
   and it is the precondition for everything after it.
3. **`.github/` writable + `ci` dispatch** (§8.2) — the runner as the agent's shell, with the guard from
   step 2 already in place.
4. **Agent push to `development`** (§8.1) — one refspec, but it needs the branch constant and the
   protection check, so it belongs after the agent has something worth pushing.
5. **Artifact → workspace → tap to open, and `downloads` both ways** (§3.3, §3.4, §8.3) — no permission,
   no risk, and it closes the loop the operator asked for.
6. **The vault** (§5) — last, and now with a precise job: it is the thing that keeps §8.2's power from
   becoming §5's attack.

**The dependency worth staring at:** step 3 without step 2 is the vulnerability. That is why they are two
steps and not one.
