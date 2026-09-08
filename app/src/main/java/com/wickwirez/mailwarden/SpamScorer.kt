package com.wickwirez.mailwarden

enum class ThreatLevel(val label: String) {
    SAFE("Safe"),
    SUSPICIOUS("Suspicious"),
    DANGEROUS("Dangerous")
}

data class Signal(
    val name: String,
    val points: Int,
    val detail: String
)

data class SpamVerdict(
    val score: Int,
    val level: ThreatLevel,
    val signals: List<Signal>
) {
    val passedAuth: Boolean
        get() = signals.none { it.name.startsWith("Auth") && it.points > 0 }

    companion object {
        val UNKNOWN = SpamVerdict(0, ThreatLevel.SAFE, emptyList())
    }
}

data class MessageHeaders(
    val fromDisplay: String = "",
    val fromAddress: String = "",
    val replyTo: String = "",
    val returnPath: String = "",
    val authResults: String = "",
    val receivedSpf: String = "",
    val listUnsubscribe: String = ""
)

object SpamScorer {

    fun score(
        headers: MessageHeaders,
        subject: String,
        body: String,
        links: List<String>
    ): SpamVerdict {
        val signals = mutableListOf<Signal>()

        signals += authSignals(headers)
        signals += senderSignals(headers)
        signals += contentSignals(subject, body)
        signals += linkSignals(headers, links)

        val total = signals.sumOf { it.points }
        val level = when {
            total >= 60 -> ThreatLevel.DANGEROUS
            total >= 25 -> ThreatLevel.SUSPICIOUS
            else -> ThreatLevel.SAFE
        }
        return SpamVerdict(total, level, signals.filter { it.points != 0 })
    }

    private fun authSignals(h: MessageHeaders): List<Signal> {
        val out = mutableListOf<Signal>()
        val auth = (h.authResults + " " + h.receivedSpf).lowercase()

        if (auth.isBlank()) {
            out += Signal("Auth: none", 15, "No authentication headers present")
            return out
        }

        if (Regex("spf=(fail|softfail)").containsMatchIn(auth)) {
            out += Signal("Auth: SPF failed", 30, "Sender's server is not authorized for this domain")
        }
        if (Regex("dkim=fail").containsMatchIn(auth)) {
            out += Signal("Auth: DKIM failed", 30, "Message signature did not validate")
        }
        if (Regex("dmarc=fail").containsMatchIn(auth)) {
            out += Signal("Auth: DMARC failed", 35, "Message failed the domain's own anti-spoofing policy")
        }
        return out
    }

    private fun senderSignals(h: MessageHeaders): List<Signal> {
        val out = mutableListOf<Signal>()
        val fromDomain = domainOf(h.fromAddress)

        val displayAddr = Regex("[\\w.+-]+@[\\w.-]+\\.\\w+")
            .find(h.fromDisplay)?.value
        if (displayAddr != null && !displayAddr.equals(h.fromAddress, true)) {
            out += Signal(
                "Sender: display name spoofing",
                35,
                "Shows \"$displayAddr\" but actually sent from ${h.fromAddress}"
            )
        }

        val replyDomain = domainOf(h.replyTo)
        if (replyDomain.isNotBlank() && fromDomain.isNotBlank() && replyDomain != fromDomain) {
            out += Signal(
                "Sender: reply-to mismatch",
                20,
                "Replies would go to $replyDomain, not $fromDomain"
            )
        }

        val returnDomain = domainOf(h.returnPath)
        if (returnDomain.isNotBlank() && fromDomain.isNotBlank() && returnDomain != fromDomain) {
            out += Signal(
                "Sender: return-path mismatch",
                10,
                "Bounces route to $returnDomain, not $fromDomain"
            )
        }

        return out
    }

    private fun domainOf(addr: String): String =
        addr.substringAfterLast('@', "").trim().lowercase().trimEnd('>')

    private val urgencyWords = listOf(
        "act now", "urgent", "immediately", "expires today", "final notice",
        "last chance", "suspended", "verify your account", "confirm your identity",
        "unusual activity", "account locked", "within 24 hours", "failure to respond"
    )

    private val lureWords = listOf(
        "you have won", "congratulations", "claim your", "free gift",
        "prize", "lottery", "inheritance", "wire transfer", "bitcoin",
        "gift card", "refund pending", "unclaimed funds"
    )

    private val credentialWords = listOf(
        "verify your password", "update your payment", "confirm your ssn",
        "social security", "banking details", "login to restore",
        "re-enter your card", "billing information"
    )

    private fun contentSignals(subject: String, body: String): List<Signal> {
        val out = mutableListOf<Signal>()
        val text = (subject + " " + body).lowercase()

        val urgency = urgencyWords.filter { text.contains(it) }
        if (urgency.isNotEmpty()) {
            out += Signal(
                "Content: urgency pressure",
                minOf(urgency.size * 8, 24),
                "Pressure phrases: ${urgency.take(3).joinToString(", ")}"
            )
        }

        val lures = lureWords.filter { text.contains(it) }
        if (lures.isNotEmpty()) {
            out += Signal(
                "Content: reward lure",
                minOf(lures.size * 10, 30),
                "Prize/money phrases: ${lures.take(3).joinToString(", ")}"
            )
        }

        val creds = credentialWords.filter { text.contains(it) }
        if (creds.isNotEmpty()) {
            out += Signal(
                "Content: credential request",
                40,
                "Asks for sensitive data: ${creds.take(3).joinToString(", ")}"
            )
        }

        val letters = subject.count { it.isLetter() }
        val caps = subject.count { it.isUpperCase() }
        if (letters >= 10 && caps.toDouble() / letters > 0.6) {
            out += Signal("Content: shouting subject", 8, "Subject line is mostly capitals")
        }

        return out
    }

    private val shorteners = listOf(
        "bit.ly", "tinyurl.com", "goo.gl", "t.co", "ow.ly", "is.gd",
        "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at", "rb.gy"
    )

    private val riskyTlds = listOf(
        ".zip", ".mov", ".xyz", ".top", ".tk", ".ml", ".ga", ".cf", ".gq", ".click"
    )

    private fun linkSignals(h: MessageHeaders, links: List<String>): List<Signal> {
        val out = mutableListOf<Signal>()
        if (links.isEmpty()) return out

        val hosts = links.mapNotNull { hostOf(it) }.filter { it.isNotBlank() }

        val shortened = hosts.filter { host -> shorteners.any { host.endsWith(it) } }
        if (shortened.isNotEmpty()) {
            out += Signal(
                "Links: shortened URLs",
                15,
                "${shortened.size} link(s) hide their destination behind a shortener"
            )
        }

        val risky = hosts.filter { host -> riskyTlds.any { host.endsWith(it) } }
        if (risky.isNotEmpty()) {
            out += Signal(
                "Links: high-risk domain",
                25,
                "Uses domains commonly abused for phishing: ${risky.distinct().take(3).joinToString(", ")}"
            )
        }

        val rawIp = hosts.filter { Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(it) }
        if (rawIp.isNotEmpty()) {
            out += Signal(
                "Links: raw IP address",
                30,
                "Links point straight at an IP instead of a domain name"
            )
        }

        return out
    }

    private fun hostOf(url: String): String? = try {
        java.net.URI(url).host?.lowercase()?.removePrefix("www.")
    } catch (_: Exception) {
        null
    }
}
