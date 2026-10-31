#!/usr/bin/env bash
# make smoke: every main route of the running stack, through Caddy, by host. Each row says what it
# expects and is checked: the code, and for a redirect where it goes -- a redirect that drops the
# port is still a 302, so the Location is what catches it. Exit 1 when any row fails.
#
#   scripts/smoke.sh          the routes, over the environment's scheme (http on dev, https else)
#   scripts/smoke.sh certs    each host's certificate over HTTPS, and whether it verifies
#
# Run by make, which exports what it needs: PUBLIC_SCHEME, HTTP_PORT, HTTPS_PORT, STUDIO_HOST,
# ID_HOST, STUDIO_ORIGIN, ID_ISSUER, SITE, SITE_HOST, SMOKE_DEMO, SMOKE_HOST, WEB_PORT, MGMT_PORT,
# PACOD_ENV. Requests go to SMOKE_HOST (localhost) with --connect-to, so the URL -- and the Host
# header and the TLS name -- is the real host's: Caddy picks the route by the one and the
# certificate by the other.
set -uo pipefail

MODE=${1:-routes}
SCHEME=${PUBLIC_SCHEME:-http}
if [ "$MODE" = certs ]; then SCHEME=https; fi
if [ "$SCHEME" = https ]; then PORT=${HTTPS_PORT:-8443} DEFAULT=443; else PORT=${HTTP_PORT:-8000} DEFAULT=80; fi
SUFFIX=; [ "$PORT" = "$DEFAULT" ] || SUFFIX=":$PORT"
SMOKE_HOST=${SMOKE_HOST:-localhost}
STUDIO_HOST=${STUDIO_HOST:-studio.localhost}
ID_HOST=${ID_HOST:-id.localhost}
STUDIO_URL=${STUDIO_ORIGIN:-$SCHEME://$STUDIO_HOST$SUFFIX}
ID_URL=${ID_ISSUER:-$SCHEME://$ID_HOST$SUFFIX}
SITE=${SITE:-}
SITE_HOST=${SITE_HOST:-}
SITE_URL=$SCHEME://$SITE_HOST$SUFFIX
# A developer machine's HTTPS comes from Caddy's local CA, which nothing here trusts.
INSECURE=; [ "${PACOD_ENV:-dev}" != dev ] || INSECURE=-k
failed=0

# code<TAB>redirect for host+path; extra curl arguments after the path.
fetch() {
  local host=$1 path=$2
  shift 2
  curl -s -o /dev/null $INSECURE --max-time 20 -w '%{http_code}\t%{redirect_url}' \
    --connect-to "$host:$PORT:$SMOKE_HOST:$PORT" "$@" "$SCHEME://$host$SUFFIX$path"
}
# row LABEL EXPECTED HOST PATH [LOCATION-PREFIX] [curl args…]; EXPECTED may be "200|302".
row() {
  local label=$1 want=$2 host=$3 path=$4 where=${5:-}
  shift 4; [ $# -eq 0 ] || shift
  local out code loc mark=ok note=
  out=$(fetch "$host" "$path" "$@")
  code=${out%%$'\t'*} loc=${out#*$'\t'}
  case "|$want|" in *"|$code|"*) ;; *) mark=FAIL note="expected $want" ;; esac
  if [ -n "$where" ] && [ "$mark" = ok ] && [ "${loc#"$where"}" = "$loc" ]; then
    mark=FAIL note="expected a redirect to ${where}…"
  fi
  [ "$mark" = ok ] || failed=$((failed + 1))
  [ ${#loc} -le 90 ] || loc="${loc:0:90}…"
  line "$([ "$mark" = ok ] && echo ✓ || echo ✗)" "$label" "$code${loc:+  -> $loc}${note:+   ($note)}"
}
line() { printf '   %s %-44s %s\n' "$1" "$2" "$3"; }
# A row against an application's own port, on loopback, bypassing Caddy.
direct() {
  local label=$1 want=$2 url=$3 code
  shift 3
  code=$(curl -s -o /dev/null --max-time 10 -w '%{http_code}' "$@" "$url")
  if [ "$code" = "$want" ]; then
    line ✓ "$label" "$code"
  else
    line ✗ "$label" "$code   (expected $want)"; failed=$((failed + 1))
  fi
}

if [ "$MODE" = certs ]; then
  echo "certificates on :$PORT (by SNI, through $SMOKE_HOST)"
  for host in $SITE_HOST "$STUDIO_HOST" "$ID_HOST"; do
    echo "  $host"
    echo | openssl s_client -connect "$SMOKE_HOST:$PORT" -servername "$host" 2>/dev/null \
      | openssl x509 -noout -subject -issuer -enddate 2>/dev/null | sed 's/^/      /'
    if curl -s -o /dev/null --max-time 10 --connect-to "$host:$PORT:$SMOKE_HOST:$PORT" "https://$host$SUFFIX/"; then
      echo "      verifies"
    else
      echo "      NOT trusted by a strict client (Caddy's local CA on dev, else expired, self-signed or the wrong name)"
      [ "${PACOD_ENV:-dev}" = dev ] || failed=$((failed + 1))
    fi
  done
  row "a host no website claims gets no certificate" 000 nobody.invalid /
  [ "$failed" -eq 0 ] || { echo "$failed problem(s)"; exit 1; }
  exit 0
fi

echo "smoke: $SCHEME, through $SMOKE_HOST:$PORT (ENV=${PACOD_ENV:-dev})"

if [ -n "$SITE_HOST" ]; then
  echo "website $SITE_HOST"
  row "/" 200 "$SITE_HOST" /
  # Never X-Frame-Options: DENY, which blanks the website's own HTML5 bookings (FramingHeaders).
  framing=$(curl -s -o /dev/null -D - $INSECURE --connect-to "$SITE_HOST:$PORT:$SMOKE_HOST:$PORT" "$SITE_URL/" \
    | tr -d '\r' | grep -iE '^(content-security-policy|x-frame-options):' | cut -d' ' -f2- | paste -sd'|' -)
  case "$framing" in
    *DENY*|'') line ✗ "framing" "${framing:-none}"; failed=$((failed + 1)) ;;
    *) line ✓ "framing" "$framing" ;;
  esac
  row "/?_preview=x (a bad token is refused)" 403 "$SITE_HOST" "/?_preview=x"
  row "/web/v1/health" 200 "$SITE_HOST" /web/v1/health
  if [ -n "${SMOKE_DEMO:-}" ]; then
    # The demo content's own pages; a website without it has none of these.
    row "/article/1" 200 "$SITE_HOST" /article/1
    row "/en/article/1" 200 "$SITE_HOST" /en/article/1
    row "/list/2" 200 "$SITE_HOST" /list/2
    row "/p/about" 200 "$SITE_HOST" /p/about
    row "/en/p/about" 200 "$SITE_HOST" /en/p/about
    row "/social (the demo's link page)" 200 "$SITE_HOST" /social
  fi
  # A four-letter prefix never resolves as a language.
  row "/news/article/1" 404 "$SITE_HOST" /news/article/1
  row "/news/p/about" 404 "$SITE_HOST" /news/p/about
  row "/site-assets/site.css" 200 "$SITE_HOST" /site-assets/site.css
  row "/search?q=a" 200 "$SITE_HOST" "/search?q=a"
  row "/search-google" "200|302" "$SITE_HOST" /search-google
  row "/search.html" 404 "$SITE_HOST" /search.html
  row "/robots.txt" 200 "$SITE_HOST" /robots.txt
  row "/sitemap.xml" 200 "$SITE_HOST" /sitemap.xml
  row "/llms.txt" 200 "$SITE_HOST" /llms.txt
  row "/llms-full.txt" 200 "$SITE_HOST" /llms-full.txt
  row "/feed.xml" 200 "$SITE_HOST" /feed.xml
  row "/issues" 200 "$SITE_HOST" /issues
  row "/issues/999999 (no such issue)" 404 "$SITE_HOST" /issues/999999
  # The issue's print front shows drafts: without the PDF renderer's token it is not there at all.
  row "/issue-print/1/front (no print token)" 404 "$SITE_HOST" /issue-print/1/front
  row "/social/go/x" 302 "$SITE_HOST" /social/go/x "$SITE_URL/"
  row "/_internal/tls-ask (closed from outside)" 404 "$SITE_HOST" "/_internal/tls-ask?domain=$SITE_HOST"
  row "/assets/img/articles/none.jpg" 404 "$SITE_HOST" /assets/img/articles/none.jpg
  # Nothing of the studio answers on a website's host, and nothing serves /v1/** (the site's own
  # JSON is /web/v1/**).
  row "/mgmt/login" 404 "$SITE_HOST" /mgmt/login
  row "/v1/health" 404 "$SITE_HOST" /v1/health
else
  echo "website: none named (SITE=<id> or SITE_HOST=<host>) -- its rows are skipped"
fi

echo "a host no website claims"
if [ "$SCHEME" = https ]; then
  row "nobody.invalid (no certificate at all)" 000 nobody.invalid /
else
  row "nobody.invalid" 404 nobody.invalid /
fi

echo "studio $STUDIO_HOST"
row "/" 302 "$STUDIO_HOST" / "$STUDIO_URL/mgmt/sites"
row "/mgmt/login" 200 "$STUDIO_HOST" /mgmt/login
row "/mgmt/sites (signed out)" 302 "$STUDIO_HOST" /mgmt/sites "$STUDIO_URL/mgmt/login"
row "/mgmt/articles (no website named)" 302 "$STUDIO_HOST" /mgmt/articles "$STUDIO_URL/mgmt/sites"
row "/sites/nobody/mgmt/articles" 404 "$STUDIO_HOST" /sites/nobody/mgmt/articles
row "/mgmt/ui-language/en (back where it was)" 302 "$STUDIO_HOST" /mgmt/ui-language/en "$STUDIO_URL/mgmt/login" \
  -e "$STUDIO_URL/mgmt/login"
row "/mgmt-assets/mgmt.css" 200 "$STUDIO_HOST" /mgmt-assets/mgmt.css
row "/mgmt-assets/tinymce/tinymce.min.js" 200 "$STUDIO_HOST" /mgmt-assets/tinymce/tinymce.min.js
row "/mgmt/sites/new" 302 "$STUDIO_HOST" /mgmt/sites/new "$STUDIO_URL/mgmt/login"
row "/mgmt/organisations" 302 "$STUDIO_HOST" /mgmt/organisations "$STUDIO_URL/mgmt/login"
row "/mgmt/platform" 302 "$STUDIO_HOST" /mgmt/platform "$STUDIO_URL/mgmt/login"
# A website's pictures in the studio are its content: behind sign-in.
row "/assets/img/articles/none.jpg" 302 "$STUDIO_HOST" /assets/img/articles/none.jpg "$STUDIO_URL/mgmt/login"
row "/resources/banners/x/index.html" 302 "$STUDIO_HOST" /resources/banners/x/index.html "$STUDIO_URL/mgmt/login"
# The API for scripts is on pacod-net only; on the studio's own port a bearer token is required.
row "/internal/v1/ai-gateway (not routed)" 404 "$STUDIO_HOST" /internal/v1/ai-gateway
if [ -n "$SITE" ]; then
  # The polls and data screens one by one: a matcher covering a screen but not its JSON would hand
  # the data to anyone, with nothing on any screen to show it.
  # The API for scripts has no website prefix (StudioSiteFilter would otherwise strip one past Caddy).
  row "/sites/$SITE/internal/v1/ai-gateway" 404 "$STUDIO_HOST" "/sites/$SITE/internal/v1/ai-gateway"
  for screen in articles monitoring/live dashboard/live jobs crawlers social/stats banners/layouts design/preview settings members \
      pdf issues issues/1/pdf/en; do
    row "/sites/$SITE/mgmt/$screen" 302 "$STUDIO_HOST" "/sites/$SITE/mgmt/$screen" "$STUDIO_URL/sites/$SITE/mgmt/login"
  done
fi

echo "sign-in $ID_HOST"
row "/.well-known/openid-configuration" 200 "$ID_HOST" /.well-known/openid-configuration
row "studio /mgmt/oauth2/authorization/pacod-id" 302 "$STUDIO_HOST" /mgmt/oauth2/authorization/pacod-id "$ID_URL/oidc/authorize"

echo "direct, bypassing Caddy (on $SMOKE_HOST)"
direct "site /web/v1/health" 200 "http://$SMOKE_HOST:${WEB_PORT:-8080}/web/v1/health"
direct "site /v1/health" 404 "http://$SMOKE_HOST:${WEB_PORT:-8080}/v1/health"
[ -z "$SITE_HOST" ] || direct "site $SITE_HOST /" 200 "http://$SMOKE_HOST:${WEB_PORT:-8080}/" -H "Host: $SITE_HOST"
direct "studio /mgmt-health" 200 "http://$SMOKE_HOST:${MGMT_PORT:-7070}/mgmt-health"
direct "studio /internal/v1/ai-gateway (no token)" 401 "http://$SMOKE_HOST:${MGMT_PORT:-7070}/internal/v1/ai-gateway"

if [ "$failed" -gt 0 ]; then
  echo "$failed row(s) failed."
  exit 1
fi
echo "All rows passed."
