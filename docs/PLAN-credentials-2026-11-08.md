# PLAN — two vaults: `http` secrets, and the credentials the app itself uses

**2026-11-08.** Written after `PLAN-universal-cut` landed. The universal cut removed every place the app
named a host's API; this plan settles what a credential *is* now that anyone can be a host, and where the
model provider stops being OpenRouter-only.

The operator's design, in their words:

> splitting secrets into several vaults. Original secrets vault should keep only http secrets with their
> scopes (general or specific account/project/branch). And separate vault for account/project/branch git
> and llm server settings where user defines whatever needed for those functionalities like for llm —
> key, provider and model endpoint, for git — token, username, host url, project url etc. And this second
> group is accessible when you add an account/project/branch … or when you enter credentials 3 dot option
> window from a specific row representing account/project/branch.

and, on the agent's reach:

> we should also give a checkmark that asks whether agents can see and use those secrets for http auth
> calls, 2 checkmarks — one for llm provider info and one for git server info.

and, on cost:

> remove it, if we can't get it through protocol then we don't need it.

---

## 1. What is true today, measured

One `SharedPreferences` file (`muffy_git`), one AndroidKeyStore AES-GCM key
(`setUnlockedDeviceRequired(true)`), and one value format for everything named: `host \n ciphertext`.

| prefs key | scope | host-bound | `auth:` can name it |
|---|---|---|---|
| `token_<login>` | account | implied by `Account.host` | **no** |
| `model_key_<login>` | account | no | **no** |
| `secret_general_<name>` | every project, every account | yes | yes |
| `secret_<login>_<name>` | account | yes | yes |
| `secret_<login>_project_<project>/<name>` | project | yes | yes |

Lookup is **narrowest first**: project → account → general. The app-only keys are unreachable from
`auth:` because `secret()` only ever reads `secret_*`. So the two-part split exists already as a
*namespace*; what does not exist is a door that shows it, a branch scope, or any structure inside the
git/llm half — a token is a bare string, and the model provider's address is a constant in `Door.kt`.

**A project-scoped `git` secret already overrides the account token** (`GitCredentials.forRemote` asks the
vault first, by host). The mechanism is there; the picker is not, and a branch cannot have one.

### 1.1 Three defects in the vault's door — all measured, all in the "a failure must say what happened" class

1. **General secrets are invisible.** `CredentialStore.secretNames()` filters `secret_<login>_`, so
   `secret_general_*` never appears. The agent can *use* a general secret (`AgentTools.kt:3589` merges both
   lists); the person can create one and then cannot see, rotate, or remove it.
2. **A project row shows the wrong host.** `secretHost(name)` reads only the account key. A project secret
   bound to `gitlab.com` renders as *"used by this phone only"* — a false sentence about a value that will
   be sent, on the screen whose whole purpose is preventing that.
3. **Remove does nothing for project and general scopes.** The screen calls `forgetSecret(name)`, which
   removes `secret_<login>_<name>` only.

**The suite cannot see any of the three**, because the test fake's `secretNames()` *does* merge general
secrets and project names. The fake is right and the phone is wrong — and the tests were written against
the fake.

The root cause is one sentence: **rows are addressed by name, but a name is not unique across scopes.**
`secretProject(name)` documents that it answers the first match. Every scope this plan adds inherits that
ambiguity unless a row becomes `(scope, name)`.

---

## 2. The two vaults

### 2.1 Vault A — `http` secrets (agent-visible, by design)

Unchanged in purpose: a value the agent may spend, named by an article or by `auth:`, and bound to one
host so naming it cannot send it anywhere. Four scopes, narrowest wins:

**branch → project → account → general**

New: **branch**. The key gains one segment, with both parts percent-encoded and `/` as the separator —
the same rule the project scope already uses, because a branch called `feature/x` is legal git and a
separator that can occur inside a part is not a separator.

Everything else about Vault A stays: `host \n ciphertext`, empty host = "never sent anywhere", whole-label
host matching, read back after writing before saying "kept".

### 2.2 Vault B — the credentials the app itself uses

Two kinds, one entry per kind per scope:

| kind | what it holds | who reads it |
|---|---|---|
| **git** | token, git user (the name a commit is made as), the host the token is for | `ProjectGit` when it pushes, pulls, `ls-remote`s |
| **llm** | key, provider base URL | `Door`, `Embedder`, `Scanner`, `ModelCatalogue` |

Scopes: **branch → project → account**, plus **general** — and general exists for exactly one thing, which
the operator's correction made visible: **embedding and rerank are app-wide**. They are the ones a key
belongs to the phone rather than to a person, because there is one vector space per app and an index built
by one identity must be readable by the next (see §3.4). The chat model never uses the general scope: every
chat request is made *as* somebody.

**A scope's entry is a connection and a choice, and the two inherit differently.** The address and the key
travel together and are atomic; the model id and the effort inherit independently:

| field | inherits on its own? | why |
|---|---|---|
| **address + key + request shape** | **no** — a scope has its own connection or inherits the whole connection | a key is bound to one host. A project with its own address and an inherited key would send that key to a host it was never stored for — the one hole this vault exists to close. The **shape** is in this group for the same reason (§2.6): it is a fact about the server, so a different server means a different shape, and half of one is a request that cannot be sent |
| **model id, effort, window, reply** | **yes**, field by field — **but only as far as the address is inherited** | changing only the model must not mean re-typing the key, which would put a second copy of a secret on the phone. The limit is measured, not cautious: a model id is a *name the server assigns*, and the names disagree — `deepseek/deepseek-v4.1-flash` (OpenRouter), `deepseek-chat` (DeepSeek's own), `gpt-…` (OpenAI), whatever `--served-model-name` said (vLLM), `gemini-…` (Gemini's compat layer). So a scope that **sets its own address must set its own model**; the choice is inheritable exactly when the pair it belongs to is |

So a window opens showing what the scope *would* use, the person changes any field, and **leaving a field
alone writes nothing for it** — that is what "save as inherited" means, and it is why the window has no
"inherit" switch: the absence of a local value *is* the inheritance.

**Not nameable by `auth:`** unless the checkmark below is ticked. That is the security property the current
namespace already has, kept.

**Reached only through the ⋮ of a row** — the account row, the project row, the branch row — and from the
window that adds one, so the credential exists at the moment the thing it belongs to is made. There is no
"Credentials" list in Settings: a credential with no owner is a row nobody can act on.

### 2.3 The two checkmarks

Each Vault B entry carries **"the agent may spend this on its own requests to this host"** — one for git,
one for llm, independent, **default off**.

- Off (default): the entry is invisible to `auth:`, exactly as `token_`/`model_key_` are today.
- On: `auth: git` (or `auth: llm`) resolves to this key, **only for the host the entry is bound to**. The
  agent's tool description lists what it may name, so a refusal is not a mystery.

Why per-kind and not per-entry: the two have different blast radii. A git token can push; a model key can
spend money. A person may want the agent to ask the model provider what models exist without letting it
push as them.

### 2.4 What the window asks for — and what it does *not* store

The git window collects **address, host, git user, token**, because that is the whole act of connecting a
project. But only the credential and the host it is for go into the vault. **The address is git's own fact**
(`remote.origin.url`, and *Set the address* is the verb that changes it) and is not copied into a second
store where it could drift — the same rule that made `secretHost` and the model config live in one place
each. A window may ask for both; the store keeps each thing once.

For llm the base URL *is* part of the credential (there is no other record of it), so it is stored with it.

### 2.4b Only what the protocol defines — the inventory, field by field

The operator's rule, and the test that decides what a scoped window may ask for: **if the protocol does
not define it, it does not live in the scope.** Written out because one of the four is where the intuition
is one step off.

| field | where the protocol puts it | in the window? |
|---|---|---|
| **address** (base URL) | the request target: `POST {base}/chat/completions` | yes |
| **key** | `Authorization: Bearer <key>` | yes |
| **model** | a **body field**, `model` | yes — a connection with no model has nothing to ask for |
| **effort** | a body field, `reasoning_effort`, **optional** | yes |
| git user | git's own: basic auth is `username:token` at the address | yes, for the git kind |

**"Provider" is not a protocol word at all** — which is why the operator's reading is right in the sense
that matters and one word off in the strict one:

- **The provider *is* the endpoint.** There is no routing dimension in the protocol; whoever answers at
  the address is the provider. C2 deleted `provider.only` precisely because the address made it redundant.
- **The model is not part of the endpoint.** It is a request field, and one server serves many models —
  two models on one address share every byte of the endpoint. It still belongs in the same window,
  because a key and an address with no model is a connection that cannot be used, and **the window's
  size comes with the model** (`contextLength`, `maxOutput`) rather than with the address.

So a chat connection is **four** fields, and they group two-and-two for inheritance (§2.2): the
**credential pair** (address + key, atomic) and the **choice** (model + effort, field by field).

**The subset this app promises, and nothing wider.** "OpenAI-compatible" is a claim of varying truth: what
transfers is a *shape* (`POST {base}/chat/completions`), and above it every implementation negotiates
separately. The measured divergences that matter here, and what each one cost:

