# OAuth and PKCE, read against the code

A critique of `docs/oauth-proposal`, with a counterproposal and the questions that have to be answered
before any of it is built.

**Verdict in one paragraph.** The *goal* is right and the diagnosis is right — a PAT is the worst part of
first use, and `signIn(login, token, …)` makes the person do the app's paperwork. But the proposal as
written cannot be built as one piece, because **the two providers are not the same problem** and the
proposal treats them as if they were. OpenRouter's PKCE is a clean fit and is genuinely one click. GitHub's
PKCE, which the proposal names first, **still requires a `client_secret` at the token exchange** — so it
does not remove the secret, it only adds protection to the code. The flow that *does* remove the secret on
GitHub is the device flow, which is not PKCE and which the proposal does not mention. And the third request
— rendering sign-up pages "right inside the app, in its own screens" — is the one thing in the proposal
that should not be built, for reasons that are about GitHub's rules and about phishing, not about effort.

---

## 1. What the proposal gets right

Worth saying plainly, because the rest of this document is disagreement:

- **The PAT is the real problem.** `AppViewModel.signIn` (line 3996) takes `login`, `token`,
  `commitName`, `commitEmail`. A person must know their GitHub login, create a token in a browser, choose
  scopes they cannot evaluate, copy a `ghp_…` string, and type four fields. Every one of those is a place
  to give up.
- **`login` is derivable, and that is the best single win.** `GitHub.identity(token)` already exists and
  returns an `Identity`. An OAuth flow — any flow — hands back a token that answers that call, so the
  screen can stop asking for the login at all.
- **The credential store already supports what this needs.** `CredentialStore` encrypts per-login with a
  keystore key and already distinguishes `isDead` from `isLocked`. Nothing about OAuth requires touching
  it, and a refresh token would go in the same place as the token does today.
- **First-run does need reordering.** `openFirstRunReadme()` (`AppViewModel.kt:506`) currently opens the
  README before anything else. Putting a connection step in front of it is a small, contained change.
- **The MCP code already says the honest thing** (`Mcp.kt:72`): *"OAuth needs a browser round trip the app
  does not do yet."* This proposal is the work that sentence was waiting for.

---

## 1a. Decisions taken since (2026-11-02, from the operator)

Recorded here so the rest of this document is read in their light. These supersede the open questions they
answer.

**D1. GitHub and OpenRouter are activated together, and both are mandatory.** The app does not work with
one and not the other: GitHub is identity and storage, OpenRouter is inference. So onboarding is a single
path — capabilities screens, then GitHub, then OpenRouter, then the app. There is no "either", and no
working state without both.

**D2. Sign-in is mandatory; the app has an account on arrival.** The user-facing provisional state goes
away, and the last attached account being removed returns the person to the sign-in screen rather than to a
local workspace.

> **What this does *not* mean, and the distinction is load-bearing.** The *machinery* stays. `AccountStore`
> states that the test account is *"a test account of a debug build… a release build can never make one,
> and no account stored by a release build ever has this set"* — so `~local` already cannot exist for a
> real user. What is removed is the **user-facing** provisional state. The dev account itself is what makes
> every one of the 76 test classes hermetic: `Scenario.init` calls `signedIn()` for every scenario, and the
> dev account talks to *"a GitHub that is a folder on this phone"*, so no test touches the network. Deleting
> it means every test needs an injected fake GitHub — a large refactor, not a deletion. There are 86
> references to `provisional` and 41 of them are in tests.
>
> **Confirmed by the operator, 2026-11-02:** the machinery stays. What goes is the user-facing state.

**D3. Tokens must be durable; no refresh machinery is wanted.** Verified: **OAuth App tokens do not expire
by default.** The eight-hour lifetime and the refresh token belong to **GitHub Apps**. So the ordinary OAuth
App token is as durable as the PAT it replaces, and "the agent runs for days in the background" needs no new
work. What kills a token is revocation, not time — and all three causes (the person revoking, GitHub
revoking, the registration being deleted) arrive as a **401**, which is one path: say so, offer to reconnect.

**D4. `.html` preview is deferred** until the auth block is finished. Cut from this change, as §2.4 argued.

**D5. The plan for now: device flow, with the PAT as the primary fallback.** No backend, no facade pool, no
Actions. The PAT is not merely a rate-limit remedy — it is the **standing second door**, always available,
because it is the only path that cannot be refused by a limit, a registration mistake, or a provider outage.
A backend is a **growth story for later**, not a plan: it becomes worth building when the ceiling binds, and
that day will be visible.
This closes every alternative explored below — each was checked and each failed on its own merits, not on
taste: routing across Apps multiplies a pool the app does not use, a facade pool buys throughput by handing
users N unfamiliar identities, and Actions is prohibited by the terms for exactly this use. What is left is
the flow that is free, secretless, and needs nothing running anywhere.

