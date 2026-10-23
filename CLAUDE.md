# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

pacod-cms is an open-source, multi-tenant CMS for editorial websites (see `docs/ROADMAP.md`). This
file describes the code **as it is now**, after Phase 8: one stack serving many websites, two
applications, one MongoDB database per website plus a control-plane database, and no accounts of
its own — people sign in through **pacod-id**, and what they may do at each website comes from its
feed, decided by a Java port of its rules engine. The studio's portal creates organisations and
websites and manages who works on them. Each website renders with a template set, its pages composed
from layouts of sections and styled by a theme, all edited as drafts in the studio and previewed
before they are published. Long work runs as jobs in the studio -- PDFs of articles, standard or a
two-column scientific paper, and whole journal issues -- and AI features go through **pacod-gw**.
Most of this file records a lesson some code already paid for — a setting that looks redundant, an
order that looks arbitrary — so read the relevant section before tidying anything it describes. The overviews for people are
`docs/ARCHITECTURE.md`, `docs/TEMPLATES.md` (writing a template set) and `docs/SECURITY.md`; keep
them true when the code they describe changes.

## Toolchain

Neither the JDK nor Maven is assumed to be on `PATH`. Resolve them explicitly, or go through
`make`, which does it for you:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw ...
```

Java 25, Spring Boot 4.1, MongoDB 8. The Maven wrapper is at the root; the modules have none. Use
`-Dmaven.test.skip`, **not** `-DskipTests`: on Spring Boot 4.1 the latter no longer skips AOT
processing.

## Commands

```bash
make                    # every target, grouped; and this machine's ENV and studio address
make build              # ./mvnw clean install -Dmaven.test.skip
make verify             # brand check, then the full build with unit tests — run before committing
make test               # unit tests only
make it                 # integration tests (*IT) on Testcontainers — needs Docker

make init               # once per machine: .env with ENV=dev and fresh secrets (stage/prod: ENV=… STUDIO_HOST=… ID_HOST=… SITE_DOMAIN=… EMAIL=…)
make up                 # check, network, images, the whole stack, wait, provisioner, addresses
make status             # ENV, the containers, the addresses  |  make down: stop, keep data
make restart            # rebuild and recreate cms-site + cms-studio
make logs / logs-<svc>  # every log / one service's  |  make shell-<svc>
make mongo              # mongosh on the control plane; `make mongo SITE=demo` on site_demo
make seed               # the demo content into a website without articles (SITE=demo on dev/stage)
make site-create SLUG=acme NAME="Acme News" HOSTS=acme.localhost [LANGUAGE=de] [ORG=org_…] [TEMPLATE=minimal] [DEMO=1]
make reseed-pages SITE=demo        # rebuild a website's standing pages; reports unless APPLY=1
make restore DUMP=d [FROM_DB= TO_DB=]   # mongorestore from MONGO_BACKUP_DIR; reports unless APPLY=1
make check              # the preflight `make up` runs: .env complete and safe for ENV
make smoke              # every main route through Caddy by host, codes and redirects checked; exit 1 on a mismatch
make smoke-tls          # each host's certificate over HTTPS (by SNI)
make e2e                # e2e-signin + e2e-portal on the running stack (dev, stage; Node, mailpit)
make caddy-reload       # validate the Caddyfile, then recreate Caddy (hosts, Caddyfile, a renewed pair)
make destroy            # stop + drop volumes (accounts too); CONFIRM=<env> outside dev
```

`docs/RUNNING.md` is the guide to the three environments. **`ENV` (`dev`, `stage`, `prod`) picks a
preset, `env/<ENV>.env`**: profile, Caddyfile, ports, paths, profiles, the demo switch, and dev's
hosts. Settings come from the preset, then the machine's `.env` (`ENV`, hosts, secrets; `make init`
writes it), then an optional `.env-local`, the later winning (*Settings files*, under *Docker*).
Locally the studio is **http://studio.localhost:8000/**. Sign in as `ID_ADMIN_EMAIL`, default
`admin@pacod-cms.test`, with `ID_ADMIN_PASSWORD` from `.env`. The rest of the local stack:

- pacod-id at **http://id.localhost:8000/**, its mail at **http://localhost:8125/**;
- the demo websites at **http://demo.localhost:8000/** (and http://localhost:8000/, also one of its
  hosts) and, on a platform created since Phase 8, **http://minimal.localhost:8000/**;
- all of it over HTTPS on 8443 too, from Caddy's local CA.

`make up` needs a `pacod-id:dev` image or pacod-id's sources at `PACOD_ID_SRC` (`../pacod-id`):
`make images` builds it once, a slow Rust build, and the mock gateway from `PACOD_GW_SRC` on dev and
stage. Override ports on the command line, `make up HTTP_PORT=8100 WEB_PORT=8180` — never
`HTTP_PORT=… make up`, which the included `.env` beats — and pass the same values to every target
that recreates a container, or the studio's issuer moves while pacod-id's does not. `STUDIO_HOST`,
`ID_HOST`, `SITE` (`demo` on dev and stage) and `SITE_HOST` (`$(SITE).$(PACOD_SITE_DOMAIN)`) pick
what the site-scoped and smoke targets address.

One test: `./mvnw test -pl pacod-cms-site -Dtest=WebArticleServiceTest` (or `-Dtest=Class#method`);
`-pl` is required, as a bare `-Dtest=` fails in the modules without the class. The unit suite needs
no MongoDB (the driver connects lazily). The `run-pacod-cms` skill drives the running stack.

## Architecture

Three Maven modules, packages `io.pacod.cms.{core,site,studio}`, one library and two applications
— and the split is not what the names suggest:

- **`pacod-cms-core`** — nearly all the code: documents (`core.data`), repositories, services, the
  tenancy (`core.tenant`), the control plane (`core.control`), the seed steps (`core.seed`) and
  their JSON (`seed/*.json`), and the rules engine (`core.identity.authz`, plain classes, no beans)
  with the CMS's manifest `authz/cms.yaml`. A jar, not an app, and it has **no controllers**.
- **`pacod-cms-site`** — the public Thymeleaf site, for every website at once: controllers, view
  models, `WebArticleService`, caches, the counters' buffers, the chrome's message bundles. Compose
  service `cms-site`.
- **`pacod-cms-studio`** — the studio: sign-in and the website list at `/mgmt/**`, each website's
  screens at `/sites/<id>/mgmt/**`, its CSS and JS at `/mgmt-assets/**`, and the pacod-id
  integration (`studio.identity`, `studio.access`). Compose service `cms-studio`. Routes, `app.mgmt.*`
  properties, `MGMT_*` variables and the `Mgmt*` classes still say mgmt (`ROLE_MGMT` survives only
  in the tests, see *Sign-in and roles*), and `WEB_PORT`/`MGMT_PORT` keep their names: the services
  were `web` and `admin` until Phase 8.

### Neither application is a client of the other

The most important fact, and it contradicts the module names. `WebArticleService` and every
`Mgmt*Service` autowire the **MongoDB repositories directly**; the one `RestClient` in the project
is the studio's `IdClient`, which talks to pacod-id, never to the site. Two JVMs over one MongoDB
server. Consequences:

- **A cache in the site cannot be invalidated by a write in the studio.** `CacheConfig` uses a
  uniform **60 s** TTL as its mechanism, for every cache including `SITE` (settings and menus). The
  studio has no cache manager (`MgmtApplicationTests` asserts none), and the `@CacheEvict`s that do
  exist (`ArticleReader.evict`, `TagService`'s) act only inside the JVM that runs them — none is a
  way for a studio edit to reach the site. A studio edit is live within a minute.
- **Both applications enumerate the core packages** — `core.data`, `core.repository`,
  `core.services`, `core.config`, `core.tenant` — rather than scanning `io.pacod.cms.core` whole,
  so anything added to core later, a controller above all, cannot quietly appear on every website's
  host or behind the studio's permit-all chain (core's old `/v1` controllers once reached the public
  site that way). **Only the studio scans `core.seed`**: only the studio provisions websites.
  `WebApplicationTests` asserts exactly **one** filter chain, zero `UserDetailsService` beans and no
  `/v1/**` pattern while `/web/v1/health` is mapped; `MgmtApplicationTests` asserts exactly **two**
  chains, that every `io.pacod.cms` `@Controller` is the studio's own, and that the website filter
  is ordered before security; `make smoke` expects `/v1/health` to 404 through Caddy and on the site's
  port.
- **`WebApplication` excludes `UserDetailsServiceAutoConfiguration`**, or Boot invents an in-memory
  user and logs its password at every start. The studio has no `UserDetailsService` either — Boot's
  in-memory user backs off once an OAuth2 client registration exists — and `MgmtApplicationTests`
  asserts zero. Nothing in either application stores a person.
- **Startup work belongs to the studio.** `PlatformBootstrap` (`ApplicationReadyEvent`, switch
  `pacod.bootstrap.enabled`) builds the control-plane indexes, re-provisions every out-of-date
  website and, on a platform with **no** websites, creates the demo websites in an organisation of
  their own — `demo` (`pacod.bootstrap.demo.{enabled,id,name,language,hosts}`) and, in the first
  one's organisation, `minimal` on the `minimal` template set (`pacod.bootstrap.demo.minimal.{enabled,
  id,name,hosts}`) — each on its own, with the demo content unless `pacod.bootstrap.demo.content=false`
  (*The demo content*); `IdentityLifecycle` starts the
  conversation with pacod-id (*Sign-in and roles*). Each step fails on its own at ERROR and the next
  start retries. **The site seeds nothing and builds no index** — it only serves websites that exist.
- **A website's starting content is `SiteSeedStep`s** (`core.seed`), run by the provisioner with the
  website bound, in order: `LanguageBootstrap` (10, `seed/languages.json`) and `PageBootstrap` (20,
  `seed/pages.json`) while their collection is empty, `SiteBootstrap` (30, `seed/site.json`, settings
  and each menu while absent), the studio's `BannerSkeletonBootstrap` (40). Each writes **only what is
  absent** — `reconcile()` reruns them and must never overwrite an edit. The JSON is on core's
  classpath: **an edited seed reaches nothing until the studio image is rebuilt**, and an existing
  website only through `make reseed-pages`. **Sample articles are not a seed step** (*The demo
  content*): a step that wrote them "while the collection is empty" would refill a website whose
  operator had just deleted them.

### Tenancy

One database per website, `site_<id>`, plus the control plane in `spring.mongodb.database`
(`pacod_cms`: `organisation`, `website`, `domain`, `platformSettings`, `deletedWebsite`; a `user`
collection left by Phase 2 is read by nothing). A website's files are under `${pacod.storage.root}/sites/<id>/`; its cache entries carry
its id.

- **`TenantContext` is a `ScopedValue<Tenant>`** (`core.tenant`; site id, organisation, database,
  default language — `null` meaning the platform's — and time zone), bound per request by the site's
  Host or the studio's path, and by whoever runs background work. It cannot leak into the next
  request on a pooled thread, and **it is not inherited by other threads**: work handed elsewhere
  captures the `Tenant` and binds it again with `runAs`/`callAs` (`CrawlerRecorder.record` does, for
  the DNS verifier's thread). `current()` **throws** where none is bound — a default website is how
  one tenant's data lands in another's database; `find()` is for code that runs both ways.
- **`RoutingMongoDatabaseFactory.getMongoDatabase()` answers with the bound website's database, and
  throws without one.** `MongoTemplate` asks on every operation, repositories and `@DBRef`
  resolution included, so that one override routes everything — **never hold a `MongoDatabase` in a
  field**. `MongoConfig` **replaces Boot's `mongoDatabaseFactory` bean after creation** in a
  `BeanPostProcessor`: declaring a factory bean makes `MongoAutoConfiguration` back off from creating
  the `MongoClient`. The control-plane factory is deliberately **not** a bean, or something taking a
  factory by type could write content into it.
- **The control plane is reached only through `controlPlaneMongoTemplate`**, by the repositories in
  `core.control.repository` (`@EnableMongoRepositories(mongoTemplateRef = …)`); the applications'
  own `@EnableMongoRepositories` is `core.repository`, content only. `@EntityScan` over `core.data`
  and `core.control.data` fixes the mapped documents, so **index creation no longer depends on which
  document has a repository**.
- **`Website`** (`core.control.data`): id `[a-z][a-z0-9-]{1,39}` — database suffix, folder and URL
  segment, so it **never changes** (`name` is what readers see); `dbName`; `defaultLanguage` (field
  default `en`); `timezone`; status `PROVISIONING`/`ACTIVE`/`SUSPENDED`/`DELETING`; `schemaVersion`;
`createdBy` (the creator's `usr_…`, null for the platform). **`Domain`**:
  the normalised host (lowercase, no port or trailing dot) is the `@Id` — a second claim is a
  duplicate key, never a takeover — one `primary` per website. **`Organisation`** owns websites and
  is the scope `organisation:<id>`; **its id is generated** (`Organisation.newId()`, `org_` + 32
  hex), since pacod-id's organisation ids and slugs are one namespace across products — a typed id
  could claim, or be refused as, another product's. Pre-Phase-3 ones (`demo`, `default`) keep theirs.
  It records `createdBy` (what the per-person limit counts) and `aiEnabled`. The portal creates
  both and edits domains (*The portal*); `make site-create` still works without a browser.
- **`TenantDirectory` caches in Caffeine**: host → tenant 60 s, an unknown host 5 s (a scanner costs
  one lookup per host), site id → tenant 60 s. Only `ACTIVE` websites answer at their hosts; by id
  the studio reaches any. `active()` reads fresh, empty on failure. `evictAll()` clears **this JVM
  only**: the site sees a new host within 5 s, a changed one within 60.
- **`SiteProvisioner`.** `create(NewWebsite)` refuses an existing id, an id with a `DeletedWebsite`
  tombstone (deleted minutes ago, below) or a claimed host before writing anything; a null `organisationId` creates a new organisation (generated id, `organisationName`),
  an explicit one must exist or the request is refused; then it saves website and domains (the
  first primary) and provisions. `provision()` runs, with the website bound,
  `SiteIndexes.ensureSiteIndexes()` and every `SiteSeedStep` in `@Order` (a failed seed is logged, not fatal), makes it `ACTIVE`, and **records
  `SCHEMA_VERSION` only when every index was built**, so `reconcile()` (every studio start) retries
  what failed; it skips `DELETING`. **Raise `SCHEMA_VERSION` when a release needs every website
  re-provisioned** (a new index, a new seed); nothing else re-runs them. MongoDB creates a database
  on first write.
- **`SiteRetirement` deletes websites in an order that leaves nothing to write back.** `retire()`:
  status `DELETING`, domains released, directory evicted (the website stops answering — on the site
  within the 60 s host cache; `ScopeSync` leaves it alone). The studio then deletes its pacod-id
  scope, with every role and invitation there — **if that fails the website stays `DELETING`** and
  deleting again finishes it: a new website of the same id must never find the old one's roles.
  `erase()` (refused unless `DELETING`): tombstone, `dropDatabase`, the storage folder, the document.
  **MongoDB recreates a database on its first write**, and the site may still write from its caches,
  so `sweep()` drops every tombstoned database again — `SETTLE` (3 min) after the delete on a virtual
  thread, and at every studio start — and forgets the tombstone once older than `SETTLE`; a tombstone
  whose id exists again is forgotten undropped. It never drops a database it did not delete.
- **`SiteIndexes` replaced `MongoIndexBootstrap`.** Each declared index is attempted on its own and a
  failure logged at ERROR — **that index then does not exist** (queries scan, a failed unique index
  enforces nothing); an unreachable server abandons the run. Website indexes go into the bound
  website's database, control-plane ones (entities under `io.pacod.cms.core.control.`) into the
  control plane. `spring.data.mongodb.auto-index-creation` stays `false`: Spring Data's creator runs
  during refresh, aborts startup on one unbuildable index, and builds into whatever database answers.
- **`SiteStorage`**: `app.image.folder`/`app.banner.folder` are templates carrying `{site}`, resolved
  for the bound website on every write (one without it is used as written; the unit tests rely on
  that). `siteRoot(root)` is `<root>/sites/<id>`, laid out like the public addresses.
- **`TenantScopedCacheManager` wraps the site's caches**, qualifying every key with the site id
  (`TenantKey`) — at the `Cache` level, not as a `KeyGenerator`, because every `@Cacheable` has an
  explicit SpEL key (`'all'`, `#id`) and **an explicit key bypasses the generator**. `clear()` removes
  only the bound website's entries (what `allEntries = true` must mean here), refusing a cache it
  cannot walk rather than clear everyone's. Nothing bound: every operation throws.
- **`PerSite<T>` keeps in-memory state per website** — the buffers of `SiteMetricCollector`,
  `BannerImpressionCounter` and `CrawlerRecorder` — filled under the request's binding and drained
  by `forEach` **with each website bound again**, so a scheduled or shutdown flush writes each
  website's counts into its own database, one failure not stopping the others. Nothing bound:
  `current()` is empty plus a WARN at most once a minute. **A deleted website's slot is dropped,
  not flushed** (`skipWhen`, fed by `SiteLiveness.isGone`, injected into the three writers by an
  optional `@Autowired` setter so hand-built tests need none): its first write would recreate the
  database. `SiteLiveness` reads the control plane fresh — a cached "still there" is the bug — and
  answers "not gone" on a read failure; `SiteLifecycleIT` flushes after a delete and checks.
- **More per-site details.** `VisitorIdentity` hashes the site id in. `TagService`'s nightly rebuild
  loops over `TenantDirectory.active()` and runs in the site (`app.scheduler.tags.enabled`,
  `TAGS_REBUILD`, on in dev and prod; the site is the application with `@EnableScheduling`).
  **Deferred**: ids are still max-plus-one per website database (*Persistence*); bucket ids follow
  the JVM's zone.
- **`HostSiteResolverFilter` (site)** binds the website by `request.getServerName()` (after
  `ForwardedHeaderFilter`: the host the reader typed). `TenancyConfig` registers it at **-120**, ahead
  of Spring Security (-100) and `CrawlerTrafficFilter` (-110), on `REQUEST`, `ERROR` **and** `ASYNC`
  — the error dispatch runs after the request's binding ended, and needs one to render the website's
  chrome. **An unknown host is a plain-text 404, "No website answers at this address."**, written by
  the filter (the error page needs a website), never a default website — how one site's pages get
  indexed under another's name. Exempt (`UnboundPaths`, shared with the preview, print and crawler
  filters, so none of them meets a request with no website bound): `/web/v1/health`, `/site-assets/**`
  and `/_internal/tls-ask`, which Caddy asks with its own Host (*Docker*).

### Site identity is data

- **`SiteSettings`** (`core.data`, collection `siteSettings`, one document, id `site`): names,
  taglines, descriptions and footer notes as `TextItem` lists; `baseUrl`; light and dark logos,
  favicon, share image; contact address, phone, email; social profiles; `gtmId`, `googleCseCx`,
  `facebookAppId`, `googleSiteVerification`, `bingSiteVerification`.
- **`Menu`** (collection `menu`, ids `main` and `footer`) of `MenuItem`s: per-language `labels`, a
  `MenuLinkKind` (`NONE`, `HOME`, `LATEST`, `CATEGORY`, `PAGE`, `SEARCH`, `SOCIAL`, `URL`) and a
  `target`; groups nest one level. `MenuItem` has **no field called `id`** on purpose (see
  *Persistence*).
- **`SiteChromeReader`** (site) caches both under `CacheConfig.SITE` and degrades an unreadable
  database to empty settings and no menus. **`SiteChromeAdvice`** puts `${site}`, a
  **`SiteChrome`**, into every template controller's model *and* `BasicErrorController`'s — scoped
  by `assignableTypes`, not package, because the error controller is outside the application's
  packages and the `/web/v1` JSON controllers need no chrome. **A new template controller goes
  into that list** (`IssueController` and `IssuePrintController` did), or its pages render without
  `${site}` and `${theme}`. It never throws: a failure inside an
  error dispatch re-enters the error dispatch, so it falls back to `SiteChrome.empty(origin)`.
- **`SiteChrome` validates every third-party id against its vendor's shape** and returns null
  otherwise; GTM (script and `noscript`), the Facebook SDK and `fb:app_id`, the verification metas
  and the Google search screen render **only for a non-null value**. A fresh install loads no
  tracking — a page carrying another website's container is worse than one carrying none.
  `PublicTemplateRenderTest.thirdPartyIdsRenderOnlyWhenConfigured` pins it.
- **`SiteChrome` builds every menu address** from kind and target — `/list/{id}` only for a numeric
  id, `/p/{slug}` only for a slug, a `URL` only if `SocialTargets.isValidTarget` — and adds
  `?lang=`. An item whose label resolves to nothing or whose address is refused is dropped. Absolute
  URLs (`og:url`, `og:image`, JSON-LD) come from `baseUrl`, or the request's origin while it is
  blank. **`SiteSettings.ownHosts()`** is `baseUrl`'s host with and without `www.` — what an
  absolute link must name to count as the website's own.
- **`SiteBootstrap` seeds both from `seed/site.json`**, which describes the demo: **a website whose
  name is not one of the seed's names gets its own name and a "© name" footer note** (in its default
  language), the seed's icons and logos, and nothing else — no taglines, contact details or `baseUrl`.
  Menus are generic and always seeded, and **link no category** (`genericMenusLinkNoCategory`): a
  website without demo content has none, and the old "Sections" group pointed every new website at
  the retired sample data's ids. Menus are edited at `/mgmt/design/menus/{main,footer}`
  (*The design screens*); **no studio screen edits the settings yet**: use `make mongo SITE=<id>`.

### The content's original language

`LanguageChain.original()` (core) is the language the content is written in: the last resort when
resolving translated text, the one every page body is guaranteed to have, the legally governing
text of a legal page, and the language of an article's scalar `title`, `text` and `description`
(translations live in `titles`/`texts`/`descriptions`). It is **the website's own setting**,
`Website.defaultLanguage`, read through the bound `Tenant` on every call (a static, because the
documents that resolve text are not beans). Only where no website is bound — startup, code that is
about no website — does it fall back to `pacod.content.default-language` (default `en`), set once by
`ContentLanguageConfig`; an unusable value fails the start.

- **It is chosen when the website is created** (`make site-create LANGUAGE=de`; the demo's
  `pacod.bootstrap.demo.language`) and changed in the control plane's `website` document, seen
  within the 60 s directory cache. Nothing in compose carries it.
- **Never write a language code where `original()` is meant — and that now includes the property.**
  `ArticleWebController`'s `/web/v1` handlers still default `?lang` to
  `${pacod.content.default-language:en}`, the platform's default, not the website's; resolve at
  runtime instead, as `LocaleConfig`'s `CookieLocaleResolver` does with a default-locale *function*.
- **The test suites run with Bulgarian as the original on purpose.** `OriginalLanguageExtension`
  (in core's, the site's and the studio's test trees, registered through `META-INF/services` and
  `junit.jupiter.extensions.autodetection.enabled`) sets `bg` before each test and restores it
  after. With an English original, `original()` and `LanguageChain.FALLBACK` are one code and two
  rules give the same answer. A test about the English default calls `LanguageChain.useOriginal`
  itself. That is why fixtures are full of Bulgarian text: it is the language under test.
