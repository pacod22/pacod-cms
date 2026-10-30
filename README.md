# pacod-cms

An open-source content management system for editorial websites: articles, categories, authors and
tags; standing pages; a shared image library; advertising bookings with an HTML5 banner builder;
a link-in-bio page; PDFs of articles and whole journal issues; reader, advertising and crawler
statistics; and multilingual content with a configurable original language.

Java 25 · Spring Boot 4.1 · Thymeleaf · MongoDB · Maven

**One stack hosts many websites.** Each website has its own MongoDB database, its own folder of
files and its own cache entries, and answers at the hosts it claims. People sign in to the studio
through **pacod-id**, which also holds their roles — per organisation and per website. Anyone who
signs up can create an organisation (and becomes its admin), create websites in it, and invite
people to work on them; a platform admin sets the limits. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Quick start

Requires Docker (Compose v2.20 or later), `make`, `curl`, `openssl` (Node 20 or later, for `make e2e`;
`make up` sets up the studio's account in pacod-id with it, or in a Node container without it), and
**pacod-id**, the sign-in service: either an
image `pacod-id:dev` (`PACOD_ID_IMAGE`) or a checkout of its sources next to this one (`../pacod-id`,
or set `PACOD_ID_SRC`), from which `make up` builds the image once — a Rust build, slow the first
time. Development and staging also run **pacod-gw**'s mock AI gateway, so they need its two images
too — `pacod-gw-lite:dev` and `pacod-gw-mock:dev` (`PACOD_GW_LITE_IMAGE`, `PACOD_GW_MOCK_IMAGE`) —
or pacod-gw's sources (`../pacod-gw`, or set `PACOD_GW_SRC`), built the same way. Everything else
builds inside the images.

```bash
make init      # this machine's .env: ENV=dev and fresh secrets (nothing is printed)
make up        # check, build, start everything, wait; prints the addresses
```

That is a developer machine. **Stage and production** start the same way, with their hosts named:
`make init ENV=prod STUDIO_HOST=… ID_HOST=… SITE_DOMAIN=… EMAIL=…`. See
[`docs/RUNNING.md`](docs/RUNNING.md): the three environments, the first start, content, verifying.

Then open the studio at **http://studio.localhost:8000/** and sign in on pacod-id's page
as `admin@pacod-cms.test` (`ID_ADMIN_EMAIL`) with the password `ID_ADMIN_PASSWORD` from
`.env` — the bootstrap platform admin. **Create an account** on the sign-in page makes a new
account that holds no role, so it sees no website; the confirmation mail lands in the mail catcher.
On its first start the studio creates **three demo websites** in one demo organisation:
**"Demo Journal"** (editorial) at **http://demo.localhost:8000/** and **http://localhost:8000/**,
**"Demo Notes"** (minimal) at **http://minimal.localhost:8000/**, and **"Starward"**, a space-travel
magazine for an invented star system, at **http://space.localhost:8000/**.
The two journals have six sections, twenty published articles with generated pictures (some
translated into German and Spanish), a link page and two bookings of their own. Starward is the
showcase: the image-led **magazine** template set, 24 long-form articles illustrated with NASA
photographs, its own pages, menus, layouts and journal issues, and five invented advertisers with
animated HTML5 creatives and their own pages. `*.localhost` resolves to your own machine on macOS and in current
browsers, so no hosts file is needed.

| | URL (development defaults, from `env/dev.env`) |
|---|---|
| Studio | http://studio.localhost:8000/ — the website list; a website's screens are under `/sites/<id>/mgmt/` |
| Sign-in (pacod-id) | http://id.localhost:8000/ |
| Mail catcher (every mail pacod-id sends) | http://localhost:8125/ |
| Demo websites | http://demo.localhost:8000/ and http://localhost:8000/ (editorial); http://minimal.localhost:8000/ (minimal); http://space.localhost:8000/ (magazine: Starward, the space travels) |
| Over HTTPS (Caddy's local CA) | https://demo.localhost:8443/ — the studio's sign-in always returns to `STUDIO_ORIGIN`, locally http://studio.localhost:8000 |
| Site, bypassing Caddy | http://localhost:8080/ (picks the website by host, like Caddy) |
| Studio, bypassing Caddy | http://localhost:7070/mgmt-health — health only; signing in works only at the studio's configured address |
| MongoDB | `localhost:27017` |

Another website: in the studio, **Organisations → New organisation**, then **Websites → New
website** — tick **Start with demo content** to get the same sample content (English websites
only). It answers at once at `http://<id>.localhost:8000/` (`PACOD_SITE_DOMAIN`, default
`localhost`, plus any address you add), and its **Members** screen invites people by e-mail — in
development the mail lands in the mail catcher. Creating an organisation as anyone but a platform
admin needs the studio's provisioner account in pacod-id, which `make up` creates once
(`make id-provisioner`). From the command line instead:

```bash
make site-create SLUG=acme NAME="Acme News" HOSTS=acme.localhost DEMO=1   # with the demo content
make site-create SLUG=notes NAME="Notes" HOSTS=notes.localhost TEMPLATE=minimal
make seed SITE=acme                # the demo content into a website that has no articles yet
make seed SITE=acme SEED_DIR=…     # or a seed of your own, from a folder (docs/RUNNING.md)
```

The demo content is written in English, so it is offered only to a website whose content language
is English (`LANGUAGE=en`, the default); a website in another language starts empty.

(`ORG=org_…` adds it to an existing organisation; without it, a new one.)

If a port is already taken on your machine, override it on the **command line**:

```bash
make up HTTP_PORT=8100 HTTPS_PORT=8543 WEB_PORT=8180 MGMT_PORT=7170 \
        MONGO_PORT=27117
make smoke HTTP_PORT=8100 WEB_PORT=8180 MGMT_PORT=7170
```

`VAR=… make up` does **not** work: the Makefile includes `.env`, and an included assignment beats
an environment variable. A command-line assignment beats both. Or set the values in `.env`.
The studio spells a website's address (its previews and "view site" links) with Caddy's port in
development, so it follows `HTTP_PORT`; `SITE_PUBLIC_PORT` overrides it. pacod-id's issuer and the
studio's sign-in addresses follow `HTTP_PORT` too, unless `ID_ISSUER` and `STUDIO_ORIGIN` are set.

`make smoke` probes the main routes through Caddy by host and checks each code and redirect;
`make e2e` signs up, signs in, restarts the studio and signs out through pacod-id, then creates an
organisation and a website as a new account, invites an editor and deletes both. `make status`
shows the environment, its containers and its addresses; `make down` stops everything and keeps
the data; `make destroy` also drops the volumes — the content and every pacod-id account. Run
`make` on its own for every target, grouped.

## Shared network

pacod-cms runs on **`pacod-net`**, the external Docker network every pacod repo shares (`make
network`, run by `make up`, creates it), and follows their naming convention,
[`pacod-demo/docs/NETWORK.md`](../pacod-demo/docs/NETWORK.md): a container's name is its service's
and its address on the network.

Listeners that only servers reach are on a second shared network, **`pacod-admin-net`**, which is
internal (no route out); `make network` creates it too. Here that is pacod-id's internal listener,
bound to `pacod-id-internal:8071`, an alias pacod-id has on that network alone, so the rest of
`pacod-net` cannot reach it. The studio, its one caller, joins `pacod-admin-net` and calls that
address; `http://pacod-id:8071` no longer answers.

- **`docker-compose.yml`, the app file**: cms's own services — `cms-site`, `cms-studio`,
  `cms-mongo` and `cms-gotenberg`.
- **`docker-compose.standalone.yml`**: cms's copies of the shared components it uses — `pacod-caddy`,
  `pacod-id` with `pacod-postgres` and `pacod-db-init` (which runs `platform/postgres/*-db.sh`),
  `pacod-gw` and `gw-mock` (profile `dev-gateway`) and `pacod-mailpit` (profile `mailpit`). `make`
  loads both files; the full stack (`pacod-demo`) loads only the app file and runs one of each shared
  component for every product.
- **`docker-compose.external.yml`** (`PROXY_MODE=external`): in place of the standalone file on a
  host whose Caddy and pacod-id already run — the site and the studio join that Caddy's network,
  and `make caddy-config` writes cms's sites for it. See
  [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md), *Behind an existing Caddy*.
- **`platform/caddy/cms.caddy`**: cms's two Caddy sites, the studio (`CMS_STUDIO_SITE`) and every
  other host, the websites (`CMS_PUBLIC_SITE`). cms's Caddyfiles import it, and so does the full
  stack's.

Only one stack running `pacod-*` containers fits on a machine: stop another repo's standalone stack
before `make up` here. An installation from before the move keeps its data under the old volume
names until it is copied over: [`docs/RUNNING.md`](docs/RUNNING.md), *Moving to pacod-net*.

## Modules

| Module | What it is |
|---|---|
| `pacod-cms-core` | Nearly all the code: documents, repositories, services, the tenancy (which website a unit of work belongs to, and its database, storage and cache scope), the control plane (organisations, websites, domains), the seed content new websites start with, and a Java port of pacod-id's rules engine with the CMS's roles (`authz/cms.yaml`). A jar, not an application. |
| `pacod-cms-site` | The public site for every website: Thymeleaf templates, page controllers, view models, the chrome's message bundles, read-side services, caches and the traffic counters. Picks the website by the request's host. |
| `pacod-cms-studio` | The studio: the portal (organisations, creating websites, members and invitations, the installation's settings), the website list, and each website's settings, editing, journal issues, PDF settings, advertising and statistics screens. Server-rendered Thymeleaf. Sign-in is OpenID Connect against pacod-id; each screen checks the person's role at that website, read from pacod-id's authorisation feed. Its UI is in English, Bulgarian, German or Russian; `pacod.studio.default-language` (default `en`) picks the default. |

**The two applications talk to MongoDB directly.** Neither is an HTTP client of the other; the
only HTTP client in the project is the studio's, to pacod-id. That is why the site's caches use
short TTLs (60 seconds) instead of eviction: a write made in the studio cannot reach the site's
in-process cache, so an edit is live on the public site within a minute.

**Data is split by website.** The control plane is the database `pacod_cms` (`MONGO_DATABASE`):
organisations, websites and their domains, the platform settings. People, sessions and roles are
pacod-id's, in its own
PostgreSQL; the public site never consults it. Each website's content is in a database of its own,
`site_<id>`, and its files under `${STORAGE_HOST}/sites/<id>/`. Code that touches website data
without a website bound fails rather than guessing one.

Caddy fronts the stack and routes by host: the studio's host (`STUDIO_HOST`, default
`studio.localhost`) goes to the studio, pacod-id's (`ID_HOST`, default `id.localhost`) to pacod-id,
every other host to the site, which answers 404 for a host no website claims. The site also serves
each website's images and banner files, out of that website's folder. In production Caddy serves
every host over HTTPS, obtaining each website's certificate on demand (or serving a certificate
pair you supply); on a developer machine it serves plain HTTP, and HTTPS from its own local CA. **The
studio must be on a different registrable domain from the websites**, and refuses to start
otherwise — see [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

Further reading: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) (how the parts fit),
[`docs/TEMPLATES.md`](docs/TEMPLATES.md) (writing a template set),
[`docs/SECURITY.md`](docs/SECURITY.md) (what keeps websites and the studio apart).

## Configuring a website

**Identity is data, not markup.** Two kinds of document in the website's database describe it:

- `siteSettings` (one document, id `site`): name, tagline, description and footer note (each per
  language), the public base URL, logos, favicon, share image, contact details, social profiles,
  and the third-party ids — Google Tag Manager, Programmable Search, Facebook app, search-console
  verification. **Every third-party id renders only when it is set and has its vendor's shape**; a
  fresh website loads no tracking at all.
- **A website with a Tag Manager container asks before loading it.** Its pages carry the container
  id as data and a consent banner in the reader's language (accept all, necessary only, or choose
  analytics and marketing), linking the website's `cookies` page, else its `privacy` page. Nothing
  from Google loads until the reader allows analytics or marketing; Google's consent signals start
  denied and follow the choice. The choice is one first-party cookie, `cookie_consent` (180 days);
  "Manage cookie preferences" in the footer, or any link to `#cookie-preferences` on a page, reopens
  the banner, and withdrawing a choice clears the Google cookies the page can reach and reloads.
  Previews, print and PDF views, error pages and the `/social` page never load the container. The
  site's own `Content-Security-Policy` names only who may frame it; one you add in front must allow
  `https://www.googletagmanager.com`.
