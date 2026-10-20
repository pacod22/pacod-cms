// The portal, end to end, against the running stack:
//   1. someone signs up through pacod-id, holds no role, and creates an organisation -- becoming
//      its admin through the studio's provisioner;
//   2. they create a website in it, which answers at <id>.<site domain> and opens in the studio;
//   3. they invite a second person as editor of that website; the invitation arrives by mail, the
//      invitee makes an account with it, signs in, opens the website -- and is refused the demo
//      website, the website's settings and the organisation;
//   4. the platform admin sees the organisation on the installation screen and turns AI on for it;
//   5. the owner deletes the website: it stops answering, its database goes; then the
//      organisation.
//
//   make e2e-portal          (dev and stage: it reads the mail in mailpit; needs the provisioner,
//                             which make up sets up)
//
//   node scripts/e2e-portal.mjs <repo> <studio origin> <issuer> <mailpit url> <site domain> <probe url>
//
// The probe url is where a website's own pages are asked for, with its Host: Caddy over plain HTTP
// on dev, the site's loopback port on stage -- where Caddy redirects plain HTTP, and every new
// e2e-<stamp> host would otherwise cost a certificate and need wildcard DNS.
//
// The scripted sign-ins use pacod-id's hosted pages' API, as scripts/e2e-signin.mjs does.
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { request } from 'node:http';

const [, , REPO, STUDIO, ID, MAIL, DOMAIN, PROBE] = process.argv;
if (!PROBE) { console.error('usage: make e2e-portal'); process.exit(2); }
const env = {};
for (const f of ['.env', '.env-local']) {
  try {
    for (const l of readFileSync(`${REPO}/${f}`, 'utf8').split('\n')) {
      const m = l.match(/^([A-Z_][A-Z0-9_]*)=(.*)$/);
      if (m) env[m[1]] = m[2];
    }
  } catch {}
}
Object.assign(env, process.env);
const ADMIN = { email: env.ID_ADMIN_EMAIL || 'admin@pacod-cms.test', password: env.ID_ADMIN_PASSWORD };
const STAMP = Date.now().toString(36);
const SITE = `e2e-${STAMP}`;

const absorb = (jar, r) => {
  for (const c of r.headers.getSetCookie()) {
    const [p] = c.split(';');
    const i = p.indexOf('=');
    const v = p.slice(i + 1);
    if (/max-age=0/i.test(c) || v === '') delete jar[p.slice(0, i)]; else jar[p.slice(0, i)] = v;
  }
  return jar;
};
const hdr = (jar) => Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');
async function req(url, jar, opts = {}) {
  const r = await fetch(url, { redirect: 'manual', ...opts,
    headers: { Accept: 'text/html,application/xhtml+xml,*/*;q=0.8', ...(opts.headers || {}), Cookie: hdr(jar) } });
  absorb(jar, r);
  return r;
}
const json = (url, jar, body) => req(url, jar, { method: 'POST', headers: { 'Content-Type': 'application/json', Origin: ID },
  body: JSON.stringify(body) });
