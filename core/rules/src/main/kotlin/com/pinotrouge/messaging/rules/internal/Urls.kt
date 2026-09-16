package com.pinotrouge.messaging.rules.internal

/**
 * URL detection for filter conditions.
 *
 * Real spam often omits a scheme (`usps-redelivery-status.co/9182`), so bare
 * hosts must be found as well as `http(s)://` forms. Bare hosts are only
 * accepted when the final label is a [COMMON_TLDS] entry — otherwise prose
 * like `Reply STOP.Msg&data rates may apply` is misread as a link.
 */
internal object Urls {

    /**
     * Known URL shorteners from the filter-rule spec.
     * Entries are hostnames or host prefixes (e.g. "tinyurl" matches tinyurl.com).
     */
    private val SHORTENERS = setOf(
        "bit.ly",
        "t.co",
        "tinyurl.com",
        "tinyurl",
        "goo.gl",
        "ow.ly",
        "is.gd",
        "buff.ly",
        "rebrand.ly",
        "cutt.ly",
        "shorturl.at",
    )

    /**
     * TLDs that real spam and legitimate bare hosts actually use. Required
     * for scheme-less matches so `STOP.Msg` and `8.Bring` are not hosts.
     */
    private val COMMON_TLDS = setOf(
        "com", "net", "org", "edu", "gov", "mil", "int", "info", "biz", "name", "pro", "mobi",
        "io", "co", "cc", "tv", "me", "ly", "gl", "gd", "at", "to", "sh", "st", "ws", "fm", "am",
        "us", "uk", "ca", "au", "de", "fr", "es", "it", "nl", "se", "no", "ru", "cn", "jp", "in", "br", "mx",
        "xyz", "top", "shop", "site", "store", "online", "live", "app", "dev", "link", "click",
        "icu", "vip", "tech", "space", "website", "fun", "life", "world", "today", "news",
        "email", "page", "run", "zip", "mov",
    )

    /** TLDs used by actual shortener services for the length heuristic. */
    private val SHORTENER_TLDS = setOf("ly", "gl", "gd", "to", "at", "sh", "st", "cc")

    /**
     * Scheme-optional URL / bare-host finder.
     *
     * Group 1: optional scheme (`http://` / `https://` / `ftp://`)
     * Group 2: optional `www.`
     * Group 3: host
     */
    private val URL_PATTERN = Regex(
        """(?i)\b(?:((?:https?|ftp)://)|(www\.))?([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+)(?::\d{1,5})?(?:/[^\s]*)?""",
    )

    data class FoundUrl(val host: String, val raw: String)

    fun extract(body: String): List<FoundUrl> {
        if (body.isBlank()) return emptyList()
        return URL_PATTERN.findAll(body).mapNotNull { match ->
            val hasScheme = match.groupValues[1].isNotEmpty()
            val hasWww = match.groupValues[2].isNotEmpty()
            val host = match.groupValues[3].lowercase().trimEnd('.')
            if (host.isEmpty()) return@mapNotNull null
            if (!looksLikeDomain(host, unambiguous = hasScheme || hasWww)) return@mapNotNull null
            FoundUrl(host = host, raw = match.value)
        }.toList()
    }

    fun anyPresent(body: String): Boolean = extract(body).isNotEmpty()

    fun anyShortened(body: String): Boolean =
        extract(body).any { isShortenedHost(it.host) }

    fun anyOnDomain(body: String, domain: String): Boolean {
        val target = normalizeDomain(domain)
        if (target.isEmpty()) return false
        return extract(body).any { hostMatchesDomain(it.host, target) }
    }

    fun isShortenedHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        if (SHORTENERS.any { shortener -> hostMatchesShortener(h, shortener) }) {
            return true
        }
        // Name ≤ 4 chars with a shortener TLD (ly, gl, gd, to, at, sh, st, cc).
        // Deliberately tighter than "≤ 6 + any 2-letter TLD", which false-positived
        // delta.io and apple.co.
        val parts = h.split('.')
        if (parts.size == 2) {
            val (name, tld) = parts
            if (name.length <= 4 &&
                tld in SHORTENER_TLDS &&
                name.all { it.isLetterOrDigit() }
            ) {
                return true
            }
        }
        return false
    }

    private fun hostMatchesShortener(host: String, shortener: String): Boolean {
        val s = shortener.lowercase()
        return host == s ||
            host.endsWith(".$s") ||
            host.startsWith("$s.")
    }

    fun hostMatchesDomain(host: String, domain: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        val d = normalizeDomain(domain)
        if (d.isEmpty()) return false
        return h == d || h.endsWith(".$d")
    }

    private fun normalizeDomain(raw: String): String {
        var d = raw.trim().lowercase()
        d = d.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        d = d.substringBefore('/').substringBefore('?').trimEnd('.')
        return d
    }

    /**
     * @param unambiguous true when the match already had a scheme or `www.` —
     *   any letter TLD ≥ 2 is accepted. Otherwise the TLD must be in [COMMON_TLDS].
     */
    private fun looksLikeDomain(host: String, unambiguous: Boolean): Boolean {
        val parts = host.split('.')
        if (parts.size < 2) return false
        val tld = parts.last()
        if (tld.length < 2 || !tld.all { it.isLetter() }) return false
        if (!parts.all { label ->
                label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' }
            }
        ) {
            return false
        }
        if (unambiguous) return true
        return tld in COMMON_TLDS
    }
}