- `menu` (ids `main` and `footer`): navigation trees whose items name their target by kind — home,
  latest, a category id, a page slug, search, the social page, or a URL — so the site builds every
  address itself.

Both are seeded from `pacod-cms-core/src/main/resources/seed/site.json` when the website is
created, each **only while it is absent**. A website other than the demo starts with its own name
rather than the demo's. The menus are edited in the studio (*Designing a website*); the settings
have no screen yet. Edit them with `make mongo SITE=<id>`, for example:

```js
db.siteSettings.updateOne({_id: "site"}, {$set: {
  baseUrl: "https://www.example.com",
  gtmId: "GTM-XXXXXXX",
  names: [{lang: "en", text: "Example Times"}, {lang: "de", text: "Example-Zeitung"}]
}})
```

Changes show within the 60-second cache.

The other seeds work the same way: `seed/pages.json` (the standing pages — about, contact,
advertise, terms, privacy; `make reseed-pages SITE=<id>` rebuilds them for an existing website) and
`seed/languages.json` (the languages the website offers). **The privacy and terms pages ship as
placeholders. Replace them before you publish.**

**The demo content** (`pacod-cms-core/src/main/resources/demo/demo-journal.json` and
`demo-space.json`) is different: it is written only when someone asks for it — the new-website
form's checkbox, `make site-create … DEMO=1|space`, `make seed SITE=<id> [DEMO=space]` — and only into
a website that has no articles. The journal's pictures are drawn when it is written, at the
website's image size. Starward's are real photographs from NASA's missions and astronauts, captioned
as the invented places of the Reach, each credited; where they come from and the rule for what may
be shipped is `pacod-cms-core/src/main/resources/demo/space/photos/VENDORED.md`. Every place, person,
festival and advertiser is invented; replace or delete any of it in the studio.

