package com.example.smartcartbilling

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var btnGoogleSignIn: MaterialButton
    private lateinit var txtLoginStatus: TextView

    // ⚠️ Change this to your admin Gmail
    private val ADMIN_EMAIL = "vishnushanu.24748@gmail.com"

    private val RC_SIGN_IN = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        // Already signed in — route to correct screen
        if (auth.currentUser != null) {
            routeUser(auth.currentUser!!.email ?: "")
            return
        }

        btnGoogleSignIn  = findViewById(R.id.btnGoogleSignIn)
        txtLoginStatus   = findViewById(R.id.txtLoginStatus)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        btnGoogleSignIn.setOnClickListener { signIn() }
    }

    private fun signIn() {
        btnGoogleSignIn.isEnabled = false
        txtLoginStatus.visibility = View.VISIBLE
        txtLoginStatus.text = "Signing in…"
        startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account)
            } catch (e: ApiException) {
                txtLoginStatus.text = "Sign-in failed. Try again."
                btnGoogleSignIn.isEnabled = true
            }
        }
    }

    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount) {
        txtLoginStatus.text = "Authenticating…"
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)

        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user    = auth.currentUser
                    val email   = user?.email ?: ""
                    val name    = user?.displayName ?: "User"
                    val uid     = user?.uid ?: ""
                    val isAdmin = email == ADMIN_EMAIL

                    // Save user profile to Firebase
                    val db = FirebaseDatabase
                        .getInstance("https://qless-be82a-default-rtdb.firebaseio.com/")
                        .getReference("users/$uid")

                    db.setValue(mapOf("name" to name, "email" to email, "isAdmin" to isAdmin))
                        .addOnCompleteListener {
                            txtLoginStatus.text = "Welcome, $name!"
                            routeUser(email)
                        }
                } else {
                    txtLoginStatus.text = "Authentication failed. Try again."
                    btnGoogleSignIn.isEnabled = true
                }
            }
    }

    // ── Route: admin → AdminActivity, others → MainActivity ──
    private fun routeUser(email: String) {
        val intent = if (email == ADMIN_EMAIL)
            Intent(this, AdminActivity::class.java)
        else
            Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}