| divergence | who | the app's answer |
|---|---|---|
| `max_tokens` → `max_completion_tokens` | OpenAI's newer models reject the old name | the only use of `max_tokens` is the keepalive ping's 1 token; a ping that is refused is a ping skipped, never a failure |
| `reasoning_effort` (body field) vs `reasoning: {effort, exclude}` (OpenRouter's object) | the OpenAI name is a *shim* OpenRouter accepts; the reverse is not true | send `reasoning_effort` only — the vendor object was deleted in C2 |
| `developer` role vs `system`; reasoning models refusing `temperature` | OpenAI, model-dependent | the app sends `system` and no `temperature`/`top_p`/`logprobs` at all, so both cases cannot arise |
| `reasoning_content` echoed back on the next turn | DeepSeek's thinking mode, **400 without it** | already echoed (`Door.kt:690`); it is a vendor requirement the app meets rather than a protocol field |
| `provider`, `models`, `transforms`, `route`, `plugins`; `usage.cost`; `error.metadata.raw` | OpenRouter's own | **all deleted or ignored** (C2, C5). The error unwrap keeps a `message` fallback, so a server without `metadata.raw` still says its own sentence |
| `/models` with `context_length`, `top_provider`, `supported_efforts`, `pricing` | OpenRouter's catalogue | already optional per connection: no catalogue means the typed model, the protocol's four efforts, and the ceiling ladder's floor |

So the contract is: `model`, `messages` (with `tool_calls` and `tool_call_id`), `tools`, `tool_choice`,
`stream`, `stream_options.include_usage`, `reasoning_effort`; answered by `choices[].message.{content,
tool_calls}`, `choices[].finish_reason`, `usage.{prompt_tokens, completion_tokens,
completion_tokens_details.reasoning_tokens}`. Everything the app reads beyond that is optional and absent
without complaint — which is why the research finding changes no code, only the promise.

**One consequence, stated because it would be a silence:** the *ladder* of efforts a model offers is
`reasoning.supported_efforts` in OpenRouter's catalogue — a catalogue field, not a protocol one. On a
connection whose catalogue says nothing, the picker has no ladder to draw, so it offers the protocol's own
four (`minimal`, `low`, `medium`, `high`). What is sent is unchanged: `chooseModel` falls back to the typed
effort when nothing is declared, so the field is never silently dropped.

### 2.4c Everything else stays in Settings, because it is the app's

The scoped windows are **credentials and the choice they make**, and nothing else. These are app-wide facts
and they remain where they are today:

context trigger and hard limit · command execution timeouts · model response timeout · how much of the
record is kept · keepalive · the web (private-network switch, fetch size) · secrets the agent may use ·
session log · reminders · what the app did · the chat as drawn · appearance · change history · request-log
retention · running · background · the notification permission.

A timeout is not a credential, and putting one on every branch would make three numbers to keep in step for
no gain — the operator's words: *"lest of agent settings stay in general settings where they are."*

### 2.6 The request shape — a file, because a request is a document

**Researched because the operator asked whether one OpenAI request protocol exists.** It does not: what
transfers is a shape (`POST {base}/chat/completions`), and every implementation negotiates separately above
it. So the shape becomes **data**, stored with the connection, so a person can tell the app how their server
wants to be asked instead of hoping the app guessed.

**Requests are describable; streamed responses are not** — and the asymmetry is the whole design:

- a request is a JSON object built once and sent, so renames and omissions (`max_tokens` →
  `max_completion_tokens`, `system` → `developer`, the effort field) are a **substitution**;
- a streamed response is a **fold over events** — accumulate `choices[0].delta.content`, join tool-call
  argument fragments by `index`, watch `finish_reason`, stop at `[DONE]`. Anthropic terminates on
  `message_stop`, Gemini on `candidates[].content.parts[]`, Responses on `response.completed`. A file can
  name those paths and cannot name the accumulation rules, so a response schema would describe a fraction of
  a protocol and silently mis-handle the rest.

Within the OpenAI-compatible family the responses genuinely do not differ: the divergences are *additive*
(DeepSeek's `reasoning_content`) or in the error envelope — both already handled. So **no response shape**,
and the failing case says so plainly: a reply with no `choices[]` is reported as *"this server does not
answer in the OpenAI chat shape"*, not as a parse error.

**Three kinds, three presets**, and the embedding and rerank ones are not speculative: both are hard-coded to
OpenRouter's shape today (`{model, input[]}` → `data[].embedding`; `{model, query, documents[]}` →
`results[].{index, relevance_score}`), which is exactly what stops the app being pointed at Ollama, LM Studio
or a self-hosted reranker.

```json
{ "url": "chat/completions",
  "body": { "model": "{{model}}", "messages": "{{messages}}",
            "tools": "{{tools}}", "tool_choice": "auto",
            "stream": "{{stream}}",
            "stream_options": { "include_usage": true },
            "max_completion_tokens": "{{max_tokens}}",
            "reasoning_effort": "{{effort}}" } }
```

**The placeholder list is exactly §2.4b's inventory** — `{{model}}`, `{{messages}}`, `{{tools}}`,
`{{tool_choice}}`, `{{stream}}`, `{{effort}}`, `{{max_tokens}}` for chat; `{{model}}`, `{{input}}`,
`{{dimensions}}` for embedding; `{{model}}`, `{{query}}`, `{{documents}}` for rerank. A template naming
anything else fails with a sentence naming it, so the file cannot describe a protocol the app does not speak.

Three rules that make it safe and predictable:

1. **`url` is a relative path, never a URL.** A file with a scheme or a leading `//` is refused — otherwise a
   template redirects the request and the key with it. `{base}/{url}`, with the trailing slash trimmed.
2. **An empty placeholder removes its key.** No effort means no `reasoning_effort` field, not `""` — a server
   that rejects the field never sees it.
3. **The shape lives in Vault B, with the connection, not in a worktree.** A worktree arrives by clone, and a
   stranger's template would then govern how *your* key is sent to *your* server. It also means the streaming
   engine stays the app's.

**The probe validates the shape (C3a).** One small request, and the server's refusal names the field it
disliked — which is the only way a person learns that their server wants `max_completion_tokens`. The shape
file and the connection check are the same tool looking at the same fact.

### 2.5 Storage

```
conn_<scope>_<kind>          scope = acct | project_<enc(p)> | branch_<enc(p)>/<enc(b)>
                             kind  = git | llm
value = hostOrBaseUrl \n ciphertext(JSON)
```

The host stays outside the ciphertext for the reason it does today: it is not a secret, and matching a
remote against it must still work while the phone is locked — only the token refuses then, and the refusal
is already a state (`KeyState.LOCKED`) with a sentence.

Rotation is an overwrite of the same key. That is the whole argument for scopes over copies: a project that
inherits the account's git token follows a rotation with no edit, because it never held the value.

---

## 3. The model layer stops being OpenRouter's

### 3.1 What leaves

| today | why it goes |
|---|---|
| `ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"` | the base URL is the connection's |
| `provider.only` + `allow_fallbacks: false` | OpenRouter's routing; on a direct provider the endpoint *is* the provider |
| `reasoning.exclude` | OpenRouter's shape; `reasoning_effort` is the portable one and stays |
| provider *tags* as the pin | the pin is the connection — base URL + key + model id |
| `usage.cost`, per-endpoint prices | see §4 |

**Not a fallback to remove.** The app has none: `allow_fallbacks: false` is OpenRouter's switch that
*disables* its autorouting. Deleting the field is the whole act.

### 3.2 What has to be added

**`stream_options: {"include_usage": true}`.** `usage` is part of the OpenAI protocol, but on a *stream* it
arrives only when asked for: one extra final chunk with `choices: []` and the request's whole `usage`; every
other chunk carries `usage: null`. The app never sends the field and still gets usage **because OpenRouter
sends it unbidden** — the captured fixture `streams/answer.sse` proves it. Against a strict compatible
endpoint, every token column would silently empty. One field fixes it; a provider that rejects it gets one
retry without, and says so.

The fields the log already reads are all in-protocol: `prompt_tokens`, `completion_tokens`,
`prompt_tokens_details.cached_tokens`, `completion_tokens_details.reasoning_tokens`. Nothing else needs to
change in `RequestLog`.

### 3.3 The catalogue becomes optional

`ModelCatalogue` reads `openrouter.ai` and offers provider tags. It becomes: read `<baseUrl>/models` **when
the connection answers it**, otherwise the person types the model id and its context numbers. OpenRouter's
richer catalogue (prices, uptime, effort ladders) stays available for an OpenRouter connection, because it
is the same endpoint and costs nothing to keep. `OpenRouterAuth` — the PKCE key door — is kept for the same
reason the GitHub device flow was kept: it is a door onto a registration process, not a routing feature.

### 3.4 What stays account-wide, on purpose

**Embedding and rerank.** `PickedModel.space()` exists because two embedding models are two vector spaces,
so a branch-level embedding choice would silently invalidate an index built by another branch. The chat
model may differ per branch; the vectors may not.

### 3.5 The chat model is chosen at the scope that runs it — and the general settings are the root

**Rewritten 2026-11-09, on the operator's correction.** This section used to say Settings holds no chat
model at all. It holds the **root of the chain** instead:

> *"keep a rule that empty "" value means that it is inherited from the parent, while if lower settings
> scope defines it, then overwrites — this way user will be able to change only what he needs and keep the
> rest of the fields inherited. And we will get just one llm request schema button per settings window."*

So there are **four places a chat setting may be written, and each is the same set of fields**:

| scope | what it is | where it is stored |
|---|---|---|
| general | the app's own settings screen | `model_conf.json`'s `active` + `model_key_` + `SettingsStore`'s numbers |
| account | one identity | `conn_<login>_account_llm` (+`_choice`) — a row here overrides the general one |
| project | one repository | `conn_<login>_project_<p>_llm` |
| branch | one working tree | `conn_<login>_branch_<p>/<b>_llm` |

**Every field is resolved on its own** (`wiredFor`, `Connections.kt`): branch → project → account →
general. A branch that writes only a model keeps the project's address, key, shape, effort and three
numbers. There is no "inherit" switch, because an empty field *is* the inheritance and a switch would be a
second thing to keep in step with the text.

**The fields, and there is one set of them:**

- the **credential**: address, key, keyless, user (git only), and a shape per request kind
- the **choice**: model, effort, and the three compaction numbers (target, forget, trim)
- the **grant**: the checkmark that lets `auth: llm` / `auth: git` name it

**There is no cap and no completeness rule.** A model id is a name the server assigns, so pointing a
project at another server while inheriting the model is a request that server refuses — in its own words,
naming the model it does not have. That is a better answer than a window that would not close, and it is
the same feedback every number in the app now gets. **The one composition that is still refused is a key
and a host** (`Wired.keyProblem`), because a key sent to the wrong server cannot be un-sent; every other
mistake produces an error from somebody who knows better.

**The ceiling is gone** (see `ContextBudget`): the operator's numbers are used as written, nothing is
capped, nothing is derived, and the app no longer needs to know or display a model's window or reply
reserve. That is why `ActiveModel.contextLength`/`maxOutput`, the pin table, the ladder, the clamp and the
window-learning path are all deleted rather than re-homed — the *consumer* of those numbers was the clamp.

**A window writes only the difference.** It opens on the values in force, so "left alone" and "typed the
same" look identical in the text; `ConnectionDraft.changesAgainst` is what stops that from being stored,
because a copy of the parent's value at a narrower scope stops following the parent.

---

---

## 3.6 The request becomes a file — the inheritance goes, and with it the whole scoped llm menu

**The operator, 2026-11-09, and this supersedes §3.5's chain:**

> *"And now I think that this inheritance thing is dumb. We could just leave in general settings a button
> that then could open a hidden folder with llm request schema templates as files that user can open and
> edit (and we already have the functionality) - and a branch llm picker just allows to select one of those
> files as a template to use for the worktree agent. And there is then no need for scoped llm settings
> menues, just a template manager in general settings and a picker at branch addition and as a 3 dot
> option."*

> *"and rerank and embedding pickers can also just reference a template file from this folder"*

§3.5 spent a week building field-by-field inheritance between four scopes, a cap, a `changesAgainst`
comparison so a window would not pin what it inherited, and a row format with every field optional. **All
of that existed because the settings were forms.** A form has fields, fields need inheritance, and
inheritance needs a resolver. A file does not: a schema that names its model *has* chosen its model, and
one that leaves it out is simply a schema that cannot be sent.

### What one schema is (§3.6.1)

`<account>/shared/requests/<kind>/<name>.jsonc` — **`Schema`** (`Schema.kt`). **The path is §3.8's; this
table is the document, not the folder:**

| key | what it is | absent means |
|---|---|---|
| `address` | the base URL, `https://openrouter.ai/api/v1` | the file is refused — a request with nowhere to go |
| `url` | the path on it, `chat/completions` | refused |
| `body` | the request, placeholders and literals mixed | refused |
| `target` | where the context should end up | off |
| `forget` | when the agent is asked to clear up | off |
| `trim` | when the app trims the context itself | off |

**The model and the effort are literals in the body, not placeholders.** Only what the app alone knows at
call time stays a placeholder: `{{messages}}`, `{{tools}}`, `{{stream}}`, `{{tool_choice}}` (chat),
`{{input}}`, `{{dimensions}}` (embedding), `{{query}}`, `{{documents}}` (rerank). A model id is a name the
server assigned and it belongs to the request the way the address does — so **three templates are three
models**, and copying one is how you get another.

**The three numbers are in the file too, because they are policy and policy belongs with the request it
governs.** A "long careful" template can carry a bigger target; a template for a small local model can
carry a small one. Zero or absent means that hand is off, which is a statement rather than a gap.

### Where the folder is, and why (measured 2026-11-09)

`.muffy/` — under the account, outside every repository. Checked, because the design depends on it:

| question | measurement |
|---|---|
| is it browsable? | **yes.** `Workspace.isHidden` hides only `.gitkeep`, `.git` and `Beside`'s temp prefix — Report 28 deliberately made the app's own folders visible |
| is it editable? | **yes.** `.json` is already `EntryKind.TEXT`, and `EditorScreen` saves any file it opens |
| can a person make one? | **yes.** The browser's own **New file** button, `AppViewModel.createFile` |
| does it get a version history? | **no — and it cannot.** `GitService.versionsOf(repo, path)` needs a repository and `.muffy/` is in none. An edit is undone by editing back, or by copying the file first; deleting the folder is how you ask for the shipped three again |

**So no template UI is written at all.** The button the operator asked for is `viewModel.open(listOf(".muffy", "schemas"))`
— a browser open, which already exists and is already tested.

### What the pickers become (§3.6.2)

**One question per pointer, and the pointer is what the file is for:**

| pointer | stored where | the file is |
|---|---|---|
| the worktree's chat request | `.muffy/schemas.json`, per `project/branch` — a **path** from the account root | a chat unit |
| ~~the embedding model~~ | ~~`model_conf.json`'s `embedding`~~ | **removed 2026-11-09** — `docs/REMOVED-2026-11-09-semantic-search-and-scanner.md` |
| ~~the rerank model~~ | ~~`model_conf.json`'s `rerank`~~ | **removed 2026-11-09**, same document |

**The pointer is a path, not a name** (§3.8.3): the operator's *"branch just holds a path link to a llm
request template it should use"*.

**Which pointer named a schema is what it is for**, so a file cannot disagree with the place that chose it:
the vocabulary it is checked against travels with the question, not with the document.

**A branch with nothing chosen refuses, naming the picker.** A worktree that silently ran another branch's
request would spend the key on a model nobody chose for it. There is no `default.json` fallback and no
"shipped default for a branch", for the same reason §3.5 has no cap: a wrong choice the server refuses is a
sentence, and a wrong choice the app picks is a surprise on a bill.

**No inheritance and no scopes.** The branch's file is the branch's request; the project and the account
have none, because they cannot be *sent* anything. The general settings keep an **entry point** — the
"Model connection" row becomes "Request templates", opening the folder — and nothing else about chat.
**That row goes as well**: §3.7.5 makes the folder itself the door, reached from a project.

### The folder is `shared/`, and the lock rule is what makes it ordinary (§3.6.2b)

**The operator, 2026-11-09:** *"we can actually rename system into shared, and make it an ordinary folder
with some system locked files (and we just need to change how lock works, so that locking a folder doesnt
lock its content but just blocks it from being deleted or renamed)."*

**Why the two are one change.** `system/` is special *only* because a folder-lock used to mean "read-only
subtree", and the app needed a whole project that a person could not write into. Correct the lock and the
special case evaporates.

**The lock rule, stated once:**

| what is locked | what the lock stops |
|---|---|
| a **file** | editing it, renaming it, deleting it — a file *is* its content |
| a **folder** | renaming it, deleting it. **Its contents are untouched.** |

That reverses Report 9's fix (`locked.any { path.startsWith("$it/") }`) deliberately: the inherited lock
was a workaround for a rule that treated a folder as a permission. A folder is not a permission; it is a
name and an existence.

**What follows, and all of it is deletion:**

- `shared/` is **an ordinary folder of the account** — not a project, not classified, not listed as one —
  and **system locked like every other folder the app depends on**, which is the operator's correction:
  *"shared should also be system locked, as all of our special folders, because app's functionality depends
  on them. And it all utilizes our already existing lock stack of system and user set locks with a minor
  change for how folder locks should work."* So the whole protection is **the two existing levels applied
  to the folders that exist**, and the only new rule is what a folder lock means:

  | path | lock | what it stops |
  |---|---|---|
  | `shared/` | **system** | renaming or deleting the folder. Its contents are the person's. |
  | `shared/schemas/` **and everything under it** | **system** | editing, renaming, deleting — these are the app's reference templates, and every file in the folder is one (§3.8.5) |
  | `shared/schemas/chat.json` | *none* | nothing. The person may edit it — see §3.7.2 for why the code does not yet, and §3.7.5 for the agent's half. |
  | `.muffy/`, `agent/`'s records | **system** | the same, as they already are |

  It needs no path rule of its own: the agent reaches it from any worktree as
  `../shared/schemas/example.json`, because `resolve` already allows anything under the account and `..` is
  already legal for reads.
- **`isSystemPath` goes** (it existed to make one whole project read-only), and `isSystemLocked`'s segment
  names go with it.
- **`appHold` becomes one question**: is this path on the app's locked list? The three sources it used to
  encode as name rules — `.muffy/`'s flags, `agent/`'s records, and now `shared/schemas/example.json` — are
  entries in that list, written by the app.
- **"System locked" stops being a name and becomes a level** on the same lock list the person uses: a flag
  the app writes and the person cannot clear, which is what "system locks restrict both" already meant.
- `SYSTEM_PROJECT`/`linkSystem`/`ensureSystemLinks`/`ensureExcluded` — **kept only as convenience**, and
  renamed: a `shared` link inside each worktree is a browsing aid, never a permission. If it is dropped
  entirely, nothing about reachability changes.

**The cost, said plainly:** a person who locks a folder to keep an agent out of its files loses that lever.
They lock the files. The alternative was a rule where the same word meant two different things depending on
what you pointed at.

### The key (§3.6.3)

**A template names a vault row; it never holds a value.** The operator, choosing between the three
options and being right about all three reasons: *"the file names a vault row"* — rotation stays in one
place, and a key is not in a document that gets copied and read.

```json
{ "address": "https://openrouter.ai/api/v1", "key": "@openrouter", "url": "chat/completions", … }
```

**Absent `key` means no `Authorization` header at all.** Absence is the statement, so a server on the phone
needs no flag and `Connection.keyless` dies.

**The name is resolved by the host, not by trust.** This is the one thing that must not be simplified away:

```
secret(name = "openrouter", host = hostOf(schema.address))
```

The vault's own signature already says *"The [host] is part of the question, not part of the name"*, it
already answers null for a host a secret was not stored for, and it already matches whole labels. So a
template that names `@openrouter` and an address of `evil.test` is **refused, naming both hosts** — the name
selects a row, the row's stored host is the fact. Without this, a `key` field is an exfiltration primitive,
which is the one failure a refusal cannot undo.

**A name is not unique across scopes** (`(scope, name)`), so it resolves **branch → project → account →
general** — the one piece of `wiredFor` worth surviving, because it is the piece with the safety property.

**Shipped `chat.json` names no key**, so a fresh install refuses by saying a key is missing (the sentence
`KeyRow` already has) instead of pointing at a row that does not exist.

### Steps

| step | what | state |
|---|---|---|
| **T1** | `Schema` + `Schemas`: the document, the folder, the shipped three, the name rules. | **landed** (`f82658bc`, `bd002dad`): the folder's existence is the flag, and a folder somebody emptied stays empty |
| **T2** | The catalogue goes: `ModelCatalogue.kt`, `CatalogueKind`, `EndpointInfo`/`ModelInfo`, `ModelChoice`, `providers`/`providersOf`, the five model/provider verbs, `addPicked`, `effortFor`/`effortRank`, `ActiveModel.provider`, `Choice.provider`, and the `modelSees` courtesy check. | **landed** (`04bfa137`): a model is a word in a file; the effort is sent as written; a sentence that wants a word for the place asks `providerOf` |
| **T0** | `system` → `shared`, and a folder lock stops covering its contents; `shared/` and `shared/schemas/` system locked; `shared/schemas/example.json` held as a *file*. | **half landed** (`78ab2190`): `isAgentLocked` drops the prefix test and `appHold` gains the exemplar's path — but `isSystemPath` still answers for **every path under `shared`**, so the person's `isLocked` still inherits and nothing in the folder can be written. **F1 in §3.7.2 is that half** |
| **T3** | The pointer and the send: `Schemas.pointer`/`setPointer` keyed as a session is; `wiredFor` becomes `(project, branch) → Schema + key-by-host`; the branch window picks a file. | **storage landed** (`13336130`) — `.muffy/schemas.json`, one file, atomic. **The resolver and the picker are next**; until they land the runner still reads the connection rows |
| **T4** | The two vector models reference files: `PickedModel.schema`, the search reading the model id out of the file, the two rows becoming one file list. | **OBSOLETE 2026-11-09** — the two models and the index behind them were removed (`docs/REMOVED-2026-11-09-semantic-search-and-scanner.md`). What survives of T4 is the **chat** template pointer, which landed in `Schema`/`Schemas` |
| **T5** | The general scope holds no chat model: `ModelSection`'s chat rows, `ModelChoice` and the rates line go; `model_conf.json` keeps only the two pointers. | **part done** (`947d1ff5`): the chat rows, the picker and the connection row are gone from Settings, and the templates row is in their place; `model_conf.json`'s `active` is still read by the runner |
| **T6** | The connections window loses its llm half: `ConnKind` becomes git-only, `Connection.shapes` and the shape dialog go, the git window opens on the project's remote host. | **landed** (`c4977768`, `5cf5e477`) — and further than the step asked: `Choice`, `wiredFor`, `ConnectionDraft`'s model half, `LlmConnectionDialog`, `ShapeDialog`, `Pending.ModelConnection` and three menu entries went with it, and so did `model_conf.json` itself. See `docs/REMOVED-2026-11-10-llm-connections-and-model-config.md` |

**Why T2 before T3.** A picker that lists files must not sit beside a list of tags: two ways to name a
model, one of which is invisible in the file, is the drift this section exists to remove.

## 3.7 First use: a folder, a starter project, and no gate

**The operator, 2026-11-09:**

> *"onboarding now is just a file from a folder that is shown at the first use of the app, it doesnt hide
> exit button and first users just swipe through the the files from onboarding folder. no extra machinery -
> that is already how files behave. and last onboarding screen shouldnt ask for sign in - we no longer need
> such onboarding file."*

and, in the same breath, the question this section answers:

> *"does standard local account with standard local project is supplied with the app? so that user can enter
> it and make some notes or read some of sysmen files from the shared folder (for example refreshing
> themselves on onboarding? or adding templates for llm requests - it seems to be an elegant solution, even
> more elegant than giving access to templates folder via general settings menue)."*

### What is measured today (§3.7.1)

| question | measurement |
|---|---|
| does a **local account** ship? | **yes.** `AccountStore.ensureProvisional()` makes `~local` at app start and again whenever the last account is removed. It is a real account folder — projects, repositories, `shared/`, the app's own state in `.muffy/` — and `AppViewModel.PROVISIONAL_AUTHOR` (`Muffy Codes <local@muffy.local>`) gives it commits with no GitHub behind it |
| does a **local project** ship? | **no.** `Workspace.ensure()` makes the account folder, `shared/` + README + `muffycodes_data/{prompts,dictionary,skills,wiki,mcp}`, and one `shared` link per worktree. **Nothing creates a project**, so a fresh first screen is `.muffy/`, `shared/`, and no folder a person can type in |
| can a person **read** `shared/` from a project? | **yes** — every worktree holds a `shared` link and the browser follows it. The README *means* to send people there, by a name that no longer exists (§3.7.6) |
| can a person **write** a template in `shared/schemas/`? | **no — and that is the defect this section turns on.** `Workspace.appHold`'s `isSystemPath(joined)` answers `Hold.APP` for **any** path whose first segment is `shared`, and `isLocked` — the *may anybody write this* question — is `appHold(file) == Hold.APP`. So `Workspace.write(shared/schemas/chat.json)` returns `false`, `createFile` refuses with `lockProblem`, and the editor opens it read-only. The agent is refused separately by `mayWrite` (*"the account's own folder… you may read it"*). **Only the app itself can write a template today**: the shipped three, and nothing else |
| is the Settings row a door? | it opens `shared/schemas` in the browser — which can **read** the folder and cannot add to it. A display case |
| is there a locked **exemplar** to copy? | **no.** `Schemas.EXAMPLE = "example.json"` and `Workspace.SCHEMA_EXAMPLE_PATH` both exist, and `appHold` holds that path as a file — but **nothing ever writes it**: `Schemas.ensure` writes `chat.json`, `embedding.json`, `rerank.json` and stops. So the exemplar is a lock on an absent file. **Superseded by §3.8**: the references are one per kind, in their own folders, and they are written |

So the operator's proposal is one rule away, and it is the rule he already made, applied to the second
question as well: **a folder lock is a name and an existence, in both directions.**

### `appHold` becomes what §3.6.2b said it was (§3.7.2)

The fix is three lines, not a redesign. `isSystemPath` stops being a subtree test:

```kotlin
// was: parts[0].equals(SYSTEM_PROJECT, ignoreCase = true)   — the whole folder read-only
joined.equals(SYSTEM_PROJECT, ignoreCase = true) ||
    joined == Schemas.DIR || joined.startsWith(Schemas.DIR + "/")
```

**The reference folder keeps its subtree, and the reason is the one already used above**: every file inside
`shared/schemas/` is the app's — shipped, commented, never edited in place — so the inheritance is the point,
exactly as it is for `agent/` and `.muffy/`. What the fix unlocks is `shared/`'s *other* contents, and above
all **`shared/requests/`**, which is where a person's and an agent's filled units live (§3.8.1).

with `SCHEMA_EXAMPLE_PATH` already held as a **file** above it, and `isSystemLocked` keeping its two name
rules — `agent/` and `.muffy/` — **because those are the app's own records: every file inside them was
written by the app, so the inheritance is the point** (the operator, on `agent/`: *"and remember that whole
subbranch should inherit them"*). The difference the fix makes is only about a folder whose *contents* are
the person's:

| path | held | what that stops |
|---|---|---|
| `shared/` | app, **folder** | renaming or deleting it |
| `shared/schemas/` | app, **folder** | renaming or deleting it — a template's folder is named by pointers |
| `shared/schemas/**` | app, **subtree** | editing, renaming, deleting. **Readable**, so it can be copied |
| `shared/requests/**` | nobody | nothing. The person may edit a unit, copy it, delete it, or make a fourth |
| `shared/onboarding/01-…md` | nobody | nothing (§3.7.3) |
| `agent/…`, `.muffy/…` | app, by **name** | writing them, wherever they appear — unchanged |

**The test that encodes the old rule flips with it.** `WorkspaceTest`'s *"the whole shared folder is held,
and a project's own muffycodes_data is not"* asserts a file three folders down is `Hold.APP`; it becomes
*"the folder is held, and the file inside it is the person's"*. That is the whole cost of the change, and
it is the test being honest about a rule that changed.

**What this does not do is give the agent a hand.** `mayWrite` refuses every write outside the worktree —
including through the `shared` link — with the operator's sentence *"any project's agent gets readonly
access to account folders outside his project (other projects, including system)"*. So the agent's half of
the table above is **not implemented and is a ruling to make, not an oversight to fix**: an agent that can
rewrite its own request body is doing what it was told, or it is editing the brief it is following. The
person's half is all §3.7.5 needs.

### Onboarding is a folder and one boolean (§3.7.3)

**What it is.** `shared/onboarding/0N-*.md`, seeded **once**, when the folder does not exist — the same
flag `Schemas.ensure` uses, and for the same reason: a person who deleted a page meant it. After the seed
they are ordinary files: editable, copyable, deletable, and the editor's swipe moves between them through
`fileBeside`, which is what it already does for any folder. Past the last page it bounces, because that is
what the swipe does at either end of every folder.

**The one piece of state is `onboardingShown`.** First launch opens the first page; the flag is set; it
never opens by itself again. There is no position, no "done", and no count.

**What that deletes** — all of it machinery that existed to make reading a gate:

- the state and its consequences: `AppViewModel.onboarding`, `mustFinishReading`, the hidden Back arrow in
  `EditorScreen`, `closeEditor`'s refusal, `onboardingReached`, `onboardingIndexOf`;
- the ending: `finishOnboarding`, `onboardingConnected`, `closeOnboardingIfConnected` and its call sites in
  `signIn` and `saveModelKey`;
- the position: `SettingsStore.onboardingAt`/`markOnboardingAt`/`onboardingDone`, replaced by the one flag;
- the rewriting: `Onboarding.REVISION`, `Workspace.ONBOARDING_STAMP`, the delete-and-rewrite loop in
  `ensureOnboarding` — `ensureOnboarding` becomes "write the pages if the folder is missing", and
  `isOnboardingPath` (already dead code) goes;
- **the CONNECT page, and with it the whole `app:` machinery**: `followLink`'s `app:` branch,
  `runLinkAction`, `AppViewModel.Connection`, `pendingConnection`, the no-arg `connectionShown`, and the
  `marks`/tick plumbing in `Markdown` and `EditorScreen`. Every verb in that closed set — `connect-github`,
  `connect-openrouter`, `use-token` — exists only for that page, so the page is the feature. `followLink`
  keeps its `http` branch; web links still open.
- six tests in `AccountsFeatureTest` about onboarding: five assert the gate (*cannot be closed before the
  last page*, *reached in order*, *finishing leaves the connect page*, *closes on its own*, *a model key
  alone does not close it*) and one the `app:` link on a locked page, which dies with the page. They are
  replaced by five that assert the folder:
  the first launch opens the first page; Back leaves it; swiping visits them in order and bounces at the
  end; a page is an ordinary file a person may edit; **the last page is not a page that asks to sign in**.

**The cost, said plainly: the app will not rewrite them.** A confusing sentence therefore reaches new
installs only, and an existing install keeps the words it has. That is the price of "no machinery", and it
is the opposite of the rule `Onboarding.REVISION` encoded — deliberately, because that rule was itself
machinery, and the pages are ordinary files now.

**Which pages remain.** 01–05. 02's opening sentence — *"Sign in with GitHub and this app becomes a folder
in your account"* — is corrected to say the app works before any sign-in (as `README_TEXT` already does),
because a page that no longer leads to a sign-in must not read like one. **06 is deleted, not rewritten**:
the operator's word is *"we no longer need such onboarding file"*, and the two connections keep their own
doors — the account screen and Settings — which onboarding never was the only way to.

### The starter project (§3.7.4)

**The provisional account ships one project: `notes`.** Created when `~local` has **no projects**, through
`ProjectGit.init` and a first commit by `PROVISIONAL_AUTHOR`, so it is a real project rather than a folder
with a `.git` in it. It holds one file, `hello.md`: two lines saying what the folder is and how to make
another — so the first project is not an empty repository, and the editor has something to open.

**Why it is keyed on the provisional account and not on every account.** A signed-in account must not be
handed a `notes` project it never made. **It no longer comes along at sign-in**, because §3.9 makes `~local`
permanent: adoption-by-rename is deleted, and moving a project to an account is a verb the person uses
(§3.9.3). The starter is made when `~local` has no projects, which after a transfer is true again.

**What it is for** is the operator's own sentence: enter it, make some notes, and reach `shared/` through
the link its worktree already holds. That makes the next section a folder away instead of a menu away.

### The templates' door is the folder, not Settings (§3.7.5)

**The operator:** the folder reached from a project *"seems to be an elegant solution, even more elegant
than giving access to templates folder via general settings menue."* So:

- **`ModelSection`'s "Request templates" row goes.** Settings keeps the two vector rows, because a refusal
  names them and a person has to be able to see what it names; it keeps nothing about chat.
- **The door is `notes/shared/schemas/`** — or `shared/schemas/` at the account root, which the browser
  reaches directly. *Copy* `example.json`, rename the copy, edit it; the branch picker (T3b) then offers it
  by name. The browser's existing **New file** is the "add a template" verb, and nothing new is written.
- **`example.json` is the whole tutorial**, which is why it is the only file held: a person learns the
  format by copying a document that is already correct.

**Blocked on §3.7.2**: without that fix the folder is a display case, and the row that goes was the only
thing that opened it. **F1 therefore lands before the row is removed**, or the door is removed with the
handle still locked.

**And the exemplar has to exist — settled in §3.8**: there is no single `example.json`. The references are
one per kind (chat, embedding, rerank), each in its own subfolder of `shared/schemas/`, each commented, each
locked; and the app also ships a **filled unit** per kind in `shared/requests/`, so the request is not
something a person has to assemble before their first run.

### The README is wrong today (§3.7.6)

`Workspace.README_TEXT` is the first file a person reads, and it says two things that are no longer true:

- **`system/`** — the folder is `shared/`. (The rename in `78ab2190` did not reach this string, so the one
  document that explains the app points at a name that does not exist.)
- **"It asks before doing anything that cannot be undone."** There is no approval gate; the operator
  removed it. The sentence promises a confirmation the agent never shows, which is the class of lie this
  app's plan is otherwise ruthless about.

It also gains the two facts this section creates: where the introduction lives (`shared/onboarding/`, so
"read all of this again at any time" is true), and that a project called `notes` is already there.

### Steps (§3.7.7)

| step | what | why this order |
|---|---|---|
| **F1** | `appHold`: `shared` held as a **folder**, `shared/schemas/**` held as a **subtree** (the app's references), everything else in `shared/` ordinary — `shared/requests/` above all. The `WorkspaceTest` that asserts the whole subtree flips | it is the smallest change that makes the folder usable, and every step below assumes a writable `shared/requests/` |
| **F2** | The starter project in `~local` + `hello.md`, through `ProjectGit.init`: made on a fresh account, not made for a signed-in account, renamed with the account on adoption, made again when `~local` returns | it owns the `shared` link that F5's door is reached through |
| **F3** | Onboarding as files: seed once by folder existence, one `onboardingShown`, delete the gate, drop 06 and the `app:` machinery, correct 02's opening sentence, and replace the six tests with the five folder tests | independent of F1/F2, and the largest deletion |
| **F4** | `README_TEXT` corrected (`shared/`, no approval sentence, where the introduction is, the starter project) | one string, and it should be right before F5 removes the Settings row that the wrong README tells people to use |
| **F5** | `ModelSection`'s templates row removed | last, because it is the row that currently opens the folder — F1 must be in place first |
| **F6** | The template library (§3.8): the JSONC scanner and `Schema.parse`, `.jsonc` in `Schemas.names`, `shared/schemas/<kind>/` references and `shared/requests/<kind>/` units, and the pointer as a **path** | after F1, because the units' folder must be writable; and before T3b, which resolves the pointer F6 defines |

**`app:` links die with 06, and that is a ruling worth recording.** They were allowed in any file **exactly
two levels down** inside `shared/`, because `followLink` derives the folder as `parent.parent.name` and asks
whether that is the system one — so `shared/onboarding/01.md` and any `shared/schemas/<kind>/…` reference
were pages that could act. While `shared/` was read-only for the person that was harmless; with F1 it would mean a note the
person wrote at `shared/notes/x.md` could carry `app:delete-account`. Deleting the verbs with the page is
both the simpler and the safer answer, and it leaves nothing to get wrong when the folder becomes writable.

## 3.8 The template library: references, units, and JSONC

**The operator, 2026-11-09, correcting §3.6.1's shorthand:**

> *"I thought that branch just holds a path link to a llm request template it should use, while shared folder
> holds the actual templates with request structure commented system locked templates in jsonc (to allow for
> comments), and user or agent can copy those and make a filled in units to be used by the app to power chat
> agents."*

and, asked what ships:

> *"we provide an openrouter oauth door, so we can ship a request form for openrouter's deepseek 1017 endpoint
> when user chooses to use this door - and if user chooses to use the door this form will work out of the box,
> same for embedding and rerank models - but I think that they should have their own subfolders."*

### Two folders, and the difference is who may write (§3.8.1)

| folder | holds | written by | locked |
|---|---|---|---|
| `shared/schemas/<kind>/` | **references** — the request structure, each field commented | the app only | **yes**, the whole subtree (§3.7.2) |
| `shared/requests/<kind>/` | **units** — what the app actually sends | the person, and the agent | no |

`<kind>` is `chat`, `embedding` or `rerank` — **the subfolders are the kinds**, which is the reading that earns
its keep: the kind already travels with the pointer (§3.6.2), and a folder makes the picker's list *already*
the right kind, so an embedding picker cannot offer a chat unit at all. (If the intent was one subfolder per
*provider*, it is one constant and a rename: nothing below depends on the word.)

**Why two folders rather than one with a lock-list.** The reference must be untouchable and the unit must be
the person's; a single folder would need a lock entry per shipped file, and the entry list would have to grow
every time the app shipped another reference. Two folders make the rule a location, and the location is then
the lock (§3.7.2's `appHold`), with no per-file bookkeeping.

**A unit is a copy, not a second format.** With JSONC the comments survive the copy, so the shipped unit *is*
the reference with the address, the key name and the model chosen — one artifact, two addresses. Nothing strips
anything, and an agent that edits a unit keeps the sentence explaining what it changed.

### JSONC, and the one trap (§3.8.2)

The references are `.jsonc` so a field can be explained beside itself, which is the whole reason the operator
asked for it. The units are `.jsonc` too, because a copy of a commented file is a commented file.

**`Schema.parse` cannot be `JSONObject(text)` any more** — measured: a `//` line makes `JSONObject` throw, and
`Schema.parse` catches that as *"…could not be read"*, so today a commented template is a template the app
refuses with a sentence about being unreadable.

**The trap is that `//` appears inside the data.** Every address in this library is
`"https://openrouter.ai/api/v1"`, so a stripping pass that does not track string state deletes the address
and leaves a template that parses and points nowhere — a failure that looks like a typo in the file. The reader
is therefore a small scanner, and its rules are the testable part:

- inside a string, `//` and `/*` are text; outside, they start a comment;
- `\"` inside a string does not end it, and `\\` does not start an escape;
- a line comment ends at a newline, a block comment at `*/`, and an unterminated block comment is refused **by
  name and line**, in the same class of sentence `whereIn` already produces;
- the result must still parse, and a comment that was hiding a structural break is reported as the parse error
  it is.

Comments need no preserving on write: `Schema.text()` writes plain JSON, which is valid JSONC, so a unit the
app rewrites (`Schemas.write`) loses its comments — acceptable, and worth saying because it is the one place a
comment can vanish.

The suffix rule widens: `Schemas.names` offers `.jsonc` **and** `.json`, so a person who writes strict JSON is
not refused for it.

### The pointer is a path (§3.8.3)

`Schemas.pointer`/`setPointer` store a **path from the account root** — `shared/requests/chat/deepseek-1017.jsonc`
— not a bare name, and `Schemas.read` resolves it. The reasons a path wins:

- the operator's word is *"a path link"*;
- a unit may be kept wherever the person wants, including inside a project, without a second mechanism to
  express "somewhere else";
- the refusal can quote the whole path, which is what a person needs when a pointer outlives the file it named.

The validation is the same three questions a name asked, plus one: not empty, **resolves inside the account**
(a `..` that climbs out is refused rather than clamped), is a file, and parses as the requested kind. A pointer
whose file is gone refuses by naming the path and the picker — never a fallback, for §3.6.2's reason.

### What ships, and why it works out of the box (§3.8.4)

| reference (locked) | unit (editable) |
|---|---|
| `shared/schemas/chat/openrouter.jsonc` | `shared/requests/chat/deepseek-1017.jsonc` |
| `shared/schemas/embedding/openrouter.jsonc` | `shared/requests/embedding/qwen3-8b.jsonc` |
| `shared/schemas/rerank/openrouter.jsonc` | `shared/requests/rerank/voyage-2.5-lite.jsonc` |

The unit's `"key": "@openrouter"` and its address are what make the operator's sentence true: **the unit works
the moment that door has been used**, and refuses before that with the sentence `KeyRow` already has. No
conditional writing, no "create the form when the door is chosen" — a file that is inert until a credential
exists is a file, and the credential is what changes.

**The chat unit names `deepseek/deepseek-v4.1-flash-1017` and `"reasoning_effort": "high"`** — the model this
app has been measuring against. The two vector units name the models `ModelConfig` was already built around;
the embedding one carries `"dimensions": "{{dimensions}}"`, because dimensions are a request parameter and the
picker still chooses among 1024/2048/4096.

**A unit is only chosen by a branch, never inferred.** The picker lists `shared/requests/chat/`; a branch with
no pointer refuses naming the picker (§3.6.2). So "out of the box" means *one press away*, not *silently on*.

### Accounts and projects, measured (§3.8.5)

The operator asked, and the answer decides whether a default local account is justified:

| question | measurement |
|---|---|
| can a **local account** be created? | **no.** Every account goes through `addAccount(login, host, gitUser, token, …)`, which stores a key and a host; `AddAccountDialog` offers exactly two doors — GitHub's device flow, or *another host* with a name, a host and a key. `Account` has no shape without a host |
| so what is `~local`? | the **floor**, not a creation: `ensureProvisional()` makes it at start and again whenever the last account is removed. It is already the app's answer to "work without an account" |
| can a **local project** be created? | **yes.** `NewProjectDialog` asks for a name and nothing else — *"It starts here, with its own history. An address can be added whenever you want it to live somewhere else as well."* |
| can a local project gain a **remote origin** later? | **yes, in one press.** `setAddress` → `ProjectGit.makeRemote` and, when the project was local, an immediate `sync`. The name comes from the address's last segment, so the address's own page names the repository |
| can a project arrive from one? | **yes** — `addProjectFromUrl` clones |

**So the operator's reading is exactly the app's shape, and it is worth writing down because it is the
argument for shipping a local account at all:** accounts are remote identities and cannot be local, projects are
local by default and can be pointed at a remote whenever the person wants. Local-only work is therefore the
normal state of a fresh install rather than a special case, and it is why `~local` + a starter project (§3.7.4)
is a path a person can walk with no account at all.

### The agent's hand (§3.8.6)

**"User or agent can copy those and make filled units" is a requirement now**, and it collides with the rule
`mayWrite` states: *"any project's agent gets readonly access to account folders outside his project (other
projects, including system)"*. The resolution is the one §3.6.2b already chose for writes — **the lock decides,
not the location**:

- `shared/schemas/**` is system-locked, so an agent copying a reference *reads* it and writes the copy
  elsewhere. Copying is a read plus a write, and only the write needs a place to land.
- `shared/requests/**` is unlocked, so an agent may create and edit a unit there. It may edit **the request it
  is running under**, which is the operator's own position — *"an agent that can rewrite its own request body is
  doing what it was told"* — and the person can lock a unit (`isAgentLocked`) if they do not want that.
- Everything else under `shared/` follows the same rule: unlocked means the agent may write it, locked means it
  may not. `mayWrite`'s special sentence about *"the account's own folder"* therefore goes — it is the third
  copy of a rule the lock already answers.

### What this changes in the step list (§3.8.7)

| step | change |
|---|---|
| **F1** | unchanged in size, changed in scope: `shared/schemas/**` inherits, `shared/requests/` does not |
| **F6** (new) | the library: the JSONC scanner + `Schema.parse`, `.jsonc` in `Schemas.names`, per-kind subfolders, the three references and the three units, and `Schemas.ensure`'s flag becoming **the `requests` folder** — one existence flag for both folders, so emptying either is a thing a person can mean |
| **T3b** | the pointer is a **path** (§3.8.3), so `wiredFor` resolves a path rather than joining a name onto a folder, and the picker lists `shared/requests/chat/` |
| **T4** | the two vector pointers are paths too, and the two rows list `shared/requests/embedding/` and `…/rerank/` |
| **F5** | unchanged: the Settings row goes once F1 makes the folder a door — and now the door is `shared/requests/`, which is where the working files are |

## 3.9 Local is permanent, local has no remote, and projects move between accounts

**The operator, 2026-11-09:**

> *"make it possible to move and clone projects between accounts, and make ~local undeletable and its
> projects unconnectable to remote, so that local always exist and we dont need machinery for its
> recreation, and users can just transfer its projects to proper remote accounts and there connect them to
> origin."*

Three rulings in one sentence, and the first two delete machinery rather than add it.

### The contradictions this settles (§3.9.1)

Written down because "fix the contradictions" is the instruction, and each of these was a live claim:

| the claim | where it was | what is true now |
|---|---|---|
| *"whatever you made as `~local` comes with you — it is moved, not copied"* | `README_TEXT`, `AccountStore.adoptProvisional`, `addAccount`'s adopt-before-save comment | **false.** `~local` stays; projects are moved by the person, with a verb |
| *"a fresh `~local` after the last account is removed gets its starter back"* | §3.7.4 | **dead.** `~local` is never removed, so there is nothing to remake |
| `removeAccount`'s `?: accounts.ensureProvisional().login` | `AppViewModel` | **dead fallback.** `~local` is always an account on this phone, so the chain's last resort is a fact rather than a call |
| *"the provisional account is the floor … made here rather than assumed, because this is the moment it can have been adopted away"* | `removeAccount`'s comment | **inverted.** Nothing adopts it away any more |
| a local project may `Set the address…` and become published | the project row's ⋮, `setAddress` | **refused in `~local`**, and the menu item is not offered there |
| *"An account is a name, a host and a key"* | `AddAccountDialog` | **unchanged, and it is the argument for the rest**: accounts cannot be local, so an undeletable local account is the only way local-only work always has a home |

**One thing that is deliberately *not* a contradiction:** `Go local…` (keep-local) still removes a remote from
a project *in a real account*. The rule is about `~local`, not about "a project without an origin" — a person
may keep a project on this phone while signed in, and that project may still be given an address later.

### `~local` is undeletable (§3.9.2)

**The rule, in one place:** the provisional account has no removal. `AccountStore.remove`/`forget` answer
`false` for it, `AppViewModel.removeAccount` refuses with a sentence, and the accounts row shows **no
`Remove…`** — three places because a disabled menu item is not a rule, exactly as `closeEditor`'s refusal was
not one during onboarding.

The refusal's sentence names the way out rather than the prohibition alone: *"Local is this phone's own
workspace and cannot be removed. Move its projects to an account first — they are already there once they
are."*

**What deletes with it:** `AccountStore.adoptProvisional`, its call and its trace note in `addAccount`, the
three `AccountStoreTest` tests about adoption (replaced by one that asserts the opposite — signing in leaves
`~local` and its projects exactly where they were), and `removeAccount`'s recreation call. `ensureProvisional()`
stays, because a fresh install still needs the folder made once; it is creation, not recreation.

**`ensureProvisional` keeps writing nothing when the folder is there** — the existing rule — which matters more
now: it runs on every start, and the folder it would otherwise touch holds the person's only copy of work on a
phone that has never signed in.

### Local projects cannot have a remote (§3.9.3)

**Three doors, all refused in `~local`, each naming the transfer:**

- `setAddress` — the address a project points at;
- `addProjectFromUrl` — a clone *arrives* with an origin, so it is the same act with the address already
  filled in;
- `transferProject` — a project that **has** a remote may not be moved or copied **into** `~local`, because it
  would arrive connected. (A local project with no remote may.)

The refusals are in the view model and the transfer object, not only in the menu: an agent may reach the same
verbs, and a rule enforced by a hidden menu item is a rule with a hole in it. The menu hides `Set the
address…` in `~local` for the ordinary reason — a menu item that always says no is a menu item that wastes a
press — and `AddProjectDialog` says why the address half is missing rather than showing an empty field.

**Why this is the right shape and not a restriction for its own sake.** The point of the local account is that
it is *not* an identity: it has no host, no key, and `whoFor` answers `null` for it, so a push could only be
anonymous. A project with an origin inside it would be a repository this phone cannot speak to.

### Moving and copying a project (§3.9.4)

**One verb, two modes**, in `Transfer.kt` — `move(project, toAccount)` and `copy(project, toAccount)` — called
by the view model and by nothing else. A project is more than its folder:

| part | what happens |
|---|---|
| `<account>/<project>/` | renamed (move) or copied (copy) |
| `<account>/.muffy/worktrees/<project>/` | the same, then **repointed** |
| the two absolute pointers | rewritten: the tree's `.git` names `<account>/<project>/.git/worktrees/<id>`, and that entry's `gitdir` names the tree back. **`commondir` (`../..`) and the `shared` link are relative and survive** |
| the repository itself | nothing. `search.db` lives in the project's `agent/`, so it travels with the folder — the index is about the files, and they are the same files |

**`Worktrees.rename`'s repointing is extracted, not copied.** Its body already rewrites both absolute pointers
per branch, and it is the only code that knows the four-file recipe; `Transfer` calls `repoint(project)` and
`rename(from, to)` becomes "move the folders, then repoint".

**Four things do not travel, and each is a decision:**

- **the request history** (`.muffy/`) — it is the account's record of what that account's key spent, and
  moving it would make one account's usage look like another's;
- **the branch's schema pointer** — it names a path in the *source* account's `shared/requests/`, which does
  not exist in the destination. The row is removed on both sides, so a moved branch refuses by naming its
  picker rather than pointing at a file that is not there;
- **the agent session rows** — a session is a claim about an agent standing in a worktree, and the moved
  project has no agent standing in it;
- **`shared/`** — the app's references and the person's units belong to the account. A person who wants their
  units in the new account copies the file, which is the browser's Copy and needs nothing new.

**Refusals, all of them sentences:** the destination is the account the project is already in; the
destination has a folder of that name (a merge of two repositories is not a move, and §5's reasoning about
two histories applies unchanged); the project is not a project; the project has a remote and the destination
is `~local`.

**The destination is offered, not typed** — a list of this phone's accounts, minus the one the project is in.
Which is why the menu item only appears when there are two.

### What this costs (§3.9.5)

- **A person who signs in no longer finds their work moved for them.** They find it where they left it, in
  Local, one menu item away — which is the trade the operator asked for: local always exists, and nothing
  happens to their files that they did not do.
- **`copy` walks a repository on the main thread**, exactly as the browser's Copy of a folder already does. A
  large project makes the screen wait; it is the same cost the app already pays for the same act, and the
  transfer is a deliberate press rather than something a refresh does.
- **A moved project's branches keep their commits and lose their pointer rows**, so an agent in the
  destination asks for a template once. That is the honest state: the template it was using is in another
  account.

### Steps (§3.9.6)

| step | what |
|---|---|
| **L1** | `~local` undeletable: `AccountStore.remove`/`forget` refuse it, `removeAccount` refuses with a sentence, the row loses `Remove…`; **delete `adoptProvisional`**, its call and its trace note; simplify the takeover chain; replace the three adoption tests with one that asserts local is untouched |
| **L2** | No origin in `~local`: refuse in `setAddress`, `addProjectFromUrl`, and a connected project arriving by transfer; hide `Set the address…` and the address half of `AddProjectDialog` there |
| **L3** | `Transfer.kt` + `Worktrees.repoint`/`rename` split; move and copy with the refusals above; tests for a project with branches (history, branches, repointed pointers, `shared` link still working) |
| **L4** | The row's ⋮ gains `Move to account…` and `Copy to account…`, offered when there are two accounts, with a destination list |

**All four landed** (`80a23d8d`, `42513743`, `5fba7232`, `7c7e12e6`, `35d6c687`):
`adoptProvisional` and its call are deleted, `ensureProvisional()` runs on every start as creation rather than
recreation, `Transfer.kt` moves or copies a project with its worktrees repointed, the two origin doors refuse
in `~local`, and the row's menu offers both modes. Two measured lessons, both in the code as comments:
`Result.Success`/`Failure` are **internal**, so an outcome is asked through `exceptionOrNull()`; and a phone
that has signed in now lists **`~local` as a row too**, which is the rule working — three rows, three menus.

**L1 and L2 before L3**, because the transfer has to refuse a connected project entering `~local`, and that
rule is L2's. **L3 before L4**, so the menu has something to call.

## 3.10 The chat reads paths: pictures draw, links open

The operator, 2026-11-09:

> *"and dont kill app links altogether. I want for agent to give me path links to open files in editor right
> from the chat messages"* … *"And I want for agent to be able to insert pictures in his messages by using
> their file paths. Agent's messages are rendered in markdown so it should probably work - add this to the
> plan as well"*

### What is true today, measured (§3.10.1)

- **`MarkdownBody` already draws `![alt](address)`** and resolves it against a `baseDir`: a relative address
  is a file beside the document, an absolute one and a `file://` one are taken as given, and an `http(s)` one
  is refused by name (`resolvePicture`, `Markdown.kt:707`). A picture that cannot be read says so in words.
- **`PageBody` passes neither `baseDir` nor `onLink`** (`AgentChatScreen.kt:1207`), so every picture in an
  agent's message reads *"could not be read here"* today, and every `[words](address)` in one is plain text.
  The renderer is ready; the chat is not wired to it.
- **The agent already produces exactly that address.** `shot` writes a PNG to `agent/browser/shot-<t>.png`
  and its answer is the path **relative to the worktree** (`file.relativeTo(projectDir)`); `look` reads paths
  the same way. So the shape a picture needs is already the shape the tools answer with.
- **A path in a document does nothing**: `followLink`'s third branch is `Trace.note("link-ignored")`.
- **`Workspace.relative` answers null outside the account** (`Workspace.kt:949`), and `openEditor` assigns it
  straight to `editingPath` — so opening a path outside the account would close the editor silently, which is
  the one failure this app does not do.
- **Neither the editor nor the chat draws `viewModel.message`** — only the browser does. A refusal raised
  while the chat is on screen has nowhere to land.

### The rulings (§3.10.2)

1. **`app:` links go; links do not.** F3 (§3.7.3) deletes `runLinkAction`, its three verbs,
   `pendingConnection`, `Connection` and page 06 — machinery that existed for onboarding's two connection
   pages, and nothing else. `followLink` itself stays and gains a branch. **A link in a document is not
   onboarding's.**
2. **A path is an address.** `[words](notes.md)` opens that file in the editor;
   `![what it is](pictures/cat.png)` draws it in place. Both resolve against the **branch's worktree** —
   the same `projectDir` every tool composes paths from (`AgentTools.projectDir` = `ws.treeOf(project,
   branch)`), so the address the agent was *given* is the address the person can *press*, with no
   translation between them and no chance of the two disagreeing.
3. **A path that leaves the account is refused in words**, naming it. The editor cannot show what the
   workspace cannot name, and a silent close is worse than a sentence.
4. **`http(s)` pictures stay refused.** A paragraph must not open a socket, and the alt text plus the address
   is the honest answer — already the renderer's behaviour.
5. **`app:` is honoured only in a document the app wrote.** A chat message is never that, so an `app:` verb in
   one is refused, however it got there. The agent can write `app:connect-github` into a reply; it must not
   act.
6. **The agent is told the shape once, and again where it bites.** One short section in the system prompt
   (`WHAT THE PERSON SEES`), and `shot`'s own answer ends with the line that shows its picture — teaching it
   at the moment the path exists, which is the only moment it is needed.

### Steps (§3.10.3)

| step | what |
|---|---|
| **C1** | `PageBody` takes `base: File?` and `onLink`; `MessageRow` and the fullscreen reader pass them; the chat resolves `base` as `ws.treeOf(project, chatBranch)` |
| **C2** | `AppViewModel.followLink(address, base)` — the third branch resolves the address against `base` and opens it; outside the account refuses by name. `followChatLink(address)` refuses `app:` and resolves against the worktree |
| **C3** | The chat draws `viewModel.message` above the composer, and `openChat`/`closeChat` clear it, so a sentence raised while the chat is open is visible and nothing stale follows the person in |
| **C4** | The prompt's `WHAT THE PERSON SEES`; `shot`'s answer names the markdown to write |
| **C5** | Tests: a `shot` path in an agent message draws; a picture that is missing says so; a path link opens the editor on the branch's file; `../..` and an outside absolute path are refused by name; `app:` in a message is refused; `http(s)` is not fetched |

## 3.11 No local account: `shared` is the app's, and the agent's template is a chat control

The operator, 2026-11-09:

> *"what do you think about scrapping local account so that there are no prebuilt accounts and
> projects?"* … *"why onboarding has nowhere to live if it is inside shared folder that doesnt belong to
> any project and belongs to the app itself?"* … *"after onboarding user just enters the accounts screen
> where he can add accounts (there is always github sign in + option and manual sign in option), and
> branches should not require to select a llm template for agent to use - it should be a separate button
> inside the agent chat screen itself that allows you to select/change the template that this chat is
> using and that automatically pops up when a particular chat doesnt have a template selected. And
> openrouter oauth key minting should be an option in general settings in the same block with vault
> secrets management button - because it should be used to basically mint an openrouter key as a secret
> that then could be given an access scope (a particular branch, project, account, general or undefined
> which means that the secret is not availiable to agents at the time) and used in templates and http
> requests."*

### Why `~local` goes (§3.11.1)

**It is a workspace wearing an account's clothes.** `AccountStore` says an account is a name, a host and a
key (`AccountStore.kt:20`); `~local` has no key and no host, so §3.9 had to special-case it six ways:
undeletable, no origin, a refusal at two doors, a transfer rule, `whoFor` null, a fake `PROVISIONAL_AUTHOR`
for its commits. **An account cannot be shipped by an app** — an identity is something a person has, and one
that cannot be deleted is a credential that cannot be revoked.

**The measurement that settles it: the honest screens already exist and are unreachable today.**
`AccountsScreen`'s empty state ("No accounts yet — an account is a name, a host and a key…") and
`BrowserScreen`'s "Sign in to begin" were both written, then a workaround made them dead. Dead-but-correct
code is the author already knowing the shape.

**Projects keep what accounts cannot: the phone.** `ProjectGit.isLocal` is a per-project fact
(`remoteUrl(project).isNullOrBlank()`) and stays one. A project with no address, inside an account, is
exactly "kept on this phone" — so the honest thing §3.9 wanted to protect survives as a property of a
project rather than as a fake identity. `Transfer` becomes what the operator asked for in the first place:
between two identities.

### `shared` moves to the app (§3.11.2)

**Measured: `shared` is `<filesDir>/accounts/<login>/shared`** (`Workspace.kt:216`, `projectDir` → the same
folder). The account is the only thing that makes it exist, and nothing about it needs the account: no
`.git`, not a project, not per-identity. It is where it is because it used to be `system/`, a row beside the
projects.

**So it becomes the app's own folder, one per phone: `<filesDir>/shared`.** That is what makes onboarding
possible with zero accounts, and it is **more** consistent with the decision that made the library work at
all: a template names `@openrouter` and resolves against the vault of the account it is used in — that
indirection exists so one library serves every account. Two libraries would be two places to keep in step.

**The one real cost: containment is a single `startsWith(accountDir)`** (`partsOf`, `Workspace.kt:993`), and
`relative` answers null outside it — so the browser would refuse to show the app's folder and the editor
would refuse to open its files. `Workspace` gains a **second root** and serializes it as `shared/…`, which is
the name a person already sees. `partsOf` canonicalizes, so a file reached through a project's `shared` link
still counts; and `linkSystem`'s target base moves with the folder, leaving the relativization (and
therefore every depth) correct.

| what | where it goes |
|---|---|
| `AgentPrompt.systemFile` | `<filesDir>/shared/muffycodes_data/prompts/…` |
| `shared/onboarding/` | the same folder — **readable with no account** |
| `shared/schemas/`, `shared/requests/` | unchanged names, app-wide |
| `shared/prompt.txt` | unchanged |
| every project's `shared` link | points into the app's folder; still relative, still canonicalized |

**A template unit written for one account is visible from every account**, and that is correct: the vault row
it names is resolved where it is used, so `@openrouter` means *this account's* OpenRouter key. That is the
whole reason a template holds a name.

### Branches stop asking for a template (§3.11.3)

**A template is not a property of a branch, it is a property of a chat.** A branch is a tree; the model is
something you choose when you talk. Making it a branch attribute meant:

- the branch screen asked for something before the branch did anything (§3.6's scoped llm menu, of which
  only the pointer survives — and the pointer itself is what moves);
- a person arriving at a chat with no choice made had to *back out* to a different screen to fix it;
- and "which template is this chat using" was answerable only by reading a file.

**So: a button in the chat, and it opens by itself when there is nothing chosen.**

- **Where**: the chat's own top bar, beside the branch name — the chat is the thing that spends the token,
  so the chat is where the token's shape is chosen.
- **What it says**: the template's name when one is chosen (`deepseek-1017`), "Choose a template" when none
  is. A refusal is not a message: with nothing chosen the button is the only enabled control, so a person
  cannot type into a chat that has nowhere to send.
- **Auto-open**: on entering a chat whose branch has no pointer, the picker opens once. **Once**, not every
  frame — it is a window the person can dismiss, and dismissing it leaves the chat read-only rather than
  opening it again on the next redraw. `AgentChatScreen` uses the same `remember(session)` idiom it already
  uses for `opened`/`page`.
- **What it lists**: `shared/requests/chat/` — the units (§3.8), by name, with the model id beneath. Choosing
  one writes the pointer (`.muffy/schemas.json`, keyed `project/branch`), so the choice is per chat and is
  the same fact the runner reads.
- **The branch screen loses the picker entirely.** Its row menu keeps `Git connection…`; the model half
  goes back to being the account's, which is what §3.6 already decided (general settings → the account's
  request default) — the chat overrides it per conversation.

**What this changes in §3.8's storage**: the pointer stays exactly as it is (a path, per `project/branch`).
Only the *door* moves, from a branch row to the chat.

### Minting a key becomes a vault verb (§3.11.4)

**Today the OpenRouter key is minted by a sign-in flow that ends in a *connection*, not in a secret.** So the
key exists in two shapes at once — a row under `conn_<login>_…` and, for the token to be usable elsewhere
(an `http` call, a template), it has to be a vault row. That duplication is why `ConnKind.LLM` exists at all.

**The operator's shape: minting produces a secret, and a secret has a scope.**

- **Where**: general settings, in the block that holds the vault button — the same block, because it is the
  same act (put a credential on this phone) with one step of automation in front of it.
- **What it does**: the OAuth flow (existing `beginOpenRouterSignIn`/`DeviceFlow` machinery) ends by writing
  **a vault row** — no connection row, no `ConnKind.LLM`.
- **What it asks for after minting**: the **scope** of the secret, which is the vault's own vocabulary:

| scope | who may spend it |
|---|---|
| **undefined** | nobody yet — stored, not available to agents, which is the honest default for a key that was just minted |
| **general** | any account's agents, any project, any branch |
| **account** | the agents of one account |
| **project** | the agents of one project |
| **branch** | one branch's agents |

- **Why the scope is asked at mint time and not at mint**: because the key arrives without one, and a key
  that defaults to usable-by-everything is a capability nobody granted. The default is `undefined`, and
  choosing a scope is the grant.
- **Where it is used**: a template (`"key": "@openrouter"`) or an `http` call (`"auth": "@openrouter"`) — the
  same `secret(name, host)` resolution both already perform, which is what makes one minted key serve the
  chat, an embedding request, a rerank, and a hand-written `http` call without being copied four times.

### The journey the pages teach (§3.11.6)

The operator, 2026-11-09, in their own order:

> *"user reads onboarding, then he adds an account, then he adds projects to that account, then he enters a
> branch (main for example), then enters a chat, then he encounters a popup that asks him to select a request
> form and tells where templates and request forms are stored and how a key can be added (via secrets
> management menu in general settings menu). And as I said in secrets management block there should be a
> button to mint a new openrouter key, which is then added to secrets and which scope can then be set up in
> there. That is one of potential workflows, and of course user can enter settings and add his llm keys and
> then add or edit a request form before that, so that in the chat he could just select a form and proceed
> with writing to the agent. We can explain how to set up the model in the onboarding so that new operators
> knew what is where and how it works, one onboarding file at a time."*

**So the onboarding stops being a manifesto and becomes the walkthrough.** One file per step of that
journey, in the order the steps happen, each naming the place the next act lives. A person can stop reading
at any point and do the step; the pages are re-readable forever because they are files in `shared/`.

| page | what it says | where it points |
|---|---|---|
| `01-what-this-is.md` | a notebook that is a workshop; everything is a file in a git repository | — |
| `02-an-account.md` | **an account is where your files live** — GitHub's sign-in, or any host by hand | the accounts screen's two doors |
| `03-a-project.md` | a project is a repository: make one, or clone one that exists | `+ Add` on the account screen |
| `04-a-branch.md` | `main` is the project's own folder; a branch is a second tree | the branch screen, and why main needs no choosing |
| `05-the-model.md` | **the agent needs a model, and a request form is a file that says which** — where forms live (`shared/requests/chat/`), and that choosing one is per chat | the chat's template button |
| `06-a-key.md` | **a key is a secret**: Settings → Secrets, where OpenRouter's mint button is, and what a scope means (branch, project, account, general, or undefined = no agent may use it) | Settings → the Secrets block |
| `07-nothing-is-hidden.md` | every request is recorded: the model, the tokens, and what happened | the record |

**Two things are deliberately *not* pages**: the popup (it teaches where it interrupts, which is the only
moment a person needs it) and the minting window (its own fields are its explanation).

**The popup, in full.** It is not "choose a form" — it is the one place a person learns the shape of the
whole model layer at the moment they need it:

> **This chat needs a request form.**
> A request form is a file that says which model to ask, how, and which secret to send. Forms for chat live
> in `shared/requests/chat/`.
> Pick one below. If the list is empty, the next page explains how to make one — it needs a key, and keys are
> minted or added in **Settings → Secrets**.
> [ the forms, by name, with the model id beneath each ]

Three answers in one window: *what this is*, *where it lives*, *what to do if it is not there yet* — and the
last one is a link-shaped sentence rather than a link, because a settings screen behind a dialog is a
navigation a person cannot come back from.

**The other workflow is first-class, not a workaround.** Nothing in the design requires the popup to be
answered before Settings is visited: a person who adds their keys and writes or edits a form first arrives at
the chat with a list already populated, picks one, and writes. The popup is a *gate*, and a gate that has
already been opened never appears — that is what "automatically pops up when a particular chat doesn't have a
template selected" means.

### Steps (§3.11.5)

| step | what | why here |
|---|---|---|
| **N0** | `shared` → the app's folder: a second root in `partsOf`/`relative`/`projectOf`, `linkSystem`'s base, the ~8 `File(accountDir, SYSTEM_PROJECT)` sites, `ensure()` made independent of an account | first, because everything else needs a place to live that is not an account |
| **N1** | Delete `~local`: `PROVISIONAL`, `ensureProvisional`, `isProvisional`, `localOriginSaid`, `removeAccount`'s refusal, `Transfer`'s `toIsLocal`, `AddProjectDialog(fromAddress=…)`, the hidden `Set the address…`, `PROVISIONAL_AUTHOR`, `createLocalProject`; **and F2's starter project with it**, since it only existed to make `~local` non-empty | second: every one of these is a branch of code whose condition can no longer be true |
| **N2** | The act doors speak: the 58 `workspace ?: return` sites are now reachable, so every *act* sets `message`; read doors keep answering empty | **landed** (`5d86cdbd`, `0bafbc70`, `9cdde465`): `workspaceFor(done)` — one helper taking the act's past tense — replaced **26** act sites, two acts kept their own words, and `NoAccountFeatureTest` holds both halves (a read must leave an existing message alone, not merely be silent). 21 sites remain: the reads, the background passes and the internal helpers |
| **N3** | Onboarding, now that it has a home: one boolean, the folder read from the app's `shared/`, page 06 and the `app:` machinery deleted (F3's decision, unchanged) | **code side landed** (`bc002504`, `5bf52f00`, `4def3506`): the app's folder is seeded at start-up with no account, `openOnboarding` reads it with no workspace, `app:` links resolve through `inAppFolder()` instead of `workspace.relative`. **Still open:** the seven-page journey and the deletion of page 06 with the `app:` machinery (N5b), and the `app:connect-*` links on page 06 still exist |
| **N4** | The accounts screen is the landing screen: no workspace means the empty state, and its `+` is a **menu with two separate entries — *Add GitHub account* and *Add custom account*** — not one door with a mode | **the menu is already built** (`Pending.ChooseAccountDoor` → `AddAccountDialog` → `AddHostAccountDialog`); what N4 still needs is the **landing screen** and the names, which landed 2026-11-09. **The operator's shape, twice corrected:** the two are separate options, chosen before any field is filled; and — *"github is just one of the git compatible hosts"* — GitHub is **not** the privileged route. It gets its own entry for one reason: **the OAuth device flow fills in what an account needs** (name, host, key) instead of the person typing it. Everything else is `AddHostAccountDialog`, the general case for any server that speaks git. Nothing in the account model is GitHub-shaped: `Account` is `(login, host, gitUser, key)` with `host` data, `GitCredentials` asks the account's own host, `noreplyEmail` refuses GitHub's form on other hosts, and `whoFor` answers null off GitHub rather than sending a key to `api.github.com` |
| **N5** | The chat's template button + auto-open picker; the branch screen loses the picker | after §3.8's library exists (F6) — before it, there is nothing to list |
| **N5b** | The popup's three sentences (§3.11.6) and the seven pages, written as one sequence | with N5: the popup and the pages say the same thing about the same folders, and writing them apart is how they drift |
| **N6** | Minting writes a vault row and asks for its scope; `ConnKind.LLM` and the connection shape for llm go | **landed** (`c4977768`, `5cf5e477`). `finishOpenRouterSignIn` and the paste path both end at `saveMintedKey`, which writes the vault's `@openrouter` row for OpenRouter's host — the name the shipped form already says. `KeyRow` became `MintedKeyRow` and sits in the Secrets block beside the vault button, per §3.11.4. **The scope is the account's** (the narrowest that names no project) and is moved on the vault screen, which is where every other secret's scope is chosen; the "undefined" rung the operator named is a row nobody names, and minting grants nothing because a *form* names it. **A chat with no form refuses in words** and the picker is the door. Record: `docs/REMOVED-2026-11-10-llm-connections-and-model-config.md` |

