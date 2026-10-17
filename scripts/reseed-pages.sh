#!/usr/bin/env bash
#
# Rebuilds one website's standing pages from the seed, pacod-cms-core/src/main/resources/seed/pages.json.
#
# Use it after editing that file. Editing it alone changes nothing on an existing website:
# PageBootstrap seeds ONLY while the website's `page` collection is empty, and it runs when the
# website is provisioned, not on every start. This is what makes it run again.
#
#   SITE=demo ./scripts/reseed-pages.sh           # report what is there and what re-seeding would cost
#   SITE=demo ./scripts/reseed-pages.sh --apply   # back up, drop, rebuild, re-provision, verify
#
# Dry run by default: this drops a collection an operator edits through the studio's Pages
# screen, and the studio is the only record of what was in it.
#
#
# WHY IT DROPS THE WHOLE COLLECTION RATHER THAN REPLACING THE DOCUMENTS
#
# Because PageBootstrap.toPage() is the one owner of the seed-JSON -> Page mapping, and it must
# stay that way. Writing the documents from here instead would put a second copy of that mapping
# in JavaScript -- the language-keyed maps becoming TextItem lists, put() dropping a blank rather
# than storing "", every field added to Page from now on. A path with two owners drifts, and this
# one would drift silently: the pages would still render, just missing whatever the JS copy had
# never heard of. Pages the seed does not carry -- anything created in the studio -- go with it,
# which is what the dry run is for and why the backup below is not optional.
#
#
# WHAT `--apply` DOES, AND WHY IN THAT ORDER
#
#   1. Back up the website's `page` collection to ./.data/backup/pages/.
#   2. BUILD the studio image. The seed is read from the CLASSPATH, so an edited seed that has not
#      been rebuilt re-seeds the OLD content, which looks exactly like the script not working.
#   3. Drop the collection, and mark the website as needing provisioning (schemaVersion 0 in the
#      control plane). Provisioning is what runs the seed steps, and the studio re-provisions
#      every out-of-date website when it starts.
#   4. Force-recreate `studio`, so it starts and re-provisions. --force-recreate because an image
#      the layer cache rebuilt identically would otherwise leave the container running, and the
#      website's pages would stay empty -- every /p/* a 404.
#   5. Verify: the document count, /p/about through Caddy with the website's host, and the log
#      line PageBootstrap writes. Caddy needs no restart: it resolves the containers afresh for
#      every new connection.
#
# It does not touch the `language` collection: LanguageBootstrap is a separate seed with its own
# guard, and a language removed from the list still resolves for every page written in it.
set -euo pipefail

APPLY=false
RESTORE=""
case "${1:-}" in
    --apply) APPLY=true ;;
    --restore)
        RESTORE="${2:-}"
        test -n "$RESTORE" || { echo "usage: SITE=<id> $(basename "$0") --restore <backup.json>" >&2; exit 2; }
        ;;
    "") ;;
    *) echo "usage: SITE=<id> $(basename "$0") [--apply | --restore <backup.json>]" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SEED_FILE="$ROOT/pacod-cms-core/src/main/resources/seed/pages.json"
BACKUP_DIR="$ROOT/.data/backup/pages"

# Deliberately NOT $MONGO_BACKUP_DIR. That one is the directory mounted into the mongo container
# for mongorestore, and this backup is written host-side through mongosh's stdout precisely so it
# depends neither on that mount existing nor on the container carrying the database tools.

# The Makefile exports COMPOSE with the environment's three --env-file flags; run it through make.
COMPOSE="${COMPOSE:-docker compose --env-file env/dev.env --env-file .env}"
# The website, its database, and the control plane that records its provisioning state.
SITE="${SITE:-demo}"
[[ "$SITE" =~ ^[a-z][a-z0-9-]{1,39}$ ]] || { echo "not a website id: $SITE" >&2; exit 2; }
DB="site_${SITE}"
CONTROL_DB="${MONGO_DATABASE:-pacod_cms}"
# The host the website answers at, for the check at the end.
SITE_HOST="${SITE_HOST:-${SITE}.localhost}"
SMOKE_HOST="${SMOKE_HOST:-localhost}"
# The website's own address, as make derived it: http://<host>:8000 on dev, https://<host> else.
SCHEME="${PUBLIC_SCHEME:-http}"
if [[ "$SCHEME" == https ]]; then PORT="${HTTPS_PORT:-443}"; else PORT="${HTTP_PORT:-8000}"; fi
case "$SCHEME:$PORT" in http:80|https:443) URL="$SCHEME://$SITE_HOST" ;; *) URL="$SCHEME://$SITE_HOST:$PORT" ;; esac

