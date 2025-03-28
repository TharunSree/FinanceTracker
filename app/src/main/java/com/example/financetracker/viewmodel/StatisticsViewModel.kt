package com.example.financetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.financetracker.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*

class StatisticsViewModel : ViewModel() {
    private val _transactionSummary = MutableStateFlow(TransactionSummary())
    val transactionSummary: StateFlow<TransactionSummary> = _transactionSummary

    private val _categorySummaries = MutableStateFlow<List<CategorySummary>>(emptyList())
    val categorySummaries: StateFlow<List<CategorySummary>> = _categorySummaries

    private val _dailySummaries = MutableStateFlow<List<DailySummary>>(emptyList())
    val dailySummaries: StateFlow<List<DailySummary>> = _dailySummaries

    private val _selectedPeriod = MutableStateFlow(TimePeriod.MONTH)
    val selectedPeriod: StateFlow<TimePeriod> = _selectedPeriod

    enum class TimePeriod {
        WEEK, MONTH, YEAR
    }

    fun updatePeriod(period: TimePeriod) {
        viewModelScope.launch {
            _selectedPeriod.value = period
            refreshStatistics()
        }
    }

    private fun refreshStatistics() {
        viewModelScope.launch {
            // Here you'll add the actual data fetching logic
            // For now, we'll use sample data
            loadSampleData()
        }
    }

    private fun loadSampleData() {
        // Sample transaction summary
        _transactionSummary.value = TransactionSummary(
            totalAmount = 25000.0,
            transactionCount = 15,
            startDate = Date(),
            endDate = Date()
        )

        // Sample category summaries
        _categorySummaries.value = listOf(
            CategorySummary("Food", 8000.0, 0.32f, 5),
            CategorySummary("Transport", 5000.0, 0.2f, 3),
            CategorySummary("Shopping", 7000.0, 0.28f, 4),
            CategorySummary("Bills", 5000.0, 0.2f, 3)
        )

        // Sample daily summaries for the last 7 days
        val calendar = Calendar.getInstance()
        _dailySummaries.value = (0..6).map { daysAgo ->
            calendar.add(Calendar.DAY_OF_MONTH, -1)
            DailySummary(
                date = calendar.time,
                amount = (1000..5000).random().toDouble()
            )
        }.reversed()
    }

    init {
        refreshStatistics()
    }
}