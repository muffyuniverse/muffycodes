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
     * How many changes — local commits — are kept on this phone. 0 keeps all.
     *
     * Applies to the phone's own change history and to each project's individual
     * limit alike.
     */
    var historyLength: Int by mutableStateOf(prefs.getInt(KEY_HISTORY, legacyLength()))
        private set

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
     * How often an idle agent is checked on, in seconds — **the operator's number.**
     *
     * The agent says only that it is idle; how often the app comes back is a decision about this
     * person's phone: a poll is a request that is paid for and a wake that costs battery, and a
     * shorter interval is a faster agent on both counts. It is not the agent's to choose, because an
     * agent has no way of knowing what a request costs the person paying for it.
     *
     * Zero means the design's own four minutes. The ceiling is 250 seconds, which is short enough
     * that an idle session is never far from being seen.
     */
    var pollSeconds: Int by mutableStateOf(prefs.getInt(KEY_POLL, 0))
        private set

    /** Sets the idle interval, clamped. */
    fun keepPoll(seconds: Int) {
        val clean = if (seconds <= 0) {
            0
        } else {
            seconds.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS)
        }
        Trace.tap("poll interval", if (clean == 0) "the app's own" else "$clean s")
        pollSeconds = clean
        prefs.edit().putInt(KEY_POLL, clean).apply()
        Trace.said(if (clean == 0) "polled at the app's own four minutes" else "polled every $clean s")
    }

    /** The interval in milliseconds, with the design's own when the person set none. */
    val pollMs: Long
        get() = if (pollSeconds <= 0) {
            AgentSession.POLL_MS
        } else {
            (pollSeconds * 1000L).coerceAtMost(AgentSession.MAX_IDLE_MS)
        }

    /**
     * How long a **command** may take before the turn stops waiting for it, in seconds.
     *
     * **The other wait, and it is a different question.** [timeoutMinutes] is how long the app waits
     * for the model's *answer*; this is how long it waits for a tool the model asked for to finish.
     * A `clone` of a large repository takes minutes, and holding the conversation for it means the
     * person sees nothing and the agent can do nothing else. Past this the agent is told the command
     * is still running, and the work carries on beside the conversation.
     *
     * Zero means wait for it however long it takes, which is what the app did before this existed.
     */
    var commandSeconds: Int by mutableStateOf(prefs.getInt(KEY_COMMAND, DEFAULT_COMMAND_SECONDS))
        private set

    /** Sets the command's soft timeout, clamped. */
    fun keepCommand(seconds: Int) {
        val clean = seconds.coerceIn(0, MAX_COMMAND_SECONDS)
        Trace.tap("command timeout", if (clean == 0) "wait for ever" else "$clean s")
        commandSeconds = clean
        prefs.edit().putInt(KEY_COMMAND, clean).apply()
        Trace.said(
            if (clean == 0) "a command may take as long as it takes" else "a command may take $clean s"
        )
    }

    /** The command's soft timeout in milliseconds; zero is "wait for it". */
    val commandMs: Long get() = commandSeconds * 1000L

    /**
     * How long the app waits for the model's answer, in minutes.
     *
     * **This is what turns "the provider is not answering" into a blip.** The door's read timeout
     * ends the attempt, the ladder retries it at one, two, four seconds, and the session reconnects
     * instead of hanging. Too short retries a long turn for ever; too long holds the session's one
     * worker for half an hour on a provider that is simply down. The original brief asked for four
     * minutes and a field, because a phone on a slow network is a fact only the person holding it
     * knows.
     */
    var timeoutMinutes: Int by mutableStateOf(
        prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_MINUTES)
    )
        private set

    /** Sets the answer timeout, clamped to a wait a person would recognise. */
    fun keepTimeout(minutes: Int) {
        val clean = minutes.coerceIn(MIN_TIMEOUT_MINUTES, MAX_TIMEOUT_MINUTES)
        Trace.tap("answer timeout", "$clean min")
        timeoutMinutes = clean
        prefs.edit().putInt(KEY_TIMEOUT, clean).apply()
        Trace.said("the app waits $clean minutes for an answer")
    }

    /**
     * The most one tool result may carry, in characters.
     *
     * The brief asked for this as a field, and the reason is the same as the timeout's: a result
     * longer than the limit says so — `1`, and how much of it was shown — and the person can raise
     * the number rather than being told by an agent that cannot read its own file.
     */
    var maxResult: Int by mutableStateOf(
        prefs.getInt(KEY_MAX_RESULT, DEFAULT_MAX_RESULT)
    )
        private set

    /** Sets the longest result. Never zero: a tool that answers with nothing is a hang. */
    fun keepMaxResult(chars: Int) {
        val clean = chars.coerceIn(MIN_MAX_RESULT, MAX_MAX_RESULT)
        Trace.tap("result length", "$clean")
        maxResult = clean
        prefs.edit().putInt(KEY_MAX_RESULT, clean).apply()
        Trace.said("one result may carry $clean characters")
    }

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

        /** The reminder interval, in minutes. */
        const val KEY_REMIND = "agent_remind_minutes"

    /** The two context limits, in tokens, as the operator typed them. */
    const val KEY_TRIGGER = "agent_trigger_tokens"
    const val KEY_HARD = "agent_hard_tokens"

    /** How long one answer may take, in minutes, and how much one result may carry. */
    const val KEY_TIMEOUT = "agent_timeout_minutes"

    /** How often an idle agent is polled, and how long a command may run before a handover. */
    const val KEY_POLL = "agent_poll_seconds"
    const val KEY_COMMAND = "agent_command_seconds"
    const val KEY_MAX_RESULT = "agent_max_result"

    /**
     * A million tokens. Past this the number is larger than any window this app can be pointed at,
     * so a bigger one is a typo rather than a setting.
     */
    const val MAX_TOKENS = 1_048_576

    /** Four minutes: the brief's own number, and long enough for a turn that does real work. */
    const val DEFAULT_TIMEOUT_MINUTES = 4

    /** The idle interval: a floor below which polling is a loop of its own, and the 250 s cap. */
    const val MIN_POLL_SECONDS = 15
    const val MAX_POLL_SECONDS = 250

    /**
     * A command's soft timeout, **off by default.**
     *
     * Zero is "wait for the command however long it takes", which is exactly what this app did
     * before the handover existed. A default of one minute would have changed the behaviour of every
     * agent on every phone without anybody asking — and a command that is handed over when nobody
     * wanted it costs a whole extra request. The row exists so a person whose commands are slow can
     * choose it; the app does not choose it for them.
     */
    const val DEFAULT_COMMAND_SECONDS = 0
    const val MAX_COMMAND_SECONDS = 900

    /** Under a minute a real request cannot finish; half an hour holds a worker too long. */
    const val MIN_TIMEOUT_MINUTES = 1
    const val MAX_TIMEOUT_MINUTES = 30

    /** Twenty thousand characters is about a long source file, which is what a result is for. */
    const val DEFAULT_MAX_RESULT = 20_000
    const val MIN_MAX_RESULT = 1_000
    const val MAX_MAX_RESULT = 200_000

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
