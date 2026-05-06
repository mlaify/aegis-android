// Mail-app-style shell shown after the vault is unlocked. Mirrors the
// macOS NavigationSplitView shell from aegis-apple#5: the same three
// mailboxes (Inbox / Sent / Drafts) plus a Settings destination that
// holds the relay editor + identity card (mirrors macOS's Cmd-,
// Settings scene). Inbox / Sent / Drafts each show a placeholder
// until the inbox sync + compose + relay-publish wiring lands.
//
// Phone first: Scaffold + NavigationBar (bottom nav). Tablet /
// foldable adaptation (NavigationRail / drawer) can layer on later
// via NavigationSuiteScaffold without changing the destinations.

package io.aegis.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.aegis.android.core.AegisIdentity
import io.aegis.android.session.AppSession

enum class MailDestination(val title: String, val icon: ImageVector) {
    Inbox("Inbox", Icons.Default.Inbox),
    Sent("Sent", Icons.AutoMirrored.Filled.Send),
    Drafts("Drafts", Icons.Default.Drafts),
    Settings("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    session: AppSession,
    identity: AegisIdentity,
) {
    var selected by remember { mutableStateOf(MailDestination.Inbox) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(selected.title) }) },
        bottomBar = {
            NavigationBar {
                MailDestination.values().forEach { dest ->
                    NavigationBarItem(
                        selected = selected == dest,
                        onClick = { selected = dest },
                        icon = { Icon(dest.icon, contentDescription = dest.title) },
                        label = { Text(dest.title) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            when (selected) {
                MailDestination.Inbox ->
                    // Wired in aegis-android Round-3 part 1 — fetches
                    // envelopes from the relay and opens them via the
                    // FFI's openHybridPq.
                    InboxScreen(session = session)
                MailDestination.Sent,
                MailDestination.Drafts ->
                    MailboxPlaceholder(destination = selected)

                MailDestination.Settings ->
                    SettingsScreen(session = session, identity = identity)
            }
        }
    }
}

@Composable
private fun MailboxPlaceholder(destination: MailDestination) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Email,
                contentDescription = null,
            )
            Text(
                "No messages",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${destination.title} is empty. Compose and relay publish " +
                    "land in the next iteration.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
