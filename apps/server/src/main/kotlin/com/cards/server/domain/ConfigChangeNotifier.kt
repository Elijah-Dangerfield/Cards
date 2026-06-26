package com.dangerfield.cards.server.domain

/**
 * Side-channel announcement of a config mutation (to Slack, a webhook, …) so a
 * change to live config doesn't only live in the audit table where nobody looks.
 * Fire-and-forget: an implementation must never block or fail the write that
 * triggered it. The default is a no-op (notifications unconfigured).
 */
fun interface ConfigChangeNotifier {
    fun changed(event: ConfigChangeEvent)
}

/** Who changed what. [detail] is a short human summary (e.g. the new value). */
data class ConfigChangeEvent(
    val actor: String,
    val action: String,
    val flagPath: String?,
    val detail: String?,
)