**The original language of a website's content** is chosen when the website is created
(`make site-create … LANGUAGE=de`; English by default) and stored on its record in the control
plane. It is what every page is guaranteed to be written in, the last fallback for a missing
translation, and the legally governing text. `pacod.content.default-language` (default `en`) is
only the fallback for code that runs with no website bound.

**The public chrome** (navigation labels, buttons, notices) is translated on the server from
`pacod-cms-site/src/main/resources/messages*.properties`: English base, plus Bulgarian, German,
French, Spanish, Italian and Russian. A language with no bundle gets the English chrome around
its own content.

## Designing a website

In the studio, under **Design**, each website chooses how it looks:

- **A template set**: `editorial` (a magazine: a front page of article grids, a column beside the
  content) or `minimal` (one quiet column). Switching first shows what the website's layouts would
  lose on the other set. Nothing is removed: sections the set cannot show are kept, flagged, and
  come back on a switch back.
- **A layout per page type**: home, article, category, latest, standing page, search. Each is a list
  of sections per region, such as the page's own content, lists of articles (latest, a category, the
  most read), rich text, a call to action, a search box or the social links. Sections are added,
  configured, and moved by dragging or with the arrows.
- **A theme**: colours (with their night-mode values), fonts and widths, a preset to start from,
  and CSS of the website's own.
