#!/usr/bin/env node
/**
 * Driver for the pacod-cms stack.
 *
 * Zero npm dependencies on purpose: this repo has no package.json and no JavaScript
 * toolchain (see CLAUDE.md -- mgmt.js and site.js are hand-written, unbundled, and the
 * console's only vendored asset is TinyMCE). Adding node_modules just to screenshot a
 * page would be the first build step in a project that has deliberately avoided one.
 *
 * Everything here uses Node built-ins: fetch for HTTP, the global WebSocket (Node 22+)
 * to speak Chrome DevTools Protocol directly, child_process for docker/mongosh.
 *
 * Why a driver at all, for a server-rendered site with no SPA:
 *
 *   1. THE STUDIO CANNOT BE REACHED WITH ONE curl. /mgmt/** is a Spring Security form
 *      login with CSRF ENABLED, so you must GET the login page, scrape the hidden
 *      _csrf token, POST it with the credentials, and carry the JSESSIONID after that.
 *      Every studio screen -- every screen worth looking at -- is behind that. `login`
 *      and `get` below are that flow.
 *   2. A STATUS CODE HIDES THE FAILURE THAT ACTUALLY HAPPENS HERE. A redirect that
 *      drops the port is still a 302; a JSON endpoint answering with the login PAGE is
 *      still a 200. `smoke` prints Location and content-type, not just the number.
 *   3. SCREENSHOTS NEED THE SESSION. The studio renders charts and a dark/light theme.
 *      A screenshot without the cookie is a screenshot of the login form.
 *
 * Usage:  node .claude/skills/run-pacod-cms/driver.mjs <command> [args]
 * Run with no arguments for the command list. Ports and host come from the environment only
 * (HTTP_PORT, WEB_PORT, MGMT_PORT, SMOKE_HOST, STUDIO_HOST, SITE, SITE_HOST), never from .env.
 */

import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, readdirSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SKILL_DIR = dirname(fileURLToPath(import.meta.url));
const REPO = join(SKILL_DIR, '..', '..', '..');
const OUT = join(REPO, '.artifacts', 'run-skill');
const JAR = join(OUT, 'cookies.json');

const HOST = process.env.SMOKE_HOST || 'localhost';
// The same variables `make up HTTP_PORT=… WEB_PORT=…` takes, so a stack moved off a port that
// another project holds can still be driven.
const PORTS = {
  http: Number(process.env.HTTP_PORT || 8000),
  web: Number(process.env.WEB_PORT || 8080),
  mgmt: Number(process.env.MGMT_PORT || 7070),
};
// Caddy routes by host: the studio's host to the studio, every other host to the public site,
// which picks the website by it. *.localhost resolves to loopback for Node and for Chrome alike.
const SITE_ID = process.env.SITE || 'demo';
const SITE_HOST = process.env.SITE_HOST || `${SITE_ID}.localhost`;
const STUDIO = `http://${process.env.STUDIO_HOST || 'studio.localhost'}:${PORTS.http}`;
const SITE = `http://${SITE_HOST}:${PORTS.http}`;
// The same website on the site container's own port, bypassing Caddy: the traffic generator's
// address, because Caddy replaces the X-Forwarded-For a client writes (see `traffic`).
const SITE_DIRECT = `http://${SITE_HOST}:${PORTS.web}`;

