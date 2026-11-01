package com.example.notes

/**
 * The standard workflows, as text, **kept in the system project where a person can read and change
 * them.**
 *
 * ## What these are, and are not
 *
 * They are **presets**. A project gets a copy in its own `.github/workflows/`, and the copy is what
 * runs — because GitHub only dispatches a workflow that is in the repository, so a file in the system
 * project cannot be dispatched at all. The preset is the source; the copy is the workflow.
 *
 * **They live at `system/muffycodes_data/starters/<kind>/workflows/`**, one folder per kind, beside that kind's
 * `project_prompt.txt`. The folder is the mapping: to know what an Android project starts with, look in
 * `starters/android/` rather than in a table in code.
 *
 * A copy is the project's own file, so a project that needs an extra step can have one without asking
 * the template to change for everybody.
 *
 * ## Why these live in the app's source as well as on the phone
 *
 * The text has to come from somewhere on first run, and a file in `assets/` would be a second copy of
 * the same thing with no way to keep the two honest. Here there is one copy: this object, written into
 * the system project once, and never overwritten afterwards — so a person's edit survives every
 * refresh, and the next app version can offer a newer template without having overwritten the old one.
 */
object WorkflowTemplates {

    /** The template for an Android app: build, test, sign, publish an APK. */
    const val ANDROID = "android.yml"

    /** The template for a web app: build, then publish to GitHub Pages. */
    const val WEB = "web.yml"

    /**
     * The template for anything else — a script, a CLI, a desktop build.
     *
     * **No signing and no release.** A program that is not an Android app has nothing to sign with the
     * key this app can keep, so offering one would be offering a step that cannot work.
     */
    const val PLAIN = "plain.yml"

    /** Every template, by the name it has on the phone. */
    val ALL: Map<String, String> = mapOf(
        ANDROID to android(),
        WEB to web(),
        PLAIN to plain(),
    )

    /**
     * What the agent is told when it works in a project of this type — **the hard-won part.**
     *
     * ## Why this exists
     *
     * The system prompt says how this app works. The project prompt says what this project is for.
     * **Neither says how to build a program**, and that is the knowledge that took this repository
     * months: tests that wait for the right fact, traces that record what happened rather than what
     * was intended, workflows that fail a build rather than warn, bug reports that a person can act on.
     *
     * A new project starts with none of it, and an agent that has to rediscover it per project spends
     * its first hours learning what is already known here. So the presets carry it.
     *
     * ## Why they are short
     *
     * They are read at the start of **every turn**, and a provider's cache matches on the longest
     * common prefix — so a thousand-line briefing would be paid for on every request and read by
     * nobody. These are the rules that changed an outcome, not a style guide.
     */
    fun promptFor(type: AppType): String? = when (type) {
        AppType.ANDROID -> androidPrompt()
        AppType.WEB -> webPrompt()
        AppType.PLAIN -> plainPrompt()
        AppType.CUSTOM -> null
        AppType.LATER -> laterPrompt()
    }

    /**
     * The template for an [AppType].
     *
     * **Null for the two types with no preset.** [AppType.CUSTOM] is a person saying they will write it
     * themselves, and [AppType.LATER] is the question not yet answered — for both, a workflow written
     * now would be a guess, and a wrong workflow is worse than none because it fails on every push and
     * looks like the project is broken.
     */
    fun forType(type: AppType): String? = when (type) {
        AppType.ANDROID -> ANDROID
        AppType.WEB -> WEB
        AppType.PLAIN -> PLAIN
        AppType.CUSTOM, AppType.LATER -> null
    }

