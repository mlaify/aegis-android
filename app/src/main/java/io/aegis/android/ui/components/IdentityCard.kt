// Reusable "identity unlocked" summary card. Same surface used by the
// pre-Mail-shell SetupScreen (post-unlock) and the SettingsScreen
// Identity tab inside the Mail shell. Lock + Reset buttons live here
// — they're the destructive/state-changing controls scoped to the
// unlocked identity.

package io.aegis.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aegis.android.core.AegisIdentity
import io.aegis.android.session.AppSession
import kotlinx.coroutines.launch

@Composable
fun IdentityCard(
    session: AppSession,
    identity: AegisIdentity,
    onStatus: (String) -> Unit,
    showHeader: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var enrollingPasskey by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeader) {
            Text("Identity", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
        }
        LabeledRow("Identity ID", identity.identityId)
        LabeledRow(
            "Signed",
            if (identity.document.signature == null) "no" else "yes",
        )
        LabeledRow(
            "Encryption keys",
            identity.document.encryptionKeys.size.toString(),
        )
        LabeledRow(
            "Signing keys",
            identity.document.signingKeys.size.toString(),
        )

        Button(
            onClick = {
                enrollingPasskey = true
                scope.launch {
                    try {
                        session.enrollPasskey(label = "Aegis (Android)")
                        onStatus(
                            "Passkey enrolled. You can now unlock with it from the lock screen."
                        )
                    } catch (e: io.aegis.android.passkey.PasskeyError.UserCancelled) {
                        // User dismissed the prompt — quiet.
                    } catch (e: Throwable) {
                        onStatus("Add passkey failed — ${e.message ?: e::class.simpleName}")
                    } finally {
                        enrollingPasskey = false
                    }
                }
            },
            enabled = !enrollingPasskey,
        ) {
            Text(if (enrollingPasskey) "Adding passkey…" else "Add Passkey")
        }
        Button(onClick = { session.lock() }) {
            Text("Lock vault")
        }
        Button(
            onClick = {
                scope.launch {
                    try {
                        session.deleteIdentity()
                        onStatus("Identity wiped from this device.")
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
fun LabeledRow(label: String, value: String) {
    Column(modifier = Modifier.padding(PaddingValues(vertical = 2.dp))) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