// The studio's platform screens belong to no website; every other /mgmt screen is a website's,
// under /sites/<id>/. `shot /mgmt/articles` therefore means the articles of SITE.
const PLATFORM = [/^\/mgmt\/login/, /^\/mgmt\/sites/, /^\/mgmt\/organisations/, /^\/mgmt\/platform/, /^\/mgmt\/oauth2\//, /^\/mgmt\/ui-language\//, /^\/mgmt-assets\//];
function studioPath(path) {
  if (path.startsWith('/sites/') || PLATFORM.some(re => re.test(path))) return path;
  if (path === '/mgmt' || path.startsWith('/mgmt/')) return `/sites/${SITE_ID}${path}`;
  return null;
}
/** The full URL for a path: the studio's for a studio path, the website's for anything else. */
function urlFor(path) {
  const studio = studioPath(path);
  return studio ? STUDIO + studio : SITE + path;
}

/* ------------------------------------------------------------------ env + docker */

/**
 * Reads the environment's preset, then .env, then .env-local, the later winning, as the Makefile
 * does. Values are read here for the sign-in credentials; a naive parse is enough because the
 * files are plain KEY=VALUE.
 */
function machineEnv() {
  for (const name of ['.env-local', '.env']) {
    const path = join(REPO, name);
    if (!existsSync(path)) continue;
    const m = readFileSync(path, 'utf8').match(/^ENV=(\w+)$/m);
    if (m) return m[1];
  }
  return 'dev';
}
function env() {
  const merged = {};
  for (const name of [`env/${machineEnv()}.env`, '.env', '.env-local']) {
    const path = join(REPO, name);
    if (!existsSync(path)) continue;
    for (const line of readFileSync(path, 'utf8').split('\n')) {
      const m = line.match(/^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$/);
      if (m) merged[m[1]] = m[2].trim().replace(/^["']|["']$/g, '');
    }
  }
  return merged;
}

/** The compose invocation the Makefile uses: the preset, .env, and .env-local if there is one. */
function composeArgs() {
  const args = ['compose', '--env-file', `env/${machineEnv()}.env`, '--env-file', '.env'];
  if (existsSync(join(REPO, '.env-local'))) args.push('--env-file', '.env-local');
  return args;
}

function docker(args, opts = {}) {
  return execFileSync('docker', args, { cwd: REPO, encoding: 'utf8', ...opts });
}

function mongoContainerId() {
  const id = docker([...composeArgs(), 'ps', '-q', 'mongo']).trim();
  if (!id) throw new Error('mongo container is not running -- run `make up` first');
  return id;
}

/* ------------------------------------------------------------------------- cookies */

function loadJar() {
  return existsSync(JAR) ? JSON.parse(readFileSync(JAR, 'utf8')) : {};
}

function saveJar(jar) {
  mkdirSync(OUT, { recursive: true });
  writeFileSync(JAR, JSON.stringify(jar, null, 2));
}

function cookieHeader(jar) {
  return Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');
}

function absorb(jar, response) {
  for (const raw of response.headers.getSetCookie?.() ?? []) {
    const [pair] = raw.split(';');
    const i = pair.indexOf('=');
    if (i > 0) jar[pair.slice(0, i).trim()] = pair.slice(i + 1).trim();
  }
  return jar;
}

/* ---------------------------------------------------------------------------- HTTP */

// What a browser sends. It matters: the trailing */*;q=0.8 means a handler that can only write
// JSON still "satisfies" the request, so a page-not-found used to come back as a 404 of raw JSON
// under this header and as a 500 Whitelabel under a strict `Accept: text/html`. fetch's default
// Accept would have seen neither.
const BROWSER_ACCEPT =
  'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8';

async function probe(url) {
  try {
    const r = await fetch(url, { redirect: 'manual', headers: { Accept: BROWSER_ACCEPT } });
    return {
      status: r.status,
      location: r.headers.get('location') || '',
      type: (r.headers.get('content-type') || '').split(';')[0],
    };
  } catch (e) {
    return { status: 'ERR', location: e.message, type: '' };
  }
}

/**
 * Signs into the studio through pacod-id, the way a browser does and the way pacod-id's own
 * tooling scripts it (pacod-id/scripts/measure.mjs): the studio's authorization endpoint redirects
 * to pacod-id's authorize, which opens a sign-in "flow"; the password step is pacod-id's page API
 * (POST /ui-api/v1/flows/{flow}/password, with the issuer as Origin); its answer names the studio's
 * callback with the code, which the studio redeems and answers with its session.
 *
 * Two cookie jars: the studio's (saved, for later commands) and pacod-id's (this sign-in only).
 * Credentials are the bootstrap admin's, ID_ADMIN_EMAIL and ID_ADMIN_PASSWORD from .env/.env-local.
 */
async function login() {
  const cfg = env();
  const email = cfg.ID_ADMIN_EMAIL || 'admin@pacod-cms.test';
  const password = cfg.ID_ADMIN_PASSWORD;
  if (!password) throw new Error('ID_ADMIN_PASSWORD not found in .env or .env-local (make init writes it)');

  const studio = {};
  const id = {};
  const start = await fetch(`${STUDIO}/mgmt/oauth2/authorization/pacod-id`, { redirect: 'manual' });
  absorb(studio, start);
  const authorize = start.headers.get('location');
  if (start.status !== 302 || !authorize) throw new Error(`studio did not redirect to pacod-id (${start.status})`);

  const issuer = new URL(authorize).origin;
  const a = await fetch(authorize, { redirect: 'manual' });
  absorb(id, a);
  const flowAt = a.headers.get('location') || '';
  const flow = new URL(flowAt, issuer).searchParams.get('flow');
  if (!flow) throw new Error(`pacod-id opened no sign-in flow (${a.status} ${flowAt})`);

  const step = await fetch(`${issuer}/ui-api/v1/flows/${flow}/password`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: issuer, Cookie: cookieHeader(id) },
    body: JSON.stringify({ email, password }),
  });
  absorb(id, step);
  const answer = await step.json().catch(() => ({}));
  if (!step.ok || answer.next !== 'redirect') {
    throw new Error(`pacod-id refused the password step (${step.status}): ${JSON.stringify(answer)}`);
  }

  // The studio's callback, with the code: it redeems it and answers with its own session.
  const back = await fetch(answer.to, { redirect: 'manual', headers: { Cookie: cookieHeader(studio) } });
  absorb(studio, back);
  const where = back.headers.get('location') || '';
  if (back.status !== 302 || where.includes('error')) {
    throw new Error(`the studio refused the sign-in (${back.status} ${where})`);
  }
  saveJar(studio);
  return where;
}

async function authed(path) {
  const jar = loadJar();
  if (!Object.keys(jar).length) throw new Error('no session -- run `login` first');
  const res = await fetch(urlFor(path), {
    redirect: 'manual',
    headers: { Cookie: cookieHeader(jar), Accept: 'text/html,application/json' },
  });
  // An expired session answers 302 to the login page, not 401 -- the same trap readJson()
  // in mgmt.js exists for. Say so rather than returning the login form as if it were data.
  if (res.status === 302 && (res.headers.get('location') || '').includes('/mgmt/login')) {
    throw new Error('session expired -- run `login` again');
  }
  return res;
}

/* ------------------------------------------------- headless Chrome over raw CDP */

const CHROME_CANDIDATES = [
  process.env.CHROME,
  `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1223/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/usr/bin/google-chrome',
];

function findChrome() {
  // Every Playwright chromium-* revision, not just the one pinned above: that cache is
  // versioned and the next `npx playwright install chromium` bumps the number.
  const globbed = [];
  for (const cache of [`${process.env.HOME}/Library/Caches/ms-playwright`,
                       `${process.env.HOME}/.cache/ms-playwright`]) {
    if (!existsSync(cache)) continue;
    for (const dir of readdirSync(cache).filter(d => d.startsWith('chromium-'))) {
      globbed.push(`${cache}/${dir}/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`);
      globbed.push(`${cache}/${dir}/chrome-mac/Chromium.app/Contents/MacOS/Chromium`);
      globbed.push(`${cache}/${dir}/chrome-linux/chrome`);
    }
  }
  for (const p of [...CHROME_CANDIDATES, ...globbed]) {
    if (p && existsSync(p)) return p;
  }
  throw new Error(
    'no Chromium found. Install one with:  npx -y playwright install chromium\n' +
    'or point CHROME=/path/to/chrome at an existing binary.');
}

/** Minimal CDP client: one WebSocket, id-matched responses. */
async function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((ok, no) => { ws.onopen = ok; ws.onerror = () => no(new Error('CDP connect failed')); });
  let id = 0;
  const waiting = new Map();
  ws.onmessage = (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id && waiting.has(msg.id)) {
      const { ok, no } = waiting.get(msg.id);
      waiting.delete(msg.id);
      msg.error ? no(new Error(msg.error.message)) : ok(msg.result);
    }
  };
  return {
    send: (method, params = {}) => new Promise((ok, no) => {
      const n = ++id;
      waiting.set(n, { ok, no });
      ws.send(JSON.stringify({ id: n, method, params }));
    }),
    close: () => ws.close(),
  };
}

/**
 * Screenshots a path, carrying the console session if one exists.
 *
 * Full-page, because the interesting console screens (the monitoring charts, the article
 * editor) are taller than a viewport and a 720px crop would cut off the thing you came
 * to look at.
 */
async function shot(path, outFile, { theme = 'light', width = 1440, height = 900 } = {}) {
  const chrome = findChrome();
  const profile = join(tmpdir(), `pp-shot-${process.pid}`);
  const port = 9222 + (process.pid % 500);

  const proc = spawn(chrome, [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    `--window-size=${width},${height}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars',
    'about:blank',
  ], { stdio: 'ignore' });

  try {
    let target;
    for (let i = 0; i < 60; i++) {
      try {
        const v = await fetch(`http://127.0.0.1:${port}/json/version`).then(r => r.json());
        target = v.webSocketDebuggerUrl;
        break;
      } catch { await new Promise(r => setTimeout(r, 250)); }
    }
    if (!target) throw new Error('Chrome did not expose a debugging port');

    const browser = await cdp(target);
    const { targetId } = await browser.send('Target.createTarget', { url: 'about:blank' });
    // One socket per target rather than flat sessions multiplexed over the browser socket:
    // /json/list hands out a per-page WebSocket URL, so the tiny cdp() client above needs no
    // sessionId routing at all.
    browser.close();
    const list = await fetch(`http://127.0.0.1:${port}/json/list`).then(r => r.json());
    const pageTarget = list.find(t => t.id === targetId) || list.find(t => t.type === 'page');
    const c = await cdp(pageTarget.webSocketDebuggerUrl);

    await c.send('Page.enable');
    await c.send('Emulation.setEmulatedMedia', {
      features: [{ name: 'prefers-color-scheme', value: theme }],
    });

    // The media query alone is not the theme on either half of this stack, and getting
    // that wrong fails silently -- a `dark` screenshot that came out light looks like a
    // screenshot. The console falls through to prefers-color-scheme only while
    // `pacod-mgmt-theme` is UNSET, and the public site never consults it at all: its
    // fragments/head.html snippet tests localStorage['pacod-theme'] === 'night' and nothing
    // else. So both keys are written before the document's own scripts run -- which is
    // what addScriptToEvaluateOnNewDocument buys over an evaluate after navigate, since
    // both snippets are inline in <head> and run before any of that would land.
    await c.send('Page.addScriptToEvaluateOnNewDocument', {
      source: `try{localStorage.setItem('pacod-theme',${JSON.stringify(theme === 'dark' ? 'night' : 'day')});`
            + `localStorage.setItem('pacod-mgmt-theme',${JSON.stringify(theme)});}catch(e){}`,
    });

    const jar = loadJar();
    if (Object.keys(jar).length) {
      await c.send('Network.enable');
      // `url`, not `domain`/`path`: SMOKE_HOST is an IP address whenever the loopback
      // workaround below is in play, and a cookie set by IP domain is not one Chrome
      // agrees to send back. A silent miss here is a screenshot of the login form.
      await c.send('Network.setCookies', {
        cookies: Object.entries(jar).map(([name, value]) => ({ name, value, url: STUDIO })),
      });
    }

    await c.send('Page.navigate', { url: urlFor(path) });
    // No Page.loadEventFired race: this site is full page-loads with no client routing,
    // so a fixed settle beats waiting on an event that may already have fired.
    await new Promise(r => setTimeout(r, 2500));

    const { data } = await c.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
    mkdirSync(dirname(outFile), { recursive: true });
    writeFileSync(outFile, Buffer.from(data, 'base64'));
    c.close();
    return outFile;
  } finally {
    proc.kill('SIGKILL');
    rmSync(profile, { recursive: true, force: true });
  }
}

