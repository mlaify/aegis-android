package io.aegis.android.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Unit tests for the v2 vault format introduced in PR aegis-android#3.
// Run on the JVM unit-test classpath; the production KeystoreVaultStore
// requires Android Context + the Android Keystore service, so these
// tests use [InMemoryVaultStore] for the lifecycle paths and exercise
// the Codable + crypto layers directly.
//
// JNI host lib must exist under ../aegis-ffi/target/release/ — run
// `./scripts/build-jni.sh` first.

class VaultTest {

    @Before
    fun setUp() {
        Aegis.configure(InMemoryVaultStore())
    }

    @After
    fun tearDown() {
        // Best-effort cleanup; the next test will wire its own
        // InMemoryVaultStore via @Before.
        runCatching { Aegis.deleteVault() }
    }

    // --------------------------------------------------------------
    // JSON parity with the web v2 schema
    // --------------------------------------------------------------

    @Test
    fun `vault json uses snake_case keys matching the web schema`() {
        val vault = Vault(
            masterIvB64 = "AAECAwQFBgcICQoL",
            ciphertextB64 = "Y2lwaGVydGV4dA==",
            unlockMethods = listOf(
                PassphraseMethod(
                    id = "abc123==",
                    enrolledAt = "2026-05-05T00:00:00Z",
                    label = "test",
                    saltB64 = "c2FsdA==",
                    iterations = 100_000,
                    ivB64 = "aXY=",
                    wrappedMasterKeyB64 = "d3JhcA==",
                ),
            ),
        )
        val json = Json { encodeDefaults = true }.encodeToString(Vault.serializer(), vault)
        assertTrue(json.contains("\"master_iv_b64\""))
        assertTrue(json.contains("\"ciphertext_b64\""))
        assertTrue(json.contains("\"unlock_methods\""))
        assertTrue(json.contains("\"enrolled_at\""))
        assertTrue(json.contains("\"salt_b64\""))
        assertTrue(json.contains("\"wrapped_master_key_b64\""))
        // Reject Kotlin camelCase leakage that would break cross-platform
        // interop.
        assertFalse(json.contains("masterIvB64"))
        assertFalse(json.contains("wrappedMasterKeyB64"))
        assertFalse(json.contains("enrolledAt"))
    }

    @Test
    fun `web format vault round-trips through the Kotlin decoder`() {
        // Hand-built JSON in the exact shape aegis-web writes; ensures
        // we can deserialize a vault produced by the web client (and
        // by aegis-apple, which uses the same schema).
        val webJson = """
            {
              "version": 2,
              "master_iv_b64": "AAECAwQFBgcICQoL",
              "ciphertext_b64": "Y2lwaGVydGV4dA==",
              "unlock_methods": [
                {
                  "type": "passphrase",
                  "id": "fromweb=",
                  "enrolled_at": "2026-05-05T00:00:00Z",
                  "salt_b64": "c2FsdA==",
                  "iterations": 100000,
                  "iv_b64": "aXY=",
                  "wrapped_master_key_b64": "d3JhcA=="
                },
                {
                  "type": "passkey",
                  "id": "pk1=",
                  "enrolled_at": "2026-05-05T01:00:00Z",
                  "label": "MacBook Touch ID",
                  "credential_id_b64": "Y3JlZA==",
                  "prf_salt_b64": "c2FsdDI=",
                  "iv_b64": "aXY=",
                  "wrapped_master_key_b64": "d3JhcDI="
                }
              ]
            }
        """.trimIndent()
        val vault = Json { ignoreUnknownKeys = true }
            .decodeFromString(Vault.serializer(), webJson)
        assertEquals(2, vault.version)
        assertEquals(2, vault.unlockMethods.size)

        val first = vault.unlockMethods[0]
        assertTrue("first method should be passphrase", first is PassphraseMethod)
        assertEquals("fromweb=", (first as PassphraseMethod).id)
        assertEquals(100_000, first.iterations)

        val second = vault.unlockMethods[1]
        assertTrue("second method should be passkey", second is PasskeyMethod)
        assertEquals("Y3JlZA==", (second as PasskeyMethod).credentialIdB64)
        assertEquals("MacBook Touch ID", second.label)
    }

    // --------------------------------------------------------------
    // VaultCrypto primitives
    // --------------------------------------------------------------

