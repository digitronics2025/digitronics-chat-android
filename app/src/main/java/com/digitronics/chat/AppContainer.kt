package com.digitronics.chat

import android.app.Application
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import com.digitronics.chat.auth.AppVersionInterceptor
import com.digitronics.chat.auth.AuthApi
import com.digitronics.chat.auth.BearerInterceptor
import com.digitronics.chat.auth.KeystoreCipher
import com.digitronics.chat.auth.RefreshAuthenticator
import com.digitronics.chat.auth.SecureTokenStore
import com.digitronics.chat.auth.SessionManager
import com.digitronics.chat.auth.SessionState
import com.digitronics.chat.auth.SignInResult
import com.digitronics.chat.auth.TokenRefresher
import com.digitronics.chat.data.Account
import com.digitronics.chat.data.AppPreferences
import com.digitronics.chat.data.ChatRepository
import com.digitronics.chat.data.SendMessageWorker
import com.digitronics.chat.data.db.ChatDatabase
import com.digitronics.chat.net.ApiClient
import com.digitronics.chat.net.DmApi
import com.digitronics.chat.net.MobileApi
import com.digitronics.chat.net.RealtimeFrame
import com.digitronics.chat.push.Notifier
import com.digitronics.chat.push.PushRegistrar
import com.digitronics.chat.push.PushRegistrationWorker
import com.digitronics.chat.realtime.RealtimeClient
import com.digitronics.chat.update.UpdateCheckWorker
import com.digitronics.chat.update.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

sealed interface SignInStatus {
    data object Idle : SignInStatus

    data object Completing : SignInStatus

    data class Failed(val result: SignInResult.Failure) : SignInStatus
}

