package com.example.financetracker

import android.Manifest
import androidx.transition.TransitionManager
import androidx.transition.AutoTransition
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
import android.provider.Telephony
import android.util.Log
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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.example.financetracker.databinding.ActivityMainBinding
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.NotificationCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.example.financetracker.database.dao.TransactionDao
import com.example.financetracker.ui.screens.StatisticsScreen
import com.example.financetracker.utils.CategoryUtils
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.viewmodel.StatisticsViewModel
import com.example.financetracker.viewmodel.TransactionStatistics
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.first
import kotlin.math.abs

class MainActivity : BaseActivity(), TransactionDetailsDialog.TransactionDetailsListener {

    override fun getLayoutResourceId(): Int = R.layout.activity_main

    private val statisticsViewModel: StatisticsViewModel by viewModels()
    private lateinit var smsBroadcastReceiver: SmsBroadcastReceiver
    private lateinit var messageExtractor: MessageExtractor
    private var currentTransaction: Transaction? = null
    private var currentMessageBody: String? = null
    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"
    private lateinit var statisticsCard: MaterialCardView
    private lateinit var categoryStatsContainer: LinearLayout
    private lateinit var expandCollapseIcon: ImageView
    private var isExpanded = false

    private val transactionViewModel: TransactionViewModel by viewModels {
        val database = TransactionDatabase.getDatabase(this)
        TransactionViewModel.Factory(database, application)
    }

