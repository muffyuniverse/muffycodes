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
| a push to a host that is not the account's own uses that host's secret, or says which one to add | `ProjectGit.credentialFor` → `GitCredentials`, before anything leaves the phone | **True, and tested** (`GitCredentialsTest`, and a `ProjectGitTest` that pushes to `gitlab.com` and gets a sentence rather than a request) |
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

### P1 — a credential is data, not a provider table  ✅ *landed*

`GitService.credentials()` hardcoded two GitHub conventions in one function: the token was the
**username**, and any host that was not GitHub got **no credential at all and no message**. The second was
worse than it looked — it is a rule about *whose token it is*, written where the token becomes a header, so
the vault's own credential for `gitlab.com` could never be used: the project was refused before it was
offered, and the server's 401 was the only explanation anybody saw.

**What landed:**

1. **`GitCredentials`** — a pure object, no Android, tested with strings. Three answers: *use this token*,
   *nothing to send* (no host: a local path), or *a sentence saying why*. The order is: a secret for that
   host wins; otherwise the account's token **only for the account's own host**; otherwise say which secret
   to add, **before anything is sent**.
2. **`GitService.credentials`** keeps only the mechanism, and learns the one portable thing it was missing:
   the username comes from the remote's address when it names one (`x-token-auth@bitbucket.org` is git's own
   convention, and the only place a host-specific name can live without a table), otherwise the token is the
   username with an empty password.
3. **`push`, `pull` and `sync` ask once, at the top** — one answer instead of three — and `ProjectGit` takes a
   `vaultToken` provider, so a `ProjectGit` in a test needs no keystore.
4. **The app passes `Credentials::secret`**, which already answers narrowest-first (project, then account,
   then shared). So supporting a host is an act in the vault, not a commit here.
5. **Sentences that named GitHub stopped naming it** in the places a person reads them during a sync.

**Not in P1, and known:** `GitService.clone` still refuses a non-GitHub host in its own words, because the
app's *add a project* flow is GitHub's API and a non-GitHub clone is not reachable from the UI. When that
flow becomes portable the guard moves to `GitCredentials` with the other verbs. Recorded rather than
half-done.

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

### P4 — `ssh`: the second protocol, and the smallest thing that speaks it

**The operator, correcting me:**

> *"http and ssh are 2 separate protocols. you cant send ssh request with http, and most servers dont have
> web api - but they do have ssh. we need ssh tool, but really low footprint one, without building it
> ourselve."*

That correction is right, and the argument I made against it was wrong in a specific way. I had claimed `http`
"covers the same ground for anything with an API". It does not: an API is a thing a server *may* have, and SSH
is a thing a server *does* have — it is the oldest and most universal remote interface there is. A tool whose
only verbs are `http` and `git` can reach a web service and a repository, and nothing else. **Portability is
not only about git hosts; it is about which servers the app can talk to at all**, and SSH is the protocol that
makes the long tail reachable.

So the question is not *whether* an SSH tool belongs here. It is **which library, and what is the smallest
honest shape of the tool**.

#### The library: `com.github.mwiede:jsch`

Measured, not assumed — the numbers are the jar on Maven Central, read on 2026-11-10:

| | `com.github.mwiede:jsch:2.28.8` | `org.connectbot.sshlib:sshlib:0.5.0` (cbssh) | `arcshell-ssh` |
|---|---|---|---|
| jar | **700 KB** (717 KB, 380 classes) | **2.6 MB** (sshlib 2.2 MB + protocol 416 KB) | not published |
| dependencies | **zero** — the POM declares none | ktor-network, tink, kyber, keccak, jbcrypt, kstatemachine, slf4j, coroutines | vendored BC fork |
| licence | **Revised BSD** (+ ISC jBCrypt, JZlib BSD) | Apache-2.0 | Apache-2.0 |
| on Maven Central | **yes** | yes | **no — 404** |
| Android | pure Java, no `java.nio.file`, no native code | same | same |

**`jsch` wins on the only axis the operator named.** Zero dependencies is not a small advantage over six
dependency families: it means no Ktor inside an app that has its own networking, no Tink inside an app whose
operator has said *no encryption anywhere*, and no version-alignment work ever. 700 KB against 2.6 MB, for the
same protocol. `arcshell-ssh` is disqualified by not being published — vendoring 189 files of someone's
BouncyCastle fork is the opposite of "without building it ourselves".

`jsch` is the maintained fork of JCraft's JSch (the library every Java SSH client has used for twenty years),
and it has what the tool needs: `ChannelExec`, `ChannelSftp`, `ChannelShell`, public-key/password/
keyboard-interactive auth, and **`HostKeyRepository`** — the interface that makes host-key pinning the app's
own business instead of a `known_hosts` file on a phone's filesystem.

