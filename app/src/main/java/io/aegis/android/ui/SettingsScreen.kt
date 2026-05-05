// Settings tab inside the Mail shell. Mirrors the macOS Cmd-, Settings
// scene from aegis-apple#5 — same logical surface (relay editor +
// identity card) just laid out as a single scrollable column instead
// of a tabbed window.

package io.aegis.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.aegis.android.core.AegisIdentity
import io.aegis.android.session.AppSession
import io.aegis.android.session.AppSessionException
import io.aegis.android.ui.components.IdentityCard
import io.aegis.android.ui.components.RelayEditor
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    session: AppSession,
    identity: AegisIdentity,
) {
    val savedRelayUrl by session.relayUrl.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var relayUrlInput by remember { mutableStateOf("") }
    LaunchedEffect(savedRelayUrl) {
        if (savedRelayUrl != null && relayUrlInput.isEmpty()) {
            relayUrlInput = savedRelayUrl ?: ""
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
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

            IdentityCard(
                session = session,
                identity = identity,
                onStatus = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            )
        }
        SnackbarHost(snackbarHostState)
    }
}
