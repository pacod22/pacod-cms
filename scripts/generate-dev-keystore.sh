#!/usr/bin/env bash
#
# Generates a SELF-SIGNED keystore for local development only.
#
# Never commit the output, and never use it in production: browsers will reject it, and a
# keystore that lives in the repository is a published private key.
#
# For production, obtain a real certificate (from Let's Encrypt, for example) and
# supply it at deploy time via the SSL_KEYSTORE / SSL_KEYSTORE_PASSWORD environment
# variables. It must never be baked into the jar.
set -euo pipefail

OUT="${1:-config/keystore.p12}"
ALIAS="${KEY_ALIAS:-pacod-cms}"
PASSWORD="${SSL_KEYSTORE_PASSWORD:-}"

if [[ -z "$PASSWORD" ]]; then
  PASSWORD="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)"
  echo "Generated a random keystore password. Store it somewhere safe:"
  echo
  echo "    export SSL_KEYSTORE_PASSWORD='$PASSWORD'"
  echo
fi

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"

keytool -genkeypair \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 825 \
  -storetype PKCS12 \
  -keystore "$OUT" \
  -storepass "$PASSWORD" \
  -dname "CN=localhost, O=pacod-cms development" \
  -ext "SAN=dns:localhost,ip:127.0.0.1"

echo "Wrote self-signed development keystore to $OUT (alias: $ALIAS)"
echo "Run with:  SSL_ENABLED=true SSL_KEYSTORE=file:$OUT SSL_KEYSTORE_PASSWORD='<password>' ..."
