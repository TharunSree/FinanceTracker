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
        // 1. Ensure transaction has userId (either authenticated or guest)
        if (transaction.userId.isNullOrEmpty()) {
            transaction.userId = getUserId(getApplication())
        }

        // 2. Insert into local database first
        transactionDao.insertTransaction(transaction)

        // 3. Sync with Firestore only for authenticated users
        syncTransactionToFirestore(transaction)

        // 4. Update statistics
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

    private fun getUserId(context: Context): String {
        return auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(context)
    }


    // Method to clear all transactions (used when user logs out)
    // Method to clear all transactions (used when user logs out)
    fun clearTransactions() = viewModelScope.launch {
        // Clear from local database
        transactionDao.clearTransactions()

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

                    if (snapshots == null) {
                        Log.d("TransactionViewModel", "Null snapshot received")
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
                                    val documentId =
                                        doc.id // Always use the actual Firestore document ID

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
                            val firestoreDocIds = transactions.map { it.documentId }.toSet()

                            // Update or insert transactions from Firestore
                            // Update or insert transactions from Firestore
                            for (transaction in transactions) {
                                // First check for document ID match
                                val existingByDocId = currentTransactions.find {
                                    it.documentId == transaction.documentId && !transaction.documentId.isEmpty()
                                }

                                // Then check for ID match
                                val existingById = if (existingByDocId == null) {
                                    currentTransactions.find { it.id == transaction.id && transaction.id != 0 }
                                } else null

                                // Finally check for a "similar" transaction (same amount, date, name)
                                val existingSimilar = if (existingByDocId == null && existingById == null) {
                                    currentTransactions.find {
                                        it.amount == transaction.amount &&
                                                Math.abs(it.date - transaction.date) < 60000 && // Within 1 minute
                                                it.name == transaction.name &&
                                                it.userId == transaction.userId
                                    }
                                } else null

                                when {
                                    existingByDocId != null -> {
                                        // Update existing transaction by document ID
                                        Log.d("TransactionViewModel", "Updating existing transaction by docId: ${transaction.documentId}")
                                        transactionDao.updateTransaction(transaction)
                                    }
                                    existingById != null -> {
                                        // Update document ID and update transaction
                                        Log.d("TransactionViewModel", "Updating existing transaction by id: ${transaction.id}")
                                        transaction.documentId = existingById.documentId.ifEmpty { transaction.documentId }
                                        transactionDao.updateTransaction(transaction)
                                    }
                                    existingSimilar != null -> {
                                        // Similar transaction found, update with new document ID if needed
                                        Log.d("TransactionViewModel", "Found similar transaction, updating with new data")
                                        if (transaction.documentId.isNotEmpty() && existingSimilar.documentId.isEmpty()) {
                                            existingSimilar.documentId = transaction.documentId
                                        }
                                        existingSimilar.name = transaction.name
                                        existingSimilar.category = transaction.category
                                        transactionDao.updateTransaction(existingSimilar)
                                    }
                                    else -> {
                                        // No similar transaction found, insert as new
                                        Log.d("TransactionViewModel", "Inserting new transaction from Firestore")
                                        transactionDao.insertTransaction(transaction)
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
            // First, check if we already have this transaction in the database with a document ID
            viewModelScope.launch {
                // If the transaction already has a document ID, use that
                val docRef = if (transaction.documentId.isNotEmpty()) {
                    firestore.collection("users")
                        .document(userId)
                        .collection("transactions")
                        .document(transaction.documentId)
                } else {
                    // Create a new document with auto-generated ID
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

                // Create a map with all transaction data
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

                // Save to Firestore
                docRef.set(transactionMap)
                    .addOnSuccessListener {
                        Log.d(
                            "TransactionViewModel",
                            "Transaction synced to Firestore: ${transaction.id}, docId: ${transaction.documentId}"
                        )
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
