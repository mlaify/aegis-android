// Reusable relay-URL editor used by both SetupScreen (locked / first-run
// flow) and SettingsScreen (post-unlock Mail-shell settings tab).
// Keeps a single canonical surface so the two surfaces never drift.

package io.aegis.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun RelayEditor(
    relayUrlInput: String,
    onRelayUrlInputChange: (String) -> Unit,
    savedRelayUrl: String?,
    onSave: () -> Unit,
    showHeader: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeader) {
            Text("Relay", style = MaterialTheme.typography.titleMedium)
        }
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
