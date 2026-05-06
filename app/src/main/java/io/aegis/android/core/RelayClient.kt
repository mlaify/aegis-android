// RelayClient — HttpURLConnection-based wrapper around the RFC-0004
// relay HTTP API. Mirrors aegis-apple's `RelayClient.swift`
// endpoint-for-endpoint:
//
//   GET   /v1/identities/:id           → resolveIdentity
//   GET   /v1/aliases/:alias           → resolveAlias
//   PUT   /v1/identities/:id           → publishIdentity
//   GET   /v1/envelopes/:recipient     → fetchEnvelopes
//   POST  /v1/envelopes                → pushEnvelope
//   POST  /v1/identities/:id/prekeys   → publishPrekeys
//   GET   /v1/identities/:id/prekey    → claimOneTimePrekey
//
// We use HttpURLConnection deliberately — no new HTTP dep (OkHttp /
// Ktor) needed for the surface we cover here, and the JDK's built-in
// client matches the iOS side's "use the platform's URLSession"
// philosophy. All blocking I/O is wrapped in `withContext(Dispatchers.IO)`
// so callers `await` from Compose's lifecycle without freezing the UI.
//
// JSON identifiers like `amp:did:key:z…` contain `:` and (rarely) `/`,
// which percent-encode to `%3A` / `%2F`. `encId` matches the Rust
// `aegis_identity::resolver::url_encode` helper byte-for-byte.

package io.aegis.android.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Errors RelayClient surfaces to callers. Network and decoding failures
 * flow through here; UI should display [Throwable.message].
 */
sealed class RelayClientException(message: String) : Exception(message) {
    /** Caller-supplied URL is empty / not parseable / wrong scheme. */
    class InvalidRelayUrl(url: String) :
        RelayClientException("Invalid relay URL: $url")

    /** HTTP layer returned a non-success status. `code` and `messageDetail`
     *  are populated when the relay returned a structured error body. */
    class HttpStatus(
        val status: Int,
        val code: String?,
        val messageDetail: String?,
    ) : RelayClientException(
        buildString {
            append("Relay returned HTTP ").append(status)
            val parts = listOfNotNull(code, messageDetail)
                .filter { it.isNotBlank() }
            if (parts.isNotEmpty()) append(" — ").append(parts.joinToString(": "))
        }
    )

    /** Response body failed to decode into the expected schema. */
    class Decoding(detail: String) :
        RelayClientException("Failed to decode relay response: $detail")

    /** Anything else from HttpURLConnection (timeouts, TLS, offline). */
    class Transport(detail: String) :
        RelayClientException("Relay transport error: $detail")
}

/**
 * Pure-transport client for the relay HTTP API. Holds a base URL only;
 * everything else is stateless. Constructor validates the URL eagerly
 * so the UI can surface a clean error before issuing any requests.
 */