**N0 and N1 before N2**, because N2's audit is about doors that only become reachable when the workspace can
be absent. **N5 after F6**, because it lists what F6 stores.

**N0 landed** (`c5ce10b6`, `fc2677b6`, `c9d05996`, `f5b6be4c`; CI 42657430545 green). Measured on the way:
`Workspace` needed **two roots**, not one (`partsOf` answers `shared/…` for the app's folder and the account
path for everything else, and `fileAt` answers `shared/` directly rather than through a worktree, because it
is not a project and has no branch); the constructor has **no default**, and `Workspace.single(dir)` names the
one-tree shape so no production caller can get the per-account layout by omission; and the **request history
moves to `<account>/.muffy/requests`** — it is what that identity spent, and its folder was one name away from
the request library's `requests/`.

Three fixtures had to be told the truth twice: a folder that is no longer an entry (`VisibilityFeatureTest`'s
three lists), a test that asserts the old path is *gone* — which a careless rewrite turned into a tautology —
and six more sites that built `<account>/shared` by hand where `systemDir` is the call the app makes.

**N1 landed** (`270a8066`, `bc002504`): `~local` is deleted outright — `provisional`, `isProvisional`,
`ensureProvisional`, `PROVISIONAL`, `localOriginSaid`, `AccountStore.remove`'s guard, `Transfer.toIsLocal`,
`AddProjectDialog(fromAddress = …)`, `createLocalProject`, and F2's starter project with it. The only
`accounts.save` callers left are inside `addAccount`, which is what makes "no account can be made without a
key" a fact about the code rather than a convention.

