package com.example.financetracker

import com.example.financetracker.activities.BaseActivityWithBack
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.databinding.ActivityLoginBinding
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class LoginActivity : BaseActivityWithBack() {
    override fun getLayoutResourceId(): Int = R.layout.activity_login
    override fun getScreenTitle(): String = "Login"

    private lateinit var auth: FirebaseAuth
    private lateinit var binding: ActivityLoginBinding
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        initializeFirebase()
        checkCurrentUser()
        setupClickListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun initializeFirebase() {
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
    }

    private fun checkCurrentUser() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            updateUI(currentUser)
        }
    }

    private fun setupClickListeners() {
        binding.loginButton.setOnClickListener { handleLogin() }
        binding.guestModeButton.setOnClickListener { handleGuestMode() }
        binding.registerLink.setOnClickListener {
            startActivity(Intent(this, RegistrationActivity::class.java))
        }
    }

    private fun handleLogin() {
        val email = binding.emailInput.text.toString()
        val password = binding.passwordInput.text.toString()

        when {
            email.isEmpty() -> binding.emailInput.error = "Email is required"
            password.isEmpty() -> binding.passwordInput.error = "Password is required"
            else -> {
                showLoading(true)
                signIn(email, password)
            }
        }
    }

    private fun handleGuestMode() {
        showLoading(true)
        val guestUserId = GuestUserManager.getGuestUserId(applicationContext)
        GuestUserManager.setGuestMode(applicationContext, true)
        ensureCleanSlate()
        navigateToMain()
    }

    private fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                showLoading(false)
                if (task.isSuccessful) {
                    handleSuccessfulSignIn()
                } else {
                    handleFailedSignIn(task.exception?.message)
                }
            }
    }

    private fun handleSuccessfulSignIn() {
        val user = auth.currentUser
        user?.let { firebaseUser ->
            checkUserProfile(firebaseUser)
        }
    }

    private fun checkUserProfile(user: FirebaseUser) {
        firestore.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    updateUI(user)
                } else {
                    createUserInFirestore(user)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking user profile", e)
                updateUI(user)
            }
    }

    private fun createUserInFirestore(user: FirebaseUser) {
        val userProfile = hashMapOf(
            "uid" to user.uid,
            "email" to (user.email ?: ""),
            "createdAt" to System.currentTimeMillis()
        )

        firestore.collection("users").document(user.uid)
            .set(userProfile)
            .addOnSuccessListener { updateUI(user) }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to create user profile", e)
                updateUI(user)
            }
    }

    private fun handleFailedSignIn(errorMessage: String?) {
        Log.w(TAG, "signInWithEmail:failure")
        Toast.makeText(
            this,
            errorMessage ?: "Authentication failed",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showLoading(show: Boolean) {
        binding.loginButton.isEnabled = !show
        binding.guestModeButton.isEnabled = !show
        binding.registerLink.isEnabled = !show
        if (show) {
            Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureCleanSlate() {
        lifecycleScope.launch {
            val database = TransactionDatabase.getDatabase(this@LoginActivity)
            val viewModel = TransactionViewModel(database, application)
            viewModel.clearTransactions()
            kotlinx.coroutines.delay(200)
            Log.d(TAG, "Database cleaned for new login")
        }
    }

    private fun updateUI(user: FirebaseUser?) {
        if (user != null) {
            ensureCleanSlate()
            navigateToMain()
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    companion object {
        private const val TAG = "LoginActivity"
    }
}