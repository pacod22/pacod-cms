#!/usr/bin/env bash
# make check: is this machine ready to run its environment? `make up` runs it first and stops on
# any ✗; a ! is worth reading and starts anyway.
#
#   scripts/check.sh <dev|stage|prod>
#
# What it reads: env/<ENV>.env, .env and .env-local, the later winning, and -- for the settings
# make uses itself -- the value make computed (a command-line override, a derived origin), which
# make exports. Secrets come from the files only: make never exports them.
set -uo pipefail
cd "$(dirname "$0")/.."

ENV_NAME=${1:-dev}
FILES=("env/$ENV_NAME.env" .env .env-local)
errors=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; errors=$((errors + 1)); }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }

lastval() {
  local key=$1 file line value=
  shift
  for file in "$@"; do
    [ -f "$file" ] || continue
    line=$(grep -E "^$key=" "$file" | tail -n 1 || true)
    [ -z "$line" ] || value=${line#*=}
  done
  printf '%s' "$value"
}
# A setting: what make exported, else the files.
get() { printenv "$1" 2>/dev/null || lastval "$1" "${FILES[@]}"; }
# Two labels: a stand-in for the registrable domain, good enough for a warning (the studio checks
# the Public Suffix List itself at startup).
reg() { printf '%s' "$1" | awk -F. '{ if (NF >= 2) print $(NF-1) "." $NF; else print $0 }'; }
local_host() { case "$1" in localhost|*.localhost|*.local|*.test|*.invalid|example.com|*.example.com|example.net|*.example.net|example.org|*.example.org) return 0;; esac; return 1; }

echo "Checking ENV=$ENV_NAME (env/$ENV_NAME.env, .env$([ -f .env-local ] && printf ', .env-local'))"

case "$ENV_NAME" in dev|stage|prod) ;; *) bad "ENV must be dev, stage or prod, not $ENV_NAME"; exit 1 ;; esac
if [ ! -f .env ]; then
  bad "no .env: run \`make init\` (a developer machine) or \`make init ENV=stage|prod STUDIO_HOST=… ID_HOST=… …\`"
  exit 1
fi
written=$(lastval ENV .env .env-local)
[ "${written:-dev}" = "$ENV_NAME" ] || warn ".env is written for ENV=${written:-dev}; running $ENV_NAME because the command line says so"

# ---- values both make and compose read must mean the same to both ------------------------------
# Every name the stack reads appears in one of these; a key in .env that none mentions is read by
# nothing (left from an older release, or a typo) and only reported.
# The Makefile's unexport list: values compose alone reads.
SECRET_KEYS="MONGO_ROOT_PASSWORD MONGO_PASSWORD PID_PG_SUPERUSER_PASSWORD PID_DB_PASSWORD PID_KEK PID_TOKEN_PEPPER
CMS_ID_STUDIO_SECRET CMS_ID_SERVICE_SECRET ID_ADMIN_PASSWORD PACOD_PREVIEW_SECRET METRICS_VISITOR_SALT PACOD_GW_API_KEY
PACOD_ID_PROVISIONER_TOKEN PID_SMTP_USERNAME PID_SMTP_PASSWORD"
SECRET_KEYS=$(printf '%s' "$SECRET_KEYS" | tr '\n' ' ')
known=" $(cat docker-compose.yml docker-compose.standalone.yml Makefile env/*.env .env.example 2>/dev/null | grep -oE '[A-Z][A-Z0-9_]{2,}' | sort -u | tr '\n' ' ') "
unused=()
for file in .env .env-local; do
  [ -f "$file" ] || continue
  while IFS= read -r line; do
    key=${line%%=*} value=${line#*=}
    case "$known" in *" $key "*) ;; *) unused+=("$key"); continue ;; esac
    case "$value" in
      \"*|\'*) bad "$file: $key is quoted -- compose drops the quotes, make and the scripts keep them. Write it bare." ;;
      *\$*) bad "$file: $key contains \$ -- make and compose expand it. Choose another value." ;;
    esac
    # A secret make never exports goes to compose as compose reads it: # and spaces are fine there.
    case " $SECRET_KEYS " in *" $key "*) continue ;; esac
    case "$value" in *\#*) bad "$file: $key contains # -- make cuts the value there. Choose another value." ;; esac
    case "$key" in
      CADDY_GLOBAL_OPTIONS|CMS_STUDIO_SITE|CMS_PUBLIC_SITE|ID_ADMIN_EMAIL|ID_MAIL_FROM) ;;
      *) case "$value" in *[[:space:]]*) bad "$file: $key contains a space." ;; esac ;;
    esac
  done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$file")
