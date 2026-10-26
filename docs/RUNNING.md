# Running pacod-cms

One Makefile runs pacod-cms in three environments: **dev** on a developer machine, **stage** on a
server where testers can try anything, and **prod**. This guide covers starting each one the first
time, giving it content, keeping it running and checking that it works. `make` on its own lists
every target, grouped. [`DEPLOYMENT.md`](DEPLOYMENT.md) is the reference behind it: services,
hosts, certificates, sign-in, every setting.

Every environment runs on the shared Docker network `pacod-net` (README, *Shared network*), with
cms's own services in `docker-compose.yml` (`cms-site`, `cms-studio`, `cms-mongo`, `cms-gotenberg`)
and its copies of the shared components in `docker-compose.standalone.yml` (`pacod-caddy`,
`pacod-id`, `pacod-postgres`, `pacod-db-init`, `pacod-gw`, `gw-mock`, `pacod-mailpit`). The Makefile
loads both.

## The three environments

| | dev | stage | prod |
|---|---|---|---|
| Runs on | a developer machine | a server with domains of its own | a server |
| Spring profile | `dev` (templates re-read) | `prod` | `prod` |
| Front door (Caddy) | HTTP on 8000; HTTPS on 8443 from Caddy's local CA | HTTPS on 443 (Let's Encrypt), HTTP redirected | the same as stage |
| Hosts | `*.localhost`, nothing to set up | yours, in DNS | yours, in DNS |
| pacod-id's mail | pacod-mailpit, http://localhost:8125/ | pacod-mailpit (through an SSH tunnel) | your relay (`PID_SMTP_*`) |
| AI | the mock gateway (echoes) | the mock gateway | your pacod-gw, or off |
| Demo websites | created on the first start | created on the first start | none |
| Application and MongoDB ports | every interface | loopback only | loopback only |
| Data | Docker volumes, `./.data/` | `/data/…` | `/data/…` |

Stage is a rehearsal of production that cannot hurt anyone. It runs production's configuration,
but nobody real receives its mail and nothing is spent on AI. To try real mail or a real gateway
there, set `PID_SMTP_*` or `PACOD_GW_*` in its `.env`.

### Where the settings come from

Three files, the later winning:

| File | Holds | In git |
|---|---|---|
| `env/<ENV>.env` | the environment's shape: profile, Caddyfile, ports, paths, what runs. No secrets | yes |
| `.env` | this machine: `ENV=dev\|stage\|prod`, its hosts, its secrets. Written by `make init` | no |
| `.env-local` | optional overrides for this machine, read last | no |

[`.env.example`](../.env.example) lists every setting a `.env` may hold, with what it does. Copy a
line from it into `.env` to change something.

A command-line assignment beats all three files: `make up HTTP_PORT=8100`. `HTTP_PORT=8100 make up`
does **not** work, because an included file beats the environment. Write values bare, without
quotes, `#` or `$`: make and compose both read these files, and only a bare value means the same to
both. `make check` says so if one is not. `make status` shows the environment in use, its
containers and its addresses.

### What a machine needs

- **Docker** with Compose v2.20 or later (`docker compose version`), **make**, **curl**, **openssl** and
  **Node** 20 or later. `make up` uses Node to set up the studio's provisioner account in pacod-id.
- **pacod-id**: an image `pacod-id:dev` (`PACOD_ID_IMAGE`), or its sources at `../pacod-id`
  (`PACOD_ID_SRC`). `make up` builds the image from the sources once, a slow Rust build.
- **dev and stage**: the mock AI gateway's images, or pacod-gw's sources at `../pacod-gw`
  (`PACOD_GW_SRC`). These are also built once.
- A JDK 25 only for `make build`, `make test`, `make verify` and `make it`. The images build inside
  Docker.

## Development

```bash
make init        # .env with ENV=dev and fresh secrets; nothing is printed
make up          # check, build, start, wait; prints the addresses
```

Then open **http://studio.localhost:8000/** and sign in as `admin@pacod-cms.test` with the password
`ID_ADMIN_PASSWORD` from `.env`. This is the bootstrap platform admin. The first start creates three
demo websites with the demo content:

- **"Demo Journal"** (editorial template set) at http://demo.localhost:8000/ and http://localhost:8000/
- **"Demo Notes"** (minimal template set) at http://minimal.localhost:8000/
- **"Starward"**, the space-travels demo (magazine template set), at http://space.localhost:8000/

