package io.aegis.android.core

import android.content.Context
import java.time.Instant
import javax.crypto.SecretKey
import kotlinx.serialization.json.Json
import uniffi.aegis_ffi.FfiException
import uniffi.aegis_ffi.generateIdentity as ffiGenerateIdentity
import uniffi.aegis_ffi.signIdentityDocument as ffiSignIdentityDocument
import uniffi.aegis_ffi.version as ffiVersion

/**
 * Top-level facade over the AegisFFI bridge + the on-device vault.
 *
 * Mirrors aegis-apple's `Aegis` Swift enum and aegis-web's storage.ts
 * exports — same surface, same semantics. JSON crosses the FFI
 * boundary in both directions; typed Kotlin records ([IdentityDocument],
 * [AegisIdentity], [Vault]) wrap what the app code actually consumes.
 *
 * Vault lifecycle:
 *   1. App init: call [configureWithKeystore] (in `MainActivity.onCreate`
 *      or an `Application.onCreate` subclass).
 *   2. First launch (no Keystore item): caller asks the user for a
 *      passphrase, calls [createVault]. Vault is written to Keystore;
 *      the in-memory session is unlocked.
 *   3. Subsequent launches (Keystore item exists): caller asks the
 *      user for the passphrase, calls [unlockVault]. Vault is decrypted;
 *      the in-memory session is unlocked. [loadIdentity] then yields
 *      the persistent identity.
 *   4. Lock: [lockVault] drops the in-memory master key. The vault
 *      stays in Keystore.
 *
 * The unlocked-session state lives in module-private storage on Aegis
 * itself. This is fine for an Android app process; Compose state is
 * driven separately via [io.aegis.android.session.AppSession].
 */
object Aegis {

    // ----------------------------------------------------------------------
    // FFI passthroughs (unchanged from PR aegis-android#1)
    // ----------------------------------------------------------------------

    /** FFI crate version. Useful for confirming the bridge is wired up. */
    fun version(): String = ffiVersion()

    /** Generate a fresh hybrid PQ identity. The returned identity is
     *  *unsigned* — call [signIdentityDocument] before publishing it. */
    @Throws(AegisError::class)
    fun generateIdentity(identityId: String): AegisIdentity {
        val bundle = try {
            ffiGenerateIdentity(identityId)
        } catch (e: FfiException) {
            throw AegisError.from(e)
        }
        return AegisIdentity.from(
            documentJson = bundle.documentJson,
            secretsJson = bundle.secretsJson,
        )
    }

    /** Attach the hybrid Ed25519 + ML-DSA-65 signature to the supplied
     *  identity. Returns a new [AegisIdentity] whose `document.signature`
     *  is populated; the caller's input is not mutated. */
    @Throws(AegisError::class)
    fun signIdentityDocument(identity: AegisIdentity): AegisIdentity {
        val signedJson = try {
            ffiSignIdentityDocument(identity.documentJson, identity.secretsJson)
        } catch (e: FfiException) {
            throw AegisError.from(e)
        }
        return AegisIdentity.from(
            documentJson = signedJson,
            secretsJson = identity.secretsJson,
        )
    }

    // ----------------------------------------------------------------------
    // Vault config
    // ----------------------------------------------------------------------

    private var _store: VaultStore? = null

    /** Production wiring — call once at app init. */
    fun configureWithKeystore(context: Context) {
        _store = KeystoreVaultStore(context)
    }

    /** Test seam: inject a custom store (e.g. [InMemoryVaultStore]). */
    fun configure(store: VaultStore) {
        _store = store
    }

    private fun store(): VaultStore = _store ?: throw VaultError.NotConfigured

    // ----------------------------------------------------------------------
    // Vault state
    // ----------------------------------------------------------------------

    /** True if the on-device store holds a vault for this app. */
    fun isVaultPresent(): Boolean = store().load() != null

    /** True when no master key is held in memory. */
    fun isVaultLocked(): Boolean = Session.masterKey == null

    // ----------------------------------------------------------------------
    // Vault lifecycle
    // ----------------------------------------------------------------------

