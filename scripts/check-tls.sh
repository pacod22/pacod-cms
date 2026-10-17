#!/usr/bin/env bash
#
# Validates a SUPPLIED certificate pair -- SSL_CERT_FILE and SSL_KEY_FILE -- before Caddy is
# started with it. Without one, Caddy obtains every certificate itself and there is nothing to
# check.
#
# Without this, a mismatched pair surfaces only as a Caddy container that will not start, its log
# saying the key does not match the certificate. The usual cause is copying a renewed
# fullchain.pem without the privkey.pem generated alongside it -- certbot mints a NEW keypair on
# renewal unless --reuse-key was used, so the previous key stops matching.
set -uo pipefail

CERT="${SSL_CERT_FILE:-}"
KEY="${SSL_KEY_FILE:-}"

if [[ -z "$CERT" && -z "$KEY" ]]; then
    echo "TLS preflight: no certificate pair supplied -- Caddy obtains every certificate."
    exit 0
fi
if [[ -z "$CERT" || -z "$KEY" ]]; then
    echo "TLS preflight FAILED: SSL_CERT_FILE and SSL_KEY_FILE go together -- set both, or neither." >&2
    exit 1
fi

fail() { echo "  ✗ $*" >&2; FAILED=1; }
ok()   { echo "  ✓ $*"; }

FAILED=0
echo "TLS preflight"
echo "  cert: $CERT"
echo "  key : $KEY"
echo

[[ -r "$CERT" ]] || { fail "certificate not readable"; }
[[ -r "$KEY"  ]] || { fail "private key not readable"; }
if [[ $FAILED -eq 1 ]]; then
    echo
    echo "  SSL_CERT_FILE / SSL_KEY_FILE are host paths, set in .env (or .env-local); unset both and"
    echo "  Caddy obtains the certificates itself."
    exit 1
fi

# --- the pair -----------------------------------------------------------------
cert_pub=$(openssl x509 -in "$CERT" -noout -pubkey 2>/dev/null | openssl md5)
key_pub=$(openssl pkey -in "$KEY" -pubout 2>/dev/null | openssl md5)

if [[ -z "$key_pub" || "$key_pub" == *"d41d8cd98f00b204e9800998ecf8427e"* ]]; then
    fail "could not read the private key (wrong format, or encrypted with a passphrase)"
elif [[ "$cert_pub" == "$key_pub" ]]; then
    ok "certificate and key are a matching pair"
else
    fail "certificate and key DO NOT match — Caddy will refuse to start"
    echo "      cert public key: ${cert_pub##* }"
    echo "      key  public key: ${key_pub##* }"
    echo "      Copy the privkey.pem generated alongside this certificate."
fi

# --- chain --------------------------------------------------------------------
n=$(grep -c "BEGIN CERTIFICATE" "$CERT" 2>/dev/null || echo 0)
if [[ "$n" -ge 2 ]]; then
    ok "full chain present ($n certificates, leaf first)"
else
    echo "  ! only $n certificate in the file — clients without the intermediate will fail to verify"
    echo "    use certbot's fullchain.pem, not cert.pem"
fi

# --- validity -----------------------------------------------------------------
if ! openssl x509 -in "$CERT" -noout -checkend 0 >/dev/null 2>&1; then
    fail "certificate has EXPIRED ($(openssl x509 -in "$CERT" -noout -enddate | cut -d= -f2))"
elif ! openssl x509 -in "$CERT" -noout -checkend 1209600 >/dev/null 2>&1; then
    echo "  ! certificate expires within 14 days ($(openssl x509 -in "$CERT" -noout -enddate | cut -d= -f2))"
else
    ok "certificate valid until $(openssl x509 -in "$CERT" -noout -enddate | cut -d= -f2)"
fi

# --- names --------------------------------------------------------------------
sans=$(openssl x509 -in "$CERT" -noout -ext subjectAltName 2>/dev/null | tail -1 | tr -d ' ')
[[ -n "$sans" ]] && echo "  · names: ${sans//DNS:/}"

# --- key permissions ----------------------------------------------------------
perms=$(stat -f "%Lp" "$KEY" 2>/dev/null || stat -c "%a" "$KEY" 2>/dev/null)
if [[ -n "$perms" && "$perms" != "600" && "$perms" != "400" ]]; then
    echo "  ! private key is mode $perms; tighten it with: chmod 600 '$KEY'"
fi

echo
[[ $FAILED -eq 0 ]] && echo "TLS preflight passed." || echo "TLS preflight FAILED."
exit $FAILED
