package online.timefiles.vantaloom

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LoopbackCapabilityUrlCacheTest {
    private val key = "a".repeat(64)
    private val raw = "http://127.0.0.1:9137/v1/files/raw?path=a%2Fb.mp4&x=1+2&x=%20"
    private val now = 1_800_000_000L

    @Test fun repeatedRendersAcrossSecondsKeepTheExactSignedUrl() {
        val cache = LoopbackCapabilityUrlCache()
        val initial = cache.authorize(raw, 9137, key, now)
        assertEquals(LoopbackCapabilitySigner.authorize(raw, 9137, key, now), initial)
        for (second in 1..120) assertEquals(initial, cache.authorize(raw, 9137, key, now + second))
        assertNotEquals(initial, LoopbackCapabilitySigner.authorize(raw, 9137, key, now + 1))
    }

    @Test fun exactRawPathQueryAndSchemeRemainIndependentAndBytePreserved() {
        val cache = LoopbackCapabilityUrlCache()
        val variants = listOf(raw, raw.replace("b.mp4", "c.mp4"), raw.replace("1+2", "1%202"),
            raw.replace("x=%20", "x=+"), raw.replace("http:", "ws:"), raw + "&seek=2")
        for ((index, url) in variants.withIndex()) {
            val signed = cache.authorize(url, 9137, key, now + index)
            assertEquals(LoopbackCapabilitySigner.authorize(url, 9137, key, now + index), signed)
            assertEquals(signed, cache.authorize(url, 9137, key, now + 60))
        }
    }

    @Test fun samePortKeyRotationAndPortChangesClearTheWholeScope() {
        val cache = LoopbackCapabilityUrlCache()
        val initial = cache.authorize(raw, 9137, key, now)
        val nextKey = "b".repeat(64)
        assertEquals(LoopbackCapabilitySigner.authorize(raw, 9137, nextKey, now + 1),
            cache.authorize(raw, 9137, nextKey, now + 1))
        assertNotEquals(initial, cache.authorize(raw, 9137, key, now + 2))
        val newPortUrl = raw.replace(":9137", ":9138")
        assertEquals(LoopbackCapabilitySigner.authorize(newPortUrl, 9138, key, now + 3),
            cache.authorize(newPortUrl, 9138, key, now + 3))
        assertNull(cache.authorize(raw, 9138, key, now + 4))
        assertEquals(LoopbackCapabilitySigner.authorize(raw, 9137, key, now + 5),
            cache.authorize(raw, 9137, key, now + 5))
    }

    @Test fun expiryMarginAndExpiredEntriesRefreshWithoutSlidingHits() {
        for (elapsed in listOf(43200L - 300, 43200L, 43201L)) {
            val cache = LoopbackCapabilityUrlCache()
            val initial = cache.authorize(raw, 9137, key, now)
            assertEquals(initial, cache.authorize(raw, 9137, key, now + 43200 - 301))
            assertEquals(LoopbackCapabilitySigner.authorize(raw, 9137, key, now + elapsed),
                cache.authorize(raw, 9137, key, now + elapsed))
        }
    }

    @Test fun clockRollbackRespectsGoMaximumFutureExpirationAndOverflowFailsClosed() {
        val cache = LoopbackCapabilityUrlCache()
        val initial = cache.authorize(raw, 9137, key, now)
        assertEquals(initial, cache.authorize(raw, 9137, key, now - 300))
        assertEquals(LoopbackCapabilitySigner.authorize(raw, 9137, key, now - 301),
            cache.authorize(raw, 9137, key, now - 301))
        assertNull(cache.authorize(raw, 9137, key, Long.MAX_VALUE))
    }

    @Test fun invalidOriginsAndReservedParametersRemainRejectedEvenWhenCacheIsWarm() {
        val cache = LoopbackCapabilityUrlCache()
        val signed = cache.authorize(raw, 9137, key, now)!!
        val rejected = listOf(signed, raw.replace("127.0.0.1", "localhost"),
            raw.replace("127.0.0.1", "evil.test"), raw.replace("http:", "https:"),
            raw.replace("127.0.0.1", "user@127.0.0.1"), raw + "&${LoopbackAuth.queryParameter}=bad",
            raw + "&${LoopbackAuth.expirationQueryParameter}=0",
            raw + "&%5f_vantaloom_loopback_token=bad", "bad", "x".repeat(32769))
        for (url in rejected) {
            assertNull(LoopbackCapabilitySigner.authorize(url, 9137, key, now + 1))
            assertNull(cache.authorize(url, 9137, key, now + 1))
        }
        assertNull(cache.authorize(raw, 9137, "bad", now))
        assertNull(cache.authorize(raw, 0, key, now))
    }

    @Test fun leastRecentlyUsedEntriesAreEvictedAndClearDiscardsAllEntries() {
        val cache = LoopbackCapabilityUrlCache(2)
        val a = cache.authorize(raw, 9137, key, now)
        val bRaw = raw + "&b=1"
        cache.authorize(bRaw, 9137, key, now)
        assertEquals(a, cache.authorize(raw, 9137, key, now + 1))
        cache.authorize(raw + "&c=1", 9137, key, now + 1)
        assertEquals(a, cache.authorize(raw, 9137, key, now + 2))
        assertEquals(LoopbackCapabilitySigner.authorize(bRaw, 9137, key, now + 2),
            cache.authorize(bRaw, 9137, key, now + 2))
        cache.clear()
        assertEquals(LoopbackCapabilitySigner.authorize(raw, 9137, key, now + 3),
            cache.authorize(raw, 9137, key, now + 3))
    }

    @Test fun defaultCapacityIsBoundedAt256() {
        val cache = LoopbackCapabilityUrlCache()
        for (index in 0..256) cache.authorize(raw + "&n=$index", 9137, key, now)
        assertEquals(LoopbackCapabilitySigner.authorize(raw + "&n=0", 9137, key, now + 1),
            cache.authorize(raw + "&n=0", 9137, key, now + 1))
    }

    @Test fun concurrentRequestsReuseOneValueAndRotationNeverReturnsAnotherKeysSignature() {
        val cache = LoopbackCapabilityUrlCache()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val initial = cache.authorize(raw, 9137, key, now)
            val repeated = pool.invokeAll((1..200).map { second -> Callable {
                cache.authorize(raw, 9137, key, now + second)
            } })
            for (result in repeated) assertEquals(initial, result.get())
            cache.clear()
            val rotated = pool.invokeAll((1..200).map { index -> Callable {
                val testKey = if (index % 2 == 0) key else "b".repeat(64)
                cache.authorize(raw, 9137, testKey, now + 500) ==
                    LoopbackCapabilitySigner.authorize(raw, 9137, testKey, now + 500)
            } })
            // Begin rotation at a new instant after explicitly invalidating the warmed scope.
            for (result in rotated) assertTrue(result.get())
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
