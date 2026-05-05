package io.aegis.android.core

/**
 * At-rest persistence for the encrypted [PersistedIdentity] blob.
 *
 * Two implementations:
 *   - [KeystoreVaultStore] — production. Encrypts the JSON bytes with
 *     an Android Keystore-backed AES-256-GCM key (alias
 *     `io.aegis.android.vault.kek`), then stores the resulting
 *     ciphertext in `SharedPreferences`. The vault is already encrypted
 *     under the user's passphrase; the Keystore wrap is an additional
 *     device-binding layer so a stolen file can't be read on another
 *     device. Mirrors aegis-apple's `kSecAttrAccessibleWhenUnlocked
 *     ThisDeviceOnly`.
 *   - In-memory test double — used in unit tests so the lifecycle paths
 *     in [Aegis] can be exercised without instrumented testing.
 *
 * The interface is intentionally small (3 methods); both implementations
 * are simple enough to read end-to-end.
 */
interface VaultStore {
    /** Persist (overwrites any existing blob). */
    fun save(persisted: PersistedIdentity)

    /** Load the persisted blob, or null on first run. */
    fun load(): PersistedIdentity?

    /** Wipe persistence. */
    fun delete()
}

/** Test double — keeps the persisted blob in memory only. */
class InMemoryVaultStore : VaultStore {
    private var current: PersistedIdentity? = null

    override fun save(persisted: PersistedIdentity) {
        current = persisted
    }

    override fun load(): PersistedIdentity? = current

    override fun delete() {
        current = null
    }
}
