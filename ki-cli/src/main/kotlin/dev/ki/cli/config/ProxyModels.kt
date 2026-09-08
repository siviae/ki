package dev.ki.cli.config

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ki.ai.KiLlm
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Best-effort metadata lookup against the LiteLLM proxy's `GET /v1/models` — the
 * auto-filled source for [dev.ki.agent.config.ModelEntry] fields left unset in ki.toml
 * (context window / max output tokens). Precedence: ki.toml > proxy > KiModel defaults.
 *
 * The proxy does NOT expose capability flags (`/v1/model/info` is 403 on our keys and
 * `/v1/models` carries only token limits), so vision is configured manually
 * (`[models.<alias>].vision`) — see the bot's ki.toml.
 *
 * Any failure (no key, timeout, non-200, bad JSON, unknown model) degrades to null and
 * the caller falls back — a metadata lookup must never break startup.
 */
class ProxyModels(
    /** Injectable HTTP fetch (url, apiKey) → body; defaults to java.net.http. For tests. */
    private val http: (url: String, apiKey: String?) -> String? = { url, apiKey ->
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .GET()
            .build()
        HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString())
            .takeIf { it.statusCode() == 200 }
            ?.body()
    },
) {
    data class Info(val maxInputTokens: Long, val maxOutputTokens: Long)

    /**
     * Look up [modelId] on the proxy behind [baseUrl] (a `/v1`-suffixed or bare proxy
     * root both work — normalized like the LLM client does). Returns null when anything
     * is off; never throws.
     */
    fun lookup(baseUrl: String, apiKey: String?, modelId: String): Info? = try {
        val url = KiLlm.normalizeBaseUrl(baseUrl) + "/v1/models"
        val body = http(url, apiKey) ?: return null
        val data = ObjectMapper().readTree(body)?.get("data") ?: return null
        val entry = data.firstOrNull { it?.get("id")?.asText() == modelId } ?: return null
        val input = entry.get("max_input_tokens")?.takeIf { it.isNumber }?.asLong() ?: return null
        val output = entry.get("max_output_tokens")?.takeIf { it.isNumber }?.asLong() ?: return null
        Info(maxInputTokens = input, maxOutputTokens = output)
    } catch (_: Exception) {
        null
    }
}
