# Deployment

Docker Compose is the supported way to run pacod-cms, and the only one that runs the studio, with
two files the Makefile always loads together: `docker-compose.yml` (the app file: cms's own
services) and `docker-compose.standalone.yml` (cms's copies of the shared `pacod-*` components). One
stack hosts every website. Both applications are built from the root `Dockerfile`, selected by its
`MODULE` build argument; sign-in is pacod-id's, a separate project whose image the stack runs:

| Service | File | Image built from | Listens on (inside) | Published as | Log file |
|---|---|---|---|---|---|
| `cms-mongo` | app | `mongo:8.3.13` | 27017 | `${MONGO_PORT:-27017}` | — |
| `cms-site` | app | `pacod-cms-site` | 8443 | `${WEB_PORT:-8080}` | `cms-site.log` |
| `cms-studio` | app | `pacod-cms-studio` | 7443 | `${MGMT_PORT:-7070}` | `cms-studio.log` |
| `cms-gotenberg` | app | `gotenberg/gotenberg:8.38.1` | 3000 | — (the studio's PDF export only) | — |
| `pacod-id` | standalone | `${PACOD_ID_IMAGE:-pacod-id:dev}` | 8070 (public), 8071 (internal, `pacod-id-internal` on `pacod-admin-net`) | — (through Caddy) | — (`make logs-pacod-id`) |
| `pacod-postgres` | standalone | `postgres:18.6-alpine3.24` | 5432 | — | — |
| `pacod-db-init` | standalone | `postgres:18.6-alpine3.24` | — (runs once per start) | — | — |
| `pacod-mailpit` | standalone | `axllent/mailpit:v1.27` | 8025 | `${MAILPIT_PORT:-8125}`, loopback only | — |
| `pacod-gw`, `gw-mock` | standalone | `pacod-gw-lite:dev`, `pacod-gw-mock:dev` | 8080, 9000 | — | — |
| `pacod-caddy` | standalone | `caddy:2.11.6-alpine` | 80, 443 (and 443/udp) | `${HTTP_PORT:-8000}`, `${HTTPS_PORT:-8443}` | — (`make logs-pacod-caddy`) |

`cms-site` is the public site for every website; `cms-studio` is the studio; `pacod-id` is pacod-id,
which holds every account, session and role in `pacod-postgres` (volume `pacod-postgres-data`), where
`pacod-db-init` creates its role and database at every start (`platform/postgres/id-db.sh`);
`pacod-mailpit` catches every mail pacod-id sends; `pacod-gw` and `gw-mock` are the development AI
gateway (profile `dev-gateway`); `pacod-caddy` is the front door for every host.
Logging follows the pacod convention, [`pacod-demo/docs/LOGGING.md`](../../pacod-demo/docs/LOGGING.md).
Both applications write one event per line to stdout (`make logs`), JSON by default or text with
`APP_LOG_FORMAT=text` (`env/dev.env` sets it), at the level `APP_LOG_LEVEL` (`debug`, `info`, `warn`,
`error`; default `info`); `SERVICE_NAME` (`cms-site`, `cms-studio`) is the event's `service`. They
also write a JSON file, `<service>.log`, under `LOG_DIR` (`/data/logs` in the containers), mounted
from `LOG_DIR_HOST`: rotated at midnight UTC and at 20 MB to `<service>.<yyyy-mm-dd>.<n>.log.gz`,
kept 30 days and 600 MB in all. Every container's own log is rotated by Docker (`json-file`, 10 MB,
five files). The application ports are published only for bypassing Caddy while debugging — the
studio's sign-in does not work there — and in production should be bound to loopback or not
published.

## Before the first start

[`RUNNING.md`](RUNNING.md) is the step-by-step guide for each environment. In short:

```bash
make init ENV=prod STUDIO_HOST=studio.example.com ID_HOST=id.example.com SITE_DOMAIN=sites.example.net EMAIL=ops@example.com
$EDITOR .env      # the mail block, and anything else from .env.example
make up           # make check first; builds pacod-id's image if missing; starts; waits
```

- **Three files, the later winning**: the environment's preset `env/<ENV>.env` (tracked: profile,
  Caddyfile, ports, paths, profiles, the demo switch; no secret), this machine's `.env` (`ENV`, its
  hosts and secrets) and an optional `.env-local`. The Makefile passes all three to compose as
  `--env-file`, and reads them itself. `make status` shows which environment runs.
- **`make init` writes `.env`**, with fresh secrets, never printed:
  - MongoDB's two passwords;
  - pacod-id's seven secrets (`PID_PG_SUPERUSER_PASSWORD`, `PID_DB_PASSWORD`, `PID_KEK`,
    `PID_TOKEN_PEPPER`, `CMS_ID_STUDIO_SECRET`, `CMS_ID_SERVICE_SECRET`, `ID_ADMIN_PASSWORD`);
  - the draft-preview key and the visitor salt;
  - on dev and stage, the mock gateway's key.

  Run on an existing `.env`, it adds only what neither `.env` nor `.env-local` names. A blank value
  there is a decision and stays.
- **Keep `.env`, and back it up.** MongoDB took its passwords on its first start. PostgreSQL's role
  was created with `PID_DB_PASSWORD`, and pacod-id seals what it stores under `PID_KEK`. So
  **`make init` refuses to write a new value for any of them while that data exists**, and writes
  nothing then. Compose refuses to start without MongoDB's and pacod-id's ("run make init").
