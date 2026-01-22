package org.benesv.history.data

import org.benesv.history.core.getProtocol

/**
 * Favicon helpers.
 *
 * We no longer read favicons from browser databases. Instead, we use the Google S2 favicon service
 * and fetch icons in the background based on the domain.
 */
object FaviconExtractor {
    fun getFaviconUrl(domain: String, size: Int = 64): String =
        "https://www.google.com/s2/favicons?domain=$domain&sz=$size"

    /**
     * Ordered a list of favicon URL candidates to try for a given domain.
     * 1) Site-specific TeamCity-like path: https://<domain>/img/icons/favicon.ico
     * 2) Generic root favicon: https://<domain>/favicon.ico
     * 3) Google S2 fallback
     */
    fun getCandidateFaviconUrls(domain: String, size: Int = 64): List<String> {
        val protocol = getProtocol(domain)

        return buildList {
            // Special case for myworkday.com - use HTTPS variant
            if (domain == "myworkday.com") {
                add("https://www.myworkday.com/favicon.ico")
            }
            add(getFaviconUrl(domain, size))
            add("$protocol://$domain/favicon.ico")
            add("$protocol://$domain/img/icons/favicon.ico")
            add("$protocol://$domain/swagger/favicon.ico")
        }
    }
}
