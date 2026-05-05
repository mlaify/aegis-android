// State-based router for the Android app's root surface, mirroring
// AegisMacRootView from aegis-apple#5:
//   - FirstRun / Locked      → SetupScreen (create or unlock vault)
//   - Unlocked(identity)     → MailScreen (Mail-app-style chrome)

package io.aegis.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.aegis.android.session.AppSession

@Composable
fun RootScreen(
    session: AppSession = viewModel(),
) {
    val state by session.state.collectAsStateWithLifecycle()
    when (val s = state) {
        AppSession.State.FirstRun,
        AppSession.State.Locked ->
            SetupScreen(session = session)

        is AppSession.State.Unlocked ->
            MailScreen(session = session, identity = s.identity)
    }
}
