# PLAN — portability is the rule, and the token is the boundary

**Written 2026-11-08, from the operator's requirement in their own words:**

> *"the system needs to work with all account plans and be independent from github specifics (it should rely
> solely on git protocol and http tokens), so that it can be used with any git hosting provider. The point
> that concerns me is agent from one branch modifying repo of another branch — but even that is not a hard
> rule, universal compatibility stays the top priority."*

Everything in this file follows from that sentence, including the parts that **delete** what earlier sections
of `PLAN-universal-2026-11-06.md` proposed. Where this file and an older one disagree, this one is right.

## 1. The sentence that organises everything

There are **two boundaries**, and only one of them belongs to the app.

| | decided by | portable? |
|---|---|---|
| what may be written on the device | **the app** — this is files in folders | **yes, exactly** |
| what may be pushed to or read from a remote | **the token**, at the host | no, and that is the point |

So:

> **An agent may write inside its own worktree; every other folder on the device is read-only. Everything
> off the device is the token's business, not the app's.**

That is provider-independent by construction, and it is why `http` is the correct API funnel rather than a
compromise: the app never needs to know what a permission *means*, only which host a secret may be sent to.

## 2. What this strikes down, and why

Recorded because each of these was proposed in good faith, measured, and then contradicted by the rule.

| Struck | Why |
|---|---|
| **Main as trunk-only, releases on the branches screen** | It restricts what a person may do without adding one grain of enforcement. §13d already leaves `main`'s tree a place a person can enter, and a release is the person's act. **Not built. Not to be built.** |
| **Rulesets, actor rules, environments, required reviewers as the gate** | All GitHub. A host without them gets no protection from a design that depends on them. |
| **GitHub Apps, machine users, fine-grained PATs as the agent's identity** | A second registration and a permanent key, to buy a per-repository scope that `push` cannot exceed anyway (see §3). Provider-specific, plan-specific, and not needed. |
| **`ci`, `runs`, `artifact`, and the release *dialog* as app features** | They hardcode one host's API. The portable replacement is a **tag**, and the rest is `http` with a secret. Phase P3. |

**What survives from §13b.2/§13b.4, because it is not provider policy:** the environment gate was never the
boundary — the boundary is that the app's own publish path is a person pressing a button, and the agent has
no tool that speaks to a release at all. That is true on every host.

## 3. The device half — what is already true, measured

| claim | where | status |
|---|---|---|
| `push` cannot name a ref | `AgentTools.push()` takes no arguments; `GitService.pushNow` sends `currentBranch(repo) → remoteBranch(repo, local)` | **Structural.** An agent can only send the branch it stands in. |
| a write to another project is refused | `mayWrite` → `projectOf` → the "belongs to `$owner`" sentence | **True, and tested** (`AgentTest`: *another project may be read, and not written*). |
| a write into another **branch's** tree of the same project is refused | falls through the owner check (same project), then `rel` gives `../.muffy/worktrees/…` and the dot-name fallback refuses it | **True by accident.** The sentence printed is *"A name that starts with a dot belongs to the app"* about a path whose first segment is `..` — a rule the path did not break. **No test holds it.** |
| a write through the `system` link is refused | `projectOf` canonicalises (its `partsOf` resolves links), so the target reads as `system` and the "another project" refusal fires | **True by accident**, and with a sentence that offers an action that does not exist: *"changing it needs `system` open"* — there is no way to open it. **No test holds it.** |

**So the boundary holds, but by two accidents and with refusals that name the wrong rule.** P0 makes it the
rule rather than the outcome: one canonical test in the funnel, three sentences that name what a path
actually reaches, and four tests that fail if the guard goes. A guard that holds because two spellings
happen to agree is one that stops holding the day one of them changes.

## 4. The phases

### P0 — the boundary is a fact, not an accident  ✅ *this commit*

