// The studio's provisioner in pacod-id: a service account holding cms.provisioner at the platform,
// and a pidt_ token for it, written into this machine's .env as PACOD_ID_PROVISIONER_TOKEN -- or
// into .env-local if that file already names it: whichever file names it gets it, since the later
// file wins and a new token beside an old one would be shadowed.
//
//   node scripts/id-provisioner.mjs <repo> <issuer> <studio origin> [rotate]
//                                                      (make id-provisioner [ROTATE=1])
//
// Why the studio needs one: the person who creates an organisation must become its org_admin, and
// nobody holds a role at an organisation that did not exist a moment ago. The studio exchanges this
// token for a short-lived admin token and makes exactly that binding (io.pacod.cms.studio.identity.
// Provisioner). The manifest lets cms.provisioner grant roles, but never platform_admin or
// provisioner itself.
//
// It signs in as the bootstrap admin (ID_ADMIN_EMAIL / ID_ADMIN_PASSWORD) through pacod-id's hosted
// pages' API and uses /ui-api/v1/admin -- the admin API as that person, on the public listener.
// Writes there need the realm's own Origin.
//
// Safe to repeat. With a token already recorded it only checks that its account still exists
// (a `make destroy` empties pacod-id's database, not .env) and replaces both if not; `rotate`
// issues a new token whatever. Every other token of the account is revoked once the new one exists. The token
// is never printed. Exit 0 and the word "created" on stdout when it wrote a new one -- the Makefile
// recreates the studio then -- else exit 0 silently.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash, randomBytes } from 'node:crypto';

const [, , REPO, ISSUER, STUDIO, MODE] = process.argv;
const ROTATE = MODE === 'rotate';
// Where the token goes: the file that names it already, else .env.
const TARGET = ['.env-local', '.env'].find((f) => {
  try { return /^PACOD_ID_PROVISIONER_TOKEN=/m.test(readFileSync(`${REPO}/${f}`, 'utf8')); } catch { return false; }
}) || '.env';
const LOCAL = `${REPO}/${TARGET}`;
const NAME = 'pacod-cms provisioner';

const env = {};
for (const f of ['.env', '.env-local']) {
  try {
    for (const l of readFileSync(`${REPO}/${f}`, 'utf8').split('\n')) {
      const m = l.match(/^([A-Z_][A-Z0-9_]*)=(.*)$/);
      if (m) env[m[1]] = m[2];
    }
  } catch {}
}
const email = env.ID_ADMIN_EMAIL || 'admin@pacod-cms.test';
const password = env.ID_ADMIN_PASSWORD;
if (!password) {
  console.error('id-provisioner: ID_ADMIN_PASSWORD is not set (make init writes it)');
  process.exit(1);
}
const fail = (what, answer) => {
  console.error(`id-provisioner: ${what}: ${JSON.stringify(answer)}`);
  process.exit(1);
};