- **The draft-preview key, `PACOD_PREVIEW_SECRET`**, is read by both `cms-site` and `cms-studio`: the studio
  signs a preview's address with it and the site checks it, and PDF export signs the renderer's
  requests to the site with it too. It needs at least 32 characters. Blank, the studio offers no
  preview and no PDF export, and the site refuses every preview address. Changing it ends the
  previews open at that moment, nothing else.
- **`make check`** runs before every `make up` and stops on any ✗. It requires:
  - every secret, and no placeholder outside dev;
  - on stage and prod, public hosts, ports 80/443, and `https://` origins matching the hosts;
  - on stage, the demo websites' hosts;
  - in production, a mail relay (`PID_SMTP_HOST`) and a real sender (`ID_MAIL_FROM`);
  - a supplied certificate pair, when `SSL_CERT_FILE` names one, to be readable, matching, a full
    chain and not expired;
  - values make and compose read alike: no quotes, and no `#` or `$` in what make uses;
  - a `docker-compose.yml` that resolves.

  It warns about the rest, for example a site domain that looks like the studio's registrable
  domain.
- **pacod-id's image must exist, or its sources.** `make up` runs `make images`, which builds
  `PACOD_ID_IMAGE` (default `pacod-id:dev`) from `PACOD_ID_SRC` (default `../pacod-id`) only when the
  image is missing, and stops with an error when neither is there. The first build is a Rust build
  and slow. A deployment that pulls a published pacod-id image sets `PACOD_ID_IMAGE` instead. On dev
  and stage it also builds the mock gateway from `PACOD_GW_SRC`.
- **The Docker network is external and shared.** Both compose files attach every service to
  `pacod-net`, declared `external: true`, which every pacod repo and the full stack (pacod-demo)
  join; compose never creates it. `make network` does, idempotently, and `make up` runs it first. A
  bare `docker compose up` on a fresh machine aborts with "declared as external, but could not be
  found". The `pacod-*` names are shared too: one stack running them per machine (README, *Shared
  network*). `make network` also creates **`pacod-admin-net`**, external and internal (no route
  out): pacod-id binds its internal listener to `pacod-id-internal`, its alias there, and the
  studio joins it to call that address. On a fresh machine without `make`:
  `docker network create pacod-net && docker network create --internal pacod-admin-net`.
- **Stage and production publish Caddy on 80 and 443** (the presets). Caddy obtains its
  certificates over them, so both must be reachable from the internet, and DNS for the studio's and
  pacod-id's hosts must point at the server before the first start (*TLS*). The applications' and
  MongoDB's own ports are published on loopback only there (`PUBLISH_IP=127.0.0.1`).
- **MongoDB initialisation runs once.** `docker/mongo-init/01-create-app-user.js` creates the
  application user (`MONGO_USER`) in the `admin` database, with `readWriteAnyDatabase` and
  `dbAdminAnyDatabase`, the first time the data directory is initialised, and never again. The
  *AnyDatabase* roles are needed because every website's database is created later, when the
  website is. The applications therefore authenticate against `admin`
  (`spring.mongodb.authentication-database`, which reads `MONGO_AUTH_DB` but compose passes none).
  Neither role grants user or role administration. Changing `MONGO_PASSWORD` later does not change
  the user's password.
- **The studio prepares the platform on every start.** It creates the control plane's indexes,
  brings every website whose database predates the release up to date (indexes and missing seed
  content), and — **only while there are no websites at all** — creates three demo websites with
  the demo content, in one organisation of their own: `demo` (editorial) at `DEMO_HOSTS` (default
  `demo.localhost,localhost`), `minimal` (the minimal template set) at `DEMO_MINIMAL_HOSTS`
  (default `minimal.localhost`) and `space` (the space-travels demo) at `DEMO_SPACE_HOSTS` (default
  `space.localhost`), so a fresh install has something to open. Deleting them later does
  not bring them back. Production's preset creates none (`DEMO_WEBSITES=false`). Each step logs
  its own failure at ERROR and is retried on the next start: **check `make logs-cms-studio` for index
  failures after every deploy** — a logged failure means that index does not exist. In the
  background it then pushes its role catalogue to pacod-id, follows pacod-id's authorisation feed,
  and registers every organisation and website with it, retrying until pacod-id answers.
- **The public site creates nothing.** It serves the websites that exist and answers 404 for a host
  no website claims; it starts fine before the studio, but no website answers until the studio has
  created one.

## Websites and hosts

A website is a record in the control plane (`pacod_cms`, the `website` collection), a database of
its own (`site_<id>`), a folder of files (`${STORAGE_HOST}/sites/<id>/`), and one or more hosts
(the `domain` collection, one website per host). Its id is chosen once — lowercase letters, digits
and hyphens, starting with a letter — and never changes; its name is what readers see.

**In the studio** (the portal): an organisation admin creates websites under **Websites → New
website**. Each new website answers at once at `<id>.<PACOD_SITE_DOMAIN>` — set
`PACOD_SITE_DOMAIN` to a domain whose wildcard DNS points at this installation, **not** the
studio's registrable domain — plus any further addresses given there. A website's **Settings**
rename it, change its content language, time zone or status (suspended: it answers nothing, its
content is kept), and add, remove or make primary its addresses. The studio's and pacod-id's hosts,
and every name below them, are refused as website addresses. **Deleting** a website (an
organisation admin's decision, with its id typed) takes it offline, removes its roles and
invitations from pacod-id, drops its database and deletes its files; three minutes later the
database is dropped once more, in case the public site's caches wrote into it meanwhile, and until
then the id cannot be reused. If pacod-id cannot be reached the website stays offline and
"deleting" — delete it again to finish.

From the command line, without a browser:

```bash
make site-create SLUG=acme NAME="Acme News" HOSTS=acme.example.com,www.acme.example.com LANGUAGE=de
```

This runs the studio image once with `--create-site` against the running stack's MongoDB: it creates
a new organisation for the website (its id is generated, `org_` and 32 hex digits) — or, with
`ORG=org_…`, adds the website to that existing organisation — claims the hosts (the first is the
primary one), creates the indexes, seeds the languages, standing pages, settings, menus and banner
skeletons, and exits. `LANGUAGE` is the language the website's content is written in (default
`en`). A host that already belongs to another website is refused before anything is written. The
new website answers within a few seconds, and the running studio registers it and its organisation
with pacod-id within about ten seconds; until then a role held at that website or organisation does
not count.