1. **Canonical containment in the write funnel.** `insideWorktreeOrAgent` gains the test the read path
   already had (`withinProject`, `AgentTools.kt:1959`): the target is canonicalised and must be under the
   canonical tree. It then no longer matters whether `projectOf` and `rel` happen to agree about a path.
2. **The refusal names what the path reaches** — another branch's tree with its branch named, or the
   account's own folder — instead of claiming a dot name, and instead of offering to "open" a folder that
   has no branches and no agent. One sentence each, because a refusal that names the wrong rule sends the
   model to try another spelling of the same path.
3. **Falsification tests**, each of which fails if the guard is removed: `write`, `delete`, `move` and
   `restore` against another branch's tree and against `system` through the link; plus the positive case
   (the same acts inside the agent's own tree still work), because a guard that refuses everything also
   passes the first four.

**Deliberately not in P0:** nothing about credentials, nothing about GitHub. This phase is portable by
construction — it is about folders.

### P1 — a credential is data, not a provider table

Today `GitService.credentials()` hardcodes two GitHub conventions in one function: the token is the
**username**, and any host that is not GitHub gets **no credential at all and no message**.

1. `Secret(value, host, user, scheme)` — the store keeps the username git should send and the scheme an
   `Authorization` header uses, beside the host it already keeps. The host rule does not change: a secret
   bound to `api.example` still cannot be sent to `evil.example`.
2. `GitService.credentials(remoteUrl, secret)` builds `UsernamePasswordCredentialsProvider(user, value)` from
   the record, and stops consulting a list of providers.
3. `http` builds its header from the scheme; `push` uses the same record, so the two cannot disagree about
   what a credential is.
4. **A project on another host becomes possible**: a secret named `git`, bound to that host, is the
   credential its `push` uses. Absent one, the account's own token is used only for the account's own host —
   and when neither applies the refusal *says so* instead of returning null.

### P2 — the typed GitHub tools become the first user of `http`

`ci`, `runs`, `artifact` and the release dialog are provider logic in the app. Each becomes: a sentence in the
prompt, a secret bound to the host, and `http`. Two small pieces of plumbing are needed first, both measured:

- **`Link` header** is already in `Fetcher.Answer.headers` (`:66`) and is used only for `Location` and rate
  limits (`AgentTools.kt:4242`, `:4284`). Surface it, and `http` can walk pages.
- **A tag is a release that works everywhere.** `git push --tags` (a `tag` op on the existing `git` tool) is
  the portable half; whatever the host builds from it is the host's business.

### P3 — what stays the app's, and why

| stays | because |
|---|---|
| `auth:` → vault → **host binding** | The one guard that constrains the agent rather than gating a person, and it is provider-neutral: it is about DNS names. |
| `push`/`pull` without a ref argument | The portable version of "an agent cannot touch another branch", and it needs no host feature. |
| the **sentence** on a refusal | A bare 403 is what a person sees when a token is under-scoped. The app is the only thing that can say which dial to turn. That is explanation, never enforcement. |

## 5. What is not solved by any of this

- **No per-branch scope exists in git or in any token.** "This agent may push only `feature`" is not
  expressible at a host. What *is* expressible is "this agent may only ever push the branch it stands in",
  which the app already enforces structurally — and that is the whole of what a branch means here.
- **A token that can write `main` can write `main`.** Host rulesets exist on some hosts and not others; the
  app's answer is that no *tool* can name a ref, and the person's merge is the person's.
- **`http` deletes the app's GitHub vocabulary** — `ciSaid`'s sentences, the "already gone is not a failure"
  rule. That is the price of letting the host's API be the API, and P3's last row is where the remainder
  lives.

## 6. Order, and why

P0 first because it is the operator's named concern, it is small, and it survives every portability decision
that follows (it is about folders, not hosts). P1 second because it is the precondition for P2 — a credential
that can only be a GitHub token cannot be sent to a host that has not been thought about. P2 third because
deleting a typed tool is only safe once `http` can do everything it did, and that means pagination and tags.
