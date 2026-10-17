---
name: run-pacod-cms
description: Launch and drive the pacod-cms stack — the public site for every website, the studio (the website list and each website's /sites/<id>/mgmt screens) and pacod-id, its sign-in — through docker compose, with a driver that signs into the studio through pacod-id, screenshots any page, generates traffic and probes every route by host. Use when asked to run, start, screenshot or manually verify this app.
---

# Running pacod-cms

The containers: `caddy` in front of `site` (the public Thymeleaf site, for every website), `studio`
(the studio) and `id` (pacod-id, the sign-in service), plus `mongo`, `id-postgres` (pacod-id's
accounts and roles), `mailpit` (every mail pacod-id sends), `gotenberg` (PDF export) and the
development AI gateway `gw`/`gw-mock`. Neither application is a client of
the other — both talk to the same MongoDB, the control plane `pacod_cms` plus one `site_<id>`
database per website — and the studio asks pacod-id who someone is and what they may do, so
"running the app" means running the whole stack. There is no dev server and no watch mode.

```bash
make init                         # once per machine: .env with ENV=dev and fresh secrets
make up                           # check, pacod-id's image, build, start everything, wait, addresses
make status                       # the environment, the containers, the addresses
make seed SITE=<id>               # the demo content into a website without articles (the demo has it already)
make down                         # stop, keep data
```

Caddy routes **by host**: **http://studio.localhost:8000/** is the studio,
**http://id.localhost:8000/** pacod-id, **http://demo.localhost:8000/** (and
http://localhost:8000/) the demo website, which the studio creates on its first start against an
empty database. Any other host is a 404 from the site. pacod-id's mail is at
**http://localhost:8125/**. `make site-create SLUG=acme NAME="Acme" HOSTS=acme.localhost` adds a
website. The same hosts answer HTTPS on 8443 with certificates from Caddy's own local CA, which no
browser trusts; sign-in stays on the plain-HTTP addresses pacod-id has registered.

**`make up` needs pacod-id's image** (`pacod-id:dev`) or its sources at `PACOD_ID_SRC` (default
`../pacod-id`): the first `make up` builds it, a slow Rust build, and fails if neither exists.
`make init` wrote pacod-id's secrets into `.env` (an older machine keeps them in `.env-local`), among
them `ID_ADMIN_PASSWORD`, the password of the bootstrap platform admin `ID_ADMIN_EMAIL` (default
`admin@pacod-cms.test`) — the account the driver signs in as. Without a `.env`, `make up` stops at
`make check` and says to run `make init`. `docs/RUNNING.md` covers stage and prod; this skill drives
a dev stack. **Right after `make up` a signed-in
studio screen can answer 503** for a few seconds: the studio decides nothing until it has pacod-id's
roles, and loads them in the background.

Then drive it with the bundled driver — zero npm dependencies, Node built-ins only (this repo has
no `package.json` and deliberately no JS toolchain):

```bash
node .claude/skills/run-pacod-cms/driver.mjs <command> [args]
```

| Command | What it does |
|---|---|
| `smoke` | the main routes on the website's host, the studio's host and a host nobody claims, plus the direct ports — printing `Location` and content-type, not just the status code |
| `login` | signs in through pacod-id as the bootstrap admin — the studio's authorization redirect, pacod-id's password step, the studio's callback — and saves the studio session to `.artifacts/run-skill/cookies.json` |
| `get <path>` | authenticated GET of a studio page, prints the first 2 KB |
| `shot <path> [out] [theme]` | full-page PNG through headless Chrome; `theme` = `light`\|`dark` |
| `traffic [readers]` | synthetic traffic on the website from N distinct readers |
| `mongo <js>` | `mongosh` on the website's database (`site_<SITE>`), credentials read from `.env` |
| `ps` / `logs <service> [lines]` | container states / container logs (`site`, `studio`, `id`, `caddy`, `mongo`) |

Artifacts land in `.artifacts/run-skill/` (git-ignored — the jar holds a live `JSESSIONID`).

**Which host a path goes to.** `/mgmt/login`, `/mgmt/sites` (and `/mgmt/sites/new`),
`/mgmt/organisations…`, `/mgmt/platform`, `/mgmt/oauth2/…`, `/mgmt/ui-language/…` and
`/mgmt-assets/…` are the studio's platform paths and are sent as they are; **any other `/mgmt/…`
path means that screen of `SITE`'s website**, so `shot /mgmt/articles` captures
`/sites/demo/mgmt/articles`. A path starting `/sites/` goes to the studio unchanged; everything else
goes to the website's host.

