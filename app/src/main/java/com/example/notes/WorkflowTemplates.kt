package com.example.notes

/**
 * The standard workflows, as text, **kept in the system project where a person can read and change
 * them.**
 *
 * ## What these are, and are not
 *
 * They are **templates**. A project gets a copy in its own `.github/workflows/`, and the copy is what
 * runs — because GitHub only dispatches a workflow that is in the repository, so a file in
 * `system/workflows/` cannot be dispatched at all. The template is the source; the copy is the
 * workflow.
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
     * The template for an [AppType].
     *
     * **Null for [AppType.NONE]**, which is a folder the person keeps notes in. It has no build, so it
     * gets no workflow, and a template for it would be a file that fails on every push.
     */
    fun forType(type: AppType): String? = when (type) {
        AppType.ANDROID -> ANDROID
        AppType.WEB -> WEB
        AppType.PLAIN -> PLAIN
        AppType.NONE -> null
    }

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