    /**
     * The shared half of every prompt: **how this project is worked in, whatever it builds.**
     *
     * These are the rules that changed an outcome here. Each one cost a session to learn, and each is
     * stated as the thing to do rather than as the story of what went wrong — a brief is read every
     * turn, and the story is in the repository's own docs for whoever wants it.
     */
    private fun common(): String = """
        ## How work is done here

        This file is yours. It is one of the three things in your context — the system prompt, this,
        and the log — and it is the only one you can change. The system prompt is locked and says
        only how the tools behave; everything about *how to work* belongs here, and a project that
        works differently should change it rather than work around it.

        **Read before you write.** Guess only when reading is impossible, and then say that you are
        guessing.

        **Change as little as the task needs.** A rename is not a rewrite.

        **Test what you changed, and make the test say what broke.** A test's failure message is the
        only thing anyone reads at 2am, so it names the fact that was wrong rather than the line that
        asserted it. "waited 10s for the folder to be dropped: folder=true" sends a reader straight to
        the cause; "expected true but was false" sends them to the test.

        **Wait for the exact fact the code reads.** A test that waits for a neighbouring fact — the
        file on disk when the function reads the history — passes or fails by timing. That is a race
        wearing a test's clothes.

        **A test can encode a bug as an expectation.** Before fixing a failure, ask whether the test
        was asserting the behaviour you want or the behaviour you have. One here asserted that a file
        contained text, and passed only because the app wrote that file on every read.

        **Write down what happened, not what you meant to do.** A trace line is for the person reading
        it when something is wrong, and it is the only place that can say which of several paths ran.
        Name the thing that happened: "agent-gone", not "handling".

        **A default that can be overridden is not a boundary.** If something must not happen, refuse it
        — and refuse it *loudly*, naming what was asked and what is allowed. A value silently replaced
        is a value the caller believes it set.

        **Silence is a real answer.** A check that finds nothing wrong should say nothing. A warning
        nobody can act on trains people to ignore the ones they can.

        **Say why, in the code, where the next reader is.** Not a changelog — the reason the obvious
        alternative is wrong. The obvious alternative is what the next person will try.

        ## When something is refused

        A refusal names the thing that was wrong, the thing that is allowed, and how to get from one to
        the other. "There is no workflow at that path. The workflows it has are: a.yml, b.yml." A model
        that guessed the wrong name learns the right one; a model told only "no" guesses again.
    """.trimIndent()

    private fun androidPrompt(): String = """
        # This is an Android app

        ${'$'}common

        ## Building and testing

        **Gradle, and never a build you cannot see the result of.** `./gradlew` fails before it compiles
        anything when the SDK is missing, and a pipe swallows that: `./gradlew ... | grep -E "^e: "`
        prints nothing and `echo OK` then reports success on a build that never ran. **Echo the exit
        code**, and never let a pipe be the last thing that decides what happened.

        **Unit tests are the fast loop.** Robolectric gives you a real Android environment without a
        device; a feature test extends the project's shared base class rather than building its own
        setup, because a test that duplicates the scaffolding drifts from it and then fails for reasons
        that have nothing to do with what it tests.

        **Screenshots are evidence.** If the project can render a screen to an image, do that rather
        than describing the screen in prose — a layout bug is visible and a paragraph about it is not.

        ## Signing and release

        **The signing key is never in the repository.** It lives in a GitHub *environment* called
        `release`, and only a job that names that environment can read it. A repository secret is
        readable by every workflow in the repository, including one you write, so one at that scope
        makes the environment rule decorative.

        **The environment is restricted to the branches that build**, so a workflow on any other branch
        cannot use it even if it names it. That is what makes an approval step unnecessary.

        **A release that is not signed cannot be installed over an existing one.** If a build suddenly
        produces an APK that will not install, the certificate changed — not the phone.

        **Never auto-install or auto-open an artifact.** Opening the system installer is the app's
        business; installing without a press is not.
    """.trimIndent()

    private fun webPrompt(): String = """
        # This is a web app

        ${'$'}common

        ## Building and testing

        **`npm ci --frozen-lockfile`, not `npm install`.** A build that quietly changes its own
        dependencies is a build whose result nobody can reproduce. A lockfile that is out of date is a
        decision for a person.

        **Test the behaviour, not the framework.** A test that asserts a component rendered is a test
        that the framework works; a test that asserts what a person sees when they do a thing is a test
        of this project.

        **Nothing here is signed, and that is correct.** What makes a web app trusted is the domain it
        is served from, not a certificate in the repository. Do not add signing steps to a web project.

        ## Publishing

        **Only `main` publishes.** A pull request builds so somebody can see whether it would work;
        publishing from a branch is how a work in progress becomes the thing people are looking at.
    """.trimIndent()