let failures = 0;
const check = (ok, what, extra = '') => {
  console.log(`${ok ? ' ok ' : 'FAIL'}  ${what}${extra ? '  ' + extra : ''}`);
  if (!ok) failures++;
  return ok;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function startSignIn(studio, id, signup = false) {
  const s = await req(`${STUDIO}/mgmt/oauth2/authorization/pacod-id${signup ? '?signup=1' : ''}`, studio);
  return req(s.headers.get('location'), id);
}
async function finish(studio, to) {
  return (await req(to, studio)).headers.get('location');
}
async function signIn(studio, id, email, password) {
  const a = await startSignIn(studio, id);
  const flow = new URL(a.headers.get('location'), ID).searchParams.get('flow');
  const p = await json(`${ID}/ui-api/v1/flows/${flow}/password`, id, { email, password });
  const j = await p.json();
  if (j.next !== 'redirect') throw new Error('password step: ' + JSON.stringify(j));
  return finish(studio, j.to);
}
async function mailTo(address, pattern) {
  for (let i = 0; i < 30; i++) {
    const list = await (await fetch(`${MAIL}/api/v1/messages`)).json();
    const msg = (list.messages || []).find((m) => (m.To || []).some((t) => t.Address === address));
    if (msg) {
      const full = await (await fetch(`${MAIL}/api/v1/message/${msg.ID}`)).json();
      const found = (full.Text.match(pattern) || [])[1];
      if (found) return decodeURIComponent(found);
    }
    await sleep(1000);
  }
  return null;
}
/** A studio page's CSRF token (the meta tag), for a form post. */
async function page(url, jar) {
  const r = await req(url, jar);
  const html = await r.text();
  return { status: r.status, html, csrf: (html.match(/name="_csrf" content="([^"]+)"/) || [])[1] };
}
async function post(url, jar, csrf, fields) {
  return req(url, jar, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ _csrf: csrf, ...fields }) });
}
/**
 * GET on a website's own host, at the probe address. node:http, not fetch: fetch drops a Host
 * header, and localhost is one of the demo website's hosts -- every probe would answer.
 */
function site(host, path) {
  const probe = new URL(PROBE);
  return new Promise((resolve, reject) => {
    request({ host: probe.hostname, port: probe.port, path, headers: { Host: host } }, (r) => {
      r.resume();
      r.on('end', () => resolve({ status: r.statusCode }));
    }).on('error', reject).end();
  });
}
// As the root account the cms-mongo container was given: no password passes through here.
function databases() {
  const out = execFileSync('docker', ['exec', 'cms-mongo', 'sh', '-c',
    'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin --eval "$1"', '--',
    'db.adminCommand({listDatabases:1,nameOnly:true}).databases.map(d=>d.name).join(",")'],
    { encoding: 'utf8' });
  return out.trim().split(',');
}

// ---- 1. a new person creates an organisation ---------------------------------------------------
const owner = { studio: {}, id: {}, email: `owner-${STAMP}@example.test` };
{
  const a = await startSignIn(owner.studio, owner.id, true);
  const flow = new URL(a.headers.get('location'), ID).searchParams.get('flow');
  const s = await json(`${ID}/ui-api/v1/flows/${flow}/signup`, owner.id, { email: owner.email,
    password: 'a-long-test-password-123', display_name: 'Olivia Owner', locale: 'en', accept_terms: true });
  let step = await s.json();
  if (step.next === 'verify_email' || step.next === 'check_email') {
    const token = await mailTo(owner.email, /token=([A-Za-z0-9_\-.%]+)/);
    await json(`${ID}/ui-api/v1/verification/confirm`, owner.id, { token });
    step = await (await json(`${ID}/ui-api/v1/flows/${flow}/resume`, owner.id, {})).json();
  }
  const landed = await finish(owner.studio, step.to);
  check(landed === `${STUDIO}/mgmt/sites`, 'a new account signs up and lands on the website list', landed);
  const list = await page(`${STUDIO}/mgmt/sites`, owner.studio);
  check(!list.html.includes('/sites/demo/') && list.html.includes('/mgmt/organisations'),
    'holding no role, it sees no website and is offered a new organisation');

  const orgs = await page(`${STUDIO}/mgmt/organisations`, owner.studio);
  check(orgs.html.includes('name="name"'), 'the organisations screen offers the create form');
  const made = await post(`${STUDIO}/mgmt/organisations`, owner.studio, orgs.csrf, { name: `E2E Media ${STAMP}` });
  owner.org = (made.headers.get('location') || '').match(/organisations\/(org_[0-9a-f]{32})/)?.[1];
  check(made.status === 302 && !!owner.org, 'creating an organisation lands on it', made.headers.get('location'));
  const org = await page(`${STUDIO}/mgmt/organisations/${owner.org}`, owner.studio);
  check(org.status === 200, 'its creator opens it: org_admin came through the provisioner', String(org.status));
  check(org.html.includes('Olivia Owner') && org.html.includes('Organisation admin'),
    'and is listed as its organisation admin');
}

