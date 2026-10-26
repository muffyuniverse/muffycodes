# Counterproposal 2: one range language, `copy` as a flag, `edit` that can say where its text came from

*Answers the four comments on [counterproposal 1](agent-tools-counterproposal.md). The net effect is
**fewer tools than either draft**: 14 today → 12.*

---

## 1. `copy` is a flag on `move` — agreed, with the one consequence to settle

You are right that they do practically the same: `Workspace.move` (`Workspace.kt:408`) and
`Workspace.copy` (`Workspace.kt:488`) are the same guard, the same destination check, and then either
`renameTo` or a write. One boolean between them.

```json
{"path":"milk.txt","into":"archive"}                  move
{"path":"milk.txt","into":"archive","copy":true}      copy — the original stays
{"path":"milk.txt","into":"archive","name":"x.txt"}   move and rename
```

**Two real differences get collapsed by the flag, and one of them must be decided, not inherited:**

| | `move` today | `copy` today |
|---|---|---|
| destination exists | **refused** | takes a free name (`milk 2.txt`) |
| source is locked | **refused** | **allowed** — a copy only reads the source |
| source is a folder into itself | refused | refused |

- **Lock behaviour is not a choice.** `copy: true` means "do not lose the original", and reading a
  locked file is legal in this app. So the flag decides the permission, and that is exactly right: the
  locked file stays untouched in both modes.
- **Collision is a choice**, and I would take the strict one for the agent: **refuse when the
  destination name is taken, in both modes.** A silent `milk 2.txt` is a surprise the model will not
  notice and cannot undo; a refusal is a sentence it can act on ("pick a name"). The person's screen
  keeps its free-name copy — `Workspace.copy` still does it; the agent's tool is the one that
  refuses.

**The name is the only thing I still dislike.** `move {"copy":true}` contradicts itself in three
words. Options: keep `move` (the flag is explicit, and "move a copy" is a real English sentence);
rename the tool to `put`/`place`/`transfer` (neutral, but a new word to learn). I lean to keeping
`move`, because renaming costs the model a word for no gain, but it is your call.

## 2. One selection language, everywhere: `from`/`to`/`grep`

**`from` and `to` are line numbers, both inclusive. `to` absent means "to the end".** This replaces
`from`+`lines` (a start and a count) wherever it appears — `read`, `history`, and the new
`edit` selection. Your instruction: *standardize that.*

```json
{"from":10,"to":49}            lines 10 through 49
{"from":10}                    line 10 to the end
{"to":20}                      line 1 through 20
{"grep":"TODO"}                only the matching lines, from all of it
{"from":10,"to":49,"grep":"TODO"}   the matching lines, inside 10–49 only
```

**Order is always the same, and it is yours:** the range is applied first, then `grep` inside it.
Either may be used alone; both may be used together; neither means everything. One order, one
meaning, in every tool.

This is a real change to `read`, not a rename: `{"from":10,"lines":40}` today means 10–49 and
becomes `{"from":10,"to":49}`. The tool description and the prompt change with it.

## 3. `edit` takes a read call as its replacement — and that absorbs paste

Your suggestion, and it is better than a `paste` tool because it puts the pipe where the model
already looks: **an argument**.

```json
edit {
  "path": "todo.txt",                       the file being changed
  "find": "…",                              what to look for — or "from"/"to" below
  "replace": {"tool":"read","args":{"path":"notes.txt","from":10,"to":20,"grep":"TODO"}}
}
```

The replacement is either text or a call that produces text. **Only `read` may appear there** — it
is the one tool whose job is to produce text, and allowing anything else turns one call into a small
program.

With the standardized range, `edit` now covers all four of the operations you asked about:

| you want | call |
|---|---|
| replace matched text | `{"find":"old","replace":"new"}` — today, unchanged |
| replace a line range | `{"from":10,"to":12,"replace":"new"}` |
| paste a selection over a selection | `{"from":10,"to":12,"replace":{read from another file}}` |
| **insert** (nothing → something) | `{"at":11,"replace":{read …}}` |
| append / prepend | `{"at":1,…}` / `{"at":<last+1>,…}` |
| overwrite the file | `{"text":"…"}` — today, unchanged |

**`at` is the one shape that is not a range**, and I want your ruling on it: an insertion point is a
position *between* lines, which a range cannot express — `from:11,to:10` would be an empty range
that happens to mean "here", and a model will get that wrong. `at` is two characters and is
unambiguous. If you would rather have no fifth argument, the alternative is appending to the end
only, and insert becomes read-splice-write again.

**A paste cannot exceed the result limit, because it is not a result.** The nested `read`'s text goes
to the file, and the `edit` result is the one short sentence it always was (`Edited todo.txt`). So
the char limit never applies to the replacement — which is the point: a 200 KB paste is exactly the
case where a tool must not complain about size.

## 4. `background` takes any command

Agreed, and it is simpler than what I proposed: no `LONG` set, no "finishes in milliseconds, call it
directly" refusal, one rule instead of two.

Refused anyway, for reasons that are about the shape of a turn rather than about speed:

- **`ask`, `conclude`, `wait`** — they end a turn and have no result to file.
- **`background` itself** — a background of a background is a way to lose a result.

**The hazard, stated plainly:** backgrounding a *writing* command means the project changes while the
turn carries on, and the next request may read a tree that is being written underneath it. That is
already true of a slow `restore`, so the app already lives with it — but the tool description must
say it, in those words, because the model is the one choosing.

One consequence: after `clone` is removed, `restore` is the only command that was ever slow, so
`background`'s remaining use is narrow. That is fine — it is one sentence of code and it is honest.

## 5. The rest, unchanged from counterproposal 1

- **A result over the char limit is an error** asking for a narrower selection. One limit, the
  setting that exists. No `MAX_MATCHES`, no `take(20)`.
- **`history`** greps the file's content at each version; a version that does not match does not
  appear. `from`/`to` select versions, then grep. Too big → the error.
- **`read` gains `revision`**, so history finds a version, read shows it, restore puts it back.
- **`clone` is removed.** Cloning a repository is the operator's, and the add-project window already
  does it.
- **`tree`** keeps `path`/`depth`/`name`/`grep`; its 200-match cap becomes the char limit.

---

## 6. Resulting tool set — 12

| tool | change |
|---|---|
| `tree` | 200-cap → the char limit |
| `read` | + `revision`; `lines` → `to`; grep applies inside the range |
| `edit` | + range selection; `replace` may be a `read` call; + `at` for insertion |
| `create` | unchanged |
| `delete` | unchanged |
| `move` | + `copy` flag — absorbs `copy` |
| ~~`clone`~~ | **removed** |
| `history` | content grep; `from`/`to`; no silent 20 |
| `restore` | unchanged |
| `background` | any command except the three that end a turn, and itself |
| `reason`, `forget` | unchanged |
| `ask`, `conclude`, `wait` | unchanged |

## 7. What still needs your word

1. **`move` with a `copy` flag — keep the name, or rename the tool to something neutral?**
2. **`at` for insertion, or no insertion shape at all?** (I recommend `at`; an empty range is a trap.)
3. **Collision on an existing destination: refuse in both modes** (my recommendation, and it differs
   from the person's screen, which keeps finding a free name)?