**What §3.11 could not have predicted, and did not:** removing the provisional account turned
`workspace ?: return` from a dead branch into the state a phone *starts* in. That is N2's whole subject, and
it is why the step could not be folded into N1 — the audit only becomes meaningful once the state is real,
and it is the kind of audit that rots silently if it is done before the change it is about.

**§3.11's code is complete** (see the table above): N0–N6 have all landed, N6 on 2026-11-10. What remains of
§3.11 is **the writing** — N5b's popup's three sentences and the seven pages — which is a documentation
step and waits on nothing.

## 4. Cost is removed

`usage.cost` and the per-endpoint prices are OpenRouter's extension; the OpenAI protocol has no money in it.
The operator's ruling: **remove it**. So:

- `RequestDb` loses the `cost` and `reported` columns and their sums; `RequestStats` loses `cost` and
  `costReported` and the average that divided by the last one.
- The stats screen loses the money column, and the two screenshot scenarios that are about it
  (*what it cost*, *what it cost before anything*) go with it.
- An OpenRouter reply's `usage.cost` is **ignored rather than shown**: a number that exists for one
  connection and is blank for every other is worse than no number, because it invites a total that silently
  excludes most of the requests.

**What this costs, stated:** the app can no longer answer "what did this month cost" at all, even on
OpenRouter. The token counts survive in full, and tokens × a price the person knows is arithmetic they can
do; a column that is right for one host and empty for the rest is not.

