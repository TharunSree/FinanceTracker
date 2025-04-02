package com.example.financetracker.viewmodel

import android.app.Application // Need application or context for user ID potentially
import android.util.Log
import androidx.compose.ui.graphics.Color // Import Compose Color
import androidx.compose.ui.graphics.toArgb // To potentially convert Color to Int if needed elsewhere
import androidx.core.graphics.ColorUtils // Useful for hex parsing if needed
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.financetracker.database.dao.CategoryDao // Import CategoryDao
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.repository.TransactionRepository
import com.example.financetracker.utils.GuestUserManager // Needed for user ID
import com.google.firebase.auth.FirebaseAuth // Needed for user ID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

fun parseColor(hexColor: String?): Color {
    return try {
        if (hexColor != null && hexColor.startsWith("#") && (hexColor.length == 7 || hexColor.length == 9)) {
            // Add alpha if missing (assuming opaque)
            val finalHex = if (hexColor.length == 7) "#FF${hexColor.substring(1)}" else hexColor
            Color(android.graphics.Color.parseColor(finalHex)) // Use android graphics color parsing
        } else {
            Color.Transparent // Default or fallback color if hex is invalid/null
        }
    } catch (e: Exception) {
        Log.w("ColorParse", "Failed to parse color: $hexColor", e)
        Color.Transparent // Fallback on error
    }
}

class StatisticsViewModel(
    private val transactionRepository: TransactionRepository,
    private val categoryDao: CategoryDao,
    private val application: Application
) : ViewModel() {

    data class DailySpending(
        val date: Date,
        val category: String,
        val amount: Double
    )

    private val _selectedPeriod = MutableStateFlow(TimePeriod.WEEK)
    val selectedPeriod: StateFlow<TimePeriod> = _selectedPeriod.asStateFlow()

    private val _transactionStatistics = MutableStateFlow(
        TransactionStatistics(0.0, 0.0, 0.0, emptyMap())
    )
    val transactionStatistics: StateFlow<TransactionStatistics> = _transactionStatistics.asStateFlow()

    private val _transactionCount = MutableStateFlow(0)
    val transactionCount: StateFlow<Int> = _transactionCount.asStateFlow()

    private val _dailySpendingData = MutableStateFlow<List<DailySpending>>(emptyList())
    val dailySpendingData: StateFlow<List<DailySpending>> = _dailySpendingData.asStateFlow()

    private val _categoryColorsMap = MutableStateFlow<Map<String, Color>>(emptyMap())
    val categoryColorsMap: StateFlow<Map<String, Color>> = _categoryColorsMap.asStateFlow()



    init {
        // Load stats on init (existing)
        loadStatistics()
        // Load category colors on init
        loadCategoryColors()
    }

    fun updatePeriod(period: TimePeriod) {
        _selectedPeriod.value = period
        loadStatistics()
    }

    private fun loadStatistics() {
        viewModelScope.launch {
            val transactions = getTransactionsForPeriod(_selectedPeriod.value)
            val stats = calculateTransactionStatistics(transactions)
            _transactionStatistics.value = stats
            _transactionCount.value = transactions.size
            _dailySpendingData.value = calculateDailySpending(transactions)
        }
    }

    private suspend fun getTransactionsForPeriod(period: TimePeriod): List<Transaction> {
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        val startTime = when (period) {
            TimePeriod.TODAY -> {
                calendar.apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            TimePeriod.WEEK -> {
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                calendar.timeInMillis
            }
            TimePeriod.MONTH -> {
                calendar.add(Calendar.MONTH, -1)
                calendar.timeInMillis
            }
            TimePeriod.ALL -> 0L
        }

        return if (period == TimePeriod.ALL) {
            transactionRepository.getAllTransactions()
        } else {
            transactionRepository.getTransactionsByDateRange(startTime, endTime)
        }
    }

    private fun calculateTransactionStatistics(transactions: List<Transaction>): TransactionStatistics {
        if (transactions.isEmpty()) {
            return TransactionStatistics(0.0, 0.0, 0.0, emptyMap())
        }

        val categoryMap = mutableMapOf<String, MutableList<Transaction>>()
        var maxExpense = Double.MIN_VALUE
        var minExpense = Double.MAX_VALUE
        var totalExpense = 0.0

        transactions.forEach { transaction ->
            categoryMap.getOrPut(transaction.category) { mutableListOf() }.add(transaction)
            maxExpense = maxOf(maxExpense, transaction.amount)
            minExpense = minOf(minExpense, transaction.amount)
            totalExpense += transaction.amount
        }

        val categoryStats = categoryMap.mapValues { (_, transactions) ->
            CategoryStatistics(
                maxExpense = transactions.maxOfOrNull { it.amount } ?: 0.0,
                totalExpense = transactions.sumOf { it.amount },
                transactionCount = transactions.size
            )
        }

        return TransactionStatistics(
            maxExpense = maxExpense,
            minExpense = minExpense,
            totalExpense = totalExpense,
            categoryStats = categoryStats
        )
    }

    private fun calculateDailySpending(transactions: List<Transaction>): List<DailySpending> {
        val dailySpendingMap = mutableMapOf<Date, MutableMap<String, Double>>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        transactions.forEach { transaction ->
            val date = dateFormat.parse(dateFormat.format(Date(transaction.date))) ?: return@forEach
            val categorySpending = dailySpendingMap.getOrPut(date) { mutableMapOf() }
            categorySpending[transaction.category] = categorySpending.getOrDefault(transaction.category, 0.0) + transaction.amount
        }

        val dailySpendingList = mutableListOf<DailySpending>()
        dailySpendingMap.forEach { (date, categorySpending) ->
            categorySpending.forEach { (category, amount) ->
                dailySpendingList.add(DailySpending(date, category, amount))
            }
        }
        return dailySpendingList
    }

    private fun loadCategoryColors() {
        viewModelScope.launch {
            // Get current user ID (you might need context/application here)
            val userId = getCurrentUserId()

            categoryDao.getAllCategories(userId) // Fetch categories from DAO
                .map { categories ->
                    // Convert List<Category> to Map<String, Color>
                    categories.associate { category ->
                        // Use the parseColor helper function
                        category.name to parseColor(category.colorHex)
                    }
                }
                .catch { e ->
                    // Handle potential errors during fetch/map
                    Log.e("StatisticsViewModel", "Error loading category colors", e)
                    emit(emptyMap()) // Emit empty map on error
                }
                .collect { map ->
                    // Update the StateFlow
                    _categoryColorsMap.value = map
                }
        }
    }

    private fun getCurrentUserId(): String? {
        // Reuse logic similar to TransactionRepository if applicable
        val auth = FirebaseAuth.getInstance()
        return auth.currentUser?.uid // Return null if no user logged in
        // If you need guest support for categories specifically, add GuestUserManager logic:
        // return auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(application)
    }

    enum class TimePeriod {
        TODAY, WEEK, MONTH, ALL
    }
}