- **The menus**: the main and footer navigation, with labels per language.

Layouts and the theme are drafts until someone allowed to publish publishes them. **Preview**
shows the drafts in a frame from the website's own address, at desktop, tablet or phone width, or
on another template set. It needs `PACOD_PREVIEW_SECRET`, which `make up` generates. Previews are
never counted as readers and load no tracking.

A template set is code: a folder under `pacod-cms-site/src/main/resources/templates/sets/<id>/`
plus a manifest, `pacod-cms-core/src/main/resources/design/sets/<id>.yaml`. The manifest declares
the set's page types, regions, the section types it implements, its default layouts and its theme
presets. A set may `extend` another and override only some of its templates.

## Jobs, PDF export and journal issues

Work that takes a while runs in the background in the studio, under **Jobs**: one job of an
organisation at a time, with a live log, cancel and "run it next". The PDFs below and the AI
translations are jobs.

**PDF export** renders each article into one PDF per language it is written in, through Gotenberg
(part of the compose stack), from the article's print view; the article links its PDF once it
exists. The article editor has a button for its own PDFs; the jobs screen takes numbers or a range.

- **Two formats.** *Standard* is the website's print view. *Scientific* is a paper layout: a
  full-width head with the title, authors, abstract and keywords, then the article's text in two
  columns.
