// Creates the account the two Spring applications authenticate as.
//
// Runs only once, when the data volume is first initialised. On an existing volume MongoDB
// skips /docker-entrypoint-initdb.d entirely, so re-running compose will not clobber anything --
// which also means a volume initialised by a different version of this script keeps that
// version's account. Start from an empty data directory after changing it.
//
// One database per website. The control plane is MONGO_INITDB_DATABASE (pacod_cms); every
// website's content is in a database of its own, site_<id>, created by the studio when the
// website is. The account therefore needs rights on databases that do not exist yet, which only
// the *AnyDatabase roles give -- so it lives in `admin` and the applications authenticate there
// (spring.mongodb.authentication-database=admin).
//
//   readWriteAnyDatabase  every website's content and the control plane
//   dbAdminAnyDatabase    createIndex in a new website's database, and dropDatabase when a
//                         website is deleted
//
// Neither grants user or role administration, cluster administration or server-side scripting.
// A deployment that wants a narrower blast radius runs one MongoDB per organisation instead.

const user = process.env.MONGO_APP_USER || 'pacod-cms';
const pwd = process.env.MONGO_APP_PASSWORD;
if (!pwd) {
    // No fallback: a password published in this file would be every installation's password.
    throw new Error('MONGO_APP_PASSWORD is not set; run make init');
}

const admin = db.getSiblingDB('admin');

if (admin.getUser(user)) {
    print(`user ${user} already exists in admin, leaving it alone`);
} else {
    admin.createUser({
        user: user,
        pwd: pwd,
        roles: [
            { role: 'readWriteAnyDatabase', db: 'admin' },
            { role: 'dbAdminAnyDatabase', db: 'admin' },
        ],
    });
    print(`created user ${user} in admin with readWriteAnyDatabase and dbAdminAnyDatabase`);
}
