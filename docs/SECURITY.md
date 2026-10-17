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

## Signed, short-lived tokens

- **Draft previews** carry a token naming one website (and optionally a template set), valid for 30
  minutes, signed with the secret both applications share. A bad, expired or other website's token
  is a 403 — never the published page, which an editor would take for the draft. A preview counts
  nothing, loads no third-party script, and is `noindex`, `no-store` and `no-referrer`.
- **The PDF renderer** proves itself with a five-minute token of its own purpose, sent only to the
  site's internal address. The front door (Caddy) removes that header from every incoming
  request.
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
- **Writing help** is held to a small allow-list of tags and of link schemes (http, https, mailto,
  relative); images survive only where the selection already had them.
- **Design assist** answers are validated exactly as if an operator had typed them, and only reach
  a draft.
- Attribution to the gateway uses opaque ids only: a website's id, a feature, a job, a person's
  pacod-id subject. No names or e-mail addresses.

## Public pages

- **Only published articles reach a reader**, on every route, list, feed, sitemap and PDF.
- **No third-party script runs unless the website configured it**, and every third-party id is
  checked against its vendor's shape before it is rendered.
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