/** Hand-wired dependencies: one instance per process, owned by [ChatApplication]. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = AppPreferences(app)
    val tokenStore = SecureTokenStore(app, KeystoreCipher())
    val notifier = Notifier(app)
    val activeThread = ActiveThreadTracker()

    private val apiHost = BuildConfig.BASE_URL.toHttpUrl().host

    private val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AppVersionInterceptor(BuildConfig.VERSION_CODE))
        .build()

    // The token endpoint gets its own dispatcher too, so a refresh never waits
    // behind the very requests that are waiting for it.
    val authApi = AuthApi(
        ApiClient(baseHttp.newBuilder().dispatcher(Dispatcher()).build(), BuildConfig.BASE_URL),
        BuildConfig.AUTH_CLIENT_ID,
    )

    lateinit var session: SessionManager
        private set

    val refresher = TokenRefresher(tokenStore, authApi::refresh) { session.onSessionEnded() }

    private val authedHttp: OkHttpClient = baseHttp.newBuilder()
        .addInterceptor(BearerInterceptor(tokenStore, refresher, apiHost))
        .authenticator(RefreshAuthenticator(refresher, apiHost))
        .build()

    private val api = ApiClient(authedHttp, BuildConfig.BASE_URL)
    val dmApi = DmApi(api)
    val mobileApi = MobileApi(api)
    val db = ChatDatabase.create(app)
    val chat = ChatRepository(db, dmApi) { accountKey, clientId -> SendMessageWorker.enqueue(app, accountKey, clientId) }
    val pushRegistrar = PushRegistrar(app, prefs, mobileApi)
    val updates = UpdateRepository(prefs, mobileApi, { session.account?.deviceId })

    // The server answers our ping every 25 s, so 70 s of silence means a dead socket.
    val realtime = RealtimeClient(
        http = authedHttp.newBuilder().readTimeout(70, TimeUnit.SECONDS).build(),
        url = BuildConfig.BASE_URL + DmApi.REALTIME_PATH,
        tokens = tokenStore,
        refresher = refresher,
        scope = scope,
        onConnected = { reconcile() },
        onFrame = { handleFrame(it) },
    )

    private val _signInStatus = MutableStateFlow<SignInStatus>(SignInStatus.Idle)
    val signInStatus: StateFlow<SignInStatus> = _signInStatus.asStateFlow()

    /** A conversation or screen a notification asked to open, consumed by the UI. */
    val pendingConversation = MutableStateFlow<String?>(null)
    val pendingDestination = MutableStateFlow<String?>(null)

    private val listRefreshRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        session = SessionManager(
            tokens = tokenStore,
            prefs = prefs,
            authApi = authApi,
            mobileApi = { mobileApi },
            wipe = { wipe() },
            onSignedIn = { afterSignIn(it) },
        )
    }

    fun start() {
        scope.launch {
            session.restore()
            updates.restore()
            if (session.account != null) {
                UpdateCheckWorker.schedulePeriodic(app)
                PushRegistrationWorker.enqueue(app, force = false)
            }
        }
        scope.launch {
            for (request in listRefreshRequests) {
                delay(300)
                val account = session.account ?: continue
                runCatching { chat.refreshConversations(account, activeThread.conversationId) }
            }
        }
        scope.launch {
            session.state.collect { state ->
                if (state is SessionState.SignedIn && activeThread.foreground) realtime.start()
                if (state !is SessionState.SignedIn) realtime.stop()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    activeThread.foreground = true
                    if (session.account == null) return
                    realtime.start()
                    scope.launch {
                        runCatching { session.refreshSession() }
                        PushRegistrationWorker.enqueue(app, force = false)
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    activeThread.foreground = false
                    realtime.stop()
                }
            },
        )
    }

    /** Routes an incoming Intent: the sign-in callback or a notification tap. */
    fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data?.toString()?.toHttpUrlOrNull()
        if (intent.action == Intent.ACTION_VIEW && data != null &&
            com.digitronics.chat.auth.AuthorizeUrl.isCallback(BuildConfig.BASE_URL, data)
        ) {
            _signInStatus.value = SignInStatus.Completing
            scope.launch {
                _signInStatus.value = when (val result = session.completeSignIn(data)) {
                    SignInResult.Success -> SignInStatus.Idle
                    is SignInResult.Failure -> SignInStatus.Failed(result)
                }
            }
            return
        }
        // A tap from an old notification while signed out must not follow the next person in.
        if (session.state.value is SessionState.SignedOut) return
        when (intent.action) {
            MainActivity.ACTION_OPEN_CONVERSATION ->
                pendingConversation.value = intent.getStringExtra(MainActivity.EXTRA_CONVERSATION_ID)
            MainActivity.ACTION_OPEN_DESTINATION ->
                pendingDestination.value = intent.getStringExtra(MainActivity.EXTRA_DESTINATION)
        }
    }

    fun clearSignInStatus() {
        _signInStatus.value = SignInStatus.Idle
    }

    fun requestListRefresh() {
        listRefreshRequests.trySend(Unit)
    }

    /** After any (re)connect: catch up by cursor on the list and the thread on screen. */
    private suspend fun reconcile() {
        val account = session.account ?: return
        requestListRefresh()
        activeThread.conversationId?.let { runCatching { chat.syncThread(account, it) } }
    }

    private suspend fun handleFrame(frame: RealtimeFrame) {
        val account = session.account ?: return
        when (frame.type) {
            "dm.message.created" -> {
                val id = frame.conversationId ?: return
                if (activeThread.conversationId == id) runCatching { chat.syncThread(account, id) }
                requestListRefresh()
            }
            "dm.message.read" -> {
                val id = frame.conversationId ?: return
                val messageId = frame.lastReadMessageId ?: return
                chat.applyPeerRead(account, id, messageId)
            }
            "dm.group.deleted" -> requestListRefresh()
        }
    }

    private suspend fun afterSignIn(account: Account) {
        UpdateCheckWorker.schedulePeriodic(app)
        PushRegistrationWorker.enqueue(app, force = true)
        if (activeThread.foreground) realtime.start()
        runCatching { chat.refreshConversations(account) }
    }

    /**
     * Ends everything the signed-in person owned on this phone. NonCancellable:
     * it can be triggered from inside a worker that the wipe itself cancels.
     */
    private suspend fun wipe() = withContext(NonCancellable) {
        realtime.stop()
        withTimeoutOrNull(5_000) { runCatching { pushRegistrar.forgetToken() } }
        tokenStore.clear()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        prefs.clearAccountData()
        notifier.cancelAll()
        updates.reset()
        pendingConversation.value = null
        WorkManager.getInstance(app).cancelAllWork()
    }
}