Hosts are stored lowercase, without a port. The public site sees a new host within 5 seconds and a
change to an existing one — a removed address, a suspended or deleted website — within a minute.

## Sign-in and roles

Nobody has an account in pacod-cms. **People sign in through pacod-id** (OpenID Connect) and hold
roles there, at the platform, at an organisation or at one website; the studio reads those roles
from pacod-id's authorisation feed and checks every screen against them.

| Role | Held at | What it covers |
|---|---|---|
| `cms.platform_admin` | the platform | everything, everywhere; the last one cannot be removed |
| `cms.org_admin` | an organisation | the organisation and every website in it, its members |
| `cms.website_admin` | a website | that website: settings, content, design, advertising, statistics, members |
| `cms.editor` | an organisation or a website | content, including publishing and deleting |
| `cms.author` | a website | reading and writing content |
| `cms.designer` | an organisation or a website | layouts, themes and templates |
| `cms.analyst` | the platform, an organisation or a website | statistics |

`authz/cms.yaml` in `pacod-cms-core` is the full list of roles and permissions. An author writes
content; publishing (or taking down) and deleting it are an editor's. The design screens (layouts,
the theme, the template set, menus) need the design permissions, and publishing a layout or the
theme, or switching the template set, needs `design.publish`.

- **The first account** is pacod-id's bootstrap admin: `ID_ADMIN_EMAIL` (default
  `admin@pacod-cms.test`) with `ID_ADMIN_PASSWORD`, holding `id.admin` and `cms.platform_admin`.
  pacod-id creates it when it starts and no account has that address yet, and changes nothing once
  it exists: changing `ID_ADMIN_PASSWORD` later does nothing.
- **Anyone may create an account** (the sign-in page's "Create an account"; self-registration is
  on in the `cms` realm, and pacod-id confirms the address by mail). A new account holds no role:
  it sees an empty website list and is refused every website's screens.
- **Creating an organisation makes its creator its admin.** Anyone signed in may create one while
  the installation allows self-service (the default; `PACOD_ORG_CREATION=platform-admins` closes
  it whatever the installation screen says), up to the installation's limits: organisations per
  person, websites per organisation (3 and 5 to begin with; platform admins are not limited). The
  admin role is given by the studio's **provisioner**, a pacod-id service account holding
  `cms.provisioner`, which may give roles but never `platform_admin` or `provisioner`. `make up`
  creates it once (`make id-provisioner`: it signs in as the bootstrap admin and writes
  `PACOD_ID_PROVISIONER_TOKEN` into `.env`, or into `.env-local` when that file already names the
  token). Every environment runs it the same way, or creates the account on pacod-id's admin pages
  and sets the token in `.env`; without a token only platform admins create organisations. Treat
  the token as a secret: it can give any organisation or website role anywhere. `make id-provisioner ROTATE=1` replaces it, revoking every
  older token of the account, and recreates the studio.
- **Giving someone a role**: the organisation's and each website's **Members** screens list who holds
  which role, change and remove roles, and invite people by e-mail (or as a link to pass on). The
  studio acts as the signed-in person — pacod-id checks every change against their own roles — and
  offers only the roles they may give. An invitee without an account makes one from the invitation.
  A role at an organisation or website counts once the studio has registered that scope, which it
  does as it creates it.
