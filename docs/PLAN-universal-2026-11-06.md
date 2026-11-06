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

## 5. Part C — the provider config

`Door.kt` hardcodes one endpoint (`:845`) and pins three OpenRouter-only fields in the body (`:522`):

| Field | Who needs it |
|---|---|
| `provider.only` / `allow_fallbacks` | **OpenRouter only** |
| `reasoning.exclude` | **OpenRouter only** |
| `reasoning_effort` | OpenAI-compatible |
| `model`, `messages`, `tools`, `stream` | **everyone** |

The response reader expects the OpenAI shape, which Groq, Together, DeepSeek-direct, Fireworks, vLLM and
Ollama all speak. **So a config replaces exactly the provider-specific part:**

```json
{
  "endpoint": "https://api.deepseek.com/chat/completions",
  "headers": { "Authorization": "Bearer {{key}}" },
  "body": { "model": "{{model}}", "messages": "{{messages}}", "stream": true },
  "reads": { "content": "choices[0].delta.content",
             "tool_calls": "choices[0].delta.tool_calls" }
}
```

**What it buys:** any OpenAI-compatible endpoint, by editing one file. `provider.only` stops being Kotlin
and becomes a template field — a **deletion**.

**What it cannot do:** Anthropic's Messages API and Google's `generateContent` are structurally different —
different message shapes, different tool-call format, different streaming events. Those need a **response
parser**, not a template. The config covers the ~90% that copied OpenAI's shape, and the plan should say so
rather than implying "any provider".

**The cost that is easy to miss:** `Door.body` is currently pinned by a Kotlin test — its own comment says
*"the assembled body is the thing worth pinning rather than the call that wraps it."* Moving the body into
a config moves that guarantee from **compile time to runtime**. Therefore: **the config is validated on
load**, not on first use. A typo must be a refusal at the settings screen, not a mystery 400 mid-turn.

**Tests:** a valid config assembles the pinned body; a missing `endpoint` is refused on load; a template
naming an unknown variable is refused on load.

**Cost:** ~200 lines plus tests.

---

## 6. Part D — the `git` tool: named operations, not argv

The operator asked for `{ git: "arguments" }`. **JGit ships a CLI** (`org.eclipse.jgit.pgm`), so this was
researched properly rather than dismissed.

### 6.1 Why argv passthrough is refused

`{ git: "arguments" }` **is a shell by another name**: quoting, `-c`-style flags, `--upload-pack=…`
reaching an arbitrary binary, and a model asked to remember git's 200 flags. It also fails the app's rule —
a failure must say what happened, and argv hands back git's error text.

### 6.2 Why embedding `pgm` is refused

Measured against the version the app already pins (`6.10.1.202505221210-r`):

- `org.eclipse.jgit` and `org.eclipse.jgit.lfs` — **already in the APK.**
- `org.eclipse.jgit.ui` — **AWT/Swing.** 11 classes, all `awtui.*` (`SwingCommitList`, `CommitGraphPane`,
  `AWTPlotRenderer`). **Android has neither.**
- **Only two of `pgm`'s own classes touch it:** `Glog` (a Swing graph viewer) and `Main` (help plumbing).
  Every porcelain command is free of it — so it is *nearly* embeddable, which is what makes this worth
  recording rather than hand-waving.
- But it also drags in **Jetty** (an HTTP server for `git lfs serve`), a **second Apache HttpClient**, SSH
  transports and a GPG stack — all to run a terminal CLI this app has no terminal for.

### 6.3 What is built instead

```kotlin
"git" to setOf("op", "path", "into", "message", "revision", "branch")
```

`op` is a **closed set**, each mapping to JGit porcelain that already exists or is one call:

| `op` | JGit | Status |
|---|---|---|
| `status` | `GitService.status` | **already written** |
| `log` | `RevWalk` | already used by the history screen |
| `diff` / `show` | `DiffFormatter` | already used |
| `checkout` | `git.checkout()` | new |
| `revert` | `RevertCommand` | new |
| `stash` | `git.stashCreate()` / `stashApply()` | new |
| `blame` | `BlameCommand` | new |

**Why this beats argv:** the agent cannot reach a flag the app has not reasoned about, every refusal is a
sentence the app wrote, and every operation goes through `RepoLocks.hold` and the existing rules — the dirty
check, commit-first, LFS-before-refs. `git revert` on a locked path must refuse, and it will, because it is
the app's code rather than a subprocess.

**The honest limit:** this is not a git CLI. No `gc`, no `filter-branch`, no `rebase -i`. `filter-branch`
rewrites history, which this app has already decided against — `HistoryPrune` was deleted.

**Cost:** ~250 lines including refusals; one test per `op`.

### 6.4 The `git` tool is **local only** — and that is the whole point

This is the distinction the first draft of this plan left ambiguous, and it decides the shape of the tool:

| | Local history | The network |
|---|---|---|
| **What** | `status` `log` `diff` `show` `blame` `branch` `checkout` `revert` `stash` | `clone` `pull` `push` |
| **Who drives it** | **the agent**, through `git` | **the app**, through `pull` / `push` |
| **Touches a remote** | never | always |
| **Takes arguments** | yes (`op`, `path`, `revision`…) | **no** (`setOf()`) |

**`clone`, `pull` and `push` are NOT part of the `git` tool, and must not be added to it.**

Three reasons, each already paid for elsewhere in this plan:

1. **They are not one JGit call.** `ProjectGit.push` runs `LfsSync.push` **first** — because GitHub rejects a
   tree naming an LFS object it does not have (GH008) — then the ref push, then reports which half failed.
   A `git push` op would either duplicate that ordering or lose it.
2. **They hold a lock and obey rules a local op does not.** `RepoLocks.hold(dir, "push")`, the dirty check
   (*"a push sends commits, not files"*), and the commit-first rule. `git checkout` needs a lock; `git push`
   needs the whole ritual.
3. **The agent has no reason to name a remote.** It has exactly one project, one remote, one token. An
   `op: "push"` would take either no arguments — in which case it is `push` under a second name — or
   arguments that let the agent choose a remote, which is a capability nobody asked for and a way to send
   the operator's work somewhere unintended.

**So the tool list stays as it is:** `git` for local history, `state` / `pull` / `push` for the network.
Nothing is subsumed.

**And `clone` was never the agent's.** `ProjectGit.cloneInto` is a **UI** act — a person adds a project from
an existing repository. There is no `clone` tool and there should not be: the agent works inside a project
that already exists, and a clone that created one would need a name, a licence and a destination — three
answers the app's own add-project dialog already collects from the person.

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

**And Part D makes it urgent:** a `checkout` can change hundreds of files at once, and a list refreshed only
by taps would be stale for the entire time the agent works.

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
| **`provider.only` as Kotlin** | Becomes a template field in the provider config (§5) — a deletion, not an addition. |

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
| **5** | **C — provider config** (§5) | ~200 lines | Independent of 1–4; do it when a second provider is wanted. |
| **6** | **D — the `git` tool** (§6) | ~250 lines | Last, because it needs the refresh hook (1) to be visible and `checkout` makes staleness worst. |
| **7** | one real call by hand against the GitHub API | — | Prove the shape end to end before trusting it. |

**Parts 1 and 2 are a morning's work and each fixes something that is wrong right now.** That is deliberate:
the plan starts with the two things that are bugs, not with the two things that are features.

---

## 13. Risks and open questions

- **`http` is the first tool that can write to a foreign system on the agent's own initiative.** `push` was
  the only outward act before. The refusals in §3.3 are the guard, and test 4 is the one that must never be
  deleted.
- **A provider config moves a compile-time guarantee to runtime** (§5). Validation on load is not optional.
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