// ---- 2. a website in it -------------------------------------------------------------------------
{
  const form = await page(`${STUDIO}/mgmt/sites/new?org=${owner.org}`, owner.studio);
  check(form.html.includes(`value="${owner.org}"`), 'the new-website form offers the organisation');
  const made = await post(`${STUDIO}/mgmt/sites/new`, owner.studio, form.csrf, {
    organisationId: owner.org, id: SITE, name: `E2E Gazette ${STAMP}`, language: 'en', timezone: 'Europe/Berlin', hosts: '' });
  check(made.status === 302 && made.headers.get('location') === `${STUDIO}/sites/${SITE}/mgmt/dashboard`,
    'creating a website lands on its dashboard', `${made.status} ${made.headers.get('location')}`);
  const dash = await req(`${STUDIO}/sites/${SITE}/mgmt/dashboard`, owner.studio);
  check(dash.status === 200, 'which the owner opens: organisation roles reach its websites', String(dash.status));
  let answered = 0;
  for (let i = 0; i < 15 && answered !== 200; i++) {
    answered = (await site(`${SITE}.${DOMAIN}`, '/')).status;
    if (answered !== 200) await sleep(1000);
  }
  check(answered === 200, `the website answers at ${SITE}.${DOMAIN}`, String(answered));
  const stranger = (await site(`nobody-${STAMP}.${DOMAIN}`, '/')).status;
  check(stranger === 404, 'while a host no website claims does not', String(stranger));
  check(databases().includes(`site_${SITE}`), `its database site_${SITE} exists`);
  const settings = await page(`${STUDIO}/sites/${SITE}/mgmt/settings`, owner.studio);
  check(settings.html.includes(`${SITE}.${DOMAIN}`) && settings.html.includes('/settings/delete'),
    'its settings list the address and offer deletion to the organisation admin');
}

// ---- 3. an editor, invited ----------------------------------------------------------------------
const editor = { studio: {}, id: {}, email: `editor-${STAMP}@example.test` };
{
  const members = await page(`${STUDIO}/sites/${SITE}/mgmt/members`, owner.studio);
  check(members.html.includes('value="cms.editor"') && !members.html.includes('value="cms.platform_admin"'),
    'the members card offers website roles, never platform ones');
  const sent = await post(`${STUDIO}/sites/${SITE}/mgmt/members/invite`, owner.studio, members.csrf,
    { email: editor.email, role: 'cms.editor' });
  check(sent.status === 302, 'the owner invites an editor', String(sent.status));
  const after = await page(`${STUDIO}/sites/${SITE}/mgmt/members`, owner.studio);
  check(after.html.includes(editor.email), 'the invitation is listed as open');

  const token = await mailTo(editor.email, /invite\?token=([A-Za-z0-9_\-.%]+)/);
  check(!!token, 'the invitation reaches the mail catcher');
  const look = await (await json(`${ID}/ui-api/v1/invitations/lookup`, editor.id, { token })).json();
  check(look.role === 'cms.editor' && look.account_exists === false, 'pacod-id shows it: editor, no account yet', look.role);
  const accepted = await (await json(`${ID}/ui-api/v1/invitations/accept`, editor.id,
    { token, password: 'another-long-test-password-456', display_name: 'Eddie Editor' })).json();
  check(accepted.next === 'sign_in', 'accepting makes the account', JSON.stringify(accepted).slice(0, 100));

  const landed = await signIn(editor.studio, editor.id, editor.email, 'another-long-test-password-456');
  check(landed === `${STUDIO}/mgmt/sites`, 'the editor signs in', landed);
  let list = '';
  for (let i = 0; i < 10 && !list.includes(`/sites/${SITE}/`); i++) {
    list = (await page(`${STUDIO}/mgmt/sites`, editor.studio)).html;
    if (!list.includes(`/sites/${SITE}/`)) await sleep(1000);
  }
  check(list.includes(`/sites/${SITE}/`) && !list.includes('/sites/demo/'), 'and sees that website, and no other');
  const articles = await req(`${STUDIO}/sites/${SITE}/mgmt/articles`, editor.studio);
  check(articles.status === 200, 'opens its articles', String(articles.status));
  const demo = await req(`${STUDIO}/sites/demo/mgmt/articles`, editor.studio);
  check(demo.status === 403, 'is refused the demo website', String(demo.status));
  const settings = await req(`${STUDIO}/sites/${SITE}/mgmt/settings`, editor.studio);
  check(settings.status === 403, 'is refused the website\'s settings (website.manage)', String(settings.status));
  const org = await req(`${STUDIO}/mgmt/organisations/${owner.org}`, editor.studio);
  check(org.status === 403, 'and the organisation', String(org.status));
  const platform = await req(`${STUDIO}/mgmt/platform`, editor.studio);
  check(platform.status === 403, 'and the installation screen', String(platform.status));

  const roster = await page(`${STUDIO}/sites/${SITE}/mgmt/members`, owner.studio);
  check(roster.html.includes('Eddie Editor'), 'the owner sees the editor among the members');
}