`make e2e-signin` (`scripts/e2e-signin.mjs`, also plain Node) is the scripted end-to-end check of
sign-in: a self-registered account confirmed through mailpit sees no website and gets the 403 page,
the bootstrap admin opens the demo, a studio restart signs them back in through pacod-id's session,
a session ended at pacod-id signs them out, and sign-out ends pacod-id's session. It **restarts the
`studio` container** and addresses `studio.localhost` and `id.localhost` only.

`make e2e-portal` (`scripts/e2e-portal.mjs`) is the portal's: a self-registered account creates an
organisation (becoming its admin through the provisioner — `make id-provisioner` must have run,
which `make up` does), creates a website and sees it answer at `<id>.localhost`, invites an editor
by mail; the editor makes an account from the invitation, opens that website and is refused the
demo, its settings, the organisation and the installation; the platform admin turns AI on for the
organisation; the owner deletes the website (its host 404s within about a minute, its database
stays gone) and the organisation. It leaves accounts behind in pacod-id, harmlessly.

**Never probe a website with a `Host` header on `localhost` from Node**: `fetch` drops the header,
and `localhost` is one of the demo website's hosts, so every probe answers 200. Use the host's name
(`http://<id>.localhost:<port>/`), or `curl -H Host:`, which does send it.

## Rebuild before you believe anything

`make up` builds the images from the working tree. The driver talks to **whatever was built
last**, so a Java or template change you have not rebuilt is simply not there — and it does not
look like staleness, it looks like a 404 or an old rendering. This is the first thing to check when
a route you just added answers 404: `docker compose ps` shows the containers up and healthy either
way. A changed seed (`pacod-cms-core/src/main/resources/seed/*.json`) reaches an existing website
only through `make reseed-pages SITE=<id>`.

Caddy needs no restart after the applications are recreated: it resolves `site` and `studio` for
every new connection. A changed Caddyfile or `STUDIO_HOST` needs `make caddy-reload`, which checks
the file in a one-off container and then recreates Caddy.

## Ports, hosts, and what is actually listening

When a port is taken, move the stack with **command-line** assignments —
`make up HTTP_PORT=8100 WEB_PORT=8180 MGMT_PORT=7170` — not `HTTP_PORT=… make up`, which the
Makefile's included `.env` silently overrides. The studio's "view site" and banner-preview
addresses, pacod-id's issuer and the studio's sign-in addresses all follow `HTTP_PORT` while
`SITE_PUBLIC_PORT`, `ID_ISSUER` and `STUDIO_ORIGIN` stay unset, as `.env.example` leaves them.

The driver is the other way round: it reads `HTTP_PORT`, `WEB_PORT`, `MGMT_PORT`, `SMOKE_HOST`,
`STUDIO_HOST` (default `studio.localhost`), `ID_HOST` (default `id.localhost`), `SITE` (default
`demo`) and `SITE_HOST` (default `<SITE>.localhost`) from its **environment only**, never from
`.env` — except the sign-in credentials, which `login` reads from the preset, `.env` and
`.env-local`, and the compose files it passes (`env/<ENV>.env`, `.env`, `.env-local`). Pass the
same values:

```bash
HTTP_PORT=8100 WEB_PORT=8180 MGMT_PORT=7170 node .claude/skills/run-pacod-cms/driver.mjs smoke
SITE=acme node .claude/skills/run-pacod-cms/driver.mjs shot /mgmt/articles
```

**Check what owns a port before trusting a localhost result.** Another process bound to
`127.0.0.1` or `::1` — an `ssh -L` tunnel, another project's stack — beats Docker's `0.0.0.0`
binding for loopback connections, and every command here would then test something else while
looking exactly like a local test. `*.localhost` resolves to loopback (to `::1` first, from Node on
macOS), so the driver's Caddy rows are as exposed to this as `localhost` itself.

```bash
lsof -nP -iTCP:8000 -sTCP:LISTEN            # want com.docker, not ssh or anything else
docker port caddy; docker port site         # what this stack actually published
```