done
[ ${#unused[@]} -eq 0 ] || warn "read by nothing in this release (remove them when convenient): $(printf '%s\n' "${unused[@]}" | sort -u | paste -sd' ' -)"

# ---- secrets -------------------------------------------------------------------------------------
missing=()
for key in MONGO_ROOT_PASSWORD MONGO_PASSWORD PID_PG_SUPERUSER_PASSWORD PID_DB_PASSWORD PID_KEK PID_TOKEN_PEPPER CMS_ID_STUDIO_SECRET CMS_ID_SERVICE_SECRET ID_ADMIN_PASSWORD; do
  [ -n "$(lastval "$key" "${FILES[@]}")" ] || missing+=("$key")
done
if [ ${#missing[@]} -gt 0 ]; then
  bad "missing: ${missing[*]} -- \`make init\` writes them"
else
  ok "the secrets are there"
fi
preview=$(lastval PACOD_PREVIEW_SECRET "${FILES[@]}")
if [ -z "$preview" ]; then
  warn "PACOD_PREVIEW_SECRET is blank: no draft previews and no PDF export"
elif [ ${#preview} -lt 32 ]; then
  bad "PACOD_PREVIEW_SECRET is shorter than 32 characters: both applications refuse to start"
fi

profiles=$(get COMPOSE_PROFILES)
smtp=$(lastval PID_SMTP_HOST "${FILES[@]}")
gw_key=$(lastval PACOD_GW_API_KEY "${FILES[@]}")
case ",$profiles," in
  *,dev-gateway,*) [ -n "$gw_key" ] || warn "PACOD_GW_API_KEY is blank: the mock gateway refuses the studio, AI is off" ;;
  *) [ -n "$gw_key" ] && [ -n "$(lastval PACOD_GW_URL "${FILES[@]}")" ] || warn "no AI gateway (PACOD_GW_URL, PACOD_GW_API_KEY): AI is \"not set up\"" ;;
esac

# ---- stage and prod: a server on the internet ----------------------------------------------------
if [ "$ENV_NAME" != dev ]; then
  studio=$(get STUDIO_HOST) id=$(get ID_HOST) sites=$(get PACOD_SITE_DOMAIN)
  hosts_ok=1
  for pair in "STUDIO_HOST:$studio" "ID_HOST:$id"; do
    name=${pair%%:*} value=${pair#*:}
    if [ -z "$value" ]; then bad "$name is not set (.env)"; hosts_ok=
    elif local_host "$value"; then bad "$name=$value is not a public host name"; hosts_ok=
    fi
  done
  [ -z "$hosts_ok" ] || ok "hosts: studio $studio, pacod-id $id"
  [ "$(get HTTP_PORT)" = 80 ] && [ "$(get HTTPS_PORT)" = 443 ] \
    || bad "HTTP_PORT/HTTPS_PORT are $(get HTTP_PORT)/$(get HTTPS_PORT): Caddy obtains certificates on 80 and 443, and the prod profile writes addresses without a port"
  [ "$(get STUDIO_ORIGIN)" = "https://$studio" ] || bad "STUDIO_ORIGIN is $(get STUDIO_ORIGIN), not https://$studio: pacod-id registers it exactly"
  [ "$(get ID_ISSUER)" = "https://$id" ] || bad "ID_ISSUER is $(get ID_ISSUER), not https://$id"
  [ "$(get CADDYFILE)" = ./docker/caddy/Caddyfile ] || warn "CADDYFILE is $(get CADDYFILE), not production's ./docker/caddy/Caddyfile"
  [ "$(get SPRING_PROFILE)" = prod ] || warn "SPRING_PROFILE is $(get SPRING_PROFILE), not prod"
  [ "$(get PUBLISH_IP)" = 127.0.0.1 ] || warn "PUBLISH_IP is '$(get PUBLISH_IP)': the applications' and MongoDB's ports are reachable from outside"
  case "$(get CADDY_GLOBAL_OPTIONS)" in *email*) ;; *) warn "no CADDY_GLOBAL_OPTIONS=email …: the certificate authority cannot warn you about expiring certificates" ;; esac
  if [ -n "$sites" ]; then
    if local_host "$sites"; then
      bad "PACOD_SITE_DOMAIN=$sites is not a public domain"
    elif [ "$(get PACOD_SITE_DOMAIN_SHARED)" = true ]; then
      warn "PACOD_SITE_DOMAIN_SHARED=true: the websites may share the studio's registrable domain; run only websites you trust"
    elif [ "$(reg "$sites")" = "$(reg "$studio")" ] || [ "$(reg "$sites")" = "$(reg "$id")" ]; then
      warn "PACOD_SITE_DOMAIN=$sites looks like the studio's or pacod-id's registrable domain: the studio refuses to start if it is"
    fi
  fi
  if command -v getent >/dev/null 2>&1; then
    for h in "$studio" "$id"; do [ -z "$h" ] || getent hosts "$h" >/dev/null || warn "$h does not resolve yet: Caddy cannot obtain its certificate"; done
  fi
fi

if [ "$ENV_NAME" = stage ]; then
  sites=$(get PACOD_SITE_DOMAIN)
  [ -n "$sites" ] || bad "PACOD_SITE_DOMAIN is not set: new websites and the demo websites answer below it"
  if [ "$(get DEMO_WEBSITES)" = true ]; then
    demo="$(get DEMO_HOSTS),$(get DEMO_MINIMAL_HOSTS)"
    case "$demo" in
      *localhost*|,*|*,) bad "DEMO_HOSTS/DEMO_MINIMAL_HOSTS are '$demo': the demo websites are created once, on the first start, at these hosts" ;;
      *) ok "demo websites at $demo" ;;
    esac
  fi
  [ -z "$smtp" ] || [ "$smtp" = pacod-mailpit ] || warn "PID_SMTP_HOST=$smtp: stage sends real mail"
fi

if [ "$ENV_NAME" = prod ]; then
  if [ -z "$smtp" ] || [ "$smtp" = pacod-mailpit ]; then
    bad "PID_SMTP_HOST is not set: pacod-id sends confirmations, invitations and password resets through it"
  else
    ok "mail through $smtp"
  fi
  from=$(lastval ID_MAIL_FROM "${FILES[@]}")
  case "$from" in
    ''|*.test) bad "ID_MAIL_FROM is '${from}': the sender must be on a domain your relay sends for" ;;
  esac
  case "$(lastval ID_ADMIN_EMAIL "${FILES[@]}")" in ''|*.test) warn "ID_ADMIN_EMAIL is a .test address: the first account's password resets go nowhere" ;; esac
  [ "$(get DEMO_WEBSITES)" != true ] || warn "DEMO_WEBSITES=true: an empty production platform gets the two demo websites"
  case ",$profiles," in *,dev-gateway,*|*,mailpit,*) warn "COMPOSE_PROFILES=$profiles runs the mock gateway or the mail catcher in production" ;; esac
