package io.aegis.android.session

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.aegis.android.core.Aegis
import io.aegis.android.core.AegisIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Process-wide state for the current launch.
 *
 * Mirrors aegis-apple's `AppSession` semantics:
 *   - relay URL persisted across launches (here via DataStore Preferences;
 *     iOS uses UserDefaults).
 *   - identity stays in memory only for v0; encrypted-at-rest persistence
 *     (Android Keystore + EncryptedSharedPreferences) is the next iteration.
 *
 * Exposed as a ViewModel so Compose can observe via `viewModel()` —
 * keeps the iOS @Observable / Android StateFlow patterns each idiomatic
 * to their platform without forcing Android into a SwiftUI mental model.
 */
class AppSession(application: Application) : AndroidViewModel(application) {

    private val _relayUrl = MutableStateFlow<String?>(null)
    val relayUrl: StateFlow<String?> = _relayUrl

    private val _identity = MutableStateFlow<AegisIdentity?>(null)
    val identity: StateFlow<AegisIdentity?> = _identity

    init {
        viewModelScope.launch {
            val prefs = getApplication<Application>().dataStore.data.first()
            _relayUrl.value = prefs[RELAY_URL_KEY]
        }
    }

    /**
     * Validate, normalize, and persist the relay URL.
     *
     * @throws AppSessionException if the URL is empty or its scheme isn't http(s).
     */
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

    /**
     * Generate a fresh hybrid PQ identity through the FFI bridge,
     * sign the document, and replace [identity] with the result.
     *
     * @throws io.aegis.android.core.AegisError on FFI failure.
     */
    fun generateIdentity(identityId: String) {
        val unsigned = Aegis.generateIdentity(identityId)
        _identity.value = Aegis.signIdentityDocument(unsigned)
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
