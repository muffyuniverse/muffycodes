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
| **address + key** | **no** — a scope has its own pair or inherits the whole pair | a key is bound to one host. A project with its own address and an inherited key would send that key to a host it was never stored for — the one hole this vault exists to close |
| **model id, effort, window, reply** | **yes**, field by field | a model id is portable (it is the person's business whether that server serves it), so changing only the model must not mean re-typing the key — which would put a second copy of a secret on the phone |

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

### 3.5 The chat model is chosen at the scope that runs it — and Settings holds none

**Settings is the app's own settings, not any account's.** It keeps the embedding and rerank model (with
their own key and endpoint, exactly as a scoped chat connection has) and nothing about chat.

The chat model is chosen **per account, per project, per branch** — in the window that adds the thing, and
in the ⋮ settings window of the row afterwards. Each window opens on the values it inherits and lets the
person change any of them (§2.2's table says which fields inherit independently).

**Why the account is not the same as general.** An account is an identity; a project is one repository; a
branch is one working tree. Chat *is* per identity — the log says who spent the tokens — so the account is
the widest place a chat choice can live, and a new account asks for one. That is also why the service polls
correctly with no "default model" anywhere: it polls as an account, so it asks that account.

**Consequences, stated rather than discovered later:**

- `model_conf.json` keeps only `embedding` and `rerank`. Its `active` block, and with it `ModelChoice`, the
  `models` list, `providers`, `providersOf` and `ModelSection`'s chat rows, **go**: C2 stopped sending
  provider tags, so the list only ever fed a pin that no longer exists.
- **`ContextBudget.CEILINGS` loses its key.** It is a table of measured ceilings indexed by *provider tag*,
  and there are no tags left to index. The numbers move to where they already half lived — stored with the
  choice (`contextLength`, `maxOutput`) and filled from the endpoint read when a picker is used — and a
  choice nobody measured falls through the existing ladder to the floor. Deleting the table without saying
  where its numbers went would be losing a measurement, so the measured values move into the endpoint read's
  defaults rather than into a table nothing indexes.
- The runner already reads the model **per request** (`AgentWiring.kt:132`), and a session is already keyed
  `project/branch`, so this is a seam rather than a redesign.

---

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
| **C2** | Generic model layer: base URL from the connection, drop `provider.only`/`allow_fallbacks`/`reasoning.exclude`, add `stream_options.include_usage`, catalogue optional. `model_conf.json` gains `baseUrl`. | not started |
| **C3** | Vault B: `conn_<scope>_<kind>` (kind `git` \| `llm`, scope branch/project/account/general), the account entry seeded from today's `token_`/`model_key_`; the ⋮ windows on account/project/branch rows; `ProjectGit` and the runner read it narrowest-first. Per §2.2 the address+key are atomic and model/effort inherit field by field. | not started |
| **C4** | The two checkmarks, off by default; `auth:` resolves a Vault B entry only when ticked and only for its host; the agent's tool description lists what it may name. | not started |
| **C5** | Cost removal (§4). | not started |
| **C6** | Settings holds **no chat model at all**: it keeps embedding and rerank (key + endpoint + model + dimension). `model_conf.json` drops `active` and the `models` list; `ModelSection`'s chat rows, `ModelChoice`, `providers`/`providersOf` and the tag-keyed `ContextBudget.CEILINGS` go with them (§3.5). | not started |

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
