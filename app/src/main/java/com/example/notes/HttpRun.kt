package com.example.notes

import java.io.File

/**
 * **One request, sent and filed** — the half of `http` that has nothing to do with being a tool.
 *
 * The agent reaches this through [AgentTools.http]; a person reaches it by running a `.http` file from
 * the browser. Both must send the same thing and file the answer in the same place, or a request that
 * works in the conversation would not work from a screen and nobody would know which one was wrong.
 *
 * So the split is: [AgentTools] decides *whether* a request may be sent (the network switch, the
 * address rule, the deadline), and this object does the sending and the filing. Everything here is
 * about a request that has already been allowed.
 */
object HttpRun {

    /**
     * What happened, in the two shapes a caller needs.
     *
     * **`text` is what a person reads**, and it is the same sentence the agent gets: the status, the
     * body when it is small enough, and the path the whole answer was filed at. `ok` is whether the
     * status was 2xx, which is not the same question as "did the tool work" — a 404 is a working
     * request that found nothing.
     */
    data class Outcome(
        val ok: Boolean,
        val status: Int,
        val said: String,
        /** Where the answer was filed, account-relative. Null when it could not be written. */
        val filed: String?,
    )

    /**
     * Sends [request] and files the answer under [root]'s `agent/http/`.
     *
     * [root] is the project's working tree, because that is where a person looks for what the agent
     * did — a request run from a screen lands in the same folder as one run from a conversation, which
     * is what makes "what did this project send" one question rather than two.
     */
    fun send(
        request: HttpRequest,
        root: File,
        fetcher: Fetcher,
        limitBytes: Long,
    ): Result<Outcome> {
        val answer = fetcher.request(request.url, request.method, request.headers, request.body, limitBytes)
            .getOrElse { return Result.failure(it) }
        val dir = File(root, Fetched.HTTP_DIR)
        val file = File(dir, nameFor(request))
        val rel = try {
            dir.mkdirs()
            file.writeBytes(answer.bytes)
            file.relativeTo(root).invariantSeparatorsPath
        } catch (e: Throwable) {
            return Result.failure(
                IllegalStateException("The answer came back and could not be saved: " + Secrets.explain(e, "not written")),
            )
        }
        return Result.success(Outcome(answer.ok, answer.status, said(request, answer, file, rel, root), rel))
    }

    /**
     * The sentence: **the status first, then the body, then where it went.**
     *
     * An API's vocabulary is statuses, and a model — or a person — that read "done" for a 422 would
     * stop checking. The path is said last because it is the least interesting thing until the body is
     * too big to quote, and then it is the only thing.
     */
    private fun said(
        request: HttpRequest,
        answer: Fetcher.Answer,
        file: File,
        rel: String,
        root: File,
    ): String {
        val head = "${request.method} ${request.url} → ${answer.status}"
        // **Small text is quoted**, because the next decision is made from it. Binary, or anything
        // large, is filed only — a JPEG in the window spends the budget on something nobody can read.
        val inline = answer.bytes.size <= AgentTools.HTTP_INLINE && !looksBinary(answer.bytes)
        val body = when {
            inline -> answer.text()
            answer.ok -> "The answer is ${Fetcher.size(answer.bytes.size.toLong())} and is filed, not quoted."
            else -> "The answer is filed at $rel."
        }
        // **A downloaded archive says what is inside it**, because "which files did this produce" is
        // the question asked next. A zip that will not open is not a failure of the download — the
        // bytes are on disk either way — so an unreadable one simply has nothing to list.
        val inside = if (request.method == "GET" && answer.ok) archiveListing(file, root) else null
        val redirect = if (answer.status in 300..399) {
            "\n\nA redirect was returned rather than followed: a ${request.method} that became a GET " +
                "would lose its body. Location: " + answer.headers["Location"].orEmpty()
        } else {
            ""
        }
        return head + "\n\n" + body + "\n\nIt is at $rel." + (inside ?: "") + redirect + rateLimitNote(answer)
    }

    /**
     * What an archive holds, as a sentence, or `null` when the file is not one.
     *
     * **Moved from `fetch`, which is why `fetch` was not merely `http` with GET.** A model that has
     * just downloaded a zip needs to know which paths are inside before it can unpack it, and the
     * listing is the difference between "I have a file" and "I have a dependency".
     */
    private fun archiveListing(file: File, root: File): String? {
        val inside = try {
            java.util.zip.ZipFile(file).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }
                    .take(AgentTools.FETCHED_SHOWN).map { it.name }.toList()
            }
        } catch (_: Throwable) {
            return null
        }
        if (inside.isEmpty()) return null
        val path = file.relativeTo(root).invariantSeparatorsPath
        return "\n\nIt holds: " + inside.joinToString(", ") +
            "\nIt is filed whole, not unpacked. `archive {\"path\":\"$path\",\"unpack\":true}` " +
            "unpacks it into the project."
    }

    /**
     * The rate-limit headers, when they are there.
     *
     * **A caller that can see the number waits instead of retrying into a wall**, and GitHub sends
     * these on every answer, so a model that never read them would hammer an API it was told to slow
     * down on.
     */
    private fun rateLimitNote(answer: Fetcher.Answer): String {
        val left = answer.headers.entries
            .firstOrNull { it.key.equals("X-RateLimit-Remaining", ignoreCase = true) }?.value
            ?: return ""
        val reset = answer.headers.entries
            .firstOrNull { it.key.equals("X-RateLimit-Reset", ignoreCase = true) }?.value
        return "\n\n$left requests remain" + if (reset.isNullOrEmpty()) "." else " until $reset."
    }

    /**
     * A filename for an answer, **unique per request and readable to a person.**
     *
     * `Fetched.nameFor` already answers the readable half — the last path segment, or the host when the
     * URL is a bare endpoint. The hash is of the **method and the body**, not the URL: two `POST`s to
     * one endpoint are two different requests, and without it the second would overwrite the first's
     * answer and a comparison would be reading the same file twice.
     */
    fun nameFor(request: HttpRequest): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((request.method + "\n" + (request.body?.let { String(it, Charsets.UTF_8) } ?: "")).toByteArray())
        val short = digest.take(3).joinToString("") { "%02x".format(it) }
        return Fetched.nameFor(request.url) + "-" + short
    }
}
