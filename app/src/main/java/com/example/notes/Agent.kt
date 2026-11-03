package com.example.notes

import java.io.File

/**
 * One burst: the operator spoke, and the agent works until it ends the turn.
 *
 * **The request is built from the record, not from a list held in memory.** Everything the model is
 * sent is the record's `in` rows mapped into messages — the instructions above them — so the
 * conversation the model sees and the file a person reads are the same object. An in-memory
 * transcript beside a file is two truths, and they disagree the first time the app is closed
 * mid-turn.
 *
 * The loop is synchronous on purpose. It runs on the app's one worker, which means a burst
 * cannot overlap another burst, and a file cannot be written twice from two turns. What it
 * costs is that a long turn blocks the worker; what it buys is that the record has one order.
 */
class Agent(
    private val accountDir: File,
    private val project: String,
    private val ws: Workspace,
    private val git: ProjectGit,
    private val log: AgentLog,
    private val door: Door,
    private val model: ActiveModel,
    /**
     * How much of one tool result may enter the conversation, in characters.
     *
     * **A budget for the conversation, applied at the one boundary a result crosses** (see
     * [AgentTools.forConversation]). Over it the body is written whole to a file and the answer is
     * that path, so nothing is lost and nothing is cut. The caller has already clamped the operator's
     * number to the model's input window — a result bigger than the window cannot be sent at all.
     */
    maxResult: Int = AgentTools.MAX_RESULT,
    /**
     * The backstop from [MAX_REQUESTS], overridable so a test can reach it in three requests
     * instead of forty. The policy is the constant; this is only who is asking.
     */
    private val maxRequests: Int = MAX_REQUESTS,
    /**
     * The two context limits, as the settings and the pinned endpoint make them at this moment.
     *
     * A value rather than a settings reader, because the ceiling is the pin's and the pin does not
     * change mid-turn — and because a test that had to build a `SettingsStore` to reach a size
     * limit would be testing the store instead.
     */
    private val limits: ContextBudget.Limits = ContextBudget.Limits.of(
        triggerAsked = 0,
        hardAsked = 0,
        ceiling = ContextBudget.UNKNOWN_CEILING,
    ),
    /**
     * Why the turn should stop now, or null to go on.
     *
     * **A turn is cut short, never raced.** The app has one worker and one record, so a second
     * request cannot be sent beside the first; what can happen "immediately" is that the request in
     * flight is abandoned the moment it returns and the operator's words are the next turn
     * (v1 C8). Checked between requests and again before a reply is acted on, so a command is never
     * obeyed after the person said to stop.
     */
    private val cut: () -> String? = { null },
    /**
     * True when this poll is a retry — the session has already failed at least once.
     *
     * **A failed attempt is news once.** Eight attempts at a provider that is down are one outage,
     * and a row per attempt would put eight identical `stopped` rows into a file the next clear-up
     * then has to carry. The design's rule is the first attempt written down and one line when it
     * comes back, with the count. The runner knows the ladder's position and the turn does not, so
     * it is passed in.
     */
    private val retrying: Boolean = false,
    /**
     * Where a `background` call's work runs, and how long the foreground waits for a command.
     *
     * **The wait is the person's, not the model's.** [foregroundMs] is the operator's setting; a
     * command that outlasts it is aborted and the call's own result says so. Zero means wait for
     * ever, and it is what a test that is not about a slow command uses, so no test acquires a
     * deadline it did not ask for.
     */
    private val bg: Background = Background.none(),
    private val foregroundMs: Long = 0L,
    /**
     * How long a failing connection is retried before the session stops and says a person is
     * needed — **the operator's window**, from Settings.
     *
     * Passed in rather than read here for the same reason every other limit is: the agent is built
     * with the numbers in force when the turn began, so a setting changed mid-turn cannot make one
     * turn follow two rules.
     */
    private val reconnectMs: Long = AgentSession.DEFAULT_RECONNECT_MS,
    /**
     * The clock a foreground deadline is measured against.
     *
     * **Injected so a test can say the wait is over** without sleeping for it. The deadline itself
     * asks a clock for the time; this is which clock, and the only place the foreground's deadline
     * is made.
     */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Three symbols for the keepalive's tail — **injected so its uniqueness is testable.**
     *
     * A test that drew from the real generator and compared two draws would be asserting a
     * probability: with 238,328 combinations it would pass a quarter of a million times and fail
     * once, which is a flake dressed as a proof. A test supplies a sequence instead and asserts the
     * *rule* — every ping gets the tail it was given, and the tail reaches nothing but the wire.
     */
    private val pingMark: () -> String = { mark() },
    /** The operator's limit for a `background` command; zero is "no limit". */
    private val backgroundMs: () -> Long = { 0L },
    /**
     * How big the record may get, in bytes; **zero keeps everything, which is the default.**
     *
     * A different act from [limits]: trimming the context takes a row out of what the model is sent
     * and leaves the evidence alone, while this removes the row. The app used to do this at four
     * megabytes without being asked, so a person trimming their context could watch rows disappear
     * from a file they had understood as append-only.
     */
    /**
     * How many tokens of `session.log` to keep; zero keeps everything.
     *
     * **Tokens, not bytes.** The operator's correction: the limit and the cost should be one unit,
     * and the unit the model is billed in is the one a person is choosing about.
     */
    private val sessionTokens: Long = 0L,
    /**
     * The clear-up point in tokens, read when a tool is called rather than when the agent was built.
     *
     * **`forget` refuses under it**, and the refusal belongs with the act rather than with the screen
     * that draws the number - so the agent carries it to the tools the way it carries the operator's
     * background limit. Zero means "the app has no opinion", which refuses nothing.
     */
    private val triggerTokens: () -> Long = { 0L },
    /**
     * **What the agent is doing right now, one page at a time.**
     *
     * The operator's design: a page is a command *and its result*, so a page can be published the
     * moment the call is made and filled in the moment the result arrives - which is the difference
     * between a screen that says "working" and one that says "running `create`".
     *
     * Called twice per call: with `answer = null` when the model asks, and again with the body when
     * the tool returns. The screen keys them by id, so the second call replaces the first.
     *
     * **A callback rather than a log read**, because the rows are written either way and re-reading
     * the file mid-turn would race the writer. This is the only place that has both the call and its
     * answer in hand.
     */
    private val onPage: (Live) -> Unit = {},
    /**
     * The browser, or `Browser.NONE` when this agent may not browse.
     *
     * **Passed in rather than made here**, like the door and the clock: the engine needs a
     * `Context` and a `Looper` thread, and an `Agent` that could build one would be an `Agent` that
     * cannot be constructed in a test. The default refuses, so an agent built without a decision
     * cannot reach the web.
     */
    private val browser: com.example.notes.browser.Browser = com.example.notes.browser.Browser.NONE,
    /**
     * Where downloaded bytes come from, for `fetch`.
     *
     * **Passed in rather than made here**, like the browser: a download needs a network and a socket,
     * and an `Agent` that made its own would be one that cannot be constructed in a test. The default
     * refuses, so an agent built without a decision cannot download.
     */
    private val fetcher: Fetcher = Fetcher.NONE,
    /**
     * The MCP servers an article describes, for `mcp`.
     *
     * **Passed in rather than made here**, like the browser and the fetcher, and the default refuses — so an
     * agent built without a decision cannot call out to a server.
     */
    private val mcp: Mcp = Mcp.NONE,
    /**
     * Where an MCP article's named secret is resolved.
     *
     * **Null rather than an empty store**, because "this app has no store" and "the store does not have this
     * secret" are two different refusals and only the second one is actionable.
     */
    private val credentials: Credentials? = null,
    /**
     * What cuts suspected instructions out of text brought in from outside the app.
     *
     * **Passed in rather than made here**, like the browser and the fetcher, and the default passes everything
     * through — so an agent built without a decision does not silently cut sections of a page.
     */
    private val scanner: Scanner = Scanner.NONE,
    /** Whether the agent may reach this phone and the private network. Off unless asked. */
    private val privateAllowed: () -> Boolean = { false },
    /**
     * Whether downloading is allowed — **the tool is dropped from the list when it is not.**
     *
     * A function rather than a value, like the other limits: a person who turns downloading off while an agent
     * is idle means the next turn.
     */
    private val fetchAllowed: () -> Boolean = { true },
    /** How long a page may take, in milliseconds; zero is the browser's own bound. */
    private val browseMs: () -> Long = { 0L },
    /**
     * GitHub, and the key to reach it with, or nulls when this project is not a GitHub one.
     *
     * **A function, not a value**, for the same reason the model is: a person can sign out or change
     * the key while an agent is idle, and the key that applies is the one in force when the call
     * arrives. Null means the CI tools refuse — a local project has no runs to read.
     */
    private val github: () -> GitHub? = { null },
    private val githubToken: () -> String? = { null },
    /**
     * Where a `downloads` call puts a file, or null when the app has no way to write one.
     *
     * **Injected for the same reason the browser is**: `MediaStore` needs a `Context`, and an `Agent`
     * built in a test must not need one. Null makes the tool refuse in words rather than fail at the
     * last moment, which is the difference between a model that learns the app cannot do this and one
     * that learns nothing.
     */
    private val downloads: ((File) -> Result<Export.Done>)? = null,
    /**
     * Whether the model in force can see a picture — or `null` when nothing has said.
     *
     * **`ModelCatalogue.Entry.inputModalities` already answers this** for any model the app has
     * looked up, so the wiring passes the real answer where it has one. Null is the default because
     * an `Agent` built in a test has no catalogue and must still be able to call `look`: the check is
     * a courtesy that stops a blind model wasting a turn, never a gate on the capability itself.
     */
    private val modelSees: (() -> Boolean)? = null,
) {

    /** **The prompt for this agent**, built fresh each turn so an edit is seen next turn. */
    private fun layers(): AgentPrompt.Layers = AgentPrompt.read(accountDir, tools.projectDir)

    private fun readOpening(layers: AgentPrompt.Layers, log: AgentLog, tools: List<ToolSpec>): Opening {
        // **The line numbers are the context file's, and they cost nothing to know.**
        // `context.log` holds the `in` rows in the order they entered it, so the nth one is its nth
        // line. The index is read off the evidence, with no second file to parse and no position to
        // look up — and because both files are only ever appended to, line N stays line N until a
        // row leaves the context, which is a moment the clear-up line is being rewritten anyway.
        val costs = log.contextRows()
            .withIndex()
            .map { (index, row) -> RowCost(row.id, row.kind, row.name, row.size, index + 1) }
            .filter { it.tokens > 0 }
            .sortedByDescending { it.tokens }
            .take(BIGGEST_ROWS)
        // **Measured from the rows in hand, not from the memo.** This function has already read
        // every context row to find the biggest ones - the clear-up line names them - so the sum is
        // free here, and asking the memo would read the file a second time to avoid an addition.
        // The memo pays where a caller has no rows: `size()`, and the trim.
        return Opening(measure(layers.message, log.contextRows(), tools), costs)
    }

    /** How many files in the project are different from what was committed. */
    private fun changedFiles(): Int = try {
        git.changes(project).total
    } catch (e: Throwable) {
        -1
    }

    /**
     * What a ping tells the model to do, **so that the ping is a bounded piece of work.**
     *
     * Measured, on the pinned endpoint: the same conversation sent with no instruction and one
     * token allowed produced a hundred and thirty-six tokens of answer, because a conversation
     * with no question in it invites the model to continue it. With this instruction the same
     * request produced twenty. The instruction is the difference between "say something" and "do
     * nothing", and a heartbeat should be the second.
     *
     * **It also removes the last ambiguity a provider could reason about.** The keepalive asks the
     * model to spend nothing, and an instruction that can be read one way leaves nothing to
     * interpret. That matters more than the tokens: OpenRouter's own documentation warns that
     * `max_tokens` bounds reasoning *on most providers*, and a provider that ignores it while
     * reasoning about an unclear request is exactly the failure this sentence prevents.
     */
    private fun pingInstruction(): Msg = Msg.user(
        // **The instruction, then three random symbols.** The words are what bound the answer: the
        // same conversation with no instruction and one token allowed produced 136 tokens, because
        // a conversation with no question in it invites the model to continue. With the one-dot
        // instruction it produced 20. The tail is what stops the request ever being the one before
        // it - see [mark], and the operator's ruling at L28198: *"attach a new dot to every
        // subsequent keepalive message so that message never stays exactly the same."*
        //
        // **Why a changing tail cannot break the cache the ping exists to protect.** A provider
        // matches the longest common *prefix*, and this sits after the whole conversation - so the
        // cached part is untouched and only the last two tokens are new. It defeats an exact-match
        // cache, which is the thing that was answering instead of the upstream, and leaves a
        // prefix cache hitting.
        //
        // **Appended rather than prepended, deliberately.** A prefix cache reads forwards, so
        // anything new belongs at the end; a symbol at the front would invalidate every cached
        // token behind it and turn every ping into a full-price re-prefill.
        "Answer with a message that contains only one dot and nothing else. " + pingMark(),
    )

    private fun build(layers: AgentPrompt.Layers, opening: Opening): Request {
        val tools = AgentTools.specs(fetchAllowed())
        var rows = log.contextRows()
        val size = measure(layers.message, rows, tools)
        // **Said by the trim, not inferred from two measurements.** Rows grow between requests as
        // tool results arrive, so "the size changed" is true of every turn and would have silenced
        // the warning whenever the app's own limit happened to be configured at all.
        var trimmed = false

        // **One pass, aimed at the same target the agent is told to reach.** The trim used to drop a
        // single conversation per request whatever number it was given, so a context far over its
        // limit took one request per conversation to come down. Both hands now aim at
        // `limits.targetAt`: whichever of them acts, the context lands in the same place, and the
        // operator's two numbers describe one destination rather than two.
        //
        // **Still not a loop.** `trimContext` walks whole bursts from the newest backwards and stops
        // where the next one would not fit, so it converges in one pass by construction. A loop
        // that re-trimmed until the size fitted would empty the context against a limit below what
        // the instructions alone cost, and could never converge - an impossible limit is honoured
        // once rather than fought.
        val hard = limits.hardAt
        if (hard > 0 && size > hard) {
            // **The target is a budget for the whole request; the trim only removes rows.** The
            // instructions and the tool specs are sent whatever happens, so the rows are allowed
            // `target - fixed`. Passing the target straight through would have the app trim to the
            // target *plus* the instructions, which lands the request above the number it was told
            // to reach - and with a target below what the instructions alone cost, the difference is
            // negative and the honest answer is "keep the newest burst and nothing else", which is
            // what `keepNewest` already does with a budget it cannot meet.
            val fixed = size - rows.sumOf { it.size }
            val dropped = log.trimContext(limits.targetAt - fixed)
            if (dropped > 0) {
                trimmed = true
                Trace.note(
                    "agent-trim",
                    "$project · $dropped rows, over ${ContextBudget.short(hard)}, " +
                        "to ${ContextBudget.short(limits.targetAt)}",
                )
                // Read back from the file: what is sent has to be what was left, or the trim is a
                // note about the app's intentions rather than a change to the request.
                rows = log.contextRows()
            }
        }

        // **The same line for every request of this turn**, from the size the turn began at — and
        // none at all when the app has just trimmed: the context is no longer the one the warning
        // was about, and telling the model it is over a limit it was just brought under would be a
        // message from before the app acted.
        val trigger = limits.triggerAt
        // **The stop condition, and the only thing the target is checked against.** The app does
        // not gate a clear-up on a size — the estimate is its own, and a refusal on arithmetic is
        // a refusal for the wrong reason. What it does is *stop asking*: once an edit in this turn
        // took the context under the target, the rest of the turn is not nagged, which is what
        // keeps a model from deleting rows that matter to satisfy a sentence that is already true.
        // **The one condition, and it is the size NOW.**
        //
        // It used to read `opening.size`, measured once before the loop and never changing - so
        // within a turn the sentence kept being added after the agent had already forgotten enough,
        // stopped only by a separate `clearedUp` flag that required the app to *notice* a row leave.
        // A model that forgot in a way the row count did not show - two rows out, two rows in, from a
        // `read` still running - kept being told to clear up a context that already fitted.
        //
        // `size` is the measurement this request was built from, so asking about it and naming it are
        // the same statement. Below the trigger there is nothing to say, and the turn goes quiet
        // after the single `forget` that took it there.
        val line = if (trigger > 0 && size > trigger && !trimmed) {
            Trace.note(
                "agent-clear-up",
                "$project · ${ContextBudget.short(size)} over ${ContextBudget.short(trigger)}",
            )
            clearUpLine(size, trigger, limits.targetAt, limits.trigger.ceiling, opening.biggest)
        } else {
            null
        }

        return Request(
            model = model.model,
            provider = model.provider,
            effort = model.effort,
            system = layers.withLine(line),
            messages = messages(rows),
            tools = tools,
        )
    }

    /**
     * What the request costs, estimated.
     *
     * The three parts it is made of and nothing else: the instructions, the rows, and the tool
     * descriptions. A measurement that left out the tool specs would be a limit the request could
     * exceed without ever being over it.
     */
    /**
     * What a request of these parts would cost.
     *
     * **The rows are summed here rather than asked of the record's memo**, because this function is
     * also handed rows that are not the record's - `build` passes the list it has already filtered
     * and possibly trimmed. The memo is used where the caller genuinely has no rows in hand: see
     * [size], which is the number a person asks for.
     */
    private fun measure(system: String, rows: List<AgentLog.Row>, tools: List<ToolSpec>): Long =
        ContextBudget.tokens(system) +
            // **The number the row already carries.** It is measured once, when the row is written,
            // as the cost of what a request actually carries — the body, not the formatted line with
            // its columns. Summing a stored number also means the request is measured in one pass
            // rather than two, and that no byte array is allocated to count a row.
            rows.sumOf { it.size } +
            tools.sumOf { ContextBudget.tokens(it.description, it.parameters.toString()) }

    /**
     * What the agent is told when its context is over the trigger.
     *
     * Two halves, and both are in the design for a reason: the instruction — clear out the records
     * that do not relate to the current task — and **the numbers**, because "you are over your
     * limit" with no size teaches a model nothing about how much to remove. The words are the
     * app's, not the agent's, so the row that says it is a trace note and not a record row: the
     * model reads this, the evidence does not pretend the agent wrote it.
     */
    private fun clearUpLine(
        size: Long,
        trigger: Long,
        target: Long,
        ceiling: Long,
        biggest: List<RowCost>,
    ): String =
        buildString {
            // **Three numbers, and each says which it is.** A model told only "you are over your
            // limit" cannot judge how much to remove: it does not know how far over, and it does not
            // know whether the limit is a budget it is being asked to respect or a wall that would
            // refuse the request outright. Naming all three makes the decision arithmetic.
            //
            // "is", not "was when this turn began": the number is the measurement this very request
            // was built from, so a context that already shrank is described as it is now. The old
            // wording described the turn's opening size, which stopped being true the moment the
            // agent acted on it.
            append("Your context is about ${ContextBudget.short(size)} tokens, over the ")
            append("${ContextBudget.short(trigger)}-token point where the app asks you to clear up. ")
            append("This endpoint takes ${ContextBudget.short(ceiling)} tokens of input. ")
            // **By id, and that is the whole command.** `forget` names rows; it cannot carry text,
            // so compaction cannot change what the record says — which is what makes the context
            // auditable afterwards rather than a claim the model made about it.
            append("Clear up your context: call `forget` with the ids of the rows that do not ")
            append("relate to your current task. Keep the newest task, and forget a call together ")
            append("with its result. ")
            // **Every row, in ONE call.** A model that forgets a row per request spends a request
            // per row at exactly the moment the context is largest, which is the worst possible
            // time to pay for a round trip - and each of those requests carries the context it is
            // trying to shrink. Report: "it should list all ids and id spans in one request to
            // forget in bulk".
            append("**Name every row you want gone in that one call** - ids and spans together, ")
            append("like `forget {\"ids\":\"3-9 14 21-26\"}`. A row per call costs a whole ")
            append("request per row, and every one of them carries the context you are shrinking. ")
            // **A target, and it is a floor rather than a ceiling.** "Cut it down" is advice; "cut
            // it to at least this" is a number the next turn can measure, which is what makes the
            // request repeat until it is true. Cutting **past** the target is wanted rather than
            // tolerated: rows that do not serve the current task have no place in the context at
            // any size, and a model told to stop exactly at a number keeps the near-irrelevant
            // ones that come before it.
            // **The target, stated once, as a floor.** "Cut it down" is advice; "cut it to at least
            // this" is a number the next request can measure, which is what makes the ask stop being
            // repeated the moment it is true. Cutting **past** the target is wanted rather than
            // tolerated: rows that do not serve the current task have no place in the context at any
            // size, and a model told to stop exactly at a number keeps the near-irrelevant ones that
            // come just before it.
            //
            // **And the stopping rule is stated with it**, because the agent has to know the ask is
            // not a per-request nag: one `forget` that takes the context under this point is the
            // whole job, and the app goes quiet - one call, one measurement, no second cache miss.
            // **The target, which is now its own number rather than the trigger.** The ask fires at
            // the trigger and aims lower, so one clear-up buys headroom: a target equal to the
            // trigger would land the context exactly on the point that asked for it, and the next
            // row to arrive would cross it again. The operator's correction: "both forget and trim
            // compactions should have same target but different triggers".
            // **No target when there is none to derive.** A trigger of one token has no positive
            // integer below it, so `targetAt` is zero - and "get it to off tokens" would be a
            // sentence about nothing. The ask is then simply "cut what does not serve the task",
            // which is the honest instruction when the app cannot name a size.
            // **The two branches do not repeat each other.** The first names the size to reach and
            // then says how far past it to go; the second has no size to name, so "cut what does not
            // serve the task" *is* its instruction and the "further" clause would be the same
            // sentence twice.
            if (target > 0L) {
                append("Get it to ${ContextBudget.short(target)} tokens or less in this one ")
                append("`forget` - then the app stops asking. Cut further if rows do not serve the ")
                append("current task; anything unrelated to what you are doing now can go.")
            } else {
                append("Cut what does not serve the current task in this one `forget` - then the ")
                append("app stops asking. Anything unrelated to what you are doing now can go.")
            }
            if (biggest.isNotEmpty()) {
                // **The index, because the file cannot be read in one go.** A `read` of a large
                // context stops at the tool's own limit, so a model deciding what to remove either
                // pages through it — a request per 20k characters — or guesses. Naming the biggest
                // rows costs a line and saves the paging: one `read` of one line is one request for
                // the exact row that matters most.
                //
                // **The lines are `context.log`'s, and they are stable because it is appended to.**
                // A newest-first record would make every one of them a position that moves under
                // the model; an appended file keeps line N as line N.
                //
                // And it names the call the model has to make, exactly: **`from <line> to <line>`,
                // not `lines 1`** — `lines` is a key `read` refuses, so the old sentence was an
                // instruction that came back as a refusal at the moment the context had least room
                // to spare. `kind` is left out on purpose: `context.log` does not carry it.
                append("\nThe rows taking the most room — `read agent/context.log from <line> to ")
                append("<line>` shows one, as `id size name body`: ")
                append(biggest.joinToString("; ") {
                    "line ${it.line}: #${it.id} ${it.name}, about " +
                        "${ContextBudget.short(it.tokens)} tokens"
                })
                append(". Forget the ones that do not serve the task, by id.")
            }
        }

    /**
     * The request as the files stand right now.
     *
     * The same composition [loop] makes, spelled once, for anything that asks what the agent
     * would be sent — a check on a phone, and the tests that prove nothing is remembered.
     */
    /**
     * What the request would cost right now, estimated — the number the limits are compared with.
     *
     * Public because it is a question a person can have ("how full is it?") and because a test that
     * had to re-derive it would be measuring its own arithmetic rather than the app's.
     */
    fun size(): Long =
        // **The rows come from the record's memo**, because this is the question asked on a timer -
        // "how full is it?" - and re-reading and re-summing every context row to answer it is what
        // the memo exists to avoid. The instructions and the tool specs are counted as usual: they
        // change rarely and are cheap, and a second memo for them would be a second thing to
        // invalidate.
        ContextBudget.tokens(layers().message) +
            log.contextTokens() +
            AgentTools.specs(fetchAllowed()).sumOf {
                ContextBudget.tokens(it.description, it.parameters.toString())
            }

    fun request(): Request {
        val layers = layers()
        return build(layers, readOpening(layers, log, AgentTools.specs(fetchAllowed())))
    }

    /**
     * The record, as the model reads it.
     *
     * Three decisions:
     * - **Everything between two tool results belongs to one assistant turn.** The prose, the
     *   reasoning and the calls were one reply on the wire, so they are put back as one.
     * - **A `GOT` answers the oldest call still waiting**, matched by the id the call's own row
     *   gave it. That is what makes a rebuilt conversation legal even after a seal.
     * - **The reasoning rides with the turn it came from**, in its own field. It is not prose the
     *   model wrote — the provider wants it in `reasoning_content` — so it is accumulated beside
     *   [text] and never appended to it.
     */
    fun messages(rows: List<AgentLog.Row>): List<Msg> {
        val out = mutableListOf<Msg>()
        val text = StringBuilder()
        val thought = StringBuilder()
        val calls = mutableListOf<Call>()
        /**
         * Which reply the reasoning being accumulated belongs to.
         *
         * A reasoning row names the `agent` note it was written with, and only that note's turn may
         * take it. Matching on "the newest assistant turn" would attach a thought to the wrong reply
         * the first time two replies were adjacent in the record.
         */
        var replyId = 0
        /**
         * The call ids still waiting for a result.
         *
         * A **set**, not a queue: the result names the call it answers, so the order results arrive
         * in stops being load-bearing. What remains is the one thing that is: a result may only be
         * sent if its call is still unanswered, which is what keeps the array legal.
         */
        val awaiting = mutableSetOf<String>()

        fun flush() {
            if (text.isEmpty() && calls.isEmpty()) return
            out.add(Msg.assistant(text.toString().trim(), calls.toList(), thought.toString().trim()))
            thought.clear()
            replyId = 0
            awaiting.clear()
            awaiting.addAll(calls.map { it.id })
            text.clear()
            calls.clear()
        }

        for (row in rows) {
            if (row.broken) continue
            when (row.kind) {
                AgentLog.TOLD -> {
                    flush()
                    out.add(Msg.user(row.body))
                }

                // **A speaking call is not part of the conversation.** `ask` and `conclude`
                // are never run, so they never get a result — and a request whose assistant
                // message calls a tool with no answer is refused by every endpoint. Their
                // words are the ASK/CONCLUDE row, which is added below as the agent's own
                // message; that is the seam an answer needs.
                AgentLog.CALL -> calls.add(Call(callId(row.id), row.name, row.body))
                AgentLog.GOT -> {
                    flush()
                    // **The result says which call it answers.** It used to be inferred: a queue of
                    // unanswered calls, and the next result took the oldest. That is right only
                    // while the writer interleaves calls and results — it does, but nothing said it
                    // had to, and a reply may carry several calls.
                    //
                    // A result that names no call, or names one this conversation no longer has, is
                    // **not sent**: every request needs each tool message to answer a call the model
                    // made, and an orphan result is a message an endpoint refuses. The clear-up
                    // rules cannot produce one, but a reset can — the evidence keeps a call the
                    // context no longer has, and the app's own seal then answers it in the evidence
                    // alone.
                    val call = callId(row.answers)
                    if (row.answers <= 0 || !awaiting.remove(call)) continue
                    out.add(Msg.tool(call, row.body))
                }

                // **`woke` is a message of its own, and the only note that is.** A wake has to say
                // why it happened; `agent`/`reason` accumulate into the assistant's own paragraph,
                // which would make the app's sentence look like something the model wrote. It is a
                // user message because that is the only role every endpoint lets a conversation
                // carry mid-array — and it is at the end of the array, where changing it costs one
                // message rather than the whole prompt.
                AgentLog.NOTE -> when (row.name) {
                    // **The reasoning, attached to the turn it belongs to.** It is `app` in the
                    // context file, so it is never in `rows` here as prose; this branch exists for
                    // the replay, and it reads the id out of the detail written by
                    // `AgentLog.thinking`. Attached to the paragraph being accumulated for that
                    // reply rather than flushed as a message of its own: the provider wants it
                    // inside the assistant turn, not beside it.
                    "thinking" -> {
                        val forReply = row.body.substringAfter("reply=", "").substringBefore(' ').toIntOrNull()
                        if (forReply != null && forReply == replyId) {
                            thought.append(row.body.substringAfter(' ', ""))
                        }
                    }
                    "woke" -> {
                        flush()
                        out.add(Msg.user(row.body))
                    }
                    // **A picture the agent asked to see, arriving on the turn it is asked to act on.**
                    // `look` cannot answer with an image — a `role: "tool"` message is text-only — so
                    // it writes this note and the note becomes the message that carries the picture.
                    //
                    // **A row rather than memory, and that is what makes it correct on every rebuild.**
                    // This function builds the whole array from the record each time; a picture held in
                    // a field would be attached once and lost. As a row it is part of the conversation,
                    // it counts against the budget, and `forget` can drop it like anything else.
                    //
                    // A `user` role because that is the only one a conversation may carry mid-array,
                    // and it is honest: the picture is context being handed over, not an answer.
                    "see" -> {
                        flush()
                        // **Each line is `path<TAB>url`**, so the message can name what it carries.
                        // A conversation with three of these otherwise has three identical sentences
                        // and no way to tell which picture is which file.
                        val attached = row.body.split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
                            val tab = line.indexOf('\t')
                            if (tab <= 0) null
                            else line.substring(0, tab) to line.substring(tab + 1)
                        }
                        if (attached.isNotEmpty()) {
                            out.add(
                                Msg.user(
                                    "the picture" + (if (attached.size > 1) "s" else "") +
                                        " you asked to see: " +
                                        attached.joinToString(", ") { it.first },
                                    pictures = attached.map { it.second },
                                ),
                            )
                        }
                    }
                    "agent", "reason" -> {
                        text.append(row.body).append("\n")
                        // The `agent` note is the reply's own row, so its id is what a reasoning row
                        // names. `reason` is the same paragraph continued and must not take the id
                        // back: a thought written after it still belongs to the same reply.
                        if (row.name == "agent") replyId = row.id
                    }
                    else -> Unit
                }
            }
        }
        flush()
        return out
    }

    /** The wire id of a call, derived from the row that recorded it — stable across rebuilds. */
    fun callId(rowId: Int): String = "call_$rowId"
}