const jar = {};
const absorb = (r) => {
  for (const c of r.headers.getSetCookie()) {
    const [pair] = c.split(';');
    const i = pair.indexOf('=');
    jar[pair.slice(0, i)] = pair.slice(i + 1);
  }
};
const cookie = () => Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');
async function call(method, path, body) {
  const r = await fetch(`${ISSUER}${path}`, {
    method,
    redirect: 'manual',
    headers: { Cookie: cookie(), Origin: ISSUER, 'Content-Type': 'application/json', Accept: 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  absorb(r);
  const text = await r.text();
  let json = {};
  try { json = text ? JSON.parse(text) : {}; } catch { json = { text }; }
  return { status: r.status, json };
}

// Sign in: an authorize request opens a flow; its password step sets the realm's session cookie.
const b64 = (b) => b.toString('base64url');
const verifier = b64(randomBytes(32));
const authorize = await fetch(`${ISSUER}/oidc/authorize?` + new URLSearchParams({
  response_type: 'code',
  client_id: 'pacod-cms-studio',
  redirect_uri: `${STUDIO}/mgmt/oauth2/code/pacod-id`,
  scope: 'openid',
  state: b64(randomBytes(8)),
  code_challenge: b64(createHash('sha256').update(verifier).digest()),
  code_challenge_method: 'S256',
}), { redirect: 'manual' });
absorb(authorize);
const flow = new URL(authorize.headers.get('location') || '', ISSUER).searchParams.get('flow');
if (!flow) fail('pacod-id opened no sign-in flow', { status: authorize.status });
const step = await call('POST', `/ui-api/v1/flows/${flow}/password`, { email, password });
if (step.json.next !== 'redirect') fail('signing in as the bootstrap admin failed', step.json);

const list = await call('GET', '/ui-api/v1/admin/service-accounts');
if (list.status !== 200) fail('listing service accounts failed', list.json);
const accounts = list.json.service_accounts || [];

if (env.PACOD_ID_PROVISIONER_TOKEN && !ROTATE) {
  const recorded = env.PACOD_ID_PROVISIONER_ACCOUNT;
  if (recorded && accounts.some((a) => a.id === recorded && a.status !== 'disabled')) {
    process.exit(0);
  }
  console.error('id-provisioner: the recorded provisioner no longer exists in pacod-id; making a new one');
}

let account = accounts.find((a) => a.name === NAME && a.status !== 'disabled');
if (!account) {
  const made = await call('POST', '/ui-api/v1/admin/service-accounts', { name: NAME });
  if (made.status !== 201) fail('creating the service account failed', made.json);
  account = made.json;
}

// The role first, and only then a token: a token minted for an account whose binding then fails
// would be a live credential nobody recorded. On a fresh stack the studio may not have pushed the
// cms catalogue yet, and pacod-id refuses cms.* roles until it has -- so the bind is retried.
let bound;
for (let i = 0; i < 30; i++) {
  bound = await call('POST', '/ui-api/v1/admin/bindings', { principal: account.id, role: 'cms.provisioner', scope: 'platform' });
  if (bound.status === 201 || bound.json?.error?.details?.code === 'binding_exists') break;
  await new Promise((r) => setTimeout(r, 2000));
}
if (bound.status !== 201 && bound.json?.error?.details?.code !== 'binding_exists') {
  fail('binding cms.provisioner failed', bound.json);
}

const token = await call('POST', `/ui-api/v1/admin/service-accounts/${account.id}/tokens`, {});
if (token.status !== 201 || !token.json.token) fail('creating its token failed', { status: token.status });

// One live token per provisioner: every other is revoked, so a rerun leaves no unrecorded
// credential behind (a running studio holding an old one is recreated by make id-provisioner).
const tokens = await call('GET', `/ui-api/v1/admin/service-accounts/${account.id}/tokens`);
for (const t of tokens.json.tokens || []) {
  if (t.id !== token.json.id && t.status !== 'revoked') {
    const gone = await call('DELETE', `/ui-api/v1/admin/service-tokens/${t.id}`);
    if (gone.status >= 300) console.error(`id-provisioner: could not revoke the old token ${t.id}: ${gone.status}`);
  }
}

let local = '';
try { local = readFileSync(LOCAL, 'utf8'); } catch {}
local = local
  .split('\n')
  .filter((l) => !/^PACOD_ID_PROVISIONER_(TOKEN|ACCOUNT)=/.test(l) && !l.startsWith("# The studio's provisioner in pacod-id"))
  .join('\n')
  .replace(/\n*$/, '\n');
local += `\n# The studio's provisioner in pacod-id, written by scripts/id-provisioner.mjs (make id-provisioner).\n`
  + `PACOD_ID_PROVISIONER_ACCOUNT=${account.id}\nPACOD_ID_PROVISIONER_TOKEN=${token.json.token}\n`;
writeFileSync(LOCAL, local, { mode: 0o600 });
console.log(`created the provisioner ${account.id}; its token is in ${TARGET}`);
