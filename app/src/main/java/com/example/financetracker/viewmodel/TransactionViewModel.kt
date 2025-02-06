package com.example.financetracker.viewmodel

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

class TransactionViewModel(private val database: TransactionDatabase) : ViewModel() {

    private val transactionDao = database.transactionDao()

    // Using StateFlow for filtered transactions
    private val _filteredTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    val filteredTransactions: StateFlow<List<Transaction>> = _filteredTransactions

    // Using Flow for all transactions
    val transactions = transactionDao.getAllTransactions().asLiveData()

    // Method to add a transaction
    fun addTransaction(transaction: Transaction) {
        viewModelScope.launch {
            transactionDao.insertTransaction(transaction)
        }
    }

    // Method to delete a transaction
    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            transactionDao.deleteTransaction(transaction)
        }
    }

    // Method to update a transaction
    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch {
            transactionDao.updateTransaction(transaction)
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