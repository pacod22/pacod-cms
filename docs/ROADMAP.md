# Roadmap

pacod-cms is a generic, open-source, multi-tenant CMS for editorial websites: many organisations,
each with many isolated websites, sign-in through **pacod-id**, AI features through **pacod-gw**, a
portal to create websites and manage members, and a section composer with switchable template sets
and themes. This page says what v1 includes, the decisions it rests on, and what comes after it.

Every change lands with `make verify` green: it runs the brand check first (`make brand-check`, a
private deny-list of words kept outside the repository), then the build with the unit tests, which
need no Docker. `make it` runs the integration tests (failsafe `*IT` tests against MongoDB on
Testcontainers).

## What v1 includes

### Tenancy
- **One stack serves every website.** Each website has a MongoDB database of its own (`site_<id>`),
  a folder of files and one or more hosts. A control plane (`pacod_cms`) holds the organisations,
  websites, domains, platform settings, the job queue and the tombstones of deleted websites.
- **The website is bound to each unit of work** (`TenantContext`, a `ScopedValue`): by the Host on
  the public site, by the `/sites/<id>/mgmt/**` path in the studio, and explicitly around background
  work. The database factory **fails closed** when no website is bound, and an unknown host is a
  404, never a default website.
- **Provisioning**: indexes, seed steps (languages, standing pages, settings and menus, banner
  skeletons) and a schema version, reconciled at every studio start; `make site-create` creates a
  website from the command line.
- **Per-website storage**, served by the site for the website the Host names and kept inside its
  folder; caches keyed per website; per-website buffers for page views, banner impressions and
  crawler visits, a deleted website's dropped rather than flushed.
- **Isolation tests** for routing, caches, metrics, storage, host resolution and a website's whole
  life (`make it`).

### Sign-in and roles through pacod-id
- **OpenID Connect** (authorization code with PKCE), every endpoint under `/mgmt/oauth2/`, absolute
  redirect URIs from configuration. Signing out of the studio ends pacod-id's session as well, and
  after a studio restart pacod-id's own session signs people straight back in.
- **Tokens carry no roles.** The studio follows pacod-id's authorisation feed (snapshot, long poll,
  reload on 410, deletion confirmations), pushes its role catalogue, registers organisations and
  websites as pacod-id scopes, and decides every permission itself with a Java port of pacod-id's
  rules engine, which passes every check of pacod-id's engine vectors.
- **`authz/cms.yaml`**: platform admin, provisioner, organisation admin, website admin, designer,
  editor, author and analyst, at platform > organisation > website, with vectors pinning what each
  may do. No role is implicit.
- **Every studio screen declares the permission it needs**, checked at the website's scope path; a
  screen that declares nothing is refused, and a test fails any handler without a declaration.
  Publishing content needs `content.publish` and deleting it `content.delete`, so an author writes
  and an editor also publishes and deletes.
- **A stale copy of the roles decides nothing**: when the feed has not answered for a minute the
  studio answers 503. A session or account pacod-id ends signs the person out at the next request.
- **`__Host-` cookies** for the studio's session and CSRF token in production.

### The portal
- **Organisations**: a list, self-service creation — the creator becomes the organisation's admin
  through the studio's provisioner, a pacod-id service account allowed exactly that
  (`make id-provisioner`) — renaming, and deleting an empty one. Organisation ids are generated
  (`org_<hex>`), because pacod-id's ids and slugs are one namespace.
- **Websites**: a creation form (organisation, name, permanent id, content language, time zone,
  template set, addresses, optional demo content), each new website answering at
  `<id>.<site domain>` at once; a settings screen for its name, language, time zone, status and
  domains (add, make primary, remove); deletion that takes it offline, removes its roles from
  pacod-id, then drops its database and files — and drops the database again once the public
  site's caches have let go, since a late write would recreate it.
- **Members and invitations** for organisations and websites, as a facade over pacod-id's admin
  API acting as the signed-in person (token exchange), offering only roles the person may give;
  invitations by e-mail or as a link.
- **The installation screen** for platform admins: who may create organisations (self-service by
  default; `PACOD_ORG_CREATION=platform-admins` is a ceiling), organisations per person, websites
  per organisation, AI for new organisations (off by default), AI per organisation, and a test of
  the gateway connection.
- **The studio and the websites on different registrable domains**, judged by the Public Suffix
  List as browsers judge cookies: the studio refuses to start otherwise, refuses a website address
  on its own registrable domain or pacod-id's, and reports stored ones.

