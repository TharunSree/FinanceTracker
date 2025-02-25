package com.example.financetracker

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.TransactionPattern
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.ui.dialogs.TransactionDetailsDialog
import com.example.financetracker.utils.MessageExtractor
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.example.financetracker.databinding.ActivityMainBinding
import com.example.financetracker.databinding.NavHeaderBinding

class MainActivity : BaseActivity(), TransactionDetailsDialog.TransactionDetailsListener,
    NavigationView.OnNavigationItemSelectedListener {

    override fun getLayoutResourceId(): Int = R.layout.activity_main

    private lateinit var smsBroadcastReceiver: SmsBroadcastReceiver
    private lateinit var messageExtractor: MessageExtractor
    private var currentTransaction: Transaction? = null
    private var currentMessageBody: String? = null
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var toggle: ActionBarDrawerToggle
    private lateinit var binding: ActivityMainBinding
    private lateinit var headerBinding: NavHeaderBinding

    private val transactionViewModel: TransactionViewModel by viewModels {
        val database = TransactionDatabase.getDatabase(this)
        TransactionViewModel.Factory(database)
    }

    companion object {
        private const val SMS_PERMISSION_CODE = 123
        private const val NOTIFICATION_PERMISSION_CODE = 124

        const val TRANSACTION_CHANNEL_ID = "TRANSACTION_CHANNEL"
        const val DETAILS_REQUIRED_CHANNEL_ID = "DETAILS_REQUIRED_CHANNEL"
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                registerSmsReceiver()
                setupNotificationChannel()
            } else {
                val deniedPermissions = permissions.entries.filter { !it.value }
                showPermissionsDeniedMessage(deniedPermissions.map { it.key })
            }
        }

    private val addTransactionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.let { data ->
                    try {
                        val name = data.getStringExtra("name")
                            ?: throw IllegalArgumentException("Name is required")
                        val amount = data.getDoubleExtra("amount", 0.0)
                        val date = data.getStringExtra("date")
                            ?: throw IllegalArgumentException("Date is required")
                        val category = data.getStringExtra("category") ?: "Uncategorized"
                        val merchant = data.getStringExtra("merchant") ?: ""
                        val description = data.getStringExtra("description") ?: ""

                        val transaction = Transaction(
                            id = 0,
                            name = name,
                            amount = amount,
                            date = parseDateToLong(date),
                            category = category,
                            merchant = merchant,
                            description = description
                        )

                        transactionViewModel.addTransaction(transaction)
                    } catch (e: Exception) {
                        Toast.makeText(
                            this,
                            "Error adding transaction: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase Auth and Firestore
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // Setup navigation drawer toggle
        setupDrawerToggle()

        // Other initialization code...
        setupPermissions()
        setupStatisticsView()
        setupNotificationChannel()
        requestNotificationPermission()
        setupStatisticsButton()
        setupObservers()

        // Handle intent extras for notifications
        handleIntentExtras(intent)

        setSupportActionBar(binding.toolbar)

        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener(this)

        // Update nav header with user info
        headerBinding = NavHeaderBinding.bind(binding.navView.getHeaderView(0))
        updateNavHeader()
    }

    private fun setupDrawerToggle() {
        // Initialize the toggle
        toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
    }

    private fun updateUI(user: FirebaseUser?) {
        // Update UI based on user state
        if (user == null) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val transactionChannel = NotificationChannel(
                TRANSACTION_CHANNEL_ID,
                "Transactions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Financial transaction notifications"
                enableLights(true)
                lightColor = Color.GREEN
                enableVibration(true)
            }

            val detailsChannel = NotificationChannel(
                DETAILS_REQUIRED_CHANNEL_ID,
                "Transaction Details Required",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for missing transaction details"
                enableLights(true)
                lightColor = Color.YELLOW
                enableVibration(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(transactionChannel)
            notificationManager.createNotificationChannel(detailsChannel)
        }
    }

    private fun showPermissionsDeniedMessage(deniedPermissions: List<String>) {
        val message = buildString {
            append("The following permissions are required:\n\n")
            deniedPermissions.forEach { permission ->
                append("• ${getPermissionDescription(permission)}\n")
            }
            append("\nPlease grant these permissions in Settings to use all features.")
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupStatisticsView() {
        transactionViewModel.transactionStatistics.observe(this) { stats ->
            findViewById<TextView>(R.id.maxExpenseText).text =
                getString(R.string.max_expense_format, stats.maxExpense)
            findViewById<TextView>(R.id.minExpenseText).text =
                getString(R.string.min_expense_format, stats.minExpense)

            // Update category-wise statistics
            val categoryStatsContainer = findViewById<LinearLayout>(R.id.categoryStatsContainer)
            categoryStatsContainer.removeAllViews()

            stats.categoryStats.forEach { (category, categoryStats) ->
                val categoryView = layoutInflater.inflate(
                    R.layout.item_category_stats,
                    categoryStatsContainer,
                    false
                )

                categoryView.findViewById<TextView>(R.id.categoryName).text = category
                categoryView.findViewById<TextView>(R.id.categoryMaxExpense).text =
                    getString(R.string.amount_format, categoryStats.maxExpense)
                categoryView.findViewById<TextView>(R.id.categoryTotalExpense).text =
                    getString(R.string.amount_format, categoryStats.totalExpense)

                categoryStatsContainer.addView(categoryView)
            }
        }
    }

    private fun openAppSettings() {
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
            startActivity(this)
        }
    }

    private fun handleIntentExtras(intent: Intent) {
        if (intent.getBooleanExtra("SHOW_TRANSACTION_DIALOG", false)) {
            val message = intent.getStringExtra("TRANSACTION_MESSAGE")
            val amount = intent.getDoubleExtra("TRANSACTION_AMOUNT", 0.0)
            val date = intent.getLongExtra("TRANSACTION_DATE", System.currentTimeMillis())
            val merchant = intent.getStringExtra("TRANSACTION_MERCHANT") ?: "Unknown Merchant"
            val description = intent.getStringExtra("TRANSACTION_DESCRIPTION") ?: ""

            // Create a temporary transaction for the dialog
            val transaction = Transaction(
                id = 0,
                name = merchant,
                amount = amount,
                date = date,
                category = "Uncategorized",
                merchant = merchant,
                description = description
            )

            message?.let {
                showTransactionDetailsDialog(transaction, it)
            }
        }
        if (intent.getBooleanExtra("SHOW_TRANSACTION_DIALOG", false)) {
            val message = intent.getStringExtra("TRANSACTION_MESSAGE")
            val amount = intent.getDoubleExtra("TRANSACTION_AMOUNT", 0.0)
            // Use Firestore to handle transaction dialog if necessary
        }
    }

    private fun getPermissionDescription(permission: String): String {
        return when (permission) {
            Manifest.permission.RECEIVE_SMS -> "SMS Reception (for transaction tracking)"
            Manifest.permission.READ_SMS -> "SMS Reading (for transaction details)"
            Manifest.permission.POST_NOTIFICATIONS -> "Notifications (for transaction alerts)"
            else -> permission
        }
    }

    private fun setupPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )

        if (!permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            requestPermissionsLauncher.launch(permissions)
        } else {
            registerSmsReceiver()
        }
    }

    private fun registerSmsReceiver() {
        smsBroadcastReceiver = SmsBroadcastReceiver()
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        registerReceiver(smsBroadcastReceiver, filter)
    }

    private fun setupStatisticsButton() {
        findViewById<Button>(R.id.statisticsButton).setOnClickListener {
            val intent = Intent(this, StatisticsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupObservers() {
        transactionViewModel.transactions.observe(this) { transactions ->
            // Handle transactions if needed
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                transactionViewModel.filteredTransactions.collect { filteredTransactions ->
                    // Handle filtered transactions if needed
                    updateFilterStatus(filteredTransactions.size)
                }
            }
        }
    }

    private fun updateFilterStatus(count: Int) {
        supportActionBar?.subtitle = "Showing $count transactions"
    }

    private fun filterTransactionsByDate(period: FilterPeriod) {
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        when (period) {
            FilterPeriod.TODAY -> Unit // Start time is already set to today's beginning
            FilterPeriod.WEEK -> calendar.add(Calendar.DAY_OF_YEAR, -7)
            FilterPeriod.MONTH -> calendar.add(Calendar.MONTH, -1)
        }

        val startTime = calendar.timeInMillis
        transactionViewModel.loadTransactionsByDateRange(startTime, endTime)
    }

    private fun parseDateToLong(date: String): Long {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            dateFormat.parse(date)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentExtras(intent)
    }

    fun showTransactionDetailsDialog(transaction: Transaction, messageBody: String) {
        currentTransaction = transaction
        currentMessageBody = messageBody

        val dialog = TransactionDetailsDialog.newInstance(
            originalMerchant = transaction.name,
            messageBody = messageBody
        )
        dialog.show(supportFragmentManager, "TransactionDetailsDialog")
    }

    override fun onDetailsEntered(merchant: String, category: String, saveAsPattern: Boolean) {
        currentTransaction?.let { transaction ->
            lifecycleScope.launch {
                transaction.name = merchant
                transaction.category = category
                transactionViewModel.updateTransaction(transaction)
            }
        }

        if (saveAsPattern) {
            currentMessageBody?.let { messageBody ->
                saveTransactionPattern(messageBody, merchant, category)
            }
        }
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
                handleLoginLogout()
            }
        }

        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun handleLoginLogout() {
        if (auth.currentUser != null) {
            // User is signed in, show logout confirmation dialog
            showLogoutConfirmationDialog()
        } else {
            // User is not signed in, redirect to login activity
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }
    }

    private fun showLogoutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout Confirmation")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                auth.signOut()
                Toast.makeText(this, "Logged out successfully.", Toast.LENGTH_SHORT).show()
                updateUI(null)
                updateNavHeader()
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun saveTransactionPattern(messageBody: String, merchant: String, category: String) {
        val pattern = TransactionPattern(messageBody, merchant, category)
        val prefs = getSharedPreferences("TransactionPatterns", Context.MODE_PRIVATE)
        val patternsJson = prefs.getStringSet("patterns", mutableSetOf()) ?: mutableSetOf()
        val newPatternsJson = mutableSetOf<String>().apply {
            addAll(patternsJson)
            add(Gson().toJson(pattern))
        }
        prefs.edit().putStringSet("patterns", newPatternsJson).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(smsBroadcastReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    private fun updateNavHeader() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            headerBinding.userLoginText.text = currentUser.email
        } else {
            headerBinding.userLoginText.text = "Guest"
        }
    }

    enum class FilterPeriod {
        TODAY, WEEK, MONTH
    }
}