package com.desmond.ofd.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Expiry parsing across the four CDN edges OPPO actually redirects to. Values are real
 * samples captured from live `downloadCheck` redirects.
 */
class FirmwareRulesTest {

    @Test fun parses_aws_style_expires_from_cn_edge() {
        val url = "https://gauss-compota-c-cn.allawnfs.com/a/b.zip" +
            "?sign=x&t=6a65cabb&AWSAccessKeyId=key&Expires=1785057731&Signature=sig"
        assertEquals(1785057731L, parseFirmwareUrlExpiresEpochSeconds(url))
    }

    @Test fun parses_oss_style_expires_from_eu_edge() {
        // EU/GL/IN edges sign Alibaba-OSS style; the old parser only knew `Expires` and so
        // never showed a countdown outside CN.
        val url = "https://gauss-compota-c-fr.allawnofs.com/a/b.zip" +
            "?sign=x&t=6a65cf6c&x-oss-signature=sig&x-oss-signature-version=OSS2" +
            "&x-oss-expires=1785058932&x-oss-access-key-id=key"
        assertEquals(1785058932L, parseFirmwareUrlExpiresEpochSeconds(url))
    }

    @Test fun parameter_name_match_is_case_insensitive() {
        // HttpUrl.queryParameter is case-sensitive; the CDNs are not consistent.
        assertEquals(1785058932L, parseFirmwareUrlExpiresEpochSeconds("https://e.com/a?EXPIRES=1785058932"))
        assertEquals(1785058932L, parseFirmwareUrlExpiresEpochSeconds("https://e.com/a?X-OSS-Expires=1785058932"))
    }

    @Test fun returns_null_when_edge_carries_no_expiry() {
        // NA redirects to redirector.gvt1.com, which signs nothing.
        assertNull(parseFirmwareUrlExpiresEpochSeconds("https://redirector.gvt1.com/a/b.zip"))
    }

    @Test fun rejects_relative_lifetime_masquerading_as_epoch() {
        // Some signing schemes put a duration in the same parameter name. Accepting `3600`
        // would render a 1970 date and make every link look permanently expired.
        assertNull(parseFirmwareUrlExpiresEpochSeconds("https://e.com/a?x-oss-expires=3600"))
        assertNull(parseFirmwareUrlExpiresEpochSeconds("https://e.com/a?Expires=900"))
    }

    @Test fun returns_null_for_unparseable_inputs() {
        assertNull(parseFirmwareUrlExpiresEpochSeconds("not a url"))
        assertNull(parseFirmwareUrlExpiresEpochSeconds(""))
        assertNull(parseFirmwareUrlExpiresEpochSeconds("https://e.com/a?Expires=notanumber"))
    }
}
