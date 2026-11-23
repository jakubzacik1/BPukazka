package app.impulseblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRulesTest {

    private fun ok(input: String) = (DomainRules.normalize(input) as DomainRules.Result.Ok).domain
    private fun isError(input: String) = DomainRules.normalize(input) is DomainRules.Result.Error

    @Test fun normalizesUrls() {
        assertEquals("wolt.com", ok("https://www.Wolt.com/en/svk/bratislava?x=1#top"))
        assertEquals("ubereats.com", ok("*.ubereats.com"))
        assertEquals("foodora.cz", ok("foodora.cz:443"))
        assertEquals("bolt.eu", ok("  bolt.eu. "))
        assertEquals("food.bolt.eu", ok("http://user:pw@food.bolt.eu/path"))
        assertEquals("www.com", ok("www.com"))
        assertEquals("xn--bcher-kva.de", ok("bücher.de"))
    }

    @Test fun rejectsInvalidInput() {
        assertTrue(isError(""))
        assertTrue(isError("localhost"))
        assertTrue(isError("192.168.1.1"))
        assertTrue(isError("[::1]"))
        assertTrue(isError("bad domain.com"))
        assertTrue(isError("-bad.com"))
        assertTrue(isError("a..b.com"))
    }

    @Test fun matchesDomainAndSubdomainsOnly() {
        assertTrue(DomainRules.matches("wolt.com", "wolt.com"))
        assertTrue(DomainRules.matches("www.wolt.com", "wolt.com"))
        assertTrue(DomainRules.matches("a.b.wolt.com.", "wolt.com"))
        assertFalse(DomainRules.matches("notwolt.com", "wolt.com"))
        assertFalse(DomainRules.matches("wolt.com.evil.net", "wolt.com"))
        assertFalse(DomainRules.matches("wolt.fi", "wolt.com"))
    }

    @Test fun matcherFindsMostSpecificRule() {
        val m = DomainRules.Matcher(listOf("wolt.com", "api.wolt.com", "ubereats.com"))
        assertEquals("api.wolt.com", m.match("x.api.wolt.com"))
        assertEquals("wolt.com", m.match("WWW.Wolt.com"))
        assertNull(m.match("example.com"))
        assertNull(m.match("com"))
        assertEquals("api.wolt.com", DomainRules.findMatch("x.api.wolt.com", listOf("wolt.com", "api.wolt.com")))
    }
}
