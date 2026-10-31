# Security

What keeps websites apart, what the studio trusts, and why. The design assumes a website's owner
may be hostile to everyone else on the installation: a website runs script its owner wrote (HTML5
banners, tag managers, article bodies the editor accepts as any markup), and the platform must
keep that script away from other websites, from the studio and from pacod-id.

## Websites are kept apart

- **One database per website.** Every database operation goes to the database of the website bound
  to the work in hand; with none bound it throws, so a forgotten binding is an error, never a write
  into somebody else's data. Background work binds each website in turn.
- **The host picks the website**, before anything else runs. A host no active website claims gets a
  plain 404 — never a default website, which is how one site's pages would get indexed under another
  name.
- **Files are served per website**, out of that website's folder only, with the resolved path checked
  to stay inside the tree the address names. Caches carry the website's id in every key, and
  clearing a cache clears that website's entries only.
- **A deleted website stays deleted**: it goes offline and releases its hosts first, loses its roles
  in pacod-id before its data, and its database is dropped again once the site's caches have let
  go, so nothing can write it back. Its id cannot be reused for a few minutes.

## The studio is on another site

**The studio and the websites must be on different registrable domains** (`studio.example.org`
against `*.example-sites.net`), judged by the Public Suffix List as browsers judge cookies:

- the studio **refuses to start** when its host or pacod-id's shares a registrable domain with
  `<id>.<site domain>`;
- a website's own address on their registrable domain is **refused** when added, and a stored one
  is reported at every start;
- a website never answers at the studio's or pacod-id's host or below them.

Register a tenant domain shared by many websites in the list's private section, so its websites
are isolated from each other as well.

`PACOD_SITE_DOMAIN_SHARED=true` lifts the first two rules (the third stays) for an installation
whose websites are all trusted, such as a demo on one domain: the studio starts with a warning, and
a website's script can then set cookies the studio and pacod-id receive. Serve the studio over
HTTPS then, so its `__Host-` cookies are the ones a sibling host cannot set.

**In production the studio's cookies are `__Host-` cookies** (`__Host-studio-session`,
`__Host-XSRF-TOKEN`): `Secure`, `Path=/`, no `Domain`, which a browser lets no other host set or
overwrite. CSRF protection is on for every state-changing request.

**The studio never serves a website's HTML, script or SVG.** Its previews of a website's pictures
are images only, with a sandboxing Content-Security-Policy. Anything that runs a website's code —
the draft preview, an HTML5 banner's preview — is framed from the website's own origin, and an
HTML5 preview loads only when clicked. The draft preview's frame is sandboxed and may never
navigate the studio's page.

## Who may do what

- **pacod-id owns accounts and roles.** Tokens carry no roles; the studio follows pacod-id's feed
  of role bindings and decides every permission in one place with the rules engine, against the
  CMS's manifest (`authz/cms.yaml`), at the platform, the organisation and the website.
- **Every studio handler declares its permission**; one that declares none is refused (a test
  asserts every handler declares one). Members are managed through pacod-id's admin API acting as
  the signed-in person, so pacod-id checks every change against that person's own roles.
- **A stale picture is refused, not trusted**: when pacod-id's feed has not answered for 60 seconds
  the studio answers 503 rather than decide with roles that may have been taken away. A session
  pacod-id ended, or an account it disabled, is signed out at the next request.
- **No role is implicit**, because an implicit role is held everywhere: one carrying a grant
  permission would grant everywhere.
- **Scripts use the studio's internal API** (`/internal/**`, today the AI gateway's address and
  key) with a pacod-id service token (`pidt_…`) as bearer. The studio checks it with pacod-id's
  introspection as its own service client, caches the answer for at most a minute by the token's
  hash (and drops the cache when pacod-id's feed reports a revoked token), and then decides what the
  account may do from its role bindings, exactly as for a person. The same 503 applies when the
  feed is old. The chain keeps no session and reads no cookie, so it has no CSRF to defend. Caddy
  does not route `/internal/` (a 404), and the studio refuses the API under a website's
  `/sites/<id>` prefix (also a 404); callers use the studio on the compose network. **The token
  check is the guard**, because the studio's own port is published as well (on every interface in
  development, loopback on stage and production).
- **Known trade-off**: setting the gateway needs `platform.manage`, which only `cms.platform_admin`
  holds, and that role holds every permission. A script's service account therefore gets the
  broadest role. A narrower role would change the manifest and is left for later.

## Signed, short-lived tokens

- **Draft previews** carry a token naming one website (and optionally a template set), valid for 30
  minutes, signed with the secret both applications share. A bad, expired or other website's token
  is a 403 — never the published page, which an editor would take for the draft. A preview counts
  nothing, loads no third-party script, and is `noindex`, `no-store` and `no-referrer`.