    /**
     * The prompt for a project whose type **nobody has chosen yet.**
     *
     * ## Why this is a prompt and not an empty file
     *
     * "Decide later" is an answer, and an empty brief would make the agent guess — which is the one
     * thing this type exists to prevent. So instead of pretending to know what the project is, the
     * brief **knows that it does not know**, says what the choices are, and tells the agent how to
     * help the person pick.
     *
     * ## Why it is copied once and not refreshed
     *
     * The same rule as every other preset: a file in a project belongs to that project. An app that
     * rewrote it on every refresh would be an app that can undo an edit — and a person who wants the
     * newest version can copy it from the system project themselves.
     */
    private fun laterPrompt(): String = """
        # What is this project?

        Nobody has said yet, and the app is not going to guess. This brief exists so that you do not
        guess either: find out what the project is before you decide how to build it, and ask the
        person when the folder does not tell you.

        ${'$'}common

        ## What the project can be

        The person picks one of these, in the app, and it changes what you are told here and what
        workflow the project starts with:

        **An Android app.** Built with Gradle, tested with Robolectric, and released as an APK that
        must be signed. The signing key lives in a GitHub environment called `release`, never in the
        repository.

        **A web app.** Built with a lockfile and published — usually to GitHub Pages. Nothing is
        signed; what makes it trusted is the domain.

        **A program or a script.** One build step, whatever the project's own tooling is. Nothing is
        signed and there is no release.

        **Custom.** The person writes the instructions and the workflow themselves, and you follow
        them.

        ## How to help them choose

        **Read the project first.** A `build.gradle.kts` with `com.android.application` is an Android
        app; a `package.json` with a `build` script is probably a web app; a `Makefile` or a
        `pyproject.toml` with no app manifest is a program. Say what you found and what it suggests.

        **Say what changes, not what the type is called.** A person choosing between "Android" and
        "program" needs to know that one gets a release job and a signing key and the other does not.

        **Do not ask before you look**, and do not look for long. Two or three files tell you most of
        it, and a question the folder already answered is a question that costs the person a turn.

        **If it is genuinely ambiguous, say so and offer the two candidates.** "There is a Gradle build
        and a web folder — is this the app, or the site the app is served with?" is answerable. "What
        kind of project is this?" is not.

        **Then tell them where to answer.** The app shows a bar inside the project while this is
        undecided, and it is one tap. You do not need to be the one who changes it.
    """.trimIndent()

    private fun plainPrompt(): String = """
        # This is a program or a script

        ${'$'}common

        ## Building and testing

        **One step, and it is the one to change.** The template guesses from what it finds — a
        `package.json`, a `Makefile`, a `Cargo.toml`, a `pyproject.toml` — and a project that is none of
        those needs its build command written. That is expected, not a failure of the template.

        **Nothing here is signed.** A program is trusted because of where a person got it, not because
        of a certificate in the repository. Do not add signing steps.

        **A script that fails must fail loudly.** An exit code of zero after an error is how a broken
        build reports success, and it is the most expensive kind of wrong because it is invisible.
    """.trimIndent()

    private fun android(): String = """
        # Build, test and release an Android app.
        #
        # Copied from the system project's workflows folder. **This copy is yours**: a step you add
        # here is a step this project runs, and it does not change the template or any other project.
        #
        # What it does, by branch:
        #
        #   a pull request  the tests, and nothing else — the tests are what answer whether a change
        #                   is good, and that answer is the only one needed before a merge
        #   development     the tests and a release build, kept as an artifact you can install
        #   main            everything, including publishing a release
        #
        # The signing key is read from the `release` environment, which the app sets up. It is not a
        # repository secret on purpose: a repository secret is readable by every workflow in the
        # repository, including one the agent writes.
        name: Build and release

        on:
          push:
            branches: [main, development]
          pull_request:
            branches: [main]
          workflow_dispatch:
            inputs:
              version_name:
                description: "The version of the release (for example 1.4)"
                required: true
                type: string

        concurrency:
          group: ${'$'}{{ github.workflow }}-${'$'}{{ github.ref }}
          cancel-in-progress: true

        jobs:
          test:
            name: Test
            runs-on: ubuntu-24.04
            timeout-minutes: 20
            permissions:
              contents: read
            steps:
              - uses: actions/checkout@v4
                with:
                  persist-credentials: false
              - uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: '21'
              - name: Test
                run: ./gradlew --no-daemon testDebugUnitTest

          build-release:
            name: Build the release
            # **The environment is what makes the signing key reachable**, and only a job that names
            # it can read the key. The environment is restricted to `main` and `development`, so a
            # workflow on any other branch cannot use it even if it names it.
            environment: release
            if: github.event_name != 'pull_request'
            needs: test
            runs-on: ubuntu-24.04
            timeout-minutes: 25
            permissions:
              contents: read
            steps:
              - uses: actions/checkout@v4
                with:
                  persist-credentials: false
              - uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: '21'
              - name: Build the release
                env:
                  MUFFY_KEYSTORE_BASE64: ${'$'}{{ secrets.MUFFY_KEYSTORE_BASE64 }}
                  MUFFY_KEYSTORE_PASSWORD: ${'$'}{{ secrets.MUFFY_KEYSTORE_PASSWORD }}
                  MUFFY_KEY_ALIAS: ${'$'}{{ secrets.MUFFY_KEY_ALIAS }}
                  MUFFY_KEY_PASSWORD: ${'$'}{{ secrets.MUFFY_KEY_PASSWORD }}
                  VERSION_NAME: ${'$'}{{ inputs.version_name }}
                run: ./gradlew --no-daemon assembleRelease
              - uses: actions/upload-artifact@v4
                with:
                  name: app-release
                  path: app/build/outputs/apk/release/app-release.apk

          deliver:
            name: Deliver
            needs: [test, build-release]
            if: |
              !cancelled()
              && github.event_name != 'pull_request'
              && needs.build-release.result == 'success'
            runs-on: ubuntu-24.04
            timeout-minutes: 10
            permissions:
              contents: write
            steps:
              - uses: actions/download-artifact@v4
                with:
                  name: app-release
                  path: apk
              - name: Publish the release
                # **Only main publishes.** A development push keeps its APK as an artifact, which is
                # something to install and look at; it is not a release.
                if: github.ref == 'refs/heads/main'
                env:
                  GH_TOKEN: ${'$'}{{ secrets.GITHUB_TOKEN }}
                  VERSION_NAME: ${'$'}{{ inputs.version_name }}
                run: |
                  if [ -z "${'$'}{VERSION_NAME:-}" ]; then
                    highest="${'$'}(gh release list --limit 100 --json tagName \
                      --jq '[.[] | .tagName | sub("^v";"") | select(test("^[0-9]+${'$'}")) | tonumber] | max // 0')"
                    VERSION_NAME="${'$'}(( ${'$'}{highest:-0} + 1 ))"
                  fi
                  gh release create "v${'$'}{VERSION_NAME}" apk/app-release.apk \
                    --title "v${'$'}{VERSION_NAME}" --generate-notes
    """.trimIndent()

