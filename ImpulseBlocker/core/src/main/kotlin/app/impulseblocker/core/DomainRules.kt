package app.impulseblocker.core

import java.net.IDN

/**
 * Domain rule handling.
 *
 * A rule such as `wolt.com` blocks `wolt.com` itself and every subdomain
 * (`www.wolt.com`, `api.wolt.com`, ...). It does NOT block unrelated domains,
 * other TLDs (`wolt.fi`) or third-party ordering services; those must be added
 * as separate rules.
 */
object DomainRules {

    sealed interface Result {
        data class Ok(val domain: String) : Result
        data class Error(val message: String) : Result
    }

    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val NUMERIC = Regex("^[0-9]+$")

    /**
     * Turns user input such as `https://www.Wolt.com/en/order?x=1`,
     * `*.ubereats.com`, `foodora.cz:443` or `bolt.eu.` into a bare domain rule.
     *
     * A leading `www.` is removed so that the rule covers the whole site
     * (the `www` host is still covered because subdomains match).
     */
    fun normalize(input: String): Result {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return Result.Error("Enter a domain, for example wolt.com")

        // Strip scheme (http://, https://, anything://)
        val schemeIdx = s.indexOf("://")
        if (schemeIdx in 1..15) s = s.substring(schemeIdx + 3)
        // Cut path / query / fragment
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        // Strip user info
        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)
        if (s.startsWith("[") || s.count { it == ':' } > 1) {
            return Result.Error("IP addresses cannot be blocked with DNS rules; enter a domain name")
        }
        // Strip port
        val colon = s.indexOf(':')
        if (colon >= 0) s = s.substring(0, colon)
        // Wildcards / leading or trailing dots
        while (s.startsWith("*.") ) s = s.substring(2)
        s = s.trim('.')
        if (s.startsWith("www.") && s.count { it == '.' } >= 2) s = s.substring(4)

        if (s.isEmpty()) return Result.Error("Enter a domain, for example wolt.com")
        if (s.any { it.isWhitespace() }) return Result.Error("A domain cannot contain spaces")

        val ascii = try {
            IDN.toASCII(s, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (e: IllegalArgumentException) {
            return Result.Error("Not a valid domain name")
        }
        return validateAscii(ascii)
    }

    private fun validateAscii(domain: String): Result {
        if (domain.length > 253) return Result.Error("Domain is too long")
        val labels = domain.split('.')
        if (labels.size < 2) return Result.Error("Enter a full domain including its ending, for example wolt.com")
        if (labels.any { !LABEL.matches(it) }) return Result.Error("Not a valid domain name")
        if (NUMERIC.matches(labels.last())) {
            return Result.Error("IP addresses cannot be blocked with DNS rules; enter a domain name")
        }
        return Result.Ok(domain)
    }

    /** True if [host] equals [rule] or is a subdomain of it. */
    fun matches(host: String, rule: String): Boolean {
        val h = host.trimEnd('.').lowercase()
        return h == rule || h.endsWith(".$rule")
    }

    /** Returns the most specific rule matching [host], or null. */
    fun findMatch(host: String, rules: Collection<String>): String? {
        val h = host.trimEnd('.').lowercase()
        var best: String? = null
        for (rule in rules) {
            if ((h == rule || h.endsWith(".$rule")) && (best == null || rule.length > best.length)) {
                best = rule
            }
        }
        return best
    }

    /**
     * Fast matcher for the DNS hot path: walks the suffixes of the host and looks
     * each up in a hash set, so cost is O(labels) instead of O(rules).
     */
    class Matcher(rules: Collection<String>) {
        private val set: Set<String> = rules.toHashSet()

        fun match(host: String): String? {
            var h = host.trimEnd('.').lowercase()
            while (true) {
                if (h in set) return h
                val dot = h.indexOf('.')
                if (dot < 0) return null
                h = h.substring(dot + 1)
            }
        }

        val isEmpty: Boolean get() = set.isEmpty()
    }

    /**
     * Well-known DNS-over-HTTPS / DNS-over-TLS endpoints. Browsers configured to
     * use one of these would bypass the local DNS filter, so (optionally) their
     * hostnames are blocked. This is best effort: a browser with a hard-coded
     * resolver IP address can still bypass the filter.
     */
    val ENCRYPTED_DNS_PROVIDERS: List<String> = listOf(
        "dns.google",
        "dns.google.com",
        "cloudflare-dns.com",
        "one.one.one.one",
        "1dot1dot1dot1.cloudflare-dns.com",
        "dns.quad9.net",
        "dns9.quad9.net",
        "dns10.quad9.net",
        "dns11.quad9.net",
        "doh.opendns.com",
        "dns.opendns.com",
        "doh.familyshield.opendns.com",
        "doh.cleanbrowsing.org",
        "dns.nextdns.io",
        "dns.adguard.com",
        "dns.adguard-dns.com",
        "unfiltered.adguard-dns.com",
        "doh.dns.sb",
        "dns.mullvad.net",
        "doh.mullvad.net",
        "dns.controld.com",
        "freedns.controld.com",
        "doh.xfinity.com",
        "dns.alidns.com",
        "doh.pub",
        "dns.twnic.tw",
        "doh.libredns.gr",
        "dns0.eu",
    )

    /**
     * Firefox checks this canary domain; an NXDOMAIN answer tells Firefox not to
     * enable its automatic DNS-over-HTTPS, keeping it on the local filter.
     */
    const val FIREFOX_DOH_CANARY = "use-application-dns.net"
}
