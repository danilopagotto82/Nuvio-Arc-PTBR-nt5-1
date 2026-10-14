package com.nuvio.tv.core.image

import android.content.Context
import coil3.annotation.InternalCoilApi
import coil3.disk.DiskCache
import coil3.network.CacheNetworkResponse
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.Options
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Path.Companion.toOkioPath
import okio.buffer

class ImageFreshnessTest {
    private val day = 24L * 60 * 60 * 1000
    private val options = Options(mockk<Context>(relaxed = true))

    private fun saved(cacheControl: String, ageMs: Long) = NetworkResponse(
        code = 200,
        requestMillis = System.currentTimeMillis() - ageMs,
        responseMillis = System.currentTimeMillis() - ageMs,
        headers = NetworkHeaders.Builder().set("Cache-Control", cacheControl).build(),
    )

    private suspend fun fresh(response: NetworkResponse, request: NetworkRequest = NetworkRequest("https://img.example/p.jpg")) =
        FreshnessFloorCacheStrategy().read(response, request, options).response != null

    @Test fun floorCoversAWeekFromTheLastDownloadOrCheck() {
        val now = 100 * day
        assertTrue(withinFreshnessFloor(now - 6 * day, now))
        assertTrue(withinFreshnessFloor(now - 7 * day + 1, now))
        assertFalse(withinFreshnessFloor(now - 7 * day, now))
        assertFalse(withinFreshnessFloor(0, now))
        assertTrue(withinFreshnessFloor(now + day, now))
    }

    @Test fun shortServerMaxAgeIsStillFreshWithinAWeek() = runTest {
        assertTrue(fresh(saved("max-age=60", 2 * day)))
        assertTrue(fresh(saved("public, max-age=3600", 6 * day)))
        assertFalse(fresh(saved("max-age=60", 8 * day)))
    }

    @Test fun longerServerRulesStillApplyAfterTheWeek() = runTest {
        assertTrue(fresh(saved("max-age=31536000", 30 * day)))
    }

    @Test fun explicitRevalidationRequestBypassesTheFloor() = runTest {
        val noCache = NetworkRequest(
            url = "https://img.example/p.jpg",
            headers = NetworkHeaders.Builder().set("Cache-Control", "no-cache").build(),
        )
        assertFalse(fresh(saved("max-age=60", 2 * day), noCache))
    }

    @Test fun staleCopyAsksTheServerWithConditions() = runTest {
        val response = NetworkResponse(
            code = 200,
            requestMillis = System.currentTimeMillis() - 9 * day,
            responseMillis = System.currentTimeMillis() - 9 * day,
            headers = NetworkHeaders.Builder().set("Cache-Control", "max-age=60").set("ETag", "\"a\"").build(),
        )
        val result = FreshnessFloorCacheStrategy(CacheControlCacheStrategy()).read(response, NetworkRequest("https://img.example/p.jpg"), options)
        assertNull(result.response)
        assertNotNull(result.request?.headers?.get("If-None-Match"))
    }

    @OptIn(InternalCoilApi::class)
    @Test fun onlyCopiesShownWithoutAnOnlineCheckUseTheDiskLanes() = runTest {
        val dir = kotlin.io.path.createTempDirectory("posters").toFile()
        try {
            val cache = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(1L shl 20).build()
            fun put(key: String, response: NetworkResponse?) {
                val editor = cache.openEditor(key)!!
                cache.fileSystem.write(editor.data) { writeUtf8("jpeg") }
                cache.fileSystem.sink(editor.metadata).buffer().use { sink ->
                    response?.let { CacheNetworkResponse.writeTo(it, sink) }
                }
                editor.commit()
            }
            put("fresh", saved("max-age=60", 3 * day))
            put("stale", saved("max-age=60", 10 * day))
            put("manual", null)
            suspend fun fresh(key: String) = cache.openSnapshot(key)!!.use { savedImageIsFresh(it, cache.fileSystem, key, options) }
            assertTrue(fresh("fresh"))
            assertFalse(fresh("stale"))
            assertTrue(fresh("manual"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
