# OPlus Firmware Download

Android app for checking and downloading full OTA firmware packages for OPPO,
realme, and OnePlus devices.

Minimum supported Android version: Android 13.

Release APKs are currently arm64-v8a only.

## Backends

The app queries up to three sources in parallel.

### realme-ota

Website: https://github.com/R0rt1z2/realme-ota

This backend talks to OPPO's OTA endpoint. The app ports the request logic from
`realme-ota` into Kotlin:

- build the OTA request from model, current OTA version, ColorOS version,
  region, and NV ID
- encrypt the request body using the protocol required by the ColorOS version
- send the request to OPPO's update endpoint
- decrypt the response
- read the download URL, size, and MD5 from the returned component packet

If OPPO rejects the supplied OTA version, the app retries with the same
zero-tail OTA version fallback used by `realme-ota`.

### danielspringer.at

Website: https://roms.danielspringer.at/index.php?view=ota

Queried through the site's public JSON API:

```text
GET /api/ota.php?model=<MODEL>&latest=1
```

The `model` filter takes the canonical model code the app already reads from the
device, so no catalog mapping is needed. Responses are cached by `ETag` and
revalidated with `If-None-Match`.

The API returns a `source_url` — an OPPO `/downloadCheck` gate URL — and optional
size and MD5 metadata. The app resolves and probes the link, checks its size and
any CDN checksum against the catalog, and retains the catalog MD5 for final file
verification even when the CDN has no checksum header.

The older HTML form is kept as a fallback for the rare model the API's catalog
misses, but it is deliberately hard to reach. Scraping is what the site rate
limits, and its rate limiting escalates from `429` to an IP-level block of the
whole domain, which takes the API down with it. So the fallback runs only when
the API answered and had nothing usable — never when the site could not be
reached — and a persisted circuit breaker keeps it off after trouble.
The fallback supports both the older page with a ready link and the current
page that prepares the link through a second request in the same cookie session.

### Mirror

An optional secondary source, configured through `local.properties` (or CI
secrets) and disabled entirely when unconfigured. Its catalog is largely
China-market models, so it complements rather than duplicates the other two.
When it is not configured, the app shows only two backends.

## Reading the device

The app reads firmware properties from the phone it runs on:

- `ro.product.name`
- `ro.build.version.ota`
- `ro.build.oplus_nv_id`
- `ro.build.version.realmeui`
- `ro.build.version.oplusrom`
- `ro.build.display.id`

Android does not expose these through normal public SDK APIs, so the app reads
`android.os.SystemProperties` by reflection.

## Version selection

Display versions are compared by their numeric body, ignoring the model prefix
and the region suffix:

```text
PLK110_16.0.7.206(CN01)
PLK110_16.0.5.702(CN01)
```

Here `16.0.7.206` is newer than `16.0.5.702`.

The newest result wins. Because the sources usually agree, most checks are ties,
and ties fall to a fixed precedence:

```text
realme-ota  >  danielspringer.at  >  Mirror
```

So the Mirror is chosen only when it alone has something strictly newer. Two
versions that cannot be parsed are treated as a tie rather than compared as
strings, so precedence decides them instead of alphabetical accident.

## Download behavior

Downloads use Android's document picker, so the user chooses where the ZIP is
saved.

Firmware links are pre-signed and live only minutes, while a full package is
several GB. The downloader therefore holds a *re-resolvable source* rather than
a fixed URL — a gate URL for realme-ota and danielspringer, a device and build
pair for the Mirror — and mints a fresh link when the CDN reports the current
one has expired. Range workers keep bytes already written when refreshing links
or retrying a broken connection.

The CN manual CDN (`gauss-compota-c-cn.allawnfs.com`) sometimes returns `404
NoSuchKey` for a package still present on the official automatic CDN
(`gauss-compotaauto-c-cn.allawnfs.com`). On that specific 404, the shared resolver
tries the same signed object path on the automatic CDN and verifies its size
and checksum. Firmware checks, Copy URL, initial downloads, and in-flight URL
refreshes all use this resolver. A failed probe is reported before writing a
file; it is not interpreted as a server without range support.

Foreground work starts as soon as a save location is selected, including the
Mirror token exchange. It stops when transfers and verification finish, even
when completed cards remain in the Downloads tab.

The downloader supports:

- foreground-service download notification
- up to 64 HTTP range connections when supported by the server
- HTTP range downloads when the server supports `206 Partial Content`
- single-thread fallback when range requests are not supported
- MD5 verification when the backend provides an expected MD5
- retry on MD5 mismatch

MD5 comes from the catalog when available, or from the CDN checksum header.
OPPO's China CDN exposes that header; the EU/global/India edges generally do not.
If neither source supplies a checksum, the download completes unverified.

`Copy URL` resolves and verifies a link when pressed. The countdown shown
afterwards belongs to that copied link. Gate and Mirror downloads renew their
own links; a terminal link from the HTML fallback needs a new firmware check
after expiry.

That countdown varies between runs, which is expected. OPPO's gate signs a link
for 10 minutes but caches and re-serves it for a few minutes before minting the
next one, so a link handed out mid-cache arrives with anywhere from a few
seconds to the full 10 minutes left. Measured on the CN edge:

```text
12:56:05  Expires=13:02:58  413s left   <- new signature
12:56:56  Expires=13:02:58  362s left   <- same URL re-served
12:58:42  Expires=13:08:01  559s left   <- next signature
```

Nothing on the client can change this; the countdown reports what the link
actually has left, which is why it is worth showing at all.

Canceling a download cancels the HTTP calls, removes the download item, removes
the notification, and deletes the partially written local file.

## Diagnostics

A failed download, or a check that found nothing, offers a copy-pasteable report
with the device, the firmware, every backend's result, and the error. It never
contains the Mirror's host, key, email, or tokens, and file paths are reduced to
the storage provider and file name.

## Building

`local.properties` (git-ignored) supplies the SDK location and, optionally, the
Mirror configuration:

```properties
sdk.dir=/path/to/Android/Sdk
mirror.baseUrl=
mirror.key=
mirror.ua=
mirror.email=
```

Leave the `mirror.*` values out to build without that source; everything else
works unchanged.

## Verification

The release workflow runs the unit/integration tests and Android lint before
building and signing the APK:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Tests replay the CN 404 and automatic-CDN response, the site's lazy HTML
resolver, token expiry, and download cancellation. Robolectric integration tests
write and hash complete 100 MiB files to check fallback, refresh, and truncation.

Live checks are opt-in and require the normal Mirror build configuration:

```sh
# Query all three backends and read the first, middle, and last 64 KiB of each package.
OFD_LIVE_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveBackendSmokeTest' --rerun-tasks

# Download the entire current PLK110 package and verify its MD5 (several GB).
# The test always deletes its temporary package afterwards.
OFD_LIVE_DOWNLOAD=1 ./gradlew :app:testDebugUnitTest --tests '*LiveDownloadTest' --rerun-tasks
```
