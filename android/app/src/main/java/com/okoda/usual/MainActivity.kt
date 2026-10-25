package com.okoda.usual

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var destination by mutableStateOf<AppDestination?>(null)
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); destination = AppDestination.parse(intent.dataString) }
    override fun onResume() { super.onResume(); (application as UsualApplication).privacy.permissionChanged(); (application as UsualApplication).privacy.refreshWidgets() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        destination = AppDestination.parse(intent.dataString)
        val repository = (application as UsualApplication).repository
        val couples = (application as UsualApplication).couples
        setContent {
            val model = viewModel { UsualViewModel(repository) }
            val coupleModel = viewModel { CoupleViewModel(repository, couples) }
            val mediaModel = viewModel { MediaViewModel(application as UsualApplication, coupleModel.state) }
            val gameModel = viewModel { GameViewModel((application as UsualApplication).backend, coupleModel.state) }
            val state by model.state.collectAsStateWithLifecycle()
            MaterialTheme {
                UsualScreen(state, model, coupleModel, mediaModel, gameModel, (application as UsualApplication).media, (application as UsualApplication).privacy, destination, { destination = null }, acquireGoogleToken = { googleIdToken(this) }, onSignOut = {
                    lifecycleScope.launch {
                        (application as UsualApplication).privacy.beforeSignOut()
                        model.signOut()
                        clearGoogleCredentialState(this@MainActivity)
                    }
                })
            }
        }
    }
}