- **The installation screen** (platform admins) sets who may create organisations, the two limits,
  whether a new organisation starts with AI on (off by default: every AI call spends the
  installation's one gateway key), and turns AI on or off per organisation.
- **Signing out of the studio signs out of pacod-id too.** A session ended in pacod-id (a sign-out
  elsewhere, an administrator, a password change) or a disabled or deleted account signs the person
  out of the studio at their next request, however long its studio session has sat idle.
- **When pacod-id cannot be reached**, the studio keeps running and its health check stays green,
  but once its copy of the roles is more than a minute old every screen answers **503** rather than
  decide with roles that may have been taken away. The public websites are unaffected.

## The studio's and pacod-id's domains

**In production, put the studio on a different registrable domain from the websites** — for
example `studio.example.org` against `*.example-sites.net`, and never `studio.example.com` beside
`www.example.com`. A website runs script its owner wrote (HTML5 banners, tag managers, article
bodies), and on a shared registrable domain that script could set cookies for the studio and
defeat its CSRF protection. Set it with `STUDIO_HOST` (default `studio.localhost`). pacod-id's
sign-in pages answer at `ID_HOST` (default `id.localhost`); for the same reason, keep that host off
the websites' registrable domain too.

**The studio enforces it.** It refuses to start when `<id>.PACOD_SITE_DOMAIN` would share a
registrable domain with `STUDIO_ORIGIN`'s host or `ID_ISSUER`'s, judged by the Public Suffix List
the way browsers judge cookies; the log names the domain. A tenant domain that is itself a public
suffix passes: register one shared by many websites in the list's **private section**, as
`github.io` is, and every website below it becomes a registrable domain of its own. A website's
own address on the studio's or pacod-id's registrable domain is refused when it is added, and a
stored one is reported at ERROR in the studio's log after every start.

**Each host has an address to match, and nothing derives one from the other:**

| Host (Caddy) | Address (what browsers use) | Used for |
|---|---|---|
| `STUDIO_HOST` | `STUDIO_ORIGIN`, e.g. `https://studio.example.org` | the studio's sign-in callback and post-logout address, registered exactly in pacod-id |
| `ID_HOST` | `ID_ISSUER`, e.g. `https://id.example.org` | pacod-id's issuer; it answers its pages only at exactly this host and port |

Both addresses default to `http://…localhost:${HTTP_PORT}`, so they follow `make up HTTP_PORT=…`
in development. In production set all four, with `https`. A sign-in started at any other address —
the studio's direct port, or a host that is not `STUDIO_HOST` — cannot complete.

## Settings files

| File | Holds | Written by |
|---|---|---|
| `env/dev.env`, `env/stage.env`, `env/prod.env` | an environment's shape: `SPRING_PROFILE`, `CADDYFILE`, `PUBLIC_SCHEME`, `COMPOSE_PROFILES`, the ports and `PUBLISH_IP`, the host paths, `DEMO_WEBSITES`, dev's hosts. No secrets | the repository (tracked) |
| `.env` | this machine: `ENV`, its hosts, its secrets, anything changed from the preset | `make init`, then you (git-ignored) |
| `.env-local` | optional overrides, read last | you (git-ignored) |

`ENV` in `.env` (or on the command line) picks the preset; without it, `dev`. The Makefile reads the
three files in that order, the later winning, and passes them to compose as three `--env-file`
flags. Running compose by hand needs the same three: naming any `--env-file` turns the implicit
`.env` off. Make also derives `STUDIO_ORIGIN` and `ID_ISSUER` from the hosts (https and no port on
stage and prod; `http://<host>:$HTTP_PORT` on dev) unless `.env` names them.

**Write values bare**: no quotes, and no `#` or `$`. Make and compose both read these files, and make
keeps quotes, cuts at `#` and expands `$`. The secrets are the exception for `#` and spaces: make
never exports them, so compose reads them as it parses them. `make check` names any value that
would mean two things.

To override a value for one run, use a command-line assignment: `make up STORAGE_HOST=./.data/storage`.
`STORAGE_HOST=… make up` is silently ignored, because the Makefile's included assignment beats the
environment.

On a developer machine the preset keeps MongoDB in the Docker volume `cms-mongo-data` rather than a
folder: on macOS a bind mount into the Docker VM intermittently refuses MongoDB's file operations
("Operation not permitted"), which shows up as an index that silently fails to build. `make destroy`
drops that volume with the rest — `pacod-postgres-data` included, so every pacod-id account and role goes
too. Stage and production keep MongoDB in `/data/mongo/db`, which `make destroy` does not delete.

## Files

One host directory, `STORAGE_HOST` (default `./.data/storage`), holds every website's files, one
folder each: `sites/<id>/assets/img/articles/` (article images), `sites/<id>/resources/banners/`
(banner creatives and HTML5 banner folders), and anything else served under `/assets/` and
`/resources/`. It is mounted at `/data/storage` (`STORAGE_ROOT`) in both applications.

- **The public site serves them**, out of the folder of the website the request's host belongs to:
  one website's host cannot reach another's files, and a path cannot climb out of the folder. Caddy
  serves no files. Responses carry a ten-minute public cache lifetime.
- **The studio is the only writer.** It mounts the directory read-write: uploads land there, each
  website's folder is created on its first upload, and deleting an image or a banner layout deletes
  the files. The site's mount is read-only.
- **The studio shows a website's pictures** (thumbnails, the image library, images inside the
  editor) itself, behind sign-in and only for raster image types — never a banner's HTML or
  script, which would run on the studio's origin. A banner layout's preview is framed from the
  website's own address instead.

## Caddy

Caddy is the front door for every host. The rules for cms's sites are `platform/caddy/cms.caddy`,
mounted at `/etc/caddy/sites/` and imported by both Caddyfiles, which add the global options,
pacod-id's site and the certificates; `CADDYFILE` picks one. The fragment's addresses are
`CMS_STUDIO_SITE` and `CMS_PUBLIC_SITE`, which the Makefile derives from `STUDIO_HOST` and the
preset's scheme (`http://<host>, https://<host>` and `http://, https://` on dev; `https://<host>`
and `https://` on stage and prod), and `CMS_PUBLIC_TLS` switches the websites' on-demand
certificates on. The full stack imports the same fragment behind its own HTTP-only edge.

