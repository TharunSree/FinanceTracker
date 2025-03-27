package com.example.financetracker.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.*
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.utils.GuestUserManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import android.app.Application
import com.example.financetracker.database.entity.Merchant
import com.example.financetracker.repository.TransactionRepository

data class CategoryStatistics(
    val maxExpense: Double,
    val totalExpense: Double,
    val transactionCount: Int
)

data class TransactionStatistics(
    val maxExpense: Double,
    val minExpense: Double,
    val totalExpense: Double,
    val categoryStats: Map<String, CategoryStatistics>
)

class TransactionViewModel(
    private val database: TransactionDatabase,
    application: Application
) : AndroidViewModel(application) {

    private val transactionDao = database.transactionDao()
    private val repository: TransactionRepository
    init {
        val database = TransactionDatabase.getDatabase(application)
        repository = TransactionRepository(database.transactionDao(), application)

        // Try to sync pending transactions when ViewModel is created
        viewModelScope.launch {
            repository.syncPendingTransactions()
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
    init {
        viewModelScope.launch {
            loadInitialData()
        }
    }

    private suspend fun loadInitialData() {
        try {
            updateCategories()
            loadAllTransactions()
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error loading initial data", e)
        }
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
            }
        }
    }

    // Add a flag to track transaction source
    private var isLocalUpdate = false

    // Method to add a transaction
    fun addTransaction(transaction: Transaction) = viewModelScope.launch {
        try {
            isLocalUpdate = true

            if (transaction.userId.isNullOrEmpty()) {
                transaction.userId = getUserId(getApplication())
            }

            val insertedId = transactionDao.insertTransactionAndGetId(transaction)

            if (transaction.id == 0) {
                transaction.id = insertedId
            }

            if (!GuestUserManager.isGuestMode(transaction.userId)) {
                syncTransactionToFirestore(transaction)
            }

            // Refresh both filtered lists
            loadAllTransactions()
            updateStatistics()
            isLocalUpdate = false
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error adding transaction", e)
            isLocalUpdate = false
        }
    }

    // Method to delete a transaction
    fun deleteTransaction(transaction: Transaction) = viewModelScope.launch {
        transactionDao.deleteTransaction(transaction)

        // Delete from Firestore
        val uid = userId ?: return@launch

        // Use documentId if available, otherwise use ID
        val docId = if (transaction.documentId.isNotEmpty()) {
            transaction.documentId
        } else {
            transaction.id.toString()
        }

        firestore.collection("users").document(uid)
            .collection("transactions")
            .document(docId)
            .delete()

        updateStatistics()
    }

    // Method to update a transaction
    fun updateTransaction(transaction: Transaction) = viewModelScope.launch {
        // 1. Ensure transaction has userId (either authenticated or guest)
        if (transaction.userId.isNullOrEmpty()) {
            transaction.userId = getUserId(getApplication())
        }

        // 2. Update in local database
        transactionDao.updateTransaction(transaction)

        // 3. Sync with Firestore only for authenticated users
        syncTransactionToFirestore(transaction)

        // 4. Update statistics
        updateStatistics()
    }

    fun syncWithFirestore() = viewModelScope.launch {
        repository.loadFromFirestore()
        repository.syncPendingTransactions()
    }

    private fun getUserId(context: Context): String {
        return auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(context)
    }


    // Replace the existing clearTransactions method with this improved version
    fun clearTransactions() = viewModelScope.launch {
        try {
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
        }
    }

    // Method to set transactions (used when fetching user transactions from Firestore)
    fun setTransactions(transactions: List<Transaction>) = viewModelScope.launch {
        transactionDao.clearTransactions()
        transactions.forEach { transactionDao.insertTransaction(it) }
        updateStatistics()
    }

    // Load transactions for a specific date range
    fun loadTransactionsByDateRange(startTime: Long, endTime: Long) = viewModelScope.launch {
        val filteredList = transactionDao.getTransactionsByDateRange(startTime, endTime)
        _filteredTransactions.value = filteredList
    }

    fun saveMerchant(merchantName: String, category: String, userId: String) {
        viewModelScope.launch {
            // Save to Room database
            val merchant = Merchant(
                id = 0,
                name = merchantName,
                category = category,
                userId = userId // Add userId field to Merchant entity
            )
            database.merchantDao().insertMerchant(merchant)

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
        }
    }

    // Add these convenience methods
    fun loadAllTransactions() = viewModelScope.launch {
        currentFilterState = FilterState.ALL
        currentCategory = null
        val allTransactions = transactionDao.getAllTransactions().first()
        _filteredTransactions.value = allTransactions
        _filteredTransaction.postValue(allTransactions)
        updateStatistics()
    }

    fun loadTodayTransactions() = viewModelScope.launch {
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
    }

    fun loadWeekTransactions() = viewModelScope.launch {
        currentFilterState = FilterState.WEEK
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, -7)
        val startTime = calendar.timeInMillis

        val transactions = transactionDao.getTransactionsByDateRange(startTime, endTime)
        applyFilters(transactions)
    }

    fun loadMonthTransactions() = viewModelScope.launch {
        currentFilterState = FilterState.MONTH
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.add(Calendar.MONTH, -1)
        val startTime = calendar.timeInMillis

        val transactions = transactionDao.getTransactionsByDateRange(startTime, endTime)
        applyFilters(transactions)
    }

    fun filterByCategory(category: String) = viewModelScope.launch {
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
                        return@addSnapshotListener
                    }

                    if (snapshots == null || isLocalUpdate) {
                        return@addSnapshotListener
                    }

                    viewModelScope.launch {
                        try {
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
                                        id = id,
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
                                    continue
                                }
                            }

                            // Smart merge with existing data
                            val currentTransactions = transactionDao.getAllTransactions().first()

                            for (transaction in transactions) {
                                val existingTransaction = currentTransactions.find {
                                    (it.documentId == transaction.documentId && transaction.documentId.isNotEmpty()) ||
                                            (it.id == transaction.id && transaction.id != 0) ||
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
                                    if (existingTransaction.documentId.isEmpty() && transaction.documentId.isNotEmpty()) {
                                        existingTransaction.documentId = transaction.documentId
                                        transactionDao.updateTransaction(existingTransaction)
                                    }
                                }
                            }

                            updateStatistics()
                        } catch (e: Exception) {
                            Log.e("TransactionViewModel", "Error processing Firestore data", e)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error setting up Firestore listener", e)
        }
    }


    // Load from Firestore once (not real-time)
    fun loadFromFirestore(userId: String) {
        viewModelScope.launch {
            Log.d("TransactionViewModel", "Loading transactions from Firestore for user $userId")

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
                                        id = id,
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
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("TransactionViewModel", "Error loading from Firestore", e)
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
    private fun syncTransactionToFirestore(transaction: Transaction) {
        val userId = transaction.userId ?: return

        // Skip Firestore for guest users
        if (GuestUserManager.isGuestMode(userId)) {
            return
        }

        try {
            viewModelScope.launch {
                val docRef = if (transaction.documentId.isNotEmpty()) {
                    firestore.collection("users")
                        .document(userId)
                        .collection("transactions")
                        .document(transaction.documentId)
                } else {
                    firestore.collection("users")
                        .document(userId)
                        .collection("transactions")
                        .document()
                }

                // If it's a new document, update transaction with the document ID
                if (transaction.documentId.isEmpty()) {
                    transaction.documentId = docRef.id
                    // Update in local database with the document ID
                    transactionDao.updateTransaction(transaction)
                }

                val transactionMap = hashMapOf(
                    "id" to transaction.id,
                    "name" to transaction.name,
                    "amount" to transaction.amount,
                    "date" to transaction.date,
                    "category" to transaction.category,
                    "merchant" to transaction.merchant,
                    "description" to transaction.description,
                    "documentId" to transaction.documentId,
                    "userId" to userId
                )

                docRef.set(transactionMap)
                    .addOnSuccessListener {
                        Log.d(
                            "TransactionViewModel",
                            "Transaction synced to Firestore: ${transaction.id}, docId: ${transaction.documentId}"
                        )
                        viewModelScope.launch {
                            loadAllTransactions()
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("TransactionViewModel", "Failed to sync transaction to Firestore", e)
                    }
            }
        } catch (e: Exception) {
            Log.e("TransactionViewModel", "Error syncing transaction to Firestore", e)
        }
    }


}
