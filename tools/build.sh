#!/usr/bin/env bash
# Build a signed apk and publish it where the tv's updater will find it.
#
# Every deployment-specific value is read off the host that actually runs the
# services and passed straight to gradle, so none of them are ever written down
# here or in git. The certificate pins come from the certificates themselves,
# so a reissued certificate needs no edit anywhere -- just another build.
#
# Building and serving are deliberately separate: this script is run under a
# timeout often enough that having it also *be* the server meant the server
# died with it, which looks from the device like an update source that works
# and then does not.
#
# Releases are dated rather than numbered. Nothing here tracks upstream's
# version, and a date says the one thing worth knowing about a build of a fork:
# how old it is.
#
#   versionName   32.31-2026.08.28
#   versionCode   202608280          YYYYMMDD plus a same-day counter
#
# versionCode has to be an increasing int; YYYYMMDDn stays inside int range
# until the year 2147 and sorts correctly for free.
#
# Diagnostics are OFF unless DIAG_LEVEL is set. When it is unset the endpoint,
# token and pin are not passed at all, so a release build carries no collector
# address and no credential for one -- the reporting code compiles to something
# that can never fire.
#
# Usage: tools/build.sh [same-day-counter]        # dated release
#        DIAG_LEVEL=basic tools/build.sh 1        # same, with reporting on
set -euo pipefail

SERIAL="${1:-0}"
CODE="$(date +%Y%m%d)$SERIAL"
SUFFIX="-$(date +%Y.%m.%d)"

HERE="$(cd "$(dirname "$0")/.." && pwd)"
# No defaults: these describe somebody's own network, and a fork's build script
# is not the place to write one down. Set them in the environment.
HOST="${SVC_HOST:?set SVC_HOST to the ssh target that runs the services}"
LAN_IP="${SVC_LAN_IP:?set SVC_LAN_IP to the address the tv reaches it on}"
# The update source lives on the same always-on box as the services, not on
# whatever laptop did the build. A laptop's dhcp address changes, and an address
# with no host behind it is worse than one that refuses: the connect hangs
# rather than failing, and SmartTube's update check has no timeout, so the
# browse screen spins until somebody force-stops the app.
# Two different names for the same box, on purpose. SERVE_SSH is how *this*
# machine reaches it to upload; SERVE_HOST is what gets compiled into the app,
# so it has to be something the television can resolve and route to -- an ssh
# alias is neither.
SERVE_SSH="${SERVE_SSH:-$HOST}"
SERVE_HOST="${SERVE_HOST:-$LAN_IP}"
SERVE_PORT="${SERVE_PORT:-8088}"
SERVE_DIR="${SERVE_DIR:-/var/lib/smarttube-updatesvc}"
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
DIAG_TOKEN=""
DIAG_PIN=""
if [ -n "${DIAG_LEVEL:-}" ]; then
    # Only read when they are going to be used. A release build should not so
    # much as fetch the collector's credentials, let alone carry them.
    DIAG_TOKEN="$(ssh "$HOST" 'sudo cat /run/secrets/smarttube-log-token')"
    DIAG_PIN="$(remote_pin 'sudo openssl x509 -noout -fingerprint -sha256 \
        -in /var/lib/smarttube-logsvc/logs.crt')"
    for v in DIAG_TOKEN DIAG_PIN; do
        [ -n "${!v}" ] || { echo "$v came back empty" >&2; exit 1; }
    done
fi

for v in COOKIE_TOKEN COOKIE_PIN; do
    [ -n "${!v}" ] || { echo "$v came back empty" >&2; exit 1; }
done
echo "    cookie pin ${COOKIE_PIN:0:16}…"

echo "==> building"
cd "$HERE"
DIAG_ARGS=""
if [ -n "${DIAG_LEVEL:-}" ]; then
    echo "    diagnostics: $DIAG_LEVEL"
    DIAG_ARGS="-PdiagEndpoint='https://$LAN_IP:5568/logs' \
        -PdiagToken='$DIAG_TOKEN' -PdiagPin='$DIAG_PIN' -PdiagLevel='$DIAG_LEVEL'"
else
    echo "    diagnostics: off (no collector address or token compiled in)"
fi

nix-shell tools/android-shell.nix --run "./gradlew --no-daemon assembleStbetaRelease \
    -PcookieAuthEndpoint='https://$LAN_IP:5567/' \
    -PcookieAuthToken='$COOKIE_TOKEN' \
    -PcookieAuthPin='$COOKIE_PIN' \
    $DIAG_ARGS \
    -PupdateUrl='http://$SERVE_HOST:$SERVE_PORT/manifest.json' \
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
  "package": { "downloadUrl": "http://$SERVE_HOST:$SERVE_PORT/smarttube.apk" },
  "$VERSION": { "versionCode": $CODE, "changelog": ["local build"] }
}
JSON

echo "==> uploading to $SERVE_SSH"
# The apk first, the manifest second: the manifest is what makes the device go
# looking, so publishing it before the file it points at is a window where the
# updater finds a version it cannot download.
scp -q serve/smarttube.apk "$SERVE_SSH:$SERVE_DIR/smarttube.apk.part"
# shellcheck disable=SC2029  # $SERVE_DIR is ours and must expand here
ssh "$SERVE_SSH" "mv '$SERVE_DIR/smarttube.apk.part' '$SERVE_DIR/smarttube.apk'"
scp -q serve/manifest.json "$SERVE_SSH:$SERVE_DIR/manifest.json"

echo "==> published $VERSION (versionCode $CODE)"
curl -fsS --max-time 10 "http://$SERVE_HOST:$SERVE_PORT/manifest.json" | sed 's/^/    /'
echo "    on the tv: Settings, About, Check for updates"
