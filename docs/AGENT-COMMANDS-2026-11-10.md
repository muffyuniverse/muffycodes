# The agent's commands, as the code has them

**Written 2026-11-10 from `AgentTools.kt` itself** — `KEYS` for the arguments each command takes, and the
`spec(...)` list for what each one tells the model. This is a reference to what is **built and shipped**, not a
plan. Nothing here is proposed.

The authority is the code. Where this document and `AgentTools.kt` disagree, the code is right and this file is
stale — the same rule the plan tables are held to.

---

## 1. The set

**Twenty commands.** The list is part of every request's cache prefix, so it is kept short deliberately: a
command is added only when nothing else can answer the question.

| command | one line | arguments |
|---|---|---|
| `help` | what the other commands are, and how to use one | `of` |
| `search` | find files by where they are, what they say, and whether it is a saved version | `path`, `grep`, `history`, `depth`, `count`, `total`, `word` |
| `read` | read a file, or one of its versions | `path`, `from`, `to`, `grep`, `revision`, `multi`, `word`, `numbers` |
| `write` | make a file or change it — **one command for both** | `path`, `grep`, `from`, `to`, `text`, `multi`, `word` |
| `forget` | take rows out of the agent's own context | `ids` |
| `delete` | delete a file, or a folder and everything in it | `path` |
| `move` | move, copy, or rename | `path`, `into`, `copy` |
| `restore` | put an old version back | `path`, `revision` |
| `background` | run any command, answer written to a file | `tool`, `args` |
| `browse` | read a web page with JavaScript run — the page is dumped to a file | `url`, `wait`, `eval` |
| `shot` | photograph a page to a PNG | `url`, `full`, `eval` |
| `look` | attach pictures to the next message | `paths` |
| `query` | SQL over the request log, written to `agent/sql/` | `sql` |
| `archive` | zip something, or unpack a zip | `path`, `unpack`, `seal`, `key` |
| `http` | send one request, written as the request | `request` |
| `mcp` | ask a server described by an article | `server`, `tool`, `args` |
| `diff` | what changed between two revisions | `path`, `from`, `to` |
| `log` | the versions of a file, one line each | `path`, `count` |
| `pull` | fetch and merge `origin/main` | *(none)* |
| `push` | make a version and send it, optionally tagging it | `message`, `tag` |

**No command has ops any more.** `git` was the only wrapper, and its two ops became `diff` and `log`:
`diff` is a question about two versions, `log` is a question about one path's history, and neither
needed an `op` key to say which. `tag` moved to `push`, where the version it marks is made — a tag on a
push is a tag on the commit that push creates, which is the only thing a tag can mean. That also
deleted two refusals: a tag used to have to refuse off `main` and over unsent work, and on a push both
are true by construction.

**`search` lost `mode` on 2026-11-10.** It accepted one value (`regex`), refused the other by name, and
refused to appear without a `grep` — a parameter whose only work was refusing. Its arguments are now
`path` (a pattern over the path), `grep` (a case-insensitive per-line regex), `history`, `depth`,
`count` (how many paths) and `total` (the tally instead of the lines). `paths` was the count and
`count` was the flag, which is the confusion the swap removed.

**No command names a host.** Not one of them mentions GitHub, GitLab, or any provider. That is the
portability rule enforced where it is visible — in the list a model reads.

### `help` — the one command about the others

`{"tool":"help"}` answers every command with one short line; `{"tool":"help","of":"search"}` answers that
one in full — its keys, what each means, and its shape.

**It exists so that refusals can stop explaining themselves.** Until 2026-11-10 a wrong key got a
hand-written sentence about that key: `lines` was told it had been renamed, `mode` was told `semantic` was
gone, and each of those sentences was a second description of a command, able to drift from the spec that
described the same thing. Now every shape mistake gets the same answer — *this command does not take that;
it takes these; ask `help <command>`* — and the explanation is **the same text the model was already
sent**, because both are built from `specs()`.

**The line it draws**, from the operator: *"it is used when the shape of arguments is wrong, not when they
themselves error out."* So `help` answers unknown keys, removed keys and impossible combinations. It does
**not** answer a wrong value — a range past the end of a file, a revision that does not exist, a regex that
will not compile. The test is **could `help` have prevented it?** A refusal that pointed at `help` for a
missing line 400 would send a model to a page that cannot help it.

---

## 2. The ops

### `git` is gone — `diff` and `log` are commands, `tag` is a push argument

`git` was the only command with an `op`, and it was two questions wearing one name:

| was | is now | why |
|---|---|---|
| `{"op":"diff",…}` | `diff` | a question about **two versions** — `path`, `from`, `to` |
| `{"op":"tag","to":…}` | `push {"tag":…}` | a tag marks **the version a push makes** |
| — | `log` | **new**: which versions there are, one line each |

**`log` answers the question that comes first.** `diff` needs two revision ids, and `search
{history:true}` answers a different question — which versions' *text* matches a pattern — per matching
line. `log` says which commits touched a path, `id  when  subject`, newest first; `count` is how many
(20 by default, 200 at most), and the answer says when it stopped.