### Editing and the public site
- **Articles** with translations edited one language per screen, a picture library with alt text
  per language, authors, categories and tags, in a vendored rich-text editor configured to rewrite
  stored bodies as little as it can.
- **Standing pages** per language at `/p/{slug}`, with a notice when a page is served in another
  language than the one asked for, and legal pages that name their governing language.
- **A link page** (`/social`) for the website's social profiles, with per-network statistics.
- **Advertising**: image and HTML5 bookings, and banner layouts built in the studio or imported as
  a ZIP; served and viewable impressions and clicks.
- **Statistics**: a dashboard, views and readers per period without a cookie, and a crawler log
  with reverse and forward DNS verification. A crawler is never counted as a reader.
- **Only published articles reach a reader**, on every route, list, feed, sitemap and PDF.
  `/robots.txt` and `/sitemap.xml` are answered per website, from its own database.
- **Languages**: the public chrome is translated on the server (English, Bulgarian, German, French,
  Spanish, Italian) with a server-rendered language switcher; the studio's interface is English,
  Bulgarian, German or Russian.

### Template sets, layouts and themes
- **Template sets**: `editorial` and `minimal` (one column, extending `editorial`), each a folder of
  templates in the site and a manifest in core declaring its page types, regions, the section types
  it implements, default layouts and theme presets. One template engine per set; a website renders
  with its own, the default if its set is missing. A set is chosen when a website is created.
- **Layouts**: per website and page type, a draft and a published list of sections per region —
  the article, a list of articles (latest, a category, most read; cards, hero, list, ranked or
  video), rich text, a call to action, a search box, the social links. The home page is a layout
  like any other.
- **Themes**: a shared vocabulary of tokens (colours with dark values, fonts, widths) plus the
  website's own sanitised CSS, served as `/theme.css`; presets per set.
- **The design screens**: a layout editor per page type (forms, and drag and drop), the theme
  editor, the menu builder, and the template switch, which reports what the new set would not
  render and keeps it — flagged, never dropped. Drafts are published by those who may
  (`design.publish`).
- **The draft preview**: the website's drafts in a frame of its own origin, through a short-lived
  token signed with a secret both applications share. Previews count for nothing, load no tracking
  and stay in preview as one follows links.

### Jobs and PDF export
- **Jobs run in the studio** (`/mgmt/jobs`): a queue in the control plane, claimed with a lease and
  fenced writes, so a stalled worker writes nothing over the one that took its job over; one job of
  an organisation at a time by default; a job of a deleted website is cancelled rather than run.
- **PDF export through Gotenberg**, locked to the site's own address: one PDF per language an
  article is written in, uncounted and untracked, linked from the article once it exists.

### AI through pacod-gw
- **Switched on per organisation** by a platform admin (off by default) and off per website by its
  admin. One gateway client: attribution by opaque ids, one retry for a 429, none for a 402 or 504,
  a cut-off answer a failure.
- **Translation of articles** as jobs: one call per field, an answer refused unless it keeps its
  source's markup exactly, each language written atomically and alone; from the jobs screen or the
  article editor.
- **Translation of the rest of a website** as a job: standing pages, section names, menus, the
  social page and, when ticked, tags. Menus and the social page are written only if nobody edited
  them meanwhile; a run makes at most 2,000 calls.
- **Writing help** in the editor: a selection rewritten six ways, previewed and inserted only on
  request; a suggested summary and tags. Answers are held to a small HTML allow-list.
- **Alt text per language** for every image, suggested by a vision model from the picture itself,
  and rendered on the site in the reader's language.
- **Design assist**: a theme or a page layout drafted from a few words, its answer schema built from
  the tokens and the sections the template set has, everything checked by the composer's own
  validators and written to the draft only.
- The development stack runs pacod-gw-lite in front of pacod-gw's mock providers.

### Demo content
- **"Demo Journal"**, fictional and general-interest: six sections (technology, travel, food,
  culture, science, opinion), four authors, twenty published articles of which five are also in
  German and Spanish, tags, a "Sections" menu group, a published link page, and two bookings of the
  website's own — an image and an HTML5 rotator built on the `sidebar` skeleton.
- **Asked for, never assumed**: a checkbox on the new-website form, `make site-create … DEMO=1`,
  `make seed SITE=<id>` for a website without articles, and the platform's two demo websites on a
  fresh development or stage install — `demo` (editorial) and `minimal`, in one organisation. A
  website without it starts with pages, settings and menus only.
