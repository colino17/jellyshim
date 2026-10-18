package io.github.colino17.jellyshim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PureFunctionsTest {

    // ---- normalizeUrl
    @Test fun normalizeAddsSchemeAndTrimsSlashes() {
        assertEquals("http://192.168.1.5:8096", normalizeUrl(" 192.168.1.5:8096/ "))
        assertEquals("https://jf.example.com", normalizeUrl("https://jf.example.com//"))
    }

    // ---- remainingText
    @Test fun remainingUnderAMinute() = assertEquals("LESS THAN A MINUTE REMAINING", remainingText(59_000))
    @Test fun remainingOneMinute() = assertEquals("1 MINUTE REMAINING", remainingText(60_000))
    @Test fun remainingMinutes() = assertEquals("21 MINUTES REMAINING", remainingText(21 * 60_000L + 1_000))
    @Test fun remainingOneHour() = assertEquals("1 HOUR REMAINING", remainingText(60 * 60_000L))
    @Test fun remainingHourAndMinutes() = assertEquals("1 HOUR 5 MINUTES REMAINING", remainingText(65 * 60_000L))
    @Test fun remainingHoursAndMinutes() = assertEquals("2 HOURS 1 MINUTE REMAINING", remainingText(121 * 60_000L))

    // ---- hash
    @Test fun hashIsStableAndSensitive() {
        assertEquals(fnv1a64("a\u0000b"), fnv1a64("a\u0000b"))
        assertNotEquals(fnv1a64("a\u0000b"), fnv1a64("a\u0000c"))
        assertNotEquals(fnv1a64("ab"), fnv1a64("ba"))
    }

    // ---- private-network detection
    @Test fun privateHosts() {
        listOf(
            "192.168.1.50", "10.0.0.2", "172.16.0.1", "172.31.255.1", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "100.127.1.1", "localhost", "jellyfin", "nas.local", "media.lan", "[::1]", "[fe80::1]",
        ).forEach { assertTrue("$it should be private", isPrivateHost(it)) }
    }

    @Test fun publicHosts() {
        listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.128.0.1", "100.63.0.1", "jf.example.com", "11.0.0.1")
            .forEach { assertFalse("$it should be public", isPrivateHost(it)) }
    }

    @Test fun insecurePublicUrls() {
        assertTrue(isInsecurePublic("http://jf.example.com:8096"))
        assertFalse(isInsecurePublic("https://jf.example.com"))
        assertFalse(isInsecurePublic("http://192.168.1.50:8096"))
        assertFalse(isInsecurePublic("http://jellyfin:8096"))
    }
}
