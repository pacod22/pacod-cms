// Sign-in through pacod-id, end to end, against the running stack:
//   1. a self-registered account (address confirmed through the mail catcher) holds no role:
//      it sees no website and gets the studio's 403 page on one;
//   2. the bootstrap admin signs in and opens the demo website;
//   3. the studio container restarts: its session is gone, and pacod-id's own session signs the
//      admin straight back in, with no password;
//   4. a session ended at pacod-id signs the person out of the studio, through the feed;
//   5. signing out of the studio ends pacod-id's session too.
//
//   make e2e-signin          (dev and stage: it reads the mail in mailpit; restarts the studio in step 3)
//
//   node scripts/e2e-signin.mjs <repo> <studio origin> <issuer> <mailpit url> <studio direct url>
//
// The scripted sign-in is pacod-id's own approach (pacod-id/scripts/measure.mjs): the hosted
// pages' API, /ui-api/v1/flows/{flow}/..., with the issuer as Origin.
import { readFileSync } from 'node:fs';
import { execSync } from 'node:child_process';

const [, , REPO, STUDIO, ID, MAIL, STUDIO_DIRECT] = process.argv;
if (!STUDIO_DIRECT) { console.error('usage: make e2e-signin'); process.exit(2); }
// The secrets from the machine's files (make never exports them), anything make exports over them.
const env = {};
for (const f of ['.env', '.env-local']) {
  try { for (const l of readFileSync(`${REPO}/${f}`, 'utf8').split('\n')) { const m = l.match(/^([A-Z_][A-Z0-9_]*)=(.*)$/); if (m) env[m[1]] = m[2]; } } catch {}
}
Object.assign(env, process.env);
const ADMIN = { email: env.ID_ADMIN_EMAIL || 'admin@pacod-cms.test', password: env.ID_ADMIN_PASSWORD };

const absorb = (jar, r) => { for (const c of r.headers.getSetCookie()) { const [p] = c.split(';'); const i = p.indexOf('='); const v = p.slice(i + 1); if (/max-age=0/i.test(c) || v === '') delete jar[p.slice(0, i)]; else jar[p.slice(0, i)] = v; } return jar; };
const hdr = (jar) => Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');
async function req(url, jar, opts = {}) {
  const r = await fetch(url, { redirect: 'manual', ...opts, headers: { Accept: 'text/html,application/xhtml+xml,*/*;q=0.8', ...(opts.headers || {}), Cookie: hdr(jar) } });
  absorb(jar, r);
  return r;
}
let failures = 0;
const check = (ok, what, extra = '') => { console.log(`${ok ? ' ok ' : 'FAIL'}  ${what}${extra ? '  ' + extra : ''}`); if (!ok) failures++; };

/** Starts a studio sign-in; returns the authorize response. */
async function startSignIn(studio, id, signup = false) {
  const s = await req(`${STUDIO}/mgmt/oauth2/authorization/pacod-id${signup ? '?signup=1' : ''}`, studio);
  return req(s.headers.get('location'), id);
}
async function finish(studio, to) {
  const back = await req(to, studio);
  return back.headers.get('location');
}
async function password(studio, id, email, pw) {
  const a = await startSignIn(studio, id);
  const flow = new URL(a.headers.get('location'), ID).searchParams.get('flow');
  const p = await req(`${ID}/ui-api/v1/flows/${flow}/password`, id, { method: 'POST', headers: { 'Content-Type': 'application/json', Origin: ID }, body: JSON.stringify({ email, password: pw }) });
  const j = await p.json();
  if (j.next !== 'redirect') throw new Error('password step: ' + JSON.stringify(j));
  return finish(studio, j.to);
}
const body = async (url, jar) => (await req(url, jar)).text();

// ---- 1. a self-registered account: signed in, no roles -------------------------------------
{
  const studio = {}, id = {};
  const email = `writer-${Date.now()}@example.test`;
  const a = await startSignIn(studio, id, true);
  const at = new URL(a.headers.get('location'), ID);
  check(at.pathname === '/signup', 'the create-an-account link opens pacod-id\'s sign-up page', at.pathname);
  const flow = at.searchParams.get('flow');
  const s = await req(`${ID}/ui-api/v1/flows/${flow}/signup`, id, { method: 'POST', headers: { 'Content-Type': 'application/json', Origin: ID },
    body: JSON.stringify({ email, password: 'a-long-test-password-123', display_name: 'Test Writer', locale: 'en', accept_terms: true }) });
  let step = await s.json();
  check(s.ok, 'sign-up accepted', JSON.stringify(step).slice(0, 120));
  if (step.next === 'verify_email' || step.next === 'check_email') {
    let msg = null;
    for (let i = 0; i < 30 && !msg; i++) {
      const list = await (await fetch(`${MAIL}/api/v1/messages`)).json();
      msg = (list.messages || []).find(m => (m.To || []).some(t => t.Address === email));
      if (!msg) await new Promise(r => setTimeout(r, 1000));
    }
    check(!!msg, 'a confirmation mail reached the mail catcher');
    const full = await (await fetch(`${MAIL}/api/v1/message/${msg.ID}`)).json();
    const token = (full.Text.match(/token=([A-Za-z0-9_\-.%]+)/) || [])[1];
    const c = await req(`${ID}/ui-api/v1/verification/confirm`, id, { method: 'POST', headers: { 'Content-Type': 'application/json', Origin: ID }, body: JSON.stringify({ token: decodeURIComponent(token) }) });
    check(c.ok, 'address confirmed', String(c.status));
    const r = await req(`${ID}/ui-api/v1/flows/${flow}/resume`, id, { method: 'POST', headers: { 'Content-Type': 'application/json', Origin: ID }, body: '{}' });
    step = await r.json();
  }
  check(step.next === 'redirect', 'the flow continues to the studio', step.next);
  const landed = await finish(studio, step.to);
  check(landed === `${STUDIO}/mgmt/sites`, 'lands on the website list', landed);
  const list = await body(`${STUDIO}/mgmt/sites`, studio);
  check(list.includes(email) && !list.includes('/sites/demo/'), 'the list shows no website to a person holding no role');
  const dash = await req(`${STUDIO}/sites/demo/mgmt/dashboard`, studio);
  check(dash.status === 403, 'and the demo website refuses them', String(dash.status));
  const page = await dash.text();
  check(page.includes('/mgmt/sites') && !page.includes('Whitelabel'), 'with the studio\'s own error page', `${dash.headers.get('content-type')} ${page.length} ${page.slice(0, 120).replace(/\s+/g, ' ')}`);
  const fs = await import('node:fs');
  fs.mkdirSync(`${REPO}/.artifacts/e2e`, { recursive: true });
  fs.writeFileSync(`${REPO}/.artifacts/e2e/cookies-writer.json`, JSON.stringify(studio));
}