- **The PDF renderer** proves itself with a five-minute token of its own purpose, sent only to the
  site's internal address. The front door (Caddy) removes that header from every incoming
  request. A journal issue's print front page shows drafts and answers that token alone; to
  anyone else it is a 404.
- **Gotenberg is locked down**: its Chromium may reach the site's internal address and nothing else,
  runs no JavaScript, and publishes no port. An article body that frames an internal service
  renders a blocked frame.

## What the AI returns is untrusted

Every answer from the gateway is treated as hostile input:

- The material goes into the user message, alone; the instructions say the material is never
  instructions.
- **A translated body must have exactly its source's markup skeleton** — the same elements,
  attributes and values in order, only `alt`, `title`, `aria-label` and `placeholder` changed — or it
  is refused. What is stored is the parser's serialisation of the checked answer, never the raw
  string, so a model cannot add anything executable the body did not already have.
- **Writing help** — rewrites, drafted articles, passages, laid-out bodies — is held to a small
  allow-list of tags and of link schemes (http, https, mailto, relative); a `class` survives only
  where it names one of the body's styled blocks on its own element, never a `style`; images survive
  only where the author's own text already had them, and links only to addresses the author's text,
  brief or article gave. A laid-out body must keep every word of the author's in order, and a body
  holding a frame or script is not sent to be laid out at all. Numbers, names and pull quotes an
  answer holds that its material does not are listed beside it for the author to check. That check
  is a heuristic, not proof: the author's review is the last guard. Nothing is saved until the author
  saves.
- **Design assist** answers are validated exactly as if an operator had typed them, and only reach
  a draft, which one click restores to what it was.
- Attribution to the gateway uses opaque ids only: a website's id, a feature, a job, a person's
  pacod-id subject. No names or e-mail addresses.
- **The gateway's key is never shown back.** Set on the installation screen or through the internal
  API, it is stored in the control plane as given, not encrypted (whoever reads the control plane
  already controls the installation). Screens and answers name only its last four characters.
  **An address and its key are one pair**: a new address is refused unless its key comes in the
  same change, so a key never goes to a host it was not given for.

## Archives

- **An uploaded archive is untrusted.** Every entry is read and checked before anything is created:
  paths stay inside the website's own trees, files are of the types a website stores, collections
  are the website's own, and no document may reference another database (`$db`), carry an operator
  field name or hold code. A restore always creates a new website, through the same checks as
  creating one, and writes only into its database and folder; it never overwrites an existing one.
- **Exports leave out members and roles** (pacod-id's), the hosts, and the traffic statistics.
- Only the two restore paths accept a large request body; every other upload stays at 4 MiB.

## Public pages

- **Only published articles reach a reader**, on every route, list, feed, sitemap and PDF; only
  published journal issues are listed, and a draft issue's PDF is kept outside every public folder
  and downloaded only through the studio, as an attachment.
- **The crawler files** (`robots.txt`, `sitemap.xml`, `llms.txt`, `llms-full.txt`) list only what
  a reader may see. A website's own text for one is checked when saved (a sitemap must be
  well-formed XML without a DOCTYPE, its additions name only the website's own hosts), served as
  plain text or XML with `nosniff`, never as HTML, and shown in the studio as text. A database that
  cannot be read answers 503, never an allow-all `robots.txt`.
- **What an AI gateway writes for a PDF is text**, checked as if typed: an abstract, keywords and a
  running title, stripped of markup. The article's body is never sent back to the site changed.
- **No third-party script runs unless the website configured it**, and every third-party id is
  checked against its vendor's shape before it is rendered.
- **A website's tag manager loads only after the reader consents.** The page the server sends
  carries its container id as data and nothing from Google; the reader's browser loads the
  container once analytics or marketing is allowed in the consent banner, with Google's consent
  signals denied until then. The choice is one first-party cookie, `cookie_consent`; withdrawing
  it clears the Google cookies the page can reach and reloads. Previews, print views, the error
  page and `/social` never load it.
- **The site never sends `X-Frame-Options: DENY`** (it would blank the website's own banners), but
  says who may frame it: itself, and the studio for previews and banner folders.
- Redirects through `/b/{id}` and the link page are validated twice — when saved and before the
  `Location` is written — so a stored value cannot make an open redirect.
- A website's CSS (its theme) is served as an external stylesheet with `@import`, `expression(`,
  `javascript:` and friends neutralised.

## Certificates on demand

Caddy requests a website's certificate at the first visit to its host, but only after the site
confirms the host belongs to an active website. Nobody can make the installation ask a CA
for names it does not serve. The question is asked on the internal network; the front door
answers `/_internal/` with a 404.

## Reporting a vulnerability

Please report security issues privately to the maintainers rather than in a public issue.
