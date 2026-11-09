package com.digitronics.chat.auth

import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import com.digitronics.chat.BuildConfig
import com.digitronics.chat.data.Account
import com.digitronics.chat.data.AppPreferences
import com.digitronics.chat.net.ApiException
import com.digitronics.chat.net.DeviceRegistrationDto
import com.digitronics.chat.net.MobileApi
import com.digitronics.chat.net.MobileSessionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import java.io.IOException
import java.security.MessageDigest

sealed interface SessionState {
    data object Loading : SessionState

    data class SignedOut(val notice: SignOutNotice? = null) : SessionState

    data class SignedIn(val account: Account) : SessionState
}

enum class SignOutNotice { SESSION_ENDED, SIGNED_OUT }

sealed interface SignInResult {
    data object Success : SignInResult

    data class Failure(val reason: SignInFailure) : SignInResult
}

enum class SignInFailure { NO_PENDING, STATE_MISMATCH, EXPIRED, DENIED, NETWORK, SERVER, REJECTED }

/**
 * Owns the person's session: start sign-in, finish it from the callback, restore
 * it at launch, and end it — locally, on the server, or because the server
 * refused a refresh. Ending a session always runs [wipe], which clears tokens,
 * the Room cache, WorkManager jobs and notifications.
 */
class SessionManager(
    private val tokens: SecureTokenStore,
    private val prefs: AppPreferences,
    private val authApi: AuthApi,
    private val mobileApi: () -> MobileApi,
    private val wipe: suspend () -> Unit,
    private val onSignedIn: suspend (Account) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val endLock = Mutex()

    val account: Account? get() = (state.value as? SessionState.SignedIn)?.account

    /** For background work started in a cold process: waits until [restore] has run. */
    suspend fun awaitAccount(): Account? =
        (state.first { it !is SessionState.Loading } as? SessionState.SignedIn)?.account

    /** At launch: a stored refresh token plus account means signed in, even offline. */
    suspend fun restore() {
        val account = prefs.account()
        _state.value = if (account != null && tokens.hasRefreshToken()) {
            SessionState.SignedIn(account)
        } else {
            if (account != null || tokens.hasRefreshToken()) wipe()
            SessionState.SignedOut()
        }
    }

    /** Creates a fresh PKCE pair and state, stores them sealed, and returns the consent URL. */
    suspend fun beginSignIn(): Uri {
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        tokens.savePending(PendingSignIn(verifier, state, now()))
        val url = AuthorizeUrl.build(BuildConfig.BASE_URL, BuildConfig.AUTH_CLIENT_ID, Pkce.challengeS256(verifier), state)
        return url.toString().toUri()
    }

    /** Handles `https://<host>/android-auth/callback?code=…&state=…` (or `?error=…`). */
    suspend fun completeSignIn(callback: HttpUrl): SignInResult {
        val pending = tokens.takePending() ?: return SignInResult.Failure(SignInFailure.NO_PENDING)
        if (now() - pending.startedAtMillis > PENDING_TTL_MS) return SignInResult.Failure(SignInFailure.EXPIRED)
        val returnedState = callback.queryParameter("state") ?: return SignInResult.Failure(SignInFailure.STATE_MISMATCH)
        if (!MessageDigest.isEqual(returnedState.toByteArray(), pending.state.toByteArray())) {
            return SignInResult.Failure(SignInFailure.STATE_MISMATCH)
        }
        if (callback.queryParameter("error") != null) return SignInResult.Failure(SignInFailure.DENIED)
        val code = callback.queryParameter("code") ?: return SignInResult.Failure(SignInFailure.DENIED)

        val pair = try {
            authApi.exchangeCode(code, pending.verifier, AuthorizeUrl.redirectUri(BuildConfig.BASE_URL), deviceRegistration())
        } catch (e: ApiException) {
            return SignInResult.Failure(if (e.code == "invalid_grant") SignInFailure.REJECTED else SignInFailure.SERVER)
        } catch (e: IOException) {
            return SignInResult.Failure(SignInFailure.NETWORK)
        }
        tokens.save(pair)

        val session = try {
            mobileApi().session()
        } catch (e: Exception) {
            // Signed in on the server but we could not learn who we are: undo cleanly.
            runCatching { mobileApi().logout() }
            endSession(notice = null)
            return SignInResult.Failure(if (e is IOException) SignInFailure.NETWORK else SignInFailure.SERVER)
        }
        val account = session.toAccount()
        prefs.setAccount(account)
        _state.value = SessionState.SignedIn(account)
        onSignedIn(account)
        return SignInResult.Success
    }

    /** Re-reads who we are (name, workspace, may-send). A refused refresh ends the session by itself. */
    suspend fun refreshSession() {
        val current = account ?: return
        val session = runCatching { mobileApi().session() }.getOrNull() ?: return
        val fresh = session.toAccount()
        if (fresh.key != current.key) {
            // Never show one person's cache to another.
            endSession(SignOutNotice.SESSION_ENDED)
            return
        }
        if (fresh != current) {
            prefs.setAccount(fresh)
            _state.value = SessionState.SignedIn(fresh)
        }
    }

    /** "Sign out": revoke this device on the server (best effort), then wipe locally. */
    suspend fun signOut() {
        runCatching { mobileApi().logout() }
        endSession(SignOutNotice.SIGNED_OUT)
    }

    /** The server refused our refresh token (revoked, expired or replayed). */
    suspend fun onSessionEnded() = endSession(SignOutNotice.SESSION_ENDED)

    private suspend fun endSession(notice: SignOutNotice?) = endLock.withLock {
        wipe()
        _state.value = SessionState.SignedOut(notice)
    }

    private suspend fun deviceRegistration() = DeviceRegistrationDto(
        installId = prefs.installId(),
        label = Build.MODEL?.trim()?.take(80)?.takeIf { it.isNotEmpty() },
        appVersionCode = BuildConfig.VERSION_CODE,
        appVersionName = BuildConfig.VERSION_NAME.take(40),
        sdkInt = Build.VERSION.SDK_INT,
        channel = BuildConfig.RELEASE_CHANNEL,
    )

    private fun MobileSessionDto.toAccount() = Account(
        userId = user.id,
        email = user.email,
        fullName = user.fullName,
        workspaceId = workspace.id,
        workspaceName = workspace.name,
        role = workspace.role,
        deviceId = device.id,
        canSend = canSendDirectMessages,
    )

    private companion object {
        const val PENDING_TTL_MS = 10 * 60 * 1000L
    }
}
