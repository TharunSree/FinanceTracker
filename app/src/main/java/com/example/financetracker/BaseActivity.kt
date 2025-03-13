package com.example.financetracker

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

abstract class BaseActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    protected lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    protected open lateinit var auth: FirebaseAuth
    protected open lateinit var firestore: FirebaseFirestore
    private val TAG = "BaseActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getLayoutResourceId())

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // Check authentication state
        checkAuthState()

        setupNavigationDrawer()
    }

    abstract fun getLayoutResourceId(): Int

    private fun checkAuthState() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.d(TAG, "No logged in user - continuing in guest mode")
            // No redirection to login - allow using the app in guest mode
            updateNavHeader() // Update header to show "Guest"
        } else {
            Log.d(TAG, "User logged in: ${currentUser.uid}")
        }
    }

    fun setupNavigationDrawer() {
        try {
            drawerLayout = findViewById(R.id.drawer_layout)
            navigationView = findViewById(R.id.nav_view)
            val toolbar = findViewById<Toolbar>(R.id.toolbar)

            setSupportActionBar(toolbar)
            supportActionBar?.apply {
                setDisplayHomeAsUpEnabled(true)
                setHomeAsUpIndicator(R.drawable.ic_menu)
            }

            navigationView.setNavigationItemSelectedListener(this)
            updateNavHeader()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up navigation drawer", e)
        }
    }

    fun updateNavHeader() {
        try {
            val headerView = navigationView.getHeaderView(0)
            if (headerView != null) {
                val userLoginText = headerView.findViewById<TextView>(R.id.userLoginText)
                if (userLoginText != null) {
                    val currentUser = auth.currentUser
                    if (currentUser != null) {
                        userLoginText.text = currentUser.email ?: "Guest"
                        Log.d(TAG, "Updated nav header with email: ${currentUser.email}")

                        // Also check Firestore for user info
                        currentUser.uid.let { userId ->
                            firestore.collection("users").document(userId)
                                .get()
                                .addOnSuccessListener { document ->
                                    if (document != null && document.exists()) {
                                        val name = document.getString("name")
                                        if (!name.isNullOrEmpty()) {
                                            userLoginText.text = name
                                            Log.d(TAG, "Updated nav header with name: $name")
                                        }
                                    }
                                }
                        }
                    } else {
                        userLoginText.text = "Guest"
                        Log.d(TAG, "Updated nav header to Guest (no user)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating nav header", e)
        }
    }

    override fun onResume() {
        super.onResume()
        updateNavHeader()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_home -> {
                // Handle home navigation
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_transactions -> {
                // Handle transactions navigation
                val intent = Intent(this, TransactionActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_settings -> {
                // Handle settings navigation
            }
            R.id.nav_login_logout -> {
                // Handle logout
                if (auth.currentUser != null) {
                    auth.signOut()
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else {
                    val intent = Intent(this, LoginActivity::class.java)
                    startActivity(intent)
                }
            }
        }
        drawerLayout.closeDrawers()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                drawerLayout.openDrawer(GravityCompat.START)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}