// Inbox screen — drains the relay's queue for the unlocked identity
// and lets the user open envelopes one at a time.
//
// Mirrors aegis-apple's `InboxView.swift` and aegis-web's
// `pages/Inbox.tsx`:
//   1. Fetch button → calls RelayClient.fetchEnvelopes(recipientId)
//   2. List of envelopes (sender hint, envelope id, suite) with an
//      "Open" button on each row.
//   3. On open, calls `Aegis.openEnvelope(_)`, decoding the inner
//      PrivatePayload and surfacing the FFI's sigStatus as a
//      verified / failed / unsigned / unavailable badge.
//
// Out of scope for this iteration:
//   - One-time prekey secret persistence (envelopes that used a
//     prekey surface a graceful error rather than failing into the
//     FFI). Lands with the Identity-detail "Publish prekeys" PR.
//   - Compose / reply (separate Round-3 deliverable).

package io.aegis.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.aegis.android.core.Aegis
import io.aegis.android.core.Envelope
import io.aegis.android.core.OpenedEnvelope
import io.aegis.android.core.RelayClient
import io.aegis.android.core.openEnvelope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.aegis.android.session.AppSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uniffi.aegis_ffi.SigStatus

private val inboxJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Composable
fun InboxScreen(session: AppSession) {
    var envelopes by remember { mutableStateOf<List<Envelope>>(emptyList()) }
    var isFetching by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    val opened = remember { mutableStateMapOf<String, OpenedEnvelope>() }
    val openErrors = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    val relayUrlState by session.relayUrl.collectAsStateWithLifecycle()
    val sessionState by session.state.collectAsStateWithLifecycle()
    val identityId = (sessionState as? AppSession.State.Unlocked)?.identity?.identityId
    // Snapshot the delegated `relayUrlState` into a local val so the
    // Kotlin compiler can smart-cast inside lambdas / nested scopes.
    val relayUrl: String? = relayUrlState

    // Auto-fetch on first composition if we have an identity + relay.
    // This matches the iOS UX where Inbox tries to load right away;
    // the user can still hit Refresh to re-poll.
    LaunchedEffect(relayUrl, identityId) {
        if (relayUrl != null && identityId != null) {
            doFetch(
                scope, session, relayUrl, identityId,
                setIsFetching = { isFetching = it },
                setEnvelopes = { envelopes = it },
                setError = { lastError = it },
                opened = opened,
                openErrors = openErrors,
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header row: identity hint + fetch button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Inbox",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (identityId != null && relayUrl != null) {
                    Text(
                        "recipient ${truncate(identityId, 22)} · relay ${hostFor(relayUrl)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = {
                    if (relayUrl != null && identityId != null) {
                        doFetch(
                            scope, session, relayUrl, identityId,
                            setIsFetching = { isFetching = it },
                            setEnvelopes = { envelopes = it },
                            setError = { lastError = it },
                            opened = opened,
                            openErrors = openErrors,
                        )
                    }
                },
                enabled = !isFetching && relayUrl != null && identityId != null,
            ) {
                if (isFetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("Fetch")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Inline error banner
        lastError?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Body
        when {
            relayUrl == null || identityId == null -> {
                EmptyInboxState(
                    title = "Inbox unavailable",
                    body = "Configure a relay URL and unlock an identity in Settings before fetching.",
                )
            }
            envelopes.isEmpty() -> {
                EmptyInboxState(
                    title = "Inbox is empty",
                    body = "Tap Fetch to drain the relay's queue for this identity.",
                )
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(envelopes, key = { it.envelopeId }) { env ->
                        EnvelopeCard(
                            envelope = env,
                            opened = opened[env.envelopeId],
                            openError = openErrors[env.envelopeId],
                            onOpen = {
                                // Reached the `else -> {}` arm only when
                                // `envelopes` is non-empty, which implies a
                                // successful fetch — and that requires
                                // `relayUrl != null`. We re-bind defensively
                                // to keep the lambda standalone.
                                relayUrl?.let { url ->
                                    scope.launch {
                                        doOpen(
                                            envelope = env,
                                            relayUrl = url,
                                            opened = opened,
                                            openErrors = openErrors,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ─── Helpers ───────────────────────────────────────────────────────────────

private fun doFetch(
    scope: CoroutineScope,
    session: AppSession,
    relayUrl: String,
    identityId: String,
    setIsFetching: (Boolean) -> Unit,
    setEnvelopes: (List<Envelope>) -> Unit,
    setError: (String?) -> Unit,
    opened: MutableMap<String, OpenedEnvelope>,
    openErrors: MutableMap<String, String>,
) {
    setIsFetching(true)
    scope.launch {
        try {
            val client = RelayClient(relayUrl)
            val fetched = client.fetchEnvelopes(identityId)
            setEnvelopes(fetched)
            setError(null)
            // Drop per-envelope state for envelopes the relay no longer
            // returns — otherwise stale errors hang around.
            val present = fetched.map { it.envelopeId }.toSet()
            opened.keys.toList().filter { it !in present }.forEach(opened::remove)
            openErrors.keys.toList().filter { it !in present }.forEach(openErrors::remove)
        } catch (t: Throwable) {
            setError(t.message ?: t.javaClass.simpleName)
        } finally {
            setIsFetching(false)
        }
        // suppress unused; we only call session here for symmetry with iOS
        @Suppress("UNUSED_EXPRESSION") session
    }
}

private suspend fun doOpen(
    envelope: Envelope,
    relayUrl: String,
    opened: MutableMap<String, OpenedEnvelope>,
    openErrors: MutableMap<String, String>,
) {
    // We don't yet persist one-time prekey secrets on Android (that
    // ships with the Identity-detail "Publish prekeys" PR). Surface a
    // clear message rather than letting the FFI throw a confusing
    // decryption error.
    if (envelope.usedPrekeyIds.isNotEmpty()) {
        openErrors[envelope.envelopeId] =
            "Envelope used a one-time prekey, but prekey persistence isn't wired up yet on this device."
        return
    }

    // Best-effort sender resolution. If it fails, we still try to
    // open — the FFI returns sigStatus = UNAVAILABLE instead of
    // crashing.
    var senderDocJson: String? = null
    val senderHint = envelope.senderHint
    if (senderHint != null && senderHint.startsWith("amp:")) {
        try {
            val client = RelayClient(relayUrl)
            val doc = client.resolveIdentity(senderHint)
            senderDocJson = inboxJson.encodeToString(doc)
        } catch (_: Throwable) {
            // Ignore — caller falls back to unverified open.
        }
    }

    try {
        val result = Aegis.openEnvelope(
            envelope = envelope,
            prekeyKyber768SecretB64 = null,
            senderDocumentJson = senderDocJson,
        )
        opened[envelope.envelopeId] = result
        openErrors.remove(envelope.envelopeId)
    } catch (t: Throwable) {
        openErrors[envelope.envelopeId] = t.message ?: t.javaClass.simpleName
    }
}

// ─── Composable helpers ────────────────────────────────────────────────────

@Composable
private fun EnvelopeCard(
    envelope: Envelope,
    opened: OpenedEnvelope?,
    openError: String?,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "from ${envelope.senderHint ?: "<anonymous>"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${envelope.envelopeId} · ${envelope.createdAt}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    envelope.suiteId,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
            }

            Spacer(Modifier.height(8.dp))

            if (opened != null) {
                SigStatusBadge(opened.sigStatus)
                Spacer(Modifier.height(6.dp))
                opened.payload.privateHeaders.subject?.takeIf { it.isNotEmpty() }?.let { subject ->
                    Text(
                        "subject: $subject",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Text(
                        opened.payload.body.content,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onOpen) { Text("Open") }
                    if (openError != null) {
                        Spacer(Modifier.padding(horizontal = 8.dp))
                        Text(
                            openError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SigStatusBadge(status: SigStatus) {
    val (label, color) = when (status) {
        SigStatus.VERIFIED -> "sig: verified" to Color(0xFF1B7F3A)
        SigStatus.FAILED -> "sig: failed" to MaterialTheme.colorScheme.error
        SigStatus.UNSIGNED -> "sig: unsigned" to Color(0xFFB45309)
        SigStatus.UNAVAILABLE -> "sig: unavailable" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyInboxState(title: String, body: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Inbox, contentDescription = null)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun truncate(value: String, max: Int): String =
    if (value.length <= max) value else "${value.take(max - 1)}…"

private fun hostFor(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
