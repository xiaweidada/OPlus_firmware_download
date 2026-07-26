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

The API returns a `source_url` — an OPPO `/downloadCheck` gate URL — but neither
size nor MD5, so the app probes that URL itself for both.

The older HTML form is kept as a fallback for the rare model the API's catalog
misses, but it is deliberately hard to reach. Scraping is what the site rate
limits, and its rate limiting escalates from `429` to an IP-level block of the
whole domain, which takes the API down with it. So the fallback runs only when
the API answered and had nothing usable — never when the site could not be
reached — and a persisted circuit breaker keeps it off after trouble.

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
one has expired. Bytes already written are kept.

The downloader supports:

- foreground-service download notification
- up to 64 HTTP range connections when supported by the server
- HTTP range downloads when the server supports `206 Partial Content`
- single-thread fallback when range requests are not supported
- MD5 verification when the backend provides an expected MD5
- retry on MD5 mismatch

MD5 is only available for some regions: OPPO's China CDN returns a package MD5,
the EU/global/India edges do not. Without one there is nothing to verify against
and the download completes unverified.

`Copy URL` resolves a fresh link at the moment it is pressed, so what gets
copied is always usable — the countdown shown afterwards belongs to that copied
link, not to the download, which renews itself.

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
