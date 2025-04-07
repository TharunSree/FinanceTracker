package com.example.financetracker.viewmodel

import android.app.Application
import android.content.Context
import android.provider.Telephony
import android.database.Cursor
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.financetracker.SmsBroadcastReceiver
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Merchant
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.model.TransactionDetails
import com.example.financetracker.repository.TransactionRepository
import com.example.financetracker.utils.GeminiMessageExtractor
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.utils.MessageExtractor
import com.example.financetracker.utils.SenderListManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.NonCancellable.isCancelled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

data class CategoryStatistics(
    val maxExpense: Double = 0.0,
    val totalExpense: Double = 0.0,
    val transactionCount: Int = 0
)

data class TransactionStatistics(
    val maxExpense: Double = 0.0,
    val minExpense: Double = 0.0,
    val totalExpense: Double = 0.0,
    val categoryStats: Map<String, CategoryStatistics> = emptyMap()
)


class TransactionViewModel(
    private val database: TransactionDatabase,
    application: Application
) : AndroidViewModel(application) {

    private val transactionDao = database.transactionDao()
    private val categoryDao = database.categoryDao()
    private val merchantDao = database.merchantDao()
    private val TAG = "TransactionViewModel"
    private val repository: TransactionRepository =
        TransactionRepository(transactionDao, application)

    private val _scanProgress = MutableLiveData<String>() // For detailed progress updates
    val scanProgress: LiveData<String> = _scanProgress

    private val _scanStatus = MutableLiveData<String?>()
    val scanStatus: MutableLiveData<String?> = _scanStatus

    // Loading state
    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _scanProgressValues = MutableLiveData<Pair<Int, Int>?>(null) // Pair(current, total), nullable to reset
    val scanProgressValues: LiveData<Pair<Int, Int>?> = _scanProgressValues

    // Inside TransactionViewModel class...


    private val defaultCategories = listOf( // Make sure this list is populated
        "Food & Dining", "Groceries", "Transportation", "Utilities", "Rent/Mortgage",
        "Shopping", "Entertainment", "Health & Wellness", "Travel", "Salary",
        "Freelance", "Investment", "Personal Care", "Education", "Gifts & Donations",
        "Subscriptions", "Miscellaneous", "Uncategorized"
    )

    enum class ScanPeriod { LAST_MONTH, ALL_TIME }

    val categoryNames: StateFlow<List<String>> =
        flow {
            val id = getCurrentUserId()
            Log.d(TAG, "categoryNames flow: Emitting User ID = $id") // Log User ID
            emit(id)
        }
            .flatMapLatest { userId ->
                Log.d(TAG, "categoryNames flow: flatMapLatest received userId = $userId")
                if (userId == null || GuestUserManager.isGuestMode(userId)) {
                    Log.d(TAG, "categoryNames flow: User is null or guest, emitting empty Category list.")
                    flow { emit(emptyList<com.example.financetracker.database.entity.Category>()) }
                } else {
                    Log.d(TAG, "categoryNames flow: Calling DAO getAllCategoriesForUser for userId = $userId")
                    // Ensure categoryDao.getAllCategoriesForUser(userId) returns Flow<List<Category>>
                    categoryDao.getAllCategories(userId) // RENAMED from previous example, ensure it matches your DAO
                }
            }
            .map { userCategoryList ->
                // This map block might not run if the flow above emits nothing or errors before this
                Log.d(TAG, "categoryNames flow: .map received DAO list (size ${userCategoryList.size}) = ${userCategoryList.map { it.name }}")
                val userCategoryNames = userCategoryList.map { it.name }

                Log.d(TAG, "categoryNames flow: Combining user names [$userCategoryNames] with defaults [$defaultCategories]")
                val combinedNames = (userCategoryNames + defaultCategories)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sorted()
                Log.d(TAG, "categoryNames flow: Final combined list (size ${combinedNames.size}) = $combinedNames")
                combinedNames
            }
            .catch { e ->
                Log.e(TAG, "categoryNames flow: ERROR caught in flow", e)
                // Emit defaults on error
                emit(defaultCategories.sorted())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = defaultCategories.sorted().also {
                    Log.d(TAG, "categoryNames flow: Setting initialValue (size ${it.size}) = $it") // Log initial value
                }
            )

    private fun getCurrentUserId(): String? {
        val auth = FirebaseAuth.getInstance()
        return auth.currentUser?.uid
        // return auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(getApplication()) // If guest needed
    }

    // Error message
    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage

    init {
        viewModelScope.launch {
            try {
                _loading.value = true
                loadInitialData()
                repository.syncPendingTransactions()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to initialize: ${e.message}"
                Log.e(TAG, "Initialization error", e)
            } finally {
                _loading.value = false
            }
        }
    }

    private val firestore = FirebaseFirestore.getInstance()
    private var transactionListener: ListenerRegistration? = null
    private val auth = FirebaseAuth.getInstance()

    private var currentFilterState = FilterState.ALL
    private var currentCategory: String? = null
    private val _filteredTransaction = MutableLiveData<List<Transaction>>()
    val filteredTransaction: LiveData<List<Transaction>> = _filteredTransaction

    enum class FilterState {
        ALL, TODAY, WEEK, MONTH
    }

    private val _categories = MutableLiveData<List<String>>()
    val categories: LiveData<List<String>> = _categories

    private suspend fun loadInitialData() {
        try {
            updateCategories()
            loadAllTransactions()
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error loading initial data", e)
            _errorMessage.postValue("Error loading initial data: ${e.message}")
        }
    }


    fun updateCategoryColor(categoryId: Int, colorHex: String?) {
        viewModelScope.launch(Dispatchers.IO) { // Use IO dispatcher for DB operations
            try {
                categoryDao.updateCategoryColor(categoryId, colorHex) // Call DAO method
                Log.d(TAG, "Updated color for category $categoryId to $colorHex")
                // The Flow observed in CategoriesFragment should automatically update the list UI.
                // The Flow observed in StatisticsViewModel should automatically update the color map.
            } catch (e: Exception) {
                Log.e(TAG, "Error updating category color for ID $categoryId", e)
                // Post error message to be observed by UI
                _errorMessage.postValue("Failed to update category color.")
            }
        }
    }

    fun migrateGuestTransactions(guestUserId: String, loggedInUserId: String) = viewModelScope.launch {
        Log.d(TAG, "Attempting to migrate transactions from guest '$guestUserId' to user '$loggedInUserId'")
        try {
            // It's crucial this runs on an IO thread
            val updatedRows = withContext(Dispatchers.IO) {
                transactionDao.updateUserIdForGuest(guestUserId, loggedInUserId)
            }
            Log.i(TAG, "Migrated $updatedRows guest transactions to user '$loggedInUserId'")
            // Optional: Trigger a refresh of the transaction list if needed,
            // although navigating to MainActivity usually handles this.
            // loadAllTransactions() // Or specific filter based on prior state
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating guest transactions", e)
            _errorMessage.postValue("Error associating guest data: ${e.message}")
        }
        // Note: Loading state (_loading) might need management if this takes significant time.
    }

    private suspend fun updateCategories() {
        val distinctCategories = database.transactionDao().getAllCategories()
        _categories.postValue(distinctCategories)
    }

    fun refreshCategories() = viewModelScope.launch {
        updateCategories()
    }

    // Get current user ID
    val userId: String?
        get() = auth.currentUser?.uid

    // Using StateFlow for filtered transactions
    private val _filteredTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val _transactionStatistics = MutableLiveData<TransactionStatistics>()
    val transactionStatistics: LiveData<TransactionStatistics> = _transactionStatistics
    val filteredTransactions: StateFlow<List<Transaction>> = _filteredTransactions

    // Using Flow for all transactions
    val transactions = transactionDao.getAllTransactions().asLiveData()

    init {
        updateStatistics()
    }

    fun updateStatistics() {
        viewModelScope.launch {
            try {
                val allTransactions = transactions.value ?: return@launch

                // Skip update if there are no transactions
                if (allTransactions.isEmpty()) {
                    _transactionStatistics.value = TransactionStatistics(
                        maxExpense = 0.0,
                        minExpense = 0.0,
                        totalExpense = 0.0,
                        categoryStats = emptyMap()
                    )
                    return@launch
                }

                val categoryMap = mutableMapOf<String, MutableList<Transaction>>()
                var maxExpense = Double.MIN_VALUE
                var minExpense = Double.MAX_VALUE
                var totalExpense = 0.0

                allTransactions.forEach { transaction ->
                    // Skip invalid transactions
                    if (transaction.category.isNullOrBlank()) return@forEach

                    categoryMap.getOrPut(transaction.category) { mutableListOf() }.add(transaction)
                    maxExpense = maxOf(maxExpense, transaction.amount)
                    minExpense = minOf(minExpense, transaction.amount)
                    totalExpense += transaction.amount
                }

                // Handle edge case if filtering removed all transactions
                if (categoryMap.isEmpty()) {
                    _transactionStatistics.value = TransactionStatistics(
                        maxExpense = 0.0,
                        minExpense = 0.0,
                        totalExpense = 0.0,
                        categoryStats = emptyMap()
                    )
                    return@launch
                }

                val categoryStats = categoryMap.mapValues { (_, transactions) ->
                    CategoryStatistics(
                        maxExpense = transactions.maxOfOrNull { it.amount } ?: 0.0,
                        totalExpense = transactions.sumOf { it.amount },
                        transactionCount = transactions.size
                    )
                }

                _transactionStatistics.value = TransactionStatistics(
                    maxExpense = if (maxExpense != Double.MIN_VALUE) maxExpense else 0.0,
                    minExpense = if (minExpense != Double.MAX_VALUE) minExpense else 0.0,
                    totalExpense = totalExpense,
                    categoryStats = categoryStats
                )
            } catch (e: Exception) {
                // Handle any errors
                Log.e("TransactionViewModel", "Error updating statistics", e)
                _errorMessage.postValue("Error updating statistics: ${e.message}")
            }
        }
    }

    // Add a flag to track transaction source
    private var isLocalUpdate = false

    // Method to add a transaction
    suspend fun addTransaction(transaction: Transaction) {
        // Use withContext(Dispatchers.IO) if calling directly without launch
        try {
            // Ensure userId is set
            if (transaction.userId.isBlank()) {
                transaction.userId = getCurrentUserId() ?: throw IllegalStateException("Cannot save transaction without User ID")
            }
            // Insert into Room, get ID
            val insertedId = transactionDao.insertTransactionAndGetId(transaction)
            transaction.id = insertedId
            Log.d(TAG, "Inserted transaction locally with ID: $insertedId")

            // Sync to Firestore if needed
            if (!GuestUserManager.isGuestMode(transaction.userId)) {
                syncTransactionToFirestore(transaction) // This needs to handle setting documentId and updating Room again
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in addTransaction function", e)
            _errorMessage.postValue("Failed to add transaction: ${e.message}") // Post error for UI
            // Re-throw if the caller needs to know about the failure
            // throw e
        }
    }

    // Add this helper method to check for duplicates
    private suspend fun checkForDuplicate(transaction: Transaction): Transaction? {
        val timeWindow = 60000L // 1 minute window to check for duplicates
        val startTime = transaction.date - timeWindow
        val endTime = transaction.date + timeWindow

        return transactionDao.getTransactionsByDateRange(startTime, endTime)
            .firstOrNull { existingTransaction ->
                existingTransaction.amount == transaction.amount &&
                        existingTransaction.name == transaction.name &&
                        existingTransaction.merchant == transaction.merchant &&
                        existingTransaction.category == transaction.category
            }
    }

    // Inside class TransactionViewModel(...) { ...

    // Method to update only specific fields after fetching the original transaction
    fun updateTransactionCategoryAndMerchant(
        transactionId: Long,
        newName: String?,
        newCategory: String,
        newMerchant: String? // Merchant can be nullable/empty string
    ) = viewModelScope.launch {
        Log.d(TAG, "Attempting partial update for ID: $transactionId with Cat: $newCategory, Merch: $newMerchant")
        try {
            _loading.value = true // Indicate loading started

            // 1. Fetch the original transaction from the database
            // Ensure your DAO's getTransactionById accepts a Long ID
            val originalTransaction = database.transactionDao().getTransactionById(transactionId.toInt())

            if (originalTransaction == null) {
                // Handle case where the transaction might have been deleted elsewhere
                Log.e(TAG, "Cannot update: Transaction not found for ID $transactionId")
                _errorMessage.postValue("Transaction not found, cannot update.")
                // Set loading false here as we are returning early
                _loading.value = false
                return@launch // Exit the coroutine
            }

            // 2. Create the updated transaction object
            // Use the copy() method of the data class to preserve other fields
            val updatedTransaction = originalTransaction.copy(
                name = newName ?: "",
                category = newCategory,
                merchant = newMerchant ?: "" // Use empty string if null was passed
            )

            Log.d(TAG,"Partially updated object prepared: $updatedTransaction")

            // 3. Call the existing full update method
            // This existing method should handle updating Room and Firestore (if applicable)
            updateTransaction(updatedTransaction) // Reuse existing update logic

            // Note: The finally block within the reused updateTransaction method
            // should ideally handle setting _loading.value = false.
            // If not, you might need to manage it here, but it's better if
            // updateTransaction consistently handles its own loading state end.

        } catch (e: Exception) {
            Log.e(TAG, "Error during partial update for transaction $transactionId", e)
            _errorMessage.postValue("Error updating transaction details: ${e.message}")
            // Ensure loading is false even if an exception occurs before calling updateTransaction's finally
            _loading.value = false
        }
        // No finally block needed here if updateTransaction reliably handles it.
    }

    private fun isFinancialMessageScan(sender: String, message: String, allowedSenders: Set<String>): Boolean {
        val smsReceiver = SmsBroadcastReceiver() // Need instance or make methods static/object
        // TODO: Call the filtering logic, potentially adapting it for scanning needs
        // return smsReceiver.isFinancialMessage(sender, message, allowedSenders) // Need to adapt receiver logic access
        return true // Placeholder
    }

    fun scanPastSmsForTransactions(context: Context, period: ScanPeriod) {
        val userId = getCurrentUserId() ?: run {
            _errorMessage.postValue("User ID not found. Cannot perform scan.")
            _scanStatus.postValue("Error: User not found")
            return
        }

        viewModelScope.launch {
            _loading.postValue(true)
            _scanStatus.postValue("Starting SMS scan...")
            _scanProgressValues.postValue(null) // Reset progress

            var processedCount = 0
            var totalMessages = 0
            var addedCount = 0
            var duplicateCount = 0
            var financialMsgCount = 0
            var failedExtractionCount = 0
            var permissionError = false
            var cursorError = false
            var otherError: String? = null

            withContext(Dispatchers.IO) { // Perform DB and ContentResolver access off main thread
                var cursor: Cursor? = null
                var countCursor: Cursor? = null
                try {
                    // --- Time Range Calculation ---
                    val calendar = Calendar.getInstance()
                    val endTime = calendar.timeInMillis
                    when (period) {
                        ScanPeriod.LAST_MONTH -> calendar.add(Calendar.MONTH, -1)
                        ScanPeriod.ALL_TIME -> calendar.timeInMillis = 0
                    }
                    val startTime = calendar.timeInMillis
                    Log.d(TAG, "[SMS Scan] Scanning from ${Date(startTime)} to ${Date(endTime)}")

                    // --- Setup for Query ---
                    val appContext = getApplication<Application>().applicationContext // Use app context
                    val selection = "${Telephony.Sms.Inbox.DATE} BETWEEN ? AND ?"
                    val selectionArgs = arrayOf(startTime.toString(), endTime.toString())

                    // --- Get Total Count ---
                    countCursor = appContext.contentResolver.query(
                        Telephony.Sms.Inbox.CONTENT_URI,
                        arrayOf("COUNT(*)"),
                        selection,
                        selectionArgs,
                        null
                    )
                    countCursor?.use {
                        if (it.moveToFirst()) {
                            totalMessages = it.getInt(0)
                            Log.d(TAG, "[SMS Scan] Found $totalMessages total messages in period.")
                            withContext(Dispatchers.Main) { // Post updates to Main thread
                                _scanProgressValues.postValue(Pair(0, totalMessages))
                                _scanStatus.postValue("Preparing to scan $totalMessages messages...")
                            }
                        }
                    } ?: Log.w(TAG, "[SMS Scan] Count query cursor was null.")
                    countCursor?.close() // Close count cursor promptly

                    if (totalMessages == 0) {
                        _scanStatus.postValue("No messages found in the selected period.")
                        return@withContext
                    }

                    // --- Main Processing Query ---
                    val allowedSenders = SenderListManager.getActiveSenders(appContext)
                    val extractor = GeminiMessageExtractor(appContext) // Use Gemini Extractor
                    val projection = arrayOf(
                        Telephony.Sms.Inbox.ADDRESS,
                        Telephony.Sms.Inbox.BODY,
                        Telephony.Sms.Inbox.DATE,
                        Telephony.Sms.Inbox._ID // Optional ID for logging
                    )

                    cursor = appContext.contentResolver.query(
                        Telephony.Sms.Inbox.CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        Telephony.Sms.Inbox.DATE + " DESC"
                    )

                    if (cursor == null) {
                        cursorError = true
                        Log.e(TAG, "[SMS Scan] Main query cursor was null.")
                        // _scanStatus will be updated in finally block
                        return@withContext
                    }

                    cursor.use { // Safely handle main cursor
                        val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS)
                        val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY)
                        val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE)

                        while (it.moveToNext() && !NonCancellable.isCancelled) { // Check coroutine cancellation
                            processedCount++

                            // Post progress update periodically (on Main thread)
                            if (processedCount % 50 == 0 || processedCount == totalMessages) {
                                val currentProgress = processedCount
                                withContext(Dispatchers.Main) {
                                    _scanProgressValues.postValue(Pair(currentProgress, totalMessages))
                                    _scanStatus.postValue("Scanning: $currentProgress / $totalMessages")
                                }
                            }

                            val sender = it.getString(addressIndex)
                            val body = it.getString(bodyIndex)
                            val smsDate = it.getLong(dateIndex)

                            if (sender != null && body != null) {
                                // *** Use the new helper function to check sender and message content ***
                                if (shouldProcessScannedMessage(sender, body, allowedSenders)) {
                                    financialMsgCount++
                                    val (details, type) = extractor.extractTransactionDetails(body, sender)

                                    if (details != null) {
                                        val isDuplicate = checkForDuplicateScan(details, smsDate, userId)
                                        if (!isDuplicate) {
                                            // Look up category based on merchant before creating transaction
                                            val knownCategory = details.merchant.takeIf { m -> m.isNotBlank() && m != "Unknown Merchant"}
                                                ?.let { mName -> merchantDao.getCategoryForMerchant(mName, userId) } // Use merchantDao
                                                ?: details.category.ifBlank { "Uncategorized" } // Fallback to Gemini's category

                                            val transaction = Transaction(
                                                id = 0,
                                                name = details.name,
                                                amount = details.amount,
                                                date = smsDate, // Use SMS timestamp
                                                category = knownCategory,
                                                merchant = details.merchant,
                                                description = details.description,
                                                userId = userId,
                                                documentId = null
                                            )
                                            try {
                                                // Call addTransaction (handles Room & potentially Firestore)
                                                // Already runs within viewModelScope's IO context
                                                addTransaction(transaction)
                                                addedCount++
                                            } catch(addEx: Exception) {
                                                Log.e(TAG, "[SMS Scan] Error adding transaction during scan", addEx)
                                                failedExtractionCount++
                                            }
                                        } else { duplicateCount++ }
                                    } else { failedExtractionCount++ }
                                }
                            }
                        } // end while
                    } // end cursor.use

                } catch (e: SecurityException) {
                    permissionError = true
                    Log.e(TAG, "[SMS Scan] READ_SMS permission error.", e)
                    otherError = "Permission Error"
                } catch (e: Exception) {
                    otherError = "Error: ${e.localizedMessage}"
                    Log.e(TAG, "[SMS Scan] Error during scan process", e)
                } finally {
                    cursor?.close()
                    countCursor?.close() // Ensure count cursor is also closed here
                }
            } // end withContext(IO)

            // Final UI updates on Main thread
            withContext(Dispatchers.Main) {
                _loading.postValue(false)
                val finalStatus = when {
                    permissionError -> "Permission Error"
                    cursorError -> "Error: Query Failed"
                    otherError != null -> otherError
                    totalMessages == 0 && !permissionError -> "No messages found in period." // Handle 0 messages case
                    else -> "Scan Complete! Processed: $processedCount, Financial: $financialMsgCount, Added: $addedCount, Duplicates: $duplicateCount, Failed Extract: $failedExtractionCount"
                }
                _scanStatus.postValue(finalStatus)

                if (totalMessages > 0 && !permissionError && !cursorError && otherError == null) {
                    _scanProgressValues.postValue(Pair(processedCount, totalMessages))
                } else if (totalMessages == 0 && !permissionError && !cursorError && otherError == null) {
                    _scanProgressValues.postValue(Pair(0, 0))
                }

                Log.i(TAG, "[SMS Scan] Final Status: $finalStatus")
                launch { // Coroutine for delayed reset
                    kotlinx.coroutines.delay(5000)
                    if (_scanStatus.value == finalStatus) {
                        _scanStatus.postValue(null)
                        _scanProgressValues.postValue(null)
                    }
                }
            }
        } // end viewModelScope.launch
    } // end scanPastSmsForTransactions

    private fun shouldProcessScannedMessage(sender: String, message: String, allowedSenders: Set<String>): Boolean {
        // 1. Check Sender using SenderListManager logic
        val upperSender = sender.uppercase()
        val senderAllowed = allowedSenders.any { knownSender -> upperSender.contains(knownSender) }
        if (!senderAllowed) {
            // Log.v(TAG, "[Scan Filter] Sender '$sender' not in allowed list.") // Verbose log
            return false
        }

        // 2. Check for essential financial keywords (similar to SmsBroadcastReceiver)
        // Consider if Gemini should handle this entirely, but a pre-filter can save API calls.
        val hasAmount = currencyPatterns.values.flatten().any { it.containsMatchIn(message) }
        if (!hasAmount) {
            // Log.v(TAG, "[Scan Filter] Message from '$sender' does not contain amount pattern.")
            return false
        }

        val isDebit = isDebitTransaction(message) // Use helper function
        if (!isDebit) {
            // Log.v(TAG, "[Scan Filter] Message from '$sender' does not contain debit keyword.")
            // If you also want to process credits during scan, remove or modify this check
            return false
        }

        // 3. Optional: Add checks to exclude common non-transactional messages (e.g., OTP) *before* calling Gemini
        if (message.contains("OTP", ignoreCase = true) || message.contains("Verification code", ignoreCase = true)) {
            Log.v(TAG, "[Scan Filter] Message from '$sender' looks like OTP, skipping.")
            return false
        }

        Log.d(TAG, "[Scan Filter] Message from '$sender' passed pre-Gemini checks.")
        return true // Passed all checks, proceed to Gemini extraction
    }

    // --- Helper functions used by shouldProcessScannedMessage (copied from SmsBroadcastReceiver context) ---

    // Currency patterns (consider moving to a shared utility file)
    private val currencyPatterns = mapOf(
        "INR" to listOf(
            Regex("""(?:Rs\.?|INR|₹)\s*([\d,]+\.?\d*)"""),
            Regex("""(?:INR|Rs\.?)\s*([\d,]+\.?\d*)"""),
            Regex("""debited by\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE),
            Regex("""Payment of Rs\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE),
            Regex("""debited for INR\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
        ),
        // Add other currencies if needed
    )

    // Debit keywords (consider moving to a shared utility file)
    private fun isDebitTransaction(message: String): Boolean {
        val debitKeywords = setOf( // Use Set for efficient lookup
            "debited", "debit", "spent", "paid", "withdrawn", "purchase",
            "payment", "transferred", "sent", "dr"
            // Consider adding: "txn", "transaction" combined with amount?
        )
        return debitKeywords.any { keyword -> message.contains(keyword, ignoreCase = true) }
    }

    private suspend fun checkForDuplicateScan(details: TransactionDetails, smsTimestamp: Long, userId: String): Boolean {
        val timeWindowMillis = 120000L // +/- 2 minutes around SMS timestamp
        val startTime = smsTimestamp - timeWindowMillis
        val endTime = smsTimestamp + timeWindowMillis
        // ... (rest of the duplicate check logic using referenceNumber, merchant, name as before) ...
        Log.d(TAG, "[Duplicate Check Scan] Checking for user $userId, amount ${details.amount}, time ${Date(startTime)} - ${Date(endTime)}")
        val potentialDuplicates = try { transactionDao.findPotentialDuplicates(userId, details.amount, startTime, endTime) } catch (e: Exception) { emptyList() }
        if (potentialDuplicates.isEmpty()) return false
        Log.d(TAG, "[Duplicate Check Scan] Found ${potentialDuplicates.size} potential duplicates.")
        for (existing in potentialDuplicates) {
            if (!details.referenceNumber.isNullOrBlank() && !existing.documentId.isNullOrBlank() && details.referenceNumber == existing.documentId) { Log.w(TAG, "[Duplicate Check Scan] MATCH (Ref == DocId). IS DUPLICATE."); return true } // Check Ref == DocId as proxy
            val newMerchant = details.merchant.ifBlank { details.name }
            if (existing.merchant.equals(newMerchant, ignoreCase = true) || existing.name.equals(newMerchant, ignoreCase = true)) { Log.w(TAG, "[Duplicate Check Scan] PROBABLE MATCH (Merchant/Name). IS DUPLICATE."); return true }
        }
        Log.d(TAG, "[Duplicate Check Scan] No strict match found among potentials.")
        return false
    }


    // Reminder: Ensure your TransactionDao interface has this method declared:
    /*
    @Dao
    interface TransactionDao {
        // ... other methods ...

        @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
        suspend fun getTransactionById(id: Long): Transaction? // Accepts Long
    }
    */

    // Reminder: Ensure your existing updateTransaction handles loading state
    /*
    fun updateTransaction(transaction: Transaction) = viewModelScope.launch {
        try {
            _loading.value = true // Make sure it sets loading true
            // ... Room update logic ...
            // ... Firestore update logic (using createFirestoreDocument/updateFirestoreTransaction) ...
            loadAllTransactions() // Or trigger appropriate refresh
        } catch (e: Exception) {
            Log.e(TAG, "Error updating transaction", e)
            _errorMessage.postValue("Error updating transaction: ${e.message}")
        } finally {
            _loading.value = false // ** Crucial: Ensure finally block sets loading false **
        }
    }
    */


// ... rest of your TransactionViewModel class ...
// } // End of TransactionViewModel class

    // Method to update a transaction
    fun updateTransaction(transaction: Transaction) = viewModelScope.launch {
        try {
            _loading.value = true
            // Update in Room
            database.transactionDao().updateTransaction(transaction)

            // Update in Firestore if not guest mode
            val userId = transaction.userId
            if (!GuestUserManager.isGuestMode(userId)) {
                val documentId = transaction.documentId.takeIf { it?.isNotEmpty() == true  } ?:
                createFirestoreDocument(transaction)

                transaction.documentId = documentId
                updateFirestoreTransaction(transaction)
            }

            // Reload transactions to refresh the UI
            loadAllTransactions()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating transaction", e)
            _errorMessage.postValue("Error updating transaction: ${e.message}")
        } finally {
            _loading.value = false
        }
    }

    private suspend fun createFirestoreDocument(transaction: Transaction): String {
        return try {
            val userId = transaction.userId ?: throw IllegalStateException("User ID is required")

            val docRef = FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("transactions")
                .document()

            val transactionMap = hashMapOf(
                "id" to transaction.id,
                "name" to transaction.name,
                "amount" to transaction.amount,
                "date" to transaction.date,
                "category" to transaction.category,
                "merchant" to transaction.merchant,
                "description" to transaction.description,
                "userId" to userId,
                "documentId" to docRef.id
            )

            docRef.set(transactionMap).await()
            docRef.id
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Firestore document", e)
            _errorMessage.postValue("Error creating Firestore document: ${e.message}")
            throw e
        }
    }

    private suspend fun updateFirestoreTransaction(transaction: Transaction) {
        try {
            val userId = transaction.userId ?: return
            val documentId = transaction.documentId ?: return

            val transactionMap = hashMapOf(
                "id" to transaction.id,
                "name" to transaction.name,
                "amount" to transaction.amount,
                "date" to transaction.date,
                "category" to transaction.category,
                "merchant" to transaction.merchant,
                "description" to transaction.description,
                "userId" to userId,
                "documentId" to documentId
            )

            FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("transactions")
                .document(documentId)
                .set(transactionMap)
                .await()

            Log.d(TAG, "Transaction updated in Firestore: $documentId")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating transaction in Firestore", e)
            _errorMessage.postValue("Error updating transaction in Firestore: ${e.message}")
            throw e
        }
    }

    fun syncWithFirestore() = viewModelScope.launch {
        try {
            _loading.value = true
            repository.loadFromFirestore()
            repository.syncPendingTransactions()
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing with Firestore", e)
            _errorMessage.postValue("Error syncing with Firestore: ${e.message}")
        } finally {
            _loading.value = false
        }
    }

    private fun getUserId(context: Context): String {
        return auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(context)
    }

    // Replace the existing clearTransactions method with this improved version
    fun clearTransactions() = viewModelScope.launch {
        try {
            _loading.value = true
            Log.d("TransactionViewModel", "Starting transaction clearing process")

            // Make sure to stop listening first
            stopListeningToTransactions()

            // Clear from local database with a blocking operation
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                transactionDao.clearTransactions()

                // Double-check that transactions are cleared
                val count = transactionDao.getAllTransactions().first().size
                if (count > 0) {
                    Log.w("TransactionViewModel", "First clearing attempt left $count transactions, trying again")
                    // Try one more time
                    transactionDao.clearTransactions()
                }
            }

            // Ensure the filtered transactions are also cleared immediately
            _filteredTransactions.value = emptyList()

            // Reset statistics
            _transactionStatistics.postValue(
                TransactionStatistics(
                    maxExpense = 0.0,
                    minExpense = 0.0,
                    totalExpense = 0.0,
                    categoryStats = emptyMap()
                )
            )

            Log.d("TransactionViewModel", "All transactions cleared from local database")

            // Force the LiveData to emit an empty list
            val emptyTransactionList = emptyList<Transaction>()
            if (transactions.value?.isNotEmpty() == true) {
                // This is a workaround to force the LiveData to update
                // We need to trigger a change in the database that will cause the Flow to emit
                val dummyTransaction = Transaction(
                    id = -999,
                    name = "DUMMY_FOR_CLEARING",
                    amount = 0.0,
                    date = System.currentTimeMillis(),
                    category = "",
                    merchant = "",
                    description = "TO BE DELETED",
                    documentId = "temp",
                    userId = ""
                )

                // Insert and immediately delete to force Flow to emit
                transactionDao.insertTransaction(dummyTransaction)
                transactionDao.deleteTransaction(dummyTransaction)
            }
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error clearing transactions", e)
            _errorMessage.postValue("Error clearing transactions: ${e.message}")
        } finally {
            _loading.value = false
        }
    }

    // Method to set transactions (used when fetching user transactions from Firestore)
    fun setTransactions(transactions: List<Transaction>) = viewModelScope.launch {
        try {
            _loading.value = true
            transactionDao.clearTransactions()
            transactions.forEach { transactionDao.insertTransaction(it) }
            updateStatistics()
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error setting transactions", e)
            _errorMessage.postValue("Error setting transactions: ${e.message}")
        } finally {
            _loading.value = false
        }
    }

    // Load transactions for a specific date range
    fun loadTransactionsByDateRange(startTime: Long, endTime: Long) = viewModelScope.launch {
        try {
            _loading.value = true
            val filteredList = transactionDao.getTransactionsByDateRange(startTime, endTime)
            _filteredTransactions.value = filteredList
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error loading transactions by date range", e)
            _errorMessage.postValue("Error loading transactions by date range: ${e.message}")
        } finally {
            _loading.value = false
        }
    }

    fun saveMerchant(merchantName: String, category: String, userId: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                // Save to Room database
                val merchant = Merchant(
                    id = 0,
                    name = merchantName,
                    category = category,
                    userId = userId // Add userId field to Merchant entity
                )
                database.merchantDao().insert(merchant)

                // Save to Firestore (under user's merchants collection)
                if (!GuestUserManager.isGuestMode(userId)) {
                    firestore.collection("users")
                        .document(userId)
                        .collection("merchants")
                        .document(merchantName)
                        .set(mapOf(
                            "name" to merchantName,
                            "category" to category
                        ))
                }
            } catch (e: Exception) {
                Log.e("TransactionViewModel", "Error saving merchant", e)
                _errorMessage.postValue("Error saving merchant: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    // Add these convenience methods
    fun loadAllTransactions() = viewModelScope.launch {
        try {
            _loading.value = true
            currentFilterState = FilterState.ALL
            currentCategory = null
            val allTransactions = transactionDao.getAllTransactions().first()
            _filteredTransactions.value = allTransactions
            _filteredTransaction.postValue(allTransactions)
            updateStatistics()
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error loading all transactions", e)
            _errorMessage.postValue("Error loading all transactions: ${e.message}")
        } finally {
            _loading.value = false
        }
    }

    fun loadTodayTransactions() = viewModelScope.launch {
        try {
            _loading.value = true
            currentFilterState = FilterState.TODAY
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            val endTime = calendar.timeInMillis

            val transactions = transactionDao.getTransactionsByDateRange(startTime, endTime)
            applyFilters(transactions)
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error loading today's transactions", e)
            _errorMessage.postValue("Error loading today's transactions: ${e.message}")
        } finally {
            _loading.value = false
        }
    }

    fun loadWeekTransactions() = viewModelScope.launch {
        try {
            _loading.value = true
            currentFilterState = FilterState.WEEK
            val calendar = Calendar.getInstance()
            val endTime = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, -7)
            val startTime = calendar.timeInMillis

            val transactions = transactionDao.getTransactionsByDateRange(startTime, endTime)
            applyFilters(transactions)
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error loading week's transactions", e)
            _errorMessage.postValue("Error loading week's transactions: ${e.message}")
        } finally {
            _loading.value = false
        }
    }

    fun loadMonthTransactions() = viewModelScope.launch {
        try {
            _loading.value = true
            currentFilterState = FilterState.MONTH
            val calendar = Calendar.getInstance()
            val endTime = calendar.timeInMillis
            calendar.add(Calendar.MONTH, -1)
            val startTime = calendar.timeInMillis

            val transactions = transactionDao.getTransactionsByDateRange(startTime, endTime)
            applyFilters(transactions)
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error loading month's transactions", e)
            _errorMessage.postValue("Error loading month's transactions: ${e.message}")
        } finally {
            _loading.value = false
        }
    }

    fun filterByCategory(category: String) = viewModelScope.launch {
        try {
            _loading.value = true
            currentCategory = if (category == "All Categories") null else category

            // Get transactions based on current date filter
            val transactions = when (currentFilterState) {
                FilterState.ALL -> transactionDao.getAllTransactions().first()
                FilterState.TODAY -> {
                    val calendar = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val startTime = calendar.timeInMillis
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                    val endTime = calendar.timeInMillis
                    transactionDao.getTransactionsByDateRange(startTime, endTime)
                }
                FilterState.WEEK -> {
                    val calendar = Calendar.getInstance()
                    val endTime = calendar.timeInMillis
                    calendar.add(Calendar.DAY_OF_YEAR, -7)
                    val startTime = calendar.timeInMillis
                    transactionDao.getTransactionsByDateRange(startTime, endTime)
                }
                FilterState.MONTH -> {
                    val calendar = Calendar.getInstance()
                    val endTime = calendar.timeInMillis
                    calendar.add(Calendar.MONTH, -1)
                    val startTime = calendar.timeInMillis
                    transactionDao.getTransactionsByDateRange(startTime, endTime)
                }
            }

            applyFilters(transactions)
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error filtering by category", e)
            _errorMessage.postValue("Error filtering by category: ${e.message}")
        } finally {
            _loading.value = false
        }
    }

    private fun applyFilters(transactions: List<Transaction>) {
        val filtered = if (currentCategory != null) {
            transactions.filter { it.category == currentCategory }
        } else {
            transactions
        }
        _filteredTransactions.value = filtered
        _filteredTransaction.postValue(filtered)
        updateStatistics()
    }

    // Method to start listening to Firestore updates for the user's transactions
    // Update the startListeningToTransactions method
    fun startListeningToTransactions(userId: String) {
        // Skip for guest users
        if (GuestUserManager.isGuestMode(userId)) {
            Log.d("TransactionViewModel", "Skipping Firestore listener for guest user")
            return
        }

        // Stop any existing listener first
        stopListeningToTransactions()

        Log.d("TransactionViewModel", "Starting Firestore listener for user $userId")

        try {
            transactionListener = firestore.collection("users").document(userId)
                .collection("transactions")
                .addSnapshotListener { snapshots, e ->
                    if (e != null) {
                        Log.e("TransactionViewModel", "Firestore listener error", e)
                        _errorMessage.postValue("Firestore listener error: ${e.message}")
                        return@addSnapshotListener
                    }

                    if (snapshots == null || isLocalUpdate) {
                        return@addSnapshotListener
                    }

                    viewModelScope.launch {
                        try {
                            _loading.value = true
                            val transactions = mutableListOf<Transaction>()

                            for (doc in snapshots.documents) {
                                try {
                                    // Extract data from document
                                    val id = (doc.getLong("id") ?: 0).toInt()
                                    val name = doc.getString("name") ?: ""
                                    val amount = doc.getDouble("amount") ?: 0.0
                                    val date = doc.getLong("date") ?: 0L
                                    val category = doc.getString("category") ?: ""
                                    val merchant = doc.getString("merchant") ?: ""
                                    val description = doc.getString("description") ?: ""
                                    val documentId = doc.id

                                    val transaction = Transaction(
                                        id = id.toLong(),
                                        name = name,
                                        amount = amount,
                                        date = date,
                                        category = category,
                                        merchant = merchant,
                                        description = description,
                                        documentId = documentId,
                                        userId = userId
                                    )

                                    transactions.add(transaction)
                                } catch (e: Exception) {
                                    Log.e("TransactionViewModel", "Error parsing document", e)
                                    _errorMessage.postValue("Error parsing document: ${e.message}")
                                    continue
                                }
                            }

                            // Smart merge with existing data
                            val currentTransactions = transactionDao.getAllTransactions().first()

                            for (transaction in transactions) {
                                val existingTransaction = currentTransactions.find {
                                    (it.documentId == transaction.documentId && transaction.documentId?.isNotEmpty() == true) ||
                                            (it.id == transaction.id && transaction.id.toInt() != 0) ||
                                            (it.amount == transaction.amount &&
                                                    it.date == transaction.date &&
                                                    it.name == transaction.name &&
                                                    it.userId == transaction.userId)
                                }

                                if (existingTransaction == null) {
                                    // Only insert if no matching transaction exists
                                    transactionDao.insertTransaction(transaction)
                                } else {
                                    // Update existing transaction if needed
                                    if (existingTransaction.documentId?.isEmpty() == true  && transaction.documentId?.isNotEmpty() == true ) {
                                        existingTransaction.documentId = transaction.documentId
                                        transactionDao.updateTransaction(existingTransaction)
                                    }
                                }
                            }

                            updateStatistics()
                        } catch (e: Exception) {
                            Log.e("TransactionViewModel", "Error processing Firestore data", e)
                            _errorMessage.postValue("Error processing Firestore data: ${e.message}")
                        } finally {
                            _loading.value = false
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error setting up Firestore listener", e)
            _errorMessage.postValue("Error setting up Firestore listener: ${e.message}")
        }
    }

    // Load from Firestore once (not real-time)
    fun loadFromFirestore(userId: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                Log.d(
                    "TransactionViewModel",
                    "Loading transactions from Firestore for user $userId"
                )

                // Don't clear transactions, we'll merge them
                firestore.collection("users").document(userId).collection("transactions")
                    .get()
                    .addOnSuccessListener { result ->
                        Log.d(
                            "TransactionViewModel",
                            "Got ${result.size()} transactions from Firestore"
                        )

                        viewModelScope.launch {
                            try {
                                val transactions = mutableListOf<Transaction>()

                                for (doc in result.documents) {
                                    try {
                                        // Manual deserialization for better error handling
                                        val id = (doc.getLong("id") ?: 0).toInt()
                                        val name = doc.getString("name") ?: ""
                                        val amount = doc.getDouble("amount") ?: 0.0
                                        val date = doc.getLong("date") ?: 0L
                                        val category = doc.getString("category") ?: ""
                                        val merchant = doc.getString("merchant") ?: ""
                                        val description = doc.getString("description") ?: ""
                                        val documentId = doc.id

                                        val transaction = Transaction(
                                            id = id.toLong(),
                                            name = name,
                                            amount = amount,
                                            date = date,
                                            category = category,
                                            merchant = merchant,
                                            description = description,
                                            userId = userId,
                                            documentId = ""  // Add empty documentId for new transactions
                                        )

                                        transactions.add(transaction)
                                    } catch (e: Exception) {
                                        Log.e("TransactionViewModel", "Error parsing document", e)
                                        _errorMessage.postValue("Error parsing document: ${e.message}")
                                    }
                                }

                                if (transactions.isNotEmpty()) {
                                    // Smart merge with existing data
                                    val currentTransactions =
                                        transactionDao.getAllTransactions().first()
                                    val currentIds = currentTransactions.map { it.id }.toSet()

                                    for (transaction in transactions) {
                                        if (transaction.id in currentIds) {
                                            // Update existing transaction
                                            transactionDao.updateTransaction(transaction)
                                        } else {
                                            // Add new transaction
                                            transactionDao.insertTransaction(transaction)
                                        }
                                    }

                                    updateStatistics()
                                }
                            } catch (e: Exception) {
                                Log.e("TransactionViewModel", "Error processing Firestore data", e)
                                _errorMessage.postValue("Error processing Firestore data: ${e.message}")
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("TransactionViewModel", "Error loading from Firestore", e)
                        _errorMessage.postValue("Error loading from Firestore: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e("TransactionViewModel", "Error loading from Firestore", e)
                _errorMessage.postValue("Error loading from Firestore: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    // Method to stop listening to Firestore updates
    fun stopListeningToTransactions() {
        transactionListener?.remove()
        transactionListener = null
        Log.d("TransactionViewModel", "Stopped Firestore listener")
    }

    class Factory(
        private val database: TransactionDatabase,
        private val application: Application
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TransactionViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return TransactionViewModel(database, application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    // Replace the existing syncTransactionToFirestore method:
    private suspend fun syncTransactionToFirestore(transaction: Transaction) {
        val userId = transaction.userId ?: return
        if (GuestUserManager.isGuestMode(userId)) return

        withContext(Dispatchers.IO) {
            try {
                val docRef = if (!transaction.documentId.isNullOrBlank()) {
                    // Use existing ID if provided
                    firestore.collection("users").document(userId)
                        .collection("transactions").document(transaction.documentId!!)
                } else {
                    // Generate new ID if not provided
                    firestore.collection("users").document(userId)
                        .collection("transactions").document()
                }

                // If it's a new document, update the transaction object and Room
                if (transaction.documentId.isNullOrBlank()) {
                    transaction.documentId = docRef.id
                    transactionDao.updateTransaction(transaction) // Update Room with Firestore ID
                    Log.d(TAG,"Updated local transaction ${transaction.id} with Firestore docId ${docRef.id}")
                }

                // Create map for Firestore
                val transactionMap = hashMapOf(
                    "id" to transaction.id, // Store Room ID for reference (optional)
                    "name" to transaction.name,
                    "amount" to transaction.amount,
                    "date" to transaction.date,
                    "category" to transaction.category,
                    "merchant" to transaction.merchant,
                    "description" to transaction.description,
                    "userId" to userId,
                    "documentId" to transaction.documentId // Always include Firestore ID
                )

                // Set data in Firestore (overwrites if exists, creates if not)
                docRef.set(transactionMap).await()
                Log.d(TAG, "Transaction synced to Firestore: ${docRef.id}")

            } catch (e: Exception) {
                Log.e(TAG, "Error syncing transaction ${transaction.id} to Firestore", e)
                _errorMessage.postValue("Sync Error: ${e.message}") // Use postValue from background thread
            }
        }
    }


}