**A platform created before the current Starward** -- before the space demo existed, or with its
earlier, thinner version -- gets it on a website of its own. Demo content goes only into a website
without articles, so an existing `space` website has to go first: in the studio open it, *Settings
→ Delete this website*, type its id; wait three minutes (`SiteRetirement.SETTLE`) until the id is
free again, then:

```bash
make site-create SLUG=space NAME=Starward HOSTS=space.localhost DEMO=space
```

`DEMO=space` without `TEMPLATE=` puts it on the magazine set it was designed for. On dev,
`make destroy && make up` gives the same on a fresh platform (and drops every account).

pacod-id's sign-in pages are at http://id.localhost:8000/, and every mail it sends lands in pacod-mailpit,
http://localhost:8125/. **Create an account** on the sign-in page makes an account with no role,
which sees no website.

Every day:

| | |
|---|---|
| `make restart` | rebuild and recreate `cms-site` and `cms-studio` after a code change. MongoDB, pacod-id and Caddy keep running |
| `make logs`, `make logs-cms-studio`, `make logs-cms-site`, `make logs-pacod-id`, `make logs-pacod-caddy` | follow logs: text lines on dev (`APP_LOG_FORMAT=text` in `env/dev.env`), JSON on stage and prod; `APP_LOG_LEVEL=debug` in `.env` for more. The applications also write JSON files, `cms-site.log` and `cms-studio.log`, under `LOG_DIR_HOST` (`pacod-demo/docs/LOGGING.md`) |
| `make shell-cms-studio`, `make shell-cms-site` | a shell in a container |
| `make mongo`, `make mongo SITE=demo` | mongosh on the control plane, or on a website's database |
| `make down` | stop everything; the data stays |
| `make destroy` | stop and delete every volume: content, accounts, certificates. Start again with `make up` |

If a port is taken, move the stack on the command line, and keep doing so for every target that
starts containers:

```bash
make up HTTP_PORT=8100 HTTPS_PORT=8543 WEB_PORT=8180 MGMT_PORT=7170 MONGO_PORT=27117
make smoke HTTP_PORT=8100 WEB_PORT=8180 MGMT_PORT=7170
```

Or put the ports in `.env`. pacod-id's issuer and the studio's sign-in addresses follow
`HTTP_PORT`. A container recreated on another port than the others breaks sign-in.

## Stage

### Before the first start

- **Two registrable domains.** One is for the studio and pacod-id, for example
  `studio.example.com` and `id.example.com`. The other is for the websites, for example
  `stage.example-sites.net`. The studio refuses to start when a website's address shares a
  registrable domain with it: a website runs its owner's script, which could otherwise set cookies
  for the studio. So `studio.stage.example.com` with `demo.stage.example.com` is refused.
- **DNS**, pointing at the server:
  - the studio's host and pacod-id's;
  - `demo.<site domain>`, `minimal.<site domain>` and `space.<site domain>`, for the demo websites;
  - ideally `*.<site domain>` too, for the websites testers create.
- **Ports 80 and 443** reachable from the internet. Caddy obtains its certificates over them.
- pacod-id's and the mock gateway's images or sources on the server (*What a machine needs*).

### First start

```bash
git clone … pacod-cms && cd pacod-cms
make init ENV=stage STUDIO_HOST=studio.example.com ID_HOST=id.example.com \
          SITE_DOMAIN=stage.example-sites.net EMAIL=ops@example.com
make up
```

- **`make init`** writes `.env` with:
  - `ENV=stage` and the hosts;
  - the demo websites' hosts, derived from `SITE_DOMAIN`;
  - `EMAIL` as the first account's sign-in and as the address for Let's Encrypt's notices;
  - fresh secrets.
- **`make up`** checks the configuration first. It refuses to start with `*.localhost` hosts or
  ports other than 80/443. The demo websites are created **once**, at the hosts `.env` names on the
  first start.
- Sign in at `https://<studio host>/` as `EMAIL`, with `ID_ADMIN_PASSWORD` from `.env`.

Read stage's mail from your machine through a tunnel: `ssh -L 8125:localhost:8125 <server>`, then
open http://localhost:8125/. The application and MongoDB ports are reachable the same way.

