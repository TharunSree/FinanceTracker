package com.example.financetracker.viewmodel

// Import specific math functions or use prefix
import android.app.Application
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.financetracker.database.dao.CategoryDao
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.repository.TransactionRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Date


// Helper function to parse color hex strings
fun parseColor(hexColor: String?): Color {
    return try {
        if (hexColor != null && hexColor.startsWith("#") && (hexColor.length == 7 || hexColor.length == 9)) {
            val finalHex = if (hexColor.length == 7) "#FF${hexColor.substring(1)}" else hexColor
            Color(android.graphics.Color.parseColor(finalHex))
        } else { Color.Transparent }
    } catch (e: Exception) { Log.w("ColorParse", "Failed to parse color: $hexColor", e); Color.Transparent }
}

class StatisticsViewModel(
    private val transactionRepository: TransactionRepository,
    private val categoryDao: CategoryDao,
    private val application: Application
) : ViewModel() {

    // Data class for aggregated chart data points
    data class TimePointSpending(
        val timePointDate: Date, // Represents the key date/time (start of hour, day, week interval, month)
        val category: String,
        val amount: Double
    )

    // Enum for selecting the time period view
    enum class TimePeriod { TODAY, WEEK, MONTH, ALL } // Standard periods

    private val TAG = "StatisticsViewModel"

    // State for the selected general period (e.g., Daily, Weekly)
    private val _selectedPeriod = MutableStateFlow(TimePeriod.WEEK) // Default to Weekly
    val selectedPeriod: StateFlow<TimePeriod> = _selectedPeriod.asStateFlow()

    // State for the specific date selected ONLY when period is TODAY
    private val _selectedSpecificDate = MutableStateFlow(LocalDate.now())
    val selectedSpecificDate: StateFlow<LocalDate> = _selectedSpecificDate.asStateFlow()

    private val _selectedSpecificMonthYear = MutableStateFlow(YearMonth.now()) // Default to current month
    val selectedSpecificMonthYear: StateFlow<YearMonth> = _selectedSpecificMonthYear.asStateFlow()

    // State for the specific year selected ONLY when period is ALL (interpreting ALL + Picker as Specific Year)
    private val _selectedSpecificYear = MutableStateFlow(LocalDate.now().year) // Default to current year
    val selectedSpecificYear: StateFlow<Int> = _selectedSpecificYear.asStateFlow()

    // State for category colors used in charts
    private val _categoryColorsMap = MutableStateFlow<Map<String, Color>>(emptyMap())
    val categoryColorsMap: StateFlow<Map<String, Color>> = _categoryColorsMap.asStateFlow()

    // --- Flow Logic for Filtered Data and Statistics ---

    // 1. Filter transactions based on the selected period and specific date (if applicable)
    private val filteredTransactions: StateFlow<List<Transaction>> = combine(
        selectedPeriod,
        selectedSpecificDate,
        selectedSpecificMonthYear, // Add
        selectedSpecificYear,      // Add
        transactionRepository.transactions
    ) { period, specificDate, specificMonthYear, specificYear, allTransactions -> // Add params

        Log.d(TAG, "Filtering triggered: Period=$period, Date=$specificDate, MonthYear=$specificMonthYear, Year=$specificYear")
        // Pass all relevant state to calculateTimeRange
        val (startTime, endTime) = calculateTimeRange(period, specificDate, specificMonthYear, specificYear)
        Log.d(TAG, "Calculated filter range: Start=$startTime, End=$endTime")

        // The ALL case in calculateTimeRange now handles the specific year filtering
        allTransactions.filter { it.date in startTime..<endTime } // Universal filter condition

    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // 2. Calculate overall statistics (for Pie Chart, Summary Cards) from filtered transactions
    val transactionStatistics: StateFlow<TransactionStatistics> = filteredTransactions.map { transactions ->
        calculateTransactionStatistics(transactions) // Uses the filtered list
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TransactionStatistics() // Default empty stats
    )

    // 3. Calculate transaction count from filtered transactions
    val transactionCount: StateFlow<Int> = filteredTransactions.map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    // 4. Aggregate spending data (for Line Chart) from filtered transactions based on period
    val spendingDataByTimePoint: StateFlow<List<TimePointSpending>> = combine(
        selectedPeriod, filteredTransactions
    ) { period, transactions ->
        // *** Corrected Log.d statement ***
        Log.d(TAG, "Aggregating data: Period=$period, TxnCount=${transactions.size}")
        calculateSpendingByTimePoint(transactions, period) // Use the correct aggregation logic
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Initialization block
    init {
        loadCategoryColors() // Load colors when ViewModel is created
        Log.d(TAG, "StatisticsViewModel initialized (Simplified).")
    }

    // --- Public Functions for UI Interaction ---

    // Called when user selects a general period (Daily, Weekly, etc.)
    fun updatePeriod(period: TimePeriod) {
        _selectedPeriod.value = period
        // Optional: Reset specific selectors when changing period type
        when (period) {
            TimePeriod.TODAY -> _selectedSpecificDate.value = LocalDate.now()
            TimePeriod.MONTH -> _selectedSpecificMonthYear.value = YearMonth.now()
            TimePeriod.ALL -> _selectedSpecificYear.value = LocalDate.now().year
            TimePeriod.WEEK -> { /* No specific selector state to reset for WEEK yet */ }
        }
    }

    fun updateSelectedMonthYear(yearMonth: YearMonth) {
        _selectedSpecificMonthYear.value = yearMonth
        // Ensure the period is set to MONTH if user interacts with this picker
        if (_selectedPeriod.value != TimePeriod.MONTH) {
            _selectedPeriod.value = TimePeriod.MONTH
        }
    }

    // Called when user selects a specific Year (e.g., from a custom picker)
    fun updateSelectedYear(year: Int) {
        _selectedSpecificYear.value = year
        // Ensure the period is set to ALL if user interacts with this picker
        // This changes the meaning of ALL to "Specific Year" once selected
        if (_selectedPeriod.value != TimePeriod.ALL) {
            _selectedPeriod.value = TimePeriod.ALL
        }
    }

    // Called when user selects a specific date using the Date Picker (only relevant for TODAY period)
    fun updateSelectedDate(date: LocalDate) {
        _selectedSpecificDate.value = date
        if (_selectedPeriod.value != TimePeriod.TODAY) { _selectedPeriod.value = TimePeriod.TODAY }
    }

    // --- Private Helper Functions ---

    // Calculates Start and End timestamps (epoch milliseconds) for filtering
    private fun calculateTimeRange(
        period: TimePeriod,
        specificDate: LocalDate, // For TODAY
        specificMonthYear: YearMonth, // For MONTH
        specificYear: Int // For ALL (Specific Year)
    ): Pair<Long, Long> {
        val zoneId = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zoneId) // Still useful for relative periods like WEEK

        return when (period) {
            TimePeriod.TODAY -> {
                val start = specificDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = specificDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
            TimePeriod.WEEK -> {
                // Keep existing logic (e.g., last 7 days relative to 'now')
                // Or implement logic based on a selected week start date if you add that feature
                val end = now.plusDays(1).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
                val start = now.minusDays(6).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
                Pair(start, end)
            }
            TimePeriod.MONTH -> {
                // Use the selectedSpecificMonthYear
                val start = specificMonthYear.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = specificMonthYear.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                Pair(start, end)
            }
            TimePeriod.ALL -> {
                // Use the selectedSpecificYear
                // This filters ALL down to a specific year
                val start = LocalDate.of(specificYear, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = LocalDate.of(specificYear + 1, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                // If you want ALL to truly mean 'everything' initially, you'd need more complex state
                // to track if the year picker was *ever* used for the ALL period.
                // This simpler approach makes ALL + Year Picker = Specific Year View.
                Pair(start, end)
            }
        }
    }

    // Calculates overall min/max/total and category summaries
    private fun calculateTransactionStatistics(transactions: List<Transaction>): TransactionStatistics {
        if (transactions.isEmpty()) return TransactionStatistics()
        val categoryMap = mutableMapOf<String, MutableList<Double>>()
        var maxExpense = Double.MIN_VALUE; var minExpense = Double.MAX_VALUE; var totalExpense = 0.0
        transactions.forEach { if (it.amount > 0) { categoryMap.getOrPut(it.category) { mutableListOf() }.add(it.amount); maxExpense = maxOf(maxExpense, it.amount); minExpense = minOf(minExpense, it.amount); totalExpense += it.amount } }
        if (totalExpense == 0.0) { maxExpense = 0.0; minExpense = 0.0 } else if (minExpense == Double.MAX_VALUE) { minExpense = maxExpense }
        val categoryStats = categoryMap.mapValues { (_, amounts) -> CategoryStatistics(maxExpense = amounts.maxOrNull() ?: 0.0, totalExpense = amounts.sum(), transactionCount = amounts.size) }
        return TransactionStatistics(maxExpense = if (maxExpense == Double.MIN_VALUE) 0.0 else maxExpense, minExpense = if (minExpense == Double.MAX_VALUE) 0.0 else minExpense, totalExpense = totalExpense, categoryStats = categoryStats)
    }

    // Aggregates spending data for the Line Chart based on the selected period
    private fun calculateSpendingByTimePoint(transactions: List<Transaction>, period: TimePeriod): List<TimePointSpending> {
        if (transactions.isEmpty()) return emptyList()
        // Use YearMonth as key for ALL period
        val aggregationMap = mutableMapOf<Any, MutableMap<String, Double>>() // Key can be Int, DayOfWeek, YearMonth
        val zoneId = ZoneId.systemDefault()

        transactions.forEach { transaction ->
            val transactionDateTime = Instant.ofEpochMilli(transaction.date).atZone(zoneId)
            // Use YearMonth for ALL period key
            val key: Any = when (period) {
                TimePeriod.TODAY -> transactionDateTime.hour
                TimePeriod.WEEK -> transactionDateTime.dayOfWeek
                TimePeriod.MONTH -> {
                    val day = transactionDateTime.dayOfMonth
                    when { day <= 7 -> 0; day <= 14 -> 1; day <= 21 -> 2; day <= 28 -> 3; else -> 4 }
                }
                TimePeriod.ALL -> YearMonth.from(transactionDateTime) // <-- FIX: Use YearMonth
            }
            val categorySpending = aggregationMap.getOrPut(key) { mutableMapOf() }
            categorySpending[transaction.category] = categorySpending.getOrDefault(transaction.category, 0.0) + transaction.amount
        }

        val spendingList = mutableListOf<TimePointSpending>()
        // Find the earliest date for fallback/context if needed (optional optimization: could pass null if not needed)
        val firstTransactionDate = transactions.minByOrNull { it.date }?.date ?: System.currentTimeMillis()

        aggregationMap.forEach { (timeKey, categorySpendingMap) ->
            // Pass the correct zoneId needed for potential conversion
            val representativeDate = dateFromTimeKey(timeKey, period, firstTransactionDate, zoneId) // Pass zoneId
            categorySpendingMap.forEach { (category, amount) ->
                spendingList.add(TimePointSpending(representativeDate, category, amount))
            }
        }
        Log.d(TAG, "Aggregated ${spendingList.size} points for $period")
        // Sort by date AFTER generating correct dates
        return spendingList.sortedBy { it.timePointDate.time }
    }

    // Helper to create representative Date
    private fun dateFromTimeKey(key: Any, period: TimePeriod, contextTimestamp: Long, zoneId: ZoneId): Date {
        try {
            val instant: Instant = when (period) {
                TimePeriod.TODAY -> {
                    if (key is Int) {
                        _selectedSpecificDate.value.atTime(key, 0).atZone(zoneId).toInstant()
                    } else Instant.ofEpochMilli(contextTimestamp) // Fallback
                }
                TimePeriod.WEEK -> {
                    if (key is DayOfWeek) {
                        // Find the actual date matching this DayOfWeek within the relevant week range
                        // This logic needs careful implementation based on how WEEK is defined (e.g., last 7 days)
                        // Placeholder - returning start of the day for 'now' matching the DayOfWeek is complex
                        // Using Calendar logic might be simpler here if precise date isn't critical
                        val today = LocalDate.now(zoneId)
                        var dateInWeek = today.with(TemporalAdjusters.previousOrSame(key))
                        // Adjust if 'today' is before the start of your defined week
                        val weekStart = today.minusDays(6)
                        if (dateInWeek.isBefore(weekStart)) {
                            dateInWeek = dateInWeek.plusWeeks(1) // Move to the correct week if needed
                        }
                        // Ensure it's within the last 7 days (adjust based on exact week def)
                        if(dateInWeek.isAfter(today)) dateInWeek = dateInWeek.minusWeeks(1)

                        dateInWeek.atStartOfDay(zoneId).toInstant()
                    } else Instant.ofEpochMilli(contextTimestamp)
                }
                TimePeriod.MONTH -> {
                    // Determine representative date for week intervals (e.g., 1st, 8th, 15th, 22nd, 29th)
                    // This also needs careful calculation based on the month being viewed
                    // Using Calendar logic might be simpler if approximation is okay
                    if (key is Int) {
                        val monthStart = LocalDate.now(zoneId).withDayOfMonth(1) // Or relevant month start
                        val dayOffset = when(key) { 0 -> 0L; 1 -> 7L; 2 -> 14L; 3 -> 21L; 4 -> 28L; else -> 0L }
                        monthStart.plusDays(dayOffset).atStartOfDay(zoneId).toInstant()

                    } else Instant.ofEpochMilli(contextTimestamp)
                }
                TimePeriod.ALL -> {
                    if (key is YearMonth) {
                        // Convert YearMonth start to Instant
                        key.atDay(1).atStartOfDay(zoneId).toInstant()
                    } else Instant.ofEpochMilli(contextTimestamp) // Fallback
                }
            }
            return Date.from(instant)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating representative date for key '$key', period '$period'", e)
            return Date(contextTimestamp) // Fallback
        }
    }

    // Load category colors
    private fun loadCategoryColors() {
        viewModelScope.launch {
            val userId = getCurrentUserId()
            categoryDao.getAllCategories(userId)
                .map { categories -> categories.associate { it.name to parseColor(it.colorHex) } }
                .catch { e -> Log.e(TAG, "Error loading category colors", e); emit(emptyMap()) }
                .collect { map -> _categoryColorsMap.value = map; Log.d(TAG, "Category colors loaded: ${map.size}") }
        }
    }

    // Get current user ID (consider guest mode if needed for stats)
    private fun getCurrentUserId(): String? {
        return FirebaseAuth.getInstance().currentUser?.uid
    }

    // --- Potentially needed math helper function (if moved from Screen) ---
    // Ensure this uses kotlin.math prefixes if needed, though most are extension functions
    // fun calculateNiceAxisValues(...) { ... } // If moved here

}

