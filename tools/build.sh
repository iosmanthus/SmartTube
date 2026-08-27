#!/usr/bin/env bash
# Build a signed apk and publish it where the tv's updater will find it.
#
# Every deployment-specific value is read off the host that actually runs the
# services and passed straight to gradle, so none of them are ever written down
# here or in git. The certificate pins come from the certificates themselves,
# so a reissued certificate needs no edit anywhere -- just another build.
#
# Usage: tools/build.sh <versionCode> [suffix]
set -euo pipefail

CODE="${1:?usage: build.sh <versionCode> [suffix]}"
SUFFIX="${2:--local}"

HERE="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${SVC_HOST:-macmini-home}"
LAN_IP="${SVC_LAN_IP:-192.168.31.102}"
SERVE_IP="${SERVE_IP:-192.168.31.88}"
SERVE_PORT="${SERVE_PORT:-8088}"
# shellcheck disable=SC2029  # the argument is meant to expand on this side
remote_pin() {
    ssh "$HOST" "$1" | sed 's/.*=//' | tr -d ':'
}

# The cookie service is a launchd *user agent* -- a Chromium profile needs the
# login keychain -- so its state sits under the user's home, and $HOME has to
# expand on the remote side. The collector is a plain daemon, so its state is
# under /var/lib and root-owned.
echo "==> reading service configuration from $HOST"
COOKIE_TOKEN="$(ssh "$HOST" 'cat /run/secrets/smarttube-cookie-token')"
# shellcheck disable=SC2016  # $HOME must stay literal and expand remotely
COOKIE_PIN="$(remote_pin 'openssl x509 -noout -fingerprint -sha256 \
    -in "$HOME/Library/Application Support/smarttube-cookiesvc-state/cookies.crt"')"
DIAG_TOKEN="$(ssh "$HOST" 'sudo cat /run/secrets/smarttube-log-token')"
DIAG_PIN="$(remote_pin 'sudo openssl x509 -noout -fingerprint -sha256 \
    -in /var/lib/smarttube-logsvc/logs.crt')"

for v in COOKIE_TOKEN COOKIE_PIN DIAG_TOKEN DIAG_PIN; do
    [ -n "${!v}" ] || { echo "$v came back empty" >&2; exit 1; }
done
echo "    cookie pin ${COOKIE_PIN:0:16}…  diag pin ${DIAG_PIN:0:16}…"

echo "==> building"
cd "$HERE"
nix-shell tools/android-shell.nix --run "./gradlew --no-daemon assembleStbetaRelease \
    -PcookieAuthEndpoint='https://$LAN_IP:5567/' \
    -PcookieAuthToken='$COOKIE_TOKEN' \
    -PcookieAuthPin='$COOKIE_PIN' \
    -PdiagEndpoint='https://$LAN_IP:5568/logs' \
    -PdiagToken='$DIAG_TOKEN' \
    -PdiagPin='$DIAG_PIN' \
    -PdiagLevel='${DIAG_LEVEL:-off}' \
    -PupdateUrl='http://$SERVE_IP:$SERVE_PORT/manifest.json' \
    -PbuildVersionCode='$CODE' \
    -PbuildVersionSuffix='$SUFFIX'"

APK="$(ls smarttubetv/build/outputs/apk/stbeta/release/*"$SUFFIX"_universal.apk)"
NAME="$(basename "$APK")"

echo "==> signing"
# CI produces unsigned apks (it only signs when a SIGNING_KEY secret exists, and
# this fork has none), and Android rejects those outright. v1+v2+v3 because the
# stbeta flavor overrides targetSdk to 34, and from API 30 a v1-only signature
# is not accepted.
mkdir -p serve
nix-shell -p apksigner --run "apksigner sign \
    --ks '$HERE/../keys/st-test.jks' --ks-pass pass:smarttube --ks-key-alias sttest \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out serve/smarttube.apk '$APK'"

VERSION="$(echo "$NAME" | sed 's/^SmartTube_beta_//; s/_universal\.apk$//')"
cat > serve/manifest.json <<JSON
{
  "package": { "downloadUrl": "http://$SERVE_IP:$SERVE_PORT/smarttube.apk" },
  "$VERSION": { "versionCode": $CODE, "changelog": ["local build"] }
}
JSON

echo "==> serving on :$SERVE_PORT — on the tv: Settings, About, Check for updates"
cd serve && exec python3 -m http.server "$SERVE_PORT" --bind 0.0.0.0