/* ------------------------------------------------------------------------ commands */

const commands = {
  async smoke() {
    const S = SITE, T = STUDIO;
    const rows = [
      ['site   / (landing)', `${S}/`, 200],
      ['site   /latest', `${S}/latest`, 200],
      ['site   /list/2', `${S}/list/2`, 200],
      ['site   /p/about', `${S}/p/about`, 200],
      // The legacy .html aliases are gone; their files are not served from anywhere either.
      ['site   /about.html (gone)', `${S}/about.html`, 404],
      ['site   /p/nonexistent', `${S}/p/nonexistent`, 404, 'text/html'],
      ['site   /article/999999', `${S}/article/999999`, 404, 'text/html'],
      ['site   /web/v1/health', `${S}/web/v1/health`, 200],
      ['site   /site-assets/site.css', `${S}/site-assets/site.css`, 200],
      // core's old /v1 API answers nowhere; the studio answers nothing on a website's host.
      ['site   /v1/health', `${S}/v1/health`, 404],
      ['site   /mgmt/login', `${S}/mgmt/login`, 404],
      ['host no website claims', `http://nobody.localhost:${PORTS.http}/`, 404],
      ['studio /', `${T}/`, 302],
      ['studio /mgmt/login', `${T}/mgmt/login`, 200],
      ['studio /mgmt/sites', `${T}/mgmt/sites`, 302],
      ['studio /mgmt/articles (no website)', `${T}/mgmt/articles`, 302],
      [`studio /sites/${SITE_ID}/mgmt/monitoring`, `${T}/sites/${SITE_ID}/mgmt/monitoring`, 302],
      [`studio /sites/${SITE_ID}/mgmt/monitoring/live`, `${T}/sites/${SITE_ID}/mgmt/monitoring/live`, 302],
      ['studio /sites/nobody/mgmt/articles', `${T}/sites/nobody/mgmt/articles`, 404],
      ['studio /mgmt-assets/mgmt.css', `${T}/mgmt-assets/mgmt.css`, 200],
      ['studio /mgmt-assets/tinymce/tinymce.min.js', `${T}/mgmt-assets/tinymce/tinymce.min.js`, 200],
      // The content type is asserted, not just the status: a path that resolved to something
      // else would still be 200.
      ['studio /mgmt-assets/favicon.svg', `${T}/mgmt-assets/favicon.svg`, 200, 'image/svg+xml'],
      ['studio /assets/img/articles/x.jpg', `${T}/assets/img/articles/x.jpg`, 302],
      // Sign-in starts at the studio and continues on pacod-id, which answers only on its host.
      ['studio /mgmt/oauth2/authorization/pacod-id', `${T}/mgmt/oauth2/authorization/pacod-id`, 302],
      ['pacod-id discovery', `http://${process.env.ID_HOST || 'id.localhost'}:${PORTS.http}/.well-known/openid-configuration`, 200],
      ['direct site /web/v1/health', `http://${HOST}:${PORTS.web}/web/v1/health`, 200],
      ['direct site /v1/health', `http://${HOST}:${PORTS.web}/v1/health`, 404],
      ['direct studio /mgmt-health', `http://${HOST}:${PORTS.mgmt}/mgmt-health`, 200],
    ];

    let bad = 0;
    for (const [label, url, want, wantType] of rows) {
      const r = await probe(url);
      // A fourth column where the status alone cannot see the failure. /p/nonexistent answered
      // 404 both before and after the error page existed -- with a body of raw JSON before it.
      const ok = r.status === want && (!wantType || r.type === wantType);
      if (!ok) bad++;
      // The Location matters as much as the code: a redirect that drops :8000 is still a
      // 302, and it sends the browser to a port nothing listens on.
      const extra = r.location ? `  -> ${r.location}` : r.type ? `  [${r.type}]` : '';
      const wanted = wantType ? `${want} ${wantType}` : want;
      console.log(`${ok ? ' ok ' : 'FAIL'}  ${label.padEnd(42)} ${r.status} (want ${wanted})${extra}`);
    }
    console.log(bad ? `\n${bad} check(s) failed` : '\nall checks passed');
    if (bad) process.exitCode = 1;
  },

  async login() {
    console.log('logged in, redirected to ' + await login());
    console.log('session saved to ' + JAR);
  },

  async get(path = '/mgmt/monitoring') {
    const res = await authed(path);
    const body = await res.text();
    console.log(`${res.status} ${res.headers.get('content-type')}  ${body.length} bytes`);
    console.log(body.length < 2000 ? body : body.slice(0, 2000) + '\n... [truncated]');
  },

  async shot(path = '/', out, theme = 'light') {
    const file = out || join(OUT, (path.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') || 'root') + `-${theme}.png`);
    console.log('wrote ' + await shot(path, file, { theme }));
  },

  /**
   * Drives the monitoring feature: N distinct synthetic readers hitting the public pages.
   *
   * X-Forwarded-For is what VisitorIdentity hashes. Through Caddy every request would be one
   * reader: Caddy replaces the value a client writes with the address it saw, which is the point
   * of it. So the readers go to the site container's own port (WEB_PORT), where nothing stands in
   * between and the value supplied here is the first entry. 203.0.113.0/24 is the RFC 5737
   * documentation range, so it cannot collide with a real visitor.
   */
  async traffic(n = '5') {
    const readers = Number(n);
    let sent = 0;
    for (let i = 1; i <= readers; i++) {
      for (const path of ['/', '/list/1', '/latest']) {
        await fetch(SITE_DIRECT + path, {
          headers: { 'X-Forwarded-For': `203.0.113.${i}`, 'User-Agent': `run-skill/${i}` },
        });
        sent++;
      }
    }
    console.log(`${sent} page views from ${readers} distinct readers`);
    console.log('the collector buffers -- wait for app.metrics.flush.ms (60s) before reading buckets');
  },

  /** mongosh inside the container, with the credentials read from .env. */
  async mongo(...js) {
    const script = js.join(' ') || 'db.getCollectionNames()';
    // As the root account the mongo container was given; SITE names the website's database.
    const out = docker([
      'exec', mongoContainerId(), 'sh', '-c',
      'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin "$1" --eval "$2"',
      '--', `site_${SITE_ID}`, script,
    ]);
    console.log(out.trim());
  },

  async ps() {
    console.log(docker([...composeArgs(), 'ps', '--format', '{{.Service}}\t{{.Status}}']).trim());
  },

  async logs(service = 'site', lines = '80') {
    console.log(docker([...composeArgs(), 'logs', '--tail', String(lines), service]));
  },
};

const [, , cmd, ...args] = process.argv;

if (!cmd || !commands[cmd]) {
  console.log(`pacod-cms driver

  smoke                       every route through Caddy + the direct ports,
                              printing Location and content-type, not just the code
  login                       studio form login (scrapes _csrf), saves the session
  get <path>                  authenticated GET of a studio page (/mgmt/x means SITE's)
  shot <path> [out] [theme]   full-page screenshot; theme = light|dark. /mgmt/... paths
                              are the studio's (SITE's screens), anything else the website's
  traffic [readers]           synthetic public-site traffic from N distinct readers
  mongo <js>                  mongosh on SITE's database, inside the container
  ps                          container states
  logs <service> [lines]      container logs

Artifacts land in .artifacts/run-skill/ (screenshots, session jar).
Ports: HTTP_PORT, WEB_PORT, MGMT_PORT and SMOKE_HOST; hosts: STUDIO_HOST (studio.localhost),
SITE (demo) and SITE_HOST (<SITE>.localhost) -- all from the environment.
Requires the stack to be up: make up`);
  process.exit(cmd ? 1 : 0);
}

commands[cmd](...args).catch((e) => {
  console.error('error: ' + e.message);
  process.exit(1);
});
