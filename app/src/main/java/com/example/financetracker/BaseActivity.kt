package com.example.financetracker

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    protected lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    protected open lateinit var auth: FirebaseAuth
    protected open lateinit var firestore: FirebaseFirestore
    private val TAG = "BaseActivity"

    // Create our own coroutine scope
    private val activityScope = CoroutineScope(Dispatchers.Main)

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
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_login_logout -> {
                // Handle login/logout
                if (auth.currentUser != null) {
                    // Show logout confirmation dialog
                    showLogoutConfirmationDialog()
                } else {
                    val intent = Intent(this, LoginActivity::class.java)
                    startActivity(intent)
                }
            }
        }
        drawerLayout.closeDrawers()
        return true
    }

    // Add this method to BaseActivity
    protected fun showLogoutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout Confirmation")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                // Show a progress dialog
                val progressDialog = AlertDialog.Builder(this)
                    .setTitle("Logging Out")
                    .setMessage("Please wait...")
                    .setCancelable(false)
                    .create()

                progressDialog.show()

                GuestUserManager.setGuestMode(applicationContext, true)

                // Use the application context to get ViewModel
                val database = TransactionDatabase.getDatabase(applicationContext)
                val viewModel = TransactionViewModel(database, application)

                // Stop listening to Firestore updates
                viewModel.stopListeningToTransactions()

                // Use our own coroutine scope instead of lifecycleScope
                activityScope.launch {
                    try {
                        // Force synchronous execution
                        withContext(Dispatchers.IO) {
                            // Clear transactions directly from DAO
                            database.transactionDao().clearTransactions()

                            // Double-check if transactions are cleared
                            val remaining = database.transactionDao().getAllTransactions().first()
                            if (remaining.isNotEmpty()) {
                                Log.w(TAG, "Warning: ${remaining.size} transactions still remain after clearing")
                                // Try again
                                database.transactionDao().clearTransactions()
                            }
                        }

                        // Sign out
                        auth.signOut()

                        // Switch back to Main dispatcher for UI operations
                        withContext(Dispatchers.Main) {
                            // Dismiss progress dialog
                            progressDialog.dismiss()

                            // Show success message
                            Toast.makeText(
                                this@BaseActivity,
                                "Logged out successfully",
                                Toast.LENGTH_SHORT
                            ).show()

                            // Add a small delay to ensure operations complete
                            delay(300)

                            // Redirect to login
                            val intent = Intent(this@BaseActivity, MainActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        }
                    } catch (e: Exception) {
                        // Switch back to Main dispatcher for UI operations
                        withContext(Dispatchers.Main) {
                            // Handle errors
                            progressDialog.dismiss()
                            Log.e(TAG, "Error during logout", e)
                            Toast.makeText(
                                this@BaseActivity,
                                "Error during logout: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
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