    private fun web(): String = """
        # Build a web app and publish it to GitHub Pages.
        #
        # Copied from the system project's workflows folder. **This copy is yours.**
        #
        # There is no signing key here, because a web app has nothing to sign: what makes it
        # installable is the browser, and what makes it trustworthy is the domain.
        name: Build and publish

        on:
          push:
            branches: [main]
          pull_request:
            branches: [main]
          workflow_dispatch:

        permissions:
          contents: read
          pages: write
          id-token: write

        concurrency:
          group: pages
          cancel-in-progress: false

        jobs:
          build:
            name: Build
            runs-on: ubuntu-24.04
            timeout-minutes: 15
            steps:
              - uses: actions/checkout@v4
                with:
                  persist-credentials: false
              - uses: actions/setup-node@v4
                with:
                  node-version: '22'
              # **`--frozen-lockfile`, so a build cannot quietly change its own dependencies.** A
              # lockfile that is out of date is a decision somebody has to make, not something a build
              # does on its way past.
              - name: Install
                run: npm ci --frozen-lockfile
              - name: Test
                run: npm test
              - name: Build
                run: npm run build
              - uses: actions/upload-pages-artifact@v3
                with:
                  path: dist

          publish:
            name: Publish
            needs: build
            # **Only main publishes.** A pull request builds so a person can see whether it would.
            if: github.ref == 'refs/heads/main'
            runs-on: ubuntu-24.04
            timeout-minutes: 10
            environment:
              name: github-pages
              url: ${'$'}{{ steps.deployment.outputs.page_url }}
            steps:
              - id: deployment
                uses: actions/deploy-pages@v4
    """.trimIndent()

    private fun plain(): String = """
        # Build and test a program that is not an Android app and not a web page.
        #
        # Copied from the system project's workflows folder. **This copy is yours**: change the build
        # and test commands below and the rest keeps working.
        #
        # There is no signing step, because there is nothing here for a signing key to sign. A program
        # a person runs is trusted because of where they got it, not because of a certificate.
        name: Build and test

        on:
          push:
            branches: [main, development]
          pull_request:
            branches: [main]
          workflow_dispatch:

        concurrency:
          group: ${'$'}{{ github.workflow }}-${'$'}{{ github.ref }}
          cancel-in-progress: true

        jobs:
          build:
            name: Build
            runs-on: ubuntu-24.04
            timeout-minutes: 15
            permissions:
              contents: read
            steps:
              - uses: actions/checkout@v4
                with:
                  persist-credentials: false
              # **One step, and it is the one to change.** A project with a package.json, a Makefile or
              # a cargo.toml all want something different here, and the rest of the file is the same.
              - name: Build and test
                run: |
                  if [ -f package.json ]; then
                    npm ci --frozen-lockfile
                    npm test --if-present
                    npm run build --if-present
                  elif [ -f Makefile ]; then
                    make
                    make test
                  elif [ -f Cargo.toml ]; then
                    cargo build --release
                    cargo test
                  elif [ -f pyproject.toml ] || [ -f requirements.txt ]; then
                    python3 -m pip install -r requirements.txt 2>/dev/null || true
                    python3 -m pytest -q
                  else
                    echo "Nothing here knows how to build itself yet."
                    echo "Change this step in .github/workflows/plain.yml to say how."
                    exit 1
                  fi
    """.trimIndent()
}
