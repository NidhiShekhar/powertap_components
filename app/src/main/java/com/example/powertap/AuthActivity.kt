package com.drivool.iot.powertap

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val TAG = "AuthActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Always show the welcome UI first to ensure "Welcome to PowerTap" is seen
        setContentView(buildUi())

        val onboardingDone = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("onboarding_done", false)
        Log.d(TAG, "onCreate: Checking current user, onboardingDone=$onboardingDone")

        if (auth.currentUser != null) {
            Log.d(TAG, "onCreate: User already logged in, transitioning after splash delay")
            lifecycleScope.launch {
                delay(1500) // Show welcome/splash for 1.5s
                if (onboardingDone) {
                    startMainActivity()
                } else {
                    startActivity(Intent(this@AuthActivity, OnboardingActivity::class.java))
                    finish()
                }
            }
            return
        }

        handleEmailLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEmailLink(intent)
    }

    private fun handleEmailLink(intent: Intent?) {
        val link = intent?.data?.toString()
        if (link != null && auth.isSignInWithEmailLink(link)) {
            Log.d(TAG, "handleEmailLink: Valid link detected")
            val email = getSharedPreferences("AuthPrefs", MODE_PRIVATE).getString("email", null)
            if (email != null) {
                auth.signInWithEmailLink(email, link)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(TAG, "handleEmailLink: Sign in successful")
                            startMainActivity()
                        } else {
                            Log.e(TAG, "handleEmailLink: Sign in failed", task.exception)
                            Toast.makeText(this, "Link sign-in failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
            } else {
                Log.w(TAG, "handleEmailLink: Email missing from SharedPreferences")
                Toast.makeText(this, "Please verify your email to continue", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildUi(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 0, 64, 0)
            setBackgroundColor(ThemeColors.surface(this@AuthActivity))

            addView(TextView(this@AuthActivity).apply {
                text = "Welcome to PowerTap"
                textSize = 24f
                setTextColor(ThemeColors.onSurface(this@AuthActivity))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            })

            addView(TextView(this@AuthActivity).apply {
                text = "Please sign in to continue"
                textSize = 14f
                setTextColor(ThemeColors.onSurfaceVariant(this@AuthActivity))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 64)
            })

            addView(Button(this@AuthActivity).apply {
                text = "Sign in with Google"
                setBackgroundColor(ContextCompat.getColor(this@AuthActivity, R.color.google_blue))
                setTextColor(ContextCompat.getColor(this@AuthActivity, R.color.white))
                setOnClickListener {
                    Log.d(TAG, "Google Sign In button clicked")
                    signInWithGoogle()
                }
            })
        }
    }

    private fun sendSignInLink(email: String) {
        val actionCodeSettings = ActionCodeSettings.newBuilder()
            .setUrl("https://drivool-powertap.firebaseapp.com/__/auth/action?mode=signIn&email=$email")
            .setHandleCodeInApp(true)
            .setAndroidPackageName(packageName, true, "1")
            .build()

        auth.sendSignInLinkToEmail(email, actionCodeSettings)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    getSharedPreferences("AuthPrefs", MODE_PRIVATE).edit().putString("email", email).apply()
                    Toast.makeText(this, "Sign-in link sent to $email", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun signInWithGoogle() {
        Log.d(TAG, "signInWithGoogle: Initializing")
        val credentialManager = CredentialManager.create(this)
        val webClientId = getString(R.string.default_web_client_id)
        Log.d(TAG, "signInWithGoogle: Using Web Client ID: $webClientId")

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(true) // Automatically detect and log in if possible
            .setNonce(java.util.UUID.randomUUID().toString())
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                Log.d(TAG, "signInWithGoogle: Attempting to get credential")
                val result = credentialManager.getCredential(this@AuthActivity, request)
                Log.d(TAG, "signInWithGoogle: Credential received")
                handleCredentialResult(result.credential)
            } catch (e: GetCredentialException) {
                Log.e(TAG, "signInWithGoogle: GetCredentialException: ${e.message}", e)
                
                if (e.message?.contains("BAD_AUTHENTICATION", ignoreCase = true) == true || 
                    e.message?.contains("credential not available", ignoreCase = true) == true) {
                    
                    Log.w(TAG, "signInWithGoogle: Detected bad state, attempting silent reset")
                    try {
                        credentialManager.clearCredentialState(ClearCredentialStateRequest())
                        Log.d(TAG, "signInWithGoogle: State cleared, retrying")
                        val retryResult = credentialManager.getCredential(this@AuthActivity, request)
                        Log.d(TAG, "signInWithGoogle: Retry successful")
                        handleCredentialResult(retryResult.credential)
                    } catch (retryException: Exception) {
                        Log.e(TAG, "signInWithGoogle: Retry failed", retryException)
                        Toast.makeText(this@AuthActivity, "Google Sign-In failed: ${retryException.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@AuthActivity, "Google Sign-In failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "signInWithGoogle: Unexpected exception", e)
                Toast.makeText(this@AuthActivity, "An unexpected error occurred", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleCredentialResult(credential: androidx.credentials.Credential) {
        Log.d(TAG, "handleCredentialResult: Processing credential of type ${credential.type}")
        
        try {
            if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Log.d(TAG, "handleCredentialResult: Matches GoogleIdTokenCredential type")
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                
                auth.signInWithCredential(firebaseCredential)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(TAG, "handleCredentialResult: Firebase auth successful")
                            startMainActivity()
                        } else {
                            Log.e(TAG, "handleCredentialResult: Firebase auth failed", task.exception)
                            Toast.makeText(this, "Firebase Auth failed", Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Log.w(TAG, "handleCredentialResult: Unknown credential type: ${credential.type}")
                Toast.makeText(this, "Unknown login type", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleCredentialResult: Error parsing credential", e)
            Toast.makeText(this, "Login error occurred", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMainActivity() {
        Log.d(TAG, "startMainActivity: Starting MainActivity")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