- **`TestTenantExtension`** (beside it in each module, same registration) binds the website `test`
  (`site_test`) around every test, `@BeforeEach` and `@AfterEach`, as a request always is. It **names
  no language on purpose**, so `OriginalLanguageExtension`'s `bg` still governs. A test about two
  websites, or none, binds its own with `TenantContext.runAs`; the innermost binding wins.

### Template sets, layouts and themes

A website's public pages render with a **template set** (`Website.templateSet`, bound as
`Tenant.templateSet`, default `editorial`), are **composed** from a stored layout of sections per
page type, and are styled by a **theme**. The website edits data; a set is code an operator
installs — there is no upload, because Thymeleaf is code.

- **A set has two halves, and is used only with both**: templates under the site's
  `templates/sets/<id>/` and a manifest at core's `design/sets/<id>.yaml` — its name, the parent it
  `extends`, the section types it implements, per page type its regions and default layout, and
  its theme presets. `TemplateSetConfig` logs a half-set at ERROR and leaves it out; a website
  whose set is missing renders with the default rather than failing.
- **One `SpringTemplateEngine` per set** (`TemplateSets.engine`, the one factory the application and
  the render tests share), its resolver chain the set, then each parent (`TemplateSetCatalog.chain`),
  then the shared `_base`: Thymeleaf caches parsed templates per engine *by name*, so two sets in one
  engine would share whichever `article` was asked for first. `minimal` extends `editorial`,
  overriding the page templates, `sections.html` and `fragments/stylesheets`.
- **`TemplateSetViewResolver` is the bean `thymeleafViewResolver`**, so Boot's backs off, and
  **implements `ViewResolver` directly and caches nothing itself**: a caching resolver keys its cache
  by view name and would hand the first website's `article` to every website. Each set's own
  `ThymeleafViewResolver` caches inside that set. `TemplateSetIsolationIT` interleaves two sets.
- **Boot's whitelabel page is off with `spring.web.error.whitelabel.enabled=false`** — Boot 4's
  name; `server.error.whitelabel.enabled` is silently ignored. Boot looks for
  `templates/error.html` at the classpath root, finds none now that every set has its own, and
  would answer every error with its whitelabel view.
- **The site's message bundles are scanned with `classpath*:`** (`MessageBundleTest`): `classpath:`
  resolves one root, the test tree's, and silently checks nothing of the jar.
- **Page types** (`PageType`): `HOME` (no content of its own), `ARTICLE`, `CATEGORY`, `LATEST`,
  `PAGE`, `SEARCH`. **Section types** (`SectionType`, a closed vocabulary every set implements or
  skips): `primary` (the page's own content, no settings), `article-list` (source latest, category
  or most-read; count, offset, style, heading), `rich-text`, `call-to-action`, `search-box`,
  `social-links`. Each declares its `SettingField`s; `SectionSettings` validates and normalises
  against them, the one check the studio, the resolver and (later) design assist share.
