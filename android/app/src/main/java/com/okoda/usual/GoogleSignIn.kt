package com.okoda.usual

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.ClearCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** Only called after a deliberate Google button tap; no silent account selection. */
@SuppressLint("DiscouragedApi") // Google services generates this optional resource only when OAuth is configured.
suspend fun googleIdToken(activity: ComponentActivity): String {
    if (BuildConfig.USE_FIREBASE_EMULATORS) {
        throw UserFacingException("Google account selection is available in the live app. Guest access works with local emulators.")
    }
    val clientIdResource = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
    if (clientIdResource == 0) {
        throw UserFacingException("Google linking setup is not complete yet. You can keep using your guest space.")
    }
    try {
        val option = GetSignInWithGoogleOption.Builder(activity.getString(clientIdResource)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw UserFacingException("Google didn’t return a supported sign-in. Your current session is unchanged.")
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    } catch (_: GetCredentialCancellationException) {
        throw UserFacingException("Google sign-in cancelled. Your current session is unchanged.")
    } catch (_: NoCredentialException) {
        throw UserFacingException("No Google account is available. You can add one on your device or keep using your guest space.")
    } catch (_: GetCredentialException) {
        throw UserFacingException("Couldn’t open Google sign-in. Check your connection and Google Play services, then retry. Your current session is unchanged.")
    } catch (_: GoogleIdTokenParsingException) {
        throw UserFacingException("Couldn’t read Google sign-in. Please retry. Your current session is unchanged.")
    }
}

suspend fun clearGoogleCredentialState(activity: ComponentActivity) {
    try {
        CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
    } catch (_: ClearCredentialException) {
        // Firebase is already signed out. Every subsequent Google action still
        // requires a deliberate tap and an explicit account picker.
    }
}