// ---- 2. the admin: sign in, sign out ----------------------------------------------------------
const admin = { studio: {}, id: {} };
{
  const landed = await password(admin.studio, admin.id, ADMIN.email, ADMIN.password);
  check(landed === `${STUDIO}/mgmt/sites`, 'the platform admin signs in', landed);
  const list = await body(`${STUDIO}/mgmt/sites`, admin.studio);
  check(list.includes('/sites/demo/mgmt/dashboard'), 'and sees the demo website');
  const dash = await req(`${STUDIO}/sites/demo/mgmt/dashboard`, admin.studio);
  check(dash.status === 200, 'and opens it', String(dash.status));
}

// ---- 3. the studio restarts: back in through pacod-id's session, no password ------------------
{
  execSync('docker restart studio', { stdio: 'ignore' });
  for (let i = 0; i < 60; i++) {
    try { if ((await fetch(`${STUDIO_DIRECT}/mgmt-health`)).ok) break; } catch {}
    await new Promise(r => setTimeout(r, 2000));
  }
  await new Promise(r => setTimeout(r, 3000));
  const lost = await req(`${STUDIO}/sites/demo/mgmt/dashboard`, admin.studio);
  check(lost.status === 302 && lost.headers.get('location').includes('/mgmt/login'), 'the studio session went with the container', lost.headers.get('location'));
  admin.studio = {};
  const a = await startSignIn(admin.studio, admin.id);
  const to = a.headers.get('location');
  check(to.startsWith(`${STUDIO}/mgmt/oauth2/code/pacod-id?`), 'pacod-id sends straight back with a code (its own session)', to.slice(0, 80));
  const landed = await finish(admin.studio, to);
  check(landed === `${STUDIO}/mgmt/sites`, 'signed in again without a password', landed);
}

// ---- 4. a session ended at pacod-id signs the person out of the studio ------------------------
{
  const signout = await req(`${ID}/ui-api/v1/logout`, admin.id, { method: 'POST', headers: { 'Content-Type': 'application/json', Origin: ID },
    body: JSON.stringify({ client_id: 'pacod-cms-studio', post_logout_redirect_uri: `${STUDIO}/mgmt/login?loggedOut` }) });
  check(signout.ok, 'pacod-id ends the session on its own sign-out page', String(signout.status));
  let ended = null;
  for (let i = 0; i < 20 && !ended; i++) {
    const r = await req(`${STUDIO}/mgmt/sites`, admin.studio);
    if (r.status === 302 && r.headers.get('location').includes('ended')) ended = r.headers.get('location');
    else await new Promise(r => setTimeout(r, 1000));
  }
  check(!!ended, 'the studio notices through the feed and signs them out', ended || 'still signed in');
}

// ---- 5. signing out of the studio ends pacod-id's session too ---------------------------------
{
  const studio = {}, id = {};
  await password(studio, id, ADMIN.email, ADMIN.password);
  const page = await body(`${STUDIO}/mgmt/sites`, studio);
  const csrf = (page.match(/name="_csrf" content="([^"]+)"/) || [])[1];
  const out = await req(`${STUDIO}/mgmt/logout`, studio, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: `_csrf=${encodeURIComponent(csrf)}` });
  const to = out.headers.get('location') || '';
  check(out.status === 302 && to.startsWith(`${ID}/oidc/logout?`), 'sign-out goes to pacod-id\'s end-session endpoint', to.slice(0, 90));
  const end = await req(to, id);
  const back = end.headers.get('location') || '';
  check(back.startsWith(`${STUDIO}/mgmt/login?loggedOut`), 'and pacod-id sends the browser back to the studio', back);
  const after = await startSignIn({}, id);
  const flowAt = after.headers.get('location') || '';
  check(flowAt.includes('/login?flow='), 'pacod-id asks for the password again: its session is gone', flowAt.slice(0, 60));
}

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
