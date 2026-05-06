// RelayClient + RelayModels unit tests.
//
// These don't hit a live relay. We exercise:
//   - URL validation rejects garbage / wrong-scheme inputs
//   - `RelayClient.encId` matches the Rust `url_encode` helper
//   - JSON parity (encode → decode round-trip preserves snake_case)
//     against fixture payloads cribbed from aegis-sdk's TypeScript
//     definitions (mlaify/aegis-sdk/typescript/src/index.ts)
//   - Decoding tolerates omitted optional fields (`expires_at`, etc.)
//
// Mirrors the Swift suite in
// aegis-apple/shared/Tests/AegisCoreTests/RelayClientTests.swift.

package io.aegis.android.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelayClientTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // ─── URL validation ────────────────────────────────────────────────────

    @Test
    fun initRejectsEmptyUrl() {
        try {
            RelayClient("")
            fail("expected InvalidRelayUrl")
        } catch (e: RelayClientException.InvalidRelayUrl) {
            // expected
        }
    }

    @Test
    fun initRejectsBareHostname() {
        try {
            RelayClient("relay.example.com")
            fail("expected InvalidRelayUrl")
        } catch (e: RelayClientException.InvalidRelayUrl) {
            // expected
        }
    }

    @Test
    fun initRejectsFileScheme() {
        try {
            RelayClient("file:///tmp/relay")
            fail("expected InvalidRelayUrl")
        } catch (e: RelayClientException.InvalidRelayUrl) {
            // expected
        }
    }

    @Test
    fun initStripsTrailingSlash() {
        val client = RelayClient("https://relay.example.com/")
        assertEquals("https://relay.example.com", client.baseUrl.toString())
    }

    @Test
    fun initAcceptsHttpAndHttps() {
        // Should not throw.
        RelayClient("http://localhost:8787")
        RelayClient("https://relay.example.com")
    }

    // ─── encId ─────────────────────────────────────────────────────────────

    @Test
    fun encIdEncodesColonAndSlash() {
        assertEquals(
            "amp%3Adid%3Akey%3AzABC",
            RelayClient.encId("amp:did:key:zABC"),
        )
        assertEquals(
            "alias%2Fwith%2Fslashes",
            RelayClient.encId("alias/with/slashes"),
        )
    }

    @Test
    fun encIdLeavesAlphanumericsAlone() {
        assertEquals(
            "ZeroOne_two-three.four",
            RelayClient.encId("ZeroOne_two-three.four"),
        )
    }

    // ─── JSON parity (decode) ──────────────────────────────────────────────

    @Test
    fun decodeFetchEnvelopeResponse() {
        val text = """
        {
          "envelopes": [
            {
              "version": 1,
              "envelope_id": "env-001",
              "recipient_id": "amp:did:key:zRECIPIENT",
              "sender_hint": "amp:did:key:zSENDER",
              "created_at": "2026-05-06T12:34:56Z",
              "expires_at": null,
              "content_type": "application/aegis-msg+json",
              "suite_id": "AMP-HYBRID-X25519-MLKEM768-ED25519-MLDSA65-V1",
              "used_prekey_ids": ["pk-1"],
              "payload": {
                "nonce_b64": "AAAA",
                "ciphertext_b64": "BBBB",
                "eph_x25519_public_key_b64": "CCCC",
                "mlkem_ciphertext_b64": "DDDD"
              },
              "outer_signature_b64": "EEEE",
              "outer_pq_signature_b64": "FFFF"
            }
          ]
        }
        """.trimIndent()
        val body = json.decodeFromString<FetchEnvelopeResponse>(text)
        assertEquals(1, body.envelopes.size)
        val env = body.envelopes[0]
        assertEquals("env-001", env.envelopeId)
        assertEquals("amp:did:key:zRECIPIENT", env.recipientId)
        assertEquals("amp:did:key:zSENDER", env.senderHint)
        assertNull(env.expiresAt)
        assertEquals(listOf("pk-1"), env.usedPrekeyIds)
        assertEquals("CCCC", env.payload.ephX25519PublicKeyB64)
        assertEquals("DDDD", env.payload.mlkemCiphertextB64)
        assertEquals("EEEE", env.outerSignatureB64)
        assertEquals("FFFF", env.outerPqSignatureB64)
    }

    @Test
    fun decodeEnvelopeWithoutOptionalSignatureFields() {
        // Demo suite envelopes don't carry the ML-DSA outer signature.
        val text = """
        {
          "version": 1,
          "envelope_id": "env-002",
          "recipient_id": "amp:did:key:zR",
          "sender_hint": null,
          "created_at": "2026-05-06T00:00:00Z",
          "expires_at": null,
          "content_type": "application/aegis-msg+json",
          "suite_id": "AMP-DEMO-XCHACHA20POLY1305",
          "used_prekey_ids": [],
          "payload": {
            "nonce_b64": "AA",
            "ciphertext_b64": "BB"
          },
          "outer_signature_b64": null
        }
        """.trimIndent()
        val env = json.decodeFromString<Envelope>(text)
        assertNull(env.senderHint)
        assertNull(env.outerSignatureB64)
        assertNull(env.outerPqSignatureB64)
        assertNull(env.payload.ephX25519PublicKeyB64)
    }

    @Test
    fun decodePublishPrekeysResponse() {
        val text = """
        { "identity_id": "amp:did:key:zABC", "inserted": 3, "skipped": 0 }
        """.trimIndent()
        val body = json.decodeFromString<PublishPrekeysResponse>(text)
        assertEquals("amp:did:key:zABC", body.identityId)
        assertEquals(3, body.inserted)
        assertEquals(0, body.skipped)
    }

    @Test
    fun decodeClaimedPrekeyResponse() {
        val text = """
        {
          "identity_id": "amp:did:key:zABC",
          "key_id": "pk-7",
          "algorithm": "AMP-MLKEM768-V1",
          "public_key_b64": "ZZZZ"
        }
        """.trimIndent()
        val body = json.decodeFromString<ClaimedPrekeyResponse>(text)
        assertEquals("amp:did:key:zABC", body.identityId)
        assertEquals("pk-7", body.keyId)
        assertEquals("AMP-MLKEM768-V1", body.algorithm)
        assertEquals("ZZZZ", body.publicKeyB64)
    }

    @Test
    fun decodeStoreEnvelopeResponse() {
        val text = """
        { "accepted": true, "relay_id": "relay-001" }
        """.trimIndent()
        val body = json.decodeFromString<StoreEnvelopeResponse>(text)
        assertTrue(body.accepted)
        assertEquals("relay-001", body.relayId)
    }

    @Test
    fun decodeRelayErrorBody() {
        val text = """
        { "error": { "code": "prekey_already_used", "message": "key pk-7 was claimed already" } }
        """.trimIndent()
        val body = json.decodeFromString<RelayErrorBody>(text)
        assertEquals("prekey_already_used", body.error.code)
        assertEquals("key pk-7 was claimed already", body.error.message)
    }

    @Test
    fun decodePrivatePayload() {
        val text = """
        {
          "private_headers": {
            "subject": "hello",
            "thread_id": null,
            "in_reply_to": null
          },
          "body": { "mime": "text/plain", "content": "hi there" },
          "attachments": [],
          "extensions": null
        }
        """.trimIndent()
        val payload = json.decodeFromString<PrivatePayload>(text)
        assertEquals("hello", payload.privateHeaders.subject)
        assertEquals("text/plain", payload.body.mime)
        assertEquals("hi there", payload.body.content)
        assertTrue(payload.attachments.isEmpty())
    }

    // ─── JSON parity (encode round-trip) ───────────────────────────────────

    @Test
    fun envelopeEncodeRoundTrip() {
        val original = Envelope(
            version = 1,
            envelopeId = "env-rt",
            recipientId = "amp:did:key:zR",
            senderHint = "amp:did:key:zS",
            createdAt = "2026-05-06T00:00:00Z",
            expiresAt = null,
            contentType = "application/aegis-msg+json",
            suiteId = "AMP-HYBRID-X25519-MLKEM768-ED25519-MLDSA65-V1",
            usedPrekeyIds = listOf("pk-1", "pk-2"),
            payload = EncryptedBlob(
                nonceB64 = "AAAA",
                ciphertextB64 = "BBBB",
                ephX25519PublicKeyB64 = "CCCC",
                mlkemCiphertextB64 = "DDDD",
            ),
            outerSignatureB64 = "EE",
            outerPqSignatureB64 = "FF",
        )
        val encoded = json.encodeToString(Envelope.serializer(), original)

        // Snake_case must appear on the wire — what the @SerialName
        // annotations are guarding against.
        assertTrue("envelope_id missing", encoded.contains("\"envelope_id\":\"env-rt\""))
        assertTrue("recipient_id missing", encoded.contains("\"recipient_id\":\"amp:did:key:zR\""))
        assertTrue("used_prekey_ids missing", encoded.contains("\"used_prekey_ids\":[\"pk-1\",\"pk-2\"]"))
        assertTrue("eph_x25519_public_key_b64 missing", encoded.contains("\"eph_x25519_public_key_b64\":\"CCCC\""))
        assertTrue("mlkem_ciphertext_b64 missing", encoded.contains("\"mlkem_ciphertext_b64\":\"DDDD\""))

        // And the round-trip restores the same struct.
        val decoded = json.decodeFromString<Envelope>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun storeEnvelopeRequestEncodesAsExpected() {
        val env = Envelope(
            version = 1,
            envelopeId = "env-x",
            recipientId = "amp:did:key:zR",
            senderHint = null,
            createdAt = "2026-05-06T00:00:00Z",
            expiresAt = null,
            contentType = "application/aegis-msg+json",
            suiteId = "AMP-DEMO-XCHACHA20POLY1305",
            usedPrekeyIds = emptyList(),
            payload = EncryptedBlob(
                nonceB64 = "A",
                ciphertextB64 = "B",
                ephX25519PublicKeyB64 = null,
                mlkemCiphertextB64 = null,
            ),
            outerSignatureB64 = null,
            outerPqSignatureB64 = null,
        )
        val encoded = json.encodeToString(StoreEnvelopeRequest.serializer(), StoreEnvelopeRequest(env))
        assertTrue(encoded.startsWith("{\"envelope\":"))
        // explicitNulls = false, so omitted optionals don't appear.
        assertFalse("expires_at should be omitted when null", encoded.contains("\"expires_at\""))
    }
}
