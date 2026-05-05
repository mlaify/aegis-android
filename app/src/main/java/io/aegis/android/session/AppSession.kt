package io.aegis.android.session

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.aegis.android.core.Aegis
import io.aegis.android.core.AegisIdentity
import io.aegis.android.core.VaultError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Process-wide state for the current launch.
 *
 * State machine, mirroring aegis-apple's `AppSession.State`:
 *   - `FirstRun` — no vault on this device; SetupScreen shows the
 *                  identity-creation flow.
 *   - `Locked`   — vault exists but masterKey isn't held in memory;
 *                  SetupScreen shows the unlock flow.
 *   - `Unlocked(identity)` — vault unlocked, identity available;
 *                  SetupScreen shows the configured identity.
 *
 * Relay URL is persisted via DataStore Preferences; identity persistence
 * is now AndroidX Keystore + SharedPreferences via the AegisCore vault
 * layer ([Aegis.createVault] / [Aegis.unlockVault] / [Aegis.lockVault]).
 */
class AppSession(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        object FirstRun : State
        object Locked : State
        data class Unlocked(val identity: AegisIdentity) : State
    }

    private val _relayUrl = MutableStateFlow<String?>(null)
    val relayUrl: StateFlow<String?> = _relayUrl

    private val _state = MutableStateFlow<State>(State.FirstRun)
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            // Determine initial state by inspecting the Keystore vault.
            // We do the SharedPreferences read off the main thread to
            // avoid the StrictMode warning Compose may emit on cold
            // start.
            _state.value = withContext(Dispatchers.IO) {
                try {
                    if (Aegis.isVaultPresent()) State.Locked else State.FirstRun
                } catch (_: VaultError.NotConfigured) {
                    // If init order is wrong (configure() not called
                    // yet), fail open to FirstRun. Better than getting
                    // stuck in a never-resolving Locked state.
                    State.FirstRun
                }
            }
            val prefs = getApplication<Application>().dataStore.data.first()
            _relayUrl.value = prefs[RELAY_URL_KEY]
        }
    }

    /** Validate, normalize, and persist the relay URL. */
    @Throws(AppSessionException::class)
    suspend fun saveRelayUrl(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw AppSessionException.EmptyRelayUrl
        val parsed = try {
            java.net.URI(trimmed)
        } catch (_: Throwable) {
            throw AppSessionException.InvalidRelayUrl
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw AppSessionException.InvalidRelayUrl
        }
        val canonical = trimmed.trimEnd('/')
        getApplication<Application>().dataStore.edit { it[RELAY_URL_KEY] = canonical }
        _relayUrl.value = canonical
    }

    /** Generate a fresh hybrid PQ identity through the FFI bridge,
     *  sign it, then persist it under the supplied passphrase.
     *  Transitions to [State.Unlocked]. */
    suspend fun createIdentity(identityId: String, passphrase: String) {
        withContext(Dispatchers.IO) {
            val unsigned = Aegis.generateIdentity(identityId)
            val signed = Aegis.signIdentityDocument(unsigned)
            Aegis.createVault(identity = signed, passphrase = passphrase)
            _state.value = State.Unlocked(signed)
        }
    }

    /** Attempt to unlock the on-device vault. On success, transitions
     *  to [State.Unlocked]. On wrong passphrase, leaves state as
     *  [State.Locked] and rethrows. */
    suspend fun unlock(passphrase: String) {
        withContext(Dispatchers.IO) {
            Aegis.unlockVault(passphrase)
            val identity = Aegis.loadIdentity()
            _state.value = State.Unlocked(identity)
        }
    }

    /** Drop the in-memory masterKey. The vault stays in storage. */
    fun lock() {
        Aegis.lockVault()
        _state.value = State.Locked
    }

    /** Wipe the on-device vault entirely. After this the next launch
     *  is a first-run scenario. Used by the "Reset identity" button. */
    suspend fun deleteIdentity() {
        withContext(Dispatchers.IO) {
            Aegis.deleteVault()
            _state.value = State.FirstRun
        }
    }

    /**
     * Generate an [identityId] of the same shape iOS produces:
     * `amp:did:key:z` followed by 32 hex characters from a random
     * UUID. Same algorithm cross-platform so identities look
     * indistinguishable regardless of which client created them.
     */
    fun newIdentityId(): String =
        "amp:did:key:z" + UUID.randomUUID().toString().replace("-", "")

    private companion object {
        val RELAY_URL_KEY = stringPreferencesKey("relay_url")
    }
}

private val Application.dataStore by preferencesDataStore(name = "aegis_prefs")

sealed class AppSessionException(message: String) : Exception(message) {
    object EmptyRelayUrl : AppSessionException("Enter a relay URL.") {
        private fun readResolve(): Any = EmptyRelayUrl
    }
    object InvalidRelayUrl : AppSessionException("Relay URL must be a valid http:// or https:// URL.") {
        private fun readResolve(): Any = InvalidRelayUrl
    }
}
