package app.impulseblocker.core

/**
 * Decides what to do with one DNS query. Stateless apart from the snapshot it
 * is built from, so it is rebuilt whenever the configuration changes.
 */
class DnsFilter(
    blockedDomains: Collection<String>,
    blockEncryptedDns: Boolean,
) {
    sealed interface Decision {
        /** Answer locally with NXDOMAIN. [userRule] is null for built-in rules (DoH providers). */
        data class Block(val userRule: String?) : Decision
        /** Forward upstream. [clampTtl] is true for temporarily unlocked domains. */
        data class Forward(val clampTtl: Boolean) : Decision
        /** Not a query we can handle. */
        data object Drop : Decision
    }

    private val userMatcher = DomainRules.Matcher(blockedDomains)
    private val builtInMatcher = DomainRules.Matcher(
        if (blockEncryptedDns) DomainRules.ENCRYPTED_DNS_PROVIDERS + DomainRules.FIREFOX_DOH_CANARY
        else listOf(DomainRules.FIREFOX_DOH_CANARY)
    )

    fun decide(q: DnsMessage.Question, isDomainUnlocked: (String) -> Boolean): Decision {
        if (!q.isQuery || q.opcode != 0) return Decision.Forward(false)
        val name = q.name
        val rule = userMatcher.match(name)
        if (rule != null) {
            return if (isDomainUnlocked(rule)) Decision.Forward(clampTtl = true)
            else Decision.Block(rule)
        }
        if (builtInMatcher.match(name) != null) return Decision.Block(null)
        return Decision.Forward(false)
    }

    companion object {
        /** TTL used for answers of temporarily unlocked domains. */
        const val UNLOCKED_MAX_TTL = 30L
    }
}
