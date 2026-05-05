package io.aegis.android.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Vault crypto primitives. Wire-compatible with aegis-web's Web Crypto
// implementation and aegis-apple's CommonCrypto+CryptoKit pair:
//   - PBKDF2-SHA256, 100k iterations, 16-byte salt → 32-byte key
//   - AES-256-GCM with a 12-byte IV; the 16-byte tag is appended to the
//     ciphertext (matches both Web Crypto and CryptoKit output shape)
//   - The 32-byte master key wraps the JSON-encoded identity-secrets
//     blob; each unlock method holds its own AES-GCM-wrapped copy of
//     master key.
//
// We use only the standard JCA: SecretKeyFactory(PBKDF2WithHmacSHA256)
// for derivation and Cipher(AES/GCM/NoPadding) for AEAD. No external
// crypto deps; works on every Android API ≥ 26 we target.

internal object VaultCrypto {
    const val PBKDF2_ITERATIONS: Int = 100_000
    const val SALT_BYTES: Int = 16
    const val IV_BYTES: Int = 12          // AES-GCM standard IV size
    const val TAG_BITS: Int = 128         // AES-GCM standard tag size
    const val MASTER_KEY_BYTES: Int = 32  // AES-256

    private val random: SecureRandom by lazy { SecureRandom() }

    // -----------------------------------------------------------------------
    // PBKDF2 → AES key
    // -----------------------------------------------------------------------

    /** Derive a 32-byte AES-GCM key from a UTF-8 passphrase via
     *  PBKDF2WithHmacSHA256. Mirrors `derivePassphraseKek` in aegis-web
     *  and `derivePassphraseKey` in aegis-apple. */
    fun derivePassphraseKey(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
    ): SecretKey {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val spec = PBEKeySpec(
            passphrase.toCharArray(),
            salt,
            iterations,
            MASTER_KEY_BYTES * 8,
        )
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val secret = factory.generateSecret(spec)
            // PBEKey isn't directly an AES key; re-wrap the raw bytes.
            return SecretKeySpec(secret.encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    // -----------------------------------------------------------------------
    // Master key
    // -----------------------------------------------------------------------

    /** Generate a fresh 256-bit master key. We hold its raw bytes long
     *  enough to wrap them; once wrapped, only the wrapped copy is
     *  persisted and we drop the unwrapped reference once the session
     *  is locked. */
    fun generateMasterKey(): SecretKey {
        val bytes = ByteArray(MASTER_KEY_BYTES)
        random.nextBytes(bytes)
        return SecretKeySpec(bytes, "AES")
    }

    /** Wrap the master key under a KEK: returns the IV used and the
     *  resulting AES-GCM ciphertext+tag concatenated, matching the web
     *  vault's `wrapped_master_key_b64` shape. */
    fun wrapMasterKey(masterKey: SecretKey, kek: SecretKey): Pair<ByteArray, ByteArray> {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
        val wrapped = cipher.doFinal(masterKey.encoded)
        return iv to wrapped
    }

    /** Inverse of `wrapMasterKey`. Throws `VaultError.WrongPassphrase`
     *  on AES-GCM authentication failure (typical when the user typed
     *  the wrong passphrase — its derived key fails to authenticate
     *  against the stored ciphertext+tag). */
    fun unwrapMasterKey(
        wrapped: ByteArray,
        iv: ByteArray,
        kek: SecretKey,
    ): SecretKey {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
            val raw = cipher.doFinal(wrapped)
            return SecretKeySpec(raw, "AES")
        } catch (e: javax.crypto.AEADBadTagException) {
            throw VaultError.WrongPassphrase
        } catch (e: javax.crypto.BadPaddingException) {
            // BadPaddingException is the JCA's AEAD-failure surface on
            // older Android versions where AEADBadTagException isn't
            // raised separately.
            throw VaultError.WrongPassphrase
        } catch (e: Exception) {
            throw VaultError.CryptoFailure("unwrap failed: ${e.message ?: e::class.simpleName}", e)
        }
    }

    // -----------------------------------------------------------------------
    // Identity secrets blob
    // -----------------------------------------------------------------------

    /** Encrypt the identity-secrets JSON with the master key. Returns
     *  the IV and ciphertext+tag concatenated, mirroring the web vault's
     *  `master_iv_b64` / `ciphertext_b64` storage layout. */
    fun encryptSecrets(secretsJson: String, masterKey: SecretKey): Pair<ByteArray, ByteArray> {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(secretsJson.encodeToByteArray())
        return iv to ct
    }

    /** Decrypt the identity-secrets JSON with the master key. The
     *  AEAD tag protects against any vault bit-flip; an attacker
     *  can't substitute one ciphertext for another without breaking
     *  authentication. */
    fun decryptSecrets(
        ciphertext: ByteArray,
        iv: ByteArray,
        masterKey: SecretKey,
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return plaintext.decodeToString()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun randomBytes(count: Int): ByteArray {
        val b = ByteArray(count)
        random.nextBytes(b)
        return b
    }

    /** Random per-method ID: 8 bytes base64 encoded, matching the web /
     *  Apple `newMethodId()` shape so id strings are interchangeable.
     *  Uses `java.util.Base64` (available on Android API ≥ 26) so this
     *  function works on the JVM unit-test classpath too. */
    fun newMethodId(): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(randomBytes(8))
}