// ---- 4. the platform admin ----------------------------------------------------------------------
{
  const admin = { studio: {}, id: {} };
  await signIn(admin.studio, admin.id, ADMIN.email, ADMIN.password);
  const platform = await page(`${STUDIO}/mgmt/platform`, admin.studio);
  check(platform.status === 200 && platform.html.includes(owner.org), 'the platform admin sees the new organisation', String(platform.status));
  const on = await post(`${STUDIO}/mgmt/platform/organisations/${owner.org}/ai`, admin.studio, platform.csrf, { enabled: 'true' });
  check(on.status === 302, 'and turns AI on for it', String(on.status));
  const org = await page(`${STUDIO}/mgmt/organisations/${owner.org}`, admin.studio);
  check(/AI features<\/dt>\s*<dd>on<\/dd>/.test(org.html), 'the organisation now says AI is on');
}

// ---- 5. deleting ---------------------------------------------------------------------------------
{
  const settings = await page(`${STUDIO}/sites/${SITE}/mgmt/settings`, owner.studio);
  const wrong = await post(`${STUDIO}/sites/${SITE}/mgmt/settings/delete`, owner.studio, settings.csrf, { confirmation: 'nope' });
  check(wrong.headers.get('location') === `${STUDIO}/sites/${SITE}/mgmt/settings`, 'a wrong confirmation deletes nothing', wrong.headers.get('location'));
  const gone = await post(`${STUDIO}/sites/${SITE}/mgmt/settings/delete`, owner.studio, settings.csrf, { confirmation: SITE });
  check(gone.headers.get('location') === `${STUDIO}/mgmt/organisations/${owner.org}`, 'deleting the website lands on the organisation', gone.headers.get('location'));
  check(!databases().includes(`site_${SITE}`), 'its database is gone');
  const studio = await req(`${STUDIO}/sites/${SITE}/mgmt/dashboard`, owner.studio);
  check(studio.status === 404, 'the studio no longer knows it', String(studio.status));
  let answered = 200;
  for (let i = 0; i < 75 && answered !== 404; i++) {
    answered = (await site(`${SITE}.${DOMAIN}`, '/')).status;
    if (answered !== 404) await sleep(1000);
  }
  check(answered === 404, 'and its host stops answering once the site\'s cache lets go (60 s)', String(answered));
  check(!databases().includes(`site_${SITE}`), 'nothing wrote its database back meanwhile');
  const editorList = (await page(`${STUDIO}/mgmt/sites`, editor.studio)).html;
  check(!editorList.includes(`/sites/${SITE}/`), 'the editor\'s list no longer shows it');

  const org = await page(`${STUDIO}/mgmt/organisations/${owner.org}`, owner.studio);
  const deleted = await post(`${STUDIO}/mgmt/organisations/${owner.org}/delete`, owner.studio, org.csrf, {});
  check(deleted.headers.get('location') === `${STUDIO}/mgmt/organisations`, 'the empty organisation is deleted', deleted.headers.get('location'));
  const after = await req(`${STUDIO}/mgmt/organisations/${owner.org}`, owner.studio);
  check(after.status === 403 || after.status === 404, 'and opens no more', String(after.status));
}

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