**`SMOKE_HOST` now moves only the driver's direct-port rows**; its Caddy rows address the studio
and website hosts by name. To probe the stack through a LAN address instead of loopback, use
`make smoke SMOKE_HOST=$(ipconfig getifaddr en0)`, which sends the host names as `Host` headers.

## Why a driver, for a server-rendered site

Three things a `curl` one-liner cannot do here, each of which fails *silently* if you skip it:

- **The studio is unreachable with one request.** Sign-in is OpenID Connect against pacod-id, with
  PKCE: the studio's `/mgmt/oauth2/authorization/pacod-id` redirects to pacod-id's authorize, which
  opens a sign-in "flow"; the password goes to pacod-id's page API
  (`POST /ui-api/v1/flows/<flow>/password`, with the issuer as `Origin`, or it is refused); its
  answer names the studio's callback with the code, which the studio redeems for its session. Two
  cookie jars, the studio's and pacod-id's, and a callback that works only at the address pacod-id
  has registered (`studio.localhost:<HTTP_PORT>`) — which is why `login` never uses the direct
  `:7070`. `login` checks every hop and names the one that failed. The password is
  `ID_ADMIN_PASSWORD` from `.env` or `.env-local`; pacod-id creates that account once, so a password changed
  in pacod-id afterwards no longer matches the file. One sign-in covers every website.
- **A status code hides this stack's characteristic failures.** A redirect that drops the port is
  still a 302 (it just sends the browser to a port nothing listens on); a JSON endpoint answering
  with the login *page* is still a 200; a website screen asked for without its `/sites/<id>` prefix
  is a 302 to the website list; a signed-in screen whose role check cannot run is a 503. `smoke`
  prints `Location` and content-type for exactly that reason, plus a pass/fail count. It is **not**
  a superset of `make smoke-tls`, which goes over HTTPS and inspects the certificate; the driver
  speaks plain HTTP only.
- **A screenshot without the session is a screenshot of the login form.** `shot` injects the saved
  cookies for the studio's host before navigating, so run `login` first for any studio path.

## Screenshots

```bash
node .claude/skills/run-pacod-cms/driver.mjs login
node .claude/skills/run-pacod-cms/driver.mjs shot /mgmt/sites
node .claude/skills/run-pacod-cms/driver.mjs shot /mgmt/monitoring
node .claude/skills/run-pacod-cms/driver.mjs shot /list/2 .artifacts/run-skill/list-dark.png dark
node .claude/skills/run-pacod-cms/driver.mjs shot /mgmt/organisations
```

Pass the path and the file as **separate arguments**: in a zsh loop, `set -- $p` does not split a
"path file" pair, and the driver then requests the path with the file name stuck to it — a 400, or
a redirect to the website list, that looks like a broken screen.

**Look at the PNG.** A blank or login-form frame is a failed launch, and nothing in the exit code
says so; so is the studio's error page saying roles cannot be checked (503: pacod-id unreachable,
or its roles not loaded yet). A studio screen with no website navigation in its sidebar was
rendered outside a website.

- Needs a Chromium. The driver finds Chrome, Chromium or any Playwright `chromium-*` cache build;
  failing that, `npx -y playwright install chromium`, or point `CHROME=/path/to/binary` at one.
- Capture is full-page (`captureBeyondViewport`), because the monitoring charts and the article
  editor are both taller than a viewport. A fixed sidebar paints only to viewport height in such a
  capture — the white band under the studio's nav in a tall screenshot is that, not a layout bug.
- **`theme` is not just a media query.** The studio honours `prefers-color-scheme` only while
  `pacod-mgmt-theme` is unset, and the public site never consults it at all — its
  `fragments/head.html` snippet tests `localStorage['pacod-theme'] === 'night'` and nothing else. The
  driver therefore emulates the media feature *and* writes both storage keys before the document's
  own scripts run. If you add a third stored choice (`pacod-mgmt-nav`, `pacod-mgmt-editor`), it goes in
  the same place.

## Driving the monitoring feature

