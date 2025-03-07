package com.example.financetracker

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.financetracker.databinding.ActivityRegistrationBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore

// RegistrationActivity should NOT extend BaseActivity
class RegistrationActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var binding: ActivityRegistrationBinding
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase Auth and Firestore
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        binding.registerButton.setOnClickListener {
            val name = binding.name.text.toString()
            val age = binding.age.text.toString().toIntOrNull() ?: 0
            val email = binding.email.text.toString()
            val password = binding.password.text.toString()
            val confirmPassword = binding.confirmPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Email and password are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            register(name, age, email, password)
        }
    }

    private fun register(name: String, age: Int, email: String, password: String) {
        // Show progress
        binding.progressBar.visibility = View.VISIBLE

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Registration success, create user profile in Firestore
                    val user = auth.currentUser
                    if (user != null) {
                        val userId = user.uid
                        Log.d("RegistrationActivity", "Created user with ID: $userId")

                        // Create user profile with explicit userId
                        val userProfile = hashMapOf(
                            "uid" to userId,
                            "name" to name,
                            "age" to age,
                            "email" to email,
                            "createdAt" to System.currentTimeMillis()
                        )

                        // Save to Firestore with userId as document ID
                        firestore.collection("users").document(userId)
                            .set(userProfile)
                            .addOnSuccessListener {
                                binding.progressBar.visibility = View.GONE
                                Log.d("RegistrationActivity", "User profile created for ID: $userId")

                                // Get current user to ensure it's still valid
                                val currentUser = auth.currentUser
                                if (currentUser != null) {
                                    updateUI(currentUser)
                                } else {
                                    Log.e("RegistrationActivity", "User is null after profile creation")
                                    Toast.makeText(this, "Error: User session invalid", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .addOnFailureListener { e ->
                                binding.progressBar.visibility = View.GONE
                                Log.e("RegistrationActivity", "Failed to create user profile", e)
                                Toast.makeText(this, "Failed to create profile: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        binding.progressBar.visibility = View.GONE
                        Log.e("RegistrationActivity", "User is null after createUserWithEmailAndPassword")
                        Toast.makeText(this, "Error: Unable to create user", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // If registration fails, display a message to the user
                    binding.progressBar.visibility = View.GONE
                    Log.e("RegistrationActivity", "createUserWithEmail:failure", task.exception)
                    Toast.makeText(baseContext, "Registration failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun updateUI(user: FirebaseUser) {
        // User is signed in, proceed to MainActivity
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}