    private val transactionDao by lazy {
        TransactionDatabase.getDatabase(this).transactionDao()
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

    private fun updateGuestModeBanner() {
        val guestModeBanner = binding.guestModeBanner
        val isGuestMode = GuestUserManager.isGuestMode(this)

        guestModeBanner.visibility = if (isGuestMode) View.VISIBLE else View.GONE
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
                        // Get current user ID before proceeding
                        val userId = auth.currentUser?.uid

                        if (userId == null) {
                            Toast.makeText(
                                this, "You must be logged in to add transactions",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@registerForActivityResult
                        }

                        val name = data.getStringExtra("name")
                            ?: throw IllegalArgumentException("Name is required")
                        val amount = data.getDoubleExtra("amount", 0.0)
                        val date = data.getStringExtra("date")
                            ?: throw IllegalArgumentException("Date is required")
                        val category = data.getStringExtra("category") ?: "Uncategorized"
                        val merchant = data.getStringExtra("merchant") ?: ""
                        val description = data.getStringExtra("description") ?: ""

                        val transaction = Transaction(
                            id = 0,  // Room will generate this
                            name = name,
                            amount = amount,
                            date = parseDateToLong(date),
                            category = category,
                            merchant = merchant,
                            description = description,
                            userId = userId  // Set the user ID
                        )

                        // Add to Room database
                        lifecycleScope.launch {
                            transactionViewModel.addTransaction(transaction)
                            // The transaction will be synced to Firestore by the ViewModel
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding transaction", e)
                        Toast.makeText(
                            this,
                            "Error adding transaction: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    private fun addTransactionToFirestore(transaction: Transaction) {
        // Get userId (either authenticated or guest)
        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)
        val isGuestMode = GuestUserManager.isGuestMode(userId)

        Log.d(TAG, "Adding transaction: userId=$userId, guestMode=$isGuestMode")

        // Ensure transaction has userId
        transaction.userId = userId

        // For guest users, just add to Room database
        if (isGuestMode) {
            lifecycleScope.launch {
                transactionViewModel.addTransaction(transaction)
            }
            return
        }

        // For authenticated users, create a document reference first to get an ID
        val docRef = firestore.collection("users")
            .document(userId)
            .collection("transactions")
            .document()

        // Get the document ID
        val docId = docRef.id

        // Set the document ID in the transaction
        transaction.documentId = docId

        // Create a map with all transaction data
        val transactionMap = hashMapOf(
            "id" to transaction.id,
            "name" to transaction.name,
            "amount" to transaction.amount,
            "date" to transaction.date,
            "category" to transaction.category,
            "merchant" to transaction.merchant,
            "description" to transaction.description,
            "documentId" to docId,
            "userId" to userId
        )

        // Save to Firestore
        docRef.set(transactionMap)
            .addOnSuccessListener {
                Log.d(TAG, "Transaction added to Firestore with ID: $docId")

                // Update local database with document ID
                lifecycleScope.launch {
                    transactionViewModel.updateTransaction(transaction)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error adding transaction to Firestore", e)
                Toast.makeText(this, "Error saving to cloud: ${e.message}", Toast.LENGTH_SHORT).show()

                // Still save to local database even if Firestore fails
                lifecycleScope.launch {
                    transactionViewModel.addTransaction(transaction)
                }
            }
    }

    private fun debugCategories() {
        val userId = auth.currentUser?.uid ?: "guest_user"
        lifecycleScope.launch {
            try {
                val database = TransactionDatabase.getDatabase(applicationContext)
                val categories = database.categoryDao().getAllCategories(userId).first()

                Log.d("MainActivity", "=== DEBUG CATEGORIES ===")
                Log.d("MainActivity", "Found ${categories.size} categories for user $userId")
                categories.forEach { category ->
                    Log.d("MainActivity", "Category: ${category.name}, isDefault: ${category.isDefault}")
                }
                Log.d("MainActivity", "======================")

                if (categories.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No categories found, adding defaults", Toast.LENGTH_SHORT).show()
                    CategoryUtils.initializeCategories(this@MainActivity)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error debugging categories", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupComposeStatistics()

        CategoryUtils.initializeCategories(this)

        debugCategories()

        // Add this to your MainActivity's onCreate() method
        findViewById<Button>(R.id.testSmsButton).setOnClickListener {
            Toast.makeText(this, "Testing SMS processing with Gemini...", Toast.LENGTH_SHORT).show()

            // Create test messages for different bank formats
            val testMessages = listOf(
                "Your account has been debited with Rs 1500.00 for Amazon payment on 28-03-2024. Transaction ID: HDFC12345.",
                "INR 2,999.00 debited from A/c XX1234 at SWIGGY on 28-03-24",
                "Rs.799.00 spent via Debit Card xx1234 at NETFLIX.COM on 28/03/2024"
            )

            // Test each message
            lifecycleScope.launch {
                try {
                    val messageExtractor = MessageExtractor(this@MainActivity)

                    testMessages.forEachIndexed { index, message ->
                        Log.d("SMS_TEST", "Testing message ${index + 1}: $message")

                        // Extract details using Gemini
                        val details = messageExtractor.extractTransactionDetails(message)

                        if (details != null) {
                            Log.d("SMS_TEST", "Successfully extracted details: $details")

                            // Create notification to show results
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                            val notification = NotificationCompat.Builder(this@MainActivity, "debug_channel")
                                .setSmallIcon(R.drawable.ic_notification)
                                .setContentTitle("Gemini Test #${index + 1}")
                                .setContentText("Amount: ₹${details.amount}, Merchant: ${details.merchant}")
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .build()

                            notificationManager.notify(System.currentTimeMillis().toInt() + index, notification)

                            // Show results in UI
                            Toast.makeText(
                                this@MainActivity,
                                "Test ${index + 1} Success!\nAmount: ₹${details.amount}\nMerchant: ${details.merchant}",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Log.e("SMS_TEST", "Failed to extract details from test message $index")
                            Toast.makeText(
                                this@MainActivity,
                                "Test ${index + 1} Failed - No details extracted",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        // Add delay between tests
                        kotlinx.coroutines.delay(2000)
                    }
                } catch (e: Exception) {
                    Log.e("SMS_TEST", "Error in test", e)
                    Toast.makeText(
                        this@MainActivity,
                        "Error during test: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Initialize Firebase Auth and Firestore with logging
        try {
            auth = FirebaseAuth.getInstance()
            firestore = FirebaseFirestore.getInstance()

            // Log authentication state
            val currentUser = auth.currentUser
            if (currentUser != null) {
                Log.d(TAG, "User is signed in: ${currentUser.uid}, Email: ${currentUser.email}")

                // Start listening for transactions
                transactionViewModel.startListeningToTransactions(currentUser.uid)
            } else {
                Log.d(TAG, "No user is signed in")
                // Redirect to login - handled by BaseActivity
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization error", e)
            Toast.makeText(this, "Firebase init error: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)

        val isGuestMode = GuestUserManager.isGuestMode(userId)

        // Update UI for guest mode
        updateGuestModeBanner()

        transactionViewModel.startListeningToTransactions(userId)

        setupNavigationDrawer()
        setupPermissions()
        setupStatisticsView()
        setupNotificationChannel()
        requestNotificationPermission()
        //setupStatisticsButton()
        setupObservers()

        // Handle intent extras for notifications
        handleIntentExtras(intent)

        setSupportActionBar(findViewById(R.id.toolbar))

        findViewById<NavigationView>(R.id.nav_view).setNavigationItemSelectedListener(this)

        // Set up the Add Transaction FAB
        findViewById<FloatingActionButton>(R.id.addTransactionButton).setOnClickListener {
            if (auth.currentUser != null) {
                val intent = Intent(this, AddTransactionActivity::class.java)
                addTransactionLauncher.launch(intent)
            } else {
                Toast.makeText(this, "Please log in to add transactions", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, LoginActivity::class.java)
                startActivity(intent)
            }
        }
        // Set the current user's name in the navigation drawer
        updateNavHeader()


        // Initialize views
        statisticsCard = findViewById(R.id.statisticsCard)
        categoryStatsContainer = findViewById(R.id.categoryStatsContainer)
        expandCollapseIcon = findViewById(R.id.expandCollapseIcon)

        // Set initial collapsed height
        setCollapsedHeight()

        // Setup click listener for statistics card
        statisticsCard.setOnClickListener {
            toggleStatisticsExpansion()
        }

        // Initialize other views and setup
        setupView()
        setupStatisticsObserver()
        intent?.let { handleIntentExtras(it) }
    }

    private fun setCollapsedHeight() {
        val constraintLayout = statisticsCard.parent as ConstraintLayout
        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)
        constraintSet.constrainPercentHeight(statisticsCard.id, 0.25f) // 2/8 of screen height
        constraintSet.applyTo(constraintLayout)
    }

    private fun setupComposeStatistics() {
        binding.statisticsComposeView.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    StatisticsScreen(statisticsViewModel)
                }
            }
        }
    }

    private fun toggleStatisticsExpansion() {
        isExpanded = !isExpanded

        val constraintLayout = statisticsCard.parent as ConstraintLayout
        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)

        // Setup transition
        val transition = AutoTransition().apply {
            duration = 300
            interpolator = FastOutSlowInInterpolator()
        }

        TransitionManager.beginDelayedTransition(constraintLayout, transition)

        if (isExpanded) {
            // Expand to 80% height
            constraintSet.constrainPercentHeight(statisticsCard.id, 0.8f)
            categoryStatsContainer.visibility = View.VISIBLE
            expandCollapseIcon.animate().rotation(180f).duration = 300
        } else {
            // Collapse to 25% height (2/8)
            constraintSet.constrainPercentHeight(statisticsCard.id, 0.25f)
            categoryStatsContainer.visibility = View.GONE
            expandCollapseIcon.animate().rotation(0f).duration = 300
        }

        constraintSet.applyTo(constraintLayout)
    }

    private fun setupStatisticsObserver() {
        transactionViewModel.transactionStatistics.observe(this) { stats ->
            updateStatistics(stats)
        }
    }

    private fun updateStatistics(stats: TransactionStatistics) {
        // Update general statistics
        findViewById<TextView>(R.id.maxExpenseText).text =
            getString(R.string.max_expense_format, stats.maxExpense)
        findViewById<TextView>(R.id.minExpenseText).text =
            getString(R.string.min_expense_format, stats.minExpense)

        // Update category statistics
        categoryStatsContainer.removeAllViews()

        stats.categoryStats.forEach { (category, categoryStats) ->
            val categoryView = layoutInflater.inflate(
                R.layout.item_category_stats,
                categoryStatsContainer,
                false
            ).apply {
                findViewById<TextView>(R.id.categoryName).text = category
                findViewById<TextView>(R.id.categoryMaxExpense).text =
                    getString(R.string.amount_format, categoryStats.maxExpense)
                findViewById<TextView>(R.id.categoryTotalExpense).text =
                    getString(R.string.amount_format, categoryStats.totalExpense)
                findViewById<TextView>(R.id.categoryTransactionCount).text =
                    getString(R.string.transaction_count_format, categoryStats.transactionCount)
            }
            categoryStatsContainer.addView(categoryView)
        }
    }

    private fun setupView() {
        // Setup other views and listeners
        transactionViewModel.transactions.observe(this) { transactions ->
            findViewById<TextView>(R.id.noTransactionsMessage)?.visibility =
                if (transactions.isEmpty()) View.VISIBLE else View.GONE
        }

        // Setup SMS test button
        findViewById<Button>(R.id.testSmsButton).setOnClickListener {
            // Your existing SMS test implementation
        }
    }

    private fun setupDrawerToggle() {
        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            findViewById(R.id.toolbar),
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
    }

    private fun updateUI(user: FirebaseUser?) {
        if (user == null) {
            // Clear transactions from local database when user logs out
            transactionViewModel.clearTransactions()

            // Make sure to stop listening when logging out
            transactionViewModel.stopListeningToTransactions()

            // Redirect to login
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } else {
            // User is logged in, only start listening if not already listening
            transactionViewModel.startListeningToTransactions(user.uid)
        }
    }

    private fun fetchUserTransactions(userId: String) {
        Log.d(TAG, "Fetching transactions for user: $userId")

        // Start listening for real-time updates
        transactionViewModel.startListeningToTransactions(userId)
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

            // Get current user ID
            val userId = auth.currentUser?.uid ?: return

            // Create a temporary transaction for the dialog
            val transaction = Transaction(
                id = 0,
                name = merchant,
                amount = amount,
                date = date,
                category = "Uncategorized",
                merchant = merchant,
                description = description,
                userId = userId
            )

            message?.let {
                showTransactionDetailsDialog(transaction, it)
            }
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
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        registerReceiver(smsBroadcastReceiver, filter)
        Log.d(TAG, "SMS receiver registered with action: ${Telephony.Sms.Intents.SMS_RECEIVED_ACTION}")
    }

    /*private fun setupStatisticsButton() {
        findViewById<Button>(R.id.statisticsButton).setOnClickListener {
            val intent = Intent(this, StatisticsActivity::class.java)
            startActivity(intent)
        }
    }*/

    private fun setupObservers() {
        transactionViewModel.transactions.observe(this) { transactions ->
            // Handle transactions if needed
            Log.d(TAG, "Observed ${transactions.size} transactions in LiveData")

            // Show/hide "no transactions" message
            if (transactions.isEmpty()) {
                findViewById<TextView>(R.id.noTransactionsMessage)?.visibility = View.VISIBLE
            } else {
                findViewById<TextView>(R.id.noTransactionsMessage)?.visibility = View.GONE
            }
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

    // Add this function to your MainActivity
    fun testSmsReceiver() {
        try {
            // Check if receiver is registered
            val isInitialized = SmsBroadcastReceiver.isInitialized
            Log.d(TAG, "SMS Receiver initialized: $isInitialized")

            // Try to create a simulated SMS intent for testing
            val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            val bundle = Bundle()
            bundle.putString("sender", "SBIUPI")
            bundle.putString("message", "Your account has been debited with Rs 100.00 for GPAY payment.")
            intent.putExtras(bundle)

            // Log the receiver status
            Toast.makeText(this, "SMS Receiver status: ${if (isInitialized) "Initialized" else "Not Initialized"}",
                Toast.LENGTH_LONG).show()

            // Register receiver again just to be sure
            registerSmsReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "Error testing SMS receiver", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Log.e(TAG, "Error parsing date: $date", e)
            System.currentTimeMillis()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> {
                // Refresh data from Firestore
                val userId = auth.currentUser?.uid
                if (userId != null) {
                    Toast.makeText(this, "Refreshing data...", Toast.LENGTH_SHORT).show()
                    transactionViewModel.stopListeningToTransactions()
                    transactionViewModel.startListeningToTransactions(userId)
                }
                return true
            }

            R.id.menu_filter_today -> {
                filterTransactionsByDate(FilterPeriod.TODAY)
                return true
            }

            R.id.menu_filter_week -> {
                filterTransactionsByDate(FilterPeriod.WEEK)
                return true
            }

            R.id.menu_filter_month -> {
                filterTransactionsByDate(FilterPeriod.MONTH)
                return true
            }

            R.id.menu_filter_clear -> {
                transactionViewModel.loadAllTransactions()
                return true
            }

            android.R.id.home -> {
                drawerLayout.openDrawer(GravityCompat.START)
                return true
            }
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
        lifecycleScope.launch {
            try {
                val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)

                // Save merchant-category mapping first
                transactionViewModel.saveMerchant(merchant, category, userId)

                currentTransaction?.let { transaction ->
                    // Update transaction details
                    transaction.apply {
                        this.name = merchant
                        this.category = category
                        this.userId = userId
                    }

                    Log.d(TAG, "Updating transaction: $transaction")

                    // Update in Room and Firestore via ViewModel
                    transactionViewModel.updateTransaction(transaction)

                    // Force refresh the transaction list
                    transactionViewModel.loadAllTransactions()

                    Toast.makeText(this@MainActivity,
                        "Transaction updated successfully",
                        Toast.LENGTH_SHORT).show()
                }

                if (saveAsPattern) {
                    currentMessageBody?.let { messageBody ->
                        saveTransactionPattern(messageBody, merchant, category)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating transaction", e)
                Toast.makeText(this@MainActivity,
                    "Error updating transaction: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateTransactionInFirestore(transaction: Transaction) {
        val userId = auth.currentUser?.uid ?: return

        // Check if this transaction has already been synced recently to prevent duplicates
        if (transaction.documentId.isNotEmpty()) {
            Log.d(TAG, "Using existing documentId: ${transaction.documentId}")
        } else {
            Log.d(TAG, "No documentId found, will generate one")
        }

        // Create a map with all fields
        val transactionMap = hashMapOf(
            "id" to transaction.id,
            "name" to transaction.name,
            "amount" to transaction.amount,
            "date" to transaction.date,
            "category" to transaction.category,
            "merchant" to transaction.merchant,
            "description" to transaction.description,
            "userId" to userId
        )

        // Get document ID - crucial change here
        val docId = if (!transaction.documentId.isNullOrEmpty()) {
            // Use existing document ID
            transaction.documentId
        } else {
            // Before generating a new ID, check if a transaction with same properties exists
            lifecycleScope.launch {
                try {
                    val transactions = transactionDao.getAllTransactions().first()
                    val matchingTransaction = transactions.find {
                        it.userId == transaction.userId &&
                                it.amount == transaction.amount &&
                                abs(it.date - transaction.date) < 60000 && // Within 1 minute
                                it.name == transaction.name &&
                                !it.documentId.isNullOrEmpty()
                    }

                    if (matchingTransaction != null) {
                        Log.d(TAG, "Found matching transaction with docId: ${matchingTransaction.documentId}")
                        // Use the existing document ID
                        transaction.documentId = matchingTransaction.documentId
                        transactionViewModel.updateTransaction(transaction)
                        return@launch
                    }

                    // If no matching transaction found, generate a new document ID
                    val newDocId = firestore.collection("users")
                        .document(userId)
                        .collection("transactions")
                        .document().id

                    transaction.documentId = newDocId
                    transactionMap["documentId"] = newDocId

                    // Update in Firestore with the new ID
                    firestore.collection("users")
                        .document(userId)
                        .collection("transactions")
                        .document(newDocId)
                        .set(transactionMap)
                        .addOnSuccessListener {
                            Log.d(TAG, "Transaction successfully added to Firestore with new ID: $newDocId")
                            transactionViewModel.updateTransaction(transaction)
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Error updating transaction in Firestore", e)
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking for duplicate transactions", e)
                }
            }
            return // Exit early as we're handling this in the coroutine
        }

        // Add document ID to the map
        transactionMap["documentId"] = docId

        // Update in Firestore
        firestore.collection("users")
            .document(userId)
            .collection("transactions")
            .document(docId)
            .set(transactionMap)
            .addOnSuccessListener {
                Log.d(TAG, "Transaction successfully updated in Firestore")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating transaction in Firestore", e)
                Toast.makeText(this, "Error updating in cloud: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // Override this method in MainActivity to use your existing confirmation dialog
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_home -> {
                // Already in MainActivity, just close drawer
            }

            R.id.nav_transactions -> {
                // Navigate to TransactionActivity
                val intent = Intent(this, TransactionActivity::class.java)
                startActivity(intent)
            }

            R.id.nav_settings -> {
                // Handle settings navigation if implemented
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
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

    // Replace your existing showLogoutConfirmationDialog method with this improved version


    private fun saveTransactionPattern(messageBody: String, merchant: String, category: String) {
        val pattern = TransactionPattern(messageBody, merchant, category)
        val prefs = getSharedPreferences("TransactionPatterns", Context.MODE_PRIVATE)
        val patternsJson = prefs.getStringSet("patterns", mutableSetOf()) ?: mutableSetOf()
        val newPatternsJson = mutableSetOf<String>().apply {
            addAll(patternsJson)
            add(Gson().toJson(pattern))
        }
        prefs.edit().putStringSet("patterns", newPatternsJson).apply()

        Log.d(TAG, "Transaction pattern saved: $pattern")
        Toast.makeText(this, "Pattern saved for future transactions", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(smsBroadcastReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
            Log.e(TAG, "Error unregistering receiver", e)
        }

        // Stop listening to Firestore updates
        transactionViewModel.stopListeningToTransactions()
    }

    override fun onResume() {
        super.onResume()

        // Check if user is still authenticated
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // Refresh data
            transactionViewModel.startListeningToTransactions(currentUser.uid)

            // Show current user info
            val currentDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Calendar.getInstance().time)
            Log.d(TAG, "Current Date and Time (Local - YYYY-MM-DD HH:MM:SS formatted): $currentDateTime")
            Log.d(TAG, "Current User's Login: ${currentUser.email}")

            // Update subtitle with user info
            supportActionBar?.subtitle = "Logged in as: ${currentUser.email}"
        } else {
            // User not logged in - BaseActivity should handle this
        }
    }

    // For debugging Firestore connectivity
    private fun debugFirestore() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.d(TAG, "No user logged in for debugging")
            Toast.makeText(this, "Please log in first", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if user document exists
        firestore.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    Log.d(TAG, "Found user document: ${document.data}")
                    Toast.makeText(this, "User document exists", Toast.LENGTH_SHORT).show()
                } else {
                    Log.d(TAG, "No user document found")
                    Toast.makeText(this, "No user document found - creating one", Toast.LENGTH_SHORT).show()

                    // Create user document if missing
                    val userProfile = hashMapOf(
                        "uid" to userId,
                        "email" to (auth.currentUser?.email ?: ""),
                        "createdAt" to System.currentTimeMillis(),
                        "lastLogin" to "2025-03-07 10:29:59",
                        "username" to "TharunSree"  // Example using the provided information
                    )

                    firestore.collection("users").document(userId)
                        .set(userProfile)
                        .addOnSuccessListener {
                            Toast.makeText(this, "User document created", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking user document", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Shows a dialog to confirm adding a new transaction with detected info
     */
    fun showAddTransactionConfirmation(amount: Double, merchant: String, date: Long) {
        val userId = auth.currentUser?.uid ?: return

        // Create proposed transaction
        val transaction = Transaction(
            id = 0,
            name = merchant,
            amount = amount,
            date = date,
            category = "Uncategorized",
            merchant = merchant,
            description = "Auto-detected transaction",
            userId = userId
        )

        // Show confirmation dialog
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)

        AlertDialog.Builder(this)
            .setTitle("New Transaction Detected")
            .setMessage("Would you like to add this transaction?\n\nMerchant: $merchant\nAmount: $${String.format("%.2f", amount)}\nDate: $dateStr")
            // In showAddTransactionConfirmation method, update the Add button click handler:
            .setPositiveButton("Add") { _, _ ->
                lifecycleScope.launch {
                    transactionViewModel.addTransaction(transaction)
                    // Remove the addTransactionToFirestore call - it's redundant
                    Toast.makeText(this@MainActivity, "Transaction added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Edit First") { _, _ ->
                // Launch EditTransactionActivity with pre-filled data
                val intent = Intent(this, AddTransactionActivity::class.java).apply {
                    putExtra("merchant", merchant)
                    putExtra("amount", amount)
                    putExtra("date", dateStr)
                }
                addTransactionLauncher.launch(intent)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    /**
     * Synchronizes all local transactions with Firestore to ensure data integrity
     */
    private fun syncAllTransactionsToFirestore() {
        val userId = auth.currentUser?.uid ?: return

        lifecycleScope.launch {
            try {
                val localTransactions = transactionDao.getAllTransactions().first()

                for (transaction in localTransactions) {
                    // Skip transactions that don't belong to this user
                    if (transaction.userId != userId) continue

                    // Update in Firestore
                    updateTransactionInFirestore(transaction)
                }

                Toast.makeText(this@MainActivity, "Sync completed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error during sync", e)
                Toast.makeText(this@MainActivity, "Sync failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    enum class FilterPeriod {
        TODAY, WEEK, MONTH
    }
}