- **A `Layout` (collection `layout`, id the page type's name) holds a `draft` and a `published`
  map of region → sections**, stored per website and page type, *not* per set, so a layout survives
  a template switch. Null means "the set's default".
- **`LayoutResolver` never drops; it reports.** What the set cannot render — a region it lacks
  (`region-missing`), a type it does not implement (`type-unsupported`) or does not know
  (`type-unknown`), settings that fail (`settings-invalid`), a `primary` where none belongs or twice
  — is left out of the result and returned as an `Issue`. A page with content of its own that has
  no `primary` gets one at the end of the first region (`primary-missing`), so an article never
  renders without its article. The site renders the result; the studio shows the issues; the
  template switch reports them before it is applied.
- **`PageComposer` (site) turns the resolved layout into `Regions` of `RenderedSection`s**, looking
  up each section's data itself (`CategoryWidgetReader`), each lookup isolated: a section whose data
  fails is left out and logged, and the page renders. **A fragment never queries anything**, and a
  fragment name comes only from the `SectionType`, never from request data. Every page template
  loops its regions over `sections.html`'s fragments, rendering `primary` inline.
  `LandingPageService` is gone: the home page is a layout like any other.
- **Restricted-mode SpEL forbids `new`**, so anything a template would construct (an
  `article-list`'s computed heading) is computed in `PageComposer`.
- **A theme (`Theme`, one document, id `theme`) is a draft and a published set of token values plus
  each one's own CSS.** The tokens are a shared vocabulary (`ThemeToken`: eleven colours, each with a
  dark value for `.night`, three fonts from an allow-list, two lengths with bounds), so a theme
  survives a template switch; `site.css` reads them as `--accent`, `--highlight`,
  `--font-display`/`--font-text`/`--font-label` and the rest. `ThemeCss` renders them as custom
  properties and appends the CSS, with `@import` removed and `expression(`, `behavior:`,
  `-moz-binding`, `javascript:`, `vbscript:` and `</` neutralised, capped at 50,000 characters —
  defence in depth, since a website's owner already runs script of their own. Presets are the manifests' `themes`.
- **`/theme.css?v=<hash>` is an external `text/css` sheet**, never inline (nothing in it can close a
  `<style>`), `nosniff`, immutable for a year at its current version and a minute at any other
  address; linked from `fragments/head` after the set's own sheets (`fragments/stylesheets`), and
  only when something is published. `social.html` links it too.
- **Layouts and the theme are cached in `CacheConfig.DESIGN`** (60 s, per website, like the
  chrome), so a published change is live within a minute. **Drafts are never read through it**
  (*The draft preview*).

### The brand guard

`scripts/brand-check.sh` greps every text file git would consider for tokens in the git-ignored
`.local/brand-tokens.txt` (one extended regex per line, case-insensitive). The list is deliberately
not in the repository — a guard naming every word it forbids would be the reference it exists to
remove — so **never copy a token into a file, commit message or test**. Without the file the check
says it skipped. `make verify` runs it first (`make brand-check` on its own). Fixtures use fictional
names (`acme2`, `globex-top`, "Demo Journal", `example.com`, `alpha.test`).

### The studio's two kinds of address

The studio has a host of its own (Caddy's `STUDIO_HOST`). **A website's screens are
`/sites/<id>/mgmt/…`; the platform's** — sign-in and sign-out (`/mgmt/login`, `/mgmt/logout`, and
pacod-id's round trip under `/mgmt/oauth2/`), the website list `/mgmt/sites` and a new website
`/mgmt/sites/new`, the organisations `/mgmt/organisations/**`, the installation `/mgmt/platform/**`,
the language switch `/mgmt/ui-language/**` — **answer with or without a prefix** (`StudioPaths`):
nothing on them reads website content. Their controllers carry `@PlatformScreen`,
`@OrganisationPermission` or `@PlatformPermission` (*Sign-in and roles*). A new platform path goes
into `StudioPaths`' lists, or `needsSite()` silently sends it to the website list.

- **`StudioSiteFilter` presents `/sites/<id>` as the context path.** `/sites/demo/mgmt/articles`
  reaches the application as `/mgmt/articles` under context path `/sites/demo`, with `demo` bound
  for the whole request. The context path is what MVC strips before matching, what Security's
  matchers compare against, what `@{…}` prefixes and what `redirect:` is relative to — so **every
  mapping, matcher, link and redirect still says `/mgmt/…`** and gained the prefix without a rewrite.
- **`StudioSiteConfig` registers it by hand at -120**, after `ForwardedHeaderFilter` (the Referer
  check needs its host and scheme) and before Spring Security (-100), on `REQUEST`, `ERROR`,
  `ASYNC`: as a `@Component` it would land after security, where every matcher would see
  `/sites/<id>/mgmt/…` and match nothing.
- **The error dispatch is re-wrapped** from `jakarta.servlet.error.request_uri` — the container
  dispatches `/error` with the original request, not the wrapper, and the error page would otherwise
  lose the prefix on every link.
- **Cookie paths filed under the virtual context path are written back to the real root**
  (`addCookie` and raw `Set-Cookie` alike; `StudioSiteFilterTest`). It was written for remember-me,
  which is gone; it stays as the net for any cookie that defaults its path to the context path —
  under `/sites/<id>` such a cookie would reach one website only. CSRF and `mgmt-lang` pin `/` anyway.
- **An un-prefixed website screen 302s to `/mgmt/sites`** (`StudioPaths.needsSite`), never to a
  guessed website; `/sites/<id>` and `/sites/<id>/` go to its dashboard; an invalid id is never looked
  up, an unknown one is a 404. Anonymous inside a website, sign-in is `/sites/<id>/mgmt/login`;
  signing in lands on **`/mgmt/sites`** unless a saved request says otherwise. pacod-id's callback
  is always the un-prefixed `/mgmt/oauth2/code/pacod-id`; no test yet follows a sign-in started
  inside a prefix all the way back.
- **`StudioSites`**: the website list (read fresh) and the current website (cached 30 s);
  `SiteView.publicUrl` is `pacod.site.public-scheme` + primary host + `pacod.site.public-port`, null
  with no host. **An unreadable record still yields a current website**, built from the bound tenant
  — hiding the navigation would strand the operator. `MgmtLocaleConfig.postHandle` sets it as
  `currentSite` for view-rendering responses only; the filter sets `studioRoot`.
- **`StudioMediaController` serves a website's pictures to the studio's screens**: `/assets/img/**`
  and `/resources/banners/**`, JPEG/PNG/GIF/WebP/AVIF only, `nosniff`, a sandboxing CSP, private
  cache, inside the authenticated chain (matcher `/mgmt/**`, `/assets/**`, `/resources/**`). **Never
  HTML, script or SVG**: a website's HTML5 banners on the studio's origin would run with the studio's
  cookies — why the studio has a host, and in production a registrable domain, of its own. Templates
  write `@{${path}}`, carrying the prefix; **an un-prefixed `/assets/img/…`** (article bodies in
  TinyMCE, the library's JSON) takes its website from a **same-origin `Referer`**, and nothing else.
  It needs `content.read`; until Phase 4 it carried no annotation and the fail-closed interceptor
  refused every signed-in preview while the anonymous test passed — hence
  `HandlersDeclarePermissionsTest` and `StudioSiteRoutingTest.previewsFollowContentRead` (404 for a
  missing file means the permission passed).
- **`MgmtSitesController`** (`/mgmt/sites`) lists the websites the person `touches`, with their
  organisation, and offers "New website" (`/mgmt/sites/new`, *The portal*).
  **`CreateSiteCommand`** (`--create-site --site-id --site-name --hosts [--language --timezone]
  [--org-id=org_… | --org-name] [--template-set] [--demo]`) is what `make site-create` runs in a one-off studio container,
  without a web server, bootstrap or pacod-id (`--pacod.id.enabled=false`); without `--org-id` (make's
  `ORG=`) the website gets a new organisation named `--org-name`, else the website's name.
- **`webAppContextSetup` MockMvc runs no servlet filters of its own accord**, so every other studio
  test reaches the controllers with `TestTenantExtension`'s website bound and the routing bypassed.
  `StudioSiteRoutingTest` adds the real filter ahead of `springSecurity()` and is the only test of
  the two together; `StudioSiteFilterTest` covers the filter alone.

### Sign-in and roles (pacod-id)

pacod-id owns accounts, sessions and role bindings; the studio owns none. **Tokens carry no
roles**: sign-in says who someone is, pacod-id's feed what they hold where, and `StudioAccess` — the
only place a permission is decided — evaluates that with the rules engine against `authz/cms.yaml`.

- **The rules engine is a Java port** (`core.identity.authz`) of pacod-id's: `Manifest`, read as
  **YAML 1.2** (`Yaml12`, snakeyaml-engine — Spring's SnakeYAML 1.x reads YAML 1.1, where
  `implicit: yes` is a boolean the other ports refuse), and `Catalog` (`allowed`, `allowedSomewhere`,
  `hasRole`, `holdsAnyAt`, `touches`, `canGrant`, `checkProtected`). **pacod-id's vectors are the
  specification**, vendored verbatim under core's `src/test/resources/authz/engine-vectors/`
  (`VENDORED.md`) and run by `AuthzEngineVectorsTest` down to the order of `Manifest`'s checks and
  `ManifestException.code()`: a failing new check changes the port, never the vectors. The members
  screens use `canGrant`; `checkProtected` still has no caller (pacod-id enforces `last_holder`).
- **`authz/cms.yaml`** (its header lists every permission): scopes `platform > organisation >
  cms.website`; `platform_admin` (`*`, protected), `provisioner` (platform, `members.manage` only,
  granted only with `platform.manage`: the studio's service account that makes an organisation's
  creator its `org_admin`, *The portal*), `org_admin` (protected, bypass `platform.manage`),
  `website_admin`, `designer`, `editor`, `author`, `analyst`; other grants need `members.manage`.
  **No role is implicit**: an implicit role is held at the platform by every account, so one
  carrying its grant permission would grant everywhere. `CmsManifestTest` pins that and runs
  `authz/vectors/cms.yaml`, what each role may do; change the two together.
- **Every website screen declares its permission** (`studio.access`): `@StudioPermission(read,
  write)` on the controller, a method's replacing the class's (`/mgmt/social/stats` is `stats.read`
  in a `content.*` controller); GET/HEAD need `read`, other methods `write` (default `read`), local
  names checked at `[platform, organisation:<org>, cms.website:<id>]` from the bound `Tenant`.
  Content screens are `content.read`/`.write`, banners and layouts `ads.manage`, monitoring,
  crawlers and social stats `stats.read`, the dashboard `website.read`, tools and settings
  `website.manage`, members `website.read`/`members.manage`, the previews `content.read`.
  **Deleting content is `content.delete`** (method-level on the article, translation, page,
  page-translation, image, author, category, language and social-link deletes), and **publishing is
  `content.publish`**: without it a save keeps an article's move into or out of `PUBLISHED`, and a
  page's or `/social`'s `published`, as stored (`save(…, mayPublish)` in the three services), and the
  control renders disabled with a hint — so an `author` writes and an `editor` also publishes and
  deletes. `@PlatformScreen` marks sign-in, the website list and new website, organisations' list
  and create, the language switch, health and `MgmtHomeController`; **`@OrganisationPermission`**
  checks at `[platform, organisation:{orgId}]` from that path variable (refused when the mapping has
  none) and **`@PlatformPermission`** at `[platform]` — both checked first, so a `@PlatformScreen`
  on the same class cannot wave them through. **`HandlersDeclarePermissionsTest`** asserts every
  studio handler declares one of the four.
- **`PermissionInterceptor` fails closed**: a handler in `io.pacod.cms.studio.` with neither
  annotation is a 403 plus an ERROR log — a forgotten annotation must be a refusal someone reports;
  handlers outside that package (the error controller) are not checked. It runs **last**
  (`MgmtLocaleConfig.addInterceptors`: text resolver, which the refusal page needs, presence,
  `IdentityGuardInterceptor`, then it). **The website list shows what the person `touches`** (a
  binding at, above or below the website); listing is not opening. A role-less person sees nothing.
- **`IdentityGuardInterceptor`**: a session pacod-id ended (the ID token's `sid`, revoked in the
  feed) or a disabled or deleted account → session invalidated, 302 to `/mgmt/login?ended`. No
  snapshot yet, or pacod-id's last answer older than `pacod.id.max-staleness` (60 s) → **503**
  (`StudioUnavailableException`): roles may have been taken away since, and a studio deciding with
  them would keep granting what was revoked. **Revoked `sid`s are kept as long as a studio session
  may sit idle** (`server.servlet.session.timeout` + 5 min, at least pacod-id's 15-minute window) and
  **survive a snapshot reload** (the snapshot lists only recent ones): pacod-id says a session ended
  once, and an idle session must still meet it. Refusals
  render `templates/error.html` (not under `mgmt/`; copy for 403, 404, 503, other), which reads only
  `${status}`, `mgmtText` and `studioRoot` and links back to the website list.
- **Sign-in (`MgmtSecurityConfig`) is authorization code with PKCE** (`requireProofKey`; pacod-id
  demands it of confidential clients too) through a **hand-built `ClientRegistration`, no
  discovery**: pacod-id answers its browser endpoints only on the issuer's own Host, which the
  compose network is not. Authorize and end-session are on the public issuer (`pacod.id.issuer`),
  token and JWKS on the internal listener (`pacod.id.internal-url`, `http://pacod-id:8071`), `issuerUri`
  set for the `iss` check; scopes `openid email profile`, no user-info call.
  - **Every OAuth endpoint sits under `/mgmt/oauth2/`** (`authorization/pacod-id`, `code/pacod-id`):
    Spring's `/oauth2/**` and `/login/oauth2/**` fall outside the chain's matcher, into the
    permit-all chain that signs nobody in, and outside `StudioPaths`' platform screens.
  - **Redirect and post-logout URIs are absolute, from `pacod.id.studio-origin`**, never
    `{baseUrl}`: under `/sites/<id>` the context path is virtual, and pacod-id's redirect URIs are
    exact. A sign-in started on another origin (the direct `:7070`) therefore cannot complete.
  - `?signup` adds `prompt=create`; `ui_locales` is the UI language. **Sign-out ends pacod-id's
    session too** (`OidcClientInitiatedLogoutSuccessHandler`, `id_token_hint`); one done elsewhere
    arrives through the feed. `StudioPrincipals.displayName` (email, else name, else `sub`) is
    `currentUser` and presence's key.
  - **Staying signed in is pacod-id's job**: `MGMT_SESSION_TIMEOUT` (8h) bounds the studio's idle
    heap session, which also dies with the container; the next request goes through pacod-id, whose
    own session signs the person back in without a password. That replaced remember-me.
- **`studio.identity` follows pacod-id** on virtual threads started by `IdentityLifecycle` at
  `ApplicationReadyEvent`, **never blocking startup or `/mgmt-health`**: a pacod-id that is down
  costs the screens (503), not the container. `IdentityConfig` creates them only while
  `pacod.id.enabled` (off in the tests and the `--create-site` run); without them `FeedSubjects`
  gives everyone no bindings, so sign-in works and opens nothing. `IdClient` is the service client
  (`pacod-cms-service`, HTTP Basic on `/internal/v1`; pacod-id's error shape → `IdException(status,
  details.code)`; 40 s read timeout, above the long poll).
  - **`AuthzFeed`**: `GET /internal/v1/authz/snapshot?product=cms`, then a loop of
    `…/changes?after=<seq>&wait=25` with the answer's **top-level** `seq` as the next `after` (other
    products' events are skipped, so it can be ahead of the last event's); 410 reloads the
    snapshot; errors back off 1 → 30 s. It holds bindings by principal, disabled and deleted
    accounts, revoked `sid`s, scopes; `staleness()` counts from the last answer, empty polls
    included. **Deletions are confirmed** (`POST /internal/v1/deletions/<sub>/done?product=cms`, for
    the snapshot's pending list and each event, people only): the catalogue push enrols `cms`,
    pacod-id keeps a deleted account pending until every enrolled product confirms, and the studio
    keeps nothing else about a person. `awaitSeq(seq, timeout)` is the portal's read-your-writes.
  - **`CatalogPusher`**: `PUT /internal/v1/catalog/cms`, the manifest's shape-checked source as JSON
    (not the YAML text) with an empty `migrate`, retried until taken; 409/422 is an ERROR, not
    retried. Until it lands pacod-id accepts no `cms.*` binding; the bootstrap admin's waits for it.
  - **`ScopeSync`**, every 10 s after the push, PUTs what the feed's scopes lack or name
    differently: `/internal/v1/organisations/{id}` `{slug: id, display_name}` (both every time —
    pacod-id overwrites both, and an omitted slug becomes the id), then
    `/internal/v1/scopes/cms.website/{id}` `{parent, display_name}`. A reconciliation, not an
    outbox, so the `--create-site` run's websites are caught too; **it never deletes** (deleting a
    scope deletes every binding under it) and **skips `DELETING` websites**, whose scope is going.
    **pacod-id leaves out of the feed any binding at a scope it was not told about** — why the
    portal registers a new organisation or website at once rather than waiting for this round.
- **Tests never reach pacod-id**: the test profile sets `pacod.id.enabled=false` and dummy issuer,
  URL, origin and secrets (the registration must still build). **`TestSubjects`**, a `@Primary`
  `SubjectSource` in the studio's test tree, makes `@WithMockUser(roles = "MGMT")` a
  `cms.platform_admin` and anyone else nothing; `use(subject)`/`reset()` narrow it per thread.
  `StudioSiteRoutingTest` signs in with `oidcLogin()`. **`make e2e-signin`** runs the real thing: a
  self-registered account confirmed through mailpit sees no website and gets the 403 page; the
  bootstrap admin opens the demo, is signed back in by pacod-id after `docker restart cms-studio`, and
  signed out by the feed when pacod-id ends the session; a studio sign-out ends pacod-id's.
  **`make e2e-portal`** runs the portal on the stack (*The portal*); `make e2e` runs both, on dev
  and stage (they read mailpit; production refuses). Make hands them the studio's and pacod-id's
  origins and mailpit's address. They fetch those by name, never with a `Host` header on
  `localhost`: **Node's `fetch` drops a `Host` header**, and `localhost` is one of the demo
  website's hosts, so every probe would answer. A website of their own is probed with `node:http`,
  which does send the header.

### The portal

Creating organisations and websites and deciding who works on them, in the studio
(`studio.portal` services, `controller.Mgmt{Organisation,Sites,SiteSettings,SiteMembers,Platform}
Controller`, templates `organisations`, `organisation`, `site-new`, `site-settings`, `site-members`,
`platform`, `members` — the shared members card). **pacod-id applies every role change**; the studio
checks first only so its screens offer what would succeed.

- **Creating an organisation makes its creator its admin** (`PortalOrganisations.create`): the
  document (`createdBy`, `aiEnabled` from the platform settings), then **at once** the org scope PUT
  (`PortalIdentity`; the binding would be `scope_unknown` until `ScopeSync`'s next round), then the
  **provisioner** binds the creator `cms.org_admin` there, then `awaitSeq` on the higher `seq` (5 s;
  on timeout the flash says the role arrives in a moment). **If pacod-id refuses either step the
  organisation is removed again** — one nobody administers would count against its creator's limit
  and open to nobody.
- **The provisioner** is a pacod-id service account holding `cms.provisioner` at the platform, made
  by `make id-provisioner` (`scripts/id-provisioner.mjs`, run by `make up`): it signs in as the
  bootstrap admin through the hosted pages' API, uses `/ui-api/v1/admin` (writes need the issuer's
  `Origin`), and writes `PACOD_ID_PROVISIONER_TOKEN`/`_ACCOUNT` into whichever of `.env-local` and
  `.env` already names the token, else `.env` (a new token beside an old one in the later file
  would be shadowed). **It binds the
  role before it mints a token** (a token whose binding then failed would be a live credential
  nobody recorded), retrying while pacod-id refuses `cms.*` roles because the studio has not pushed
  its catalogue yet — a fresh `make up` runs it right after the stack answers — and **revokes every
  other token of the account** once the new one exists. Rerun, it only checks the account still
  exists (a `make destroy` empties pacod-id, not `.env`); `ROTATE=1` issues a new token
  whatever. It recreates the studio whenever it wrote a token. **The `pidt_` token is read by `Provisioner` alone**
  (`pacod.id.provisioner-token`, never `IdProperties`, which half the studio injects), whose one
  method binds `org_admin` at an organisation; `binding_exists` is success. Without a token the bean
  is absent and only platform admins create organisations.
- **Who may create**: platform admins always, unlimited; anyone else while `PortalSettings.orgCreation()`
  is `SELF_SERVICE`, up to `maxOrgsPerUser` (counted by `createdBy`), and only with a provisioner.
  `PortalOrganisations.allowance()` says why not, and the list shows that sentence instead of the
  form. **`PlatformSettings`** (`platformSettings`, id `platform`) holds the policy, both limits and
  `aiEnabledForNewOrgs` (default **false**: every AI call spends the installation's one gateway key);
  `pacod.portal.*` are its starting values, except **`org-creation=platform-admins`, a ceiling** the
  installation screen shows disabled and cannot lift (a disabled control posts nothing, and `save`
  keeps the stored value for a null).
- **A new website** (`/mgmt/sites/new`, `PortalWebsites.create`): offered only in organisations where
  the person holds `website.create`, and **re-checked against the posted organisation** (a posted
  value), with `maxSitesPerOrg` (not for platform admins). Its first host is
  `<id>.<pacod.portal.site-domain>` (`PACOD_SITE_DOMAIN`; compose's default `localhost`), then the
  extra hosts; the website scope is registered at once; it lands on `/sites/<id>/mgmt/dashboard`
  through `PortalModels.toSite`, a `RedirectView` on `studioRoot` — `redirect:` from a prefixed
  request would nest one website's prefix inside another's. Handlers that may redirect *or* render
  return `ModelAndView`: **a handler returning `Object` is treated as a model attribute**, and the
  `RedirectView` never redirects. **Start with demo content** (`NewSite.demo`) is checked against the
  demo's languages **before** anything is created, and installed after (`PortalWebsites.addDemo`); a
  failure there is an `error` flash on the new website's dashboard, never an undo. The **template
  set** is chosen there too (offered while the installation has more than one), checked against
  `TemplateSetCatalog` first and passed to the provisioner (`NewWebsite.templateSet`).
- **`DomainRules`**: a host is lowercase LDH labels, at least two, no IP literal; **never the studio's
  or pacod-id's host or any name below them** (from `pacod.id.studio-origin` and `.issuer`) — a website
  answering there would be sent the studio's cookies — **nor any name on their registrable domain**
  (`portal.domain.sameSite`): from `www.example.com` a website's script can set cookies for
  `example.com` that `studio.example.com` receives. In development `demo.localhost` beside
  `studio.localhost` is fine: two registrable domains.
- **`RegistrableDomains`** is a host's public suffix plus one label, from Guava's copy of the Public
  Suffix List **private section included**, as browsers scope cookies (`a.github.io` and
  `b.github.io` are two); a name under no listed suffix (`*.localhost`, `*.internal`) gets the list's
  default rule, its last label being the suffix. Pinned on real entries by `RegistrableDomainsTest`.
- **`StudioDomainCheck` refuses to start the studio** when `<id>.<pacod.portal.site-domain>` would
  share a registrable domain with the studio or pacod-id; a site domain that is itself a public
  suffix (`localhost`; a tenant domain registered in the list's private section) passes, an IP
  literal origin is skipped with a WARN. Stored `domain` rows that share one are only **reported at
  ERROR** after startup (with `pacod.bootstrap.enabled`): refusing to start over data would take
  every website's studio down. **`pacod.portal.site-domain-shared`** (`PACOD_SITE_DOMAIN_SHARED`,
  off) accepts the risk for an installation whose websites are all trusted (pacod-demo on one
  domain): the refusal becomes a WARN, the stored rows' report WARN, and `DomainRules` drops its
  `sameSite` rule — never the `reserved` one.
- **A website's settings** (`/sites/<id>/mgmt/settings`, `website.manage`): name, content language,
  time zone, `ACTIVE`/`SUSPENDED`; domains added, made primary, removed (the next becomes primary);
  **deleting needs `org.manage`** — an organisation admin's decision, the website admin's role going
  with it — and the id typed (*Tenancy* for the order). A failed pacod-id call leaves it `DELETING`.
- **Members are a facade over pacod-id's admin API, acting as the signed-in person**
  (`PortalMembers`): their access token (`UserTokens`) is exchanged (`TokenExchange`, RFC 8693 as the
  service client, audience `pacod-id`; no `expires_in`, the result lives 60 s and is kept 50 s, keyed
  by a hash of the subject token) for one `/admin/v1` accepts, so **pacod-id checks every change
  against the person's own roles**. Locally the roles offered are those whose `at` includes the
  scope's kind and that `canGrant` allows; a crafted role is refused before pacod-id is asked;
  principals and invitation ids must look like ids (`usr_…`), never a path. Each `details.code` it
  answers has a sentence (`portal.members.error.<code>`); writes `awaitSeq` their `seq`. The card is
  one fragment for `organisation:<id>` (`/mgmt/organisations/{orgId}/members/…`) and `cms.website:<id>`
  (`/sites/<id>/mgmt/members/…`); inherited roles show and are changed where they are held.
  **Platform-wide roles never show**: pacod-id lists the bindings at the scope and below it, and
  above it only up to the organisation (`admin/members.rs`, "not the platform's").
  Invitations go by e-mail or, ticked, as a link the flash shows once. **pacod-id unreachable costs
  the card, never the screen.**
- **The person's tokens stay with their studio session** (`HttpSessionOAuth2AuthorizedClientRepository`,
  given to `oauth2Login` and the `DefaultOAuth2AuthorizedClientManager`), not Spring's default
  store keyed by account: **pacod-id rotates refresh tokens and ends every session of an account
  whose refresh token is used twice**, so two browsers sharing one, or two requests of one session
  refreshing at once, would sign the person out everywhere. `UserTokens` serialises refreshes per
  session id. A session that holds no usable token asks the person to sign in again.
- **The installation** (`/mgmt/platform`, `platform.manage`): the settings and every organisation
  with its AI switch. **The chrome asks `${can}`** (`StudioCan`, a request attribute like
  `mgmtText`): the Website group (Settings, Members) and the Installation link show only to who may
  open them, and `null`-guarded for the error dispatch.
- **Tests**: `PortalScreensTest` runs the real filter and chain with people holding real-shaped
  roles (no role, org admin, website admin, author, platform admin), renders every portal screen in
  Bulgarian and English, and **posts with a real CSRF token taken from the sign-in page** —
  `csrf()` picks its chain by the raw path, before the website filter strips `/sites/<id>`, so on a
  prefixed POST it fills the wrong repository and the 403 that follows passes any test expecting a
  refusal. `PortalOrganisationsTest`, `PortalWebsitesTest`, `PortalMembersTest`, `DomainRulesTest`,
  `TokenExchangeTest` (exchange and provisioner on the wire), core's `SiteRetirementTest`,
  `PerSiteTest`, and `SiteLifecycleIT`.

### The studio (management console)

`/mgmt/**` is server-rendered Thymeleaf (`io.pacod.cms.studio`, templates under `templates/mgmt/`)
autowiring the repositories directly; the browser calls nothing but the studio.
`MgmtHomeController`'s `@GetMapping("/mgmt/**")` catch-all 404s every unmapped GET, so the default
static resource handler can never serve a file under `/mgmt/`.

- **The package name is load-bearing.** `MgmtControllerAdvice` is
  `@ControllerAdvice(basePackages = "io.pacod.cms.studio.controller")`; a controller outside that
  package silently loses `currentUser`, `imageRules` and `bannerRules`. `MgmtSearch` and `MgmtSort`
  are package-private.
- **No real context path.** `server.servlet.context-path=/mgmt/` would re-run the trap under *Spring
  Boot 4 gotchas* across every mapping and `th:href`; the only context path is the virtual
  `/sites/<id>`.
- **Two filter chains (`MgmtSecurityConfig`).** `@Order(1)` matches `/mgmt/**`, `/assets/**` and
  `/resources/**` (the previews are a website's content): `IF_REQUIRED` sessions, **CSRF on**,
  `oauth2Login`; `/mgmt/login`, `/mgmt/ui-language/*` and the two OAuth bases permit-all, the rest
  authenticated. `@Order(5)` is permit-all and stateless, with no matcher, for what is left —
  `/mgmt-assets/**`, `/mgmt-health`, `/error` — not `denyAll`, which risks turning every error page
  into a 403; the narrowed scan is what stops anything else being served. `/mgmt/` answers **302**
  to `/mgmt/sites`, which anonymous answers 302 to `/mgmt/login` (two links to pacod-id).
- **The CSRF token lives in a cookie (`CookieCsrfTokenRepository`)**: `HttpOnly` (`mgmt.js` reads
  the `_csrf` meta tag), names pinned to `_csrf`/`X-CSRF-TOKEN` (the class defaults to
  `X-XSRF-TOKEN`), **path `/`** (the default is the context path, virtual under `/sites/<id>`, and two
  cookies of one name fail every POST), `SameSite=Lax`, a **fixed 30 days** so a page left open
  across a re-sign-in — the editor holding unsaved rich text — still posts; `Secure` follows
  `X-Forwarded-Proto`. Sign-out is a CSRF-checked `POST /mgmt/logout`.
- **In production both of the studio's cookies are `__Host-` cookies**: `__Host-XSRF-TOKEN`
  (`pacod.studio.host-cookies`, always `Secure`) and `__Host-studio-session`
  (`server.servlet.session.cookie.*`), both set in `application-prod.properties`. A browser keeps a
  `__Host-` cookie only if it is `Secure`, `Path=/` and names no `Domain`, so a website on a sibling
  subdomain cannot plant or overwrite one. The session cookie stays **`SameSite=Lax`**: Strict would
  stay behind on pacod-id's cross-site callback and lose the saved authorization request.
  Development, on plain http, keeps `XSRF-TOKEN` and `JSESSIONID` (a `Secure` cookie is never
  stored over http). `StudioCookieTest` runs a real Tomcat — MockMvc writes no session cookie.
- **Studio CSS/JS live at `/mgmt-assets/**`**, outside the authenticated matcher (the *login page
  styles itself*) and clear of the catch-all, registered by `MgmtAssetsConfiguration` (its Javadoc
  still blames the gone `static-locations`); inside a website the link gains the prefix and still
  works. `make smoke` probes `mgmt.css`, `tinymce.min.js` and the favicon on the studio host.
- **The favicon is `/mgmt-assets/favicon.svg`, declared in the head fragment**, or the browser asks
  the public site for `/favicon.ico`. No `prefers-color-scheme` query: it is drawn on the tab strip.
- **Forms apply, they do not replace.** `MgmtArticleService.save` loads the document and writes
  only the fields the form carries, so `titles`/`texts`/`descriptions`/`lang`/`titleClasses`/
  `suppressArticleInfo`/`counter` survive. The corollary recurs throughout: **a control with a
  `name` that does not belong to the form can silently wipe data.**
- **Creating an article is `POST /mgmt/articles`** → `ArticleService.createNewArticle()`; never
  `getArticleByIdWithCreate`, which persists a row on a GET.
- **Both article screens show the translations and neither binds them.** `Article.translatedIn()`
  feeds a `<tr class="lang-row">` band on `/mgmt/articles` and a rail card whose chips link to the
  translation screen and carry no `name=` (`MgmtScreenRenderTest` asserts it). It reads `texts` (a
  blank body is absent), **excludes `original()`** (the original lives in the scalar fields), and
  shows **codes, not names** (article languages need not be in the `language` collection). It is
  not `getTranslatedIn()`, so Jackson does not add it to every `/web/v1` response.
  The list's finders carry no `fields` projection, so the band costs no query — add one and the
  band disappears under that filter, at 200.
- **Translations are edited at `/mgmt/articles/{id}/translations/{lang}`, one language per
  screen**, reached from the language strip (`fragments :: articleLanguages`). **`TranslationForm`,
  never `ArticleForm`**: title, description and body; `saveTranslation` writes that language's
  entries only. Existing translations only — `original()` and absent codes 404; new ones come from
  translation jobs. **Blank is removed, never `""`** (`ArticleTranslations.put`; `getLocalizedText`
  does not test blank), the body is `@NotBlank` so an emptied editor cannot delete a translation
  (deletion is its own confirmed POST), and `put` copies into a new `ArrayList`.
- **Seven list screens have a `?q=` box; two search MongoDB.** Articles use `fulltextSearch`;
  `/mgmt/images` uses the *derived* `findByFilenameContainingIgnoreCase`, because Spring Data's
  `MongoRegexCreator` escapes metacharacters and a hand-written `@Query("{filename:/?0/i}")` does
  not. The small collections filter in memory through `MgmtSearch.matches`: every whitespace part
  must occur in *some* field ("Morgan Alex" finds "Alex Morgan"), the id is a field (as a substring)
  and every field is nullable. All reuse `id="search-input"`, which `mgmt.js` debounces and
  auto-submits — except the editor's image library (see *Images*).
- **`/mgmt/authors` and `/mgmt/categories` sort by link, not script** (`?sort=`/`?dir=`, so a sorted
  list is a bookmarkable URL with scripting off; authors default to the name, categories to the
  operator-chosen number). `MgmtSort` normalises. Each rule fails silently:
  - header links carry `q`; the search form carries `sort`/`dir` hidden (a GET form replaces the
    whole query string);
  - `sortKey`/`sortDirection` reach the model **normalised, never raw**, from an allow-list, never
    a property path — echo `?sort=nonsense` and the header lies;
  - nulls coalesce to `""` rather than `nullsLast`, so the directions are exact inverses, and
    every key ends with the id; comparison is by code point (`String.CASE_INSENSITIVE_ORDER`), the
    price of refusing a locale-dependent `Collator`;
  - the category article count comes from `countArticlesByCategory()`, read **once** and handed to
    `findAll`; an absent category defaults to 0; a failed aggregation is an empty map, never a throw.
- **`/mgmt/images` sorts on the server, over the whole collection** (it is paged), by
  `MgmtImageService.ImageSort`, shared with the editor's picker. Same link rules; headers drop
  `page`; the detail screen's back link carries `sort`/`dir` with the filters.

### Images

- **The upload rules are settings, and the two limits switch independently.**
  `app.image.required-width`/`-height`/`max-bytes`, `app.image.dimension-check.enabled`,
  `app.image.file-size-check.enabled` are `@Value` fields on `ImageService` with defaults (600, 400,
  128 KB, on, on) **in the annotations only** — every application and test profile scans the class,
  and a default-less `@Value` fails startup wherever the property is absent. `MgmtControllerAdvice`
  and `UploadSizeAdvice` **autowire the service**, so help text and rule cannot disagree. Set on
  `cms-studio` only (`IMAGE_*`), read at startup.
  - **Two flags because two rules**: pixels are layout, bytes are weight; all four combinations
    work, none special-cased (`ImageServiceValidationTest` pins the mixed pairs).
  - **The content-type, empty-file and `ImageIO.read() == null` checks are unconditional** ("is
    this an image at all"). The stored name is `ImageService.safeFilename` of the client's: the
    last path segment (backslashes count, a Windows browser may send a whole path), whitespace →
    hyphen (the name goes verbatim into every `src`), `.`/`..`/empty refused — a crafted `../../x`
    must not choose where the file lands. `ImageServiceFilenameTest` pins it.
  - **`app.image.max-bytes` is a `DataSize`**, so `2MB` binds; on a `long` the container would
    restart-loop. `ImageServiceValidationTest.limitsBindFromProperties` runs a context (with
    `ApplicationConversionService` installed by hand) because reflection tests cannot see it.
  - **Effective only up to the 4MB multipart ceiling** (Caddy and `spring.servlet.multipart.*`);
    past it Tomcat refuses below Spring with a bare 413. Raise all three together.
  - **Every sentence stating the rules follows the flags** (the editor hint, the detail screen's
    notes, `UploadSizeAdvice`'s flash), **assembled from clauses**, and "off" is not "unlimited":
    with the byte check off the hint names the server ceiling (`ImageRules.transportKilobytes`).
    `MgmtScreenRenderTest`'s nested `DimensionCheckOff`/`FileSizeCheckOff` pin them.
  - Declared in the studio only, the one application with an upload endpoint.
- **Order *is* the cover; there is no cover field.** The first **visible** image is the share card
  (`ArticleModel.getImage()`) and every thumbnail (`ListItem`); the rest are the gallery, in order.
- **`Article.hiddenImageIds`** keeps an attached image off the public site (`visibleImages()`, not
  `getVisibleImages`, for the serialization reason) — for an image used inside the body. Null means
  all visible, so nothing migrates; it cannot live on `ImageMedia`, which several articles share.
- **Deleting an image is reference-counted in `ImageService.deleteImage` (core)**, where its two
  callers meet: `MgmtArticleService.removeImage` and `ArticleService.deleteArticle`.
  **`deleteArticle` deletes the document *before* cleaning its images**, or the dying article counts
  as a reference and every deleted article leaves its files behind.
- **Everything on the editor that fires mid-article is a `fetch`** (`/mgmt/images/search`,
  `…/images/arrange`, `…/attach`, `…/upload`), since a redirect would discard unsaved rich text;
  everything else is a form POST and a redirect. `window.mgmtCsrf()` carries the token. **Every
  `fetch` URL is built on `MGMT`**, which `mgmt.js` reads from the head fragment's
  `<meta name="mgmt-base" th:content="@{/mgmt}">` — `/mgmt`, or `/sites/<id>/mgmt` inside a website.
  A literal `'/mgmt/…'` goes out un-prefixed, is sent to the website list, and `readJson` refuses
  the redirect.
  - **The upload's no-script half is the mapping**: `POST /mgmt/articles/{id}/images` stays the
    form's `action` and redirects, `…/upload` answers with the row, both through `addImage`.
    `mgmt.js` intercepts only a form with **`data-async`** (`banner-edit.html` reuses the
    `#image-form`/`#dropzone`/`#image-file` trio and wants the redirect). Files post **one at a
    time** (`getNextId()` is read-then-write); the redirect half binds `List<MultipartFile>` (a
    single `MultipartFile` silently takes the first part). **Saving is refused while an upload is in
    flight**, or the load-modify-write `save()` drops the new image.
  - **The library card sits inside `<form id="article-form">`**: no nested `<form>`, every button
    `type="button"`, Enter `preventDefault`ed, input `library-search` (not `search-input`), and
    **`data-no-dirty`**. Expanded through `makeOverlay` it moves *out of* the form, so nothing in it
    may have a `name`; `MgmtScreenRenderTest` asserts none does.
  - **The library is ordered by the server** (id, name or article count) and refetches from page
    one; `/mgmt/images/search` **echoes the ordering it applied**. Id ascending by default — an
    unsorted paged query has no defined order. `pageByUsage` reads every count in one
    `$unwind`/`$group` (key via `MgmtDbRefs.idOf`), orders in the JVM, then slices; an absent image
    is 0; the total is the candidate count, or "load more" vanishes early.
  - **Every `fetch` checks the response first**: an expired session answers 302 to the login page;
    `readJson` refuses a redirect and a non-JSON type.
- **`/mgmt/images/{id}` exists because a count is not an answer**: the list affords
  `countArticleByImages` per row but not the titles (three eager `@DBRef`s per article), so this
  screen runs `findArticleByImages` once, unfiltered like the count. **Only the
  caption and the alt text are editable** (`saveDescription`; blank → absent; `filename` is the
  unique index and the file's location, *AI* for the alt text). `MgmtImageService.fileOf` reports size, dimensions (header only) and mtime, never
  throws, states absent/unreadable/unrecognised, and checks the path stays inside the folder. A
  refused delete returns here naming the articles (`from=detail`).
- **Two library filters resolve an id set first**, because the `@DBRef` points from article to
  image: **by article** (a number, or a title fragment through
  `findArticleByTitleContainingIgnoreCase`, capped at `ARTICLE_MATCH_LIMIT` with both numbers
  shown) and **unused** (the complement of `usedImageIds()`, the one studio aggregation **not**
  degraded to empty on failure — empty would offer the whole library for deletion). Combined they
  short-circuit to an empty page that says why.
- **The page-size control is gated on `totalElements > 0`, not the pager**; the form carries `size`.
- **New studio routes stay under `/mgmt/`**: the authenticated matcher is `/mgmt/**` (plus the two
  preview prefixes), and anything else falls to the permit-all chain; `StudioPaths` treats every
  `/mgmt/` path that is not a platform screen as a website's. `MgmtSecurityTest` pins the JSON
  search and the detail screen anonymously; a literal segment (`/search`) beats `/{id}`.

### The dashboard

`/mgmt/dashboard` is the landing page: readers on the site now, operators in the studio now, and
the last 24 hours of traffic, advertising, crawlers, the jobs, the social page and totals.

- **Almost nothing is new arithmetic.** `MgmtDashboardService` reads each section through the
  service of the screen it summarises, at `MonitoringRange.H24`, so the numbers agree with that
  screen. The comparison slices buckets `[120,144)` out of the hourly `D7` series and is `null`
  (said, never "+∞ %") when those held nothing. Articles have no creation date, so it counts **new
  images**, never "new articles".
- **Every section fails on its own** (empty value plus an ERROR log, never a 500 on the front
  door); the live count keeps `LiveCount.unavailable()` → "—", never 0.
- **"Active operators" is `MgmtPresence`**, an in-heap `displayName → lastSeen` map fed by
  `MgmtPresenceInterceptor` (keyed by `StudioPrincipals.displayName`, the email) — not a sign-in
  timestamp (pacod-id's single sign-on brings an operator back without a sign-in anyone sees), not a
  `SessionRegistry` (it counts idle sessions). It is **platform-wide**: an operator counts whichever
  website they are on. **Background polls are skipped** (`/mgmt/monitoring/live`,
  `/mgmt/dashboard/live`, `/mgmt/jobs/{id}/status`, compared within the website, i.e. after the
  context path), or an open tab keeps its operator active all day; a new timer-driven `fetch` belongs
  on that list. Everything else on the dashboard is the open website's.
- **Its tiles are `dash-readers`/`dash-admins`, never `live-count`** (the monitoring poll binds
  that). `/mgmt/dashboard/live` names who is signed in and is pinned separately in
  `MgmtSecurityTest` and `make smoke`.

### The studio's chrome

Screens are assembled from `templates/mgmt/fragments.html` — `head(pageTitle)`, `sidebar(active)`,
`topbar(heading)`, `flash` — plus `errors`, which must sit *inside* the `<form>` carrying
`th:object`.

- **`icons` is an inline SVG sprite** (`<symbol id="i-…">`, `<use href="#i-name">`), included once
  per page before first use; it inherits `currentColor`. `login.html` includes it and loads **no
  JavaScript**, so its theme is restored by an inline snippet.
- **`sidebar` is a `th:block`**: it also emits the `<input class="nav-toggle" type="checkbox">`
  before the `<nav>` and the `<label class="nav-scrim">` after. `.nav-toggle:checked ~ .sidebar` is
  the ≤1000 px drawer, pure CSS. The three must stay siblings inside `.shell`.
- **A website's screens are offered only while one is open** (`currentSite != null`); without a
  prefix each `@{/mgmt/…}` link would land on the website list. The **Platform** group (Websites
  only, since the accounts moved to pacod-id) is always shown and built on **`${studioRoot} +
  '/mgmt/…'`, never `@{}`**, which would file it under the open website's prefix — the list must
  never look like part of a website. The topbar's
  `site-chip` names the website and links to the list; "View site" is `currentSite.publicUrl`.
- **The `hidden` attribute needs a stylesheet rule.** Controls needing JavaScript render `hidden`
  and `mgmt.js` removes it, but any author `display` beats the UA's `[hidden]` rule, and `mgmt.css`
  has many (`.btn` put dead buttons on screen; `.picker-list label` made the picker filter hide
  nothing). A short guard beside `.hidden` names exactly those elements. **Add to that list**, never
  a blanket `[hidden] { display: none !important }`, which would reach into TinyMCE's skin.
- **Three stored choices, one pre-paint snippet**: `pacod-mgmt-theme` → `data-theme`, `pacod-mgmt-nav` →
  `data-nav`, `pacod-mgmt-editor` → `data-editor` on `<html>`, applied synchronously in `<head>` (any
  later flashes the other state on every navigation). Unset is the default. `mgmt.js` writes them
  through `apply*`/`paint*` pairs. TinyMCE's skin is fixed at `init`, so a theme toggle leaves the
  editor's chrome stale until reload.
- **The sidebar collapses to a 68 px rail through `--sidebar-w` alone** (width, `.main`'s margin
  and `.savebar`'s `left` together; not redefined by the theme blocks), scoped to `min-width:
  1001px`. Labels sit in `<span class="nav-text">` and are **clipped, never `display: none`**, so
  screen readers still hear them.
- **The table header cannot be `position: sticky`**: `.table-wrap` scrolls (`overflow-x: auto`).

### Five enhancements that must not become replacements

All in `mgmt.js`, each wrapping a real form control, each failing *silently* as a rewrite. **The
JavaScript has no committed test** (the repository deliberately has no JS toolchain); the render
tests pin the markup half and the rest is verified in a browser.

- **The image upload's `fetch` wraps `<form id="image-form">`**, which keeps its `method`, `enctype`
  and `action` and posts with scripting off; the interception is a `submit` listener gated on
  `data-async`. Drop the action and the no-script path uploads nothing, at 200.
- **The author/category pickers wrap `<select multiple>`**, which keeps `id`, `name` and `th:field`
  and is merely `hidden` (still submitted). The checkbox list drives `option.selected` and has **no
  `name`**; bound checkboxes would let `save()` strip authors and categories. Rows are listed by id,
  numerically (`parseFloat`), built from a **copy** (`options.slice()`) — reordering the real
  options would reorder what is submitted, and for authors that order **is** the byline.
  **Expanding lays the panel over the window through `makeOverlay`, which moves it to `<body>`**: a
  z-index cannot replace the move, because `.editor-rail` is `position: sticky`, a stacking context,
  and the panel would paint under its own scrim. Safe because nothing in the panel is submitted and
  the `<select>` never moves. A `.picker-slot` holds the card's height **and bottom margin**
  (margins collapse out of `offsetHeight`), or the rail jumps; the scrim is created and removed, not
  toggled; Escape closes only when `event.defaultPrevented` is false (the filter claims it while it
  has a term); the page is **not** scroll-locked (`overflow: hidden` on `<body>` shifts it sideways
  wherever scrollbars take width); `role="dialog"` but **no `aria-modal`** (no focus trap).
- **The author ordering card reorders the `<option>`s of `#authorIds`; it posts nothing.** A
  `<select multiple>` submits selected options in **tree order**, so moving the option *is* the
  order (a hidden `authorOrder` field would be the unsafe refactor). Hence
  **`MgmtArticleService.authorsForPicker` is load-bearing**: options rendered alphabetically made
  every save rewrite every byline. It takes the ids from the **form**, so a validation re-render
  keeps the operator's order. The card ships `hidden` and empty, *outside* `#article-form`;
  `mgmt.js` builds its buttons. `categoryIds` has the same shape; nothing reads category order.
- **The save bar's button uses `form="article-form"`** — no JavaScript. Ctrl/Cmd+S is always
  `requestSubmit()`, never `submit()` (which skips validation and TinyMCE's write-back hook).
- **The video field's two views wrap the `embedded` textarea**, which keeps `id`, `name` and
  `th:field`. The address box has **no `name`** and is `type="text"` (an unnamed `type="url"` input
  still blocks `requestSubmit()` when half-typed). `data-no-dirty` sits on the visual **pane** while
  `#embedded` is its sibling, so `writeEmbed`'s `input` event still arms the dirty tracker. **Opening
  an article never rewrites `embedded`**: the visual view recognises exactly one shape (a lone
  YouTube `/embed/` iframe) and everything else opens the HTML view byte-identical; the preview uses
  the stored `src`.

The unsaved-changes guard is `tinymce.activeEditor.isDirty()` plus input tracking, **not** "content
differs from what was loaded" — true for every legacy body TinyMCE rewrites on open, and warning on
those would train operators to ignore the warning.

### The editor is vendored, and its configuration is load-bearing

Rich text is **TinyMCE 8, committed under `static/mgmt-assets/tinymce/`** with language packs
`bg-BG`, `de`, `ru`; `VENDORED.md` there records provenance and upgrades. It is the one GPL
component (GPL-2.0-or-later — the "or later" is what lets it sit beside Apache-2.0 code, and why the
project can never be GPL-2.0-only). Only the minified build is committed, so `VENDORED.md` and
`THIRD-PARTY-NOTICES.md` both name the upstream tag; **an upgrade updates both**.

**Do not tidy the options in `mgmt.js`.** TinyMCE re-serialises HTML through its schema, so nearly
every setting stops a default from rewriting stored articles:

- **`indent: false`** — the default re-indents every block element, a diff on every body.
- `valid_elements: '*[*]'`, `valid_children: '+body[style]'`, `custom_elements: '~o:p,…'` — allow
  everything, including `<style>` blocks and Word's namespaced tags.
- `allow_conditional_comments` **and** `allow_html_in_comments` — v8's DOMPurify runs with
  `SAFE_FOR_XML` and deletes comments containing markup.
- `convert_urls: false`, `inline_styles: false`, `convert_fonts_to_spans: false`,
  `remove_trailing_brs: false` — absolute image URLs, `<font>`, `<strike>`, trailing `<br>`.
- `entity_encoding: 'named'` was chosen by measuring stored bodies; `'numeric'` would turn every
  non-ASCII letter into `&#…;`.
- `license_key: 'gpl'` is **mandatory** or the editor disables itself; GPL mode makes no network
  call.

`forced_root_block` cannot be disabled, so a body starting with bare text is rewritten on open.
`mgmt.js` compares `getContent()` with the loaded HTML on init and warns with the delta, and
`MgmtArticleService.save` keeps the prior body in `Article.previousText` for `POST
/mgmt/articles/{id}/restore-text` (a swap, so it is undoable). **Body images are URL-referenced**:
inserting or dragging a library image writes `<img src="/assets/img/articles/…">`, never data
(`paste_data_images: false`) and never with the studio's `/sites/<id>` prefix — the body is the
public site's markup; inside the editor those addresses resolve through the Referer rule above. The
drop is an `editor.on('drop', …)` inside `setup` (the editor is
an iframe), claiming only `application/x-mgmt-image` drags, positioned through
`caretRangeFromPoint`/`caretPositionFromPoint` (two spellings; no browser has both). Fullscreen is
the plugin's; `#editor-fullscreen` exists because the toolbar hides it in its `•••` overflow.

### The article editor's layout

**One `.editor-grid`, and the `<form>` is not it.** The image, restore and delete cards are their
own POSTs and cannot nest in the form, and two grids stranded them under the tall rail. So the form
carries `.editor-form`, **`display: contents`**: `.editor-main` and `.editor-rail` become items of
the surrounding grid, `.editor-tail` sits in column one, the rail spans both rows.

- **The named areas define the column count**, so the `max-width: 1180px` block resets
  `grid-template-areas` as well, or the editor stays two-column on a phone.
- **`display: contents` changes no form ownership or DOM ancestry**: fields still bind, events still
  bubble to `#article-form`. Moving the rail out and naming the form per control is how `save()`
  writes an article whose authors were never posted.
- **`MgmtScreenRenderTest` pins the grid by count** (`containsOnlyOnce("class=\"editor-grid\"")`);
  an order assertion passes against the broken markup.
- **`data-editor="wide"` folds the rail below the body**, never `display: none`. Its `!important`
  overrides the inline `height: 620`, and its **`:not(.tox-fullscreen)` is load-bearing** (wide and
  fullscreen combine; without it fullscreen lands 420 px short). TinyMCE 8 dropped
  `toolbar_sticky`, which is why the editor is not taller.

### Persistence

MongoDB via Spring Data. No JPA, no JDBC.

- **IDs are assigned by the application**: `getNextId()` in `ArticleService`/`AuthorService`/
  `ImageService` reads the current max — read-then-write, so concurrent creates can collide, and
  ids are **reused** after deleting the highest (hence `MgmtArticleService` prunes `hiddenImageIds`
  on every path that changes `images`). The max is per website database, so two websites have
  article 1 each (`RoutingIsolationIT` pins "one id, two websites, two documents"). An atomic
  per-site counter was planned for Phase 2 and is **deferred**.
- **`Article` eager-loads `@DBRef`** `authors`, `categories` and `images`: three more queries per
  article read. Never count through a page — `getTotalElements()` fetches an article too.
- **You cannot aggregate on `categories.$id`**: a `@DBRef` is `{$ref, $id}` and a path starting
  with `$` is not a legal expression. Group on the whole element (`countArticlesByCategory()`);
  derived count queries are unaffected. **The group key arrives as a `com.mongodb.DBRef`, not an
  `org.bson.Document`** — code reading only `Document` showed 0 articles per category while its test
  passed, because the test built the key in the expected shape. **`MgmtDbRefs.idOf`** reads both,
  is tested on both, and serves every such aggregation; on the image screen a wrong guess lists the
  whole library as unused.
- **An `@Id` field is stored as `_id`, and so is any property called `id`, embedded documents
  included.** `Page`'s `@Id slug` means no `slug` field exists (`find({}, {slug: 1})` silently
  returns rows without it — `scripts/reseed-pages.sh` once reported every page missing that way);
  `SocialLink.id` is `links._id`; `MenuItem` has no `id` on purpose. Fixtures written from Java
  field names test the shape the code expects, not the one MongoDB sends.
- **An `ImageMedia` can belong to several articles** (`filename` is unique; the library attaches).
- **No id range is reserved for articles**: every listing reads the whole collection (the
  predecessor's `_id < 100000` filter is gone; a generic install's 100,000th article would have
  vanished). `MOST_READ_ID = 100001` in `WebArticleService` is a pseudo-*category* id, in another
  collection's id space.
- **Only `PUBLISHED` reaches a reader.** `NEW`, `DRAFT`, `PRIVATE` and **no status** are unpublished,
  as the studio already treated them (`crossesPublished`). Until Phase 6 the public site ignored
  `status` altogether: every draft was served at `/article/{id}` and listed on `/latest`.
  - **The public site reads articles only through `ArticleRepository`'s public queries** — the
    block at its top: `findPublished`, `…AndStatus(…, PUBLIC)`, `fulltextSearchPublished`,
    `findPublishedCoversByIdIn`, `findPublishedForSitemap`. The queries below that block are the
    studio's and see drafts. **A new public query carries the predicate**, or the IT below fails.
  - `WebArticleService.getArticleById` answers empty for an unpublished article **before** the view
    counter. `ArticleReader` caches it either way, so publishing shows within the 60 s TTL; the
    print view, `/web/v1` and `/social`'s borrowed titles and covers follow from the same reads.
  - **The one exception is the studio's draft preview**: under a valid preview token
    (`SitePreview.current()`) `getArticleById` serves an unpublished article, uncounted and
    `noindex`; lists stay published-only even there (`PreviewIT.draftArticleOnlyWithToken`). The
    editor's Preview, the translation screen's and the image screen's "View on the site" go through
    **`GET /mgmt/articles/{id}/preview`** (`content.read`), which mints the token **at the click**
    (an editor page outlives a token) and redirects to the website's origin
    (`DesignPreview.articleUrl`; without the secret, the public address). They used to be
    `@{/article/…}`, which on the studio's own host is a studio path — a 404 since Phase 2.
  - Elsewhere: `PdfArticlesHandler` skips an unpublished article (a PDF lands in public storage),
    the design preview shows the newest **published** article, the demo content writes every
    article `PUBLISHED`.
  - **Not covered**: the tag collection is rebuilt from every article's `tags`, so a tag used only
    by drafts exists; its `/list/{tag}` lists nothing.
  - **`PublicArticlesIT`** pins every surface against real MongoDB: a draft, a `NEW` and an
    unstated article are 404 or absent on the article, print and JSON routes, `/latest`, a category,
    a tag, most-read, search and the sitemap; the published one is there.
- **Indexes are declared on documents and created by `SiteIndexes`** (*Tenancy*), never Spring Data.

### The article view counter

`WebArticleService.getArticleById` (1) reads the body through `ArticleReader` (`@Cacheable`, a
**separate bean** — `this.` calls bypass the proxy); (2) calls `ArticleViewCounter.recordView`, an
atomic `$inc` that **projects only the counter** (returning the document would defeat the cache);
(3) copies the article and sets the live count on the copy — the cached instance is never mutated.
Change any of the three and the counter or the cache breaks; each has a test. Crawlers are not
counted.

### Advertising

Every booking is a `Banner`, edited at `/mgmt/banners` and rendered by `fragments/ad-sidebar.html`
from the `topBanners`/`sidebarBanners` lists each controller puts in the model. A fresh install has
**no bookings** and no banner seed.

- **The rendered classes are `partner-card` / `partner-row`, never `ad-box` / `ad-row`.** EasyList
  (the default in uBlock Origin, AdBlock Plus, AdGuard, Brave) carries the generic rules
  `##.ad-box:not(#ad-banner):not(:empty)` and `##.ad-row`, which emptied the ad column for every
  reader with a blocker — invisible from the server, where everything renders and passes.
  `advertisingCarriesNoBlockerBaitClassNames` pins the *shape*, no class beginning `ad-`. **Grep the
  current `easylist.txt` before any rename** (the paths match no network rule; fragment names never
  reach the browser). It is a treadmill: lists add site-specific rules once a publisher renames.
- **`source` is a whole public path for both types** (iframe URL for `HTML5`, image URL for
  `IMAGE`), never a bare filename; uploads write `/resources/banners/{id}-{file}` in the website's
  own folder, served on its own host — which keeps HTML5 frames same-origin with the page.
- **`pages` empty means *every* page.** `BannerPage.SOCIAL` exists for the monitoring table and is
  **not bookable** (`BannerPage.bookable()`; `MgmtBannerService` refuses a crafted POST).
- **`BannerReader` caches the whole collection; `BannerService` filters per request**, or
  `activeFrom`/`activeTo` would lag the TTL.
- **Impressions are buffered**: `BannerImpressionCounter` accumulates `LongAdder`s per website
  (`PerSite` — banner ids are unique only inside one) and flushes one bulk write per website per
  `app.banner.impression-flush.ms`; an unclean exit loses the interval. Clicks are one write per
  action. **Crawlers are served banners and counted for none** (`recordImpression`,
  `recordViewable`, `recordClick` return early).
- **`impressions`/`clicks` are absent from `BannerForm`**, because `save()` writes the whole
  document and would restore stale values; an image banner's `source` comes from the upload, so
  `MgmtBannerService.apply` takes `source` from the form for `HTML5` **only**. Both tested.
- **HTML5 clicks are counted by the public page.** A click inside an iframe never reaches the
  parent, but the frame is **same-origin** with **no `sandbox`**, so `site.js` listens inside it and
  sends `POST /b/{id}/c` by `sendBeacon` (covering exports that navigate via `window.open(clickTag)`).
  Silent failure modes: **`sandbox` without `allow-same-origin`, `credentialless`, or an off-origin
  `source`** makes the frame unreadable (`Banner.isClickTracked()` is `IMAGE`, or `HTML5` whose
  `source` starts with a single `/`; others show "—" and leave every rate); **`data-partner="<id>"`
  on each frame** is how the browser learns the booking (rename in both files or neither;
  `creativeFramesCarryTheirBookingId` reads `site.js` to pin the pair). **One click path per
  type**: `GET /b/{id}` refuses `HTML5` with 404, the beacon refuses `IMAGE` and counts only an
  active booking in its window. `auxclick` counts only `button === 1`; a 1 s per-frame debounce
  covers double events. **Both beacons refuse `Sec-Fetch-Site` other than `same-origin`** (absent is
  accepted). EasyPrivacy carries `/b/s/beacon`, `/b/ss/` and `/b/stats?`, so grep **EasyList and
  EasyPrivacy** before renaming any of it.
- **The site never sends `X-Frame-Options: DENY`**, Spring Security's default, which forbids
  same-origin framing too and blanked every HTML5 booking once `MediaController` served the
  folders — at 200, visible only in a reader's browser. `FramingHeaders` sends `frame-ancestors
  'self'` (plus `SAMEORIGIN`), and on `/resources/banners/**` `'self'` and the studio's origin
  (`pacod.studio.origin`, `STUDIO_ORIGIN` on `cms-site`), which frames a layout's folder for its
  preview. `FramingIT` pins it over real HTTP; `make smoke` prints the pair.
- **Viewable impressions are a second count beside served**: half the creative on screen, in a
  visible tab, for one second, **once per booking per page view**, batched in one `POST /b/v` (at most
  16 ids). Only a creative that **loaded** qualifies. No all-time scalar. **An image banner's vCTR can
  exceed 100 %**: its clicks come from every client, its viewables only from readers running `site.js`.
- **`/b/{id}` validates the target twice** — on save and before writing `Location` — through the
  shared `HttpUrls.isHttpUrl` (core), so a hand-written document cannot make an open redirect.
- **`BannerCreativeService` is not `ImageService`**: any size, a 1 MB cap (`MAX_BANNER_BYTES`).
- **The multipart limits equal Caddy's `request_body max_size` (4MiB)**, so every upload the proxy
  admits reaches Java and is refused naming its rule; a limit equal to the 1 MB cap made the Java
  check unreachable. Past 4MB **Tomcat rejects inside the CSRF filter, below Spring**. Keep the
  three numbers in step. **`UploadSizeAdvice` is a net for requests that bypassed Caddy**:
  unscoped (the parse fails before a handler exists, and a package-scoped advice is never consulted
  for a null controller type), `@Order(HIGHEST_PRECEDENCE)` (the first matching advice wins, so a
  `ResponseEntityExceptionHandler` would claim it), returning `ModelAndView` (nothing handles
  `Object`).

### Banner layouts

An HTML5 booking's `source` names a *folder*; `/mgmt/banners/layouts` builds and manages those
folders. Two documents in `core.data`; the public site knows nothing of them — a layout reaches it
as a `Banner`.

- **A layout (`BannerLayout`) is one campaign's folder.** `BUILT`: the studio rewrites
  `index.html`, `css/banner.css` and `js/banner.js` from the document on every save. `IMPORTED`: a
  ZIP served verbatim, no builder (a save would overwrite what the studio never authored).
- **A skeleton (`BannerSkeleton`) is a look** — head, container tag, tail and CSS as text, no files,
  **no images**. The built-ins (`sidebar`, fixed; `top`, fluid) are vendored under
  `banner-skeletons/` and seeded into each new website by `BannerSkeletonBootstrap` (a `SiteSeedStep`,
  in the studio because only the studio reads skeletons) while its collection is empty, then edited
  at `/mgmt/banners/layouts/skeletons`. The "no skeletons" message in the `banners` bundles still
  names a switch that no longer exists. **They load no tag manager.** A skeleton that does
  stores its container id as `GTM_TOKEN` (several distinct ids are refused), records it as the
  default and requires an id per layout; `assemble` substitutes it back for the editor, or a round
  trip would silently drop the tag.
- **The folder name is the `@Id`, never editable or max-plus-one** (a rename orphans every
  `Banner.source`). New ids match `[a-z][a-z0-9-]{1,63}`; `new`, `import`, `adopt`, `skeletons` are
  **reserved** — a literal segment beats `/{id}`, and such a layout would 404.
- **`BannerSize.AUTO` is refused for `HTML5`** (a `height: auto` frame around a `height: 100%`
  iframe has no height). `suggestedSize` maps 300×250→`SQUARE`, 728×90→`TOP`, 300×600→`TALL`,
  fluid→`TOP`, and returns empty rather than guess.
- **Rotation belongs to the studio**: every `BUILT` folder gets the vendored `rotator.js`, reading
  the slide count off the DOM and the interval off `data-speed`.
- **`BannerSkeletonParser` insists on three tags** — the `css/banner.css` link, the `js/banner.js`
  script, a tail calling `banner()` — each of which would otherwise fail silently at 200. The split
  happens once, at import (scan for the class literal, depth-count to the close, refuse when
  unsure); generating is concatenation, and `htmlTail` **includes** the closing tag. Dimensions are
  **appended** to the skeleton's CSS, never substituted.
- **`top/banner.css` keeps its bug**: `height: 100% !important` lacks its semicolon, so `width:
  100%` is dropped and the image keeps its aspect — which is what fits a 728×90 creative into the
  90 px frame. "Fixing" it stretches every top banner (`VENDORED.md` says so).
- **`BannerLayoutStorage.importArchive`'s rules come from real archives**: a single shared leading
  directory is stripped; the entry file is `index.html`, else the single `.html`, else refused
  naming the candidates (hence `entryFile`); zip-slip containment twice; entry count and
  *uncompressed* size capped; extensions allow-listed (`js` included).
- **Deleting a booking never deletes a layout**, and deleting a layout is refused while any
  booking's `source` starts with its `publicFolder()`, naming the bookings. Recursive deletion lives
  in `BannerLayoutStorage`; `BannerCreativeService.delete` refuses any path containing `/`.
  `stat()` never throws and states absent, unreadable and empty separately.
- **The preview is click-to-load and frames the published file**, because a skeleton may load GTM
  and every render would be a pageview for the advertiser — **framed from the website's own public
  origin** (`currentSite.publicUrl + layout.publicPath()`), never the studio's: the folder is HTML and
  JavaScript its owner wrote. With no host there is no preview and no "open in a new tab".
- **The routes nest under `/mgmt/banners/`** (no security change; the literal beats
  `/mgmt/banners/{id}`), each pinned in `MgmtSecurityTest`.
- **The builder reuses `id="article-form"` and the `#dropzone`/`#image-file`/`#upload-hint`
  trio** (save bar, Ctrl/Cmd+S, the dirty guard, drag-to-upload), but **not** `id="text"`, which
  binds TinyMCE.
- **Adding a slide and swapping its picture are `fetch`es** to the form action + `/upload`, gated
  on **`data-slide-upload`** (never `data-async`). An added slide returns **server-rendered markup**
  (`slide-row`/`slide-forms`, bound with explicit `th:name`/`th:value`; each loop wraps its
  `th:replace`, since inclusion runs before `th:each`); a refusal is `slide-refused` at 409, as a
  `ModelAndView`. `imageUploadInFlight` blocks save, move and delete; file inputs carry
  `data-no-dirty`; a loaded preview resets to unloaded.
- **Slide ↑/↓/× reach out-of-line forms through `form=`.** `applySlides` refuses when the posted
  count no longer matches the document (by position across a shift, one advertiser's link would
  land under another's creative), and `rerender` squares the form's list with the document, since
  the binder auto-grows a short list into blank rows.

**Adopting existing folders.** The layouts screen offers to adopt folders under
`resources/banners/` that no document describes:

- **Decided by content** (a `.banner-container-inline` with frames → `BUILT`, else `IMPORTED`); **the
  id is the folder name verbatim** (`adoptedId`, not `normaliseId`: lowercasing 404s on Linux); the
  folder is copied to `app.banner.backup-folder` (`sites/<id>/backups/banners/`) before the first
  regenerating save, or not adopted. **That folder is outside `assets/` and `resources/`**, the only
  two trees of a website's storage `MediaController` serves — it checks the resolved file stays
  inside the tree the address names, not merely inside the website's folder. A sibling of the banner
  root would have been `resources/banner-backups/`, published; `StorageIsolationIT` probes it.
- **`readSlides` reads leniently** (a frame is any child of the container holding an `<img>`),
  entity-decodes attributes (or `&utm_…` becomes `&amp;amp;` on first save), reads `var speed` and
  ignores `var modulo`. `BannerSlideReaderTest` runs on hand-made folder copies (`acme2`,
  `globex-top`). A derived skeleton is deduplicated with whitespace collapsed for the comparison.
- **A slide's new picture gets a new name; the file editor keeps the exact one** (an export names
  its own files). Both reference-count what they displace.
- **`requireInsideFolder`, not `requireInside`, for every request-supplied path**:
  `../neighbour/index.html` stays inside the banner root but names another layout's file. The file
  editor refuses a `BUILT` layout's generated files, and is confined by containment,
  `EDITABLE_TEXT_EXTENSIONS` and a 512 KB cap.

### The standing pages

About, contact, advertise, terms and privacy are `Page` documents, served by `PageController` at
`/p/{slug}` (and `/{lang}/p/{slug}`), edited at `/mgmt/pages`. There are no `*.html` aliases.

- **Languages are a collection, `language`, edited at `/mgmt/pages/languages`** (code as id,
  `adminName`, `nativeName`, order, two notice texts; seeded from `seed/languages.json`). It governs
  **what the studio offers and the switcher lists — never whether a body resolves**: `TextItem`s
  are keyed by bare codes and `LanguageChain` resolves against those, so an empty collection or a
  deleted row cannot stop `/p/about` rendering, and a body in a removed language still serves
  (`Page.writtenIn()` reports it). A language with no `messages_xx.properties` gets its bodies in
  English chrome.
- **The chain is requested, then English, then the original** (`LanguageChain.forRequest`); **a
  reader who asked for nothing gets the original first**. On an English website it collapses to two
  codes. `LanguageChainTest` pins both orders.
- **The served language is the first in the chain with a *body*, and every field resolves against
  it**, so no page shows one language's title over another's body. `fallback` means "served
  something other than what was asked".
- **A missing translation is an absent `TextItem`, never `""`** (`Page.put` drops blanks), so "not
  translated, fall back" and "deliberately empty" stay apart; a blank notice stores as null.
- **The notice is rendered in the language the reader asked for**, with `{0}` the **native name of
  the language served** — in the served language it would tell a German reader in English that the
  page is not in German. Three tiers: the reader's language's text, English's, a `PageService`
  constant; a page in the wrong language with *no* notice is the outcome this prevents.
- **A legal page states which text governs instead**: `Page.officialInDefaultLanguageOnly` (seeded
  on terms and privacy) names `original()` as binding, independent of the chain order.
- **The studio edits one language per screen** (`?lang` tabs): one TinyMCE per language would post
  every body, and one that failed to initialise would post empty. `PageForm.of` loads the **raw**
  entry, never `page.title(chain)` (the fallback would be saved as a translation) — which is why
  `Page` spells resolved and raw as **two names**, `title(List)` / `rawTitle(String)`: SpEL picking
  the wrong overload would be silent data loss.
- **Deleting a language refuses `original()` and `LanguageChain.FALLBACK` always**, and any other
  while a page holds text in it, **naming the pages**. Nothing cascades. A code is the id, never
  editable, two or three lowercase letters (what `LanguageChain.normalise` leaves of `?lang`).
- **`/mgmt/pages/languages` sits under `/mgmt/pages/`** for the authenticated matcher; the literal
  beats `/{slug}`; `MgmtSecurityTest` pins every route. `page-edit.html` reuses `id="text"` and
  `id="article-form"`, inheriting TinyMCE's configuration, the save bar and the dirty guard.
- **`PageBootstrap`/`LanguageBootstrap` seed a website only while its collection is empty**, when
  it is provisioned (see *Architecture*); `LanguageReader` caches on the 60 s TTL and degrades to
  an empty list.
- **`make reseed-pages SITE=<id>` is the only way a changed `seed/pages.json` reaches an existing
  website.** It backs the website's collection up to `.data/backup/pages/<id>-page-<stamp>.json`,
  **rebuilds the `cms-studio` image** (the seed is on core's classpath, inside the studio's jar), drops the
  collection, sets the website's `schemaVersion` to 0 in the control plane, **force-recreates**
  `cms-studio` (an identical image would leave it running and never re-provision: every `/p/*` 404s) so
  its `reconcile()` re-seeds, and checks `/p/about` by the website's Host —
  `PageBootstrap.toPage()` stays the one owner of the mapping. Reports unless `APPLY=1`;
  `make reseed-pages-restore SITE=<id> FILE=…` undoes.
- **Terms and privacy ship as placeholders that say so.** Replace them before publishing.

### The social page

`/social` is the link-in-bio page a website's social profiles point at: profile, icon row, link
buttons, optional newest articles. `SocialController` (site) renders it; `/mgmt/social` edits it;
`/mgmt/social/stats` reports on it. One `SocialPage` (id `social`) with embedded links and **no
seed**: `MgmtSocialService` creates it on the first save from the website's name, tagline and
profiles, unpublished. Until saved and published, `/social` is 404.

- **`social.html` has its own `<head>`, and nothing on it may reload or redirect**: a second
  request would count twice and carry `/social` as its referrer. It takes `fragments/head :: theme`
  and `:: gtm` by name and loads no `site.js` (in-app browsers). Pinned by `PublicTemplateRenderTest`.
- **The network is decided when `/social` renders and carried in `?src=`** on every href, because
  the tap arrives with `/social` as its referrer. No cookie, no session; `SocialView` builds the URLs.
- **`SocialSourceClassifier` is a pure function whose order is the design**: `?src=`;
  `utm_source`; the in-app browser's UA token (`Instagram`, `FBAN`/`FB_IAB`, `musical_ly`…;
  Messenger first, as its UA carries Facebook's tokens); the `Referer` host (`l.instagram.com`,
  `t.co`, `lnkd.in`…, and `android-app://<package>/` from an exact table); a click id's *name*
  (`fbclid`, `ttclid`…, below the referrer because Instagram appends `fbclid` too); then own host →
  `SITE`, other referrer → `OTHER`, none → `DIRECT`. **Only `?src=` is reliable**, so the studio
  prints a tagged address per network. Add a captured UA to `SocialSourceClassifierTest` before
  changing a pattern. Only the key is stored.
- **Bots and self-referrals are not counted** (`isBot` first; a `/social` referrer is no new visit).
  Back from a tapped link counts again, accepted (`no-store` defeats the back-forward cache).
- **Clicks go through `GET /social/go/{linkId}?src=`** (and `/social/read/{articleId}`), a 302
  shaped like `GET /b/{id}`: re-validated through `SocialTargets`; unknown, disabled, out-of-window
  or hostile links go to `/social` uncounted (`/` while unpublished); counting needs a same-origin
  or absent `Sec-Fetch-Site` and no bot; `no-store`, `X-Robots-Tag: noindex`, `rel="nofollow"`.
- **`SocialTargets` admits site-relative paths carefully** — exactly one leading `/` (`//evil` and
  `/\evil` are other hosts), no backslash, whitespace or control character; no `mailto:`/`tel:` —
  and treats an absolute link as the website's own only on `SiteSettings.ownHosts()`.
- **Link ids are generated (`[a-z][a-z0-9]{7}`), never typed or reused**, because each is a dotted
  `$inc` key (`socialClicks.k3x9a2bq.INSTAGRAM`); `recordSocialClick` drops anything else. The
  unsaved default's starter icons have **fixed** ids (`profile1`…`profile9`): `load()` rebuilds it
  per request. A link's `id` is stored as `links._id` (`embeddedLinkIdIsStoredAsUnderscoreId`).
- **No click counter on the document** (the `BannerForm` trap); totals sum day buckets.
- **Class names start with `linkpage`**: Fanboy's Social list hides generic `social-`/`share-`/
  `follow-` names everywhere. Checked against EasyList, EasyPrivacy and both Fanboy lists
  (`/social/sprite-` is a Fanboy rule, hence the inline sprite). Grep all four before renaming.
- **No ad columns.** Without an avatar the page shows the site's logo or name. Languages follow
  `Page`'s rules, except each label resolves on its own; a button needs an original-language title.
- **Nothing in the studio's link cards leaves `/mgmt/social`**, whose profile form would otherwise
  arm the leave-page prompt: the add form is a `<details>`; `mgmt.js` posts card forms with
  `X-Mgmt-Fragment: social-links`, and the **same URL** answers that header (a `headers=` mapping)
  with the `links-card` fragment, swapped whole; a refusal is the card at **422** with values kept.
- **A link to the website's own content borrows its cover and titles**: `SocialTargets.articleIdOf`
  / `pageSlugOf` / `categoryIdOf` recognise `/article/{id}`, `/p/{slug}`, `/list/{id}`. The cover is
  `Article.coverFilename()` (the first visible image) via one projected query (`findCoversByIdIn`);
  `SocialPageReader` caches covers and titles as `LinkTarget`s with the page, each kind's failure
  contained. `SocialView` asks the link first, then the target, for each language in the chain.
  `SocialController.inLanguage` adds `?lang` to the redirect only for `SocialTargets.isSiteContent`.
  Covers show on buttons only.
- **The statistics name the article clicked** (tile or link, summed; a link is credited through its
  *current* address). `/mgmt/social/stats` reuses `MonitoringRange`, `series()` and `mgmt/chart`;
  `MgmtScreenRenderTest` renders it through the **real** `report()` over stubbed buckets.

### Monitoring

`/mgmt/monitoring`: readers now, a chart of views and readers, per-period tables for articles,
bookings, page types and totals.

- **The series starts when the stack first counted; nothing is back-filled** (`Article.counter`,
  `Banner.impressions`, `Banner.clicks` are dateless scalars). A fresh database renders `.empty`
  and says so — never a flat zero line. **Every number is people**: crawlers are excluded
  everywhere. The most-read table is all-time `Article.counter`, labelled apart from period views.
- **The site writes, the studio reads, MongoDB is the channel** (`SiteMetricHour`, `SiteMetricDay`,
  `SiteVisitor` in `core.data`), each website into its own database. The studio has **no
  `@EnableScheduling`**.
- **`SiteMetricCollector` buffers** per website (`PerSite`; `LongAdder`s, `@Scheduled` flush,
  `ContextClosedEvent`, counts restored on failure). Bucket ids are **deterministic clock strings**
  (`"2026-08-09T14"`, `"2026-08-09"`) in the JVM's zone, as the studio's reader expects — not yet the
  website's `timezone`. Additive counters `$inc` hour and day in one flush, **each grain with its own
  buffer** (one shared buffer would re-apply counts to the grain that already took them;
  `grainsRetryIndependently`). **Readers do not sum**: hourly is a `$max` of an hour-keyed set,
  daily a `countDocuments` over `siteVisitor`.
- **Writes go through the raw driver, `start` via `$setOnInsert`**: raw so dotted keys
  (`viewsByPage.ARTICLE`) reach MongoDB verbatim; `$setOnInsert` because `start` is the **TTL
  anchor**. `SiteMetricCollector.toDate` reproduces Spring Data's `LocalDateTime` encoding. One
  clock must write the ids — the first thing to break on scale-out. One index on
  `siteMetricHour.start` is both TTL and range index, built in each website's database when it is
  provisioned.
- **Readers are identified without a cookie**: `VisitorIdentity` hashes salt + site id +
  `X-Forwarded-For` + UA to 16 hex characters; `SiteVisitor` stores that, a day and a timestamp — no
  address, UA or path — expiring after two days, anchored on `day`. It undercounts behind NAT, and
  says so.
- **The live count must not degrade to zero**: it returns `LiveCount.unavailable()` and the screen
  says the database is unreachable; every other query falls back to zeroes plus an ERROR.
- **The chart is server-rendered SVG; `MgmtChart` computes every coordinate**, tooltips are
  `<title>` elements, **`Locale.ROOT` on every coordinate** (`52,00` is two numbers to SVG), colours
  are CSS classes over theme tokens (a new series key needs its rule set, or draws nothing), and
  `series()` gap-fills. Views and readers share a chart; served, viewable and clicked get their own.
- **Per-booking numbers are maps in the same buckets** (`bannerImpressionsById`,
  `bannerViewableById`, `bannerClicksById`). `SiteMetricMappingTest` reads a bucket **in MongoDB's
  shape** (Int32 included). Rates are never taken over the all-time scalars; a bucket before a
  booking's `createdOn` is not attributed to it (ids are reused); `?banner=` is resolved against the
  bookings before the model. Banner counters are read through `BannerRepository`, never a cache.
- **`/social` writes into the same buckets** (`socialViewsBySource`, `socialClicks`,
  `socialArticleClicks`, `socialVisitors`) and counts as a `BannerPage.SOCIAL` page view.
- **`/mgmt/monitoring/live` is pinned separately** in `MgmtSecurityTest` and `make smoke`, like
  every poll: a matcher covering a page but not its JSON would leak it.

### Crawlers

`/mgmt/crawlers` shows which crawlers reached the site, what they fetched and were answered, and
whether a "Googlebot" was Google. **A crawler is not a reader anywhere on the platform.**

- **One definition, made once per request.** `CrawlerTrafficFilter` (site) classifies through
  `CrawlerTraffic.classify` — `CrawlerClassifier`'s table, then `SocialSourceClassifier.isBot`
  (filed as `other`: curl, scripts, a generic bot, an empty UA) — and stores the result as a request
  attribute that `SiteMetricCollector.recordPageView`, `BannerImpressionCounter` and
  `WebArticleService.getArticleById` read **and nothing else**. **No attribute means a reader.**
  They ask `isUncounted`/`currentIsUncounted`, which also honour the preview's
  `UNCOUNTED_ATTRIBUTE` (*The draft preview*); `isAutomated` stays the crawler question alone.
- **The table is ordered, first lowercase-substring match wins** (`googlebot-image` before
  `googlebot`). Patterns must not match readers (bare `pinterest`, `naver`, `whatsapp` occur in
  in-app browser UAs); `CrawlerTrafficTest` runs every browser string from
  `SocialSourceClassifierTest` through it. Bot ids are dotted `$inc` keys (`[a-z0-9-]+`), never
  renamed, and `CrawlerClassifier.categoryOf` is **total**.
- **The address is `X-Real-IP`, never `X-Forwarded-For`** (a proxy that *appends* to XFF, as nginx
  did, and the directly published `cms-site` port leave its first entry the client's claim), and is believed **only when the TCP peer is a proxy hop**, read from
  the innermost request — the `cms-site` port is published directly. Only a parsed IP literal is stored.
  After a deploy, `db.crawlerVisit.distinct("ip")` should be public addresses.
- **Verification is reverse then forward DNS, off the request thread** (`CrawlerVerifier`):
  `VERIFIED`, `UNVERIFIED` (forged — a finding) or `UNKNOWN` (no scheme, timeout, pool full, private
  address, off). Private addresses are never looked up; `UNKNOWN` is cached ten minutes. JNDI's DNS
  provider needs `jdk.naming.dns` in the runtime image; `InetAddress.ofLiteral` never resolves a name.
- **The filter reads the status after the chain**, at order -110 (before Spring Security, so the
  firewall's 400s are seen), registered by `CrawlerTrafficConfig`; error dispatches are skipped, a
  throw is recorded as 500 and rethrown; `/site-assets/**` and `/web/v1/health` are skipped; an
  unknown host never reaches it. **`/assets/**` and `/resources/**` reach the site now**, so a
  crawler's image and banner fetches are recorded too. Our own tooling (curl, headless Chrome)
  lands in "other", visibly — except the studio's previews and PDF renders, which are marked
  uncounted before this filter runs and skipped by it (*PDF export*).
- **Counts go into the buckets** (`crawlerHitsByBot`, `crawlerErrorsByBot`,
  `crawlerVerifiedByBot`, `crawlerUnverifiedByBot`); **detail goes into `crawlerVisit`**, one row
  per request, batched by `CrawlerRecorder` per website (at most 5,000 buffered per website, a
  failed batch dropped; `record()` captures the tenant for the DNS thread). Rows expire after 31 days
  through the TTL index `SiteIndexes` builds at provisioning.
- **The stale warning** (Googlebot or Bingbot unseen for 48 hours) reads the whole log, ignores
  forged visits, and **holds back until the oldest row is 48 hours old**. Every parameter is
  normalised before the model; the path search is `Pattern.quote`d. `CRAWLER_TRACKING` gates
  recording only; `CRAWLER_VERIFY` the DNS check.

### The design screens

`/mgmt/design/**` (`MgmtDesignController`, `@StudioPermission(read = "design.read", write =
"design.write")`) edits the open website's layouts, theme, template set and menus. **Publishing a
layout or the theme and switching the set need `design.publish`**; a designer without it edits
drafts that an admin publishes.

- **Every layout and theme change goes to the draft.** The draft starts as a copy of what is
  published, or of the set's default — **whose section ids are fixed** (`Layout.copy`), because the
  editor addresses sections by id and *showing* the editor must not save anything: random ids on
  an unsaved default made every section unfindable on the next request.
- **The layout editor** (`design-layout.html`): sections added per region from the types the set
  implements, configured by forms built from their `SettingField`s, moved with ↑/↓ and between
  regions (form POSTs), removed — **never `primary`**, which can only move. The drag-and-drop in
  `mgmt.js` posts the whole new order as JSON to `…/arrange`, applied **all or nothing** (a stale
  page cannot drop a section another tab added) and then reloads; the forms remain the no-script
  path. Sections the set cannot render stay listed, flagged with their issue.
- **The theme editor** takes each token as `t_<token>` and `t_<token>@dark`, refuses an invalid
  value by name, and applies a preset by replacing the draft's values. **Its swatches read
  `p.tokens().get(k)`: SpEL takes `map[k]` with a bare identifier as the literal key `"k"`**, which
  rendered every swatch empty at 200.
- **The template switch** (`?set=`) shows `DesignTemplates.report` — per page type, what the
  website's published layouts *and* drafts would leave out on the candidate set — then applies by
  setting `Website.templateSet` and evicting the directory; nothing is removed, and the sections
  come back on a switch back.
- **Menus are saved as edited, with no draft** (live within a minute). An item is addressed by its
  position, `2` or `2.1`, because `MenuItem` has no id (*Persistence*); labels post as
  `label_<lang>`; kind and target are checked by `SiteChrome`'s rules, so the studio cannot store an
  address the site would drop.
- **A handler returning `Object` is a model attribute, not a view.** The one that answers either a
  redirect or a fragment returns `ModelAndView`.
- **`js.*` strings live in the `common*` bundles** even when only a design screen uses them;
  `MgmtJsTextTest` reads those alone. Everything else is in `i18n/design*.properties`.
- `DesignLayoutsTest`, `DesignThemeAndMenusTest` and `DesignScreensTest` (every screen through the
  real security chain and website filter, in Bulgarian and English; an editor and an author are
  refused everything, including the preview).

### The draft preview

`/mgmt/design/preview` frames the open website's drafts (layouts and theme) from **the website's
own origin** with `?_preview=<token>`, never from the studio's: the page runs the website's scripts.

- **`PreviewTokens`** (`core.design`) produces `p1.<site>.<set|->.<expiry>.<HMAC-SHA256>`, signed
  with `pacod.preview.secret` (`PACOD_PREVIEW_SECRET`).
  - **The secret is set on both applications.** Two JVMs means a blank secret turns previews off
    rather than inventing a key the other cannot know. One shorter than 32 characters fails the
    start.
  - A token is bound to one website (compared with the one the Host names), lasts 30 minutes, and
    may name a template set to try.
  - `make init` writes the key into `.env`, in every environment. Compose has no default.
  - **In a preview an HTML5 booking is its box and name, never its frame** (`ad-sidebar`): a
    creative may load its advertiser's tags, and the preview reloads on every saved draft.
- **`PreviewFilter`** (site, order -115) runs after the website's binding (-120), which the token
  must name, and before the crawler filter and Security.
  - **Any `_preview` marks the request uncounted** (`CrawlerTraffic.markUncounted`). That covers
    `/social`, which reads bots off its header.
  - **A bad, expired or other website's token is a plain-text 403, never the published page.** An
    editor would take the published page for their draft.
  - A valid token gets `no-store`, `Referrer-Policy: no-referrer` (the token is in the address) and
    `noindex`, and `FramingHeaders` lets the studio frame the page.
  - The token is read from the **query string only**; `getParameter` would parse a POST's body.
    The ERROR and ASYNC dispatches re-apply what the REQUEST dispatch stored.
- **Drafts are read past every cache**: `LayoutReader.draft`, `ThemeReader.draft`,
  `SiteChromeReader.settingsNow`/`menusNow`.
  - The caches are keyed per website only, so a draft read through one would reach readers.
  - `PageComposer` and `ThemeCssController` branch on `SitePreview.current()`.
  - The draft theme is linked by the token (`Stylesheet.href()`) and served `no-store`, never by
    `?v=`, which is cached for a year.
- **No third party in a preview.** `SiteChrome.withoutTracking()` answers null for GTM, Facebook
  and the verification ids, so no template set needs a branch.
- **The address is the only carrier**, because third-party cookies are blocked in the studio's
  frame.
  - `preview.js` is loaded only for a preview, by `fragments/head :: preview` (`social.html`
    includes that by name). It adds the token to every same-origin link and GET form, and to a
    link added later at the moment it is clicked.
  - `site.js` sends no beacon in a preview: a beacon carries no token.
  - This was checked in a browser; there is no committed JS test.
- **The studio's frame** is `sandbox="allow-scripts allow-same-origin allow-forms allow-popups"`,
  **never `allow-top-navigation`**, with `referrerpolicy="no-referrer"`. `DesignScreensTest` pins
  the frame.
- **`PreviewIT` runs the whole chain**:
  - the draft appears only with a token;
  - the headers are on the page and on its 404;
  - nothing is counted, while a reader's request is;
  - no GTM, the refusals, the draft theme, and a template set.

### The demo content

"Demo Journal" (`core.seed.DemoContent`, `demo/demo-journal.json`): six sections, four authors,
twenty articles (five also in German and Spanish), their pictures, tags, a "Sections" group in the
main menu and a published link page; then, from the studio (`studio.demo.StudioDemo`), two bookings
of the website's own. Fictional throughout: invented towns, people and festivals, `example.com` for
anything outside, no embed of any third party, nothing near the old subject.

- **Written only when asked**: the new-website form's checkbox, `--create-site --demo`
  (`make site-create … DEMO=1`), `--seed-demo --site-id=` (`SeedDemoCommand`, what `make seed` runs
  in a one-off studio container), and `PlatformBootstrap`'s demo website. **Not a `SiteSeedStep`**,
  for the reason under *Architecture*. `core.seed` is scanned by the studio only;
  `DemoSiteRenderIT` builds the installer by hand.
- **Refused before anything is written** (`DemoContent.Refused`): a website that has articles
  (`HAS_ARTICLES`), and a content language the seed is not complete in (`LANGUAGE`; `languages()`
  is the intersection of every text's languages, English today). The scalars are written in
  `original()` and only the other languages go into `titles`/`texts`/…, so English text never sits
  under another language's code. The form names the languages through `StudioDemo.languageNames`.
- **Rerunnable**: everything but the articles is written only while absent, by fixed id or (a
  picture) by file name, and the articles go last in one `saveAll` — a run that died halfway is
  finished by running it again, and the article check still holds.
- **A newer article has a higher id** (`SeedIntegrityTest.idsFollowTheDates`): the latest list,
  the hero and `/latest` order by `_id` descending, as the studio's max-plus-one ids imply; dates
  are relative to the install, and today's hour that has not come yet moves to yesterday.
- **The pictures are drawn at install time** (`DemoImages`): gradients and shapes, **never text**
  (a runtime image may have no fonts, and drawing a glyph there throws), `TYPE_INT_RGB` (the JPEG
  writer mangles alpha), seeded by the key so a test can pin them, at `ImageService`'s required size
  with the quality stepped down until the file fits its byte limit. They are stored by
  **`ImageService.save(name, type, bytes, caption)`** — the upload's own `validate` and write — with
  alt text per language. Files are `demo-<key>.jpg`.
- **The menu group** is added after "Latest" only when the main menu links no category at all, so an
  operator's own sections are never doubled. **The link page** is written only while absent,
  published, named after the website, its link ids from `newLinkId`.
- **The bookings** go through `MgmtBannerService` and `MgmtBannerLayoutService` (a creative and
  slides as `GeneratedFile`s, so their checks and folders apply): an image booking to `/latest` and
  an HTML5 rotator `journal-sections` on the `sidebar` skeleton, three slides to three sections.
  **A target must be absolute**, so they use the website's public origin (`StudioSites`); with no
  host, or with any booking already there, there are none. A failure costs the bookings only
  (`Outcome.promotionsSkipped`). Their texts say `{name}` (`DemoContent.NAME`) where the website's
  name goes, so a website advertises itself, not "Demo Journal". Their file and folder names were
  checked against EasyList and EasyPrivacy: no size, no `ad`, `banner` or `house`.
- Tests: `SeedIntegrityTest` (references, ids, whole translations, clean bodies, every address one
  the site serves), `DemoContentTest` (what is written, refusals, the rerun), `DemoImagesTest`,
  `StudioDemoTest`, `PlatformBootstrapTest` (the fresh install's path),
  `PortalWebsitesTest.demoLanguageIsCheckedFirst`, `PortalScreensTest`'s demo cases, and
  `DemoSiteRenderIT` (every page type on both sets).

### Jobs

`/mgmt/jobs` (`MgmtJobController`, `content.read`/`content.write`) is the website's background
work, run by the studio itself: PDFs of articles (*PDF export*) and of journal issues (*Journal
issues*), and AI translations. It replaced an external
Python runner and its `/mgmt/tools` queue.

- **A `Job` lives in the control plane** (`core.control.data`, collection `job`), not the website's
  database: one runner sees every website's queue, and a job of a deleted website can be found and
  refused instead of recreating its database. Finished jobs expire after 30 days (`finishedOn` is a
  TTL index). **`JobQueue`** (`core.jobs`, through `controlPlaneMongoTemplate`) is its only
  writer. It is not a bean of core's: the studio's `JobsConfig` declares it, and the site never
  scans `core.jobs`.
- **A claim is one `findAndModify`** over the queued jobs and the running ones whose lease ran
  out. It takes priority first, then the oldest, and skips organisations already running
  `pacod.jobs.per-organisation` jobs. It raises `attempt` and names the `worker`.
- **Every write after the claim is fenced** on `(id, worker, attempt, RUNNING)`: heartbeat,
  progress and finish. A worker that stalled past its lease and was taken over writes nothing.
  `JobQueueIT` pins this against real MongoDB, with two workers and a stale finish.
- **`JobRunner`** (`pacod.jobs.runner.enabled`; off in the tests and the `--create-site` run)
  runs `pacod.jobs.workers` virtual threads. Each claims a job, **binds its website**, and runs
  its `JobHandler` while a heartbeat (5 s) extends the 60 s lease and reads back a cancel.
  - A restart abandons a run. The next claim runs it again **from the start**, at most
    `MAX_ATTEMPTS` (3) times; `expireAbandoned` fails it after that. **So every handler must be
    safe to rerun**: write whole files, write targeted updates.
  - **A job of a website that is gone or `DELETING` is cancelled unrun.**
  - **`JobContext.checkpoint()`** between items throws `JobStopped` on a cancel, a lost lease or
    a deleted website. Nothing is written into the website after a failed checkpoint. A lost
    lease ends the run without any write.
  - `PortalWebsites.delete` cancels the website's jobs right after `retire()`.
- **The log keeps its last 64 KB** (`Job.MAX_LOG`) and counts all of it (`logChars`).
  - `…/status?since=` answers the text after an offset, or `replace` when the page fell behind
    the window. `mgmt.js` polls it and appends with `textContent`.
  - The poll is on `MgmtPresenceInterceptor`'s skip list and pinned anonymously in
    `MgmtSecurityTest` and `make smoke`.
- **Job ids are 24-hex `ObjectId`s**, checked before any lookup, and every lookup carries the
  bound website: another website's job is a 404. `/mgmt/jobs/pdf` is a literal and beats `/{id}`.

### PDF export

`PDF_ARTICLES` (`PdfArticlesHandler`) renders each article's print view through **Gotenberg**,
once per language the article is written in: `original()` and `translatedIn()`. It writes
`resources/pdfs/articles/<lang>/article-<id>.pdf` into the website's folder
(`SiteStorage.articlePdf`), whole: a temporary file, then an atomic move (`PdfRenderer.write`).

- **Two formats, two modes, and the defaults are data.** `PdfFormat` (`STANDARD`, the print view
  as it was; `SCIENTIFIC`, a full-width head with abstract and keywords over the body in two
  columns) and `PdfMode` (`CODE`; `LLM`, *AI* below). **`PdfSettings`** (`pdfSettings`, id `pdf`,
  edited at `/mgmt/pdf`: read `content.read`, write `website.manage`) holds the defaults, the
  paper (`PaperSize`), margins, the journal's name per language and ISSN, the running head and
  page numbers, and what the gateway is asked for. **A job's blank format and mode are resolved
  against the settings when it is queued** and stored in its parameters, so a queued job does what
  its form said; paper, margins and headers are read when it runs. The cards on `/mgmt/jobs`, the
  article editor and the issue editor share `fragments :: pdfChoices` (the default named first,
  the AI mode `disabled` with a hint where `AiAccess.refusalHere()` refuses; the controller checks
  again).
- **The print view picks its template from the enum** (`?format=` → `article-print` or
  `article-print-scientific`, anything else standard) and its abstract and keywords from
  **`PrintExtras`**: in `CODE` the article's `descriptionIn(lang)` -- never
  `ArticleModel.getDescription()`, which makes a line up -- and its translated tags; in `LLM` the
  `PdfEnrichment` only while its `sourceHash` matches the current title and body, else the code
  mode's. **One PDF per article and language whatever the format: the latest render wins.**
- **Header and footer are Gotenberg's `header.html`/`footer.html` files** (`RenderOptions`),
  built by `PdfHeaders` from data alone: every value escaped, an explicit `8pt` and Noto/DejaVu
  (Chromium renders them apart from the page, at a near-zero default size, loading nothing), and
  **no translated word** -- a PDF is in the content's language, which the studio's four bundles
  need not cover; month names come from the JDK. `PdfRenderer.options` widens a margin that holds
  one. `GotenbergClient.inches` writes `0.6`, never a locale's `0,6`.
- **`print.css`** (`/site-assets/print.css`, linked by the print templates only): unconditional
  rules, font stacks that ship in Gotenberg's image (the web fonts never load there), `.sci-body`'s
  columns with guards for legacy inline widths and `<font>`, frames hidden (JavaScript is off),
  the issue's cover and contents. Hyphenation follows `<html lang>`.

- **Gotenberg reads the site on pacod-net** (`pacod.pdf.site-url`, `http://cms-site:8443`),
  where no host names a website.
  - Every request of the render carries **`X-Forwarded-Host`**, one of the website's hosts (the
    primary first). The site applies it as Caddy's forwarded host, because
    `SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK`; `TwoWebsitesIT` sets the same.
  - Every request also carries **`X-Pacod-Print`**, a `PrintTokens` token. That is the shared
    preview key under its own purpose (`pacod-print`, `SharedSecret`), five minutes, one website.
  - Each header is **scoped to that origin** (`value;scope=<regex>`, Gotenberg 8.12+). Go's
    regexp reads Java's `Pattern.quote` (`\Q…\E`).
- **`PrintFilter`** (site, -114, beside the preview filter) handles that header.
  - A valid token marks the request uncounted and `SitePrint`, so the chrome is
    `withoutTracking()` and the response is `no-store`.
  - A bad token is a plain 403.
  - `CrawlerTrafficFilter` **skips every request already marked uncounted**. Gotenberg's Chromium
    says HeadlessChrome, and every page and image of a render would fill the crawler log; the
    preview's headless visits are skipped for the same reason.
- **Gotenberg is the one thing on the stack that fetches what an author wrote**, since an article
  body accepts any markup. Compose therefore starts it locked down:
  - `--chromium-allow-list=^http://cms-site:8443(/|$$)` with private and public addresses otherwise
    denied;
  - JavaScript off, `downloadFrom` off, and no published port.
  - A body framing `http://cms-studio:7443/…` or `http://pacod-id:8071/…` renders a blocked frame; that was
    checked on the stack. The print view's web fonts never load there.
- **The print view is `/article-print/{id}`**, renamed from `/article-clean/{id}`. It links the
  website's published theme. **A token is minted for every render** (`PdfRenderer`): it lasts five
  minutes, and an issue job renders for longer.
- **The site links a PDF only once it exists** (`SitePdfs`, one file check per article view); it
  used to link one for every article, a 404 for each never exported.
- `pacod.pdf.gotenberg-url` blank, or no shared key, means PDF export is off: `pdfEnabled`
  (`MgmtControllerAdvice`) hides the forms. The article editor's PDF card queues that article,
  next.
- **Tests:**
  - `GotenbergClientTest` runs against a local stand-in (paper, margins, the header and footer
    files);
  - `PdfArticlesHandlerTest` runs on a temporary folder (format, mode, the AI refusal, an
    enrichment failure failing its item unrendered); `PdfHeadersTest`;
  - `PrintIT` checks a render uncounted, a reader counted, and bad tokens refused;
  - `PrintExtrasTest`, `ArticleTemplateControllerTest.printFormatPicksTheTemplate`,
    `PublicTemplateRenderTest.printViewsShowTheFrontMatter`/`scientificPrintView`;
  - `CrawlerTrafficFilterTest.uncountedRequestsAreSkipped`.

### Journal issues

A **`JournalIssue`** (`core.data`, collection `issue`; not `Issue`, which is `LayoutResolver`'s) is
a cover, an editorial and an ordered list of articles, generated into one PDF per language and, once
`PUBLISHED`, listed at `/issues`. Edited at `/mgmt/issues` (`MgmtIssueController`,
`MgmtIssueService`; `content.read`/`content.write`, publishing `content.publish`, deleting
`content.delete`).

- **`articleIds` is the content.** "Fill from the month" (`fillMonth`) replaces it once with the
  published articles dated in the issue's month (`findPublishedIdsBetween`, a closed-open range,
  oldest first); it is then reordered and trimmed by hand, at most `MAX_ARTICLES` (100). A
  published issue never changes because an article was redated.
- **Two writers, two sets of fields, never a whole save**: the form `$set`s its own fields
  (`MongoTemplate.updateFirst`), the job only `renditions.<lang>` (`recordRendition`, the code
  checked before it becomes a field path). Ids are max-plus-one, **inserted**, so a race is a
  duplicate key; `{volume, number}` is unique.
- **`PDF_ISSUE` (`PdfIssueHandler`)**: the issue's published articles in its order (others logged
  and left out), each rendered on its own with the issue's running head (`PdfHeaders.issueLine`)
  and no footer, its pages counted with **PDFBox**; then the site's **`/issue-print/{id}/front`**
  (cover, editorial, contents) with each article's first page in its address
  (`?ids=…&pages=…`). The front's length depends on what it prints, so the numbers are recomputed
  until it holds -- first guess cover + editorial + one page, at most `MAX_FRONT_RENDERS` -- and
  **the printed numbers are the PDF's own page numbers**. `IssuePdfAssembler` merges, adds a
  bookmark per article, titles the document and stamps the page number on every page but the
  cover: **digits only, in the Standard 14 Helvetica** (no font file, no Cyrillic); every word is
  laid out by Chromium, bookmarks are PDF strings.
- **Whole or nothing per language**: pieces in a temporary folder deleted in `finally`; a failed
  article fails its language, never a partial issue. Every PDF is written to
  **`private/pdfs/issues/<lang>/issue-<id>.pdf`** (`SiteStorage.issueDraftPdf`), which no address
  names (`MediaController` serves `assets/` and `resources/` only); while the issue is published,
  also to `resources/pdfs/issues/…` (`issuePdf`), read back fresh after the render.
- **Publishing copies, unpublishing deletes**: `publish` sets the status and copies every private
  PDF to its public address; `unpublish` deletes the public copies; both idempotent. The studio
  downloads the private one (`/mgmt/issues/{id}/pdf/{lang}`) as an `attachment` with `nosniff` and
  a sandbox CSP -- never inline on the studio's origin.
- **`/issue-print/{id}/front` answers only behind a valid print token** (`SitePrint`, else 404):
  it reads drafts. `ids` and `pages` must be number lists of one length. The contents list
  published articles only (`IssueReader.contents`, `findPublishedOutlineByIdIn`).
- **The public pages** (`IssueController`): `/issues` and `/issues/{id}`, published issues only
  (`JournalIssueRepository`'s public block), `?lang` then the cookie then `original()`, no
  `/{lang}/` prefix, counted and advertised as `BannerPage.LIST`. A PDF is linked once its public
  file exists (`SitePdfs.issue`). Both are in the sitemap; robots disallows `/issue-print/`. The
  content is `fragments/issues :: list`/`:: detail`, each set's page around it.
- **Tests:** `IssuePdfAssemblerTest` (real PDFBox documents: order, bookmarks, digits from page
  2), `PdfIssueHandlerTest` (the front settling, draft vs published files, a failed language,
  refusals), `MgmtScreenRenderTest.issueScreens`/`pdfSettingsScreen`, `IssueRolesTest` (an author
  neither publishes, deletes nor changes the defaults; an editor publishes), `MgmtSecurityTest`,
  `IssueControllerTest`, `SitePdfsIssueTest`, `PublicTemplateRenderTest.issuePages`/
  `issuePrintFront`, and `JournalIssuesIT`.

### AI (pacod-gw)

Every AI feature calls **pacod-gw** (OpenAI's chat API, `/v1/chat/completions`) through
**`AiGateway`** (`studio.ai`) and nowhere else. The address and key are `pacod.ai.gateway-url`
and `pacod.ai.api-key` (`PACOD_GW_URL`, `PACOD_GW_API_KEY`); without both, AI is "not set up". A
feature's model is `pacod.ai.models.<feature>`, else `pacod.ai.model` (`@fast`, a pool).

- **The client.** It asks for a strict JSON-schema answer.
  - **Attribution uses opaque ids only**: `x-gw-end-user` (the website), `x-gw-task` (the
    feature), `x-gw-trace` (the job), `x-gw-user-id` (a `usr_` id).
  - `x-gw-tags` is sent only with `pacod.ai.tags`, because a workspace counts tag keys it declares.
  - **A 429 is retried once**, after its `Retry-After` (at most 30 s). A 402 (budget spent) or a
    504 (the model may still be generating, and billing) is never retried. The gateway already
    fails over between models, and a second retry layer would multiply calls.
  - **An answer that is cut off (`finish_reason: length`), refused, or not the JSON asked for is a
    failure.**
  - `usage.cost.amount` is kept in millionths of a dollar (`Job.costMicros`).
- **`AiAccess` decides whether AI may be used**, reading fresh: switching AI off must stop the next
  job. The checks, first refusal first (`AiRefusal`, labelled in the jobs bundles):
  - a gateway is set up;
  - the organisation is switched on (`Organisation.aiEnabled`, a platform admin's, off by default);
  - the website is not switched off (`Website.aiDisabled`, "disabled" so that old documents read
    as allowed; the website's settings screen);
  - for a screen, the person holds `ai.use`.

  A job checks again at its start: a switch may have flipped after it was queued.
- **Everything the model returns is untrusted.** The material goes into the *user* message, alone;
  the instructions go into the system message, which says the material is never instructions.
- **Translation (`TRANSLATE_ARTICLES`, `TranslateArticlesHandler`).**
  - It makes **one call per field**: title, description, body.
  - **`HtmlTranslation`** checks each answer. A body's answer must have exactly its source's
    skeleton (jsoup): the same elements in order, the same attributes and values (only `alt`,
    `title`, `aria-label`, `placeholder` may change), and the same script, style and comment data.
    What is stored is jsoup's serialisation of the checked output, never the raw string.
  - A field that was text stays text: an element added there is refused.
  - So a model can add nothing executable a body did not already have. A legacy body keeps its
    tables, `<font>`, `<style>` and YouTube frame, where an allow-list sanitiser would strip them.
  - **A language is written whole or not at all**, after a checkpoint, by
    **`TranslationWriter`** (core). That is one pipeline update replacing only that
    language's entries in `titles`, `descriptions` and `texts`, with `$filter` then
    `$concatArrays`. Values are wrapped in `$literal`, because "$100 off" is a field path in a
    pipeline. **Never load-modify-save** around a model call that takes seconds.
    `TranslationWriterIT` checks this against real MongoDB.
  - `mode=missing` (the default) skips the languages an article has; `all` translates again.
- **The rest of a website (`TRANSLATE_SITE`, `TranslateSiteHandler`)**: standing pages, category
  names, tags, menus, the social page — the parts ticked (`parts`, allow-listed by
  `TranslateSiteHandler.parts`). The article job's prompt, schema and checks; a page body keeps its
  skeleton.
  - **From where**: a page from `original()`, and a page with no body in it is skipped, never
    translated from a fallback; a category from its own `lang`; a tag from its name (the id); a
    menu or social label from the original, else English (the seed's menus are English).
  - **Writes**: pages, categories and tags through `TranslationWriter.putPage`/`putCategory`/
    `putTag`, the same pipeline update. **Menus and the social page nest their lists**, so they are
    read raw (`read`), rebuilt with `withText` and written by **`replaceIfUnchanged`, a
    compare-and-set on the very values read**: an edit made meanwhile fails that menu's language
    with "run it again" instead of being overwritten. **Only the fields that changed are set and
    compared**: a key the document lacks stays absent, never `null`.
  - Codes are written lowercase and **replace an entry of any case** (`$toLower` in the filter),
    as `Page`'s reads match them.
  - **Tags are their own part, unticked by default, and a run makes at most `MAX_CALLS` (2000)
    gateway calls**: the nightly rebuild only adds tags, and one click must not spend the
    installation's key. A run that hits the limit ends saying so; in `missing` mode the next run
    carries on.
  - Terms and privacy are translated like any page; only the three text lists are written, so
    `officialInDefaultLanguageOnly` and its notice stay.
  - `POST /mgmt/jobs/translate-site`, a card of its own on the jobs screen (`content.write`, AI).
- **Where translation is offered.**
  - The jobs screen's form and the article editor's card (`aiAvailable`, the website's languages
    but the original). The card is its own form, outside `#article-form`, because a `name` inside
    that form would be bound by `save()`; pinned in `MgmtScreenRenderTest.editorTranslateCard`.
  - `POST /mgmt/jobs/translate` re-checks AI and keeps only languages the website offers.
- **PDF front matter** (`PdfEnricher`, the PDF jobs' `mode=llm`; feature `pdf-enrich`, model
  `pacod.ai.models.pdf`). The title and the body **as plain text** go alone into the user message;
  the strict schema asks for `abstract`, `keywords` and `runningTitle` in the article's language.
  - **The body is never sent back or changed**: the answer is front matter, laid out above the
    author's own text.
  - Checked as typed: `HtmlSafety.text` on every string, the abstract non-blank and bounded,
    keywords trimmed, comma-free, deduplicated ignoring case and capped at `keywordLimit()`.
  - **A `PdfEnrichment` row** (`pdfEnrichment`, id `<article>:<lang>`) is written whole after a
    checkpoint, never on `Article` (serialised raw by `/web/v1`, saved whole by the editor).
    `sourceHash` ties it to the title and body: a matching row is reused unless `refresh`, and the
    site ignores a stale one. An article's rows go with it (`ArticleService.deleteArticle`).
  - An article with no body in a language gets no call: a fallback body is never summarised as
    another language's. At most `MAX_CALLS` (500) calls a run; the run then ends saying so.
  - `JobType.usesAi(params)` is true for a PDF job in `mode=llm`; the job checks `AiAccess` at its
    start, the forms through `refusalHere()`.
- **Alt text** (`/mgmt/ai/alt`, `WritingAssist.alt`). The model looks at the picture itself. The
  file is read through `MgmtImageService.bytesOf`, which keeps it inside the image folder.
  - The picture is sent inline as a `data:` URL: a JPEG at most 1024 px on its longer side, drawn
    on white, because PNG transparency would turn black. The caption goes along as context.
  - The model is `pacod.ai.models.vision`. The answer is text, at most 250 characters.
  - `ImageMedia.alts` is a `TextItem` per language. **`ImageMedia.altFor(lang)`** resolves through
    `LanguageChain.forRequest`, then the caption, then `""`.
  - Every template's article image (gallery, print view, list and search thumbnails via
    `ListItem.getImageAlt`, both sets) renders it, where the caption used to stand in.
  - The image screen's one form (`saveDescription`) carries the caption and an `alt_<code>` per
    website language, the original first. A blank removes that language.
- **Design assist** (`DesignAssist`; `POST /mgmt/design/theme/assist` and
  `…/layouts/{type}/assist`, `design.write` plus AI). A theme or a layout is drafted from a few
  words.
  - **The schema is built from what the website can take**: the token vocabulary (`ThemeToken`:
    colours with their `@dark`, fonts as an enum, lengths with bounds), or the page type's regions
    on the current set and the section types it renders, with their settings.
  - In strict mode every property is `required`, so "leave it" is `null`.
  - **Then everything is checked again as if typed**: `ThemeCss.clean`, `SectionSettings.check`,
    HTML settings through `HtmlSafety`. Only what passes reaches the **draft**:
    `DesignTheme.saveDraft` keeps the draft's CSS, and `DesignLayouts.replaceDraft` keeps the
    page's own `primary`, id and region.
  - What failed is named in the flash. **An answer with nothing usable changes nothing**, and an
    empty layout (the mock's) must never wipe a draft.
- **Writing help** (`WritingAssist`, `MgmtAiController` at `/mgmt/ai/*`, `ai.use`, JSON for
  `fetch`). It offers a passage rewritten six ways, a summary, and tags.
  - Answers go back to the operator; **nothing is written here**.
  - A passage's answer may change its markup, so it cannot be held to a skeleton. **`HtmlSafety`**
    holds it to an allow-list instead: structure, emphasis, simple tables, and links that are
    http, https, mailto or relative. Images survive only where the selection already had the same
    `src`. Never `style`, `class`, `on*`, scripts, frames or forms.
  - A summary and tags are text only. Tags are at most eight, without commas.
  - Without AI the endpoints answer 409 with `{error}` saying why, which `readJson` shows.
- **In the editor** (`mgmt.js`), the toolbar's **first** button is "AI". At the end it fell into
  TinyMCE's ••• overflow.
  - It opens a dialog: what to do, then Ask, then a preview of the cleaned answer (rendered in the
    studio's document), then Insert, which replaces the bookmarked selection.
  - TinyMCE requires `text` on a dialog's cancel button; without it `windowManager.open` throws
    and nothing appears. The string is TinyMCE's own `'Cancel'`, which its language packs
    translate.
  - The action labels are literal `t('…')` keys, because `MgmtJsTextTest` sees only spelled-out
    keys.
  - "Suggest a summary" and "Suggest tags" sit under their fields. They render `hidden`, have no
    `name`, are inside `#article-form`, and fire an `input` event so the unsaved-changes guard sees
    the change.
  - Checked in a browser: the dialog, Ask, the preview, Insert, both suggestions.
- **The installation screen tests the gateway** (`POST /mgmt/platform/ai-test`, `platform.manage`):
  `AiGateway.ping`, one call of at most 64 tokens with the default model and **no website or person
  attributed** (nothing is bound there; the headers are left out). The flash names the time, the
  model and the gateway's request id. A refusal, here and in every job log, adds the gateway's
  error code and request id when they look like identifiers, and nothing else of its answer.
- **The development gateway** is pacod-gw-lite in front of pacod-gw's mock providers (services `pacod-gw`
  and `gw-mock`, in `docker-compose.standalone.yml`, compose profile **`dev-gateway`**, images from `PACOD_GW_SRC` via
  `make images`).
  - The mock echoes the last user message and fills a JSON schema with it, so under the mock a
    translation **equals its source**; tags, arrays and numbers come back empty or 0.
  - `make init` writes the key (`PACOD_GW_API_KEY`) where the profile runs, and the gateway and the
    studio share it.
  - `env/dev.env` and `env/stage.env` set `COMPOSE_PROFILES=dev-gateway,mailpit`; `env/prod.env`
    sets it empty, and production names its real gateway in `.env`.
- **Tests:**
  - `AiGatewayTest` runs against a local stand-in: headers, the one 429 retry, 402 and 504,
    cut-off, refusal, not-JSON, the ping, a refusal's code and request id;
  - `PortalScreensTest.aiConnectionTest`: the card, both flashes, 403 for an organisation's admin;
  - `HtmlTranslationTest` runs on a legacy body and injection attempts;
  - `TranslateArticlesHandlerTest` and `TranslateSiteHandlerTest` use an echoing gateway, as the
    mock does;
  - `TranslationWriterIT` uses real MongoDB: every id shape (int, slug, name), an absent list, a
    `$` value, case, and the compare-and-set refusing a changed menu;
  - `HtmlSafetyTest` and `WritingAssistTest`;
  - `MgmtScreenRenderTest.writingHelp`/`editorAiControls`, and `MgmtSecurityTest` pins the three
    endpoints anonymously.

### Studio languages

The studio's UI is **English, Bulgarian, German or Russian**, switched in the topbar and under the
sign-in links; it covers screens, flashes, validation, errors, enum labels, `mgmt.js` strings and the
TinyMCE UI, and is passed to pacod-id as `ui_locales`. Content stays in the language it was written
in.

- **The UI language is not `lang`** (the *content* language, and the public site's cookie). It is
  the **`mgmt-lang`** cookie (path **`/`**, a year, HttpOnly, `Secure` from `isSecure()`), set by
  `GET /mgmt/ui-language/{code}` — `permitAll`, redirecting to the `Referer` **path** when it is a
  studio screen, `/sites/<id>/mgmt/…` included (checked through `StudioPaths.withinSite`; no `//`,
  `/\`, `..`), with `Location` written by hand (`redirect:` expands `{…}`). The path is `/` because
  one choice has to reach `/mgmt/` and every `/sites/<id>/mgmt/`; the studio's own host keeps it off
  the public sites. Pinned in `MgmtLocaleTest` and `make smoke`.
- **The default is `pacod.studio.default-language` (default `en`), validated at startup;
  `MgmtLocaleResolver` never reads `Accept-Language`** (MockMvc's English default would decide every
  render test). The cookie is an allow-list: `fr` resolves to the default, not `Locale("fr")`.
- **Bundles are `i18n/{common,articles,banners,pages,ops,portal,design,jobs,pdf}{,_bg,_de,_ru}.properties`**
  (`MgmtText.BASENAMES`), English base.
  **A new key goes into all four.** Values go through `MessageFormat` (`alwaysUseMessageFormat`):
  an apostrophe is always `''`, ids and years are `{0,number,#}`, markup only in keys without
  arguments (`th:utext`), counts as "Label: {0}" (Russian plurals defeat `ChoiceFormat`).
  `MgmtBundleTest` enforces it; `MgmtText.newMessageSource()` is the only `MessageSource`.
- **`MgmtText` is the one resolver** (`msg`, `of(e)`, `label(enum)`, `text(enum, attr)`). Templates
  reach it as **`${mgmtText…}`, a request attribute, never `${@mgmtText…}`**: Thymeleaf 3.1's
  restricted mode refuses bean references (and `th:attr`; use `th:data-*`).
- **Exceptions carry a key**: `MgmtOperationException`/`MgmtNotFoundException` take `(code, args…)`
  with `getMessage()` resolved through `MgmtText.base` (English); controllers show `text.of(e)`.
  Core's `MediaException` carries an optional key. Enum labels come from `enum.<Type>.<NAME>` (and
  `.hint`, `.helpText`, `.commandPreview`), falling back to the enum's English `getLabel()` —
  `MgmtEnumTextTest` asserts every constant, since the fallback would hide a missing key.
- **`#numbers`/`#temporals` follow the UI language** — keep them out of SVG, `style=`, `data-*`.
  `mgmt.js` reads strings from `<script id="mgmt-i18n">` via `t(key, …args)`.
- **The guards, because each failure answers 200**: `MgmtTemplateTextTest` (no Cyrillic in
  `templates/mgmt/*.html` or `templates/error.html` outside comments; every `#{key}` exists),
  `MgmtJavaTextTest` (no Cyrillic literal in studio Java, stored data allow-listed),
  `MgmtJsTextTest` (every `t('…')` has its key, none dead).
  **The studio test profile sets `pacod.studio.default-language=bg`**, and `MgmtScreenRenderTest`
  then **re-renders every screen each test GETs with `mgmt-lang=en`**, asserting `lang="en"`, no
  `??key??` and no Bulgarian interface string.

## Spring Boot 4 gotchas encountered here

- **A `securityMatcher` must not repeat the context path.** Inside a website the studio's chain
  sees `/mgmt/articles`, not `/sites/demo/mgmt/articles` — the deleted API application learned it
  first, when a `securityMatcher("/api/**")` under the context path `/api/` matched **nothing**. A
  request matching no chain passes `FilterChainProxy` unsecured, CORS included, without a warning;
  that is also why `StudioSiteFilter` must run *before* security.
- **`spring.web.error.whitelabel.enabled`, not `server.error.whitelabel.enabled`**, which Boot 4
  ignores without a word (*Template sets, layouts and themes*).
- **Two MongoDB prefixes, both valid**: `spring.mongodb.*` is the driver connection (the old
  `spring.data.mongodb.*` form is deprecated at level `error`); `spring.data.mongodb.*` is still
  Spring Data behaviour (`auto-index-creation`). Do not "fix" one into the other.
- **Test slices moved** to `org.springframework.boot.webmvc.test.autoconfigure`
  (`spring-boot-starter-webmvc-test`); controller tests use standalone `MockMvc`.
- **`@WithMockUser` is invisible without `apply(springSecurity())`** on a
  `webAppContextSetup(context)` MockMvc: every request is anonymous, and "requires the role" passes
  for the wrong reason. `spring-security-test` is a root-pom test dependency.
- **`@Query` on a Mongo repository has no `limit`**, and an `Optional` return throws on several
  matches. Pass a `Pageable`.
- **A closed date range cannot be a derived query**: `findByStartBetween` is **exclusive at both
  ends** on Mongo, and `…GreaterThanEqualAndStartLessThanEqual` throws. Write `@Query("{ 'start': {
  $gte: ?0, $lte: ?1 } }")`, as `SiteMetricHourRepository.findInWindow` does.

## Integration tests

`make it` (`./mvnw verify -Pit`, failsafe) runs `pacod-cms-site/…/site/it/*IT` against MongoDB on
Testcontainers: `TwoWebsitesIT` provisions `alpha` (`alpha.test`) and `beta` (`beta.test`) in one
running site — under the organisation `org_it`, which it saves first, since the provisioner refuses
an explicit organisation that does not exist — and its subclasses check routing, host resolution,
caches, storage and metrics stay apart; `SiteLifecycleIT` creates, serves and deletes a third and
checks that a flush afterwards does not bring its database back. `LayoutRenderIT`,
`TemplateSetIsolationIT` and `PreviewIT` add websites of their own where they change one (a layout,
a template set, a tag manager), so the shared context's `alpha` and `beta` stay as the others
expect; `FramingIT` reads the framing headers over real HTTP; `PublicArticlesIT` checks that only
published articles reach a reader, and each website's sitemap; `DemoSiteRenderIT` gives two new
websites (one per template set) the seed steps and the demo content, built by hand, and reads every
page type back. The base points `app.image.folder` into its storage root, so a picture written there
is one the site serves. **One `MongoDBContainer`, started in a static block**, not `@Container` (which
stops it after each class while Spring keeps the cached context pointed at it); **the JDK
`HttpClient` on HTTP/1.1** (Tomcat refuses the h2c upgrade) **with
`jdk.httpclient.allowRestrictedHeaders=host`**, or it refuses the `Host` header. The base re-enables
`app.metrics.enabled`, parks the scheduled flush, and sets the studio's origin and a preview
secret.

## Configuration

Settings and paths read from the environment with defaults (`MONGO_*`, `MGMT_*`, `SSL_*`,
`LOG_DIR`, `STORAGE_ROOT`), all in `.env.example` — except the secrets, MongoDB's two passwords and
pacod-id's, which have **no default anywhere** (below); `docker/mongo-init` refuses to create the
application's account without one.

- **MongoDB.** `spring.mongodb.database` (`MONGO_DATABASE`, `pacod_cms`) is the control plane;
  the applications authenticate against `${MONGO_AUTH_DB:admin}` (compose passes none), where
  `docker/mongo-init/01-create-app-user.js` — run only on an empty data directory — grants
  `readWriteAnyDatabase` and `dbAdminAnyDatabase`: rights on databases that do not exist yet.
- **Per service.** `cms-site`: `METRICS_VISITOR_SALT` (blank = a per-restart salt), `CRAWLER_TRACKING`,
  `CRAWLER_VERIFY`, `STUDIO_ORIGIN` (who may frame it, see *Advertising*). `cms-site` and `cms-studio`:
  `PACOD_PREVIEW_SECRET` (the same value; blank = no previews, see *The draft preview*). `cms-studio`:
  `MGMT_SESSION_TIMEOUT`, `IMAGE_*` (compose defaults equal the code's), `PACOD_PDF_GOTENBERG_URL`,
  `PACOD_JOBS_WORKERS`/`PACOD_JOBS_PER_ORGANISATION`, `PACOD_GW_URL`/`PACOD_GW_API_KEY`/`PACOD_AI_MODEL` (*AI*;
  the URL defaults to `http://pacod-gw:8080`, the key has none, `make init` writes the dev gateway's), `SITE_PUBLIC_SCHEME`/`SITE_PUBLIC_PORT`, `PACOD_BOOTSTRAP_DEMO_HOSTS` (from
  `DEMO_HOSTS`) and `PACOD_BOOTSTRAP_DEMO_MINIMAL_HOSTS` (from `DEMO_MINIMAL_HOSTS`), `PACOD_ID_ISSUER` (from `ID_ISSUER`), `PACOD_ID_INTERNAL_URL` (default `http://pacod-id:8071`),
  `PACOD_ID_{STUDIO,SERVICE}_SECRET` (from `CMS_ID_*_SECRET`), `STUDIO_ORIGIN`,
  `PACOD_ID_PROVISIONER_TOKEN` (empty default: no provisioner, platform admins create
  organisations), `PACOD_SITE_DOMAIN` (default `localhost` when unset; a blank value stays blank,
  `${…-…}`), `PACOD_BOOTSTRAP_DEMO_ENABLED` (from `DEMO_WEBSITES`), `PACOD_ORG_CREATION` (default
  `self-service`), and `PACOD_MAX_ORGS_PER_USER`/`PACOD_MAX_SITES_PER_ORG`/`PACOD_AI_FOR_NEW_ORGS`
  passed unvalued (the jar's defaults 3, 5, false; starting values only). `pacod-id`:
  `CMS_ID_ISSUER`/`CMS_STUDIO_ORIGIN` from the same two, expanded by `docker/pacod-id/pacod-id.toml`
  into the realm's issuer and the studio client's exact redirect URIs — so **`ID_ISSUER` must name
  `ID_HOST`, and `STUDIO_ORIGIN` `STUDIO_HOST`** — the Makefile derives both from the hosts unless
  `.env` names them, and `make check` refuses stage or prod when they disagree. Mail:
  `PID_SMTP_HOST`/`_PORT`/`_TLS` (`pacod-mailpit` by default), `PID_SMTP_USERNAME`/`_PASSWORD` passed
  unvalued, `ID_MAIL_FROM`. `pacod-caddy`: `CMS_STUDIO_SITE`/`CMS_PUBLIC_SITE` (from `STUDIO_HOST`),
  `ID_HOST`, `CADDYFILE`, `SSL_CERT_FILE`/`SSL_KEY_FILE`, `CADDY_GLOBAL_OPTIONS`. `cms-mongo`,
  `cms-site`, `cms-studio`: `PUBLISH_IP` in
  front of their published ports (blank: every interface, IPv4 and IPv6; stage and prod
  `127.0.0.1`).
- **The secrets** (`MONGO_ROOT_PASSWORD`, `MONGO_PASSWORD`, and pacod-id's
  `PID_PG_SUPERUSER_PASSWORD`, `PID_DB_PASSWORD`, `PID_KEK`, `PID_TOKEN_PEPPER`,
  `CMS_ID_STUDIO_SECRET`, `CMS_ID_SERVICE_SECRET`, `ID_ADMIN_PASSWORD`) are
  `${…:?run make init}` in compose — a published fallback would be a published key.
  `scripts/init-env.sh` (`make init`) writes them into `.env` (hex, or base64 for `PID_KEK` and
  `PID_TOKEN_PEPPER`; never printed), and on an existing `.env` adds only the keys neither `.env`
  nor `.env-local` names. **It refuses to write a new value for MongoDB's passwords while MongoDB's
  data exists, or for pacod-id's while the `pacod-postgres-data` volume does**, and then writes nothing: a
  new value would not match what that data was created with. The bootstrap admin (`ID_ADMIN_EMAIL`, roles `id.admin,cms.platform_admin`, realm `cms`) is
  created once; a changed `ID_ADMIN_PASSWORD` changes nothing.
- **`pacod.content.default-language`** (fallback where no website is bound) and
  **`pacod.studio.default-language`** default to `en`. Test profiles also turn off
  `pacod.bootstrap.enabled`, `pacod.id.enabled`, `app.metrics.enabled`,
  `app.banner.impression-counting.enabled`, `app.crawlers.*.enabled` and
  `app.scheduler.tags.enabled`.
- **CORS** is `app.cors.allowed-origins` (`CORS_ALLOWED_ORIGINS`), origin patterns; a bare `*` is
  refused at startup (credentials are allowed). Unset it is `http://localhost:[*]` everywhere — set
  it in production. Compose declares it without a value so unset stays unset.
- **No keystore is committed** (`make keystore` makes one; see `docs/DEPLOYMENT.md`).
  Each base `application.properties` hardcodes `spring.profiles.active=prod`.
- **Logging is the pacod convention** (`pacod-demo/docs/LOGGING.md`). Both `logback-spring.xml`
  use core's `core.logging.PacodLogEncoder`: one event per line on stdout, JSON (`timestamp`,
  `level`, `service`, `logger`, `message`, then the MDC and SLF4J's `addKeyValue` pairs flat, then
  `error`/`stack_trace`) or text by `app.log.format` (`APP_LOG_FORMAT`, default `json`; `env/dev.env`
  sets `text`), never coloured. The file appender is always JSON: `${app.log.dir}/<service>.log`
  (`app.log.service`, `SERVICE_NAME`, default `cms-site`/`cms-studio`), rolled at midnight UTC and
  20MB to `<service>.<date>.<n>.log.gz`, 30 days, 600MB. The root level is `app.log.level`
  (`APP_LOG_LEVEL`) through `LogLevelDefiner` — **logback reads an unknown level, `warning`
  included, as DEBUG**, so do not put the raw value on `<root>`, and **never set
  `logging.level.root`**: Boot applies it over logback's, and its own parser fails on `warning`.
  Boot's `logging.file.*`/`logging.pattern.*` do nothing beside a `logback-spring.xml`; the banner
  is off (`spring.main.banner-mode`), as it is no event. `PacodLogEncoderTest` pins the shapes. A
  run with `-pl <app>` and no `-am` uses the core jar in `~/.m2`: an old one lacks the encoder, and
  the context then fails to start.

## Docker

The root `Dockerfile` builds both applications, chosen by the `MODULE` build arg; its `ENTRYPOINT`
passes container arguments through (`"$@"`), which `make site-create` relies on.

**Two compose files on one shared network** (pacod-demo's convention, `pacod-demo/docs/NETWORK.md`:
service key = `container_name` = DNS name; shared components `pacod-*`, cms's own `cms-*`, volume
keys likewise). **`docker-compose.yml`, the app file**, holds cms's own services: `cms-mongo`,
`cms-site` (8443 inside, `${WEB_PORT:-8080}`), `cms-studio` (7443, `${MGMT_PORT:-7070}`,
healthcheck `/mgmt-health`) and `cms-gotenberg` (PDF export, no published port, *PDF export*).
**`docker-compose.standalone.yml`** holds cms's copies of the shared components: `pacod-caddy` (the
front door, `${HTTP_PORT:-8000}`, `${HTTPS_PORT:-8443}` tcp and udp; volumes
`pacod-caddy-data`/`pacod-caddy-config` hold its certificates, its local CA and its CA account —
keep them, a CA rate-limits re-issuing), for sign-in `pacod-id` (`${PACOD_ID_IMAGE:-pacod-id:dev}`,
public listener 8070, internal 8071), `pacod-postgres` (volume `pacod-postgres-data`),
`pacod-db-init` (a one-shot that runs every `platform/postgres/*-db.sh` at every start — here
pacod-id's `id-db.sh`, a verbatim copy of pacod-id's: role and database `pacod_id`, password set
each time) and `pacod-mailpit` (every mail pacod-id sends; published on loopback only,
`${MAILPIT_PORT:-8125}`), and under the profile `dev-gateway` `pacod-gw`/`gw-mock` (the
development AI gateway, *AI*). **The Makefile's `COMPOSE` loads both**; the full stack (pacod-demo)
loads only the app file and provides the shared components once, which is why the app file names
them only by address (`http://pacod-id:8071`, `http://pacod-gw:8080`) and never in a `depends_on`
(one would need `required: false`). The network `pacod-net` is **external** and shared by every
pacod repo; `make network` creates it. **Only one stack running `pacod-*` containers fits on a
machine** (the container names collide). **`make up`, `make restart` and `make down` pass
`--remove-orphans`**:
the containers of services the files no longer define — `mongo`, `site`, `studio`, `caddy`, `id`,
`id-postgres`, `mailpit`, `gotenberg`, `gw`, renamed for pacod-net; `web` and `admin`, renamed
`site` and `studio` in Phase 8; and `nginx`, the front door until Caddy replaced it — would hold
their published ports; a service of an inactive profile is not an orphan (checked). Gotenberg's
command names `cms-site`, so an upgrade across a rename needs `make up`: `make restart` leaves the
old allow-list, and PDF export answers 403. **The renamed volumes start empty**: `make check`
refuses while an old one (`mongo-data`, `id-pg-data`, `caddy-data`) holds data its new name lacks,
and `docs/RUNNING.md` (*Moving to pacod-net*) copies them.

- **Caddy routes by host, and cms's rules are one file**, `platform/caddy/cms.caddy`, the fragment
  every Caddyfile imports from `/etc/caddy/sites/` — cms's own two (`docker/caddy/Caddyfile`,
  production, and `Caddyfile.dev`) and the full stack's. **A fragment has no global options and its
  snippets carry the `cms-` prefix** (`cms-front`, `cms-forward`, `cms-on-demand-tls`): every
  imported fragment shares one snippet namespace, and Caddy refuses a name declared twice. Its two
  sites take their addresses from `CMS_STUDIO_SITE` and `CMS_PUBLIC_SITE`, which the Makefile
  derives (`http://<STUDIO_HOST>, https://<STUDIO_HOST>` and `http://, https://` on dev,
  `https://<STUDIO_HOST>` and `https://` on stage and prod: a bare or `https://` address keeps the
  HTTP→HTTPS redirect, an explicit `http://` one would take port 80 over), and the catch-all's TLS
  directive comes from `CMS_PUBLIC_TLS` (`import cms-on-demand-tls`, set by the standalone file;
  unset in the HTTP-only full stack). **A `{$VAR:default}` set to the empty string is used empty**,
  not defaulted, so compose never passes those three blank. The Caddyfiles add the global options,
  pacod-id's site (`{$ID_HOST}` → `pacod-id:8070`, reusing `cms-front`/`cms-forward`; not in the
  fragment, because the full stack routes pacod-id with pacod-id's own) and the supplied pair.
  Checked: the new files adapt to the same JSON as the old single `routes.caddy` on dev and prod.
  `CMS_STUDIO_SITE` goes to the studio, `/` answering 302 to `/mgmt/sites` (a relative `Location`);
  `{$ID_HOST}` to pacod-id's public listener, Host and `Origin` unchanged (it answers its pages
  only on the issuer's exact host and refuses a `/ui-api` write from another origin; its internal
  listener is never routed); **every other host** to the site, which 404s a host no website claims
  — no list of websites in Caddy, no config change per domain. **The studio never shares a host
  with a website** — a website runs its owner's script — and in production needs its own
  registrable domain, which the studio enforces at startup (`StudioDomainCheck`).
- **`CADDYFILE` picks the file**: `env/dev.env` names `Caddyfile.dev`, stage and prod `Caddyfile`,
  which is also compose's default — a mistake loud rather than a plain-HTTP studio.
  `Caddyfile.dev` serves plain HTTP on `HTTP_PORT` and HTTPS on `HTTPS_PORT` from Caddy's local CA
  (`local_certs`); **every address in it names its scheme**, because a bare host gets automatic
  HTTPS, which redirects to `https://<host>/` on 443 — not the port a developer machine publishes.
- **A website's certificate is obtained on demand**, at its host's first handshake, only after
  `GET http://cms-site:8443/_internal/tls-ask?domain=` (`TlsAskController`) answers 200 — an `ACTIVE`
  website's host through `TenantDirectory`, else 404, so a scanner cannot make the installation ask
  a CA for names it does not serve. Development asks the same question of the local CA.
  `CADDY_GLOBAL_OPTIONS` adds one global line (`email …`, or `local_certs` to try the production
  file without a public CA).
- **A supplied pair is switched on by naming it.** `SSL_CERT_FILE`/`SSL_KEY_FILE` are mounted file
  by file (certbot's symlinks resolve on the host; unset, `docker/caddy/no-certificate` stands in,
  never read), and compose sets `CADDY_SUPPLIED_PAIR: ${SSL_CERT_FILE:+import supplied-pair.caddy}`,
  a directive in a variable like `CADDY_GLOBAL_OPTIONS`. That file is a site on `https://:4443`
  (unpublished) whose only job is loading the pair into Caddy's shared certificate cache: every
  handshake for a name the pair covers finds it there, and automatic HTTPS skips those names
  ("skipping automatic certificate management" in the log); the rest get theirs as usual. **The
  site has no hostname on purpose**: one named `supplied-pair.invalid` made Caddy order an ACME
  certificate for that name, and retry. `make check-tls` (run by `make up`) checks a named pair
  and passes without one. Checked on the stack: `localhost`, covered, got the pair;
  `demo.localhost`, not covered, an on-demand certificate.
- **The headers.** Host passes unchanged, and Caddy's `X-Forwarded-Host` is that Host **with its
  port**, so a redirect keeps `:8000` (`make smoke` prints each `Location`; a redirect that drops
  the port is still a 302); no `X-Forwarded-Port`. `X-Real-IP` is set to the TCP peer
  (`{remote_host}`), which the crawler log trusts. **Caddy *replaces* a client-written
  `X-Forwarded-For`** with the address it saw (nginx appended): `VisitorIdentity` cannot be fed a
  chosen key through it, and the skill's `traffic` goes to the site's own port for that reason.
  `X-Pacod-Print` (the PDF renderer's token, *PDF export*) is removed, so only a request on the
  compose network can carry one. `SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK` on both applications.
- **Body limits are `4MiB` (1 MiB for pacod-id), never `4MB`**: Caddy's `MB` is decimal, 4,000,000
  bytes, while Spring's `4MB` is 4 MiB — with `MB`, a body between the two would be Caddy's bare
  413 instead of the application's sentence. Caddy enforces it while the upstream reads the body,
  so a 302 to sign-in at 5 MB is no evidence either way. Checked: 4,100,000 bytes reach the site,
  413 at 4,194,305 bytes read.
- **Caddy needs no restart after the applications are recreated**: it resolves `cms-site` and `cms-studio`
  for every new connection (checked by forcing the site onto a new address). A `{$VAR}` is read
  when the Caddyfile is parsed, from the container's environment, so a changed host, Caddyfile or
  pair needs `make caddy-reload`, which validates in a one-off container first and then recreates.
  The healthcheck is Caddy's admin API (`127.0.0.1:2019/config/`), not a site address, which in
  production would redirect or need a certificate.
- **Caddy waits for `pacod-id` started, not healthy** (a pacod-id that is down must cost sign-in, not the
  websites); `cms-studio` does not wait for it at all (it retries in the background, 503 meanwhile).
  `PID_TRUSTED_PROXIES=private_ranges`, or pacod-id sees Caddy's address for everyone and one
  sign-in throttle covers all. **SMTP defaults to `pacod-mailpit`** (`PID_SMTP_HOST`, port 1025,
  `PID_SMTP_TLS: none`); production names a relay. `make destroy` drops `pacod-postgres-data`: every
  account and binding.
- **Logs (`pacod-demo/docs/LOGGING.md`).** Each compose file declares its own `x-logging` anchor
  (`json-file`, 10m, five files; anchors do not cross files) and **every service carries `logging:
  *logging`**, third-party ones and the profile-gated ones included — a new service gets it too.
  `cms-site` and `cms-studio` take `SERVICE_NAME`, `APP_LOG_LEVEL`, `APP_LOG_FORMAT` and `LOG_DIR:
  /data/logs` (the container's side of `LOG_DIR_HOST`; the studio once got the host path). The
  standalone file passes `PID_LOG_FORMAT`/`PGW_LOG_FORMAT` through with `text` as the default.
  `cms-front`'s `log` writes each request to stdout in `CMS_CADDY_LOG_FORMAT` (`json`, or `console`;
  compose never passes it blank) and **deletes the query parameters `token`, `code`, `state`,
  `next`, `flow`, `id_token_hint`, `login_hint`, `key`, the `Referer`/`X-Api-Key`/`X-Goog-Api-Key`
  headers and the response's `Location`**: the studio's and pacod-id's sign-in round trips carry
  `code` and `state` in the address, which a bare `log` recorded. Gotenberg logs at
  `--log-level=info`; MongoDB keeps its default (`docker/mongo/config/mongod.conf` is mounted but
  not passed with `--config`).
- **Caddy serves no files.** `/assets/**` and `/resources/**` are the site's `MediaController`, out
  of the folder of the website the Host names (the proxy cannot know which), kept inside that root
  (`StorageIsolationIT`), public 10-minute cache. `${STORAGE_HOST:-./.data/storage}` is mounted at
  `/data/storage` (`STORAGE_ROOT`) — **read-only in `cms-site`**, **read-write in `cms-studio`**, the only
  writer, which really deletes files.
- `make up`'s wait and `make smoke` connect to `SMOKE_HOST` with `--connect-to`, so the URL — the
  Host header and the TLS name — is the real host's: Caddy picks the route by the one and the
  certificate by the other (`nobody.invalid` must 404 over HTTP, and get no certificate over
  HTTPS).
- **`env/dev.env` keeps MongoDB in the volume `cms-mongo-data`**: on macOS a bind mount into the Docker
  VM intermittently refuses MongoDB's file operations, surfacing as an index that silently fails.
  Stage and prod use `/data/mongo/db`, which `make destroy` leaves alone.
- **pacod-mailpit is a profile** (`mailpit`, on dev and stage); `pacod-id`'s `depends_on` on it is
  `required: false`, and pacod-id's mail client connects only when it sends, so production starts
  without it.

**Settings files.** Three, the later winning, and the Makefile reads and passes all three
(`COMPOSE := docker compose --env-file env/$(ENV).env --env-file .env [--env-file .env-local] -f
docker-compose.yml -f docker-compose.standalone.yml`):
the environment's **preset** `env/<ENV>.env` (tracked, no secret), the machine's **`.env`** (`ENV`,
hosts, secrets; git-ignored, `make init` writes it) and an optional **`.env-local`** (git-ignored;
on older developer machines it holds pacod-id's secrets — **keep them for as long as `pacod-postgres-data`
lives**: the database role was created with those passwords on first start, and what pacod-id
stored is sealed under `PID_KEK`). Each rule below fails silently when broken:

- **The Makefile includes `.env` and `.env-local` twice**: first to learn `ENV`, then the preset,
  then both again so the machine's values beat the preset's. An `ENV` that comes only from the shell
  is ignored (sh and ksh read a startup file from `$ENV`), and `ENV` is unexported; scripts get
  `PACOD_ENV`.
- **Bare `export`, then `unexport`.** Make exports every variable, and compose's shell environment
  beats its env files, so compose sees make's values. That is why command-line overrides reach
  compose. It is also why the secrets are unexported: compose reads them from the files itself,
  where an unquoted `#` or a space survives, while make would cut it. The helper macros (`confirm`,
  `need`, `STUDIO_CLI`, `MONGO_ROOT`) are unexported too: exported, make expands them for every
  recipe, and `need`'s `$(error)` fired on `make help`.
- **Values make reads must be bare**: no quotes (make keeps them), no `#` (make cuts there), no `$`
  (both expand it). `scripts/check.sh` checks every key the stack reads and lists the keys nothing
  reads.
- **Derived values**: `STUDIO_ORIGIN`/`ID_ISSUER` (`?=`, from the hosts, the preset's
  `PUBLIC_SCHEME` and a port suffix only off 80/443), `CMS_STUDIO_SITE`/`CMS_PUBLIC_SITE` (from
  `STUDIO_HOST` and `PUBLIC_SCHEME`; their commas are `$(comma)`), `SITE_HOST`
  (`$(SITE).$(PACOD_SITE_DOMAIN)`).
- **No `.env` means no `make up`**: `make check` fails and names `make init`. A server whose `.env`
  went missing must not quietly come up as a dev stack, with mailpit, the mock gateway, demo
  websites and MongoDB on every interface.
- **Destructive targets outside dev** (`destroy`, `restore APPLY=1`, `reseed-pages APPLY=1`) need
  `CONFIRM=<env>` — `$(call confirm,…)`, a `$(error)` at expansion, before anything runs.
- **Mongo credentials never pass through make**: `make mongo`, `make restore`, `reseed-pages.sh`,
  `e2e-portal.mjs` and the driver run mongosh or mongorestore inside the container as
  `$MONGO_INITDB_ROOT_USERNAME`/`_PASSWORD`, which compose gave it.
- **macOS ships GNU make 3.81 and bash 3.2**: no `--eval`, no associative arrays, an empty array is
  unbound under `set -u` (expand `${a[@]+"${a[@]}"}`), and `$var…` reads the ellipsis as part of the
  name (write `${var}…`).
- **Override with `make up VAR=…`, not `VAR=… make up`** (an included assignment beats the
  environment), and pass the same overrides to every target that recreates a container. A bare
  `docker compose up -d <service>` reads none of the presets and can leave one container on other
  values (`docker inspect <id> --format '{{range .Mounts}}{{.Source}} {{end}}'`).
  **`SITE_PUBLIC_SCHEME`/`SITE_PUBLIC_PORT` are passed through unvalued**, so unset stays unset and
  the profile decides: dev uses `http` and the `HTTP_PORT` compose hands the studio (so `make up
  HTTP_PORT=8100` moves previews with it), prod `https` and the scheme's default port.
- **`make smoke` (`scripts/smoke.sh`) addresses hosts by name with `--connect-to`**, over the
  preset's scheme, and fails on a wrong code or a redirect that does not start with the expected
  origin. The demo rows run where `SMOKE_DEMO` is set (dev, stage); in prod the website rows need
  `SITE=`/`SITE_HOST=`. `make e2e-portal` probes websites through node:http with a `Host` header —
  through Caddy on dev, the site's loopback port on stage, where every `e2e-<stamp>` host through
  Caddy would cost a certificate.
- **Check what owns a loopback port before trusting localhost**: a process on `127.0.0.1`/`::1`
  (an `ssh -L` tunnel, another stack) beats Docker's `0.0.0.0`. `lsof -nP -iTCP:8000 -sTCP:LISTEN`;
  `make smoke SMOKE_HOST=$(ipconfig getifaddr en0)`. The direct-port rows are the easiest to fool.

## Templates

The public site's templates, as the `editorial` set (`templates/sets/editorial/`); `minimal`
overrides some of them (*Template sets, layouts and themes*). The studio's `templates/mgmt/` is a
separate, self-contained set.

### The design system

The look is one stylesheet, `static/site-assets/site.css`, plus `site.js`, served **from the jar**
at `/site-assets/**` by `WebSiteAssetsConfiguration` (not `/assets/`, each website's own files),
exempt from the host lookup. It maps only `/site-assets/**`, which beats `/**` because
`SimpleUrlHandlerMapping` picks the most specific pattern. No Bootstrap, jQuery,
icon font or CDN script. Keep in `site.css`: **`.video-container`** (the responsive-iframe rule
widgets and articles inject YouTube into), **`.article-body`**'s guards for legacy bodies (inline
widths, `<font>`, `<style>`, Word markup, tables — a fresh seed will not show them), and real CSS
rules for every hover state.

**The article images are a scroll-snap strip (`.gallery`) that `initGallery()` upgrades into a
carousel.** The template writes the strip only; **the script builds every control** and adds
`.is-carousel`, so with scripting off the strip just scrolls (`articleGalleryShipsNoStaticControls`
asserts no `carousel` in the markup). **Nothing is ever hidden**: the carousel is the scroll
position, and the test asserts no `display:none`/`hidden` inside the gallery. `grid-auto-columns` is
92 % bare (the peek is the only scroll cue) and 100 % as a carousel, which also drops the side
padding and scrollbar; positions come from `offsetLeft` (corrected by the gallery's
`offsetLeft`/`clientLeft`), never `index × width` or `scrollIntoView`; a click sets an *intent*
that leads the scroll until it settles. **Control labels come from the page**: `data-label-gallery`
/`-previous`/`-next`/`-image` on the strip and `data-title-day`/`-night` on the theme toggle,
rendered from the bundles; `site.js` keeps only English fallbacks.

Night mode is `.night` on `<html>`, set synchronously by `fragments/head :: theme` (key `pacod-theme`),
toggled by `site.js`. `article-print.html`, the print view, omits it so it always prints light.

### The chrome is translated on the server

Every word of public chrome is a `#{key}` from the site's `messages*.properties` — **English base,
`_bg`, `_de`, `_fr`, `_es`, `_it`**. Menu labels and the website's name are data, not keys.

- **`ThymeleafConfig.messageSource` calls `setFallbackToSystemLocale(false)`**: a language without a
  bundle gets English, never the JVM locale's bundle.
- **The chrome follows the content.** Controllers resolve the content language (path prefix, then
  `?lang`, then the `lang` cookie through `CookieLocaleResolver`, whose default is `original()`) into
  the model's `lang`; `LocaleConfig.chromeFollowsContent()`, a `postHandle` interceptor, sets the
  request locale from it, so `/de/article/5` renders German chrome whatever the cookie says. It
  **writes no cookie**. `LocaleConfigTest` pins it.
- **The language switcher is server-rendered** by `SiteChrome.languageLinks(lang)` from the
  `language` collection: the same page per language, `/{lang}/` prefix dropped, every other query
  parameter kept (a search keeps its term and page); hidden below two languages.
- **A new key goes into all six bundles.** `MessageBundleTest` checks parity, that every template
  `#{…}` exists and `MessageFormat` well-formedness (`''`);
  `PublicTemplateRenderTest.everyTemplateRendersInEveryLanguage` refuses `??key??`. A new chrome
  language needs its bundle and a `language` document.

### Thymeleaf traps this codebase has hit

- **A backslash does not escape a quote in SpEL — it ends the string.** Double it: `'The site''s'`.
  The parser then blames an unrelated character further on.
- **Two BEM `__` separators in one expression fail to parse** (`__…__` is preprocessing). Use
  `th:classappend` with the modifier alone.
- **`ArticleModel.getImage()`/`getContent()` return `""`, not `null`**, with no images; `!= null`
  emits an `og:image` naming the bare host. Use `#strings.isEmpty`.
- **`article.embedded` is the same trap**: a textarea posts `""`, which rendered an empty black
  `.video-container` and suppressed the gallery and thumbnail. `MgmtArticleService.save` runs
  `blankToNull` *and* `article-view.html`, `article-print.html` and `widgets.html` test
  `#strings.isEmpty`, so stored rows render right without a migration. Keep both halves.
- **Fragment inclusion runs before `th:each`, `th:if` and `th:case` on the same element**, and
  `*{…}` needs a `th:object` a bare fragment render lacks. Wrap the `th:replace` instead.
- **`map[k]` with a bare identifier is the literal key `"k"`** in SpEL; write `map.get(k)`.
- **Restricted mode forbids `new`** (and static calls): compute it in Java.

### Fragments and the error page

`templates/sets/editorial/fragments/` — `head`, `navbar`, `ad-sidebar`, `footer`, `widgets`, `article-view`,
`scripts` — serves `landing`, `article`, `index`, `list`, `page`, `search`, `search-google` and
`error`; `social` takes `head :: theme` and `:: gtm` by name. `head` is parameterised (`lang`,
`pageTitle`, `pageDesc`, `ogUrl`, `ogImagePath`, `ogImageType`, passed by name); `index` and
`article` share `article-view`; `navbar`/`footer` render from the menus and `${site}` (no logo:
the name as text; no contact details: no block). **`ad-sidebar` takes the banner lists, and an
absent attribute renders an empty column**, so every "no booking here" assertion is paired with a
positive one. `LandingTemplateRenderTest` and `PublicTemplateRenderTest` render the real templates
with no MongoDB and no context (`TestSites` supplies a fully filled `${site}`); controller tests
assert only the view name.

**`error.html` is the error page** — for a known host; an unknown one never gets this far (the
filter's plain-text 404, *Tenancy*), and `HostResolutionIT` checks a website's 404 renders in that
website's chrome. `ArticleNotFoundException` and `PageNotFoundException` carry
`@ResponseStatus(NOT_FOUND)`, so `BasicErrorController` renders `error.html` for a browser and JSON
for a JSON client; an unmapped URL answers `application/problem+json`. **`ControllerAdvisor` is an
empty class that must stay**: extending `ResponseEntityExceptionHandler` is what produces that
problem JSON for `/web/v1/**` clients. `error.html` reads **only `${site}` and the request locale**
(no controller ran; a throw inside the error dispatch re-enters it), renders from an otherwise empty
model in the tests (`renderBare`), has **no ad columns**, branches its copy on `${status}`, and never
renders `message`.

### Routes

- **`GET /` renders `landing.html`** from the website's `HOME` layout (`PageComposer`, *Template
  sets, layouts and themes*); it resolves `?lang`, the cookie, then `original()`. **No template may load the old `main.min.js`**, which would refetch the widgets
  over `/web/v1` and overwrite them. `/latest` (`/index`, `/index/`, `/index.html`) renders
  `index.html`.
- **`/issues` and `/issues/{id}`** list the published journal issues (*Journal issues*); like
  `/search` they take no language prefix. `/issue-print/{id}/front` answers a print token only.
- **The three reader routes answer bare and under a language prefix** — `/article/{id}`,
  `/list/{id}`, `/p/{slug}`, `/{lang}/…` — as **one mapping with two patterns**, never a second
  handler (which would silently drop `pageRendered`: the ad columns and the traffic mark).
  - The prefix is `[a-z]{2,3}`, clear of `/article-print/{id}` and `/web/v1/**`; `/v1/article/5`
    and `/news/article/1` are 404s, pinned per route by tests and `make smoke`.
  - **The code is never checked against the `language` collection**; `/xx/article/5` falls back
    like `?lang=xx`.
  - **Precedence is path, `?lang`, cookie/`Locale`** (`readerLang`). The prefix never updates the
    cookie; in-page links carry the model's `lang`.
  - **On `/p/{slug}` the prefix is the *requested* language**: `/de/p/about` with no German renders
    English, says so in German, writes `<html lang="en">`.
  - `og:url` names the bare `?lang=` form; no `rel="canonical"` or `hreflang`.
  - **No top-level route may be two or three lowercase letters**, and identifier-less routes get no
    prefix (`/{lang}` alone would swallow every single-segment path).
- **`/robots.txt` and `/sitemap.xml` are answered per website** (`SitemapController`), from its own
  database — no file in any folder, so no website's list names another's.
  - robots disallows `/article-print/`, `/issue-print/`, `/b/`, `/social/go/`, `/social/read/`,
    `/search` (and so `/search-google`) and `/web/v1/`, and names the sitemap absolutely.
  - The sitemap lists `/`, `/latest`, every published standing page, every category with a
    published article (`existsByCategoriesAndStatus`), `/issues` and every published journal issue
    while there is one, and the newest 45,000 published articles,
    through a projection (`findPublishedForSitemap`: no row resolves its `@DBRef`s). Bare
    addresses, `lastmod` a date alone.
  - Absolute addresses come from `baseUrl`, else the request's origin, like `og:url`. A database
    that cannot be read is a **503** with `Retry-After`, never an empty sitemap a crawler would
    believe. Both cache an hour; `make smoke` probes both.

### Search

`/search` and `/search-google` are `SearchController`; there are no `.html` aliases.

- **Neither is a `Page` document**: results depend on the query, and the Google screen is a script
  that TinyMCE's default `xss_sanitization` would strip on the first save.
- **Two indexes**: `/search` is a regex over `title`/`text`/`description` through
  `searchArticlesPageable` (never `fulltextSearch` directly — it runs `escapeForRegex`);
  `/search-google` is Google's crawl, engine id `SiteSettings.googleCseCx`. **Without one,
  `/search-google` redirects to `/search`** with term and language, and the navbar hides its button.
- **A blank `q` is the opening state, not an empty result** (the service is not called; a blank
  regex matches everything). Pager links carry `q=`. `BannerPage.SEARCH` covers both; no prefix.
- **The navbar is one box, two destinations, no JavaScript**: `id="site-search-form"`; the ⌕ is
  the **first** submit button (the default, so Enter goes to `/search`;
  `navbarSearchReachesBothScreens` asserts the order); the Google button is `type="submit"
  form="site-search-form" formaction="/search-google"`. The box echoes `q`; a hidden `lang` sends
  the page's language so results arrive in it.
- **`q` on `/search-google` is read by Google** (`queryParameterName`), bound in Java only for the
  `<noscript>` link. No test should assert Google's behaviour.
- `SearchControllerTest` needs an explicit `InternalResourceViewResolver`, or view `search` at
  `/search` is refused as a circular dispatch.