**`tag`'s two refusals are gone, because the state they refused cannot happen.** As an op it had to
refuse off `main` (a tag there marks work `main` does not have) and over unsent work (a tag over a
commit the remote does not have would send that commit *with* the tag and leave `main` behind it). On
a push, the push has just made the commit and sent the branch it stands in — so both are true by
construction. **The refusals were replaced by a state that cannot occur**, which is a better fix than a
check. The branch rule survives as a sentence rather than a refusal: the work is pushed, and the answer
says it was not tagged and why.

`tag` writes the same `release` trace line the person's button does, because it is the same act.

### `write` — three shapes, one `text`

`text` is the new text in **every** shape:

| shape | what it does |
|---|---|
| `{path, text}` | writes the whole file, **making the file and its folders if absent** |
| `{path, from, to, text}` | replaces those lines |
| `{path, grep, text}` | replaces every match of the pattern `read` takes; `from`/`to` may narrow it |

Two selectors in one call is **refused**: a call that means two edits is a call whose result cannot be checked.
`"text": ""` deletes those lines on purpose. `multi: true` makes `grep` match across lines, so a whole function
can be replaced by its opening line and its closing brace.

**`text` may be a `read` call** — `{"tool":"read","args":{…}}` — which copies that call's answer out of another
file without it passing through the conversation, so a large paste is never refused for its size.

### `archive` — one path, one flag

`{path}` packs; `{path, unpack: true}` unpacks. There is **no destination argument** — the output is a property
of the input, not something the caller invents. `seal` and `key` are the sealed-archive pair.

### `background` — any command, deferred

`{tool, args}` runs another command and writes its result to a file, answering at once with the path. A command
that changes files changes them while the turn carries on.

### `query` — read-only SQL

Only `SELECT` and `WITH` run. The answer is written to a file (`path`), and the conversation gets the columns
and a few rows. At most `SqlTool.MAX_ROWS` rows come back, and the answer says when there were more.

### `mcp` — one tool for every remote tool

`{server}` alone lists what the server offers; `{server, tool, args}` calls one. **Not one tool per remote
tool**, because the tool list is in every request's cache prefix — registering a server's own tools would
rewrite that prefix whenever a server changed, and a server going down would do it for every conversation that
mentioned it.

---

## 3. The two arguments that expand

`http`'s `request` and `mcp`'s `args` are expanded before use, for **the request's own host**:

| mention | answered from |
|---|---|
| `{{file:path}}` | a file inside the project — **expanded recursively**, because a file may name another file |
| `{{secret:name}}` | the vault, **for that host only** — and **not** expanded again |

**A file is a template; a secret is data.** A secret is never re-expanded, because running a vault value back
through the expander would let four characters inside a password be read as a placeholder.

Other rules, all measured in `Expand.kt`:

- a path resolves **inside the project and nowhere else** — canonical on both sides, so a symlink cannot spell
  its way out;
- a secret may not name the address: the host is read from the request's URL **before** anything is expanded,
  so a value cannot decide where it travels;
- the address rule runs **before** expansion, so a private address is refused before a token goes near it;
- a cycle is detected, and there is a depth cap.

`http`'s `request` also takes **`@path`**, which reads the document from the project — that is how a `.http`
file runs. The file *is* the request.

---

## 4. What the set deliberately does not have

Each of these was removed or refused, and the reason is the same shape: a second way to say one thing.

| not there | why |
|---|---|
| `create`, `edit`, `replace`, `at` | `write` is the only way anything is written; `text` is the new text in every shape |
| `tree`, `history` (as tools) | filters on `search`, which already has `scope` and `history` |
| `fetch` | `http` with a GET, plus `archive` to unpack |
| `ci`, `runs`, `artifact`, release dialog | provider logic — `http` and `git {op:"tag"}` replace them |
| `semantic` mode | needed an embedding model and an index the app no longer carries; asking for it is refused in words rather than quietly falling back to text |
| `ask`, `conclude` | the loop ended the turn on them instead of running them; if the model can see a command, it can call it |
| one tool per MCP tool | the cache prefix |
| a `release` verb | the agent writes the release HTTP request itself |

---

## 5. What is open, and what is not a defect

Stated so this document is not mistaken for a finished thing:

- **P4 (`ssh`)** is planned and **not started**, blocked on one unanswered question: where a private key's
  passphrase lives (a second secret, or refuse passphrased keys). It is in
  `PLAN-portable-2026-11-08.md` §P4 with the library measured.
- **`.http` files hold one request each.** The VS Code convention allows several separated by `###`; this does
  not, and nothing needs it yet.
- **`MediaStore`, `push`, `pull` and `http` have never run on a device or a real network.** Every test uses a
  seam that never opens a socket — which is exactly how the app shipped 79 releases without the INTERNET
  permission. The permission is now declared and a test reads the manifest for it, but the network paths
  themselves remain unproven.
