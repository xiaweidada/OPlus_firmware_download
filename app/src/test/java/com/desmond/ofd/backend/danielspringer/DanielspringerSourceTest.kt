package com.desmond.ofd.backend.danielspringer

import com.desmond.ofd.backend.realmeota.data.Region
import com.desmond.ofd.firmware.FirmwareUrlProbe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanielspringerSourceTest {
    @Test fun retains_the_catalog_md5_when_the_cdn_omits_it() = runBlocking {
        val result = source(cdnSize = SIZE, cdnMd5 = null).latest("PLK110", Region.CN)
        assertTrue(result is DanielspringerLookup.Found)
        assertEquals(MD5, (result as DanielspringerLookup.Found).md5)
    }

    @Test fun verifies_the_cdn_size_against_the_catalog() = runBlocking {
        val result = source(cdnSize = SIZE + 1, cdnMd5 = MD5).latest("PLK110", Region.CN)
        assertTrue(result is DanielspringerLookup.Failed)
        assertTrue((result as DanielspringerLookup.Failed).detail.contains("Size mismatch"))
    }

    @Test fun rejects_a_cdn_checksum_that_disagrees_with_the_catalog() = runBlocking {
        val result = source(cdnSize = SIZE, cdnMd5 = "00000000000000000000000000000000")
            .latest("PLK110", Region.CN)
        assertTrue(result is DanielspringerLookup.Failed)
    }

    private fun source(cdnSize: Long, cdnMd5: String?): DanielspringerSource {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
            if (request.url.encodedPath == "/api/ota.php") {
                response.code(200).body(
                    """{"releases":[{"model":"PLK110","region":"CN","version":"PLK110_16.0.10.500(CN01)",
                      "source_url":"https://example.org/file.zip","size":$SIZE,"md5":"$MD5"}]}""".toResponseBody(),
                )
            } else {
                response.code(206).header("Content-Range", "bytes 0-0/$cdnSize").body("x".toResponseBody())
                if (cdnMd5 != null) response.header("x-amz-meta-filemd5", cdnMd5)
            }
            response.build()
        }.build()
        return DanielspringerSource(
            api = DanielspringerApi(httpClient = client),
            probe = FirmwareUrlProbe(client),
            breaker = ScrapeBreaker(object : BreakerStore {
                override fun getLong(key: String, fallback: Long) = fallback
                override fun getInt(key: String, fallback: Int) = fallback
                override fun putLong(key: String, value: Long) = Unit
                override fun putInt(key: String, value: Int) = Unit
                override fun remove(keys: List<String>) = Unit
            }),
        )
    }

    private companion object {
        const val SIZE = 9146672362L
        const val MD5 = "2cbe1af4dece932fff62c81f5e6dbb68"
    }
}
