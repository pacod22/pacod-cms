#!/usr/bin/env bash
#
# Generates a SELF-SIGNED certificate/key pair, for trying a SUPPLIED pair on a developer machine.
#
# A developer machine needs none: Caddyfile.dev serves HTTPS from Caddy's own local CA. A pair
# named by SSL_CERT_FILE and SSL_KEY_FILE is what a deployment uses when it brings a certificate of
# its own -- Caddy then serves it for every name it covers and obtains certificates only for the
# rest -- and this is how to try that path here: SANS= narrows the names, so that some hosts get
# this pair and the others Caddy's.
#
# The companion of scripts/generate-dev-keystore.sh, which does the same job for the Java side.
#
# Browsers will warn on the result -- it is self-signed. Never deploy it: a key that lives in a
# working tree is a published key.
set -euo pipefail

CERT="${1:-./docker/caddy/tls/local-dev.crt}"
KEY="${2:-./docker/caddy/tls/local-dev.key}"
DAYS="${DAYS:-30}"

# The names the local stack is reached by. SANS= replaces them -- with a real hostname, say, that
# an /etc/hosts entry points at 127.0.0.1, so it verifies against this certificate.
SANS="${SANS:-DNS:localhost,DNS:*.localhost,IP:127.0.0.1}"

mkdir -p "$(dirname "$CERT")" "$(dirname "$KEY")"

openssl req -x509 -newkey rsa:2048 -sha256 -days "$DAYS" -nodes \
  -keyout "$KEY" -out "$CERT" \
  -subj "/CN=localhost/O=pacod-cms development" \
  -addext "subjectAltName=$SANS" \
  -addext "basicConstraints=critical,CA:FALSE" \
  -addext "keyUsage=critical,digitalSignature,keyEncipherment" \
  -addext "extendedKeyUsage=serverAuth"

# The preflight warns at anything looser, and it is a private key in a working tree.
chmod 600 "$KEY"

echo
echo "Wrote a self-signed pair valid for $DAYS days:"
echo "    cert: $CERT"
echo "    key : $KEY"
echo
echo "Point Caddy at it by setting these in .env-local -- they are HOST paths, which is what"
echo "that file is for -- and remove them again to go back to Caddy's own certificates:"
echo
echo "    SSL_CERT_FILE=$CERT"
echo "    SSL_KEY_FILE=$KEY"
echo
echo "Then: make check && make caddy-reload"
