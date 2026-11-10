# What was removed on 2026-11-10: `ConnKind.LLM`, `model_conf.json`, and the model key

**Commits:** `c4977768` (*A chat is asked with the form it picked, and minting writes a vault row*),
`5cf5e477` (the two things the compiler caught).

**The operator's two answers, and the plan's own step (§3.11.4, §3.11.5 N6, §3.6 T6):**

> *"openrouter oauth key minting should be an option in general settings in the same block with vault
> secrets management button - because it should be used to basically mint an openrouter key as a secret
> that then could be given an access scope (a particular branch, project, account, general or undefined
> which means that the secret is not availiable to agents at the time) and used in templates and http
> requests."*

and, asked directly what a chat with **no** request form should run:

> **Refuse until a form is picked.** *"A chat with no form says 'pick a template' and cannot send. The
> template becomes the ONLY answer to 'what is this chat asked with' — one fact, one reader."*

**This document is the record of what went**, so "attach it back" is a decision somebody can make from
evidence rather than by re-discovering the shape. The code is not commented out: it is deleted, and git
holds it.

**Net: 2,660 lines deleted, 439 added, across 28 files.**

---

## 1. The measurement that decided it

A chat's request form (`Schema`, `shared/requests/chat/<name>.jsonc`) already held **every fact a turn
needs**:

```
"address": "https://openrouter.ai/api/v1",   ← where the bytes go
"url":     "chat/completions",               ← the path on that address
"key":     "@openrouter",                    ← the vault row, by NAME
"target"/"forget"/"trim": …                  ← the three context numbers
"body":    { "model": …, "reasoning_effort": …, … }
```

`Wired.ofForm` read all of it and needed nothing from the vault but the key. Meanwhile `wiredFor` — 150
lines — resolved an address, a key, a model, an effort, three numbers and a request shape by walking
branch → project → account, and `ConnKind.LLM` stored a second copy of the same six facts per scope.

**Two answers to one question.** §3.11.3 had already moved the *door* (the branch screen lost its picker,
the chat gained one); N6 removes the second answer behind it.

## 2. What was removed

| removed | what it was |
|---|---|
| `ConnKind.LLM` | the second kind of connection. `ConnKind` has one member now: `GIT` |
| `Choice` | `(model, effort, target, forget, trim)`, inherited field by field per scope |
| `Credentials.choice` / `setChoice`, `CHOICE_SUFFIX` | the store's half: `conn_<scope>_llm_choice` rows |
| `Connection.shapes` + `shapesJson` / `shapesOf` / `shapeFor` | a request's path and body stored per connection |
| `Connection.keyless` | "this server wants no key" — a form says it by naming no key |
| `wiredFor` (150 lines) | the resolver: narrowest-first walk, per-field inheritance, the host guard |
| `AgentWiring.wiredFor` | its only production caller |
| `ConnectionDraft`'s model/effort/numbers/shape half | `opening(model=…, effort=…, limits=…, shape=…)`, `choice()`, `shapeProblem()`, `numberProblem()`, `tokens()` |
| `LlmConnectionDialog` (200 lines) | the window that edited the above |
| `ShapeDialog` (77 lines), `NumberField` | its two sub-windows |
| `Pending.ModelConnection` + 3 menu entries | the doors that opened it: account row, branch row, project row |
| `ModelConfig`, `ModelConfigFile`, `model_conf.json` | the file: `active` model/effort/baseUrl, its version, its "will not parse" state |
| `AppViewModel.modelConfig` / `modelConfigProblem` / `readModelConfig` / `writeModelConfig` / `setModel` / `setEffort` / `resetModelConfig` | the file's readers and writers |
| `Credentials.modelKey` / `modelKeyState` / `setModelKey`, `model_key_<login>` | the second record of the minted key |
| `AppViewModel.saveModelKey` / `modelKeyState` / `modelKeySaved` | the second save path |
| `Workspace.APP_FILES`, `isAppOwned` | the rule that hid `model_conf.json` from the browser — with no member left, a rule about nothing |
| `AgentWiring.DEFAULT_MODEL` | a fallback with no caller once the fallback itself went |
| `KeyRow` | replaced by `MintedKeyRow` |
| `ModelConfigTest` (9 tests) | its whole subject |

