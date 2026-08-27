#!/usr/bin/env bash
# Serve the built apk to the tv's in-app updater.
#
# Caddy rather than `python3 -m http.server`, for two reasons that both showed
# up in practice: the stdlib server is single threaded, so one stalled client
# wedges every other request; and it does not answer Range requests, which
# Android's DownloadManager uses for a 40MB file. Between them, an update would
# start and then hang with no error anywhere.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${SERVE_PORT:-8088}"
SERVE="$HERE/serve"
RUN="$HERE/serve/.caddy"

[ -f "$SERVE/smarttube.apk" ] || { echo "nothing built yet: run tools/build.sh first" >&2; exit 1; }

mkdir -p "$RUN"
cat > "$RUN/Caddyfile" <<CADDY
{
	admin off
	auto_https off
}

:$PORT {
	root * $SERVE
	file_server browse
	log {
		output file $RUN/access.log
		format console
	}
}
CADDY

echo "serving $SERVE on :$PORT"
exec nix-shell -p caddy --run "caddy run --config '$RUN/Caddyfile' --adapter caddyfile"