Old databases keep their columns; nothing selects them, and no migration is written (the project has none,
and a column nobody reads is not a compatibility problem).

---

## 5. Steps

| step | what | state |
|---|---|---|
| **C0** | One row per `(scope, name)`: `secretRows()`, rows carry their own host and scope, general secrets listed, Remove acts on the row's scope. Fixes the three defects in §1.1. | **this run** |
| **C1** | `VaultScope` gains `Branch`; the store, the picker and `ProjectGit` learn it. `GitCredentials` asks by branch. | not started |
| **C2** | Generic model layer: base URL from the connection, drop `provider.only`/`allow_fallbacks`/`reasoning.exclude`, add `stream_options.include_usage`, catalogue optional. `model_conf.json` gains `baseUrl`. | **landed** (`9e0b7ac`…`75a628e`): the provider tags, the fallback ladder, `Failure`/`classify`, the six hard-coded addresses and `reasoning.exclude` are gone; `ActiveModel.baseUrl` + `chatEndpoint`/`Shape.address`; usage asked for on a stream |
| **C3b.1** | `Shape`: parse a template, substitute the inventory, refuse a scheme in `url`, drop empty placeholders. Lands with the **default preset first and no storage**, and the test that makes it a provable no-op: the preset's output must equal today's body **byte for byte** for chat, embedding and rerank. If it does not, the refactor changed the wire format, which is the one thing it may not do. | **landed** (`Shape.kt`, `ShapeTest`): all three presets proven equal to the bodies the app built in Kotlin, and every request already goes through them |
| **C3b.2** | Store it: `conn_<scope>_<kind>_url` and `_shape` beside the credential, inherited with it; the connection window shows a preset picker and an editor; a template that names an unknown placeholder is refused in the window rather than at the first request. | **landed** (`090df4d1`, `ddcdd591`): the shape travels with the pair as a **map keyed by kind** (one server answers three requests), `ConnectionDraft` holds the window's fields and every refusal, `ConnectionDialogs` is the window, and `OpenRouterDoor` sends the connection's shape rather than the preset |
| **C3a** | **Check this connection**: one small request from the connection window, reporting the server's own words — reachable, key accepted, model served, and any parameter it refused. "Compatible" is a claim; this is the measurement. It is the existing ping made useful rather than a new mechanism. | **landed** (`Probe.kt`, `ProbeTest`, the Check button): a real completion with the draft's own shape, because a `/models` read is a convenience some servers have and a completion is the protocol; a git check asks the project's **own address** (`ls-remote`), because a host is not a repository |
| **C3** | Vault B: `conn_<scope>_<kind>` (kind `git` \| `llm`, scope branch/project/general), **the account answered from today's records** rather than a second copy (`token_` + `Account.host`/`gitUser`; `model_key_` + the address in `model_conf.json`); the ⋮ windows on account/project/branch rows; `ProjectGit` and the runner read it narrowest-first. Per §2.2 the pair is atomic and the choice inherits field by field, capped at the scope that supplied the pair. | **landed** (storage `a709173a`, readers `d526d073`…`63e34748`, windows `ddcdd591`): `Connections.kt` (the chain, the cap, `wiredFor`, `Wired`), `ProjectGit.gitConnection` + `GitCredentials.Answer.Use` carrying the **username**, the runner resolving **per project and per branch**, and both doors obeying one resolution. **The account scope has no row** — a second copy of a secret is what the design avoids — so there is no account column to open: its pair is the sign-in token and the model key, and its model/address is `model_conf.json` |
| **C4** | The two checkmarks, off by default; `auth:` resolves a Vault B entry only when ticked and only for its host; the agent's tool description lists what it may name. | **landed** (`187a1e7c`): `Connection.nameable`, `namedConnection`/`kindFor`/`Credentials.spendable`, `auth: git`/`auth: llm` in `http`, a Checkbox in each window, and the refusal names both words |
| **C5** | Cost removal (§4). | **landed** (`57e2d4e3`): `usage.cost` and the `reported` denominator are gone from the request shape, the database (raw + all three rollups), the statistics, the agent's SQL schema and every test; the heartbeat's fourth cell counts **requests** |
| **C6** | **Landed 2026-11-09, in the shape §3.5 now describes** — the general settings are the root of the chain rather than emptied: the ceiling and everything that fed it are deleted (`ContextBudget`), the three compaction numbers live in the choice beside the model with the general settings as their root, the scoped window writes only the difference the person made, and the shape editor gained the Reset the operator asked for. | **landed** (`a5e3a8a4` … `10f9ad1a`): the numbers resolved per field (`wiredFor`), stored per scope (`Choice.target/forget/trim`), written by the window (three fields, `NumberField`), and used as written; `ActiveModel.contextLength/maxOutput`, `model_conf.json`'s two keys, `chooseModel`'s two arguments and `learnWindow` are gone. `ModelSection`'s chat rows and `ModelChoice` stay, because they are the general level's own editor. What did **not** happen: the numbers do not appear twice — Settings keeps the general three, and a scoped window shows the numbers in force and writes only what changed |

