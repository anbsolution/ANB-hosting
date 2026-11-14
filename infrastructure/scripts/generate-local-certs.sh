#!/bin/sh
set -eu

lan_ip="${1:-}"
case "$lan_ip" in
  ''|*[!0-9.]*)
    printf 'Usage: sh infrastructure/scripts/generate-local-certs.sh <server-lan-ipv4>\n' >&2
    exit 2
    ;;
esac

if ! printf '%s\n' "$lan_ip" | awk -F. '
  NF != 4 { exit 1 }
  {
    for (i = 1; i <= NF; i++) {
      if ($i !~ /^[0-9]+$/ || length($i) > 3 || $i + 0 > 255) exit 1
    }
  }
'; then
  printf 'Invalid IPv4 address: %s\n' "$lan_ip" >&2
  exit 2
fi

script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
cert_dir="$script_dir/../local-certs"
umask 077
mkdir -p "$cert_dir"

for name in local-ca.key local-ca.crt server.key server.crt; do
  if [ -e "$cert_dir/$name" ]; then
    printf 'Certificate file already exists: %s\n' "$cert_dir/$name" >&2
    printf 'Back up and remove the local certificate set before regenerating it.\n' >&2
    exit 1
  fi
done

temporary_dir=$(mktemp -d "$cert_dir/.generate.XXXXXX")
cleanup() {
  rm -f \
    "$temporary_dir/local-ca.key" \
    "$temporary_dir/local-ca.crt" \
    "$temporary_dir/server.key" \
    "$temporary_dir/server.crt" \
    "$temporary_dir/server.csr" \
    "$temporary_dir/server.ext" \
    "$temporary_dir/local-ca.srl"
  rmdir "$temporary_dir"
}
trap cleanup 0

openssl req \
  -x509 \
  -newkey rsa:3072 \
  -sha256 \
  -nodes \
  -days 3650 \
  -keyout "$temporary_dir/local-ca.key" \
  -out "$temporary_dir/local-ca.crt" \
  -subj '/CN=ANB Local Network CA' \
  -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -addext 'subjectKeyIdentifier=hash'

openssl req \
  -new \
  -newkey rsa:2048 \
  -nodes \
  -keyout "$temporary_dir/server.key" \
  -out "$temporary_dir/server.csr" \
  -subj "/CN=$lan_ip"

printf '%s\n' \
  'basicConstraints=critical,CA:FALSE' \
  'keyUsage=critical,digitalSignature,keyEncipherment' \
  'extendedKeyUsage=serverAuth' \
  "subjectAltName=IP:$lan_ip" \
  'subjectKeyIdentifier=hash' \
  'authorityKeyIdentifier=keyid,issuer' > "$temporary_dir/server.ext"

openssl x509 \
  -req \
  -in "$temporary_dir/server.csr" \
  -CA "$temporary_dir/local-ca.crt" \
  -CAkey "$temporary_dir/local-ca.key" \
  -CAcreateserial \
  -out "$temporary_dir/server.crt" \
  -days 825 \
  -sha256 \
  -extfile "$temporary_dir/server.ext"

openssl verify -CAfile "$temporary_dir/local-ca.crt" "$temporary_dir/server.crt"
openssl x509 -in "$temporary_dir/server.crt" -noout -checkip "$lan_ip"

mv "$temporary_dir/local-ca.key" "$cert_dir/local-ca.key"
mv "$temporary_dir/local-ca.crt" "$cert_dir/local-ca.crt"
mv "$temporary_dir/server.key" "$cert_dir/server.key"
mv "$temporary_dir/server.crt" "$cert_dir/server.crt"
printf 'Created trusted local HTTPS certificate for %s.\n' "$lan_ip"
