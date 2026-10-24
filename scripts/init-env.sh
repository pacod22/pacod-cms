#!/usr/bin/env bash
# make init: writes this machine's .env for an environment, or completes an existing one.
#
#   scripts/init-env.sh <dev|stage|prod> [STUDIO_HOST=…] [ID_HOST=…] [SITE_DOMAIN=…] [EMAIL=…]
#
# A new .env gets ENV, the hosts (stage and prod), the mail block production must fill in, and fresh
# secrets -- nothing else; .env.example lists every other setting. An existing .env (with an
# optional .env-local) gets only the secrets neither file names yet: a value written there, even a
# blank one, is a decision and stays. No value is ever printed.
#
# It refuses to invent a secret that data already depends on, and writes nothing then: MongoDB's
# two passwords once its data exists (they were taken on its first start), pacod-id's while its
# database volume exists (its role was created with them, and what it stored is sealed under
# PID_KEK). Values are hex or base64, so make and compose read them alike.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_NAME=${1:-}
case "$ENV_NAME" in
  dev|stage|prod) shift ;;
  *) echo "usage: make init [ENV=dev|stage|prod] [STUDIO_HOST=…] [ID_HOST=…] [SITE_DOMAIN=…] [EMAIL=…]" >&2; exit 2 ;;
esac

STUDIO_HOST_ARG= ID_HOST_ARG= SITE_DOMAIN_ARG= EMAIL_ARG=
for arg in "$@"; do
  case "$arg" in
    STUDIO_HOST=*) STUDIO_HOST_ARG=${arg#*=} ;;
    ID_HOST=*) ID_HOST_ARG=${arg#*=} ;;
    SITE_DOMAIN=*) SITE_DOMAIN_ARG=${arg#*=} ;;
    EMAIL=*) EMAIL_ARG=${arg#*=} ;;
    *) echo "init: unknown setting $arg" >&2; exit 2 ;;
  esac
done