### When it fails, and what the app says

The operator's decision, 2026-11-02: on a rate limit, tell the person, offer a PAT, and let them wait.

This is the right shape, and it makes the PAT a **permanent part of the design** rather than a leftover to be
retired. It is also the only honest fallback: the app cannot raise the limit, cannot see it, and cannot make
it reset sooner.

**What the app may and may not claim.** Two facts from the docs decide the wording:

- *"There is not a way to check the status of your secondary rate limit."* So the app **cannot say when the
  limit resets** — a countdown would be invented. The only defensible message is "wait and try again",
  with a retry button and no timer.
- The device flow's error codes are a closed list: `authorization_pending`, `slow_down`, `expired_token`,
  `unsupported_grant_type`, `incorrect_client_credentials`, `incorrect_device_code`, `access_denied`,
  `device_flow_disabled`. **There is no code for "too many submissions."** So a rate-limit refusal may
  arrive as an undocumented secondary-limit error, or as a plain HTTP 403/429 with a `retry-after` header,
  and the app has to recognise it by status and headers rather than by a name.

So the failure screen offers three things and promises nothing it cannot deliver:

1. **Use a personal access token instead** — the escape hatch, always present, never hidden behind a
   disclosure for this case specifically.
2. **Try again** — with the person in control of when, because the app does not know when it would work.
3. **What is happening, in plain words** — "GitHub is limiting how many new sign-ins the app may make right
   now. This is temporary and is not a problem with your account."

**The asymmetry worth stating:** a person who has signed in before is **unaffected**. Tokens do not expire,
so this limit only ever touches *new* sign-ins. The person hitting this screen is, by definition, someone who
has not connected yet — which is the worst possible time to fail and the reason the PAT must be offered
without friction rather than as an advanced option.

### The rate-limit question, answered

The device flow's limit is **50 verification-code submissions per hour, per application**. A submission is a
*new authorization*, not an API call and not a use — so because tokens do not expire, **a person authorizes
once and never again**, and the ceiling applies to new users only. 50/hour is 1,200/day.

What actually alleviates it, in order of what it buys:

1. **Do not burn slots on re-authorization.** A 401 is not proof the token is dead — it can be a scope
   problem — so test the token's capability before asking anyone to authorize again. Never offer sign-out
   as a casual action; it costs a fresh authorization. Cache the identity so a returning person is never
   re-prompted. This is free and it is the largest lever.
2. **The web flow has no app-wide ceiling.** Its limit is 10 tokens per hour *per user*, revoking the
   oldest — so a launch is unconstrained. But its exchange requires `client_secret`, which is the thing
   being avoided. The real trade is **"no secret, app-wide ceiling"** against **"a secret, no ceiling"**.
3. **A small backend would dissolve the ceiling** — it holds the secret and does the exchange. Rejected for
   now: a server, a domain, an uptime promise and a new way for sign-in to fail, all in exchange for a limit
   that is probably never reached. Reconsider only if it is actually hit — and when that day comes it is a
   **real** server: GitHub Actions is explicitly not an option for this, for the reasons in
   §"GitHub Actions cannot be the backend".
4. **Sharding across several OAuth Apps** multiplies the ceiling and fragments the app's identity in every
   user's authorized-applications list. Noted so it is not reinvented as a clever idea. Do not.

#### Why routing across Apps cannot solve this