class RelayClient
@Throws(RelayClientException::class)
constructor(baseUrl: String) {

    /** Base URL the user configured (e.g. `https://relay.example.com`).
     *  Trailing slash is stripped at init time. */
    val baseUrl: URI

    init {
        val trimmed = baseUrl
            .trim()
            .replace(Regex("/+$"), "")
        if (trimmed.isEmpty()) throw RelayClientException.InvalidRelayUrl(baseUrl)
        val uri = try { URI(trimmed) } catch (_: Exception) {
            throw RelayClientException.InvalidRelayUrl(baseUrl)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw RelayClientException.InvalidRelayUrl(baseUrl)
        }
        if (uri.host.isNullOrEmpty()) {
            throw RelayClientException.InvalidRelayUrl(baseUrl)
        }
        this.baseUrl = uri
    }

    // ─── Read endpoints ────────────────────────────────────────────────────

    /** `GET /v1/identities/:identity_id` — resolve an identity by DID. */
    suspend fun resolveIdentity(identityId: String): IdentityDocument =
        getJson<IdentityDocument>("/v1/identities/${encId(identityId)}")

    /** `GET /v1/aliases/:alias` — resolve an identity by user-friendly alias. */
    suspend fun resolveAlias(alias: String): IdentityDocument =
        getJson<IdentityDocument>("/v1/aliases/${encId(alias)}")

    /** `GET /v1/envelopes/:recipient_id` — drain the relay's inbox queue. */
    suspend fun fetchEnvelopes(recipientId: String): List<Envelope> =
        getJson<FetchEnvelopeResponse>("/v1/envelopes/${encId(recipientId)}").envelopes

    // ─── Write endpoints ───────────────────────────────────────────────────

    /** `PUT /v1/identities/:identity_id` — publish (or republish) the
     *  signed IdentityDocument. We accept the raw JSON the FFI emitted
     *  to preserve byte-for-byte fidelity with the canonical signature
     *  input. */
    suspend fun publishIdentity(documentJson: String, identityId: String) {
        sendNoContent(
            method = "PUT",
            path = "/v1/identities/${encId(identityId)}",
            bodyJson = documentJson,
        )
    }

    /** `POST /v1/identities/:identity_id/prekeys` — publish a signed
     *  PrekeyBundle. We take the JSON the FFI emitted (after
     *  `signPrekeyBundle`) so the bytes match the signature input. */
    suspend fun publishPrekeys(
        bundleJson: String,
        identityId: String,
    ): PublishPrekeysResponse =
        postJsonRaw<PublishPrekeysResponse>(
            path = "/v1/identities/${encId(identityId)}/prekeys",
            bodyJson = bundleJson,
        )

    /** `GET /v1/identities/:identity_id/prekey` — atomically claim one
     *  one-time prekey from the recipient's pool. */
    suspend fun claimOneTimePrekey(identityId: String): ClaimedPrekeyResponse =
        getJson<ClaimedPrekeyResponse>("/v1/identities/${encId(identityId)}/prekey")

    /** `POST /v1/envelopes` — push a sealed envelope to the relay. We
     *  take the JSON the FFI emitted (from `sealHybridPq`) and wrap it
     *  in `{"envelope": <…>}` per the StoreEnvelopeRequest shape. */
    suspend fun pushEnvelope(envelopeJson: String): StoreEnvelopeResponse =
        postJsonRaw<StoreEnvelopeResponse>(
            path = "/v1/envelopes",
            // String concat keeps the FFI-emitted bytes intact — going
            // through Codable would re-stringify and risk byte drift.
            bodyJson = "{\"envelope\":$envelopeJson}",
        )

    // ─── Internal HTTP plumbing ────────────────────────────────────────────

    /** All HttpURLConnection traffic uses this Json instance. We allow
     *  unknown keys so a relay rolling out a new field doesn't break
     *  older clients (forward-compat); we `prettyPrint = false` and
     *  `explicitNulls = false` to keep request bodies compact. */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private suspend inline fun <reified T> getJson(path: String): T =
        runDecode(method = "GET", path = path, body = null)

    private suspend inline fun <reified T> postJsonRaw(path: String, bodyJson: String): T =
        runDecode(method = "POST", path = path, body = bodyJson)

    private suspend inline fun <reified T> runDecode(
        method: String,
        path: String,
        body: String?,
    ): T {
        val text = withContext(Dispatchers.IO) { request(method, path, body) }
        return try {
            json.decodeFromString<T>(text)
        } catch (e: SerializationException) {
            throw RelayClientException.Decoding(e.message ?: "unknown")
        } catch (e: IllegalArgumentException) {
            throw RelayClientException.Decoding(e.message ?: "unknown")
        }
    }

    private suspend fun sendNoContent(method: String, path: String, bodyJson: String) {
        withContext(Dispatchers.IO) { request(method, path, bodyJson) }
        // 204 / empty body is acceptable; we discard whatever was returned.
    }

    /** Performs one HTTP round-trip on the calling thread. Callers
     *  wrap with `withContext(Dispatchers.IO)`; we don't do that here
     *  so blocking-IO threading stays a higher-level concern. */
    private fun request(method: String, path: String, bodyJson: String?): String {
        val url = URL(baseUrl.toString() + (if (path.startsWith("/")) path else "/$path"))
        val conn = try {
            (url.openConnection() as HttpURLConnection)
        } catch (e: IOException) {
            throw RelayClientException.Transport(e.message ?: "connection open failed")
        }
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (bodyJson != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            }
            val status = try { conn.responseCode } catch (e: IOException) {
                throw RelayClientException.Transport(e.message ?: "no response")
            }
            val isSuccess = status in 200..299
            val stream = if (isSuccess) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (!isSuccess) {
                // Try to parse the relay's structured error body; fall
                // back to plain text if the shape doesn't match.
                val parsed = runCatching {
                    json.decodeFromString<RelayErrorBody>(text)
                }.getOrNull()
                throw RelayClientException.HttpStatus(
                    status = status,
                    code = parsed?.error?.code,
                    messageDetail = parsed?.error?.message ?: text.takeIf { it.isNotBlank() },
                )
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ─── Path encoding ─────────────────────────────────────────────────────

    companion object {
        /** Mirror of `aegis_identity::resolver::url_encode` (Rust). Only
         *  `:` and `/` get percent-encoded — keeping the rest of the DID
         *  human-readable on the wire, and matching what the relay
         *  expects to receive. */
        fun encId(s: String): String =
            s.replace(":", "%3A").replace("/", "%2F")
    }
}
