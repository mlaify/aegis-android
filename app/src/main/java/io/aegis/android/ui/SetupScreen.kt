package io.aegis.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.aegis.android.core.AegisError
import io.aegis.android.core.AegisIdentity
import io.aegis.android.session.AppSession
import io.aegis.android.session.AppSessionException
import kotlinx.coroutines.launch

/**
 * First-run / configuration screen.
 *
 * Two responsibilities for v0, mirroring aegis-apple's SetupView:
 *  1. Save the user's relay URL (persisted via DataStore).
 *  2. Generate a fresh hybrid PQ identity locally via the AegisFFI
 *     bridge, and display the resulting identity_id.
 *
 * Inbox / Compose / Identity-detail / publish-to-relay flows land in
 * follow-up PRs once Setup is solid on both iOS and Android.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    session: AppSession = viewModel(),
) {
    val savedRelayUrl by session.relayUrl.collectAsStateWithLifecycle()
    val identity by session.identity.collectAsStateWithLifecycle()

    var relayUrlInput by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(savedRelayUrl) {
        if (savedRelayUrl != null && relayUrlInput.isEmpty()) {
            relayUrlInput = savedRelayUrl ?: ""
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Aegis") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            RelaySection(
                relayUrlInput = relayUrlInput,
                onRelayUrlInputChange = { relayUrlInput = it },
                savedRelayUrl = savedRelayUrl,
                onSave = {
                    scope.launch {
                        try {
                            session.saveRelayUrl(relayUrlInput)
                            snackbarHostState.showSnackbar("Saved ${session.relayUrl.value ?: ""}")
                        } catch (e: AppSessionException) {
                            snackbarHostState.showSnackbar(e.message ?: "Invalid URL")
                        }
                    }
                },
            )

            HorizontalDivider()

            IdentitySection(
                identity = identity,
                generating = generating,
                onGenerate = {
                    generating = true
                    scope.launch {
                        try {
                            val id = session.newIdentityId()
                            session.generateIdentity(id)
                            snackbarHostState.showSnackbar(
                                "Generated ${session.identity.value?.identityId ?: ""}",
                            )
                        } catch (e: AegisError) {
                            snackbarHostState.showSnackbar("Error — ${e.message}")
                        } catch (e: Throwable) {
                            snackbarHostState.showSnackbar("Error — ${e.message ?: e::class.simpleName}")
                        } finally {
                            generating = false
                        }
                    }
                },
            )

            HorizontalDivider()

            AboutSection()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaySection(
    relayUrlInput: String,
    onRelayUrlInputChange: (String) -> Unit,
    savedRelayUrl: String?,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Relay", style = MaterialTheme.typography.titleMedium)
        Text(
            "The relay accepts encrypted envelopes addressed to your identity. " +
                "It cannot read message contents.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = relayUrlInput,
            onValueChange = onRelayUrlInputChange,
            singleLine = true,
            label = { Text("https://relay.example.com") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onSave,
            enabled = relayUrlInput.trim().isNotEmpty(),
        ) {
            Text("Save relay URL")
        }
        if (!savedRelayUrl.isNullOrEmpty()) {
            Text(
                "Currently using: $savedRelayUrl",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun IdentitySection(
    identity: AegisIdentity?,
    generating: Boolean,
    onGenerate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Identity", style = MaterialTheme.typography.titleMedium)
        Text(
            "Generation is local — keys never leave the device. v0 keeps the " +
                "identity in memory only; AndroidX Keystore-backed storage lands " +
                "in a follow-up.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onGenerate, enabled = !generating) {
            Text(
                when {
                    generating -> "Generating…"
                    identity == null -> "Generate identity"
                    else -> "Regenerate identity"
                },
            )
        }
        if (identity != null) {
            Spacer(Modifier.height(4.dp))
            LabeledRow("Identity ID", identity.identityId)
            LabeledRow("Signed", if (identity.document.signature == null) "no" else "yes")
            LabeledRow("Encryption keys", identity.document.encryptionKeys.size.toString())
            LabeledRow("Signing keys", identity.document.signingKeys.size.toString())
        }
    }
}

@Composable
private fun AboutSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("About", style = MaterialTheme.typography.titleMedium)
        LabeledRow("FFI version", io.aegis.android.core.Aegis.version())
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Column(modifier = Modifier.padding(PaddingValues(vertical = 2.dp))) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
