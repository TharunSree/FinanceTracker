package com.example.financetracker.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
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
            val allTransactions = transactionDao.getAllTransactions().first() // Convert Flow to List
            _filteredTransactions.value = allTransactions
        }
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