package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.ConfigChangeEvent
import com.dangerfield.cards.server.domain.ConfigChangeNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Posts a one-line message to a Slack-compatible incoming webhook on every
 * config change. Fire-and-forget on the server's app scope: a slow or failing
 * webhook can never delay or break the admin write — failures are logged, not
 * propagated. When [webhookUrl] is blank the whole thing is a no-op, so
 * unconfigured deploys pay nothing.
 */
class WebhookConfigChangeNotifier(
    private val webhookUrl: String?,
    private val environment: String,
    private val scope: CoroutineScope,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) : ConfigChangeNotifier {

    private val logger = LoggerFactory.getLogger(WebhookConfigChangeNotifier::class.java)

    override fun changed(event: ConfigChangeEvent) {
        val url = webhookUrl?.takeIf { it.isNotBlank() } ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload(event)))
                    .build()
                client.send(request, HttpResponse.BodyHandlers.ofString())
            }.onFailure { logger.warn("Config-change webhook failed: ${it.message}") }
        }
    }

    private fun payload(event: ConfigChangeEvent): String {
        val flag = event.flagPath?.let { " `$it`" }.orEmpty()
        val detail = event.detail?.let { " → $it" }.orEmpty()
        val text = "[config · $environment] *${event.actor}* ${event.action}$flag$detail"
        return Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("text" to JsonPrimitive(text))))
    }
}
