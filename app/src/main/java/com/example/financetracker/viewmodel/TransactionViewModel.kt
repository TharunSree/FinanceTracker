package com.example.financetracker.viewmodel

import androidx.lifecycle.*
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
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

    private fun updateStatistics() {
        viewModelScope.launch {
            val allTransactions = transactions.value ?: return@launch

            val categoryMap = mutableMapOf<String, MutableList<Transaction>>()
            var maxExpense = Double.MIN_VALUE
            var minExpense = Double.MAX_VALUE
            var totalExpense = 0.0

            allTransactions.forEach { transaction ->
                categoryMap.getOrPut(transaction.category) { mutableListOf() }.add(transaction)
                maxExpense = maxOf(maxExpense, transaction.amount)
                minExpense = minOf(minExpense, transaction.amount)
                totalExpense += transaction.amount
            }

            val categoryStats = categoryMap.mapValues { (_, transactions) ->
                CategoryStatistics(
                    maxExpense = transactions.maxOf { it.amount },
                    totalExpense = transactions.sumOf { it.amount },
                    transactionCount = transactions.size
                )
            }

            _transactionStatistics.value = TransactionStatistics(
                maxExpense = maxExpense,
                minExpense = minExpense,
                totalExpense = totalExpense,
                categoryStats = categoryStats
            )
        }
    }

    // Method to add a transaction
    fun addTransaction(transaction: Transaction) {
        viewModelScope.launch {
            transactionDao.insertTransaction(transaction)
            updateStatistics()
        }
    }

    // Method to delete a transaction
    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            transactionDao.deleteTransaction(transaction)
            updateStatistics()
        }
    }

    // Method to update a transaction
    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch {
            transactionDao.updateTransaction(transaction)
            updateStatistics()
        }
    }

    // Method to clear all transactions (used when user logs out)
    fun clearTransactions() {
        viewModelScope.launch {
            transactionDao.clearTransactions()
            updateStatistics()
        }
    }

    // Method to set transactions (used when fetching user transactions from Firestore)
    fun setTransactions(transactions: List<Transaction>) {
        viewModelScope.launch {
            transactionDao.clearTransactions()
            transactions.forEach { transactionDao.insertTransaction(it) }
            updateStatistics()
        }
    }

    // Load transactions for a specific date range
    fun loadTransactionsByDateRange(startTime: Long, endTime: Long) {
        viewModelScope.launch {
            val filteredList = transactionDao.getTransactionsByDateRange(startTime, endTime)
            _filteredTransactions.value = filteredList
        }
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
    fun loadAllTransactions() {
        viewModelScope.launch {
            val allTransactions =
                transactionDao.getAllTransactions().first() // Convert Flow to List
            _filteredTransactions.value = allTransactions
        }
    }

    // Method to start listening to Firestore updates for the user's transactions
    fun startListeningToTransactions(userId: String) {
        // First load all data
        loadFromFirestore(userId)

        // Then set up continuous listener
        transactionListener =
            firestore.collection("users").document(userId).collection("transactions")
                .addSnapshotListener { snapshots, e ->
                    if (e != null) {
                        return@addSnapshotListener
                    }

                    val transactions =
                        snapshots?.toObjects(Transaction::class.java) ?: return@addSnapshotListener
                    setTransactions(transactions)
                }
    }

    fun loadFromFirestore(userId: String) {
        viewModelScope.launch {
            // First clear local transactions
            transactionDao.clearTransactions()

            // Then load from Firestore
            firestore.collection("users").document(userId).collection("transactions")
                .get()
                .addOnSuccessListener { result ->
                    val transactions = result.toObjects(Transaction::class.java)
                    setTransactions(transactions)
                }
                .addOnFailureListener { e ->
                    // Handle error
                }
        }
    }

    // Method to stop listening to Firestore updates
    fun stopListeningToTransactions() {
        transactionListener?.remove()
        transactionListener = null
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