### What replaced it

**One resolver.** `Wired.ofForm(ws, credentials, project, branch)` — already written, already tested. A chat
with no form gets `missing` set and refuses in words, naming the picker.

**One record of the minted key.** `finishOpenRouterSignIn` and the paste path both end at
`saveMintedKey(key)`, which writes the vault's `@openrouter` row for OpenRouter's host — the name the
shipped form already says. `hasModelKey` asks the vault for that row at that host.

**One row on the settings screen.** `MintedKeyRow` sits in the Secrets block beside the vault button, as the
operator asked, and names the secret it wrote, because "which secret does my form name" is the question that
follows.

**One constant for the model the app ships.** `Schemas.DEFAULT_MODEL` replaces
`ModelConfigFile.DEFAULT_MODEL`, and it sits beside the shipped form that actually asks for it.

## 3. What was deliberately kept, and why

- **`ConnKind` itself, with one member.** A git credential is still a pair — an address and the token for
  it — that travels together, and `namedConnection` / `gitConnectionFor` / `spendable` still read it. The
  enum survives because "which kind a name is" is still a question (`auth: git`), and it has one answer.
- **`Connection.nameable`** — the checkmark that lets an agent name a token. It is the permission rule, and
  it has nothing to do with the model half.
- **`ActiveModel`** — the value object `Wired` is built from. Only its *file* went.
- **`Shape`, `ShapeKind`, `Shape.preset`** — a request's shape is the **form's** own field, and the form
  library is untouched.
- **`ContextBudget` and the three numbers** — they live in the form's `target`/`forget`/`trim`, and the
  general settings are still their root.
- **`Probe`** — the connection check still exists for git; only its model branch went.

## 4. What a person loses, stated plainly

- **A chat cannot send until it picks a form.** A fresh install ships `chat.jsonc`, so this is invisible
  until somebody deletes it. When they do, the chat says so and names the picker.
- **No per-branch or per-project model.** Two chats on one branch now ask with whatever form each picked.
  That is the intent — *a template is a property of a chat, not a branch* — but it is a capability that
  existed and no longer does.
- **No `auth: llm`.** An `http` call or an MCP article that named the model key as `auth: llm` must name the
  vault secret instead: `auth: @openrouter`. Nothing else about `auth:` changed.
- **`model_conf.json` is gone from disk.** A file left behind by an older build is not read, not deleted,
  and not migrated: the project has no migrations, and a file nothing reads is not a compatibility problem.

## 5. The tests that lost their subject, and what they say now

| test | what happened |
|---|---|
| `ModelConfigTest` | **deleted.** Nine tests about a file that no longer exists. Its one surviving fact — the model the app ships — is asserted by `SchemasTest` against `Schemas.DEFAULT_MODEL` |
| `ConnectionsTest` | **13 of 30 removed**, the whole "what a turn is asked with" section. What remains is the half with the safety property: the scope walk, the host match, `namedConnection`, the checkmark |
| `ConnectionDraftTest` | **12 of 21 removed**, the whole llm window. What remains is the git window, the inheritance rule, and the grant |
| `ModelKeyTest` | **rewritten** against `saveMintedKey` and the vault: a write that cannot be read back is still refused, a kept key still closes the window, and the row still names what it wrote |
| `ModelSectionTest` | three `model_conf.json` tests replaced by two about the minted key's row |
| `AccountsScreenTest`, `BranchesScreenTest` | the "Model connection…" menu entries are asserted **absent**, which is the claim that they cannot come back unnoticed |

## 6. The two defects the compiler caught

Worth recording because both were in the *cutting*, not the design:

1. **`templateNames()` lived inside the section that was deleted.** The settings row had nothing to count.
   It is back beside `chatForms`, with a comment saying why they are two functions.
2. **`ModelSection` still read `modelConfigProblem`** and drew "this file will not read" about a file that
   no longer exists — a sentence about nothing, which is the kind of thing a sweep for deleted *symbols*
   does not find because the symbol was a *property*.