**Starting stage over.** `make destroy CONFIRM=stage` deletes the Docker volumes: pacod-id's accounts
and roles, and Caddy's certificates. It leaves alone what stage keeps in host folders: MongoDB in
`/data/mongo/db` and the websites' files in `/data/storage`. After it alone, the organisations and
websites are still there, nobody holds a role at them, and no demo websites are created, because
the platform is not empty. For a clean slate, delete those two folders as well before `make up`:

```bash
make destroy CONFIRM=stage
sudo rm -rf /data/mongo/db /data/storage     # stage's content -- not on a machine whose data matters
make up
```

Let's Encrypt limits how often the same certificates may be issued again, so don't reset stage many
times a week.

## Production

### Before the first start

- The same **two registrable domains**, **DNS** for the studio's and pacod-id's hosts, and **ports
  80 and 443** as on stage. A website's own domains are added later, in the studio. Point each at
  the server first; Caddy obtains its certificate at the first visit.
- **A mail relay** for pacod-id: account confirmations, invitations, password resets.
- A disk with room under `/data` (MongoDB, the websites' files, logs, backups), or other paths in
  `.env` (`MONGO_DATA_DIR`, `STORAGE_HOST`, `LOG_DIR_HOST`, `MONGO_BACKUP_DIR`).
- pacod-id's image or sources on the server.

### First start

```bash
make init ENV=prod STUDIO_HOST=studio.example.com ID_HOST=id.example.com \
          SITE_DOMAIN=sites.example.net EMAIL=ops@example.com
$EDITOR .env     # fill in the mail block: PID_SMTP_HOST, PID_SMTP_USERNAME/PASSWORD, ID_MAIL_FROM
make check       # every ✗ says what is missing
make up
```

- **Back up `.env` now**, somewhere safe. Its database passwords and pacod-id's key cannot be
  replaced once data exists, and `make init` refuses to write new ones then.
- **`SITE_DOMAIN`** is where new websites answer first (`<id>.<SITE_DOMAIN>`). Leave it out and each
  website's creator names its address.
- **Optional, in `.env`**:
  - a real AI gateway (`PACOD_GW_URL`, `PACOD_GW_API_KEY`); AI stays off without one;
  - a certificate pair of your own (`SSL_CERT_FILE`, `SSL_KEY_FILE`), which Caddy serves for every
    name it covers.

  [`DEPLOYMENT.md`](DEPLOYMENT.md) covers both, and everything else.
- **First sign-in**: at `https://<studio host>/` as `EMAIL`, with `ID_ADMIN_PASSWORD` from `.env`.
- **Production starts empty.** Create the first organisation and website in the studio
  (*Organisations → New organisation*, then *Websites → New website*), or with `make site-create`
  (below).

### Updating to a new release

```bash
git pull
make check       # a release may need a new setting; init adds missing secrets: make init
make up          # rebuilds what changed and recreates it; or `make restart` for the applications alone
make smoke
```

`make check` also lists the keys in `.env` and `.env-local` that nothing in the release reads;
remove them when convenient.

### Moving to pacod-net

The release that moved pacod-cms onto the shared network renamed its services and volumes (README,
*Shared network*). `make check` refuses to start while a value still names an old service
(`PID_SMTP_HOST=mailpit`, `PACOD_GW_URL=http://gw:…`, `PACOD_PDF_GOTENBERG_URL=http://gotenberg:…`,
`MONGO_DATA_DIR=mongo-data`) or while an old volume holds data the new one lacks. The data stays
where it was until it is copied, with every container of the stack stopped -- the old ones too,
which `make down` removes as orphans; the volumes are named after the checkout's folder,
`pacod-cms` here:

```bash
make down                                                    # --remove-orphans: the old containers too
docker ps -a --filter label=com.docker.compose.project=pacod-cms   # must list nothing
for pair in id-pg-data:pacod-postgres-data caddy-data:pacod-caddy-data caddy-config:pacod-caddy-config \
            mongo-data:cms-mongo-data; do
  docker volume inspect "pacod-cms_${pair%%:*}" >/dev/null 2>&1 || continue   # only the ones that exist
  docker run --rm -v "pacod-cms_${pair%%:*}:/from:ro" -v "pacod-cms_${pair#*:}:/to" \
    postgres:18.6-alpine3.24 sh -c 'cp -a /from/. /to/'
done
make up
```

Then set `MONGO_DATA_DIR=cms-mongo-data` wherever `.env` or `.env-local` still says `mongo-data`.
`id-pg-data` holds every pacod-id account: without the copy, pacod-id starts empty, with a new
bootstrap admin. `caddy-data` holds the certificates and the CA account: without it every
certificate is obtained again. Once the stack answers, `docker volume rm` the old ones. The
pacod-net network replaces `pacod-cms-net` (`DOCKER_NETWORK` is gone): `docker network rm
pacod-cms-net` when nothing uses it.

### Backups

Keep copies of five things:

- `.env`;
- the MongoDB databases;
- the websites' files (`STORAGE_HOST`);
- pacod-id's PostgreSQL;
- optionally the `pacod-caddy-data` volume, to avoid re-issuing certificates.

For example, nightly (both dump commands checked against a running stack):

```bash
docker exec cms-mongo sh -c 'mongodump -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" \
  --authenticationDatabase admin --out /data/backup/$(date +%F)'      # lands in MONGO_BACKUP_DIR
docker exec pacod-postgres pg_dumpall -U postgres > /data/backup/pacod-id-$(date +%F).sql
tar -czf /data/backup/storage-$(date +%F).tgz -C /data storage
```

`make restore DUMP=<folder under MONGO_BACKUP_DIR>` puts a MongoDB dump back. It only reports what
it would do until you add `APPLY=1`, and `CONFIRM=prod` too in production.

**One website, or one organisation, is exported and restored in the studio** rather than here: a
website's *Settings* screen and an organisation's screen offer *Export* (a ZIP of its content and
files, without traffic statistics, members or hosts), and *Websites → Restore a website* and
*Organisations → Restore an organisation* bring one back -- always as a **new** website or
organisation, on this installation or another. Uploads up to 2 GiB pass Caddy on those two paths
only (`CMS_ARCHIVE_MAX_SIZE`; the studio's `pacod.archive.max-bytes`). It is the way to move one
website between installations; the dumps above remain the way to back up the whole platform.

## What the first start does

`make up` runs these steps in order, each safe to repeat:

1. **`make check`**: is `.env` complete for this environment (above)?
2. **The Docker networks and the images**: `pacod-net` and `pacod-admin-net` (internal), if missing; pacod-id's image, and the mock
   gateway's on dev and stage. Each is built from its sources only if missing. Then `cms-site` and
   `cms-studio` are built from this checkout.
3. **The containers start.** MongoDB creates its two accounts on its first start. `pacod-db-init`
   makes sure pacod-id's role and database exist on `pacod-postgres` (at every start), then pacod-id
   creates its tables and the bootstrap admin (`ID_ADMIN_EMAIL` / `ID_ADMIN_PASSWORD`), once.
4. **The studio bootstraps the platform**, at every start:
   - it builds the control plane's indexes;
   - it re-provisions every website that is out of date;
   - on an empty platform with demo websites on (dev, stage), it creates `demo`, `minimal` and
     `space` (on the magazine set) with the demo content.

   It also tells pacod-id its roles and registers every organisation and website there.
5. **`make up` waits** until the studio, and the demo website where there is one, answer through
   Caddy. On stage and prod this includes the time to obtain the first certificates.
6. **The studio's provisioner** is a pacod-id service account that makes the creator of a new
   organisation its admin. It is created once, its token written into `.env`, and the studio
   recreated to read it. Without it only platform admins can create organisations.
7. **The addresses are printed.**

## Content

**A website** is created in the studio (*Websites → New website*, ticking *Start with demo content*
if wanted) or from the command line:

```bash
make site-create SLUG=acme NAME="Acme News" HOSTS=acme.localhost                  # dev
make site-create SLUG=acme NAME="Acme News" HOSTS=acme.example-sites.net,www.acme.example-sites.net \
                 LANGUAGE=de TEMPLATE=minimal                                     # stage, prod
```

- `ORG=org_…` puts it in an existing organisation; without it, it gets an organisation of its own.
- `DEMO=1` installs the demo content; `DEMO=space` the space travels, on the magazine set unless
  `TEMPLATE=` names another.
- Its hosts must resolve to the server, except on dev.

**The demo content** is *Demo Journal*: six sections, four authors, twenty published articles with
generated pictures (five also in German and Spanish), a link page and two bookings -- or, with
`DEMO=space`, *Space travels* ("Starward"): 24 long-form articles with NASA photographs captioned as
the places of an invented star system, its own about, contact and advertise pages, menus, layouts
for the magazine set, two journal issues, a theme, a favicon and share picture, and five invented
advertisers with HTML5 creatives and pages of their own. It narrows the website's languages to
English, the one it is written in. To add it to an existing website:

```bash
make seed SITE=acme              # Demo Journal
make seed SITE=acme DEMO=space   # Space travels
```

It is written only into a website **without articles**, whose content language is English, and it
is rerunnable: a run that stopped halfway is finished by running it again.

**A website's standing pages** (About, Contact, Terms…) come from a seed when the website is
created. `make reseed-pages SITE=<id>` rebuilds them from a changed seed. It backs the pages up
first, only reports until `APPLY=1`, and outside dev also needs `CONFIRM=<env>`. Undo it with
`make reseed-pages-restore`.

## Verifying

| Command | What it shows | dev | stage | prod |
|---|---|---|---|---|
| `make check` | the configuration is complete and safe for the environment (run by `make up`) | ✓ | ✓ | ✓ |
| `make smoke` | every main route answers through Caddy with the expected code and redirect; fails on any mismatch | ✓ | ✓ | ✓ (website rows with `SITE=<id>`) |
| `make smoke-tls` | each host's certificate, and whether it verifies | ✓ (local CA: "not trusted") | ✓ | ✓ |
| `make e2e` | sign-up, sign-in, the portal (organisation, website, invited editor, deletion) end to end | ✓ | ✓ (needs pacod-mailpit) | — (creates accounts) |
| `make test`, `make verify`, `make it` | the code's tests: unit; unit after the brand check (`make brand-check`, a private deny-list of words kept outside the repository); integration (Docker) | any machine with a JDK | | |

**`make smoke`** probes by name, through `SMOKE_HOST` (localhost): HTTP on dev, HTTPS on stage and
prod. It checks every redirect's target as well as its code: a redirect that loses the port is
still a 302. On stage and prod, run it on the server; the direct-port rows are loopback-only. In
production there is no demo website, so name a real one:

```bash
make smoke SITE=acme                    # its host is acme.<PACOD_SITE_DOMAIN>
make smoke SITE_HOST=www.example.org    # or name the host
```

The demo-content rows (`/article/1`, `/p/about`…) run only where the demo is expected (dev, stage).

**`make e2e`** signs up a new account through pacod-id and confirms it through mailpit. It also has
the bootstrap admin sign in, restarts the studio, and signs out through pacod-id. Then an owner
creates an organisation and a website, invites an editor by mail, and deletes both. It leaves test
accounts in pacod-id. `make e2e-signin` and `make e2e-portal` run the halves.

**When something fails**:

| Symptom | Likely cause |
|---|---|
| `make up`: "no .env" | run `make init` (`ENV=…` and the hosts for stage/prod) |
| `make check` ✗ | each line names the setting and why; fix `.env` and run it again |
| `make up` waits, then gives up | `make logs-cms-studio`: the studio refuses to start on a shared registrable domain; `make logs-pacod-caddy`: a certificate cannot be obtained (DNS, ports 80/443) |
| a 502 from Caddy | an application is still starting, or stopped: `make status`, `make logs-cms-site` |
| "container name already in use" (`pacod-caddy`, `pacod-id`, ...) | another repo's standalone stack, or the full stack, runs the same shared component: stop it first |
| sign-in loops or "redirect URI mismatch" | the studio's or pacod-id's address changed (another `HTTP_PORT`, a host); recreate everything with the same values: `make up` |
| no confirmation mail | dev/stage: it is in pacod-mailpit; prod: `make logs-pacod-id`, and the relay's settings |
| a smoke row on stage/prod fails with 000 | the certificate is not there yet (first visit) or DNS does not point at the server |
| `make up` answers, then fails at "id-provisioner" | the server reaches pacod-id at its public address (`https://<id host>`) from itself; a firewall or a router without hairpin NAT can refuse that. Allow it, or add the id host to the server's `/etc/hosts` as 127.0.0.1 |