fail() { echo "init: $*" >&2; exit 1; }
is_host() { [[ "$1" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]]; }
for pair in "STUDIO_HOST:$STUDIO_HOST_ARG" "ID_HOST:$ID_HOST_ARG" "SITE_DOMAIN:$SITE_DOMAIN_ARG"; do
  value=${pair#*:}
  [ -z "$value" ] || is_host "$value" || fail "${pair%%:*}=$value is not a host name (lowercase, dot-separated)"
done
[ -z "$EMAIL_ARG" ] || [[ "$EMAIL_ARG" =~ ^[^[:space:]@\"\'\#\$]+@[a-z0-9.-]+$ ]] || fail "EMAIL=$EMAIL_ARG is not a mail address"

# The last value KEY is given in these files, as written.
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
# Named by this machine's files, even blank.
named() { grep -qE "^$1=" .env 2>/dev/null || grep -qE "^$1=" .env-local 2>/dev/null; }
hex() { openssl rand -hex "$1"; }
generate() {
  case "$1" in
    hex*) hex "${1#hex}" ;;
    b64) openssl rand -base64 32 | tr -d '\n' ;;
    gw) printf 'pgw_cms_%s' "$(hex 24)" ;;
  esac
}

FILES=("env/$ENV_NAME.env" .env .env-local)

# ---- the environment -----------------------------------------------------------------------------
if [ -f .env ]; then
  have=$(lastval ENV .env .env-local)
  [ "${have:-dev}" = "$ENV_NAME" ] \
    || fail ".env is written for ENV=${have:-dev}, not $ENV_NAME. Change its ENV line by hand if this machine really changes role."
  [ -z "$STUDIO_HOST_ARG$ID_HOST_ARG$SITE_DOMAIN_ARG$EMAIL_ARG" ] \
    || fail ".env exists, and init writes hosts only into a new one: edit STUDIO_HOST, ID_HOST, PACOD_SITE_DOMAIN and the rest in .env."
elif [ "$ENV_NAME" != dev ]; then
  [ -n "$STUDIO_HOST_ARG" ] && [ -n "$ID_HOST_ARG" ] \
    || fail "ENV=$ENV_NAME needs its hosts: make init ENV=$ENV_NAME STUDIO_HOST=studio.example.com ID_HOST=id.example.com SITE_DOMAIN=sites.example.net EMAIL=ops@example.com
      SITE_DOMAIN is where websites answer (<id>.<SITE_DOMAIN>; required for stage, where the demo websites go) and must be
      another registrable domain than the studio's and pacod-id's -- the studio refuses to start otherwise."
  [ "$ENV_NAME" != stage ] || [ -n "$SITE_DOMAIN_ARG" ] || fail "ENV=stage needs SITE_DOMAIN=: the demo websites answer at demo.<SITE_DOMAIN> and minimal.<SITE_DOMAIN>."
fi

# ---- what data exists ------------------------------------------------------------------------------
project=${COMPOSE_PROJECT_NAME:-$(basename "$PWD" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')}
docker_up=; docker info >/dev/null 2>&1 && docker_up=1
volume() {
  if [ -z "$docker_up" ]; then echo "unknown: Docker is not running"; return 0; fi
  if docker volume inspect "${project}_$1" >/dev/null 2>&1; then echo "volume ${project}_$1"; fi
  return 0
}
mongo_dir=$(lastval MONGO_DATA_DIR "${FILES[@]}")
mongo_dir=${mongo_dir:-./.data/mongo}
case "$mongo_dir" in
  */*) mongo_data=
       if [ -d "$mongo_dir" ] && { [ ! -r "$mongo_dir" ] || [ -n "$(ls -A "$mongo_dir" 2>/dev/null)" ]; }; then
         mongo_data="the folder $mongo_dir"
       fi ;;
  *) mongo_data=$(volume "$mongo_dir") ;;
esac
id_data=$(volume pacod-postgres-data)

# ---- the secrets this machine lacks ----------------------------------------------------------------
# KEY:kind:guard -- guard names the data the value is bound to once it exists.
SECRETS="MONGO_ROOT_PASSWORD:hex24:mongo MONGO_PASSWORD:hex24:mongo
PID_PG_SUPERUSER_PASSWORD:hex16:id PID_DB_PASSWORD:hex16:id PID_KEK:b64:id PID_TOKEN_PEPPER:b64:id
CMS_ID_STUDIO_SECRET:hex24:id CMS_ID_SERVICE_SECRET:hex24:id ID_ADMIN_PASSWORD:hex12:id
PACOD_PREVIEW_SECRET:hex32: METRICS_VISITOR_SALT:hex16:"
profiles=${COMPOSE_PROFILES-$(lastval COMPOSE_PROFILES "${FILES[@]}")}
case ",$profiles," in *,dev-gateway,*) SECRETS="$SECRETS PACOD_GW_API_KEY:gw:" ;; esac

# bash 3.2 (macOS) calls an empty array unbound under set -u: expand as ${lines[@]+"${lines[@]}"}.
lines=() blocked=()
for spec in $SECRETS; do
  key=${spec%%:*} rest=${spec#*:}
  kind=${rest%%:*} guard=${rest#*:}
  named "$key" && continue
  case "$guard" in
    mongo) bound=$mongo_data ;;
    id) bound=$id_data ;;
    *) bound= ;;
  esac
  if [ -n "$bound" ]; then blocked+=("$key ($bound)"); continue; fi
  lines+=("$key=$(generate "$kind")")
done

if [ ${#blocked[@]} -gt 0 ]; then
  echo "init: these secrets are missing, and data that depends on them already exists:" >&2
  printf '  %s\n' "${blocked[@]}" >&2
  cat >&2 <<END
A new value would not match it: MongoDB keeps the passwords of its first start, and pacod-id's
database and sealed data the values they were created with. Put the old values back into .env (a
backup of it, or the machine's .env-local). Or, on a machine whose data may go, \`make destroy\`
and start again.
Nothing was written.
END
  exit 1
fi

umask 077
stamp=$(date +%Y-%m-%d)

# ---- an existing .env: add what it lacks -------------------------------------------------------------
if [ -f .env ]; then
  if [ ${#lines[@]} -eq 0 ]; then
    echo "init: .env (ENV=$ENV_NAME) already has every secret."
    exit 0
  fi
  { echo; echo "# ---- secrets added by make init on $stamp ----------------------------------------"
    printf '%s\n' "${lines[@]}"; } >> .env
  echo "init: added to .env: $(printf '%s\n' "${lines[@]}" | cut -d= -f1 | paste -sd' ' -)"
  exit 0
fi

# ---- a new .env ------------------------------------------------------------------------------------
{
  cat <<END
# This machine's pacod-cms settings, written by \`make init\` on $stamp. Private: it holds the
# secrets, and back it up -- the database passwords and pacod-id's key cannot be replaced once data
# exists. ENV picks the preset (env/$ENV_NAME.env); what is here wins over it, an optional
# .env-local over both. Every other setting, with what it does: .env.example.

ENV=$ENV_NAME
END
  if [ "$ENV_NAME" != dev ]; then
    cat <<END

# ---- addresses ---------------------------------------------------------------------------------------
# DNS for both must point at this server before \`make up\`: Caddy obtains their certificates then.
STUDIO_HOST=$STUDIO_HOST_ARG
ID_HOST=$ID_HOST_ARG
# New websites answer at <id>.<PACOD_SITE_DOMAIN> first; blank, their creator names an address.
PACOD_SITE_DOMAIN=$SITE_DOMAIN_ARG
END
    if [ "$ENV_NAME" = stage ]; then
      cat <<END
# The demo websites, created once on the first start: point these at the server too.
DEMO_HOSTS=demo.$SITE_DOMAIN_ARG
DEMO_MINIMAL_HOSTS=minimal.$SITE_DOMAIN_ARG
DEMO_SPACE_HOSTS=space.$SITE_DOMAIN_ARG
END
    fi
    if [ -n "$EMAIL_ARG" ]; then
      cat <<END
# The operator: the first account's sign-in (a platform admin) and the certificate authority's notices.
ID_ADMIN_EMAIL=$EMAIL_ARG
CADDY_GLOBAL_OPTIONS=email $EMAIL_ARG
END
    fi
  fi
  if [ "$ENV_NAME" = prod ]; then
    cat <<END

# ---- mail: required -------------------------------------------------------------------------------
# pacod-id sends confirmations, invitations and password resets through this relay; \`make check\`
# refuses to start until it is named. TLS: starttls, tls or none.
PID_SMTP_HOST=
PID_SMTP_PORT=587
PID_SMTP_TLS=starttls
# PID_SMTP_USERNAME=
# PID_SMTP_PASSWORD=
# The sender: an address on a domain the relay may send for.
ID_MAIL_FROM=

# ---- AI, optional --------------------------------------------------------------------------------
# A real pacod-gw; without both, AI is "not set up".
# PACOD_GW_URL=https://gw.example.com
# PACOD_GW_API_KEY=
END
  fi
  cat <<END

# ---- secrets, generated -----------------------------------------------------------------------------
END
  [ ${#lines[@]} -eq 0 ] || printf '%s\n' "${lines[@]}"
} > .env.tmp
mv .env.tmp .env
echo "init: wrote .env for ENV=$ENV_NAME (${#lines[@]} secrets generated, none printed)."
case "$ENV_NAME" in
  dev) echo "next: make up" ;;
  stage) echo "next: point DNS for $STUDIO_HOST_ARG, $ID_HOST_ARG, demo.$SITE_DOMAIN_ARG and minimal.$SITE_DOMAIN_ARG at this server, then make up" ;;
  prod) echo "next: fill in the mail block in .env, point DNS for $STUDIO_HOST_ARG and $ID_HOST_ARG at this server, then make up" ;;
esac
