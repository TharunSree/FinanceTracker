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
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

// LoginActivity should NOT extend BaseActivity since it doesn't need the navigation drawer
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

        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // Check if user is already logged in
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // User is already logged in, go directly to MainActivity
            updateUI(currentUser)
            return
        }

        binding.loginButton.setOnClickListener {
            val email = binding.email.text.toString()
            val password = binding.password.text.toString()
            if (email.isNotEmpty() && password.isNotEmpty()) {
                binding.progressBar.visibility = View.VISIBLE
                signIn(email, password)
            } else {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            }
        }

        binding.registerButton.setOnClickListener {
            val intent = Intent(this, RegistrationActivity::class.java)
            startActivity(intent)
        }
    }

    private fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                binding.progressBar.visibility = View.GONE

                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    Log.d("LoginActivity", "signInWithEmail:success")
                    val user = auth.currentUser

                    // Check if user exists in Firestore
                    user?.let { firebaseUser ->
                        firestore.collection("users").document(firebaseUser.uid)
                            .get()
                            .addOnSuccessListener { document ->
                                if (document != null && document.exists()) {
                                    updateUI(firebaseUser)
                                } else {
                                    // Create user profile if it doesn't exist
                                    createUserInFirestore(firebaseUser)
                                }
                            }
                            .addOnFailureListener { e ->
                                // Proceed with login anyway but log the error
                                Log.e("LoginActivity", "Error checking user profile", e)
                                updateUI(firebaseUser)
                            }
                    }
                } else {
                    // If sign in fails, display a message to the user.
                    Log.w("LoginActivity", "signInWithEmail:failure", task.exception)
                    Toast.makeText(baseContext, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun createUserInFirestore(user: FirebaseUser) {
        val userId = user.uid
        val userProfile = hashMapOf(
            "uid" to userId,
            "email" to (user.email ?: ""),
            "createdAt" to System.currentTimeMillis()
        )

        firestore.collection("users").document(userId)
            .set(userProfile)
            .addOnSuccessListener {
                updateUI(user)
            }
            .addOnFailureListener { e ->
                Log.e("LoginActivity", "Failed to create user profile", e)
                updateUI(user)
            }
    }

    private fun ensureCleanSlate() {
        // Create an instance of the TransactionViewModel to clear any remaining data
        val database = TransactionDatabase.getDatabase(this)
        val viewModel = TransactionViewModel(database, application)

        lifecycleScope.launch {
            // Clear any lingering transactions from previous sessions
            viewModel.clearTransactions()

            // Small delay to ensure database operations complete
            kotlinx.coroutines.delay(200)

            Log.d("LoginActivity", "Database cleaned for new login")
        }
    }

    private fun updateUI(user: FirebaseUser?) {
        if (user != null) {
            // User is signed in, proceed to MainActivity
            val intent = Intent(this, MainActivity::class.java)

            ensureCleanSlate()

            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }
}