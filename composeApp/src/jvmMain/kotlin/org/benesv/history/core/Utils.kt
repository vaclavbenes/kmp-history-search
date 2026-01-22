package org.benesv.history.core

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

object PathsMac {
    private val home = System.getProperty("user.home")
    val chromeRoot = File(home, "Library/Application Support/Google/Chrome")
    // Zen Browser: allow both "Zen" and lowercase variants used by some builds
    val zenRootCandidates = listOf(
        File(home, "Library/Application Support/Zen"),
        File(home, "Library/Application Support/zen"),
        File(home, "Library/Application Support/Zen Browser"),
    )
    val thoriumRoot = File(home, "Library/Application Support/Thorium")
}

object TimeUtil {
    private const val CHROME_EPOCH_OFFSET_MICROS = 11644473600000000L

    const val CHROME_EPOCH_OFFSET_MILLIS = 11644473600000L

    fun chromeToEpochMillis(chromeMicros: Long): Long =
        ((chromeMicros - CHROME_EPOCH_OFFSET_MICROS) / 1000L).coerceAtLeast(0L)

    fun firefoxMicrosToMillis(us: Long): Long = (us / 1000L).coerceAtLeast(0L)

    fun formatMillis(millis: Long): String = DateTimeFormatter.ofPattern("MM/dd/yyyy, HH:mm:ss")
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
}

object FileUtil {
    fun createTempCopy(original: File, prefix: String = "dbcopy"): File {
        val temp = Files.createTempFile(prefix, ".sqlite").toFile()
        temp.deleteOnExit()
        original.inputStream().use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return temp
    }
}

fun domainOf(url: String): String = try {
    val uri = URI(url)
    val host = uri.host ?: return ""
    val port = uri.port
    val domain = host.removePrefix("www.")
    if (port != -1 && isLocalhost(host)) {
        "$domain:$port"
    } else {
        domain
    }
} catch (_: Exception) {
    ""
}

fun isLocalhost(domain: String): Boolean =
    domain == "localhost" || domain.startsWith("localhost:") ||
        domain.endsWith(".local") || domain.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?"))

fun getProtocol(domain: String): String =
    if (isLocalhost(domain)) "http" else "https"

fun isInternalUrl(url: String): Boolean =
    url.startsWith("chrome://") || url.startsWith("about:") || url.startsWith("edge://") || url.startsWith("chrome-extension://") || url.startsWith("thorium://")