- **Two ways to make them.** *Code only* takes the abstract and keywords from the article's own
  description and tags. *With AI* has pacod-gw write an abstract, keywords and a short running title
  for each article and language; the article's text itself is never changed, and what the AI wrote
  is reused until the article is edited.
- **PDF settings** (under *Maintenance*, `/mgmt/pdf`) hold each website's defaults: the format and
  the way to make them, paper (A4, Letter or A5) and margins, the journal's name per language and
  its ISSN, a running head and page numbers, and what the AI is asked to write. Everyone who reads
  content sees them; the website's admin changes them. Every PDF form can choose another format and
  way for one run. An article has one PDF per language, so the latest render wins.

**Journal issues** (under *Content → Issues*, `/mgmt/issues`) gather articles into one PDF per
language, formatted as a journal. An issue has a volume, number and month, a title and an editorial
per language, and an optional cover image; its articles are picked one by one or filled from its
month, then put in order. Generating it renders a cover, the editorial, a table of contents with
page numbers, and then the articles — in the standard or scientific format, made by code or with AI
— with the issue's running head, a bookmark per article and the pages numbered throughout. A draft
issue's PDFs are downloaded from the studio only. **Publishing** it (anyone who may publish
content) puts the issue and its PDFs on the website at `/issues`; unpublishing takes them down again.

## Publishing and search engines

**Only a published article reaches a reader.** New articles, drafts and private ones answer 404
and appear in no list, search, sitemap or PDF; an author writes, an editor (who holds
`content.publish`) publishes. Each website answers its own `/robots.txt` and `/sitemap.xml` from
its database: the home page, the latest list, its published pages, categories, journal issues and
articles, at the address set as its base URL (or the one it was reached at). A draft journal issue
is never public.

## AI

AI features go through **pacod-gw**, an AI gateway: the studio never holds a provider's key. A
platform admin switches AI on per organisation; a website's admin can switch it off for that
website. **Translation** is a job: an article's title, summary and body into the languages ticked,
each answer refused unless it keeps the article's markup exactly; a second job translates the rest
of the website — standing pages, section names, menus, the social page and, if ticked, tags —
without overwriting an edit made while it ran. PDFs can have their abstract, keywords and running
title written by AI too (*Jobs, PDF export and journal issues* above). The installation screen has a button that tests the
gateway with one short call. The development stack runs
pacod-gw-lite in front of a mock (from `../pacod-gw`, `make images`), so nothing leaves the
machine and a "translation" is an echo of its source.

## Building without Docker