A proposal worth recording because it is plausible, was seriously considered, and is wrong for a reason
that is easy to miss. Register 100 GitHub Apps (the per-account cap is real: *"A user, organization, or
enterprise can register up to 100 GitHub Apps"*), rotate requests between them, and multiply the budget.
Community libraries do exactly this — `octokit-load-balancer` exists to spread requests across several Apps.

**It multiplies the wrong pool.** The rule underneath is that **the rate limit follows the actor, not the
app**: *is GitHub acting as the app itself, or as a person?* Three token types answer that question, and only
the first is the app's own budget:

| token | prefix | acts as | whose pool |
|---|---|---|---|
| GitHub App **installation** token | `ghs_` | the **app** | the installation — 5,000/h each, scaling |
| GitHub App **user** access token | `ghu_` | the **person** | the person |
| **OAuth App** token | `gho_` | the **person** | the person |
| personal access token | `ghp_` / `github_pat_` | the person | the person |

The name is the trap: "user access token" does not mean *a token belonging to the user*, it means **a token
where the app acts as the user**. A load balancer works on the installation row — N Apps installed on one org
give N installation tokens with N budgets. This app is on the other three rows.

The docs are explicit that those share: *"Primary rate limits for OAuth access tokens generated by an OAuth
App are dictated by the primary rate limits for authenticated users. This rate limit is combined with any
requests that another GitHub App or OAuth App makes on that user's behalf and any requests that the user
makes with a personal access token."* **The pool belongs to the person, not to the App.** Registering 100 Apps
and rotating them yields one 5,000/h budget, because all 100 are spending the same person's quota.

**And the reason is obvious once stated:** if each App had its own user-token budget, a person authorizing
100 Apps would get 100 × 5,000/h of their own capacity. The limit exists to bound what a person can consume,
so it has to be the person's. Installation tokens escape it because there the app is doing its *own* work,
not standing in for somebody.

There is also a practical wall on the installation path: those tokens are minted from a JWT signed with the
App's **private key**, so using them from a phone means shipping that private key in the APK — the same
problem as a `client_secret`, and worse.

**And "several accounts" changes the product rather than the limit.** Each account would need its own
authorization, so a person signing in through App #37 would see *that* name in their authorized-applications
list, with that App's permissions and that App's identity in the audit log. At scale the user-visible effect
is a rotating cast of unfamiliar app names asking for access — which is precisely the shape of an attack, and
the reason the `unverified` / third-party-app warnings exist. It also concentrates a single point of failure:
one registration suspended and a fraction of your users cannot sign in.

**The honest conclusion:** routing is not a mitigation for the API limit, and it would be a suspicious-looking
one to ship.

#### But the device-flow limit *is* app-keyed, and a facade pool would work

Worth separating, because the two limits behave differently under sharding and only one of them is a real
option:

| limit | keyed on | does sharding multiply it? |
|---|---|---|
| 5,000/h primary API (user token) | the **person** | **no** — one person's pool |
| 2,000/h OAuth token requests | the **app** | yes, in principle |
| 50/h device-flow submissions | the **application** | **yes** — "50 submissions in an hour **per application**" |

So a pool of N OAuth App registrations would genuinely yield N × 50 submissions/hour, and N × 2,000 polls if
the secondary limit is app-scoped too. The engineering is trivial — the app already has a `GitHub` interface,
so a pool is a list of client ids and a rotation.

**It should still not be built.** What device flow makes visible is worse than "unfamiliar names": the person
enters the code at `github.com/login/device` and GitHub answers *"«App #37» is requesting access to your
account"* — with that registration's name, icon and permissions. `github.com/settings/applications` then
lists **N separate entries**, so revoking access means finding and revoking each one. That is a privacy
problem rather than a cosmetic one. Add that one owner must maintain N registrations, that each is an
independent point of failure, that the 100-app cap forces multiple GitHub accounts beyond that, and that
GitHub's secondary limits exist *"to prevent abuse"* and are *"subject to change without notice"* — the pool
is hard to defend and is not even a guaranteed escape.

**A backend on the web flow dominates it on every axis** — *if* you are willing to run one. Note the "if":
GitHub Actions is **not** an available substitute, and §"GitHub Actions cannot be the backend" below explains
why. For the same operational burden of running one thing:

| | facade pool | backend + web flow |
|---|---|---|
| device-flow 50/h | N × 50 | **not used at all** |
| 2,000/h token requests | N × 2,000 polls | 1 request per sign-in |
| identities the user sees | N | **1** |
| registrations to maintain | N | 1 |
| looks like circumvention | yes | no |

The web flow's own limit is 10 tokens per hour *per user*, revoking the oldest, which is irrelevant at this
scale.

#### GitHub Actions cannot be the backend

It is tempting, because the repository already runs Actions with a signing key and it would cost nothing.
Three reasons it does not work, and the last is the one that matters.

**1. It is not a server.** A job on a GitHub-hosted runner is capped at **6 hours** of execution and has
**no** time limit only on self-hosted runners (5 days per job). A workflow *run* is capped at 35 days. So
there is no "run it indefinitely" — there is a chain of restarts, each with a queueing gap, which is a server
you are pretending not to run.

**2. Scheduled workflows stop on their own.** GitHub disables scheduled workflows in a public repository after
**60 days without repository activity**, and the workflow's own runs do not count as activity. A service that
switches itself off after two quiet months is not a service.

**3. The terms forbid it, in as many words.** GitHub's Terms for Additional Products and Features, under
*Actions*:

> *"regardless of whether an Action is using self-hosted runners, Actions should not be used for: … The
> provision of a stand-alone or integrated application or service offering the Actions product or service, or
> any elements of the Actions product or service, for commercial purposes; … Any activity that places a
> burden on our servers, where that burden is disproportionate to the benefits provided to users (for
> example, **don't use Actions as a content delivery network or as part of a serverless application**…)"*

An OAuth token-exchange endpoint is a serverless application. It is also an element of the product being
offered, used for the app's own commercial purposes. That is three of the prohibited categories at once, and
the remedy for it is account suspension — which for this app would mean **the release pipeline stops**, not
merely the auth endpoint.

**The honest position:** if a backend is ever needed it is a real one, on infrastructure you control and pay
for. That is a much larger commitment than the ceiling currently justifies, which is precisely why the answer
today is "one app, poll politely, and measure."

**So the ceiling is a signal, not a problem to engineer around.** 50/hour is 1,200 new users a day, and
because tokens do not expire, returning users cost nothing. Build one app, poll politely, and if the ceiling
ever binds, that is the evidence that a backend is justified — and the backend is better than the pool
anyway.

#### Two different budgets, and they are easy to confuse

Worth stating because it was confused once already. **The device-flow limit is not an API rate limit**, and
the two measure different things:

| | what it counts | the number | what happens when exceeded |
|---|---|---|---|
| REST API limits | API **calls** the app makes | 5,000/h per installation token, +50 per repo or user past 20, capped ~12,500 (15,000 on GHEC); a user access token follows the user's 5,000/h | 403/429 — the app is slow |
| Device-flow limit | people **completing authorization** | 50/h per application | authorization refused — new users cannot get in |

The first is about how much work the app does; the second is about how many people can join. For this app
the first is almost certainly irrelevant — a session makes dozens of calls, not thousands — while the second
lands squarely on a launch, because its failure mode is "nobody can sign up for an hour".

The exact wording, from the section headed *"Rate limits for the device flow"* in the OAuth App docs: *"When
a user submits the verification code on the browser, there is a rate limit of 50 submissions in an hour per
application."* The **verification code** is the `user_code` the person types at `github.com/login/device` —
GitHub's own term for it, and nothing to do with Marketplace publisher verification, which is a manual
review with no rate limit at all.

#### The 2,000/hour limit, and why it is the sharper one

**The claim is true.** Verified verbatim in the REST API docs, under secondary rate limits:

> *"Make too many OAuth access token requests in a short period of time. No more than **2,000 OAuth access
> token requests per hour** are allowed for **GitHub Apps and OAuth apps**."*

It appears in neither the device-flow section nor the OAuth App rate-limits page. It is a *secondary* limit,
and the docs say secondary limits are *"subject to change without notice"*.

**Why it is sharper than the 50/hour figure.** The device flow polls
`POST /login/oauth/access_token` — which is, literally, an OAuth access token request. So **every poll spends
this budget**, and one authorization attempt spends it many times:

| | polls spent |
|---|---|
| a person who types the code in ~30 s, polling every 5 s | ~6 |
| a person who types it in ~60 s | ~12 |
| **an abandoned attempt** (opens the flow, never enters a code) | **up to 180** — the full 15-minute expiry at 5 s |

That last row is the finding. **Abandoned attempts are nearly free on the 50/hour budget and ruinous on this
one.** 50 successful sign-ins cost roughly 500 polls; 2,000 ÷ 180 ≈ **11 abandoned attempts per hour would
exhaust the budget on their own** — and an abandoned attempt submits no verification code, so it is invisible
to the 50/hour limit entirely. A launch where people open the sign-in screen and wander off is the case that
breaks, and nothing in the 50/hour analysis would have predicted it.

> **Stated as inference, not fact.** GitHub does not explicitly say that device-flow polling counts against
> the 2,000. It says "OAuth access token requests", and the polled endpoint is exactly that. The inference is
> strong but it is an inference, and it is one of the things worth measuring rather than assuming.

**Three mitigations, all free:**

1. **Poll only while the app is in the foreground.** A person typing a code is in the foreground by
   definition. Backgrounding the app should stop the poll, which turns an abandoned attempt from 180 polls
   into however many seconds they actually looked at it.
2. **Stop immediately on `expired_token`.** The flow's own expiry is the signal to stop, and continuing to
   poll past it is pure waste.
3. **Poll slower than the minimum, and back off.** GitHub states 5 s as the *minimum* interval, not a
   requirement to poll that often. Polling every 10 s halves consumption for a delay a person typing an
   8-character code will not notice; 5 s for the first minute then 15 s is better still.

**One measurement worth making before writing auth code.** The 50/hour figure appears in the **OAuth App**
docs. Grepping the **GitHub App** device-flow page finds **no occurrence of "submissions" at all** — its only
rate-limit language is the polling `interval`/`slow_down`. So the ceiling is documented for OAuth Apps only,
and whether GitHub Apps are exempt or merely undocumented is open. That may mean the ceiling is OAuth-App-specific, or that it is merely
undocumented for Apps. Register both and drive 51 submissions in an hour — **and, separately, run one device flow to expiry with no
code entered and count the polls it costs**, which settles the inference above. The answers decide the
product,
because a GitHub App costs one extra screen (the installation step — *"The app can only access resources in
an account where it is installed"*) and, if unconstrained, buys the removal of the ceiling entirely. With
both providers mandatory, **every** new user passes through this flow, so the ceiling applies to the whole
funnel rather than a subset.

---

## 2. Where it breaks

### 2.1 GitHub PKCE does not remove the client secret — verified, not assumed

This is the load-bearing correction, so here is the evidence rather than the recollection.

GitHub's token exchange (`POST https://github.com/login/oauth/access_token`) documents these parameters:

| parameter | required? |
|---|---|
| `client_id` | **Required** |
| `client_secret` | **Required** |
| `code` | **Required** |
| `code_verifier` | Strongly recommended — required *if* a `code_challenge` was sent |

And GitHub's own announcement of PKCE support (2025-07-14) says why the secret is still there:

> *"GitHub is not requiring PKCE for any authentication flow at this time, as **GitHub does not
> distinguish between public and confidential clients**."*

That sentence is the whole argument. In RFC 7636 terms, PKCE exists to let a **public** client (one that
cannot keep a secret) prove it is the same client that started the flow. GitHub's OAuth Apps have no
public-client concept, so PKCE is defence in depth on top of the secret — it does not stand in for it.

**Consequence:** "use github access token instead of pat, via PKCE" is not achievable with an OAuth App.
You would ship a `client_secret` in the APK, where it is extractable in about a minute, which is strictly
worse than the status quo because a leaked client secret is not one person's token — it is the ability to
impersonate the app to every user.

There is a way to get a secretless GitHub token, and the proposal does not name it: **the device flow**.

### 2.2 The device flow is the secretless GitHub answer, and it is not PKCE

GitHub's device flow takes **`client_id` only** at both steps — `/login/device/code` and
`/login/oauth/access_token` with `grant_type=urn:ietf:params:oauth:grant-type:device_code`. No secret
anywhere. GitHub's PKCE announcement says so directly: *"The device code flow and installation token flows
do not use PKCE."*

What it costs:

- The person reads an 8-character code (`WDJB-MJHT`) and enters it at `github.com/login/device`.
- It must be **enabled in the OAuth App's settings** before it works at all.
- The code lives 15 minutes; the app polls at GitHub's stated `interval` and must honour `slow_down`.
- **A shared rate limit: 50 verification-code submissions per hour per application.** Not per user —
  per app. That is a real ceiling and it is worth knowing before shipping, though 50/hour is far above
  this app's plausible onboarding rate.

So the honest GitHub menu is:

| flow | secret in the APK? | person's work | fits this app? |
|---|---|---|---|
| PAT (today) | no | create token, choose scopes, copy, type login | works, unpleasant |
| OAuth + PKCE | **yes** | click, log in, click | no — ships a secret |
| Device flow | no | type an 8-char code on a web page | **yes** |
| GitHub App + device flow | no | type a code | yes, but a bigger registration |

The proposal asks for the one row that does not work and skips the one that does.

### 2.3 "Registration pages in its own screens" is the request to refuse

The proposal asks: *"I want to render registration/signup pages for this services right inside the app and
its own screens so that users never have to leave it, is it complicated to do?"*

Three separate answers, because three separate things are being asked.

**(a) "In its own screens" — not possible, by design.** The GitHub and OpenRouter login pages are the
*identity provider's* pages. The entire point of OAuth is that your app never handles the password. You
cannot reimplement `github.com/login` as your own Compose screen; you can only show their page or send them
to a browser. This is not a limitation to work around — it is the security property being bought.

**(b) "Render their page inside the app" — possible, and the thing RFC 8252 tells you not to do.** RFC 8252
(BCP 212, *OAuth 2.0 for Native Apps*) has said since 2017: authorization happens in an **external user
agent**, not an embedded WebView. The reasons are concrete and apply here:

- An embedded WebView is indistinguishable from a phishing page. The person cannot see the address bar,
  cannot see the certificate, and cannot tell your app from one that is harvesting a password. Google
  blocks OAuth in embedded WebViews for exactly this reason, and GitHub's own guidance says the same.
- Cookies and sessions in an embedded WebView are the *app's* storage. The person's GitHub session would
  live inside Muffycodes, which they did not agree to and cannot inspect or clear.
- **This app's WebView makes it worse than usual.** `WebViewBrowser` is built for a different job:
  `javaScriptEnabled = true`, `domStorageEnabled = true`, no `WebViewClient`, no `shouldOverrideUrlLoading`,
  no `setSupportMultipleWindows`, no download handler, no cookie policy — and its whole design is *"one
  shot, no session, no state to leak"* (`Browser.kt:15`). A login flow is the opposite of every one of
  those properties. Reusing it for authentication would mean dismantling the constraints that make it safe
  for its actual purpose.

**(c) "So users never have to leave the app" — achievable, but not the way asked.** The good version is a
**Custom Tab** (`androidx.browser`), which is the platform's answer to exactly this: it is a real browser
with a real address bar and the person's real cookies, drawn over your app, and it returns via a deep link.
The person never opens a browser *app*; they see your app, a sheet with the real GitHub page, and come
straight back. That is what "never leave the app" can honestly mean.

For **OpenRouter specifically there is a third option the proposal does not know about**, and it is the
best one: the **headless mode**. Omit `callback_url` entirely and OpenRouter displays the authorization
code on screen for the person to copy; the app exchanges it with `code_verifier`. No deep link, no Custom
Tab, no browser at all — a paste box. For a single-provider, single-key flow that is remarkably little
machinery, and it degrades to nothing when a phone has no usable WebView (which this app already knows how
to detect and already has words for).

### 2.4 `.html` editor support is a different feature wearing the same sentence

The proposal joins "render sign-up pages" and "add `.html` file editor view support" with an "and". They
share nothing but the word *render*.

`.html` today is `TEXT` — `Workspace.kt:1642` lists `html`, `htm`, `css`, … among the text extensions — so
it already opens in the editor and is already editable. What is missing is a **rendered preview**, and the
nearest precedent in this codebase is Markdown, which has a renderer (`Markdown.kt`) and a preview path in
the editor. An HTML preview is a `WebView` in the *editor*, showing `file://` content, which is a different
WebView with different settings from both the agent's browser and any auth tab:

- it must **not** have network access or it can exfiltrate what it renders;
- it must not run with `javaScriptEnabled = true` on arbitrary local files without thought;
- `setAllowFileAccess` and `setAllowFileAccessFromFileURLs` are the specific footguns;
- it is `EntryKind`-level work (a new kind, or a `TEXT` subtype), not auth work.

**Recommendation: cut it.** It is a good feature and it is unrelated to OAuth, and bundling it means the
OAuth work is judged by whether a preview renders, which is how a security change gets reverted for a
cosmetic bug.

### 2.5 Two smaller things

- **`signIn` is the wrong seam.** It takes the login as given and adopts the provisional workspace under
  that name. An OAuth flow produces a token *before* it knows the login, so the order becomes: get token →
  `identity(token)` → `signIn(identity.login, token, …)`. That is a good change (it stops the person typing
  a login), but it means `signIn`'s contract shifts and its callers move with it.
- **Token expiry is unhandled, and OAuth makes it more likely.** GitHub OAuth Apps can be configured for
  **expiring tokens** — 8-hour access token, 6-month refresh token, `offline_access` scope. If that is ever
  turned on, `CredentialStore` needs to hold a refresh token and `GitHub` needs one retry-on-401 path. The
  proposal does not mention refresh at all. Today's PATs do not expire, so this is a new failure mode
  arriving with the feature.

---

## 3. Counterproposal

Four stages. **Stage 1 is the whole of the user-visible win and needs no OAuth at all** — which is the
point: it can ship this week and it is not wasted work when OAuth arrives.

### Stage 1 — Take the login out of the sign-in screen (no OAuth)

Keep the token box. When a token is pasted, call `GitHub.identity(token)` and fill in the login, the commit
name, and the commit email from the answer. The person pastes one thing and presses Connect.

- `GitHub.identity` already exists; this is a call, not a protocol.
- It removes the field most likely to be wrong (a login typed from memory), and it removes two more.
- It is the fallback path for every OAuth failure, so it must be good anyway.

**Why first:** it is small, it is testable today, and it makes the OAuth work below a *shortening* of the
screen rather than a replacement of it.

### Stage 2 — OpenRouter PKCE, headless mode

The cleanest possible OAuth, and the one that pays off first because the model key is the key every person
needs before anything works.

1. Generate `code_verifier` (43–128 chars, `SecureRandom`), `code_challenge = base64url(sha256(verifier))`.
2. Open `https://openrouter.ai/auth?code_challenge=…&code_challenge_method=S256&key_label=Muffycodes`
   — **no `callback_url`**, so OpenRouter shows the code on screen.
3. The person copies it into a box in the app.
4. `POST https://openrouter.ai/api/v1/auth/keys` with `{code, code_verifier, code_challenge_method}`.
5. The answer is `{key}` — store it through the existing `credentials.setModelKey(login, key)`.

Notes that matter:

- The code is **single-use and expires in 10 minutes**. The UI must say so, and a failed exchange must
  start over rather than retry.
- `state` is not returned in headless mode, so there is no CSRF parameter to check — the PKCE verifier is
  doing that job. Say so in the code, or the next reader will think it was forgotten.
- This is strictly better than today's paste-a-key for one reason worth stating in the commit message: the
  key OpenRouter mints is **named and attributable**, and the person can revoke it in their own dashboard
  without touching anything else they own.
- It also works on a phone with no WebView, which is a real case this app already handles.

### Stage 3 — GitHub, device flow, with the PAT kept as the escape hatch

1. `POST https://github.com/login/device/code` with `client_id` and `scope`.
2. Show `user_code` large and a button that opens `verification_uri` in a **Custom Tab**.
3. Poll `/login/oauth/access_token` at `interval`, honouring `slow_down` and `expires_in`.
4. On success: `identity(token)` → `signIn(identity.login, token, …)`.

Requirements and hazards:

- **The device flow must be enabled in the OAuth App settings** or step 1 fails. This is a manual step
  outside the repo and belongs in the release checklist, not in a comment.
- **The `client_id` is public by design.** It ships in the APK and that is correct — say so in the code, or
  a future reader will treat it as a leak and "fix" it.
- **Poll on a coroutine with the app in the foreground**, not from the agent service. This is a person
  watching a dialog, not a background job.
- **The 15-minute window is the whole interaction.** If it lapses, say so and offer a new code; do not
  silently start over.

### Stage 4 — Only if a Custom Tab is genuinely wanted

`androidx.browser:browser` plus an `intent-filter` on `com.example.notes://oauth` (the manifest has **no**
intent filters beyond LAUNCHER today, so this is new surface). This buys a nicer in-app sheet for the device
flow's `verification_uri`, and nothing else — the code still gets typed. **I would skip it.** The device
flow's whole shape is "read a code, type it", and a Custom Tab already handles the typing step without a
redirect URI to maintain, register, and get wrong.

### What I would not build

- **An OAuth App + PKCE for GitHub**, per §2.1. It ships a secret to remove a field.
- **Login pages rendered in the app's own WebView**, per §2.3(b). It is the one pattern the spec forbids,
  and this app's WebView is deliberately the opposite of what it needs.
- **`.html` preview**, per §2.4. Right feature, wrong change.
- **A "registration" screen of our own.** We never register anyone; GitHub and OpenRouter do. Our screen is
  "connect", and calling it "registration" will make someone try to collect a password.

---

## 3a. The build order, now that D5 is settled

Six steps. The first two are the whole user-visible win and neither needs OAuth, so the screen improves even
if the flow work stalls.

**1. Fill the sign-in from the token.** `GitHub.identity(token)` already exists. Paste a token, get the login,
the commit name and the email. This is the fallback path for step 4, so it is not throwaway work.

**2. OpenRouter, headless PKCE.** No `callback_url`; the person copies the code back; `POST
/api/v1/auth/keys` returns the key; store it through `credentials.setModelKey`. Ten-minute expiry, single
use, no `state` in headless mode — the verifier does that job.

**3. The onboarding screens.** Capability screens first (what the app can do), then GitHub, then OpenRouter,
then the app. Every step needs a way forward for a person who cannot connect right now, which is where step 1
becomes the GitHub escape hatch. `openFirstRunReadme` moves after this, or is folded into it.

**4. GitHub device flow.** `POST /login/device/code` with `client_id` and `scope`; show `user_code`; poll
`/login/oauth/access_token` at `interval`, **foreground only**, **stopping on `expired_token`**, and slower
than the 5 s floor after the first minute. On success, `identity(token)` → `signIn(...)`.

**5. The failure screen.** Rate limit → "use a token instead" (visible, not tucked away), a retry the person
controls, and a sentence that promises no timer — because the docs say the status of a secondary limit cannot
be read.

**6. The mandatory-sign-in switch.** Removing the user-facing provisional state, and sending a person back to
the sign-in screen when the last account is removed. **The `~local` machinery stays** — it is what makes all
76 test classes hermetic (D2).

**Two things that must happen outside the repo**, and are easy to forget until step 4 fails: the OAuth App must
be registered, and **device flow must be ticked in its settings** (`device_flow_disabled` is what a missed
tick looks like). The `client_id` is then a build constant and is public by design.

**The measurement, before step 4 lands:** 51 submissions in an hour (does the 50/h ceiling exist as
documented?), and one device flow driven to expiry with no code entered, counting polls (does polling spend
the 2,000/h budget?). Both are cheap; the second decides whether foreground-only polling is a nicety or a
requirement.

---

## 4. Open questions

These need answers before Stage 2, and most of them are decisions rather than facts.

**Q1. Does the device flow get enabled on the OAuth App, and who owns that app?**
The device flow is off until someone turns it on in settings. Is there an OAuth App registered for this
project already, or is registering it part of the work? Its `client_id` must then be a build constant, and
its callback URL (if any) must match a domain we control.

**Q2. Is an expiring-token configuration acceptable?**
GitHub OAuth Apps can be set to issue 8-hour tokens with 6-month refresh tokens. Options: (a) keep
non-expiring tokens and no refresh path — simplest, but a stolen token never dies; (b) turn on expiry and
add a refresh token plus one retry-on-401 in `GitHub`. I lean (a) for this stage and (b) as its own small
piece of work, but this is a policy call, not a technical one.

**Q3. Does the PAT path survive?**
I think it must — for a GitHub Enterprise host, for a token someone already has, for the day the OAuth App
is rate-limited or misconfigured. But "two ways to sign in" is a screen with a fork in it, and the proposal
implies one. **Recommendation: OAuth is the default and the token box is behind "use a token instead".**
Confirm or overrule.

**Q4. What does the app do with the OpenRouter key once OAuth mints it?**
Today `setModelKey` stores one key per login. OpenRouter's OAuth mints a *new* key each time — so a person
who connects twice has two live keys in their OpenRouter account, and the app knows about one. Do we (a)
accept the litter, (b) store the key hash and offer a revoke link, or (c) use `key_label` to make them
findable (`key_label=Muffycodes` at least makes them identifiable in the dashboard)? I lean (c) plus a line
of text saying the key can be revoked at openrouter.ai/keys.

**Q5. Onboarding order, and what happens to the README.**
`openFirstRunReadme` runs before anything. Proposed order: welcome → connect OpenRouter → connect GitHub →
README. But a person can use the app with neither connection (local projects exist before sign-in —
`adoptProvisional` proves it). **So: is onboarding a gate or a nudge?** I lean nudge — every step skippable,
README still shown — because a gate contradicts the app's existing "make something before you sign in"
design. Confirm.

**Q6. Does the MCP OAuth sentence get updated in the same change?**
`Mcp.kt:72` says OAuth needs a round trip the app does not do yet. After Stage 2/3 that sentence becomes
half-false (the app does do PKCE, for two providers). It is a one-line comment, but leaving it stale is how
the next person concludes the capability exists where it does not.

**Q7. `.html` preview — deferred, or dropped?**
I have argued for cutting it from this change. If it is genuinely wanted, it deserves its own short
proposal, because the security settings (network access, file access, JS on local files) are decisions
nobody wants to make while also changing how people log in.

**Q8. Is there a test story for OAuth at all?**
Every flow here needs a browser and a real GitHub account. What is testable is: PKCE generation against
RFC 7636's test vector, the `state`/`slow_down`/`expires_in` handling as pure functions, the exchange
request's exact body, and the failure sentences. **Nothing that talks to github.com should run in CI** —
so the flows need to sit behind an interface the way `Browser` and `GitHub` already do, and that interface
is a design decision worth making before the first line rather than after.

---

## 5. Summary of the changes I would make to the proposal

| proposal says | I would |
|---|---|
| OAuth + PKCE for GitHub | **device flow** — PKCE for GitHub needs a client secret in the APK |
| PKCE for OpenRouter | yes, and **headless mode** — no callback, no deep link, no WebView |
| both on the starting screen | yes, but **skippable**, and the PAT stays behind "use a token instead" |
| onboarding before the README | yes, as a **nudge**, not a gate |
| render sign-up pages in the app's own screens | **no** — their pages, in a Custom Tab or not at all; our screens only ever say "connect" |
| `.html` editor view support | **separate proposal** — it is an editor feature, not an auth feature |

The single sentence version: **the proposal asks for the two flows that need a secret and skips the one that
does not, and bundles a genuinely good editor feature into a security change where a cosmetic bug could get
it reverted.**