@Composable
private fun UsualScreen(
    state: UsualUiState,
    model: UsualViewModel,
    coupleModel: CoupleViewModel,
    mediaModel: MediaViewModel,
    gameModel: GameViewModel,
    media: MediaRepository,
    privacy: PrivacyController,
    destination: AppDestination?,
    consumedDestination: () -> Unit,
    acquireGoogleToken: suspend () -> String,
    onSignOut: () -> Unit,
) {
    var tab by remember(state.account?.uid) { mutableStateOf("Connect") }
    val pairing by coupleModel.state.collectAsStateWithLifecycle()
    val pending by mediaModel.outbox.collectAsStateWithLifecycle()
    val mediaState by mediaModel.state.collectAsStateWithLifecycle()
    val gameState by gameModel.state.collectAsStateWithLifecycle()
    var quickAction by remember(state.account?.uid, pairing.couple.coupleId) { mutableStateOf<String?>(null) }
    var quickSequence by remember(state.account?.uid, pairing.couple.coupleId) { mutableStateOf(0) }
    var routeNotice by remember(state.account?.uid) { mutableStateOf<String?>(null) }
    LaunchedEffect(destination, state.account?.uid, pairing.couple, mediaState.coupleId, gameState.coupleId) {
        val route = destination ?: return@LaunchedEffect
        if (state.account == null || pairing.uid != state.account.uid || pairing.couple.status == "loading") return@LaunchedEffect
        if (route.coupleId != null && route.coupleId == pairing.couple.coupleId && (mediaState.coupleId != route.coupleId || gameState.coupleId != route.coupleId)) return@LaunchedEffect
        if (route.coupleId != null && route.coupleId != pairing.couple.coupleId) routeNotice = "This shared item is no longer available to this pairing."
        else when (route.type) {
            "content" -> { tab = "Memories"; route.resourceId?.let(mediaModel::select) }
            "game" -> { tab = "Play"; if (route.resourceId == null) gameModel.close() else gameModel.select(route.resourceId) }
            "draw", "photo" -> { tab = "Connect"; quickAction = route.type; quickSequence++ }
        }
        consumedDestination()
    }
    Scaffold(bottomBar = {
        if (state.account != null) NavigationBar {
            listOf("Connect", "Play", "Memories", "Settings").forEach { destination ->
                NavigationBarItem(selected = tab == destination, onClick = { tab = destination }, icon = { Text(when (destination) { "Connect" -> "♡"; "Play" -> "○"; "Memories" -> "▧"; else -> "⚙" }) }, label = { Text(destination) })
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().padding(16.dp)) {
            Row {
                Image(painterResource(R.drawable.brand_logo), null, Modifier.size(40.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            }
            Spacer(Modifier.height(12.dp))
            routeNotice?.let { Text(it) }
            val account = state.account
            if (account == null) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WelcomeForm(state, model, acquireGoogleToken)
                    if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
                    state.notice?.let { Text(it) }
                }
            } else Box(Modifier.weight(1f)) {
                when (tab) {
                    "Play" -> GamesScreen(gameModel, mediaModel, media)
                    "Memories" -> GalleryScreen(mediaModel, media)
                    "Settings" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        ProfileForm(account, state, model, acquireGoogleToken, onSignOut)
                        PrivateImage(media, null, account.uid, avatar = true, version = pending.count { it.uid == account.uid && it.kind == "AVATAR" })
                        ContentComposer(mediaModel, avatar = true)
                        PrivacySettings(privacy)
                        PairingPanel(coupleModel)
                        state.notice?.let { Text(it) }
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        PairingPanel(coupleModel, compact = true)
                        TextButton(onClick = { tab = "Settings" }) { Text("Your name and optional Google recovery") }
                        PendingSends(mediaModel)
                        if (pairing.couple.coupleId != null) androidx.compose.runtime.key(quickAction, quickSequence) { ContentComposer(mediaModel, startAction = quickAction) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeForm(state: UsualUiState, model: UsualViewModel, acquireGoogleToken: suspend () -> String) {
    var legacySignIn by remember { mutableStateOf(false) }
    Text("Start together", style = MaterialTheme.typography.titleLarge)
    Text("No email or password needed. You can link Google later if you want to recover your profile on another device.")
    Button(onClick = model::startGuest, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Continue without an account")
    }
    TextButton(onClick = { model.signInGoogle(acquireGoogleToken) }, enabled = !state.busy) {
        Text("Continue with Google")
    }
    TextButton(onClick = { legacySignIn = !legacySignIn }, enabled = !state.busy) {
        Text(if (legacySignIn) "Hide email sign-in" else "Already have an email account?")
    }
    if (legacySignIn) SignInForm(state, model)
}

@Composable
private fun SignInForm(state: UsualUiState, model: UsualViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    Text("Sign in to your existing account", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        singleLine = true,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { showPassword = !showPassword }) {
                Text(if (showPassword) "Hide" else "Show")
            }
        },
        singleLine = true,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            model.signIn(email, password)
            password = ""
        },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Sign in") }
    TextButton(onClick = { model.resetPassword(email) }, enabled = !state.busy) { Text("Reset password") }
}

@Composable
private fun ProfileForm(
    account: Account,
    state: UsualUiState,
    model: UsualViewModel,
    acquireGoogleToken: suspend () -> String,
    onSignOut: () -> Unit,
) {
    var displayName by remember(account.uid) { mutableStateOf("") }
    val profile = (state.profile as? ProfileState.Ready)?.profile
    LaunchedEffect(account.uid, profile?.displayName) {
        if (displayName.isEmpty()) displayName = profile?.displayName.orEmpty()
    }
    Text(if (account.isGuest) "Your guest space" else "Your profile", style = MaterialTheme.typography.titleMedium)
    when (val current = state.profile) {
        ProfileState.Loading -> Text("Loading your private profile…")
        ProfileState.Missing -> Text("Choose a display name to finish your profile.")
        is ProfileState.Ready -> Text(
            when {
                current.profile.pendingWrite -> "Saving profile…"
                current.profile.fromCache -> "Showing a cached profile. Connect and refresh for the latest version."
                else -> "Your private profile is up to date."
            },
        )
        is ProfileState.Failed -> Text(current.message)
    }
    OutlinedTextField(
        value = displayName,
        onValueChange = { displayName = it },
        label = { Text("Display name") },
        supportingText = { Text("${displayName.length}/80 characters") },
        isError = displayName.length > 80,
        singleLine = true,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { model.saveProfile(displayName) }, enabled = !state.busy) { Text("Save profile") }
    TextButton(onClick = model::refresh, enabled = !state.busy) { Text("Refresh account and profile") }
    Text("Google backup is optional. Email verification is not required to use the app or to pair.")
    if (account.googleLinked) {
        Text("Google linked. Use the same Google account to recover this profile on another device.")
    } else {
        if (account.isGuest) {
            Text("Reinstalling or clearing app data may lose access to this guest space. Link Google whenever you want recovery.")
        }
        TextButton(onClick = { model.linkGoogle(acquireGoogleToken) }, enabled = !state.busy) {
            Text("Link Google (optional)")
        }
    }
    if (!account.isGuest) {
        TextButton(onClick = onSignOut, enabled = !state.busy) { Text("Sign out") }
    }
}
