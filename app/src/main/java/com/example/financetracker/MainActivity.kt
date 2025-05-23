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
import android.provider.Telephony
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.TransactionPattern
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.databinding.ActivityMainBinding
import com.example.financetracker.repository.TransactionRepository
import com.example.financetracker.ui.dialogs.TransactionDetailsDialog
import com.example.financetracker.ui.screens.StatisticsScreen
import com.example.financetracker.utils.CategoryUtils
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.utils.MessageExtractor
import com.example.financetracker.viewmodel.StatisticsViewModel
import com.example.financetracker.viewmodel.StatisticsViewModelFactory
import com.example.financetracker.viewmodel.TransactionStatistics
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class MainActivity : BaseActivity(), TransactionDetailsDialog.TransactionDetailsListener {

    override fun getLayoutResourceId(): Int = R.layout.activity_main

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
    private var isDialogShowing = false

    private val transactionRepository: TransactionRepository by lazy {
        val database = TransactionDatabase.getDatabase(applicationContext)
        TransactionRepository(database.transactionDao(), applicationContext)
    }

    private val transactionViewModel: TransactionViewModel by viewModels {
        val database = TransactionDatabase.getDatabase(this)
        TransactionViewModel.Factory(database, application)
    }

    private val statisticsViewModel: StatisticsViewModel by viewModels {
        StatisticsViewModelFactory(
            transactionRepository,
            TransactionDatabase.getDatabase(applicationContext), // Pass DB instance
            application // Pass Application instance
        )
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

    private fun updateGuestModeBanner() {
        val guestModeBanner = binding.guestModeBanner // Assuming you use view binding
        // Use the check that reads the shared preference
        val isGuestMode = GuestUserManager.isGuestMode(this)
        Log.d(TAG, "Updating guest banner. isGuestMode (from context): $isGuestMode") // Add logging
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

        // Set the current user's name in the navigation drawer
        updateNavHeader()


        // Initialize views
        /*statisticsCard = findViewById(R.id.statisticsCard)*/
        categoryStatsContainer = findViewById(R.id.categoryStatsContainer)
        expandCollapseIcon = findViewById(R.id.expandCollapseIcon)

        // Set initial collapsed height
        /*setCollapsedHeight()*/

        // Setup click listener for statistics card
        /*statisticsCard.setOnClickListener {
            toggleStatisticsExpansion()
        }*/

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
        // Use the correct ID from your activity_main.xml
        binding.statisticsComposeView.apply { // Changed from composeViewStatistics
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // Pass the correctly initialized statisticsViewModel
                StatisticsScreen(viewModel = statisticsViewModel)
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

            // Your existing SMS test implementation

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

    private fun handleIntentExtras(intent: Intent?) {
        intent ?: return // Do nothing if intent is null

        Log.d(TAG, "handleIntentExtras: Checking intent for SHOW_TRANSACTION_DIALOG flag.")
        if (intent.getBooleanExtra("SHOW_TRANSACTION_DIALOG", false)) {
            Log.d(TAG, "handleIntentExtras: SHOW_TRANSACTION_DIALOG flag found.")
            val message = intent.getStringExtra("TRANSACTION_MESSAGE")
            val amount = intent.getDoubleExtra("TRANSACTION_AMOUNT", 0.0)
            // Get date (check if it's the sentinel 0L or a real date)
            val dateLong = intent.getLongExtra("TRANSACTION_DATE", 0L)
            val finalDate = if (dateLong == 0L) System.currentTimeMillis() else dateLong // Use current time if extractor defaulted

            val merchant = intent.getStringExtra("TRANSACTION_MERCHANT") ?: "Unknown Merchant"
            val description = intent.getStringExtra("TRANSACTION_DESCRIPTION") ?: ""
            // Note: We might not have the actual Room Transaction ID here yet,
            // as the service might have just inserted it. We create a temporary
            // object or pass individual details to the dialog/listener.
            // Let's recreate a temporary Transaction object for the dialog context.
            val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext) // Get user ID here

            // Find the actual transaction ID passed from the service if available
            val transactionIdFromIntent = intent.getLongExtra("TRANSACTION_ID", 0L)

            if (transactionIdFromIntent == 0L) {
                Log.e(TAG, "handleIntentExtras: TRANSACTION_ID is missing in intent. Cannot proceed reliably.")
                Toast.makeText(this, "Error: Missing transaction reference.", Toast.LENGTH_SHORT).show()
                // Clear the flag to prevent re-triggering
                intent.removeExtra("SHOW_TRANSACTION_DIALOG")
                return
            }

            // Create a transient Transaction object for the dialog context
            // IMPORTANT: Use the ID passed from the intent
            val transactionForDialog = Transaction(
                id = transactionIdFromIntent, // USE THE ID FROM INTENT
                name = merchant, // Use extracted name/merchant
                amount = amount,
                date = finalDate, // Use potentially corrected date
                category = "Uncategorized", // It's uncategorized by definition here
                merchant = merchant,
                description = description,
                userId = userId, // Set the correct user ID
                documentId = null // Firestore ID likely not known yet
            )

            Log.d(TAG, "handleIntentExtras: Showing dialog for transaction ID: ${transactionForDialog.id}")
            showTransactionDetailsDialog(transactionForDialog, message) // Pass the transient object

            // Optional: Prevent the dialog from showing again if the user rotates the screen
            // or if onNewIntent is called again for the same logical event.
            // Clear the flag in the intent ONLY after showing the dialog.
            intent.removeExtra("SHOW_TRANSACTION_DIALOG")

        } else {
            Log.d(TAG, "handleIntentExtras: SHOW_TRANSACTION_DIALOG flag not found or false.")
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
                    transactionViewModel.clearTransactions()
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

    private fun showTransactionDetailsDialog(transaction: Transaction, messageBody: String?) {
        // Store details needed by onDetailsEntered
        currentTransaction = transaction
        currentMessageBody = messageBody ?: "" // Store empty if null

        Log.d(TAG, "Showing details dialog for Merchant: '${transaction.merchant}', Amount: ${transaction.amount}")

        val dialog = TransactionDetailsDialog.newInstance(
            // Pass original merchant name from the transaction object
            originalMerchant = transaction.merchant.ifBlank { "Unknown Merchant" },
            // Pass messageBody if needed by dialog/pattern saving, otherwise it can be removed
            // messageBody = messageBody ?: ""
        )
        // Prevent accidental dismissal before handling result? (Optional)
        // dialog.isCancelable = false
        dialog.show(supportFragmentManager, "TransactionDetailsDialog")
    }

    override fun onDetailsEntered(merchant: String, category: String, saveAsPattern: Boolean) {
        // --- Keep your existing logic to update the *currentTransaction* ---
        lifecycleScope.launch {
            try {
                // Ensure currentTransaction is not null before proceeding
                val transactionToUpdate = currentTransaction ?: run {
                    Log.e(TAG, "onDetailsEntered: currentTransaction is null, cannot update.")
                    Toast.makeText(this@MainActivity, "Error: Transaction data missing.", Toast.LENGTH_SHORT).show()
                    isDialogShowing = false // Reset flag on error
                    return@launch
                }

                val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)

                // Save merchant-category mapping first (using ViewModel is better)
                transactionViewModel.saveMerchant(merchant, category, userId) // Assuming ViewModel has this

                // Start with original date


                // Update transaction details
                transactionToUpdate.apply {
                    this.name = merchant // Update name field as well if needed
                    this.merchant = merchant
                    this.category = category
                    this.userId = userId
                }

                Log.d(TAG, "onDetailsEntered: Updating transaction ID ${transactionToUpdate.id} with Category: $category, Merchant: $merchant")

                // Update in Room and Firestore via ViewModel
                transactionViewModel.updateTransaction(transactionToUpdate)

                Toast.makeText(this@MainActivity, "Transaction updated successfully", Toast.LENGTH_SHORT).show()

                if (saveAsPattern) {
                    currentMessageBody?.let { messageBody ->
                        // Only save pattern if message body was available (likely from notification)
                        if (messageBody.isNotEmpty()) {
                            saveTransactionPattern(messageBody, merchant, category)
                        } else {
                            Log.w(TAG, "Cannot save pattern, original SMS body not available.")
                        }
                    }
                }

                // --- Logic to check for the *next* uncategorized item ---
            // Trigger the check again

            } catch (e: Exception) {
                Log.e(TAG, "Error updating transaction in onDetailsEntered", e)
                Toast.makeText(this@MainActivity, "Error updating transaction: ${e.message}", Toast.LENGTH_SHORT).show()
                isDialogShowing = false // Reset flag on error
            } finally {
                // Clear current transaction references after processing
                currentTransaction = null
                currentMessageBody = null
            }
        }
    }

    private fun updateTransactionInFirestore(transaction: Transaction) {
        val userId = auth.currentUser?.uid ?: return

        // Check if this transaction has already been synced recently to prevent duplicates
        if (transaction.documentId?.isNotEmpty() == true) {
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
        if (docId != null) {
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
            Log.d(TAG, "Current Date and Time (Local - yyyy-MM-DD HH:MM:SS formatted): $currentDateTime")
            Log.d(TAG, "Current User's Login: ${currentUser.email}")

            // Update subtitle with user info
            supportActionBar?.subtitle = "Logged in as: ${currentUser.email}"

            // *** ADD THIS LINE ***
            updateGuestModeBanner() // Update banner visibility when user is confirmed logged in

        } else {
            // User not logged in - BaseActivity should handle this

            // *** ADD THIS LINE ***
            updateGuestModeBanner() // Also update banner visibility when user is confirmed logged out/guest
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