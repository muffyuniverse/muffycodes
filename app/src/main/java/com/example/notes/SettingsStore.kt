package com.example.notes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The settings that are not about an account: the appearance, and how much
 * change history is kept.
 *
 * **One length, not two.** It used to be two — the account's own history and
 * each project's — and the difference was invisible in use: somebody setting a
 * limit is answering "how far back can I go?", which is one question. The number
 * now applies to this phone and to each project individually, which is what it
 * always meant.
 *
 * Zero means "keep everything", which is the default.
 *
 * A length is applied **on the next change**: nothing is pruned when the number
 * is typed, so shortening the history of a large tree is a decision whose cost
 * arrives with the next save rather than in the middle of a setting.
 */
class SettingsStore(context: Context, name: String = "settings") {

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    var theme: AppTheme by mutableStateOf(
        AppTheme.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) }
            ?: AppTheme.CARAMEL
    )
        private set

    var useDark: Boolean by mutableStateOf(prefs.getBoolean(KEY_DARK, false))
        private set

    /**
     * Whether the README has been opened by itself, once, on the app's first screen.
     *
     * **Once for the life of the install**, not once per launch: the report was *"on first open move
     * operator to readme.md file from system project in view mode"*, and a screen that reopened it
     * every time would be a screen that will not stay where a person puts it.
     *
     * **Remembered here rather than inferred from "is there anything in the workspace"**, because a
     * person who deletes everything and starts again should not be shown the introduction a second
     * time - they have read it.
     */
    var readmeShown: Boolean by mutableStateOf(prefs.getBoolean(KEY_README_SHOWN, false))
        private set

    /** Records that the introduction has been shown, so it is not shown again. */
    fun markReadmeShown() {
        prefs.edit().putBoolean(KEY_README_SHOWN, true).apply()
        readmeShown = true
    }

    /**
     * How many changes — local commits — are kept on this phone. 0 keeps all.
     *
     * Applies to the phone's own change history and to each project's individual
     * limit alike.
     */
    var historyLength: Int by mutableStateOf(prefs.getInt(KEY_HISTORY, legacyLength()))
        private set

    /**
     * How many days of request log are kept — **the operator's number, and off by default.**
     *
     * The log is written on every response, including the keepalives sent every four minutes while
     * the agent waits, so it grows whether or not anybody is using the app: roughly a megabyte a
     * month of an idle account, and a great deal more of a busy one. Empty keeps all of it, which is
     * the right default for a log whose whole purpose is being able to ask "what did that cost" a
     * year later.
     *
     * **Retention is a number of days rather than a size in bytes**, because a person thinks in
     * "keep a month" and not in megabytes, and because the file's own timestamps make a day an
     * exact boundary while a byte count is not.
     */
    var requestDays: Int by mutableStateOf(prefs.getInt(KEY_REQUEST_DAYS, 0))
        private set

    /** Keeps this many days of request log, clamped. Zero or less keeps everything. */
    fun keepRequests(days: Int) {
        val clean = if (days < 0) 0 else days
        Trace.tap("request log limit", if (clean == 0) "all" else clean.toString())
        Trace.said(
            if (clean == 0) {
                "every response is kept"
            } else {
                "responses older than $clean days are dropped as new ones are written"
            },
        )
        requestDays = clean
        prefs.edit().putInt(KEY_REQUEST_DAYS, clean).apply()
    }

    /**
     * Whether the trace is copied into Downloads while the app is open.
     *
     * **A development aid, and only a development aid.** A release build answers no
     * whatever is stored, because a release build has no business writing a running log
     * of a person's tapping into a folder other apps can read. In a debug build it
     * starts on: a session spent describing bugs by hand produces fewer of them per
     * hour than one where the app describes itself.
     */
    var liveTrace: Boolean by mutableStateOf(
        BuildConfig.DEBUG && prefs.getBoolean(KEY_LIVE, true),
    )
        private set

    /** True only where it can work at all. */
    val liveTraceAvailable: Boolean get() = BuildConfig.DEBUG

    fun keepLiveTrace(on: Boolean) {
        if (!liveTraceAvailable) return
        liveTrace = on
        prefs.edit().putBoolean(KEY_LIVE, on).apply()
    }

    fun selectTheme(theme: AppTheme) {
        // **A setting is a tap too.** These four went through this store, not the view
        // model, so they were the one family of presses that left no trace at all — and
        // "the theme switch did nothing" was unanswerable from a log that showed only
        // that the Settings screen was open.
        Trace.tap("theme", theme.name)
        this.theme = theme
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        // **Answered here, because nothing else will.** The theme is applied by this store
        // and never passes through the view model, so the screen never redraws as a
        // consequence of the press and no state line could answer it. It sat unanswered
        // until the next unrelated screen change claimed it — a cancel or a back press
        // spending a tap it had nothing to do with.
        Trace.said("theme is now ${theme.name.lowercase()}")
    }

    fun selectDarkVariant(useDark: Boolean) {
        Trace.tap(if (useDark) "dark" else "light")
        this.useDark = useDark
        prefs.edit().putBoolean(KEY_DARK, useDark).apply()
        Trace.said(if (useDark) "dark colours" else "light colours")
    }

    /** Sets the change history length; a negative number means no limit. */
    fun keepHistory(value: Int) {
        val clean = value.coerceAtLeast(0)
        // Named with the number, because "0" and "" mean different things and the
        // difference is exactly what a person reports as "it keeps everything now".
        Trace.tap("history limit", if (clean == 0) "all" else clean.toString())
        historyLength = clean
        prefs.edit().putInt(KEY_HISTORY, clean).apply()
        Trace.said(if (clean == 0) "every version is kept" else "the last $clean versions are kept")
    }

    /**
     * How often a session that needs a person reminds them: minutes, default one hour.
     *
     * **A reminder, not a poll.** The poll is the agent working; this is the app asking somebody
     * to look at it, and it stops the moment the thing is dealt with. Zero means "say it once and
     * never again", which is a choice a person is allowed to make.
     */
    var remindMinutes: Int by mutableStateOf(
        prefs.getInt(KEY_REMIND, DEFAULT_REMIND_MINUTES)
    )
        private set

    /** Sets the reminder interval, clamped to minutes a person would recognise. */
    fun keepRemind(minutes: Int) {
        val clean = minutes.coerceIn(0, MAX_REMIND_MINUTES)
        Trace.tap("remind me", if (clean == 0) "once" else "$clean min")
        remindMinutes = clean
        prefs.edit().putInt(KEY_REMIND, clean).apply()
        Trace.said(if (clean == 0) "no reminders" else "a reminder every $clean minutes")
    }

    /**
     * When the agent is told to clear up, in tokens — and when the app trims it, also in tokens.
     *
     * Zero means two different things, deliberately: a trigger of zero is "a quarter of what this
     * pin allows", because an agent with no warning point is one the app surprises; a hard limit
     * of zero is "never", because an app that silences a conversation on its own is worse than one
     * that asks the agent to shorten it.
     */
    var triggerTokens: Int by mutableStateOf(prefs.getInt(KEY_TRIGGER, 0))
        private set

    var hardTokens: Int by mutableStateOf(prefs.getInt(KEY_HARD, 0))
        private set

    /** Sets the clear-up trigger. Zero leaves it to the pin. */
    fun keepTrigger(tokens: Int) {
        val clean = tokens.coerceIn(0, MAX_TOKENS)
        Trace.tap("clear up", if (clean == 0) "when the pin is full" else "${ContextBudget.short(clean.toLong())}")
        triggerTokens = clean
        prefs.edit().putInt(KEY_TRIGGER, clean).apply()
        Trace.said(if (clean == 0) "the trigger follows the pin" else "clear up at ${ContextBudget.short(clean.toLong())}")
    }

    /** Sets the hard limit. Zero is "never trim", which is the default. */
    fun keepHard(tokens: Int) {
        val clean = tokens.coerceIn(0, MAX_TOKENS)
        Trace.tap("trim context", if (clean == 0) "never" else "${ContextBudget.short(clean.toLong())}")
        hardTokens = clean
        prefs.edit().putInt(KEY_HARD, clean).apply()
        Trace.said(if (clean == 0) "the app never trims the context" else "the app trims at ${ContextBudget.short(clean.toLong())}")
    }

    /**
     * Whether an agent waiting on the person has its conversation kept warm — **the operator's
     * switch.**
     *
     * On, the app re-sends the identical conversation every [pollSeconds] with a one-token answer
     * nobody reads: the provider's cache stays warm, and the sentence the person finally types costs
     * a cache read instead of a fresh prompt. Nothing is written to the conversation and the agent
     * does not act, so this is a question about money and battery, not about behaviour.
     *
     * **On by default, because it was already happening.** An agent that called `wait` was polled
     * every four minutes by default; the switch replaces that poll with something cheaper rather
     * than adding a new expense. Off, a session waits in silence and the next turn pays full price
     * for its prompt.
     */
    var keepAlive: Boolean by mutableStateOf(prefs.getBoolean(KEY_KEEPALIVE, true))
        private set

    /** Turns the keepalive on or off. */
    fun keepWarm(on: Boolean) {
        Trace.tap("keepalive", if (on) "on" else "off")
        keepAlive = on
        prefs.edit().putBoolean(KEY_KEEPALIVE, on).apply()
        Trace.said(if (on) "the conversation is kept warm while it waits" else "nothing is sent while it waits")
    }

    /**
     * How often the conversation is kept warm, in seconds — **the operator's number.**
     *
     * It is a decision about this person's phone: every ping is a request that is paid for and a wake
     * that costs battery, and a shorter interval keeps the cache reliably warm on both counts. It is
     * not the agent's to choose, because an agent has no way of knowing what a request costs the
     * person paying for it.
     *
     * Zero means the design's own four minutes. The ceiling is 250 seconds, below the five-minute
     * cache almost every provider keeps: a ping that arrives after the cache expired is a cold prompt
     * paid for nothing.
     */
    var pollSeconds: Int by mutableStateOf(prefs.getInt(KEY_POLL, 0))
        private set

    /** Sets the keepalive interval, clamped. */
    fun keepPoll(seconds: Int) {
        val clean = if (seconds <= 0) {
            0
        } else {
            seconds.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS)
        }
        Trace.tap("keepalive interval", if (clean == 0) "the app's own" else "$clean s")
        pollSeconds = clean
        prefs.edit().putInt(KEY_POLL, clean).apply()
        Trace.said(if (clean == 0) "warmed at the app's own four minutes" else "warmed every $clean s")
    }

    /**
     * How long keeping warm may go on, in minutes — **the operator's ceiling on the cost.**
     *
     * A ping reads the whole prompt at cache price, and a cold start writes it once: keeping warm
     * pays for itself up to about twelve pings, which is fifty minutes at the default four, and past
     * that it is spending money to protect a cache it would have been cheaper to lose. Sixty minutes
     * is that break-even with a little room.
     *
     * Zero means no ceiling: it stays warm until the person speaks, however long that takes.
     */
    var warmMinutes: Int by mutableStateOf(prefs.getInt(KEY_WARM_MINUTES, DEFAULT_WARM_MINUTES))
        private set

    /** Sets the ceiling on keeping warm, in minutes. Zero is "until the person speaks". */
    fun keepWarmFor(minutes: Int) {
        val clean = minutes.coerceIn(0, MAX_WARM_MINUTES)
        Trace.tap("keepalive cap", if (clean == 0) "until you speak" else "$clean min")
        warmMinutes = clean
        prefs.edit().putInt(KEY_WARM_MINUTES, clean).apply()
        Trace.said(if (clean == 0) "kept warm until you speak" else "kept warm for at most $clean min")
    }

    /** [warmMinutes] in milliseconds; zero is no ceiling. */
    val warmMs: Long get() = warmMinutes * 60_000L

    /** The interval in milliseconds, with the design's own when the person set none. */
    val pollMs: Long
        get() = if (pollSeconds <= 0) {
            AgentSession.POLL_MS
        } else {
            (pollSeconds * 1000L).coerceAtMost(AgentSession.MAX_IDLE_MS)
        }

    /**
     * How long a **command whose result goes into the conversation** may run, in seconds.
     *
     * **Two waits, because the two results land in two places.** A foreground result is a row the
     * agent reads next turn, so a command that never returns holds the whole conversation still; a
     * result that goes to a file holds nothing, so it can be given more room. The clock starts when
     * the command is handed to the tool: past it the command is stopped, the result says so, and the
     * turn carries on — nothing is handed over and nothing is asked again.
     */
    var foregroundSeconds: Int by mutableStateOf(prefs.getInt(KEY_FOREGROUND, DEFAULT_FOREGROUND_SECONDS))
        private set

    /** Sets the foreground command's limit, clamped. */
    fun keepForeground(seconds: Int) {
        val clean = seconds.coerceIn(0, MAX_COMMAND_SECONDS)
        Trace.tap("foreground timeout", if (clean == 0) "no limit" else "$clean s")
        foregroundSeconds = clean
        prefs.edit().putInt(KEY_FOREGROUND, clean).apply()
        Trace.said(if (clean == 0) "a command may take as long as it takes" else "a command may take $clean s")
    }

    /** The foreground limit in milliseconds; zero is "no limit". */
    val foregroundMs: Long get() = foregroundSeconds * 1000L

    /**
     * How long a **`background` command** may run before it is stopped, in seconds.
     *
     * Its result is a file rather than a row, so nothing is waiting on it: the number can be larger,
     * and the default is larger for that reason. Zero still means no limit.
     */
    var backgroundSeconds: Int by mutableStateOf(prefs.getInt(KEY_BACKGROUND, DEFAULT_BACKGROUND_SECONDS))
        private set

    /** Sets the background command's limit, clamped. */
    fun keepBackground(seconds: Int) {
        val clean = seconds.coerceIn(0, MAX_COMMAND_SECONDS)
        Trace.tap("background timeout", if (clean == 0) "no limit" else "$clean s")
        backgroundSeconds = clean
        prefs.edit().putInt(KEY_BACKGROUND, clean).apply()
        Trace.said(if (clean == 0) "a background command may take as long as it takes" else "a background command may take $clean s")
    }

    /** The background limit in milliseconds; zero is "no limit". */
    val backgroundMs: Long get() = backgroundSeconds * 1000L

    /**
     * **How long a failing connection is retried before somebody is told, in seconds.**
     *
     * The two situations this app can be in are the operator's own words for them: *something went
     * wrong* — a network error, a provider refusing, a balance that ran out — and *idle*, waiting
     * for an answer. The first is retried on the ladder at one, two and four seconds; this is how
     * long that goes on before the session stops and says a person is needed.
     *
     * **Ten minutes by default.** Long enough for a tunnel, a lift, a network change and a provider
     * having a bad afternoon; short enough that a key which will never work is not retried all night
     * on somebody's battery.
     *
     * **A duration rather than a count of tries.** The old shape was eight attempts, and the window
     * it covered was whatever the backoff schedule happened to add up to — so changing the schedule
     * silently changed how long the app tried. A duration says what it means.
     */
    var reconnectSeconds: Int by mutableStateOf(
        prefs.getInt(KEY_RECONNECT, DEFAULT_RECONNECT_SECONDS)
    )
        private set

    /** Sets how long the ladder tries, clamped to a window a person would recognise. */
    fun keepReconnect(seconds: Int) {
        val clean = seconds.coerceIn(MIN_RECONNECT_SECONDS, MAX_RECONNECT_SECONDS)
        Trace.tap("reconnect window", "$clean s")
        reconnectSeconds = clean
        prefs.edit().putInt(KEY_RECONNECT, clean).apply()
        Trace.said("the app retries a failing connection for $clean seconds")
    }

    /** The reconnect window in milliseconds, which is what the ladder is given. */
    val reconnectMs: Long get() = reconnectSeconds * 1000L

    /**
     * How long the app waits for the model's answer, **in seconds**.
     *
     * **This is what turns "the provider is not answering" into a blip.** The door's read timeout
     * ends the attempt, the ladder retries it at one, two, four seconds, and the session reconnects
     * instead of hanging. Too short retries a long turn for ever; too long holds the session's one
     * worker for half an hour on a provider that is simply down.
     *
     * **Seconds, like every other limit on this screen.** It was minutes, and it sat eight lines from
     * two limits in seconds with nothing saying the scales differed — so "4" and "180" read as the
     * same kind of number. Reported as: "how wait for the model is different from stop command
     * after?". One unit is part of that answer.
     *
     * **Two numbers now, because streaming made the silence two different things.** A streamed
     * answer says so as it arrives, so "nothing has come back at all" and "it went quiet halfway
     * through" stopped being the same wait: the first means nothing was produced and the attempt is
     * free to repeat, the second means an answer was being written and the connection died. They
     * want different limits and they deserve different sentences.
     *
     * See [ackSeconds] and [gapSeconds]; this one is kept for the ping, which is not streamed and
     * still has exactly one wait.
     */
    var timeoutSeconds: Int by mutableStateOf(
        prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_SECONDS)
    )
        private set

    /** Sets the answer timeout, clamped to a wait a person would recognise. */
    fun keepTimeout(seconds: Int) {
        val clean = seconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        Trace.tap("answer timeout", "$clean s")
        timeoutSeconds = clean
        prefs.edit().putInt(KEY_TIMEOUT, clean).apply()
        Trace.said("the app waits $clean seconds for an answer")
    }

    /**
     * **How long before the first thing arrives** counts as "the provider never took it".
     *
     * Deliberately generous, and it must be. An upstream that is queueing holds the connection open
     * and sends nothing at all, and OpenRouter buffers the first delta — so a tight number here
     * would kill working requests at the busiest moment. The cost of waiting too long is a slow
     * failure; the cost of not waiting long enough is refusing an answer that was about to begin.
     *
     * Nothing has been generated when this expires, so the retry is free — which is why this clock
     * can be long without being expensive.
     */
    var ackSeconds: Int by mutableStateOf(
        prefs.getInt(KEY_ACK, DEFAULT_ACK_SECONDS)
    )
        private set

    /** Sets the ack wait, clamped. */
    fun keepAck(seconds: Int) {
        val clean = seconds.coerceIn(MIN_ACK_SECONDS, MAX_ACK_SECONDS)
        Trace.tap("ack timeout", "$clean s")
        ackSeconds = clean
        prefs.edit().putInt(KEY_ACK, clean).apply()
        Trace.said("the app waits $clean seconds for an answer to begin")
    }

    /** The ack wait in milliseconds. */
    val ackMs: Int get() = ackSeconds * 1000

    /**
     * **How long a gap between chunks is allowed** before the answer is treated as cut off.
     *
     * The other half of the split, and the reason a long answer is possible at all: a model thinking
     * hard about its next sentence goes quiet, and one number covering the whole exchange would have
     * killed exactly the answers worth waiting for. Only the *gap* is bounded here, so nineteen
     * minutes of steady work trips nothing while a connection that died mid-sentence is caught.
     */
    var gapSeconds: Int by mutableStateOf(
        prefs.getInt(KEY_GAP, DEFAULT_GAP_SECONDS)
    )
        private set

    /** Sets the gap allowed between chunks, clamped. */
    fun keepGap(seconds: Int) {
        val clean = seconds.coerceIn(MIN_GAP_SECONDS, MAX_GAP_SECONDS)
        Trace.tap("idle timeout", "$clean s")
        gapSeconds = clean
        prefs.edit().putInt(KEY_GAP, clean).apply()
        Trace.said("the app waits $clean seconds between pieces of an answer")
    }

    /** The gap in milliseconds. */
    val gapMs: Int get() = gapSeconds * 1000

    /**
     * The most one tool result may carry, in characters.
     *
     * **A conversation budget, not a cap on work.** The brief asked for this as a field: what enters
     * the context is the operator's to decide, and a model that cannot read a large file in one go
     * should be told to work in ranges rather than be handed a fragment. A result over it is not cut
     * short — it is written whole to a file and the answer is the path.
     *
     * It is also clamped by the model's own input window ([ContextBudget.resultLimit]), because a
     * result bigger than the window cannot be sent at all.
     *
     * **In tokens**, the same unit as every limit beside it. It was characters, which made the row a
     * person types and the wall it is clamped by two different units — 20,000 characters had to be
     * converted in the head to compare with a 128k window. A number from before this change is
     * converted once, so nobody's setting changes meaning underneath them.
     */
    var maxResult: Int by mutableStateOf(
        prefs.getInt(KEY_MAX_RESULT_TOKENS, -1).takeIf { it > 0 } ?: migratedResult(),
    )
        private set

    /** Sets the longest result, in tokens. Never zero: a tool that answers with nothing is a hang. */
    fun keepMaxResult(tokens: Int) {
        val clean = tokens.coerceIn(MIN_MAX_RESULT, MAX_MAX_RESULT)
        Trace.tap("result length", "$clean")
        maxResult = clean
        prefs.edit().putInt(KEY_MAX_RESULT_TOKENS, clean).apply()
        Trace.said("one result may carry about $clean tokens")
    }

    /**
     * The result limit a build before tokens stored, as tokens.
     *
     * The old key held **characters**, so reading it as tokens would multiply every allowance by
     * 3.5 in one upgrade — a person's 20,000 became a 70,000-character result entering the
     * conversation. Dividing by the estimate's own ratio keeps the number they chose.
     */
    private fun migratedResult(): Int {
        val old = prefs.getInt(KEY_MAX_RESULT, 0)
        if (old <= 0) return DEFAULT_MAX_RESULT
        return (old / ContextBudget.BYTES_PER_TOKEN).toInt().coerceIn(MIN_MAX_RESULT, MAX_MAX_RESULT)
    }

    /**
     * How big the session log may get, in megabytes — **and zero is "keep everything".**
     *
     * The record is the evidence: it is what the app can show about what the agent did, and it is
     * the only copy of the conversation that outlives the process. Context trimming is a different
     * act — it takes a row out of what the model is *sent* — and neither implies the other, which is
     * why they are two rows and not one. The app used to delete the oldest turns at four megabytes
     * without asking; a person who never set a number had no way to know, so the default is now
     * "keep it".
     */
    /**
     * **How tall one bubble may be, in lines.**
     *
     * Lines and not a fraction of the screen, because that is the unit a person reads in: "eight
     * lines" is a statement about the text, and "40% of the window" is a statement about the phone
     * they happen to be holding. A bubble over the cap is cut at it, and its last line says the
     * message continues — so the cap hides nothing, it just asks for one tap before showing it.
     *
     * Zero means no cap, which is the honest default for a screen that already scrolls.
     */
    var bubbleLines: Int by mutableStateOf(prefs.getInt(KEY_BUBBLE_LINES, DEFAULT_BUBBLE_LINES))
        private set

    /** Sets the bubble height cap, clamped. */
    fun keepBubbleLines(lines: Int) {
        val clean = lines.coerceIn(0, MAX_BUBBLE_LINES)
        Trace.tap("bubble height", if (clean == 0) "no cap" else "$clean lines")
        bubbleLines = clean
        prefs.edit().putInt(KEY_BUBBLE_LINES, clean).apply()
        Trace.said(if (clean == 0) "a message is as tall as it is" else "a message shows $clean lines")
    }

    var sessionMb: Int by mutableStateOf(prefs.getInt(KEY_SESSION_MB, DEFAULT_SESSION_MB))
        private set

    /** Sets the session log's ceiling in megabytes; zero keeps everything. */
    fun keepSession(megabytes: Int) {
        val clean = megabytes.coerceIn(0, MAX_SESSION_MB)
        Trace.tap("session log", if (clean == 0) "kept whole" else "$clean MB")
        sessionMb = clean
        prefs.edit().putInt(KEY_SESSION_MB, clean).apply()
        Trace.said(if (clean == 0) "the record is kept whole" else "the record is kept under $clean MB")
    }

    /** The session log's ceiling in bytes; zero is "keep everything". */
    val sessionBytes: Long get() = ContextBudget.sessionBytes(sessionMb)

    /**
     * The two lengths older builds stored separately, read once for the migration.
     *
     * The larger of the two is kept: a person who had set either history longer
     * than the other meant to be able to go back that far, and reading the smaller
     * would silently delete versions they had asked to keep.
     */
    private fun legacyLength(): Int =
        maxOf(prefs.getInt(KEY_APP_HISTORY, 0), prefs.getInt(KEY_PROJECT_HISTORY, 0))

    /**
     * Reads a length typed into a field. A blank field, or anything that is not
     * a number, means "keep everything".
     */
    companion object {
        const val KEY_THEME = "theme"
        const val KEY_DARK = "use_dark"
        const val KEY_LIVE = "live_trace"
        const val KEY_HISTORY = "history_length"

        /** Whether the system project's README has been shown by itself. */
        const val KEY_README_SHOWN = "readme_shown"

        /** Days of request log kept. 0 (the default) keeps every response ever written. */
        const val KEY_REQUEST_DAYS = "request_days"

        /** The reminder interval, in minutes. */
        const val KEY_REMIND = "agent_remind_minutes"

    /** The two context limits, in tokens, as the operator typed them. */
    const val KEY_TRIGGER = "agent_trigger_tokens"
    const val KEY_HARD = "agent_hard_tokens"

    /** How long one answer may take, in minutes, and how much one result may carry. */
    /**
     * **The key is renamed with the unit, and that is the whole migration.**
     *
     * The value used to be minutes under `agent_timeout_minutes`. Reading that number as seconds
     * would turn a stored 4 into a four-second wait - a phone that retried a long turn for ever,
     * which is the failure the setting exists to prevent. A new key means the old number is never
     * read as the new unit: this build finds no value under its own key and writes the default.
     *
     * That is not compatibility machinery. Nothing here reads the old key, nothing migrates, and
     * no code path knows a previous release existed. It is one key that was renamed because the
     * thing it stores changed meaning.
     */
    const val KEY_TIMEOUT = "agent_timeout_seconds"

    /**
     * The two clocks streaming made out of one.
     *
     * **New keys rather than a migration.** Nothing runs an older build of this app, so a key that
     * only an older build wrote is a key with no reader — and the defaults below are what a person
     * who has never set either should get.
     */
    const val KEY_BUBBLE_LINES = "chat_bubble_lines"
    const val DEFAULT_BUBBLE_LINES = 0
    const val MAX_BUBBLE_LINES = 200

    const val KEY_ACK = "agent_ack_seconds"
    const val KEY_GAP = "agent_gap_seconds"

    /** Long enough for a queue and a buffered first delta; see [ackSeconds]. */
    const val DEFAULT_ACK_SECONDS = 90
    const val MIN_ACK_SECONDS = 30
    const val MAX_ACK_SECONDS = 600

    /** A model thinking is quiet, so this is the number that must not be tight. */
    const val DEFAULT_GAP_SECONDS = 120
    const val MIN_GAP_SECONDS = 30
    const val MAX_GAP_SECONDS = 1_800

    /** How long a failing connection is retried. A new key: it is a new number, not a renamed one. */
    const val KEY_RECONNECT = "agent_reconnect_seconds"

    /** Whether a waiting conversation is kept warm, how often, and for how long. */
    const val KEY_KEEPALIVE = "agent_keepalive"
    const val KEY_WARM_MINUTES = "agent_warm_minutes"

    /** How often a waiting agent is warmed, and how long a command may run before it is stopped. */
    const val KEY_POLL = "agent_poll_seconds"
    const val KEY_FOREGROUND = "agent_foreground_seconds"
    const val KEY_BACKGROUND = "agent_background_seconds"
    /** Characters, from a build before the row was in tokens. Read once, by [migratedResult]. */
    const val KEY_MAX_RESULT = "agent_max_result"

    /** The result limit in tokens: the number the row shows and the agent is held to. */
    const val KEY_MAX_RESULT_TOKENS = "agent_max_result_tokens"
    const val KEY_SESSION_MB = "agent_session_mb"

    /**
     * A million tokens. Past this the number is larger than any window this app can be pointed at,
     * so a bigger one is a typo rather than a setting.
     */
    const val MAX_TOKENS = 1_048_576

    /** Four minutes: the brief's own number, and long enough for a turn that does real work. */
    const val DEFAULT_TIMEOUT_SECONDS = 240

    /** Ten minutes of trying, which is what the operator asked for. */
    const val DEFAULT_RECONNECT_SECONDS = 600

    /** The keepalive interval: a floor below which pinging is a loop of its own, and the 250 s cap. */
    const val MIN_POLL_SECONDS = 15
    const val MAX_POLL_SECONDS = 250

    /**
     * An hour, which is where keeping warm stops being cheaper than going cold.
     *
     * Twelve pings of cache read is roughly the price of one cache write of the same prompt, so an
     * absence longer than that is paid for twice if the pings keep coming. A day is the ceiling for
     * anyone who really wants it; zero is "until the person speaks".
     */
    const val DEFAULT_WARM_MINUTES = 60
    const val MAX_WARM_MINUTES = 1_440

    /**
     * Command limits: **three minutes in the conversation, off for work that goes to a file.**
     *
     * The two are different acts and get different answers. A foreground command is a row the model
     * is waiting to read, and the person is waiting on the turn: three minutes is the operator's
     * number, and past it the command is stopped and its result says so. A background command's
     * result is a file nothing is waiting on, so its default stays zero — let it run, because there
     * is no conversation held open.
     *
     * Zero is still allowed in both, and still means "however long it takes". The ceiling is a
     * quarter of an hour: past that it is not a command, it is a hung connection, and the tool's own
     * socket timeout answers first.
     */
    const val DEFAULT_FOREGROUND_SECONDS = 180
    const val DEFAULT_BACKGROUND_SECONDS = 0
    const val MAX_COMMAND_SECONDS = 900

    /** Under a minute a real request cannot finish; half an hour holds a worker too long. */
    /**
     * The bounds on the answer wait, in seconds.
     *
     * **The same numbers the minutes version had** — 1 minute to 30 — expressed in the unit the rest
     * of the screen uses. A person who wants four minutes types 240.
     */
    /**
     * **Half a minute is the floor, and it is the old behaviour's own window.**
     *
     * Eight tries at one, two and four seconds added up to about half a minute; a person who wants
     * the app to give up quickly can ask for exactly that, and no less — below it the ladder barely
     * completes one round trip.
     */
    const val MIN_RECONNECT_SECONDS = 30

    /** An hour: past this the app is retrying something that will not heal while the phone sleeps. */
    const val MAX_RECONNECT_SECONDS = 3_600

    const val MIN_TIMEOUT_SECONDS = 60
    const val MAX_TIMEOUT_SECONDS = 1_800

    /**
     * Six thousand tokens is about twenty thousand characters — a long source file, which is what a
     * result is for. The number is the same allowance the app always had, said in the unit the
     * limit beside it is in.
     *
     * **[MAX_MAX_RESULT] is not the ceiling that matters.** The number a person may actually ask for
     * is bounded by the model's input window (`ContextBudget.resultLimit`), because a result bigger
     * than the window cannot be sent at all. The constant exists only so the field has an upper
     * bound before any model is known — and it is a million tokens in characters, the largest window
     * this app can be pointed at, so it never becomes the limit the window should be.
     */
    const val DEFAULT_MAX_RESULT = 6_000
    const val MIN_MAX_RESULT = 1_000
    const val MAX_MAX_RESULT = 4_194_304

    /** The session log is kept whole unless the operator says otherwise. */
    const val DEFAULT_SESSION_MB = 0
    const val MAX_SESSION_MB = 512

        /** One hour: the design's default. */
        const val DEFAULT_REMIND_MINUTES = 60

        /** A week. Past this a reminder is a message from the past. */
        const val MAX_REMIND_MINUTES = 10_080

        /** The two keys older builds wrote; read once, then ignored. */
        const val KEY_APP_HISTORY = "app_history_length"
        const val KEY_PROJECT_HISTORY = "project_history_length"

        /** The number a typed field means. */
        fun parseLength(text: String): Int = text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

        /** What a length looks like in its field: blank when there is no limit. */
        fun showLength(value: Int): String = if (value <= 0) "" else value.toString()
    }
}
