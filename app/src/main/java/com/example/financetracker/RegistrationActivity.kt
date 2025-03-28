package com.example.financetracker

import com.example.financetracker.activities.BaseActivityWithBack
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.example.financetracker.databinding.ActivityRegistrationBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore

class RegistrationActivity : BaseActivityWithBack() {
    override fun getLayoutResourceId(): Int = R.layout.activity_registration
    override fun getScreenTitle(): String = "Register"

    private lateinit var auth: FirebaseAuth
    private lateinit var binding: ActivityRegistrationBinding
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        initializeFirebase()
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

    private fun setupClickListeners() {
        binding.registerButton.setOnClickListener { handleRegistration() }
        binding.loginLink.setOnClickListener { finish() }
    }

    private fun handleRegistration() {
        val name = binding.nameInput.text.toString()
        val age = binding.ageInput.text.toString()
        val email = binding.emailInput.text.toString()
        val password = binding.passwordInput.text.toString()
        val confirmPassword = binding.confirmPasswordInput.text.toString()

        when {
            name.isEmpty() -> binding.nameInput.error = "Name is required"
            age.isEmpty() -> binding.ageInput.error = "Age is required"
            email.isEmpty() -> binding.emailInput.error = "Email is required"
            password.isEmpty() -> binding.passwordInput.error = "Password is required"
            confirmPassword.isEmpty() -> binding.confirmPasswordInput.error = "Please confirm password"
            password != confirmPassword -> {
                binding.confirmPasswordInput.error = "Passwords do not match"
                binding.passwordInput.error = "Passwords do not match"
            }
            else -> {
                showLoading(true)
                register(name, age.toIntOrNull() ?: 0, email, password)
            }
        }
    }

    private fun register(name: String, age: Int, email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        createUserProfile(user, name, age, email)
                    } else {
                        showLoading(false)
                        showError("Failed to create user profile")
                    }
                } else {
                    showLoading(false)
                    showError(task.exception?.message ?: "Registration failed")
                }
            }
    }

    private fun createUserProfile(user: FirebaseUser, name: String, age: Int, email: String) {
        val userProfile = hashMapOf(
            "uid" to user.uid,
            "name" to name,
            "age" to age,
            "email" to email,
            "createdAt" to System.currentTimeMillis()
        )

        firestore.collection("users").document(user.uid)
            .set(userProfile)
            .addOnSuccessListener {
                Log.d(TAG, "User profile created for ID: ${user.uid}")
                updateUI(user)
            }
            .addOnFailureListener { e ->
                showLoading(false)
                showError("Failed to create profile: ${e.message}")
            }
    }

    private fun showLoading(show: Boolean) {
        binding.registerButton.isEnabled = !show
        binding.loginLink.isEnabled = !show
        if (show) {
            Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun updateUI(user: FirebaseUser) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    companion object {
        private const val TAG = "RegistrationActivity"
    }
}