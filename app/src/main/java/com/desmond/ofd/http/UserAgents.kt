package com.desmond.ofd.http

// A real Chromium UA: some firmware CDNs/WAFs treat OkHttp's default UA differently.
internal const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

// UA for firmware CDN requests. The User-Agent is NOT what OPPO's anti-leech checks
// (verified: com.oplus.ota / Chrome / empty all behave the same) — see FIRMWARE_ID_HEADER
// below for the header that actually gates downloads. A phone-like UA is kept only to look
// unremarkable to CDNs/WAFs.
internal const val FIRMWARE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

// OPPO/ColorOS firmware CDN anti-leech gate. The `downloadCheck` endpoint on
// `component-ota-*.allawntech.com` answers HTTP 200 `{"responseCode":2306}` (withholding the
// 302 to the real CDN) unless the caller identifies as the stock updater via this header.
// The stock com.oplus.ota app sends `userId: oplus-ota|<its versionCode>`. Empirically the
// server validates ONLY the `oplus-ota|` prefix — the value after the pipe is ignored (a
// random number, "banana", or empty all pass; anything without the prefix gets 2309) — and
// it is unrelated to any Heytap account, so a hardcoded constant is correct and stable.
// Harmless on non-OPPO hosts (danielspringer's S3 / the gauss CDN ignore unknown headers).
internal const val FIRMWARE_ID_HEADER = "userId"
internal const val FIRMWARE_ID_VALUE = "oplus-ota|16001011"