The JDK may not be on `PATH`. Either use `make`, which resolves it, or export it yourself:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)    # macOS; point JAVA_HOME at a JDK 25 elsewhere
export PATH="$JAVA_HOME/bin:$PATH"

./mvnw clean verify                     # build + unit tests
./mvnw clean install -Dmaven.test.skip  # build only
```

Use `-Dmaven.test.skip`, not `-DskipTests`: on Spring Boot 4.1 the latter does not skip AOT
processing.

Running an application directly needs a MongoDB, and the site serves only websites the studio has
created. With the compose stack's MongoDB up and the demo website created:

```bash
MONGO_PASSWORD=… java -jar pacod-cms-site/target/pacod-cms-site-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  --spring.mongodb.host=localhost --spring.mongodb.port=27017
```

## Tests

```bash
make test                # unit tests, no MongoDB needed
make it                  # integration tests against MongoDB in a Testcontainers container (Docker)
./mvnw test -pl pacod-cms-site -Dtest=WebArticleServiceTest
./mvnw test -pl pacod-cms-studio -Dtest=MgmtSecurityTest
./mvnw test -pl pacod-cms-core -Dtest=ArticleTest#copyConstructorCopiesPriority
```

`-pl` is required when selecting a single test; a bare `-Dtest=` fails in the modules that do not
contain the class. The unit suites need no MongoDB: the driver connects lazily and nothing touches
the database at startup under the `test` profile. They run with a test website bound and with
**Bulgarian as the original language** on purpose, so the non-default configuration is the one
that is exercised. The studio's tests never reach pacod-id: they sign in with Spring Security's
test support and take their roles from a fixed test source. The integration tests run two websites
in one real MongoDB and check they stay apart: routing, host resolution, caches, storage and
statistics. `make e2e` is the end-to-end check of sign-in and the portal against the running stack.

`make verify` runs the brand check and then the full build with unit tests; run it before
committing. The brand check (`scripts/brand-check.sh`) refuses any text file carrying a word from a
private deny-list kept outside the repository (`.local/brand-tokens.txt`, git-ignored) — names that
must never be published, for example. Without the list it is skipped and says so.

## Configuration

Profiles are `dev` (plain HTTP) and `prod` (expects a TLS keystore unless Caddy terminates TLS, as it
does in compose).
Settings come from three files, the later winning: the environment's preset `env/<ENV>.env`
(`dev`, `stage` or `prod`: profile, Caddyfile, ports, paths; tracked), this machine's `.env` (`ENV`,
its hosts and secrets; `make init` writes it) and an optional `.env-local`. `.env.example` documents
every setting a `.env` may hold. The secrets have no published default; `make check` (run by
`make up`) refuses to start while one is missing.

| | |
|---|---|
| MongoDB | `MONGO_HOST` `MONGO_USER` `MONGO_PASSWORD` `MONGO_DATABASE` (the control plane, default `pacod_cms`) |
| Sign-in (pacod-id) | `ID_ADMIN_EMAIL` (the first account, a platform admin) · `ID_ISSUER` `STUDIO_ORIGIN` (the addresses browsers use) · secrets `PID_PG_SUPERUSER_PASSWORD` `PID_DB_PASSWORD` `PID_KEK` `PID_TOKEN_PEPPER` `CMS_ID_STUDIO_SECRET` `CMS_ID_SERVICE_SECRET` `ID_ADMIN_PASSWORD` — no defaults; `make init` generates them into `.env` · mail `PID_SMTP_HOST` `PID_SMTP_PORT` `PID_SMTP_TLS` `PID_SMTP_USERNAME` `PID_SMTP_PASSWORD` `ID_MAIL_FROM` (pacod-mailpit unless set; production requires a relay) |
| Studio session | `MGMT_SESSION_TIMEOUT` — idle time; afterwards pacod-id's own session signs the person back in |
| AI | `PACOD_GW_URL` (default `http://pacod-gw:8080`) `PACOD_GW_API_KEY` `PACOD_AI_MODEL` (default `@fast`) |
| Portal | `PACOD_ID_PROVISIONER_TOKEN` (written by `make id-provisioner`; without it only platform admins create organisations) · `PACOD_SITE_DOMAIN` (new websites answer at `<id>.<domain>`) · `PACOD_ORG_CREATION` (`self-service` or `platform-admins`) · `PACOD_MAX_ORGS_PER_USER` `PACOD_MAX_SITES_PER_ORG` `PACOD_AI_FOR_NEW_ORGS` (starting values of the installation screen) |
| Hosts | `STUDIO_HOST` `ID_HOST` (Caddy) · `DEMO_HOSTS`, `DEMO_MINIMAL_HOSTS`, `DEMO_SPACE_HOSTS` (the demo websites', on an empty platform) · `SITE_PUBLIC_SCHEME` `SITE_PUBLIC_PORT` (how the studio spells a website's address) |
| Article image rules | `IMAGE_REQUIRED_WIDTH` `IMAGE_REQUIRED_HEIGHT` (the minimum, and the shape: 600×400 by default) `IMAGE_MAX_BYTES` (400 KB) `IMAGE_DIMENSION_CHECK` `IMAGE_FILE_SIZE_CHECK` |
| Statistics | `METRICS_VISITOR_SALT` `CRAWLER_TRACKING` `CRAWLER_VERIFY` |
| CORS | `CORS_ALLOWED_ORIGINS` |
| TLS in the JVM | `SSL_ENABLED` `SSL_KEYSTORE` `SSL_KEYSTORE_PASSWORD` `SSL_KEY_ALIAS` |
| Paths | `LOG_DIR` `STORAGE_ROOT` (container side) · `MONGO_DATA_DIR` `LOG_DIR_HOST` `STORAGE_HOST` (host side) |
| Logging | `APP_LOG_LEVEL` (`debug`, `info`, `warn`, `error`; default `info`) `APP_LOG_FORMAT` (stdout `json` or `text`; default `json`, `text` on dev) · `CMS_CADDY_LOG_FORMAT` (Caddy's access log, `json` or `console`) · files `cms-site.log`, `cms-studio.log` (JSON) under `LOG_DIR_HOST`. The convention: [`pacod-demo/docs/LOGGING.md`](../pacod-demo/docs/LOGGING.md) |
| Caddy | `HTTP_PORT` `HTTPS_PORT` · `CADDYFILE` (the preset's: `Caddyfile.dev` on dev, `Caddyfile` else) · `CMS_STUDIO_SITE` `CMS_PUBLIC_SITE` (its site addresses, derived from `STUDIO_HOST` and the preset) · `SSL_CERT_FILE` `SSL_KEY_FILE` (a certificate pair of your own, optional) · `CADDY_GLOBAL_OPTIONS` · `MAILPIT_PORT` (the mail catcher's page) |
| Environment | `ENV` (`dev`, `stage`, `prod`) · `PUBLISH_IP` (where the applications' and MongoDB's own ports listen: every interface on dev, loopback else) · `DEMO_WEBSITES` (the demo websites on an empty platform: on for dev and stage) · `COMPOSE_PROFILES` (`dev-gateway`, `mailpit`) |

See [`docs/RUNNING.md`](docs/RUNNING.md) for running each environment, and
[`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) for TLS, hosts and domains, sign-in and roles, and the
reference.

## Public JSON

The site's own JSON endpoints live under `/web/v1/**`; they are unauthenticated, read-only and
scoped to the website the host names, like every page. Nothing serves `/v1/**`.

## Licence

The project's own code is licensed under the [Apache License 2.0](LICENSE); see [`NOTICE`](NOTICE).

The TinyMCE editor vendored in `pacod-cms-studio` is **GPL-2.0-or-later**, so the studio as built
is redistributed under GPL-3.0 terms as a whole. The public site does not contain it.
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) lists every bundled component, its licence, and
where TinyMCE's source is.

## Roadmap

Where the project is going: [`docs/ROADMAP.md`](docs/ROADMAP.md).