### What C6 turned out to be, and what it is not

The plan's own warning survives as a lesson: **the picker was the only place a model's size was written
down.** Deleting it first would have left every account with a model and no window. The operator's
decision removed the *consumer* instead — nothing clamps — which is why the picker could stay where it is
and the numbers could go.

What is still not built, and is deliberately not:

- **A single "request schema" row in place of the general model block.** The general level's editor is
  still `ModelSection`'s block (model, provider, effort, rates) rather than one tappable row opening a
  window. The *scoped* windows already have one shape button and the whole field set; making the general
  one match is UI polish, not a rule.
- **`ModelChoice`/`providers`/`providersOf`** still exist, because the general level still lists the
  endpoints a person may pick. They are the general editor's data, not a routing mechanism (C2 stopped
  sending tags).

**Why C0 first.** It is the only step that fixes sentences the app is currently saying falsely, it is
small, and every later step addresses a row by scope — so the addressing has to be right before a fourth
scope is added to it.

**Why C2 before C3.** The base URL has to come from somewhere before a per-scope entry can hold it; doing
C3 first would mean writing the account's URL twice.

---

## 6. What this does not solve

- **A fine-grained token's branch restriction is the host's to enforce.** GitHub sends no scope header for
  such tokens, and `TokenScopes.judge` accepts null (rightly). So the app can *choose* the token and cannot
  *check* it; what it owes is a sentence when a push is refused 403 naming that possibility, rather than the
  current generic refusal.
- **The agent already pushes with the account's git credential** through the `git` tools. The checkmark is
  about the agent spending that credential on **its own `http` requests**; it does not gate `push`.
- **Two branches, one worktree each, two agents** — per-branch credentials make that two different remote
  identities writing to one repository. Git allows it; whether it is *wise* is the person's call, and the
  branch row shows whose key it is before they run it.
- **`MediaStore` and Downloads** — reversed 2026-11-09: the three agent verbs are being **removed** and the
  SAF folder is not made (`PLAN-universal-2026-11-06` §13e.3). `DownloadsSink` stays, because it writes the
  app's own `trace.log`.
