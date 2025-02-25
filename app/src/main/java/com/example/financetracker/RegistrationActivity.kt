package com.example.financetracker

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.financetracker.databinding.ActivityRegistrationBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore

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

            if (password == confirmPassword) {
                register(name, age, email, password)
            } else {
                Toast.makeText(this, "Passwords do not match.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun register(name: String, age: Int, email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Registration success, create user profile in Firestore
                    val user = auth.currentUser
                    user?.let {
                        val userId = it.uid
                        val userProfile = hashMapOf(
                            "name" to name,
                            "age" to age,
                            "email" to email
                        )
                        firestore.collection("users").document(userId).set(userProfile)
                            .addOnSuccessListener {
                                Toast.makeText(this, "User profile created.", Toast.LENGTH_SHORT).show()
                                updateUI(user)
                            }
                            .addOnFailureListener {
                                Toast.makeText(this, "Failed to create user profile.", Toast.LENGTH_SHORT).show()
                            }
                    }
                } else {
                    // If registration fails, display a message to the user.
                    Toast.makeText(baseContext, "Registration failed.", Toast.LENGTH_SHORT).show()
                    updateUI(null)
                }
            }
    }

    private fun updateUI(user: FirebaseUser?) {
        if (user != null) {
            // User is signed in, proceed to MainActivity
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}