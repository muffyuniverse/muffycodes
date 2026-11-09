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

`<account>/.muffy/schemas/<name>.json` — **`Schema`** (`Schema.kt`):

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
| the worktree's chat request | `.muffy/agents.json`, per `project/branch` — the file that already holds the session | a chat schema |
| the embedding model | `.muffy/model_conf.json`'s `embedding` | an embedding schema |
| the rerank model | `model_conf.json`'s `rerank` | a rerank schema |

**Which pointer named a schema is what it is for**, so a file cannot disagree with the place that chose it:
the vocabulary it is checked against travels with the question, not with the document.

**A branch with nothing chosen refuses, naming the picker.** A worktree that silently ran another branch's
request would spend the key on a model nobody chose for it. There is no `default.json` fallback and no
"shipped default for a branch", for the same reason §3.5 has no cap: a wrong choice the server refuses is a
sentence, and a wrong choice the app picks is a surprise on a bill.

**No inheritance and no scopes.** The branch's file is the branch's request; the project and the account
have none, because they cannot be *sent* anything. The general settings keep an **entry point** — the
"Model connection" row becomes "Request templates", opening the folder — and nothing else about chat.

### The key (§3.6.3)

**Looked up by the host the schema names, not stored in the file.** A schema is a document people read,
copy and hand to each other; a key in one is the leak the vault exists to prevent. So at send time:

1. the schema's `address` names a host;
2. the vault is asked for **a row bound to that host** — the same `hostCovers`/`keyScope` rule Vault A
   already uses, unchanged, which is the only part of this design that was ever about safety;
3. **no row for that host is a refusal in words**, naming the host and the place to add a key — not a
   request with the account's key offered to a server it was not made for.

**What survives from §2.3's checkmarks:** `auth: llm` still resolves a named `llm` row for a tool call.
What dies: `ConnKind.LLM`, `Choice`, `Connection.shapes`, the shape window, the provider tags, the
catalogue, and the four-scope llm walk in `wiredFor`.

### Git does NOT become files — the measurement behind that answer (2026-11-09)

The operator asked directly: should git also be templates, or the app's own structured fields?

**Structured, and the two protocols differ in a way that decides it:**

| | a chat request | a git remote |
|---|---|---|
| the destination | the schema's own `address` | **`remote.origin.url`** — git's fact, read, never stored twice |
| the variable part | the whole body: `max_tokens` vs `max_completion_tokens`, `system` vs `developer`, the reasoning echo, which field carries the text to embed | **a username and a token** — basic auth is `user:token`, one word of freedom |
| a template's freedom | large enough to be worth a document | **none: a three-key JSON with no options** |

**And a second reason that is not about shape:** documents get copied and committed, so a git template
would be `host + user + token` in plaintext, or a file whose two visible keys point at a credential row —
a form with extra steps and no gain.

**A branch cannot change the destination, by git's own rule.** A worktree shares `config` through
`commondir` (`Worktrees.kt:118`), so `remote.origin.url` is the project's. What a branch *may* override is
its **credential** — the token, and the username, which must travel with it because a token presented under
the wrong name is a different identity (`GitCredentials.Answer.Use`). `gitConnectionFor(project, branch, host)`
already walks branch → project → account and filters by host, which is exactly the rule the operator
stated; nothing about git needs to change shape.

**One defect found while checking, and it is in this plan's scope:** the git window asks for the host as
free text although `connectionShown` already knows the project's remote host. A typo there produces the
*withheld* sentence at push time — correct, but late. The window should open on the project's remote host
and say where the value came from.

### Steps

| step | what | state |
|---|---|---|
| **T1** | `Schema` + `Schemas`: the document, the folder, the three shipped requests, the name rules. | **landed** (`f82658bc`, fixed `bd002dad`): the folder's existence is the flag — a folder somebody emptied stays empty, and deleting it is the one way back to the defaults |
| **T2** | The catalogue goes: `ModelCatalogue.kt`, `CatalogueKind`, `EndpointInfo`/`ModelInfo`, `ModelChoice`, `providers`/`providersOf`, `addModel`/`removeModel`/`addProvider`/`moveProvider`/`removeProvider`, `addPicked`, the *scan semantics* that read `inputModalities` from it, and `effortFor`/`effortRank`. `ActiveModel.provider` goes with them; a sentence that wants the word asks `providerOf(address)`. | **next** — the deletion is stashed and half-done (`ModelConfig`, `AppViewModel`) |
| **T3** | The pointer and the send: `.muffy/agents.json` gains a schema name per `project/branch`; `AgentWiring.wiredFor` becomes `(project, branch) → Schema + key-by-host`; the branch window picks a file from `Schemas.names`. | after T2 |
| **T4** | The two vector models reference files: `PickedModel` becomes a name, `Embedder`/`SemanticSearch` read the schema's address and body, and the embedding/rerank pickers become one dialog over `Schemas.names`. | after T3 |
| **T5** | The general scope's entry point: "Request templates" opens `.muffy/schemas`; `ModelSection`'s chat rows, `ModelChoice`, the key row and the rates line go; `model_conf.json` keeps only the two pointers. | after T4 |
| **T6** | The connections window loses its llm half: `ConnKind` becomes git-only, `Connection.shapes`/`shapeUrl`/`shapeBody`/`shapeProblem`/`ConnectionDraft.shape*` and the shape dialog go; the git window opens on the project's remote host and names it. | after T5 |

**Why T2 before T3.** A picker that lists files must not sit beside a list of tags: two ways to name a
model, one of which is invisible in the file, is the drift this section exists to remove.

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
- **`MediaStore` and Downloads** — `PLAN-universal-2026-11-06` §13e, still open, and unrelated to this.
