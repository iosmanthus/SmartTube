# Deployment

This fork plays YouTube on a device whose egress googlevideo will not serve
without a po token. Upstream cannot get one there, for a structural reason:

- `PoTokenGate` mints tokens only for the web client family;
- `isAuthSupported` covers only the tv family.

So a client can be authorized *or* carry a token, never both -- and where a
token is demanded, that means it can be authorized or playable, never both. A
browser has both at once because it talks to InnerTube as WEB *while signed
in*, using cookies rather than an oauth bearer. `WEB_AUTH` and
`WEB_EMBED_AUTH` reproduce that.

Measured on the device: an authorized `TV_DOWNGRADED` request with a correct
`n` parameter and `pot=null` still 403s on every chunk, while a web client
carrying a token does not.

## What runs where

Everything long-lived is on the always-on box (the mac mini), managed by nix in
`~/nixos-config`. Nothing runs on the machine that does the builds.

| service | port | launchd kind | why |
|---|---|---|---|
| `smarttube-cookiesvc` | 5567 | **user agent** | a Chromium cookie store is encrypted with a key from the login keychain, which a daemon cannot unlock -- run as a daemon it comes up signed out, every time |
| `smarttube-cookie-browser` | — | **user agent** | it *is* that browser |
| `smarttube-logsvc` | 5568 | daemon | touches no browser and no keychain |
| `smarttube-updatesvc` | 8088 | daemon (caddy) | an update source that only works while somebody is logged in fails exactly when it is needed |

The two user agents need the box to be logged in. Locking the screen is fine --
a lock is not a logout -- but a reboot with no automatic login leaves them
stopped until somebody signs in.

`smarttube-updatesvc` is caddy rather than something hand-written because the
two things that broke a hand-written server here are the two a real one gets
right: Range requests, which Android's DownloadManager uses for a 40MB apk, and
serving concurrently, so one stalled client cannot wedge the rest.

## The session

Sign in **on that machine**, once:

```
smarttube-cookie-login
```

It stops the headless holder, opens a browser window on the box's display, and
puts the holder back when you quit (Cmd-Q -- closing the window leaves the app
running and holding the profile).

Do not copy cookies in from another browser. A copied session and its original
share one rotation chain for `__Secure-*PSIDTS`, and whichever copy rotates
from a value the server has already retired is signed out. Two browsers that
were each *logged in separately* are two independent sessions and coexist
indefinitely, which is why signing in on a phone does not disturb a laptop.

The service reads cookies live over CDP on every request and never caches them,
because a snapshot goes stale as the session rotates. It navigates to
youtube.com every 30 minutes, because rotation is driven by traffic rather than
by a timer, and a browser parked on about:blank quietly ages out.

**The session expires around 2027-02-23** -- 180 days from the login. Rotation
extends `__Secure-*PSIDTS` but not `SID`/`SAPISID`, which are what actually
authenticate, so keepalive prevents idle death but not expiry. Nothing warns
about this; re-run `smarttube-cookie-login` before then. It can also end early
(sign-out everywhere, password change), which shows up as `signed_out`.

## Building

```
tools/build.sh              # dated release, diagnostics off
tools/build.sh 1            # second release the same day
DIAG_LEVEL=basic tools/build.sh    # with reporting on
```

Reads the endpoint, token and certificate pin off the host that runs the
services and passes them straight to gradle, so none of them are typed by hand
or written down. The pins come from the certificates themselves: reissuing one
needs no edit anywhere, just another build.

Signs with `../keys/st-test.jks` (v1+v2+v3 -- CI produces unsigned apks, and
from API 30 a v1-only signature is rejected), uploads to the update service,
and publishes the manifest *after* the apk so there is no window where the
device finds a version it cannot download.

Nothing deployment-specific is in git. Empty defaults leave both cookie auth
and diagnostics switched off, so a build that reaches a device it was not made
for does nothing rather than reaching for a session.

## Diagnostics

Off by default, and a release build carries no collector address or credential
at all. Turn on with `DIAG_LEVEL`:

| level | contents |
|---|---|
| `basic` | client attempts, po token *length*, playability, 403s, stage timings |
| `verbose` | request paths, the n parameter before and after |
| `full` | media urls -- signed and directly fetchable by anyone holding them |

Session cookies have exactly one reporting path and it takes a length rather
than a value, at every level.

Events land as json lines in `/var/lib/smarttube-logsvc/events.jsonl`.

## Known, unfixed

- **~1s per video re-creating the V8 engine.** `V8ChallengeProvider.runJsRuntime`
  calls `shutdownIfNeeded()` after every solve, and that method disposes
  unconditionally, so each video pays for creating a runtime and reloading
  polyfill + meriyah + astring + the solver. Measured at 947-1064ms, independent
  of format count. Upstream has a `warmup()` for hot start; the disposal is
  guarded by comments about j2v8 thread affinity, so keeping it warm needs the
  solve thread identified first.
- **No warning before the session expires.**
- **`WEB_EMBED_AUTH` sits late in the walk order**, so a build without cookies
  reaches it only after a full lap. Harmless while cookies are configured.
- **`firstPlayable` accepts any response that is merely not unplayable**, which
  is how a tv client's dead stream used to win. The cookie-auth path no longer
  walks, so this only affects builds without cookies.
- **Subtitle track selection is not restored** across videos
  (`TrackSelectorManager: Can't create selection`). Cosmetic.