test -f "$SEED_FILE" || { echo "no seed file at $SEED_FILE" >&2; exit 1; }
# Checked here rather than beside the --restore block below, which runs after the container guard:
# a mistyped filename would otherwise be reported as "is the stack up?", sending you to look at
# Docker over a typo.
test -z "$RESTORE" -o -f "$RESTORE" || { echo "no such backup: $RESTORE" >&2; exit 1; }

# Passed with --eval rather than on stdin: mongosh treats stdin as a REPL session and interleaves
# its prompt and progress markers with the output. As the root account the mongo container was
# given, so no password passes through this script.
mongo() {
    $COMPOSE exec -T mongo sh -c 'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin "$1" --eval "$2"' -- "${2:-$DB}" "$1"
}

$COMPOSE exec -T mongo sh -c 'command -v mongosh >/dev/null 2>&1' 2>/dev/null || {
    echo "cannot reach mongosh in the mongo container -- is the stack up? (make up)" >&2
    exit 1
}

# --restore puts a backup this script wrote back, and exits. It is the undo for --apply, and it
# goes through mongosh for the same reason the backup came out of mongosh rather than mongoexport:
# the database tools are not guaranteed to be in the container, and an undo that may not exist is
# not an undo.
#
# EJSON.deserialize over an inlined literal rather than EJSON.parse over a string: it turns the
# {"$date": ...} wrappers back into real dates with no shell escaping anywhere in the path.
#
# Refuses a non-empty collection. insertMany would fail on the duplicate _ids anyway, but it would
# fail HALFWAY -- ordered inserts stop at the first error, leaving a partial restore that reads as
# a successful one.
if [[ -n "$RESTORE" ]]; then
    echo "Restoring the page collection from $RESTORE"
    mongo "const RAW = $(cat "$RESTORE");
$(cat <<'JS'
const existing = db.page.countDocuments();
if (existing > 0) {
    print(`refusing: the page collection already holds ${existing} document(s).`);
    print("Empty it first (db.page.drop()) -- a partial insert would look like a whole one.");
    quit(1);
}
const docs = EJSON.deserialize(RAW);
db.page.insertMany(docs);
print(`restored ${db.page.countDocuments()} document(s): ${docs.map(d => d._id).join(", ")}`);
JS
)"
    echo "The public site shows them within a minute (its page cache)."
    exit 0
fi

# The seed is inlined into the mongosh program rather than parsed host-side, so the comparison is
# against the very bytes that will be baked into the jar and no JSON parser has to exist on the
# host. Expanded inside double quotes, so nothing in the content is re-evaluated by the shell.
SEED_JSON="$(cat "$SEED_FILE")"