    @Test
    fun `pbkdf2 derives a stable key`() {
        val salt = ByteArray(16) { it.toByte() }
        val k1 = VaultCrypto.derivePassphraseKey("hunter2", salt, 1_000)
        val k2 = VaultCrypto.derivePassphraseKey("hunter2", salt, 1_000)
        assertEquals(32, k1.encoded.size)
        assertTrue(k1.encoded.contentEquals(k2.encoded))
    }

    @Test
    fun `wrap and unwrap master key round-trip`() {
        val masterKey = VaultCrypto.generateMasterKey()
        val kek = VaultCrypto.generateMasterKey()
        val (iv, wrapped) = VaultCrypto.wrapMasterKey(masterKey, kek)
        val recovered = VaultCrypto.unwrapMasterKey(wrapped, iv, kek)
        assertTrue(masterKey.encoded.contentEquals(recovered.encoded))
    }

    @Test
    fun `unwrap with wrong kek throws WrongPassphrase`() {
        val masterKey = VaultCrypto.generateMasterKey()
        val kek = VaultCrypto.generateMasterKey()
        val wrongKek = VaultCrypto.generateMasterKey()
        val (iv, wrapped) = VaultCrypto.wrapMasterKey(masterKey, kek)
        try {
            VaultCrypto.unwrapMasterKey(wrapped, iv, wrongKek)
            fail("expected VaultError.WrongPassphrase")
        } catch (_: VaultError.WrongPassphrase) {
            // expected
        }
    }

    @Test
    fun `encrypt and decrypt secrets round-trip`() {
        val masterKey = VaultCrypto.generateMasterKey()
        val secrets =
            """{"identity_id":"amp:did:key:zX","x25519_private_key_b64":"AAAA"}"""
        val (iv, ciphertext) = VaultCrypto.encryptSecrets(secrets, masterKey)
        val recovered = VaultCrypto.decryptSecrets(ciphertext, iv, masterKey)
        assertEquals(secrets, recovered)
    }

    // --------------------------------------------------------------
    // Aegis facade lifecycle (in-memory store)
    // --------------------------------------------------------------

    @Test
    fun `create then unlock then loadIdentity round-trip`() {
        val id = "amp:did:key:zVaultLifecycle"
        val identity = Aegis.generateIdentity(id)
        val signed = Aegis.signIdentityDocument(identity)

        Aegis.createVault(identity = signed, passphrase = "correct horse")
        assertTrue(Aegis.isVaultPresent())
        assertFalse(Aegis.isVaultLocked())

        // Lock and re-unlock — the path that exercises the wrap/unwrap
        // round trip (createVault stashes the masterKey directly).
        Aegis.lockVault()
        assertTrue(Aegis.isVaultLocked())

        Aegis.unlockVault("correct horse")
        assertFalse(Aegis.isVaultLocked())

        val loaded = Aegis.loadIdentity()
        assertEquals(id, loaded.identityId)
        assertEquals(signed.documentJson, loaded.documentJson)
        assertEquals(signed.secretsJson, loaded.secretsJson)
    }

    @Test
    fun `unlock with wrong passphrase throws`() {
        val identity = Aegis.generateIdentity("amp:did:key:zWrongPass")
        Aegis.createVault(identity = identity, passphrase = "right")
        Aegis.lockVault()

        try {
            Aegis.unlockVault("wrong")
            fail("expected VaultError.WrongPassphrase")
        } catch (_: VaultError.WrongPassphrase) {
            // expected
        }
        assertTrue(Aegis.isVaultLocked())
    }

    @Test
    fun `listUnlockMethods after create`() {
        val identity = Aegis.generateIdentity("amp:did:key:zList")
        Aegis.createVault(identity = identity, passphrase = "p", passphraseLabel = "device")
        val methods = Aegis.listUnlockMethods()
        assertEquals(1, methods.size)
        assertEquals("passphrase", methods[0].type)
        assertEquals("device", methods[0].label)
    }

    @Test
    fun `removeUnlockMethod refuses to remove the last passphrase`() {
        val identity = Aegis.generateIdentity("amp:did:key:zRemoveLast")
        Aegis.createVault(identity = identity, passphrase = "p")
        val methods = Aegis.listUnlockMethods()
        try {
            Aegis.removeUnlockMethod(methods[0].id)
            fail("expected VaultError.WouldRemoveLastPassphrase")
        } catch (_: VaultError.WouldRemoveLastPassphrase) {
            // expected
        }
    }

