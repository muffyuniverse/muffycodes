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
            ?: DEFAULT_THEME
    )
        private set

    var useDark: Boolean by mutableStateOf(prefs.getBoolean(KEY_DARK, false))
        private set

    /**
     * **How far through onboarding a person got** — the index of the page they were on.
     *
     * A number rather than a boolean, because the pages are a folder a person swipes through and a
     * person who quits on page four should not have to swipe past three they have read. It is also
     * the *only* record of progress: there is no separate "done" flag, because finishing is
     * `onboardingAt >= pages.size`, and two records that must agree are two records that eventually
     * will not.
     *
     * **It is not a preference and is not reset by "reset settings".** It records where a person got
     * to, which is not a setting they chose.
     */
    var onboardingAt: Int by mutableStateOf(prefs.getInt(KEY_ONBOARDING_AT, 0))
        private set

    /** Records the page a person is on, so the next launch resumes there. */
    fun markOnboardingAt(index: Int) {
        prefs.edit().putInt(KEY_ONBOARDING_AT, index).apply()
        onboardingAt = index
    }

    /**
     * True when onboarding is finished.
     *
     * **A property rather than a stored flag**, so "finished" cannot disagree with "where they got
     * to". The count is passed in because the pages live in [Onboarding] and this class has no
     * business knowing how many there are.
     */
    fun onboardingDone(count: Int): Boolean = onboardingAt >= count

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
     * **Not a setting any more.** The operator's question, and the answer to it: *"why is there a
     * switch? dev version is for debugging - trace log should be on by default."*
     *
     * The switch cost a week. A debug build exists to be reported on, the log is the report, and
     * every session where it was off produced a paste that looked like a healthy session with no
     * file behind it. A development aid that can be quietly switched off is a development aid that
     * will be off on the day something goes wrong — and the person then has to *remember* it was
     * off, which is exactly the thing nobody remembers.
     *
     * **Still gated on `DEBUG`, and that is not a setting either.** A release build has no business
     * writing a running log of a person's tapping into a folder other apps can read, so there it is
     * off and cannot be turned on. In a debug build it is on, always.
     */
    val liveTrace: Boolean get() = BuildConfig.DEBUG

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

    // **The three context numbers are not settings any more — they are the form's.** They used to
    // live here as `triggerTokens`/`hardTokens`/`targetTokens`, and when N6 made the request form the
    // only answer to "what is this chat asked with", every reader moved to `Wired.ofForm` while these
    // three fields stayed behind: a person could type a number into Settings and no request ever read
    // it. A write-only setting is worse than a missing one, because it answers a question it has no
    // part in. They are deleted rather than kept as a default, on the operator's decision: a limit is
    // per chat, and the form is where a per-chat fact already lives.

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

    /**
     * Whether the agent may browse this phone and the private network.
     *
     * **Off unless the operator says otherwise.** The public internet is the default because that is
     * what "read a web page" means; loopback and the private ranges are a capability on top of it,
     * and a capability that appears without being granted is the one kind of default this app does
     * not allow.
     */
    var browsePrivate: Boolean by mutableStateOf(prefs.getBoolean(KEY_BROWSE_PRIVATE, false))
        private set

    /** Turns the private-network permission on or off. */
    fun keepBrowsePrivate(on: Boolean) {
        Trace.tap("browse private", if (on) "on" else "off")
        browsePrivate = on
        prefs.edit().putBoolean(KEY_BROWSE_PRIVATE, on).apply()
        Trace.said(
            if (on) {
                "the agent may read this phone and the private network"
            } else {
                "the agent may read the public web only"
            },
        )
    }

    /**
     * How long one page may take, in milliseconds; zero is the browser's own bound.
     *
     * Read per call like the other limits, so a number changed while an agent is idle applies to the
     * next page it reads rather than to the runner built before it.
     */
    val browseMs: Long get() = prefs.getInt(KEY_BROWSE_SECONDS, DEFAULT_BROWSE_SECONDS) * 1_000L

    /** Sets the page limit, in seconds, clamped to something a phone can survive. */
    fun keepBrowseSeconds(seconds: Int) {
        val clean = seconds.coerceIn(MIN_BROWSE_SECONDS, MAX_BROWSE_SECONDS)
        Trace.tap("browse timeout", "$clean")
        prefs.edit().putInt(KEY_BROWSE_SECONDS, clean).apply()
        Trace.said("a page may take $clean seconds")
    }

    /**
     * Whether the agent may download files from the web at all.
     *
     * **On by default, unlike [browsePrivate]**, and the difference is the direction of the risk: a download
     * writes bytes onto the phone, which a person can see and delete, while reaching the private network is a
     * capability that should never appear without being granted. `fetch` is the one tool that turns an address
     * into a file, and turning this off removes it rather than making it refuse.
     *
     * **Removing it is the point.** A tool that refuses still appears in the tool list, and the tool list is
     * part of every request's cache prefix — so a person who turns this off would pay for the description of a
     * capability they do not have, in every request, for ever.
     */
    var httpAllowed: Boolean by mutableStateOf(prefs.getBoolean(KEY_FETCH, true))
        private set

    /**
     * Turns reaching the network on or off.
     *
     * **The stored key keeps its old name.** `KEY_FETCH` is what is on every phone that has this
     * setting, and renaming it would silently turn the switch back on for anyone who had it off —
     * a security setting that resets itself is worse than a name that reads oddly.
     */
    fun keepHttp(on: Boolean) {
        Trace.tap("http", if (on) "on" else "off")
        httpAllowed = on
        prefs.edit().putBoolean(KEY_FETCH, on).apply()
        Trace.said(
            if (on) {
                "the agent may send a request and keep the answer, in agent/http/"
            } else {
                "the agent may not reach the network"
            },
        )
    }

    /** What [keepBrowseSeconds] last set, in seconds, for the field to show. */
    val browseSeconds: Int get() = prefs.getInt(KEY_BROWSE_SECONDS, DEFAULT_BROWSE_SECONDS)

    /**
     * How large one download may be, in megabytes.
     *
     * **Read per call**, like every other limit here, so a number changed while an agent is idle
     * applies to the next request rather than to the runner built before it.
     *
     * **100 MB by default, and that number was the operator's, not the app's** — twenty refused
     * archives that were genuinely a dependency. A cap that cannot be changed is the tool's opinion
     * rather than the person's, which is why this is a setting at all; it is still a refusal and
     * still not a truncation, so a 2 GB ISO cannot arrive by accident.
     */
    val fetchMegabytes: Int get() = prefs.getInt(KEY_FETCH_MB, DEFAULT_FETCH_MB)

    /** Sets the download cap, in megabytes, clamped to something a phone can survive. */
    fun keepFetchMegabytes(megabytes: Int) {
        val clean = megabytes.coerceIn(MIN_FETCH_MB, MAX_FETCH_MB)
        Trace.tap("fetch size", "$clean MB")
        prefs.edit().putInt(KEY_FETCH_MB, clean).apply()
        Trace.said("one download may be $clean MB")
    }

    /** The cap in bytes, which is what the fetcher compares against. */
    val fetchBytes: Long get() = fetchMegabytes.toLong() * 1024L * 1024L

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
     * **The answer wait is gone, and where it went matters.**
     *
     * It bounded the same thing the connection wait bounds - nothing has come back yet - on the one
     * transport that is not streamed: the keepalive ping. So it was a second number for one silence,
     * with no field on any screen and a sentence instead, which made it in force and invisible at the
     * same time. Reported as "connection timeout (ignored)": that is exactly what an unsettable
     * number feels like from the outside.
     *
     * The ping reads [responseSeconds] now, so one number covers "the provider never took it"
     * whichever way the request was sent.
     */
    /**
     * **One number, and it means "nothing has arrived for this long".**
     *
     * The operator merged the two clocks: "lets merge connection and stalling timeouts into a single
     * response timeout that tracks time between request and first response packet" - and, when asked
     * what bounds a model that dies mid-answer, "one number that also restarts per packet".
     *
     * So this is an **idle** timeout in the strict sense: it restarts on every packet. The first
     * packet is bounded by it, and so is every gap after that, which means a model thinking hard
     * about its next sentence is safe as long as it is working, and a connection that died
     * mid-sentence is caught by the same number.
     *
     * **Deliberately generous, and it must be.** An upstream that is queueing holds the connection
     * open and sends nothing at all, and OpenRouter buffers the first delta - so a tight number here
     * would kill working requests at the busiest moment. The cost of waiting too long is a slow
     * failure; the cost of not waiting long enough is refusing an answer that was about to begin.
     *
     * **One setting and one key.** It used to be two, and a build that carried the two old numbers
     * forward was a build with a reader for a format nothing writes any more — which is what "no
     * migration" forbids. A phone finds its own key or the default, and either answer is a number a
     * person can read on the row and change.
     */
    var responseSeconds: Int by mutableStateOf(
        prefs.getInt(KEY_RESPONSE, DEFAULT_RESPONSE_SECONDS)
    )
        private set

    /** Sets the response wait, clamped. */
    fun keepResponse(seconds: Int) {
        val clean = seconds.coerceIn(MIN_RESPONSE_SECONDS, MAX_RESPONSE_SECONDS)
        Trace.tap("response timeout", "$clean s")
        responseSeconds = clean
        prefs.edit().putInt(KEY_RESPONSE, clean).apply()
        Trace.said("the app waits $clean seconds for an answer to continue")
    }

    /** The response wait in milliseconds. */
    val responseMs: Int get() = responseSeconds * 1000

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
     * converted in the head to compare with a 128k window. The key changed with the unit, so a stored
     * character count is never read as a token count: it has no reader at all.
     */
    var maxResult: Int by mutableStateOf(prefs.getInt(KEY_MAX_RESULT_TOKENS, DEFAULT_MAX_RESULT))
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

    /**
     * The session log's ceiling, **in tokens**.
     *
     * **One field, and one unit.** This used to be megabytes of the file on disk, which is a
     * storage budget; what a person is choosing is how much conversation the agent may keep, and
     * that is measured the same way the model is billed. Report: "for session just one field: max
     * length (in tokens with default 10000000). this mb and lines limits are to be removed."
     *
     * **Zero means "keep everything"**, which is still reachable - a blank field. The default is ten
     * million, which is far above any real session: a limit that fires is a limit somebody asked for,
     * not one the app chose.
     */
    var sessionTokens: Int by mutableStateOf(prefs.getInt(KEY_SESSION_TOKENS, DEFAULT_SESSION_TOKENS))
        private set

    /** Sets the session log's ceiling in tokens; zero keeps everything. */
    fun keepSession(tokens: Int) {
        val clean = tokens.coerceIn(0, MAX_SESSION_TOKENS)
        Trace.tap("session log", if (clean == 0) "kept whole" else "$clean tokens")
        sessionTokens = clean
        prefs.edit().putInt(KEY_SESSION_TOKENS, clean).apply()
        Trace.said(
            if (clean == 0) "the session log is kept whole"
            else "the session log is kept under ${ContextBudget.short(clean.toLong())} tokens",
        )
    }

    /** The session log's ceiling in tokens; zero is "keep everything". */
    val sessionLimit: Long get() = sessionTokens.toLong()

    /**
     * **Every setting back to the number the app shipped with.**
     *
     * One function, because a reset that restates the defaults is a second copy of them and the two
     * drift: a default changed at its read site would leave the reset restoring the old value, and
     * nothing would say so. Every value here is the same `DEFAULT_*` constant its reader uses, so
     * there is exactly one statement of each number in the app.
     *
     * **`onboardingAt` is deliberately NOT reset.** It is not a preference; it records how far
     * through the introduction a person got, and resetting it would make them read it again - a
     * side effect nobody asking for "restore defaults" expects.
     *
     * The theme IS reset, to the one the app starts with, because it is the most visible setting on
     * the screen and a reset that left it alone would look like it had not worked.
     */
    fun reset() {
        Trace.tap("reset settings")
        // **Theme first**, because it repaints the screen the button was pressed on - and to the
        // theme the app actually starts with, which is `CARAMEL` rather than whichever enum member
        // happens to be first. A reset to the wrong theme is a reset that looks like a bug.
        selectTheme(DEFAULT_THEME)
        selectDarkVariant(false)
        keepRequests(0)
        // **No context numbers here, because they are not settings.** A reset restores this screen
        // to its defaults; the three compaction numbers live in each chat's request form, which a
        // reset has no business reaching into.
        keepWarm(true)
        keepPoll(0)
        keepWarmFor(DEFAULT_WARM_MINUTES)
        keepForeground(DEFAULT_FOREGROUND_SECONDS)
        keepBackground(DEFAULT_BACKGROUND_SECONDS)
        keepReconnect(DEFAULT_RECONNECT_SECONDS)
        keepResponse(DEFAULT_RESPONSE_SECONDS)
        keepMaxResult(DEFAULT_MAX_RESULT)
        keepBubbleLines(DEFAULT_BUBBLE_LINES)
        keepSession(DEFAULT_SESSION_TOKENS)
        keepRemind(DEFAULT_REMIND_MINUTES)
        Trace.said("every setting is back to what the app shipped with")
    }

    /**
     * Reads a length typed into a field. A blank field, or anything that is not
     * a number, means "keep everything".
     */
    companion object {


    /**
     * The theme the app starts with.
     *
     * Named rather than written as `AppTheme.entries.first()`, because "the first one" is a fact
     * about the enum's declaration order and not a decision - and a reset that restored the wrong
     * theme would be the first thing a person noticed.
     */
    val DEFAULT_THEME = AppTheme.CARAMEL

    const val KEY_THEME = "theme"
        const val KEY_DARK = "use_dark"

        /** Which onboarding page a person reached. Absent means they have not started. */
        const val KEY_ONBOARDING_AT = "onboarding_at"

        /** Days of request log kept. 0 (the default) keeps every response ever written. */
        const val KEY_REQUEST_DAYS = "request_days"

        /** The reminder interval, in minutes. */
        const val KEY_REMIND = "agent_remind_minutes"

    /** The two context limits, in tokens, as the operator typed them. */

    /** How tall one chat bubble may be, in lines. `0` is "no cap". */
    const val KEY_BUBBLE_LINES = "chat_bubble_lines"
    const val DEFAULT_BUBBLE_LINES = 0
    const val MAX_BUBBLE_LINES = 200

    const val KEY_RESPONSE = "agent_response_seconds"

    /**
     * **Two minutes.** It bounds the first packet and every gap after it, so it must be wide enough
     * for an upstream that is queueing and a model that pauses mid-sentence — and still narrow
     * enough that a dead connection is caught. See [responseSeconds].
     */
    /** A page: long enough for a slow site, short enough that a turn is not lost to one. */
    const val DEFAULT_BROWSE_SECONDS = 30

    /** One download's default ceiling, in megabytes. */
    const val DEFAULT_FETCH_MB = 100

    /**
     * The range a person may set.
     *
     * **One megabyte minimum**, because a cap of zero would be a switch wearing a number's clothes —
     * and there is already a switch for "no downloads at all". **A gigabyte maximum**, because beyond
     * that the phone's own storage is the real limit and the app should not be the thing that
     * discovers it.
     */
    const val MIN_FETCH_MB = 1
    const val MAX_FETCH_MB = 1024

    const val MIN_BROWSE_SECONDS = 5
    const val MAX_BROWSE_SECONDS = 180

    const val DEFAULT_RESPONSE_SECONDS = 120
    const val MIN_RESPONSE_SECONDS = 30
    const val MAX_RESPONSE_SECONDS = 1_800

    /** A model thinking is quiet, so this is the number that must not be tight. */

    /** How long a failing connection is retried. A new key: it is a new number, not a renamed one. */
    const val KEY_RECONNECT = "agent_reconnect_seconds"

    /** Whether a waiting conversation is kept warm, how often, and for how long. */
    const val KEY_KEEPALIVE = "agent_keepalive"
    const val KEY_WARM_MINUTES = "agent_warm_minutes"

    /**
     * **Whether the agent may reach this phone and the private network**, and how long a page may
     * take.
     *
     * Off by default, and the default is the point: a browsable address is a capability, and a page
     * that could steer the agent at `192.168.1.1` has reached the operator's router without either
     * of them asking. A home server or a NAS is a real use, and it is one a person should choose.
     */
    const val KEY_BROWSE_PRIVATE = "agent_browse_private"

    /** Whether downloading is allowed at all. */
    const val KEY_FETCH = "agent_fetch"
    const val KEY_BROWSE_SECONDS = "agent_browse_seconds"

    const val KEY_FETCH_MB = "agent_fetch_mb"

    /** How often a waiting agent is warmed, and how long a command may run before it is stopped. */
    const val KEY_POLL = "agent_poll_seconds"
    const val KEY_FOREGROUND = "agent_foreground_seconds"
    const val KEY_BACKGROUND = "agent_background_seconds"

    /** The result limit in tokens: the number the row shows and the agent is held to. */
    const val KEY_MAX_RESULT_TOKENS = "agent_max_result_tokens"
    const val KEY_SESSION_TOKENS = "agent_session_tokens"


    /**
     * A million tokens. Past this the number is larger than any window this app can be pointed at,
     * so a bigger one is a typo rather than a setting.
     */
    const val MAX_TOKENS = 1_048_576

    /** Four minutes: the brief's own number, and long enough for a turn that does real work. */

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
    const val DEFAULT_SESSION_TOKENS = 10_000_000
    const val MAX_SESSION_TOKENS = 100_000_000

        /** One hour: the design's default. */
        const val DEFAULT_REMIND_MINUTES = 60

        /** A week. Past this a reminder is a message from the past. */
        const val MAX_REMIND_MINUTES = 10_080

        /** The number a typed field means. */
        fun parseLength(text: String): Int = text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

        /** What a length looks like in its field: blank when there is no limit. */
        fun showLength(value: Int): String = if (value <= 0) "" else value.toString()
    }
}