`traffic` sends page views to the website's host as N distinct synthetic readers: `X-Forwarded-For`
is what `VisitorIdentity` hashes (with the website's id), and it takes the **first** entry of the
chain. Caddy *replaces* a value a client wrote with the address it saw — through it, every synthetic
reader would be one — so `traffic` goes to the site container's own port (`WEB_PORT`), where the
supplied address is the first entry. The addresses are in the RFC 5737 documentation range, so they
cannot collide with a real visitor.

The collector buffers per website and flushes on a schedule, so **wait out `app.metrics.flush.ms`
(60 s)** before the numbers move, then read them on that website's `/mgmt/monitoring`. On a fresh
database the chart renders empty and says so — the series starts when the stack first counted
anything and nothing is back-filled.

## Driving the design screens and the preview

The design screens are `/mgmt/design/**` (the driver's `get`/`shot` file them under `SITE`):
`/mgmt/design/layouts/home`, `/mgmt/design/theme`, `/mgmt/design/template?set=minimal`,
`/mgmt/design/menus/main`, `/mgmt/design/preview[?page=article&set=minimal&width=phone]`. Edits
are form POSTs with the page's `_csrf` and land in the draft; readers see them only after
`…/publish`, and then within the 60-second design cache — poll the website, don't sleep once.

**The preview's address is in the preview screen's `<iframe class="preview-frame" src=…>`**: the
website's own origin plus `?_preview=<token>`, valid 30 minutes. `get` that screen and take the
`src` to drive the website as the preview shows it. A plain-text 403 means the token expired, is
another website's, or the two applications hold different `PACOD_PREVIEW_SECRET`s (`make restart`
after `make init` wrote one). **That links keep the token is `preview.js`'s doing and has no
committed test**: check it in a real browser (CDP `Runtime.evaluate`: every same-origin
`a[href]` carries `_preview`, and a clicked menu link lands on a page that still has
`<meta name="pacod-preview">`). Nothing a preview does moves a counter; a reader's request does.

## What to expect when it is working

Two rows assert a **content type** as well as a status: `/p/nonexistent` and `/article/999999` are
404s that must come back as `text/html`, rendered by the site's error page. A 404 whose body is raw
JSON would pass a status-only check. `probe` sends a browser's `Accept` header for the same reason.

Shapes that look like failures and are not:

- on the studio's host, `/` is **302** to `/mgmt/sites`, `/mgmt/sites` is **302** to the login page
  until you sign in, `/mgmt/oauth2/authorization/pacod-id` is **302** to pacod-id's
  `/oidc/authorize` on `id.localhost`, `/mgmt/articles` (no website named) is **302** to
  `/mgmt/sites`, and `/sites/<id>/mgmt/monitoring` and its `/live` poll are **302** to
  `/sites/<id>/mgmt/login` (a 200 there would be the page handed to anyone who asked); `/sites/nobody/mgmt/articles` is **404**;
  `/assets/img/…` is **302** to sign-in — the studio's image previews are behind it;
- on the website's host, `/mgmt/login` and `/v1/health` are **404**, `/about.html` is **404** (no
  `.html` aliases), and `/social` is 404 until the page has been saved and published in the studio;
- a host no website claims is a **404** in `text/plain`, "No website answers at this address.";
- pacod-id answers its discovery document (`/.well-known/openid-configuration`) only on its own
  host and port — through Caddy at `id.localhost:<HTTP_PORT>`, never under another name;
- signed in, a website you hold no role at is a **403**, rendered by the studio's own error page;
  so are a website's settings for anyone short of `website.manage`, an organisation's screen for
  someone outside it, and `/mgmt/platform` for anyone but a platform admin. An image preview of a
  file that does not exist is a **404** for someone who may read the website's content;
- the studio's portal routes (`/mgmt/organisations`, `/mgmt/platform`, `/mgmt/sites/new`,
  `/sites/<id>/mgmt/settings`, `…/members`) are **302** to sign-in anonymously;
- a website's **Members** screen lists no platform admins: pacod-id lists the roles held at the
  website, below it and up to its organisation, never platform-wide ones.

The direct row `web /web/v1/health` answers on any host; a failure there with a `text/html` body
usually means something else owns the site port (`docker port site`).

Locally the article thumbnails are usually broken images. That is honest: the seeded records name
image files, and the storage directory (`STORAGE_HOST`, default `./.data/storage`) holds whatever
was uploaded, under `sites/<id>/`. The studio's mount is read-write, so deleting an image in the
local studio deletes the file — keep `STORAGE_HOST` on a scratch directory when exercising deletes
or banner layouts.
