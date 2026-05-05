package io.aegis.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.aegis.android.core.Aegis
import io.aegis.android.core.AegisError
import io.aegis.android.core.VaultError
import io.aegis.android.session.AppSession
import io.aegis.android.session.AppSessionException
import io.aegis.android.ui.components.IdentityCard
import io.aegis.android.ui.components.LabeledRow
import io.aegis.android.ui.components.RelayEditor
import kotlinx.coroutines.launch

/**
 * Pre-Mail-shell setup surface — only seen while the vault is in
 * `FirstRun` or `Locked`. Once unlocked, [io.aegis.android.ui.RootScreen]
 * swaps the root to [MailScreen]. The post-unlock identity-summary
 * branch in [SetupScreen] still renders if a caller forces it (used in
 * Compose previews) but the live app reaches [MailScreen] instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    session: AppSession = viewModel(),
) {
    val savedRelayUrl by session.relayUrl.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()

    var relayUrlInput by remember { mutableStateOf("") }
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
            RelayEditor(
                relayUrlInput = relayUrlInput,
                onRelayUrlInputChange = { relayUrlInput = it },
                savedRelayUrl = savedRelayUrl,
                onSave = {
                    scope.launch {
                        try {
                            session.saveRelayUrl(relayUrlInput)
                            snackbarHostState.showSnackbar(
                                "Saved ${session.relayUrl.value ?: ""}",
                            )
                        } catch (e: AppSessionException) {
                            snackbarHostState.showSnackbar(e.message ?: "Invalid URL")
                        }
                    }
                },
            )

            HorizontalDivider()

            when (val s = state) {
                AppSession.State.FirstRun ->
                    CreateIdentitySection(
                        session = session,
                        onStatus = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )
                AppSession.State.Locked ->
                    UnlockSection(
                        session = session,
                        onStatus = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )
                is AppSession.State.Unlocked ->
                    IdentityCard(
                        session = session,
                        identity = s.identity,
                        onStatus = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )
            }

            HorizontalDivider()

            AboutSection()
        }
    }
}

@Composable
private fun CreateIdentitySection(
    session: AppSession,
    onStatus: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var passphraseConfirm by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Create identity", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your identity is generated locally and encrypted at rest under " +
                "the passphrase you choose. Keys never leave the device. The " +
                "passphrase isn't recoverable — if you forget it you'll need " +
                "to reset the identity and start over.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            singleLine = true,
            label = { Text("Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = passphraseConfirm,
            onValueChange = { passphraseConfirm = it },
            singleLine = true,
            label = { Text("Confirm passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                creating = true
                scope.launch {
                    try {
                        val id = session.newIdentityId()
                        session.createIdentity(identityId = id, passphrase = passphrase)
                        onStatus("Created $id")
                        passphrase = ""
                        passphraseConfirm = ""
                    } catch (e: AegisError) {
                        onStatus("Error — ${e.message}")
                    } catch (e: VaultError) {
                        onStatus("Error — ${e.message}")
                    } catch (e: Throwable) {
                        onStatus("Error — ${e.message ?: e::class.simpleName}")
                    } finally {
                        creating = false
                    }
                }
            },
            enabled = !creating
                && passphrase.isNotEmpty()
                && passphrase == passphraseConfirm,
        ) {
            Text(if (creating) "Creating…" else "Create identity")
        }
    }
}

@Composable
private fun UnlockSection(
    session: AppSession,
    onStatus: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var unlocking by remember { mutableStateOf(false) }
    var passkeyUnlocking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hasPasskey = remember { session.hasPasskeyMethod() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Unlock", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your encrypted identity vault is on this device. Enter the " +
                "passphrase you set when you created it. \"Reset\" wipes the " +
                "local vault — the identity itself stays whatever the relay " +
                "knows about, but you'll lose the keys to operate it from " +
                "this device.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            singleLine = true,
            label = { Text("Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                unlocking = true
                scope.launch {
                    try {
                        session.unlock(passphrase = passphrase)
                        passphrase = ""
                    } catch (e: VaultError.WrongPassphrase) {
                        onStatus("Wrong passphrase.")
                    } catch (e: Throwable) {
                        onStatus("Unlock failed — ${e.message ?: e::class.simpleName}")
                    } finally {
                        unlocking = false
                    }
                }
            },
            enabled = !unlocking && passphrase.isNotEmpty(),
        ) {
            Text(if (unlocking) "Unlocking…" else "Unlock")
        }
        if (hasPasskey) {
            Button(
                onClick = {
                    passkeyUnlocking = true
                    scope.launch {
                        try {
                            val ok = session.unlockWithPasskey()
                            if (!ok) onStatus("No passkeys are enrolled on this device.")
                        } catch (e: io.aegis.android.passkey.PasskeyError.UserCancelled) {
                            // User dismissed the prompt — quiet.
                        } catch (e: Throwable) {
                            onStatus("Passkey unlock failed — ${e.message ?: e::class.simpleName}")
                        } finally {
                            passkeyUnlocking = false
                        }
                    }
                },
                enabled = !passkeyUnlocking,
            ) {
                Text(if (passkeyUnlocking) "Unlocking…" else "Unlock with Passkey")
            }
        }
        Button(
            onClick = {
                scope.launch {
                    try {
                        session.deleteIdentity()
                    } catch (e: Throwable) {
                        onStatus("Reset failed — ${e.message ?: e::class.simpleName}")
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text("Reset identity (wipe vault)")
        }
    }
}

@Composable
private fun AboutSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("About", style = MaterialTheme.typography.titleMedium)
        LabeledRow("FFI version", Aegis.version())
    }
}
