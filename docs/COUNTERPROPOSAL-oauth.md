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

**D3. Tokens must be durable; no refresh machinery is wanted.** Verified: **OAuth App tokens do not expire
by default.** The eight-hour lifetime and the refresh token belong to **GitHub Apps**. So the ordinary OAuth
App token is as durable as the PAT it replaces, and "the agent runs for days in the background" needs no new
work. What kills a token is revocation, not time — and all three causes (the person revoking, GitHub
revoking, the registration being deleted) arrive as a **401**, which is one path: say so, offer to reconnect.

**D4. `.html` preview is deferred** until the auth block is finished. Cut from this change, as §2.4 argued.

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
   that is probably never reached. Reconsider only if it is actually hit.
4. **Sharding across several OAuth Apps** multiplies the ceiling and fragments the app's identity in every
   user's authorized-applications list. Noted so it is not reinvented as a clever idea. Do not.

**One measurement worth making before writing auth code.** The 50/hour figure appears in the **OAuth App**
docs. The **GitHub App** device-flow docs describe the same `interval`/`slow_down` mechanics and state **no
submission limit at all**. That may mean the ceiling is OAuth-App-specific, or that it is merely
undocumented for Apps. Register both and drive 51 submissions in an hour; the answer decides the product,
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