REPORT="const SEED = ${SEED_JSON};
$(cat <<'JS'
// THE SLUG IS `_id` IN THE DOCUMENT, AND ONLY `slug` IN THE JAVA AND IN THE SEED FILE.
// Page declares `@Id private String slug`, so Spring Data maps it onto _id and the stored
// document carries NO field called slug. Projecting or sorting on `slug` is not an error -- it
// silently yields nothing, which is the whole danger: `seedSlugs.has(undefined)` is false for
// every row, so a report written that way announces that every page is missing from the
// seed and about to be lost. Same class of thing as MgmtDbRefs.idOf -- the shape the code expects
// and the shape MongoDB delivers are two different questions, and fixtures written from the Java
// field names answer the wrong one.
const seedSlugs = new Set(SEED.map(s => s.slug));
const docs = db.page.find({}, {
    published: 1, titles: 1, createdOn: 1, lastModifiedOn: 1   // _id comes back regardless
}).sort({ _id: 1 }).toArray();

// Local components, deliberately not toISOString(). These are LocalDateTime fields, which Spring
// Data encodes as a BSON date in the writing JVM's zone; toISOString() would re-render them in
// UTC and show a time off by the zone offset from what the studio displays. Local here is
// the mongo container's zone, which is the same zone the studio container wrote them in.
const pad2 = n => String(n).padStart(2, "0");
const iso = d => (d
    ? `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} `
      + `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
    : "—");
// Both fields are set to the same instant by Page(slug), and MgmtPageService stamps
// lastModifiedOn on every save -- so a difference really does mean "edited since it was written",
// not "written at all". Verified against both save paths in that service.
const edited = p => p.createdOn && p.lastModifiedOn && p.lastModifiedOn > p.createdOn;

print(`seed carries ${SEED.length} page(s); the collection holds ${docs.length}`);
print("");

if (docs.length === 0) {
    print("The collection is empty, so there is nothing to lose: --apply re-seeds it.");
} else {
    // Coerced rather than used raw: a document with no _id at all cannot be matched against the
    // seed and must still get a row. Reporting it as unrecognised is right; killing the whole
    // report -- and with it the list of what is about to be dropped -- is not.
    const slugOf = p => (p._id == null ? "—" : String(p._id));

    print("slug            seeded  published  languages  edited     last change");
    print("-----------------------------------------------------------------------");
    for (const p of docs) {
        const langs = (p.titles || []).map(t => t.lang).filter(Boolean);
        print(
            slugOf(p).padEnd(16) +
            (seedSlugs.has(slugOf(p)) ? "yes" : "NO").padEnd(8) +
            (p.published ? "yes" : "no").padEnd(11) +
            String(langs.length).padEnd(11) +
            (edited(p) ? "YES" : "no").padEnd(11) +
            iso(p.lastModifiedOn));
    }
    print("");

    const orphans = docs.filter(p => !seedSlugs.has(slugOf(p))).map(slugOf);
    const touched = docs.filter(p => seedSlugs.has(slugOf(p)) && edited(p)).map(slugOf);

    if (orphans.length) {
        print(`LOST — not in the seed, so nothing recreates ${orphans.length === 1 ? "it" : "them"}:`);
        print(`  ${orphans.join(", ")}`);
        print("  These were created at /mgmt/pages. Only the backup brings them back.");
        print("");
    }
    if (touched.length) {
        print(`REVERTED — edited since seeding, and the seed will overwrite that:`);
        print(`  ${touched.join(", ")}`);
        print("");
    }
    // Called out by name because it is the one page where the loss is both worst and least
    // visible: /p/privacy ships published with a placeholder to replace before publishing, the
    // footer links to it from every page, and a reverted policy still answers 200.
    if (touched.includes("privacy")) {
        print("  NOTE: /p/privacy is among them. It ships as a PLACEHOLDER; if the real policy");
        print("        was written through the studio, this reverts it and the page still 200s.");
        print("");
    }
    if (!orphans.length && !touched.length) {
        print("Nothing would be lost: every page is in the seed and none has been edited since.");
        print("");
    }
}
// Printed last and checked by the shell before anything is dropped. mongosh does exit non-zero
// on an uncaught throw, so `set -e` should already stop us -- but "should" is not the standard
// for the step that empties a collection an operator edits. If the report did not reach its own
// end, we do not know what is in there, and not knowing is a reason to stop rather than proceed.
print("__REPORT_OK__");
JS
)"

# The website must exist, or --apply would drop nothing and re-provision nothing.
known="$(mongo "print(db.website.countDocuments({_id: '${SITE}'}))" "$CONTROL_DB" 2>/dev/null | tail -1 | tr -dc '0-9')"
[[ "${known:-0}" -gt 0 ]] || { echo "no website '${SITE}' in ${CONTROL_DB} -- set SITE=<id>" >&2; exit 1; }

if [[ "$APPLY" == "true" ]]; then
    echo "APPLYING to '${DB}'. The page collection will be backed up, then dropped."
else
    echo "Dry run against '${DB}'. Nothing will be written."
    echo "Re-run with --apply (or 'make reseed-pages APPLY=1') to execute."
fi
echo

# Captured rather than streamed, so the sentinel can be checked and stripped. `|| true` because
# the failure is reported below with the output still in hand -- `set -e` would otherwise kill
# the script before anything explained why.
REPORT_OUT="$(mongo "$REPORT" 2>&1)" || true
printf '%s\n' "${REPORT_OUT/__REPORT_OK__/}"

case "$REPORT_OUT" in
    *__REPORT_OK__*) ;;
    *)  echo "The report above did not run to completion." >&2
        echo "Nothing has been written -- the report runs before the backup and the drop." >&2
        exit 1 ;;
esac

if [[ "$APPLY" != "true" ]]; then
    exit 0
fi

echo "---------------------------------------------------------------------------"
mkdir -p "$BACKUP_DIR"
# No timestamp from `date` inside the container: this is the host's clock, and the file is for a
# person, so local time is what it should carry.
BACKUP="$BACKUP_DIR/${SITE}-page-$(date +%Y%m%d-%H%M%S).json"

# EJSON rather than JSON.stringify so the LocalDateTime fields survive as dates and the file can
# be fed back with mongoimport if it ever has to be.
echo "1/5  backing up the page collection"
mongo 'print(EJSON.stringify(db.page.find().toArray(), null, 2))' > "$BACKUP"
test -s "$BACKUP" || { echo "backup came out empty; refusing to drop" >&2; exit 1; }
echo "     $BACKUP ($(wc -c < "$BACKUP" | tr -d ' ') bytes)"

echo "2/5  building the studio image so the edited seed is in the jar"
$COMPOSE build studio

echo "3/5  dropping the page collection and marking ${SITE} for provisioning"
mongo 'print(`dropped ${db.page.countDocuments()} document(s): ${db.page.drop()}`)'
mongo "printjson(db.website.updateOne({_id: '${SITE}'}, {\$set: {schemaVersion: 0}}).modifiedCount)" "$CONTROL_DB"

echo "4/5  recreating studio so it re-provisions ${SITE}"
$COMPOSE up -d --force-recreate --no-deps studio

echo "5/5  verifying"
for _ in $(seq 1 60); do
    # tail -1 before stripping: --quiet silences most of mongosh's chatter but not all of it, and
    # a deprecation notice carrying a digit would concatenate into the count.
    count="$(mongo 'print(db.page.countDocuments())' 2>/dev/null | tail -1 | tr -dc '0-9')"
    [[ -n "${count:-}" && "$count" -gt 0 ]] && break
    sleep 2
done
echo "     documents in page:  ${count:-0} (the seed carries $(grep -c '"slug":' "$SEED_FILE"))"
# The URL is printed, not just the code, and that is the same defence `make smoke` uses when it
# prints a Location header. SMOKE_HOST defaults to localhost, and an `ssh -L 8000:localhost:8000`
# tunnel binds 127.0.0.1:8000 ahead of Docker's 0.0.0.0 -- so a 200 here can be the REMOTE site
# answering while this stack is broken. Seeing the URL is what makes that checkable.
printf '     GET %-38s -> %s\n' "$URL/p/about" \
    "$(curl -s -o /dev/null -w '%{http_code}' --connect-to "${SITE_HOST}:${PORT}:${SMOKE_HOST}:${PORT}" "$URL/p/about")"
# The count is not proof on its own -- documents with no log line behind them came from
# somewhere else. This is the line PageBootstrap writes, and the only direct evidence it ran.
echo "     PageBootstrap said:"
$COMPOSE logs --tail 400 studio 2>/dev/null | grep -i "standing page" | tail -3 | sed 's/^/       /' \
    || echo "       (nothing yet — try: make logs-studio | grep -i 'standing page')"
echo
echo "If any of that is wrong, the collection as it was is in"
echo "  $BACKUP"
# Restoring goes back through mongosh, for the same reason the backup came out of it: mongoimport
# is one of the database tools the container may not carry, and a recovery path that may not
# exist in the container is not a recovery path. The file is inlined into the mongosh program, so
# nothing has to be mounted anywhere either.
echo "Put it back with (the collection must be empty first, or this duplicates _ids):"
echo "  make reseed-pages-restore SITE=${SITE} FILE=$BACKUP"
