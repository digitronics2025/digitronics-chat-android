package com.digitronics.chat.ui.signin

import android.content.ActivityNotFoundException
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.digitronics.chat.AppContainer
import com.digitronics.chat.R
import com.digitronics.chat.SignInStatus
import com.digitronics.chat.auth.SignInFailure
import com.digitronics.chat.auth.SignOutNotice
import com.digitronics.chat.ui.components.AppLogo
import kotlinx.coroutines.launch

@Composable
fun SignInScreen(container: AppContainer, notice: SignOutNotice?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by container.signInStatus.collectAsState()
    var browserMissing by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppLogo(size = 96.dp)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.app_display_name), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            val message = when {
                browserMissing -> stringResource(R.string.signin_no_browser)
                status is SignInStatus.Failed -> stringResource(failureText((status as SignInStatus.Failed).result.reason))
                notice == SignOutNotice.SESSION_ENDED -> stringResource(R.string.signin_session_ended)
                notice == SignOutNotice.SIGNED_OUT -> stringResource(R.string.signin_signed_out)
                else -> null
            }
            if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
            }

            if (status == SignInStatus.Completing) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.signin_completing))
            } else {
                Button(
                    onClick = {
                        container.clearSignInStatus()
                        scope.launch {
                            val uri = container.session.beginSignIn()
                            try {
                                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
                                browserMissing = false
                            } catch (e: ActivityNotFoundException) {
                                browserMissing = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.signin_button)) }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.signin_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun failureText(reason: SignInFailure): Int = when (reason) {
    SignInFailure.NO_PENDING, SignInFailure.EXPIRED -> R.string.signin_error_expired
    SignInFailure.STATE_MISMATCH -> R.string.signin_error_state
    SignInFailure.DENIED -> R.string.signin_error_denied
    SignInFailure.NETWORK -> R.string.signin_error_network
    SignInFailure.REJECTED -> R.string.signin_error_rejected
    SignInFailure.SERVER -> R.string.signin_error_server
}
