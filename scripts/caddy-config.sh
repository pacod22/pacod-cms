#!/usr/bin/env bash
# make caddy-config (PROXY_MODE=external): cms's two sites -- the studio and the websites -- for a
# Caddy that already runs on this host, as one file it imports.
#
#   scripts/caddy-config.sh [<file>]     (to stdout without one)
#
# The file is platform/caddy/cms.caddy itself, with every {$VAR} and {$VAR:default} placeholder
# filled from this environment (make exports .env): that Caddy has none of cms's variables, and an
# unset placeholder there would fall back to the default -- for CMS_PUBLIC_SITE, a catch-all that
# would take every other site that Caddy serves. So CMS_PUBLIC_SITE must name the websites' hosts.
#
# CMS_PUBLIC_EXTRA_FILE, when set, names a file of Caddyfile directives that goes into the websites'
# site before it proxies -- the redirects of a website's old addresses, for example -- its
# placeholders filled the same way. Name it anything but *.caddy if it lies where that Caddy
# imports sites from.
set -euo pipefail
cd "$(dirname "$0")/.."

out=${1:-}
src=platform/caddy/cms.caddy

: "${CMS_STUDIO_SITE:?CMS_STUDIO_SITE is not set: the studio's address, https://<STUDIO_HOST>}"
: "${CMS_PUBLIC_SITE:?CMS_PUBLIC_SITE is not set: the websites' hosts, comma-separated}"
for address in ${CMS_PUBLIC_SITE//,/ }; do
  case "$address" in
    http://|https://|:*|http://:*|https://:*|'*'|'*.*')
      echo "CMS_PUBLIC_SITE=$CMS_PUBLIC_SITE catches every host: behind a Caddy that serves other sites, name the websites' hosts" >&2
      exit 1 ;;
  esac
done
extra=${CMS_PUBLIC_EXTRA_FILE:-}
if [ -n "$extra" ] && [ ! -f "$extra" ]; then
  echo "CMS_PUBLIC_EXTRA_FILE=$extra is not a file" >&2
  exit 1
fi

rendered=$(
  {
    echo "# Written by pacod-cms (make caddy-config) from platform/caddy/cms.caddy, with its placeholders"
    echo "# filled in. Do not edit: change .env or that file, and run make caddy-config again."
    echo
    awk -v extra="$extra" '
      # Replaces each {$NAME} and {$NAME:default} as Caddy would: a set variable -- even an empty
      # one -- by its value, an unset one by its default.
      function fill(line,    out, at, token, inner, name, dflt, colon) {
        out = ""
        while (match(line, /\{\$[A-Za-z_][A-Za-z0-9_]*(:[^}]*)?\}/)) {
          token = substr(line, RSTART, RLENGTH)
          inner = substr(token, 3, length(token) - 3)
          colon = index(inner, ":")
          name = colon ? substr(inner, 1, colon - 1) : inner
          dflt = colon ? substr(inner, colon + 1) : ""
          out = out substr(line, 1, RSTART - 1) ((name in ENVIRON) ? ENVIRON[name] : dflt)
          line = substr(line, RSTART + RLENGTH)
        }
        return out line
      }
      /^[ \t]*\{\$CMS_PUBLIC_EXTRA\}[ \t]*$/ {
        if (extra != "") {
          indent = $0; sub(/\{.*$/, "", indent)
          print indent "# " extra
          while ((getline row < extra) > 0) print (row == "" ? "" : indent fill(row))
          close(extra)
        }
        next
      }
      { line = fill($0); if (line !~ /^[ \t]*$/ || $0 ~ /^[ \t]*$/) print line }
    ' "$src"
  }
)

if [ -z "$out" ]; then
  printf '%s\n' "$rendered"
else
  mkdir -p "$(dirname "$out")"
  printf '%s\n' "$rendered" > "$out"
  echo "wrote $out"
fi