    /** First-run setup. Persist [identity] encrypted under a fresh
     *  master key, with a single passphrase unlock method derived from
     *  [passphrase]. The session is left unlocked so the caller can
     *  immediately use the identity. */
    @Throws(VaultError::class, AegisError::class)
    fun createVault(
        identity: AegisIdentity,
        passphrase: String,
        passphraseLabel: String? = null,
    ) {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val masterKey = VaultCrypto.generateMasterKey()
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_BYTES)
        val kek = VaultCrypto.derivePassphraseKey(
            passphrase, salt, VaultCrypto.PBKDF2_ITERATIONS,
        )
        val (wrapIv, wrapped) = VaultCrypto.wrapMasterKey(masterKey, kek)
        val (secretIv, ciphertext) = VaultCrypto.encryptSecrets(
            identity.secretsJson, masterKey,
        )
        val method = PassphraseMethod(
            id = VaultCrypto.newMethodId(),
            enrolledAt = Instant.now().toString(),
            label = passphraseLabel,
            saltB64 = b64(salt),
            iterations = VaultCrypto.PBKDF2_ITERATIONS,
            ivB64 = b64(wrapIv),
            wrappedMasterKeyB64 = b64(wrapped),
        )
        val vault = Vault(
            masterIvB64 = b64(secretIv),
            ciphertextB64 = b64(ciphertext),
            unlockMethods = listOf(method),
        )
        val persisted = PersistedIdentity(
            documentJson = identity.documentJson,
            vault = vault,
        )
        store().save(persisted)
        Session.masterKey = masterKey
        Session.unlockedSecretsJson = identity.secretsJson
        Session.unlockedDocumentJson = identity.documentJson
    }

    /** Unlock the on-device vault with a passphrase. Tries each
     *  passphrase method in turn (the right one succeeds against
     *  exactly one; AEAD authentication fails the rest).
     *
     *  Throws [VaultError.WrongPassphrase] if no passphrase method
     *  accepts the input; other [VaultError] cases for storage / schema
     *  problems. */
    @Throws(VaultError::class)
    fun unlockVault(passphrase: String) {
        val persisted = store().load() ?: throw VaultError.NoVault
        if (persisted.vault.version != 2) {
            throw VaultError.UnsupportedVersion(persisted.vault.version)
        }
        val passphraseMethods =
            persisted.vault.unlockMethods.filterIsInstance<PassphraseMethod>()
        if (passphraseMethods.isEmpty()) throw VaultError.NoPassphraseMethod

        var lastError: Throwable? = null
        for (method in passphraseMethods) {
            try {
                val kek = VaultCrypto.derivePassphraseKey(
                    passphrase, fromB64(method.saltB64), method.iterations,
                )
                val masterKey = VaultCrypto.unwrapMasterKey(
                    fromB64(method.wrappedMasterKeyB64),
                    fromB64(method.ivB64),
                    kek,
                )
                val secretsJson = VaultCrypto.decryptSecrets(
                    fromB64(persisted.vault.ciphertextB64),
                    fromB64(persisted.vault.masterIvB64),
                    masterKey,
                )
                // Sanity-check: secretsJson must parse as a JSON object.
                // Catches the rare case of a vault that decrypts cleanly
                // (right passphrase, intact ciphertext) but holds
                // corrupt content — better to surface clearly than hand
                // garbage to the FFI.
                Json.parseToJsonElement(secretsJson)
                Session.masterKey = masterKey
                Session.unlockedSecretsJson = secretsJson
                Session.unlockedDocumentJson = persisted.documentJson
                return
            } catch (e: VaultError.WrongPassphrase) {
                lastError = e
                continue
            } catch (e: Exception) {
                lastError = e
                continue
            }
        }
        throw lastError as? VaultError ?: VaultError.WrongPassphrase
    }

    /** Drop the in-memory master key. The vault stays in storage. */
    fun lockVault() {
        Session.masterKey = null
        Session.unlockedSecretsJson = null
        Session.unlockedDocumentJson = null
    }

    /** Wipe the vault from this device. Used by the "Reset identity"
     *  flow and by tests. After this the next launch is first-run
     *  again. */
    fun deleteVault() {
        store().delete()
        lockVault()
    }

    // ----------------------------------------------------------------------
    // Identity + method introspection
    // ----------------------------------------------------------------------

    /** Once unlocked, fetch the persistent identity (typed document +
     *  the opaque-JSON secrets blob the FFI will round-trip on every
     *  sign / seal / open call). */
    @Throws(VaultError::class, AegisError::class)
    fun loadIdentity(): AegisIdentity {
        val secretsJson = Session.unlockedSecretsJson ?: throw VaultError.Locked
        val documentJson = Session.unlockedDocumentJson ?: throw VaultError.Locked
        return AegisIdentity.from(
            documentJson = documentJson,
            secretsJson = secretsJson,
        )
    }

    /** Snapshot of currently enrolled unlock methods. Suitable for UI
     *  listings (the IDs are what [removeUnlockMethod] takes). */
    fun listUnlockMethods(): List<UnlockMethodSummary> {
        val persisted = store().load() ?: return emptyList()
        return persisted.vault.unlockMethods.map {
            UnlockMethodSummary(
                id = it.id,
                type = it.type,
                label = it.label,
                enrolledAt = it.enrolledAt,
            )
        }
    }

    /** Remove an enrolled unlock method by id. Refuses to remove the
     *  last passphrase method. No session unlock is required. */
    @Throws(VaultError::class)
    fun removeUnlockMethod(id: String) {
        val persisted = store().load() ?: throw VaultError.NoVault
        val target = persisted.vault.unlockMethods.firstOrNull { it.id == id }
            ?: throw VaultError.NoSuchMethod(id)
        val remaining = persisted.vault.unlockMethods - target
        if (remaining.none { it is PassphraseMethod }) {
            throw VaultError.WouldRemoveLastPassphrase
        }
        store().save(
            PersistedIdentity(
                documentJson = persisted.documentJson,
                vault = persisted.vault.copy(unlockMethods = remaining),
            )
        )
    }
}

// MARK: - Public read-only summary

data class UnlockMethodSummary(
    val id: String,
    /** Either `"passphrase"` or `"passkey"`. */
    val type: String,
    val label: String?,
    val enrolledAt: String,
)

// MARK: - Process-wide session state

private object Session {
    var masterKey: SecretKey? = null
    var unlockedSecretsJson: String? = null
    var unlockedDocumentJson: String? = null
}

// MARK: - Helpers

private fun b64(bytes: ByteArray): String =
    java.util.Base64.getEncoder().encodeToString(bytes)

private fun fromB64(s: String): ByteArray =
    java.util.Base64.getDecoder().decode(s)
