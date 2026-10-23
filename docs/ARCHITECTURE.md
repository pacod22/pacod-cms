# Architecture

pacod-cms runs many websites, for many organisations, from one stack. This page describes how a
request finds its website, where each kind of data lives, and which part of the system does what.
It is the map; the code's comments, [`TEMPLATES.md`](TEMPLATES.md), [`SECURITY.md`](SECURITY.md)
and [`DEPLOYMENT.md`](DEPLOYMENT.md) hold the detail and the reasons behind it.

```
        readers                                       editors, admins
           │                                                │
  ┌────────┴─────────── Caddy (the front door) ─────────────┴─────────┐
  │   any other host            STUDIO_HOST                ID_HOST    │
  └──────┬──────────────────────────┬──────────────────────────┬──────┘
         ▼                          ▼                          ▼
   ┌───────────┐             ┌─────────────┐   OIDC, feed   ┌─────────┐
   │ cms-site  │             │ cms-studio  │◀──────────────▶│ pacod-id│──▶ pacod-postgres
   │ (public)  │             │ (portal and │                └─────────┘
   │ Host →    │             │  /sites/<id>│── jobs ─▶ Gotenberg ──▶ site (print view)
   │ website   │             │   /mgmt)    │── AI ───▶ pacod-gw
   └─────┬─────┘             └──────┬──────┘
         │      no calls between    │
         ▼          the two         ▼
   ┌──────────────────────────────────────────┐     ┌──────────────────────────────┐
   │ MongoDB: pacod_cms (control plane)       │     │ storage: sites/<id>/assets,  │
   │          site_<id> (one per website)     │     │          sites/<id>/resources│
   └──────────────────────────────────────────┘     └──────────────────────────────┘
```

## Two applications, one library

| Module | What it is |
|---|---|
| `pacod-cms-core` | A jar: the documents, repositories and services, the tenancy, the control plane, the seed and demo content, the design model (template sets, layouts, themes), and a Java port of pacod-id's rules engine. No controllers. |
| `pacod-cms-site` | The public site for every website at once (compose service `cms-site`). Thymeleaf pages, the JSON delivery routes, the media of each website, the counters. |
| `pacod-cms-studio` | The studio (service `cms-studio`): sign-in through pacod-id, the portal (organisations, websites, members, the installation), each website's editing screens, the background jobs, the AI features. |

**Neither application calls the other.** Both read and write MongoDB directly. A change made in the
studio reaches the site through the database; the site's caches expire after 60 seconds, which is
how long an edit takes to appear. The one service the studio calls over HTTP is pacod-id (and,
for its features, pacod-gw and Gotenberg).

## Finding the website

Every unit of work is about exactly one website, and that website is **bound** for its duration
(`TenantContext`, a `ScopedValue`):

- **On the site**, by the request's host: `HostSiteResolverFilter` looks the host up in the control
  plane (`TenantDirectory`, cached) before anything else runs. A host no active website claims is a
  plain 404, never a default website. A handful of paths are about no website — the health check,
  the site's own CSS and JavaScript, the TLS proxy's question — and pass unbound (`UnboundPaths`).
- **In the studio**, by the path: a website's screens are `/sites/<id>/mgmt/…`. `StudioSiteFilter`
  binds the website and presents `/sites/<id>` as the context path, so every mapping, link and
  redirect inside reads `/mgmt/…`. The portal's screens (`/mgmt/sites`, `/mgmt/organisations`,
  `/mgmt/platform`) are about no single website.
- **Background work** binds the website it is for: a job, a scheduled flush of one website's
  counters, the provisioning of a new website.

With a website bound, `RoutingMongoDatabaseFactory` hands every MongoDB operation that website's
database. With none bound it throws: a missing binding fails loudly instead of writing one
website's data into another's.

## Where data lives

- **The control plane**, database `pacod_cms`: organisations, websites (id, name, content language,
  template set, status), domains (host → website), platform settings, the job queue, tombstones of
  deleted websites. Reached only through its own template and repositories.
- **One database per website**, `site_<id>`: articles, pages, categories, authors, images, banners
  and banner layouts, menus, settings, layouts, the theme, the social page, the traffic and crawler
  buckets. Ids are per website; two websites can both have article 1.
- **Files**, under the storage root: `sites/<id>/assets/…` (article images) and
  `sites/<id>/resources/…` (banner creatives and HTML5 folders, PDFs of articles and published
  journal issues), served by the site at the same paths, each website's out of its own folder;
  `sites/<id>/private/…` (every generated issue PDF, drafts included) is served by nothing but the
  studio's download. The studio is the only writer.
- **People** live in pacod-id. The CMS stores no accounts; what someone may do comes from pacod-id's
  feed of role bindings, evaluated by the rules engine against `authz/cms.yaml`.

## A website's life

1. **Created** — in the portal, by `make site-create`, or as a demo on an empty platform.
   `SiteProvisioner` writes the website and its domains, builds the indexes in its database, and runs
   the seed steps (languages, standing pages, settings and menus, banner skeletons). The website's
   scope is registered with pacod-id at once, so roles can be given there.
2. **Demo content**, when asked for, is written once after creation (`DemoContent`, `StudioDemo`).
3. **Re-provisioned** on every studio start when a release raises the schema version; the seed
   steps write only what is absent.
4. **Deleted** in an order that leaves nothing to write back: offline and its hosts released, its
   pacod-id scope removed, then its database, files and record; the database is dropped once more
   after the site's caches have let go.

## Rendering a page

A website renders with its **template set** (`editorial` or `minimal`; [`TEMPLATES.md`](TEMPLATES.md)). Each
page type has a **layout** — regions holding ordered sections — stored per website, edited as a
draft in the studio and published. `PageComposer` resolves the layout against the set, looks up each
section's data, and hands the templates `Regions` of ready sections; templates never query. The
**theme** (colour, font and width tokens plus the website's own CSS) is served as `/theme.css`.

Drafts are previewed on the website's own origin with a short-lived signed token; nothing of a
preview is counted or tracked, and drafts are read past every cache.

## Background work

- **Jobs** (`/mgmt/jobs`) run in the studio: a queue in the control plane, claimed with a lease and
  fenced writes, one organisation's jobs at a time. PDF export renders the site's print view through
  Gotenberg, in a website's chosen format (standard, or a two-column scientific paper) and mode
  (code, or an abstract and keywords written by pacod-gw); a journal issue renders each article,
  has the site lay out its cover and contents with the page numbers the studio computed, and is
  merged and numbered with PDFBox. Translation jobs call pacod-gw.
- **Counters** (page views, readers, banner impressions, crawler visits) are buffered in the site per
  website and flushed into that website's database; the studio reads them for its dashboard,
  monitoring and crawler screens.

## Front door

Caddy (`pacod-caddy`) routes by host: the studio's host to `cms-studio`, pacod-id's to `pacod-id`,
every other host to `cms-site`. In production it serves every host over HTTPS, obtaining a
website's certificate on demand once the site confirms the host is an active website's, or serving
a certificate pair the deployment supplies for the names it covers; in development it serves plain
HTTP, and HTTPS from its own local CA. Both run the same rules for cms's sites
(`platform/caddy/cms.caddy`), which the full stack (pacod-demo) imports too: every container is on
the shared network `pacod-net`. The studio and the websites must be on different registrable
domains; the studio refuses to start otherwise
([`SECURITY.md`](SECURITY.md)), unless `PACOD_SITE_DOMAIN_SHARED=true` says the risk is accepted:
an installation whose websites are all trusted, such as a demo on one domain.
