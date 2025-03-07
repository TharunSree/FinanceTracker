package com.example.financetracker.viewmodel

import android.util.Log
import androidx.lifecycle.*
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

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

class TransactionViewModel(private val database: TransactionDatabase) : ViewModel() {

    private val transactionDao = database.transactionDao()
    private val firestore = FirebaseFirestore.getInstance()
    private var transactionListener: ListenerRegistration? = null
    private val auth = FirebaseAuth.getInstance()

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

    // Method to add a transaction
    fun addTransaction(transaction: Transaction) = viewModelScope.launch {
        // Set user ID if not already set
        if (transaction.userId.isNullOrEmpty()) {
            transaction.userId = userId
        }

        transactionDao.insertTransaction(transaction)
        updateStatistics()
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
        // Make sure userId is set
        if (transaction.userId.isNullOrEmpty()) {
            transaction.userId = userId
        }

        transactionDao.updateTransaction(transaction)
        updateStatistics()
    }

    // Method to clear all transactions (used when user logs out)
    fun clearTransactions() = viewModelScope.launch {
        transactionDao.clearTransactions()
        updateStatistics()
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

    // Add these convenience methods
    fun loadTodayTransactions() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val startTime = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val endTime = calendar.timeInMillis

        loadTransactionsByDateRange(startTime, endTime)
    }

    fun loadWeekTransactions() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -7)
        val startTime = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 7)
        val endTime = calendar.timeInMillis

        loadTransactionsByDateRange(startTime, endTime)
    }

    fun loadMonthTransactions() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, -1)
        val startTime = calendar.timeInMillis
        calendar.add(Calendar.MONTH, 1)
        val endTime = calendar.timeInMillis

        loadTransactionsByDateRange(startTime, endTime)
    }

    // Load all transactions
    fun loadAllTransactions() = viewModelScope.launch {
        val allTransactions = transactionDao.getAllTransactions().first() // Convert Flow to List
        _filteredTransactions.value = allTransactions
    }

    // Method to start listening to Firestore updates for the user's transactions
    fun startListeningToTransactions(userId: String) {
        // Stop any existing listener first
        stopListeningToTransactions()

        Log.d("TransactionViewModel", "Starting Firestore listener for user $userId")

        // Start a new listener that will continuously sync changes
        try {
            transactionListener = firestore.collection("users").document(userId)
                .collection("transactions")
                .addSnapshotListener { snapshots, e ->
                    if (e != null) {
                        Log.e("TransactionViewModel", "Firestore listener error", e)
                        return@addSnapshotListener
                    }

                    if (snapshots == null) {
                        Log.d("TransactionViewModel", "Null snapshot received")
                        return@addSnapshotListener
                    }

                    if (snapshots.isEmpty) {
                        Log.d("TransactionViewModel", "Empty snapshot received")
                        return@addSnapshotListener
                    }

                    Log.d("TransactionViewModel", "Received ${snapshots.size()} transactions from Firestore")

                    viewModelScope.launch {
                        try {
                            val transactions = mutableListOf<Transaction>()

                            for (doc in snapshots.documents) {
                                try {
                                    // Manual deserialization to avoid Firestore issues
                                    val id = (doc.getLong("id") ?: 0).toInt()
                                    val name = doc.getString("name") ?: ""
                                    val amount = doc.getDouble("amount") ?: 0.0
                                    val date = doc.getLong("date") ?: 0L
                                    val category = doc.getString("category") ?: ""
                                    val merchant = doc.getString("merchant") ?: ""
                                    val description = doc.getString("description") ?: ""

                                    // Get document ID for future updates
                                    val documentId = doc.id

                                    // Create transaction with the correct fields
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
                                    continue // Skip this document if there's an error
                                }
                            }

                            // Only update if we have transactions
                            if (transactions.isNotEmpty()) {
                                Log.d("TransactionViewModel", "Updating local DB with ${transactions.size} transactions")

                                // Merge with local transactions instead of replacing all
                                val currentTransactions = transactionDao.getAllTransactions().first()
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

            Log.d("TransactionViewModel", "Firestore listener successfully registered")
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
                    Log.d("TransactionViewModel", "Got ${result.size()} transactions from Firestore")

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
                                        documentId = documentId,
                                        userId = userId
                                    )

                                    transactions.add(transaction)
                                } catch (e: Exception) {
                                    Log.e("TransactionViewModel", "Error parsing document", e)
                                }
                            }

                            if (transactions.isNotEmpty()) {
                                // Smart merge with existing data
                                val currentTransactions = transactionDao.getAllTransactions().first()
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

    class Factory(private val database: TransactionDatabase) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TransactionViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return TransactionViewModel(database) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
