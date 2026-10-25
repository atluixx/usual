package com.okoda.usual

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as UsualApplication).repository
        setContent {
            val model = viewModel { UsualViewModel(repository) }
            val state by model.state.collectAsStateWithLifecycle()
            MaterialTheme {
                UsualScreen(state, model)
            }
        }
    }
}

@Composable
private fun UsualScreen(state: UsualUiState, model: UsualViewModel) {
    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).imePadding()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.ic_usual), null, Modifier.size(64.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text("A private space for two people.")
            Spacer(Modifier.height(8.dp))
            val account = state.account
            if (account == null) {
                SignInForm(state, model)
            } else {
                ProfileForm(account, state, model)
            }
            if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
            state.notice?.let { Text(it) }
        }
    }
}

@Composable
private fun SignInForm(state: UsualUiState, model: UsualViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var creatingAccount by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    Text(if (creatingAccount) "Create your account" else "Welcome back", style = MaterialTheme.typography.titleLarge)
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
            if (creatingAccount) model.signUp(email, password) else model.signIn(email, password)
            password = ""
        },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (creatingAccount) "Create account" else "Sign in") }
    TextButton(onClick = { creatingAccount = !creatingAccount; password = "" }, enabled = !state.busy) {
        Text(if (creatingAccount) "Already have an account? Sign in" else "New to Usual? Create an account")
    }
    TextButton(onClick = { model.resetPassword(email) }, enabled = !state.busy) { Text("Reset password") }
}

@Composable
private fun ProfileForm(account: Account, state: UsualUiState, model: UsualViewModel) {
    var displayName by remember(account.uid) { mutableStateOf("") }
    val profile = (state.profile as? ProfileState.Ready)?.profile
    LaunchedEffect(account.uid, profile?.displayName) {
        if (displayName.isEmpty()) displayName = profile?.displayName.orEmpty()
    }
    Text(account.email.orEmpty(), style = MaterialTheme.typography.titleMedium)
    Text(if (account.emailVerified) "Email verified" else "Verify your email before pairing becomes available.")
    if (!account.emailVerified) {
        TextButton(onClick = model::sendVerification, enabled = !state.busy) { Text("Send verification email") }
    }
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
    Text("Drawing, pairing, and shared memories are coming in later milestones.")
    TextButton(onClick = model::signOut) { Text("Sign out") }
}