| `CADDYFILE` | For | What it serves |
|---|---|---|
| `./docker/caddy/Caddyfile` (`env/stage.env`, `env/prod.env`, and compose's default) | stage, production | HTTPS for every host, HTTP redirected to it |
| `./docker/caddy/Caddyfile.dev` (`env/dev.env`) | a developer machine | plain HTTP on `HTTP_PORT`; HTTPS on `HTTPS_PORT` from Caddy's own local CA |

| Host | Goes to |
|---|---|
| `STUDIO_HOST` | the studio (`cms-studio:7443`); `/` answers 302 to `/mgmt/sites` |
| `ID_HOST` | pacod-id's public listener (`pacod-id:8070`), Host and `Origin` passed unchanged; its internal listener (`pacod-id-internal:8071`, on `pacod-admin-net` only) is reached only by the studio |
| every other host | the public site (`cms-site:8443`), which picks the website by host and answers 404 for a host no website claims |

- **No list of websites is kept in Caddy**, so a domain added to a website needs no Caddy change —
  only DNS. Its certificate follows by itself (*TLS*).
- **`STUDIO_HOST` and `ID_HOST` are read when Caddy parses its file.** Changing either, or the
  Caddyfile, needs `make caddy-reload`: it validates the file in a one-off container, then
  recreates Caddy. Caddy needs no restart when the applications are recreated; it resolves them
  for every new connection.
- **Caddy waits for pacod-id to start, not to be healthy**, and the studio does not wait for it at
  all: a pacod-id that is down costs sign-in, never the websites. pacod-id trusts the forwarded
  client address from private ranges (`PID_TRUSTED_PROXIES`), so its sign-in throttle sees each
  client rather than Caddy.
- **The Host passes unchanged, and `X-Forwarded-Host` keeps its port**, so redirects keep a
  non-default port. `X-Real-IP` is set to the TCP peer, which the crawler statistics trust; a
  client-written `X-Forwarded-For` is replaced, not appended to; `X-Pacod-Print` is always removed,
  so no request from outside can carry it; `/_internal/` answers 404.
- **Request bodies stop at 4 MiB**, the same as the studio's multipart limits — `4MiB` in
  `cms.caddy`, never `4MB`, which Caddy reads as 4,000,000 bytes. Raise them together.
  pacod-id's host takes 1 MiB.
- Each request is logged to Caddy's stdout (`make logs-pacod-caddy`), JSON or `console` by
  `CMS_CADDY_LOG_FORMAT` (default `json`). Caddy leaves out `Cookie`, `Set-Cookie` and
  `Authorization`; `cms.caddy` also removes the query parameters `token`, `code`, `state`, `next`,
  `flow`, `id_token_hint`, `login_hint` and `key` (the sign-in round trips carry `code` and
  `state`), the `Referer`, `X-Api-Key` and `X-Goog-Api-Key` headers and the response's `Location`.

### Behind an existing Caddy

A host that already runs a Caddy (and a pacod-id) for other services cannot also run cms's own:
`pacod-caddy`, `pacod-id` and `pacod-postgres` are container names, unique on a Docker host, and
compose adds a service's name as an alias on every network it joins. `PROXY_MODE=external` runs the
app file alone with `docker-compose.external.yml`. The cms services keep their own names,
`cms-site`, `cms-studio`, `cms-mongo` and `cms-gotenberg`. The site and the studio join the
existing Caddy's network (`CADDY_NETWORK`). The studio signs people in through the pacod-id that
runs there.

```bash
# .env, besides make init's: the hosts, the existing Caddy and the existing pacod-id
PROXY_MODE=external
STUDIO_HOST=studio.example.com
ID_HOST=id.example.com                    # the existing pacod-id's
CADDY_NETWORK=caddy                       # docker inspect <its container> lists its networks
CADDY_CONTAINER=caddy
CADDY_SITES_DIR=/srv/caddy/sites          # the host folder it imports *.caddy from
CMS_PUBLIC_SITE=https://www.example.com   # the websites' hosts: never a catch-all here
CMS_STUDIO_TLS=tls /etc/caddy/certs/example.full /etc/caddy/certs/example.key
CMS_PUBLIC_TLS=tls /etc/caddy/certs/example.full /etc/caddy/certs/example.key
PACOD_ID_INTERNAL_URL=http://pacod-id:8071
PACOD_ORG_CREATION=platform-admins

make up              # checks the network is there, starts cms, waits for the health checks
make caddy-config    # writes CADDY_SITES_DIR/pacod-cms.caddy (prints it when that is unset)
make caddy-reload    # caddy validate, then caddy reload, in CADDY_CONTAINER
```

Before `make up`, register the studio's two clients in that pacod-id: `pacod-cms-studio` (sign-in,
redirect `https://<STUDIO_HOST>/mgmt/oauth2/code/pacod-id`) and `pacod-cms-service` (the
catalogue and the authorisation feed), with the secrets `CMS_ID_STUDIO_SECRET` and
`CMS_ID_SERVICE_SECRET` from `.env`. They are cms's own pacod-id's, in `docker/pacod-id/pacod-id.toml`;
copy both `[[client]]` blocks into that pacod-id's configuration, in the realm its other products use. That pacod-id
must be a build the studio can use: the catalogue push, the feed and the scopes. `make up` creates
no provisioner there, so only platform admins create organisations.

`make caddy-config` writes `platform/caddy/cms.caddy` itself, with every placeholder filled from
`.env` (`scripts/caddy-config.sh`). The existing Caddy has none of cms's variables, and an
unset one would fall back to its default; for `CMS_PUBLIC_SITE` that default is a catch-all, which
would take every other site that Caddy serves. So the script refuses a catch-all. `CMS_STUDIO_TLS`
and `CMS_PUBLIC_TLS` carry the `tls` lines that Caddy needs. Where it obtains certificates itself,
leave them unset. `CMS_PUBLIC_EXTRA_FILE` names a file of directives for the websites' site, which
the script copies in before the proxy. Use it for the redirects of a website's old addresses. Name
it anything but `*.caddy` if it lies in the sites folder. The bundled Caddy takes the same setting:
there the file is mounted and imported in the same place, and its `{$VAR}` placeholders are that
Caddy's environment, so their defaults apply. Its hosts still need a certificate: in the bundled
Caddy one comes on demand only for a website's own hosts, so a host that only redirects is either
one of the website's addresses or covered by a supplied certificate pair.

