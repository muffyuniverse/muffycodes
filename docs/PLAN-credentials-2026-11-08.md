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

Scopes: **branch → project → account.** No general scope, deliberately: a credential is an *identity*, and
the phone has no identity of its own — every request is made as somebody. (The provisional account is still
an account.) General belongs to Vault A, where a shared service key is a fact about the app rather than
about a person.

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

### 3.5 Model selection moves to the branch — with the account as the default

- `model_conf.json` at the account root keeps the **default** — what a new branch inherits, and what the
  service polls with when a branch says nothing. It also keeps the embedding/rerank choices.
- A branch's override (connection, model, effort) lives in the branch's own app state, `.muffy/` beside
  `agents.json` — gitignored, never sent, a name rather than a value.
- The runner already reads the model **per request** (`AgentWiring.kt:132`), so this is a seam, not a
  redesign.
- Settings keeps one row — *Default model* — plus the connections it can pick from. Add/remove belong to
  the window the row opens, which is the shape the model picker already has.

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
| **C3** | Vault B: `conn_<scope>_<kind>` with the account entry seeded from today's `token_`/`model_key_`; the ⋮ windows on account/project/branch rows; `ProjectGit` and the runner read it narrowest-first. | not started |
| **C4** | The two checkmarks, off by default; `auth:` resolves a Vault B entry only when ticked and only for its host; the agent's tool description lists what it may name. | not started |
| **C5** | Cost removal (§4). | not started |
| **C6** | Settings shrinks to *Default model*; add/remove move into the connection window. | not started |

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