- **The pictures are drawn when the content is written** (abstract shapes, no text), at the
  website's upload size and under its byte limit, and stored through the upload's own rules, so the
  repository carries no photograph and no licence to track.

### Front door and environments
- **Caddy is the front door for every host**: one set of rules (`docker/caddy/routes.caddy`) under a
  production and a development Caddyfile. In production every host is served over HTTPS, a
  website's certificate obtained on demand once the site confirms the host is an active website's,
  and a certificate pair of the deployment's own is served for the names it covers. In development
  Caddy serves plain HTTP, and HTTPS from its own local CA.
- **Three environments, one Makefile**: presets `env/dev.env`, `env/stage.env` and `env/prod.env`, a
  machine's `.env` written by `make init` (it never replaces a secret data depends on), a preflight
  (`make check`) that refuses an unsafe or incomplete configuration, `make smoke` checking every
  code and redirect over the environment's scheme, configurable mail for production, and
  `make e2e` exercising sign-in and the portal on a running stack. Stage is production's
  configuration with mail caught and AI mocked.
- **Documentation**: [`RUNNING.md`](RUNNING.md), [`DEPLOYMENT.md`](DEPLOYMENT.md),
  [`ARCHITECTURE.md`](ARCHITECTURE.md), [`TEMPLATES.md`](TEMPLATES.md) and
  [`SECURITY.md`](SECURITY.md).

## Design decisions

- **One MongoDB database per website** (`site_<id>`), plus one control-plane database
  (`pacod_cms`). It holds organisations, websites, domains, the platform settings, the job queue and
  the tombstones of deleted websites. People, sessions and role bindings are pacod-id's, never the
  CMS's.
- **Neither application calls the other.** The public site and the studio are two JVMs over one
  MongoDB; an edit made in the studio reaches the site through the database, within the site's
  60-second cache lifetime.
- **Tokens carry no roles.** The studio reads every binding from pacod-id's authorisation feed and
  decides each permission itself, with a port of pacod-id's rules engine over the CMS's own
  manifest; a copy of the feed older than a minute decides nothing.
- **The studio is server-rendered Thymeleaf in Spring**; the browser calls nothing but the studio.
- **The studio and the tenant sites sit on different registrable domains** (for example
  `studio.example.org` against `*.example-sites.net`), and the studio refuses to start otherwise.
  Tenants run their own JavaScript — HTML5 banners, tag managers, article bodies — and on a shared
  registrable domain that script could set cookies for the studio and defeat its CSRF protection.
  The studio has a host of its own and never serves a website's HTML or script. A shared tenant
  domain should be registered in the Public Suffix List's private section.
- **The public site's JSON endpoints** (`/web/v1/**`) are read-only and Host-scoped like every
  page. A write API is deferred.
- **A template set is code an operator installs**, built into the site's jar: a Thymeleaf template
  can do anything the site can, so there is no upload.
- **Jobs run inside the studio JVM** behind a switch, with leases, so a separate worker later is a
  launcher change only.
- **Caddy is the front door**, in development and production, with on-demand TLS for custom
  domains.

## Known limitations

- **The members list shows no platform-wide roles** (pacod-id lists bindings at the scope, below
  it, and above it up to the organisation); platform admins act on every website without appearing
  there.
- **Demo content is English only**: a website in another content language cannot have it.
- **The site's identity** (`siteSettings`: name, logos, contact details, third-party ids) has no
  studio screen (menus do); edit it in the database, `make mongo SITE=<id>`.

## Deferred (not in v1)

- Atomic per-site id sequences (ids are max-plus-one per collection, in each website's database).
- Per-site time zones for the statistics (bucket ids follow the JVM's zone, though each website
  records its own).
- A studio screen for the site's identity; demo content in other languages.
- Template sets installed from a folder on disk rather than the classpath; template upload through
  the UI; per-page or per-category layout variants; layout version history; per-site overrides of
  template chrome strings.
- A fuller read-only delivery API; a write/headless management API with bearer tokens.
- Audit events in the control plane.
- A pacod-gw workspace and key per organisation (one static key, `PACOD_GW_API_KEY`, serves the
  installation), per-organisation AI budgets, batch requests.
- A separate worker deployable; an S3 storage backend; moving websites between MongoDB clusters.
- Back-channel logout (the feed's revoked sessions stand in for it).
- One end-to-end script for the whole walk: sign-up, organisation, a website from `minimal` with
  demo content, layout, theme, AI, PDF, a second editor (`make e2e-signin` and `make e2e-portal`
  cover sign-in and the portal).
- Organisation-level aggregated analytics; importers for existing content.
