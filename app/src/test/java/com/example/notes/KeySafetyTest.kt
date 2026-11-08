package com.example.notes

import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The key must not be findable anywhere, and this is the test that keeps it
 * that way.
 *
 * It runs a clone and a push with a token that looks exactly like a real one,
 * then goes through every file git wrote — the working tree, `.git/config`, the
 * logs, all of it — and fails if the token appears so much as once. It also
 * checks the messages the app would show, because a message that quotes an
 * address is the classic way a key escapes.
 */
class KeySafetyTest {

    private val token = "ghp_" + "S3cretValueNotARealToken0123456789"
    private val author = GitAuthor("Test", "test@example.com")
    private lateinit var root: File

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "keysafety-${System.nanoTime()}")
        root.mkdirs()
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** Every file below [dir], including everything git keeps hidden. */
    private fun allFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile }.toList()

    /** True when a file holds the key — searched byte-wise, so binary objects count. */
    private fun File.containsToken(): Boolean =
        readBytes().toString(Charsets.ISO_8859_1).contains(token)

    @Test
    fun `scrub masks credentials inside urls`() {
        val message = "Cloned https://$token@github.com/muffyuniverse/muffycodes.git"
        val scrubbed = Secrets.scrub(message)
        assertFalse(scrubbed.contains(token))
        assertTrue(scrubbed.contains("https://***@github.com"))
        assertTrue(scrubbed.contains("muffyuniverse/muffycodes.git"))
    }

    @Test
    fun `scrub masks loose tokens`() {
        val scrubbed = Secrets.scrub("failed with token $token, sorry")
        assertFalse(scrubbed.contains(token))
        assertTrue(scrubbed.contains("***"))
    }

    @Test
    fun `scrub leaves ordinary text alone`() {
        val message = "Cloned https://github.com/a/b.git — commit 1a2b3c4, 3 files"
        assertEquals(message, Secrets.scrub(message))
    }

    @Test
    fun `only GitHub is given the key`() {
        assertTrue(Secrets.isGitHubHost("https://github.com/a/b.git"))
        assertTrue(Secrets.isGitHubHost("https://$token@github.com/a/b.git"))
        assertTrue(Secrets.isGitHubHost("https://api.github.com"))
        assertFalse(Secrets.isGitHubHost("https://github.com.evil.example/a/b.git"))
        assertFalse(Secrets.isGitHubHost("https://evil.example/a/b.git"))
        assertFalse(Secrets.isGitHubHost("/some/local/path"))
    }

    @Test
    fun `secret cannot be printed by accident`() {
        val secret = Secret(token)
        assertEquals("***", secret.toString())
        assertEquals(token, secret.reveal())
    }

    @Test
    fun `clone puts the key nowhere`() {
        val remote = File(root, "remote")
        val remoteRepo = GitService.initOrOpen(remote, author)
        File(remote, "note.txt").writeText("milk")
        GitService.commitAll(remoteRepo, "note", author)
        remoteRepo.close()

        val target = File(root, "clone")
        val result = GitService.clone(remote.absolutePath, target, author, token)

        assertTrue(result.message, result.success)
        assertFalse("the message must not carry the key", result.message.contains(token))
        val leaky = allFiles(target).filter { it.containsToken() }
        assertTrue("the key was written into: ${leaky.map { it.relativeTo(target) }}", leaky.isEmpty())
    }

    @Test
    fun `push puts the key nowhere`() {
        // A bare repository — the way a push target really is.
        val remote = File(root, "remote")
        org.eclipse.jgit.api.Git.init()
            .setDirectory(remote)
            .setBare(true)
            .setInitialBranch("main")
            .call()
            .close()

        val work = File(root, "work")
        val repo = GitService.initOrOpen(work, author)
        File(work, "note.txt").writeText("milk")
        GitService.commitAll(repo, "note", author)
        GitService.setRemoteUrl(repo, remote.absolutePath)

        val result = GitService.push(repo, token)

        assertFalse("the message must not carry the key", result.message.contains(token))
        // The push really moved the commit.
        FileRepositoryBuilder().setGitDir(remote).build().use { remoteRepo ->
            assertTrue("the push left nothing behind", remoteRepo.resolve("refs/heads/main") != null)
        }
        val leaky = (allFiles(work) + allFiles(remote))
            .filter { it.containsToken() }
        assertTrue("the key was written into: ${leaky.map { it.relativeTo(root) }}", leaky.isEmpty())
    }

    @Test
    fun `an old leaking address is healed when seen`() {
        val work = File(root, "work")
        val repo = GitService.initOrOpen(work, author)
        // What an older version of the app wrote to disk.
        GitService.setRemoteUrl(repo, "https://$token@github.com/a/b.git")

        GitService.scrubRemoteUrls(repo)

        assertEquals("https://github.com/a/b.git", GitService.remoteUrl(repo))
        repo.close()
    }

    @Test
    fun `a refused key is said in words and not as an exception`() {
        // Every sync that failed with "not authorized" and nothing else sent a person
        // looking for a broken app. The key is the thing to fix, so the key is what
        // the message has to be about.
        val refused = org.eclipse.jgit.errors.TransportException(
            "https://$token@github.com/a/b.git: not authorized",
        )
        val said = Secrets.explain(refused, "Push failed")
        assertFalse("the key is not in the message", said.contains(token))
        assertTrue("it says the key was refused: $said", said.contains("did not accept this phone's key"))
        assertTrue("and what to do: $said", said.contains("Sign in again"))
    }

    @Test
    fun `an offline phone is said as being offline`() {
        // The shape JGit reports it in: its own words first, the network failure
        // underneath as the cause.
        val offline = RuntimeException(
            "cannot open git-upload-pack",
            java.net.UnknownHostException("github.com"),
        )
        val said = Secrets.explain(offline, "Push failed")
        assertTrue("it names the network: $said", said.contains("Could not reach GitHub"))
        assertFalse("and does not pretend a file went: $said", said.contains("Pushed"))
    }

    @Test
    fun `an error rather than an exception still says what it saw`() {
        // What a phone actually threw: a class JGit needs that the runtime cannot
        // resolve arrives as an Error, and `catch (Exception)` let it out of the
        // worker with no project, no file and no action in it.
        val notResolved = NoClassDefFoundError("Failed resolution of: Lorg/eclipse/jgit/Foo;")
        val said = Secrets.explain(notResolved, "Push failed")
        assertTrue("it keeps what it saw: $said", said.contains("Failed resolution"))
        assertTrue("and says what kind of failure it was: $said", said.contains("NoClassDefFoundError"))
    }

    @Test
    fun `a failure with nothing to add keeps the app's own words`() {
        // The falsification of all of the above: a failure that is not one of the
        // four known shapes must not be swallowed into a friendly sentence that says
        // nothing. JGit's own words are what a person can search for.
        val odd = RuntimeException("Unknown command: frobnicate")
        assertEquals("Unknown command: frobnicate", Secrets.explain(odd, "Push failed"))
        assertEquals("Push failed", Secrets.explain(RuntimeException(), "Push failed"))
    }

    @Test
    fun `a number in a path is not an http status`() {
        // The bug this lives beside: the classifier matched a bare "401" anywhere in the
        // chain, and a lock's message is a path — `/tmp/projectgit-1761300401234/...`.
        // A nanosecond timestamp containing "401" told a person their key was wrong when
        // their repository was locked. Status codes are matched as words now.
        val locked = RuntimeException(
            "Exception caught during execution of add command",
            RuntimeException("Cannot lock /tmp/projectgit-1761300401234/repo/.git/index.lock"),
        )
        assertTrue(
            "a lock is a lock whatever digits its path holds",
            Secrets.explain(locked, "Sync failed").startsWith("This project was locked"),
        )
    }

    @Test
    fun `a lock is said as something the person can do`() {
        // A lock is the one failure whose own words are useless here: "Ensure that no
        // other process has an open file handle on /data/user/0/..." is an instruction
        // for a terminal, and a phone has no terminal. It gets a sentence instead.
        val locked = RuntimeException("Cannot lock /data/user/0/x/.git/index.lock")
        assertEquals(
            "This project was locked by another copy of the app, so nothing was changed. " +
                "Close the app completely and try again.",
            Secrets.explain(locked, "Push failed"),
        )
    }

    @Test
    fun `a message from a git exception is scrubbed before it is shown`() {
        // The shape of a JGit exception: it repeats the address it failed on.
        val exception = RuntimeException("https://$token@github.com/a/b.git: not authorized")
        val shown = Secrets.scrub(exception.message.orEmpty())
        assertFalse(shown.contains(token))
        assertTrue(shown.contains("not authorized"))
    }
    // ---- a locked phone is not a dead key -------------------------------------

    // **These are shapes, not the Android classes themselves.** A plain JVM test cannot construct a
    // stub from `android.jar` (its constructor is the mock that throws), and the store on a real phone
    // reports a locked key either as `UserNotAuthenticatedException` or as the keystore's own words in
    // a `KeyStoreException`. Both are here as text and as the one JDK class that really is thrown
    // when a ciphertext will not open.
    @Test
    fun `a locked phone is not evidence the key is gone`() {
        // **The failure this is here for.** The store's own key is created with
        // `setUnlockedDeviceRequired`, so on a locked phone decryption throws — and the agent polls
        // with the screen off, which is exactly when it happens. One `catch (e: Exception)` used to
        // treat that as "the key is dead", delete it, and answer "No OpenRouter key on this account
        // yet" for ever after. A person then sets the key again, and it works until the next poll
        // with the screen off.
        val locked = IllegalStateException(
            "UserNotAuthenticatedException: Key user not authenticated",
        )

        assertFalse("a locked phone is not a dead key", KeyVerdict.isDead(locked))
        assertTrue("and it is the locked case", KeyVerdict.isLocked(locked))
    }

    @Test
    fun `a key that cannot be opened is dead, however deeply the failure is wrapped`() {
        val wrapped = IllegalStateException(
            "could not decrypt",
            javax.crypto.AEADBadTagException("mac check in GCM failed"),
        )
        assertTrue("the bytes did not open", KeyVerdict.isDead(wrapped))

        assertTrue(
            "and an invalidated keystore entry is dead too",
            KeyVerdict.isDead(IllegalStateException("KeyPermanentlyInvalidatedException: gone")),
        )
    }

    @Test
    fun `a failure nobody recognises is not a reason to throw a key away`() {
        // The destructive answer has to be earned. A store that says "something went wrong" leaves
        // the bytes where they are: a person who types the key again overwrites them anyway, and a
        // person who does not has not lost anything.
        val odd = IllegalStateException("keystore busy")

        assertFalse("not dead", KeyVerdict.isDead(odd))
        assertFalse("not locked either", KeyVerdict.isLocked(odd))
    }

    @Test
    fun `a clone from an address this app cannot speak is refused before anything is opened`() {
        // **This test used to assert that a clone from another host refused in words**, and the rule
        // has moved rather than gone: *which key a remote gets* is [GitCredentials], asked by the
        // caller that knows the account, so a private repository on `gitlab.com` is now cloned with
        // that project's own secret instead of being refused. `GitCredentialsTest` and
        // `ProjectGitTest` pin the decision, and this one pins what is left in the transport itself.
        //
        // What is left is the address the app cannot speak at all: no SSH client is packaged, so the
        // scp form every host's page offers first fails inside JGit with an error that names nothing.
        // Refused here, in words, **before anything is opened**, because the request is the harm.
        val target = File(root, "elsewhere")

        val result = GitService.clone(
            "git@gitlab.com:someone/private.git",
            target,
            author,
            token,
        )

        assertFalse("it did not succeed", result.success)
        assertTrue("it names the host: ${result.message}", result.message.contains("gitlab.com"))
        assertTrue("and offers the form it can reach: ${result.message}", result.message.contains("HTTPS"))
        assertFalse("and the key is nowhere in it", result.message.contains(token))
        assertFalse("and nothing was made", target.exists())
    }

    @Test
    fun `a remote with no host is not refused, because it has nowhere to send the key`() {
        // **A local path is not a foreign host.** A bare repository beside the project — which is
        // how a folder-to-folder clone works, and how the clone test above works — has no host at
        // all. Refusing it would have broken the one case that is obviously safe, and the guard
        // exists to protect the key, not to ban paths.
        val remote = File(root, "remote2")
        val remoteRepo = GitService.initOrOpen(remote, author)
        File(remote, "note.txt").writeText("milk")
        GitService.commitAll(remoteRepo, "note", author)
        remoteRepo.close()

        val result = GitService.clone(remote.absolutePath, File(root, "clone2"), author, token)

        assertTrue("it worked: ${result.message}", result.success)
        assertTrue("the file arrived", File(root, "clone2/note.txt").isFile)
    }

    @Test
    fun `the host of a url is read the one way, whichever scheme it came in`() {
        // **One rule, so that a refusal and a guard cannot disagree.** `isGitHubHost` and the clone
        // refusal both ask `hostOf`, so a URL one reads as `github.com` cannot be read as something
        // else by the other.
        assertEquals("https", "github.com", Secrets.hostOf("https://github.com/o/r.git"))
        assertEquals("git", "github.com", Secrets.hostOf("git://github.com/o/r.git"))
        assertEquals("ssh", "github.com", Secrets.hostOf("ssh://git@github.com/o/r.git"))
        assertEquals(
            "credentials stripped",
            "github.com",
            Secrets.hostOf("https://user:tok@github.com/o/r"),
        )
        assertEquals("port stripped", "gitlab.com", Secrets.hostOf("https://gitlab.com:8443/o/r"))
        assertEquals("case folded", "github.com", Secrets.hostOf("https://GitHub.COM/o/r"))
        assertEquals("a local path has no host", "", Secrets.hostOf("/tmp/some/repo"))
        // **A `file:` URL has no authority either, and this is the case that broke a build.**
        // `file:///tmp/x` used to answer `file` — the first segment after the scheme — which reads as
        // a hostname and is not one. A test GitHub on disk clones from exactly this shape, so every
        // signing test failed on a URL that has nowhere to send a key.
        assertEquals("a file url has no host", "", Secrets.hostOf("file:///tmp/x"))
        assertEquals("nor does a file url with a path", "", Secrets.hostOf("file:///tmp/a/b.git"))
        // **A bare name with no scheme is read as an authority**, which is what git does with
        // `clone host:path` and with `clone repo/path`. It is not a *local* path — that has a leading
        // slash — so answering the name is the honest reading rather than a guess.
        assertEquals("a bare name is an authority", "repo", Secrets.hostOf("repo"))
        assertEquals("scp shape", "github.com", Secrets.hostOf("git@github.com:o/r.git"))
    }

    @Test
    fun `a host field refuses an address, and names the host it found`() {
        // **The field decides which server a key may be sent to**, so a pasted clone address has to be
        // refused rather than accepted-and-stripped: "gitlab.com:8443" silently stored as "gitlab.com"
        // is a field that changes what was typed, and the person would never know.
        val found = Secrets.hostProblem("https://gitlab.com/team/repo.git").orEmpty()
        assertTrue("it names the host: $found", found.contains("gitlab.com"))
        assertTrue("and where the address belongs: $found", found.contains("project"))
        assertNotNull("a port is not a hostname", Secrets.hostProblem("gitlab.com:8443"))
        assertNotNull("nor is a bare word", Secrets.hostProblem("gitlab"))
        assertNotNull("nor nothing", Secrets.hostProblem("  "))
        // **The positive half, and case is not a value.** A hostname is case-insensitive, so
        // `GitLab.com` is the same host and is folded rather than refused.
        assertNull(Secrets.hostProblem("gitlab.com"))
        assertNull(Secrets.hostProblem("GitLab.com"))
        assertNull(Secrets.hostProblem("github.example.com"))
        assertNull(Secrets.hostProblem("192.168.1.9"))
    }

    @Test
    fun `an address this app cannot speak to is refused before any key is considered`() {
        // **The trap this closes.** `hostOf("git@github.com:o/r.git")` answers `github.com` quite
        // happily, so a pasted scp address looked usable right up to the moment a key was attached to it
        // and JGit failed with a null-shaped error naming nothing — this app packages no SSH transport.
        // Every host's own page shows the scp form first, so this is the address a person will paste.
        for (address in listOf(
            "git@github.com:o/r.git",
            "git@gitlab.com:team/repo",
            "ssh://git@github.com/o/r.git",
            "git+ssh://git@example.org/o/r.git",
        )) {
            val why = Secrets.transportProblem(address)
            assertTrue("$address is refused", why != null && why.contains("HTTPS"))
            assertTrue(
                "and the sentence names the host it can reach: $why",
                why!!.contains(Secrets.hostOf(address)),
            )
        }
        // **The positive control, because a guard that refuses everything is not a guard.** Every shape
        // the app really sends is accepted, including a local path — which has no host and needs none.
        for (address in listOf(
            "https://github.com/o/r.git",
            "http://192.168.1.9:3000/team/r.git",
            "/tmp/bare-repo",
            "file:///tmp/bare-repo",
        )) {
            assertNull("$address is accepted", Secrets.transportProblem(address))
        }
    }


    // ---- a secret belongs to a host ----------------------------------------

    /**
     * **The hole this closes.** `http` lets the agent name a secret instead of typing it. Without a host
     * binding, naming is enough to send the token anywhere: the tool chooses the destination, and the vault
     * has no say. Asking for a secret therefore requires saying where it is going.
     */
    @Test
    fun `a secret is not handed to a host it was not stored for`() {
        val credentials = MemoryCredentials().apply { activeLogin = "someone" }
        credentials.setSecret("github", "ghp_the_real_token", "api.github.com")

        assertNull(
            "evil.example gets nothing",
            credentials.secret("github", "evil.example"),
        )
        assertEquals(
            "and the host it was stored for gets it",
            "ghp_the_real_token",
            credentials.secret("github", "api.github.com"),
        )
    }

    /**
     * **Whole labels, not a suffix.** `endsWith("github.com")` accepts `notgithub.com`, which is the kind of
     * check that passes review and fails an attacker. Requiring the dot makes the subdomain work and the
     * look-alike fail.
     */
    @Test
    fun `a look-alike host does not collect the key, and a subdomain does`() {
        val credentials = MemoryCredentials().apply { activeLogin = "someone" }
        credentials.setSecret("github", "ghp_the_real_token", "github.com")

        assertNull(
            "notgithub.com is a different host that merely ends in the same letters",
            credentials.secret("github", "notgithub.com"),
        )
        assertNull("so is evil-github.com", credentials.secret("github", "evil-github.com"))
        assertNull("and github.com.evil.example", credentials.secret("github", "github.com.evil.example"))
        assertEquals(
            "a real subdomain is the same service",
            "ghp_the_real_token",
            credentials.secret("github", "api.github.com"),
        )
        assertEquals("and the bare host", "ghp_the_real_token", credentials.secret("github", "github.com"))
    }

    /**
     * **A secret written before hosts existed is refused, not trusted.** It has no host, so it could go
     * anywhere — which is the thing the change exists to stop. The cost is re-entering it, and that is the
     * right trade against silently widening where a token may be sent.
     */
    @Test
    fun `a secret with no host is never sent, and is not confused with one that is`() {
        val credentials = MemoryCredentials().apply { activeLogin = "someone" }
        // A decryption key: read by this app, sent nowhere.
        credentials.setSecret("MUFFY_LOG_KEY", "the_log_key", "")
        // A token: bound to a host.
        credentials.setSecret("github", "ghp_token", "api.github.com")

        assertEquals("the key is answered for nowhere", "the_log_key", credentials.secret("MUFFY_LOG_KEY", ""))
        assertNull(
            "and never for a host, which is what stops a log key being posted to a server",
            credentials.secret("MUFFY_LOG_KEY", "api.github.com"),
        )
        assertNull("a token is not a local secret", credentials.secret("github", ""))
        assertNull("nor is the key a token", credentials.secret("MUFFY_LOG_KEY", "github.com"))
    }

    /** A name is discoverable; a value is not. An agent may see what exists, never what it is. */
    @Test
    fun `names are listed and values are not`() {
        val credentials = MemoryCredentials().apply { activeLogin = "someone" }
        credentials.setSecret("github", "ghp_one", "api.github.com")
        credentials.setSecret("linear", "lin_two", "mcp.linear.app")

        assertEquals(listOf("github", "linear"), credentials.secretNames())

        credentials.forgetSecret("github", SecretScope.Account)
        assertEquals("forgetting removes the name", listOf("linear"), credentials.secretNames())
        assertNull(
            "and the value with it",
            credentials.secret("github", "api.github.com"),
        )
    }

    /** The article names a secret; the server it points at decides whether that name resolves. */
    @Test
    fun `an mcp article cannot spend a secret on a server it was not stored for`() {
        val credentials = MemoryCredentials().apply { activeLogin = "someone" }
        credentials.setSecret("linear", "lin_api_value", "mcp.linear.app")

        val elsewhere = McpServer.parse(
            "# X\n\n```mcp\nurl: https://evil.example/mcp\nauth: bearer\ntoken: linear\n```",
            "x",
            "mcp/x.md",
        ).getOrThrow()

        assertNull("no header is built for a host the secret is not for", elsewhere.authorization(credentials))
        assertTrue(
            "and the refusal names the host: ${elsewhere.missing(credentials)}",
            elsewhere.missing(credentials)!!.contains("evil.example"),
        )
    }

    // ---- a secret belongs to a project, or to every project ----------------

    /**
     * **The feature, stated as the thing it must not do.** A secret stored for one project must not
     * be readable from another, because that is the whole reason the scope exists: project A's agent
     * should not be able to read project B's key.
     */
    @Test
    fun `a project's secret is not handed to another project`() {
        val credentials = MemoryCredentials().apply { activeLogin = "someone" }
        credentials.setSecret("acme", "acme-api", "tok_1", "api.acme.test")

        assertNull(
            "another project gets nothing",
            credentials.secret("other", "acme-api", "api.acme.test"),
        )
        assertEquals(
            "and the project it was stored for gets it",
            "tok_1",
            credentials.secret("acme", "acme-api", "api.acme.test"),
        )
    }

    /**
     * **Project first, then account.** A project that needs a different key for the same service
     * stores one and shadows the shared one — that is the point of the order, and a lookup that tried
     * the account first would make the project scope unusable for exactly the case it exists for.
     */
    @Test
    fun `a project's secret shadows the account one of the same name`() {
        val credentials = MemoryCredentials().apply { activeLogin = "someone" }
        credentials.setSecret("stripe", "tok_shared", "api.stripe.com")
        credentials.setSecret("acme", "stripe", "tok_acme", "api.stripe.com")

        assertEquals(
            "the project's own key wins",
            "tok_acme",
            credentials.secret("acme", "stripe", "api.stripe.com"),
        )
        assertEquals(
            "and another project still sees the shared one",
            "tok_shared",
            credentials.secret("other", "stripe", "api.stripe.com"),
        )
        assertEquals(
            "as does the account scope itself",
            "tok_shared",
            credentials.secret(null, "stripe", "api.stripe.com"),
        )
    }

    /**
     * **The host rule applies to a project secret too.** Two independent checks that must both pass:
     * a secret stored for `api.acme.test` must not travel to `evil.example` merely because it is
     * scoped to the right project.
     */
    @Test
    fun `a project's secret is still refused for a host it was not stored for`() {
        val credentials = MemoryCredentials().apply { activeLogin = "someone" }
        credentials.setSecret("acme", "acme-api", "tok_1", "api.acme.test")

        assertNull(
            "the project matches and the host does not",
            credentials.secret("acme", "acme-api", "evil.example"),
        )
    }

    /**
     * **Deleting a project takes its secrets with it**, and leaves the account's alone. A secret
     * whose project no longer exists is a row nothing can ever read.
     */
    @Test
    fun `forgetting a project's secrets removes exactly those, and counts them`() {
        val credentials = MemoryCredentials().apply { activeLogin = "someone" }
        credentials.setSecret("acme", "one", "a", "api.acme.test")
        credentials.setSecret("acme", "two", "b", "api.acme.test")
        credentials.setSecret("other", "three", "c", "api.acme.test")
        credentials.setSecret("shared", "d", "api.stripe.com")

        assertEquals("two went", 2, credentials.forgetProjectSecrets("acme"))
        assertNull("and they are gone", credentials.secret("acme", "one", "api.acme.test"))
        assertEquals(
            "another project's secret survives",
            "c",
            credentials.secret("other", "three", "api.acme.test"),
        )
        assertEquals(
            "and so does the account-wide one",
            "d",
            credentials.secret(null, "shared", "api.stripe.com"),
        )
    }

    /**
     * **A project secret is listed, and is NOT readable as an account secret.** Two separate claims,
     * and both matter:
     *
     * 1. **It is listed.** It used to be filtered out of `secretNames()`, which made it invisible in
     *    the window — stored, readable by the agent, and impossible to see or remove. A secret nobody
     *    can audit is worse than one that is listed under a slightly odd name.
     * 2. **It is not readable without its project.** `secret(null, "acme-api", …)` must miss, or a
     *    project's token would be handed to every project on the account — which is the whole thing
     *    the scope exists to prevent.
     */
    @Test
    fun `a project-scoped secret is listed but is not an account secret`() {
        val credentials = MemoryCredentials().apply { activeLogin = "someone" }
        credentials.setSecret("acme", "acme-api", "tok_1", "api.acme.test")

        assertTrue(
            "the window can see it: ${credentials.secretNames()}",
            credentials.secretNames().contains("acme-api"),
        )
        assertEquals("and the scope is answerable", "acme", credentials.secretProject("acme-api"))
        assertNull(
            "but the account scope cannot read it",
            credentials.secret(null, "acme-api", "api.acme.test"),
        )
        assertEquals(
            "and its own project can",
            "tok_1",
            credentials.secret("acme", "acme-api", "api.acme.test"),
        )
    }

    // ---- four scopes, narrowest first (§13c.4) ------------------------------

    /**
     * **A general secret is readable from every project**, which is the whole point of the scope: the
     * same service key for every project, whether it was made under one account or another.
     */
    @Test
    fun `a general secret is readable from any project and any account`() {
        val credentials = MemoryCredentials().apply { activeLogin = "first" }
        credentials.setGeneralSecret("shared", "the-key", "api.shared.test")

        assertEquals(
            "under the account that made it",
            "the-key",
            credentials.secret("acme", "shared", "api.shared.test"),
        )

        // **And under a different account**, which is what "general" means: the login is not in the key.
        credentials.activeLogin = "second"
        assertEquals(
            "under an account that never set it",
            "the-key",
            credentials.secret("other", "shared", "api.shared.test"),
        )
    }

    /**
     * **The narrowest scope wins**, and each step is checked: a project secret shadows the account's,
     * and the account's shadows the general one. Without this order a shared key could never be
     * overridden for one project, which is the case the scopes exist for.
     */
    @Test
    fun `project shadows account, and account shadows general`() {
        val credentials = MemoryCredentials().apply { activeLogin = "dev" }
        credentials.setGeneralSecret("key", "general", "api.test")
        assertEquals("general alone", "general", credentials.secret("acme", "key", "api.test"))

        credentials.setSecret("key", "account", "api.test")
        assertEquals("account shadows general", "account", credentials.secret("acme", "key", "api.test"))

        credentials.setSecret("acme", "key", "project", "api.test")
        assertEquals("project shadows account", "project", credentials.secret("acme", "key", "api.test"))
        assertEquals(
            "and another project still sees the account's",
            "account",
            credentials.secret("shop", "key", "api.test"),
        )
    }

    /**
     * **The host rule still applies to a general secret.** A general secret is not a secret that may go
     * anywhere — it is one every project may *ask* for, and the host is still the guard.
     */
    @Test
    fun `a general secret is still refused for a host it was not stored for`() {
        val credentials = MemoryCredentials().apply { activeLogin = "dev" }
        credentials.setGeneralSecret("key", "the-value", "api.shared.test")

        assertNull(
            "a different host is refused",
            credentials.secret("acme", "key", "evil.example"),
        )
    }

    /** And a general secret is listed, so the window can show and remove it. */
    @Test
    fun `a general secret is listed`() {
        val credentials = MemoryCredentials().apply { activeLogin = "dev" }
        credentials.setGeneralSecret("shared", "v", "api.test")

        assertTrue("listed: ${credentials.secretNames()}", "shared" in credentials.secretNames())
        assertTrue(
            "and known to be general: ${credentials.generalSecretNames()}",
            "shared" in credentials.generalSecretNames(),
        )
    }

}