#### The shape: one verb, and the vault

1. **One tool, one verb: `run`.** `ssh(command, host)` — not a shell, not a session, not a port forward. A
   `shell` verb is a different product (an interactive terminal belongs in the UI, not in a tool a model
   calls), and a forward is a capability nobody has asked for. `run` returns stdout, stderr and the exit
   status, because a command that failed and a command that printed nothing are different answers.
2. **The credential is a vault secret, like every other one.** No new store: the existing vault row is
   `(name, value, host, scope)`, and SSH reads the same row shape — a private key or a password, bound to a
   host, at project/branch/account scope. `ConnKind` does not grow a member: a secret is a secret, and what
   makes it an SSH secret is the **form** that names it, exactly as a chat's form names a model key.
3. **Host keys are pinned on first use, and the fingerprint is shown.** `HostKeyRepository.check` returns
   `NOT_INCLUDED` the first time, the app records the key and says the fingerprint in words; a *changed* key
   is a refusal that names it, never a prompt to accept. This is the one place where the "no encryption
   anywhere" rule does not apply, because a host key is not encryption — it is identity, and skipping it
   makes the tool worse than useless.
4. **The refusal speaks, and names the scope.** A missing key, an unreachable host, a rejected auth: each says
   which of the three it was and which vault row would fix it. A silent no-op here would be indistinguishable
   from a command that printed nothing.
5. **The tool list does not grow by a form.** `ssh` takes `host` and `command` and nothing else; the address
   is `user@host` in the one argument, because inventing `user`, `port`, `key` as separate keys is the same
   mistake `http`'s four-argument version made and the operator already rejected.

#### What this does **not** do

- **It does not weaken the branch boundary.** The boundary in §1 is about *folders on the device*; `ssh`
  reaches the network, which is the token's business, not the app's. It is not a write door and does not
  pretend to be one.
- **It does not make the app an SSH client.** No terminal, no sftp browser, no port forwarding, no agent
  forwarding. Those are a product; this is a tool.
- **It is not a reason to remove `http`.** They answer different questions — `http` speaks to a service that
  offers one, `ssh` speaks to a machine that does not.

#### Order, and the one open question

The work is small and self-contained: add the dependency, one `SshRun` object (connect, exec, collect, close,
with a deadline), one tool registration, one vault reader, one `HostKeyRepository` backed by the vault. Tests
are the ordinary kind — a local `sshd` is not available in CI, so the transport is faked at the `SshRun`
boundary and the *policy* is what is tested: pinning, a changed key, a missing secret, a non-zero exit.

**The open question, for the operator, before any of it is built:** *a private key in the vault has a
passphrase or it does not — and if it does, where does the passphrase live?* The two answers are a second
secret (a row naming a row), or "keys with passphrases are refused, generate a key for this app". The second is
smaller and safer; the first is what a person with an existing key will want. **This is the decision that
cannot be undone later, so it is asked rather than guessed.**

---

## 5. What is not solved by any of this

- **No per-branch scope exists in git or in any token.** "This agent may push only `feature`" is not
  expressible at a host. What *is* expressible is "this agent may only ever push the branch it stands in",
  which the app already enforces structurally — and that is the whole of what a branch means here.
- **A token that can write `main` can write `main`.** Host rulesets exist on some hosts and not others; the
  app's answer is that no *tool* can name a ref, and the person's merge is the person's.
- **`http` deletes the app's GitHub vocabulary** — `ciSaid`'s sentences, the "already gone is not a failure"
  rule. That is the price of letting the host's API be the API, and P3's last row is where the remainder
  lives.
- **`ssh` does not make a server portable, only reachable.** A command run over SSH is the *server's* command:
  `systemctl restart nginx` on one host is `rc-service nginx restart` on another, and no amount of tooling
  here closes that gap. The tool's job is to deliver the command and report what came back honestly — not to
  know what the command should have been.
- **A shell on a server is the largest blast radius the app can offer.** `run` is one command per call with a
  deadline, not a session that can be held open, and that limit is deliberate rather than incidental.

## 6. Order, and why

P0 first because it is the operator's named concern, it is small, and it survives every portability decision
that follows (it is about folders, not hosts). P1 second because it is the precondition for P2 — a credential
that can only be a GitHub token cannot be sent to a host that has not been thought about. P2 third because
deleting a typed tool is only safe once `http` can do everything it did, and that means pagination and tags.

**P4 is last and is independent of P0–P3**, which is why it is written down and not started: it touches no
credential the earlier phases change, and it is blocked on one answer (the passphrase question above) that is
the operator's, not mine. When that answer exists the work is a day, not a phase.