    @Test
    fun `deleteVault clears session and store`() {
        val identity = Aegis.generateIdentity("amp:did:key:zDelete")
        Aegis.createVault(identity = identity, passphrase = "p")
        assertTrue(Aegis.isVaultPresent())

        Aegis.deleteVault()
        assertFalse(Aegis.isVaultPresent())
        assertTrue(Aegis.isVaultLocked())
    }

    @Test
    fun `web format vault decrypts when given the matching passphrase via test seam`() {
        // Build a Vault end-to-end through Aegis using a well-known
        // passphrase, then confirm we can re-load it through the same
        // store (simulating "this device's storage was a vault written
        // earlier"). The interesting bit isn't the JSON fields — it's
        // that the AES-GCM tag survives a serialize/deserialize round
        // trip through kotlinx.serialization (no surprise base64
        // canonicalization differences).
        val identity = Aegis.generateIdentity("amp:did:key:zSerialize")
        Aegis.createVault(identity = identity, passphrase = "round-trip-pass")
        Aegis.lockVault()

        // Round-trip the persisted blob through JSON to simulate
        // cross-platform import — same shape we'd ingest from the web
        // client's IndexedDB export.
        val store = InMemoryVaultStore()
        val source = (Aegis.javaClass.getDeclaredField("_store"))
            .also { it.isAccessible = true }
            .get(Aegis) as VaultStore
        val persisted = source.load()!!
        val json = Json { encodeDefaults = true }.encodeToString(
            PersistedIdentity.serializer(), persisted,
        )
        val reloaded = Json { ignoreUnknownKeys = true }
            .decodeFromString(PersistedIdentity.serializer(), json)
        store.save(reloaded)
        Aegis.configure(store)
        Aegis.unlockVault("round-trip-pass")

        val loaded = Aegis.loadIdentity()
        assertEquals(identity.identityId, loaded.identityId)

        // Sanity-check: the round-tripped JSON has the document_json
        // wrapper field from PersistedIdentity, not a bare Vault.
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertNotNull(parsed["document_json"])
        assertNotNull(parsed["vault"])
        assertEquals(2, parsed["vault"]!!.jsonObject["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `two identities produce distinct vault ciphertexts`() {
        val a = Aegis.generateIdentity("amp:did:key:zA")
        Aegis.createVault(identity = a, passphrase = "p")
        val storeA = (Aegis.javaClass.getDeclaredField("_store"))
            .also { it.isAccessible = true }
            .get(Aegis) as VaultStore
        val ctA = storeA.load()!!.vault.ciphertextB64

        // Reset and create a second identity with the same passphrase.
        Aegis.deleteVault()
        Aegis.configure(InMemoryVaultStore())
        val b = Aegis.generateIdentity("amp:did:key:zB")
        Aegis.createVault(identity = b, passphrase = "p")
        val storeB = (Aegis.javaClass.getDeclaredField("_store"))
            .also { it.isAccessible = true }
            .get(Aegis) as VaultStore
        val ctB = storeB.load()!!.vault.ciphertextB64

        assertNotEquals(
            "fresh identities should produce distinct vault ciphertexts even " +
                "with the same passphrase (random masterKey + IV)",
            ctA, ctB,
        )
    }

    // --------------------------------------------------------------
    // Passkey method (PRF-derived KEK) — vault-side bookkeeping
    //
    // We can't actually drive CredentialManager from a unit test
    // (no UI, no platform Passkey provider), so these exercise the
    // Aegis facade with a synthetic 32-byte PRF output — pretending
    // the WebAuthn assertion happened and we got back these bytes.
    // The crypto path (AES-GCM wrap/unwrap with the PRF bytes as
    // KEK) is what matters here; the CredentialManager integration
    // is exercised manually on real devices.
    // --------------------------------------------------------------

    @Test
    fun `enrollPasskey persists method and round-trips through unlockWithPrf`() {
        val id = "amp:did:key:zPasskeyEnroll"
        val identity = Aegis.generateIdentity(id)
        val signed = Aegis.signIdentityDocument(identity)
        Aegis.createVault(identity = signed, passphrase = "pp")

        val prfOutput = ByteArray(32) { it.toByte() }
        val credentialId = ByteArray(16) { (it * 7).toByte() }
        val salt = ByteArray(32) { (it * 3).toByte() }

        val methodId = Aegis.enrollPasskeyMethod(
            prfOutput = prfOutput,
            credentialIdB64 = java.util.Base64.getEncoder().encodeToString(credentialId),
            prfSaltB64 = java.util.Base64.getEncoder().encodeToString(salt),
            label = "Test Passkey",
        )
        assertTrue(methodId.isNotEmpty())

        // Method appears in the vault metadata.
        val methods = Aegis.listUnlockMethods()
        assertEquals(2, methods.size) // passphrase + passkey
        assertTrue(methods.any { it.type == "passkey" && it.label == "Test Passkey" })

        // Listed in the passkey-specific challenge view too.
        val challenges = Aegis.listPasskeyChallenges()
        assertEquals(1, challenges.size)
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(credentialId),
            challenges[0].credentialIdB64,
        )

        // Lock + unlock with the synthetic PRF output round-trips
        // through the same KEK derivation path the live Passkey flow
        // uses.
        Aegis.lockVault()
        assertTrue(Aegis.isVaultLocked())

        Aegis.unlockVaultWithPrf(
            prfOutput = prfOutput,
            credentialIdB64 = java.util.Base64.getEncoder().encodeToString(credentialId),
        )
        assertFalse(Aegis.isVaultLocked())

        val loaded = Aegis.loadIdentity()
        assertEquals(id, loaded.identityId)
        assertEquals(signed.secretsJson, loaded.secretsJson)
    }

    @Test
    fun `enrollPasskey rejects locked vault`() {
        val identity = Aegis.generateIdentity("amp:did:key:zNoEnroll")
        Aegis.createVault(identity = identity, passphrase = "pp")
        Aegis.lockVault()
        try {
            Aegis.enrollPasskeyMethod(
                prfOutput = ByteArray(32),
                credentialIdB64 = "AAAA",
                prfSaltB64 = "AAAA",
            )
            fail("expected VaultError.Locked")
        } catch (_: VaultError.Locked) {
            // expected
        }
    }

    @Test
    fun `unlockWithPrf throws WrongPassphrase on wrong PRF output`() {
        val identity = Aegis.generateIdentity("amp:did:key:zWrongPrf")
        Aegis.createVault(identity = identity, passphrase = "pp")
        val credentialId = ByteArray(16) { it.toByte() }
        val realPrf = ByteArray(32) { 0xAB.toByte() }
        val wrongPrf = ByteArray(32) { 0xCD.toByte() }

        Aegis.enrollPasskeyMethod(
            prfOutput = realPrf,
            credentialIdB64 = java.util.Base64.getEncoder().encodeToString(credentialId),
            prfSaltB64 = "c2FsdA==",
        )
        Aegis.lockVault()

        try {
            Aegis.unlockVaultWithPrf(
                prfOutput = wrongPrf,
                credentialIdB64 = java.util.Base64.getEncoder().encodeToString(credentialId),
            )
            fail("expected VaultError.WrongPassphrase on wrong PRF")
        } catch (_: VaultError.WrongPassphrase) {
            // expected
        }
    }

    @Test
    fun `removeUnlockMethod can drop a passkey while keeping the passphrase`() {
        val identity = Aegis.generateIdentity("amp:did:key:zRmPasskey")
        Aegis.createVault(identity = identity, passphrase = "pp")
        val credId = ByteArray(16) { it.toByte() }
        val pkId = Aegis.enrollPasskeyMethod(
            prfOutput = ByteArray(32) { 0x55.toByte() },
            credentialIdB64 = java.util.Base64.getEncoder().encodeToString(credId),
            prfSaltB64 = "c2FsdA==",
        )
        Aegis.removeUnlockMethod(pkId)
        val methods = Aegis.listUnlockMethods()
        assertEquals(1, methods.size)
        assertEquals("passphrase", methods[0].type)
    }

    @Test
    fun `unconfigured Aegis throws NotConfigured on vault calls`() {
        // Replace the store via reflection to clear it.
        Aegis.javaClass.getDeclaredField("_store")
            .also { it.isAccessible = true }
            .set(Aegis, null)
        try {
            Aegis.isVaultPresent()
            fail("expected VaultError.NotConfigured")
        } catch (_: VaultError.NotConfigured) {
            // expected
        }
    }
}
