# Changelog

## 1.6.2

- Fix Danielspringer and Mirror downloads returning 404 on OPPO's CN manual CDN.
  Resolve the same package on the official automatic CDN and verify its size and
  checksum for checks, copied links, downloads, and link refreshes.
- Read Danielspringer's optional catalog size and MD5; support the website's
  current lazy link resolver and retain its cookie session. Avoid HTML requests
  when the API is rate limited, forbidden, or unavailable.
- Renew rejected Mirror tokens once, stop reusing expired tokens, and keep
  mirror credentials at the configured origin.
- Preserve download progress on link refresh, share failed refresh attempts,
  propagate cancellation, and truncate old files before overwriting them.
- Start foreground work before network resolution; preserve picker selections
  and check diagnostics across activity recreation. Stop foreground work after
  transfers and verification finish.
- Add regression and file-download integration tests to the release workflow.

Verified on 2026-09-06: all three backends passed live lookup and range checks.
The complete PLK110_16.0.10.500(CN01) package downloaded successfully with the
actual download engine: 9,146,672,362 bytes, MD5
`2cbe1af4dece932fff62c81f5e6dbb68`.