fi

# ---- a certificate pair of this machine's own --------------------------------------------------
if [ -n "$(get SSL_CERT_FILE)$(get SSL_KEY_FILE)" ]; then
  SSL_CERT_FILE=$(get SSL_CERT_FILE) SSL_KEY_FILE=$(get SSL_KEY_FILE) ./scripts/check-tls.sh | sed '1d;s/^/  /' || errors=$((errors + 1))
fi

# ---- names from before the shared network pacod-net ----------------------------------------------
# The services and volumes were renamed (pacod-demo/docs/NETWORK.md); a value naming an old one
# reaches nothing, and a volume under its old name is data the stack would no longer see.
for pair in "PID_SMTP_HOST:mailpit" "PACOD_GW_URL:http://gw:" "PACOD_PDF_GOTENBERG_URL:http://gotenberg:" "MONGO_DATA_DIR:mongo-data"; do
  key=${pair%%:*} old=${pair#*:}
  case "$(get "$key")" in "$old"*) bad "$key names '$old', renamed with the move to pacod-net (cms-mongo-data, pacod-mailpit, http://pacod-gw:8080, http://cms-gotenberg:3000)" ;; esac
done
if docker info >/dev/null 2>&1; then
  project=${COMPOSE_PROJECT_NAME:-$(basename "$PWD" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')}
  pairs="id-pg-data:pacod-postgres-data caddy-data:pacod-caddy-data"
  case "$(get MONGO_DATA_DIR)" in */*) ;; *) pairs="mongo-data:cms-mongo-data $pairs" ;; esac
  for pair in $pairs; do
    old=${project}_${pair%%:*} new=${project}_${pair#*:}
    if docker volume inspect "$old" >/dev/null 2>&1 && ! docker volume inspect "$new" >/dev/null 2>&1; then
      bad "volume $old holds data from before the move to pacod-net: copy it into $new first (docs/RUNNING.md, \"Moving to pacod-net\")"
    fi
  done
fi

# ---- what compose makes of it all ----------------------------------------------------------------
if command -v docker >/dev/null 2>&1; then
  compose=${COMPOSE:-docker compose --env-file env/$ENV_NAME.env --env-file .env $([ -f .env-local ] && printf -- '--env-file .env-local') -f docker-compose.yml -f docker-compose.standalone.yml}
  if out=$($compose config -q 2>&1); then ok "the compose files resolve"; else bad "docker compose config: $out"; fi
else
  bad "docker is not installed"
fi

if [ "$errors" -gt 0 ]; then
  echo "$errors problem(s): nothing was started."
  exit 1
fi
echo "Ready."