The existing Caddyfile needs `import sites/*.caddy` (or `import /etc/caddy/sites/*.caddy`) once,
with the folder mounted. Without it, paste the file in. Where that Caddy's admin API is off,
`caddy reload` fails; `make caddy-reload RESTART=1` restarts the container instead, which
interrupts every site it serves for a moment.

When the studio and the websites share a registrable domain (`studio.example.com`,
`www.example.com`), set `PACOD_SITE_DOMAIN_SHARED=true`, and serve both over HTTPS
([The studio's and pacod-id's domains](#the-studios-and-pacod-ids-domains)); `make check` says
so.

## TLS

**Caddy terminates TLS.** The applications stay on plain HTTP behind it (`SSL_ENABLED=false`) and
run with `SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK`, so redirects and absolute URLs follow
`X-Forwarded-Proto` and `X-Forwarded-Host`. With the production Caddyfile, Caddy obtains every
certificate itself:

- the studio's and pacod-id's hosts (`STUDIO_HOST`, `ID_HOST`) the usual way, from Let's Encrypt;
- **a website's host on demand**, at the first visit to it, once the public site confirms the host
  belongs to an active website (`/_internal/tls-ask`, asked on pacod-net). A domain added
  in the studio needs nothing else; a name nobody serves gets no certificate, so a scanner cannot
  spend the CA's rate limit for you.

Point each host's DNS at the server before it is used — the studio's and pacod-id's before the first
start, a website's before its address is added in the studio — and keep ports 80 and 443 reachable.
Caddy keeps its certificates and CA account in the `pacod-caddy-data` volume: **keep it** across upgrades,
or every certificate is requested again. `CADDY_GLOBAL_OPTIONS` adds one line to Caddy's global
options, typically `email ops@example.com` for the CA's notices.

**A certificate pair of your own** is possible too. Name it and Caddy serves it for every name it
covers, and obtains certificates only for the rest:

| Variable | Meaning |
|---|---|
| `SSL_CERT_FILE` | full chain, leaf first, mounted at `/etc/caddy/tls/fullchain.pem` |
| `SSL_KEY_FILE` | the matching key, mounted at `/etc/caddy/tls/privkey.pem` |

Naming `SSL_CERT_FILE` is the whole switch; unset both to go back. The switch is
`${SSL_CERT_FILE:+…}` in `docker-compose.standalone.yml`, so check that your Compose interpolates it:
`docker compose --env-file env/prod.env --env-file .env -f docker-compose.yml -f docker-compose.standalone.yml config | grep CADDY_SUPPLIED_PAIR` must
print the line. The files are mounted one by one, so certbot's
`/etc/letsencrypt/live/example.com/fullchain.pem` and `privkey.pem` work as they are.
`make check-tls` checks the pair (readable, matching, a full chain, not expired) and `make up` runs
it first. After a renewal, copy the key that was generated *with* the new certificate — certbot
mints a new key pair unless `--reuse-key` was passed — then run `make check-tls && make caddy-reload`:
Caddy reads the pair when it starts. `make dev-cert` writes a self-signed pair for trying this on a
developer machine.

On a developer machine `Caddyfile.dev` serves HTTPS on `HTTPS_PORT` from Caddy's own local CA, which
no browser trusts: each host warns once. `make smoke-tls` checks the routes over HTTPS and prints
each host's certificate.

HSTS is deliberately off; add it in `platform/caddy/cms.caddy` once you are sure every host will keep a valid
certificate.

**Tell the studio how websites are addressed.** `SITE_PUBLIC_SCHEME` and `SITE_PUBLIC_PORT` are
how the studio spells a website's public address — for its "view site" links and for framing
banner previews from the website's own origin. Compose passes both through without a value, so the
profile decides: production uses `https` and the scheme's default port, which is right whenever
readers reach the websites through Caddy on 443. Set them only when they reach them otherwise.

### TLS in the JVM

Possible, and off by default. No keystore is committed; the applications read one at runtime:

| Variable | Meaning | Default |
|---|---|---|
| `SSL_ENABLED` | turn TLS on | `false` in compose |
| `SSL_KEYSTORE` | Spring resource URL | `file:./config/keystore.p12` |
| `SSL_KEYSTORE_PASSWORD` | keystore password | *none — must be set* |
| `SSL_KEY_ALIAS` | alias inside the keystore | `pacod-cms` |

`make keystore` (`scripts/generate-dev-keystore.sh`) generates a self-signed one and prints its
password. In compose, uncomment the `./config` mount and the `SSL_KEYSTORE*` lines for each
service. `*.p12`, `*.jks`, `*.pem`, `*.key` and `*.crt` are git-ignored; never commit one.

## Configuration

MongoDB's passwords have no default: compose requires `MONGO_ROOT_PASSWORD` and `MONGO_PASSWORD`
("run make init"), `make init` writes both into `.env` in every environment, and `make check`
refuses a missing one. The account names, `MONGO_ROOT_USER` and `MONGO_USER`, default to
`pacod-root` and `pacod-cms`. MongoDB creates both accounts on its first start, so changing any of
the four in `.env` later does not change the accounts. pacod-id's secrets have no default either
(see *Before the first start*).

Set per service, not globally:

| Variable | Service | Notes |
|---|---|---|
| `STUDIO_HOST`, `ID_HOST` | `pacod-caddy` | the studio's host and pacod-id's; every other host is a website's |
| `CMS_STUDIO_SITE`, `CMS_PUBLIC_SITE` | `pacod-caddy` | the addresses of cms's two Caddy sites (`platform/caddy/cms.caddy`); derived from `STUDIO_HOST` and the preset, set only to override |
| `CADDYFILE`, `SSL_CERT_FILE`, `SSL_KEY_FILE`, `CADDY_GLOBAL_OPTIONS` | `pacod-caddy` | which Caddyfile; an optional certificate pair of your own; one extra global line (*TLS*) |
| `STUDIO_ORIGIN`, `ID_ISSUER` | `cms-studio`, `pacod-id` | the addresses browsers use for the studio and for pacod-id (see above) |
| `STUDIO_ORIGIN` | `cms-site` | the one other page allowed to frame the site: banner folders and draft previews (`frame-ancestors`) |
| `PACOD_PREVIEW_SECRET` | `cms-site`, `cms-studio` | the same value on both; signs draft previews (see *Before the first start*); blank: no previews |
| `ID_ADMIN_EMAIL`, `ID_ADMIN_PASSWORD` | `pacod-id` | the first account, a platform admin; created once |
| `ID_MAIL_FROM` | `pacod-id` | the sender of pacod-id's mail (default `id@pacod-cms.test`) |
| `MGMT_SESSION_TIMEOUT` | `cms-studio` | idle time before the studio's session ends (default `8h`); see below |
| `PACOD_ID_PROVISIONER_TOKEN` | `cms-studio` | the provisioner's `pidt_` token (`make id-provisioner`); empty: only platform admins create organisations |
| `PACOD_SITE_DOMAIN` | `cms-studio` | new websites answer at `<id>.<domain>` (compose default `localhost`); never the studio's registrable domain |
| `PACOD_ORG_CREATION` | `cms-studio` | `self-service` (default) or `platform-admins`, a ceiling the installation screen cannot lift |
| `PACOD_MAX_ORGS_PER_USER`, `PACOD_MAX_SITES_PER_ORG`, `PACOD_AI_FOR_NEW_ORGS` | `cms-studio` | starting values (3, 5, false); the installation screen owns them once saved |
| `IMAGE_REQUIRED_WIDTH`, `IMAGE_REQUIRED_HEIGHT`, `IMAGE_MAX_BYTES`, `IMAGE_DIMENSION_CHECK`, `IMAGE_FILE_SIZE_CHECK` | `cms-studio` | what an uploaded article image must be: at least 600×400, in that shape, and 400 KB by default; read at startup |
| `SITE_PUBLIC_SCHEME`, `SITE_PUBLIC_PORT` | `cms-studio` | how the studio spells a website's address |
| `DEMO_HOSTS` (as `PACOD_BOOTSTRAP_DEMO_HOSTS`), `DEMO_MINIMAL_HOSTS` (as `PACOD_BOOTSTRAP_DEMO_MINIMAL_HOSTS`), `DEMO_SPACE_HOSTS` (as `PACOD_BOOTSTRAP_DEMO_SPACE_HOSTS`) | `cms-studio` | the three demo websites' hosts, used only on an empty platform |
| `PACOD_PDF_GOTENBERG_URL` | `cms-studio` | Gotenberg, for PDF export (compose: `http://cms-gotenberg:3000`); blank switches it off |
| `PACOD_JOBS_WORKERS`, `PACOD_JOBS_PER_ORGANISATION` | `cms-studio` | jobs run at once (2), and of one organisation (1) |
| `PACOD_GW_URL`, `PACOD_GW_API_KEY` | `cms-studio` | the AI gateway (pacod-gw, default `http://pacod-gw:8080`) and the studio's key for it; without a key AI is not set up |
| `PACOD_AI_MODEL` | `cms-studio` | the model or pool each AI feature asks for (default `@fast`) |
| `COMPOSE_PROFILES` | compose (the presets) | `dev-gateway,mailpit` on dev and stage: the development gateway and the mail catcher; empty in production |
| `METRICS_VISITOR_SALT` | `cms-site` | salts the opaque reader key; blank means a new random salt per restart |
| `CRAWLER_TRACKING`, `CRAWLER_VERIFY` | `cms-site` | crawler log, and its reverse/forward DNS check (needs outbound DNS) |
| `CORS_ALLOWED_ORIGINS` | both | comma-separated origin patterns; a bare `*` is refused at startup |
| `STORAGE_ROOT` | both | `/data/storage`, where `STORAGE_HOST` is mounted |

**`CORS_ALLOWED_ORIGINS` has no production default worth keeping.** Unset, every profile falls
back to `http://localhost:[*]`. Set it to the websites' own origins in production.

**Jobs run inside the studio.** `/mgmt/jobs` queues work (PDFs of articles, and AI translations of
articles and of the rest of a website) in the control plane, and the studio runs it on a couple of
virtual threads, one job of an organisation at a time. A restart abandons a running job; it is run
again from the start once its lease (60 s) runs out, at most three times.

**Gotenberg is the one service that reads what authors wrote.** Compose starts it with a Chromium
that may reach `http://cms-site:8443` and nothing else, publishes no port, and switches JavaScript off;
keep those flags if you run it elsewhere. The PDFs go into each website's
`resources/pdfs/articles/<lang>/`, and an article links its PDF only once it exists.

## The studio

The studio (`pacod-cms-studio`, compose service `cms-studio`) answers on `STUDIO_HOST`: the sign-in page
and the website list at `/mgmt/login` and `/mgmt/sites`, and each website's screens under
`/sites/<id>/mgmt/`. It has its own health endpoint, `/mgmt-health`, used by the compose
healthcheck. Its routes, properties and variables use the name `mgmt`.

### Signing in

An unauthenticated request to `/mgmt/sites` answers **302** to `/mgmt/login`, whose two links ("Sign
in", "Create an account") lead to pacod-id's own pages at `ID_ISSUER`; pacod-id sends the browser
back to `STUDIO_ORIGIN` and signing in lands on the website list. `make smoke` prints the redirect
targets as well as the codes, because a 302 that drops the port is still a 302, and checks that
pacod-id answers its discovery document on `ID_HOST`. `make e2e-signin` goes further on a running
stack: a self-registered account, the bootstrap admin, a studio restart, a session ended in
pacod-id, and sign-out — it restarts the `cms-studio` container on the way.

`/mgmt-health` is liveness only: a studio that cannot reach pacod-id stays healthy while its
screens answer 503, and says so in its log (`make logs-cms-studio`; pacod-id's own is `make logs-pacod-id`).

### Staying signed in

`MGMT_SESSION_TIMEOUT` (default `8h`, Spring Boot syntax, read at startup) bounds how long the
studio's session lasts **idle**; sessions live in the studio's heap, so they also end when the
container restarts. Either way the next request goes through pacod-id again, whose own session —
by its defaults 12 hours, or 7 days with "keep me signed in" — signs the person straight back in
without a password. One sign-in covers every website, and signing out of the studio ends the
pacod-id session too.

**In production the studio's cookies are `__Host-` cookies** (`__Host-studio-session` and
`__Host-XSRF-TOKEN`), which a browser keeps only over HTTPS: the studio must be reached over HTTPS
as the browser sees it, end to end.

### Mail

pacod-id sends address confirmations, invitations and password resets.

- **On dev and stage it all goes to `pacod-mailpit`** (compose profile `mailpit`). Its page is published on
  the host's loopback interface only (`MAILPIT_PORT`); nothing leaves the machine. On a stage
  server, read it through `ssh -L 8125:localhost:8125 <server>`.
- **Production names a relay** in `.env`: `PID_SMTP_HOST`, `PID_SMTP_PORT` (587), `PID_SMTP_TLS`
  (`starttls`, `tls` or `none`), `PID_SMTP_USERNAME` and `PID_SMTP_PASSWORD`. The sender is
  `ID_MAIL_FROM`, an address on a domain the relay may send for. `make check` refuses to start
  production without both: with self-registration on, anyone could otherwise create accounts in
  addresses that are not theirs.
- **pacod-id connects only when it sends**, so it starts without the relay; a wrong setting shows
  in `make logs-pacod-id` at the first mail.

### Caching across applications

The studio writes in a different JVM from the site's read caches, so it cannot evict them. **An
edit made in the studio is live on the public site within 60 seconds**, the uniform TTL in the
site's `CacheConfig`. Every cache entry is kept per website. Exact invalidation would need a shared
cache or an invalidation endpoint the writers call.

## Operations

`make` lists every target, grouped; [`RUNNING.md`](RUNNING.md) walks through them per environment.

| Target | What it does |
|---|---|
| `make init` | write `.env` for `ENV` with fresh secrets, or add the ones an existing `.env` lacks; never replaces one data depends on |
| `make up`, `make down`, `make status` | check, build, start and wait; stop; the environment, its containers and its addresses |
| `make check` | the preflight `make up` runs: is `.env` complete and safe for `ENV` |
| `make restart` | rebuild and recreate both applications; MongoDB, pacod-id and Caddy keep running |
| `make logs`, `make logs-<service>`, `make shell-<service>` | every log, one service's log (`cms-site`, `cms-studio`, `pacod-id`, `pacod-caddy`, `cms-mongo`, …), a shell |
| `make caddy-reload` | validate the Caddyfile, then recreate Caddy: after changing a host, the Caddyfile or a supplied certificate pair |
| `make images` | pacod-id's image from `PACOD_ID_SRC`, and the mock gateway's on dev and stage, if missing; run by `make up` |
| `make mongo` | `mongosh` on the control plane; `make mongo SITE=<id>` on that website's database |
| `make site-create SLUG= NAME= HOSTS= [LANGUAGE=] [ORG=] [TEMPLATE=] [DEMO=1]` | create a website, in a new organisation or `ORG` (see above) |
| `make seed SITE=<id>` | the demo content into a website that has no articles (dev and stage default to `demo`); English websites only |
| `make restore DUMP=dir [FROM_DB= TO_DB=] [EXCLUDE=…]` | `mongorestore` a dump from `MONGO_BACKUP_DIR`; reports unless `APPLY=1` (and `CONFIRM=<env>` outside dev). A `FROM_DB` dump is renamed into `TO_DB`, the control plane by default; only the collections the dump carries are dropped and replaced |
| `make reseed-pages SITE=<id>` | rebuild a website's standing pages from `seed/pages.json`: backs its collection up, drops it, rebuilds the studio image and recreates the container, which re-seeds; reports unless `APPLY=1` (and `CONFIRM=<env>` outside dev). `make reseed-pages-restore` puts the backup back |
| `make smoke`, `make smoke-tls` | the main routes through Caddy by host, each code and redirect checked (HTTPS on stage and prod; `SITE=<id>` names a website in production); each host's certificate |
| `make e2e` (`e2e-signin`, `e2e-portal`) | sign-up, sign-in, restart, ended session and sign-out through pacod-id, then the portal, on the running stack (dev and stage: it reads pacod-mailpit; restarts `cms-studio`) |
| `make destroy` | stop and delete every volume (`CONFIRM=<env>` outside dev) |
| `make it` | the integration tests, against MongoDB in a Testcontainers container (Docker) |

The public site's own JSON endpoints live under `/web/v1/**`; they are unauthenticated, read-only
and scoped to the website the host names.
