# Running pacod-cms

One Makefile runs pacod-cms in three environments: **dev** on a developer machine, **stage** on a
server where testers can try anything, and **prod**. This guide covers starting each one the first
time, giving it content, keeping it running and checking that it works. `make` on its own lists
every target, grouped. [`DEPLOYMENT.md`](DEPLOYMENT.md) is the reference behind it: services,
hosts, certificates, sign-in, every setting.

## The three environments

| | dev | stage | prod |
|---|---|---|---|
| Runs on | a developer machine | a server with domains of its own | a server |
| Spring profile | `dev` (templates re-read) | `prod` | `prod` |
| Front door (Caddy) | HTTP on 8000; HTTPS on 8443 from Caddy's local CA | HTTPS on 443 (Let's Encrypt), HTTP redirected | the same as stage |
| Hosts | `*.localhost`, nothing to set up | yours, in DNS | yours, in DNS |
| pacod-id's mail | mailpit, http://localhost:8125/ | mailpit (through an SSH tunnel) | your relay (`PID_SMTP_*`) |
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
`ID_ADMIN_PASSWORD` from `.env`. This is the bootstrap platform admin. The first start creates two
demo websites with the demo content:

- **"Demo Journal"** (editorial template set) at http://demo.localhost:8000/ and http://localhost:8000/
- **"Demo Notes"** (minimal template set) at http://minimal.localhost:8000/

pacod-id's sign-in pages are at http://id.localhost:8000/, and every mail it sends lands in mailpit,
http://localhost:8125/. **Create an account** on the sign-in page makes an account with no role,
which sees no website.

Every day:

| | |
|---|---|
| `make restart` | rebuild and recreate `site` and `studio` after a code change. MongoDB, pacod-id and Caddy keep running |
| `make logs`, `make logs-studio`, `make logs-site`, `make logs-id`, `make logs-caddy` | follow logs |
| `make shell-studio`, `make shell-site` | a shell in a container |
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
  - `demo.<site domain>` and `minimal.<site domain>`, for the demo websites;
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

### Backups

Keep copies of five things:

- `.env`;
- the MongoDB databases;
- the websites' files (`STORAGE_HOST`);
- pacod-id's PostgreSQL;
- optionally the `caddy-data` volume, to avoid re-issuing certificates.

For example, nightly (both dump commands checked against a running stack):

```bash
docker exec mongo sh -c 'mongodump -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" \
  --authenticationDatabase admin --out /data/backup/$(date +%F)'      # lands in MONGO_BACKUP_DIR
docker exec id-postgres pg_dumpall -U postgres > /data/backup/pacod-id-$(date +%F).sql
tar -czf /data/backup/storage-$(date +%F).tgz -C /data storage
```

`make restore DUMP=<folder under MONGO_BACKUP_DIR>` puts a MongoDB dump back. It only reports what
it would do until you add `APPLY=1`, and `CONFIRM=prod` too in production.

## What the first start does

`make up` runs these steps in order, each safe to repeat:

1. **`make check`**: is `.env` complete for this environment (above)?
2. **The Docker network and the images**: pacod-id's, and the mock gateway's on dev and stage. Each
   is built from its sources only if missing. Then `site` and `studio` are built from this checkout.
3. **The containers start.** MongoDB creates its two accounts on its first start. pacod-id creates
   its database and the bootstrap admin (`ID_ADMIN_EMAIL` / `ID_ADMIN_PASSWORD`), once.
4. **The studio bootstraps the platform**, at every start:
   - it builds the control plane's indexes;
   - it re-provisions every website that is out of date;
   - on an empty platform with demo websites on (dev, stage), it creates `demo` and `minimal` with
     the demo content.

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
- `DEMO=1` installs the demo content.
- Its hosts must resolve to the server, except on dev.

**The demo content** is *Demo Journal*: six sections, four authors, twenty published articles with
generated pictures (five also in German and Spanish), a link page and two bookings. To add it to an
existing website:

```bash
make seed SITE=acme
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
| `make e2e` | sign-up, sign-in, the portal (organisation, website, invited editor, deletion) end to end | ✓ | ✓ (needs mailpit) | — (creates accounts) |
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
| `make up` waits, then gives up | `make logs-studio`: the studio refuses to start on a shared registrable domain; `make logs-caddy`: a certificate cannot be obtained (DNS, ports 80/443) |
| a 502 from Caddy | an application is still starting, or stopped: `make status`, `make logs-site` |
| sign-in loops or "redirect URI mismatch" | the studio's or pacod-id's address changed (another `HTTP_PORT`, a host); recreate everything with the same values: `make up` |
| no confirmation mail | dev/stage: it is in mailpit; prod: `make logs-id`, and the relay's settings |
| a smoke row on stage/prod fails with 000 | the certificate is not there yet (first visit) or DNS does not point at the server |
| `make up` answers, then fails at "id-provisioner" | the server reaches pacod-id at its public address (`https://<id host>`) from itself; a firewall or a router without hairpin NAT can refuse that. Allow it, or add the id host to the server's `/etc/hosts` as 127.0.0.1 |
