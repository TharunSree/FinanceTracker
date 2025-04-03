package com.example.financetracker.ui.screens

// Import Pager related components
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
// Math imports for nice axis calculation
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// Other necessary imports
import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
// Import AndroidView for MPAndroidChart integration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow // Added import
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.financetracker.viewmodel.StatisticsViewModel // Assume your ViewModel is here

// --- MPAndroidChart Imports ---
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.charts.Chart // Base chart class
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.components.Legend // Added import
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet // Needed for BarChart data list
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.utils.ColorTemplate
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.listener.OnChartValueSelectedListener // Added import
import com.github.mikephil.charting.highlight.Highlight // Added import


import androidx.compose.ui.graphics.toArgb // Needed for converting Compose Color to Android Color Int
import androidx.compose.ui.platform.LocalContext
import com.github.mikephil.charting.components.AxisBase
import java.text.DateFormatSymbols // Corrected import

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit // Added import (potentially useful)

// --- End Imports ---

private const val SCREEN_TAG = "StatisticsScreen" // Tag for logging

// --- ChartColors object definition ---
object ChartColors {
    val Primary = Color(0xFF1976D2) // Blue
    val Secondary = Color(0xFF03A9F4) // Light Blue
    val Error = Color(0xFFE57373) // Reddish
    val DefaultCategoryColors = listOf(
        Color(0xFFE6194B), Color(0xFF3CB44B), Color(0xFFFFE119), Color(0xFF4363D8),
        Color(0xFFF58231), Color(0xFF911EB4), Color(0xFF46F0F0), Color(0xFFF032E6),
        Color(0xFFBCF60C), Color(0xFFFABEBE), Color(0xFF008080), Color(0xFFE6BEFF),
        Color(0xFF9A6324), Color(0xFFFFFAC8), Color(0xFF800000), Color(0xFFAAFFC3),
        Color(0xFF808000), Color(0xFFFFD8B1), Color(0xFF000075), Color(0xFF808080)
    )

    fun getDefaultColor(index: Int): Color {
        if (DefaultCategoryColors.isEmpty()) return Color.Gray
        return DefaultCategoryColors[index % DefaultCategoryColors.size]
    }

    fun getDefaultColorByName(categoryName: String): Color {
        if (DefaultCategoryColors.isEmpty()) return Color.Gray
        val hashCode = categoryName.hashCode()
        val index = (hashCode and 0x7FFFFFFF) % DefaultCategoryColors.size // Ensure positive index
        return DefaultCategoryColors[index]
    }
}

// --- Helper Function for Nice Numbers ---
fun calculateNiceAxisValues(actualMax: Double, maxTicks: Int = 5): Pair<Double, List<Double>> {
    if (actualMax <= 0) {
        // Provide a default range if max is zero or negative
        val defaultMax = 100.0
        val safeMaxTicks = maxTicks.coerceAtLeast(1)
        val defaultTick = defaultMax / safeMaxTicks
        val defaultTicks =
            List(safeMaxTicks + 1) { i -> (i * defaultTick).coerceAtMost(defaultMax) }
        return Pair(defaultMax, defaultTicks.distinct()) // Ensure distinctness
    }

    val exponent = floor(log10(actualMax))
    val fraction = actualMax / (10.0).pow(exponent)

    val niceFraction = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 2.5 -> 2.5
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }

    val niceTick = niceFraction * (10.0).pow(exponent)
    var axisMax = ceil(actualMax / niceTick) * niceTick

    // Refined adjustments for axisMax
    if (axisMax <= actualMax) { // Ensure axisMax is strictly greater if actualMax is not a multiple
        axisMax += niceTick
    }
    // Avoid tiny gaps between actual max and axis max
    if (abs(axisMax - actualMax) < niceTick * 0.01) {
        axisMax += niceTick
    }
    // Handle potential zero axisMax when actualMax is > 0 (e.g., very small numbers)
    if (axisMax == 0.0 && actualMax > 0) {
        axisMax = niceTick // Use the smallest tick as max
    }
    // Ensure axisMax is never less than actualMax after adjustments
    axisMax = axisMax.coerceAtLeast(actualMax)
    // Ensure axisMax isn't excessively large if actualMax is just above a tick multiple
    if (axisMax > actualMax + niceTick * 1.1) { // Heuristic: if axisMax is >10% larger than needed
        val potentialLowerMax = floor(actualMax / niceTick) * niceTick + niceTick
        if (potentialLowerMax >= actualMax) {
            axisMax = potentialLowerMax
        }
    }


    val ticks = mutableListOf<Double>()
    var currentTick = 0.0
    val safeNiceTick = if (niceTick <= 0) (10.0).pow(exponent)
        .coerceAtLeast(1.0) else niceTick // More robust safe tick

    // Generate ticks up to slightly beyond axisMax to ensure inclusion
    while (currentTick <= axisMax * 1.001 && ticks.size < maxTicks * 3) { // Limit total generated ticks
        ticks.add(currentTick)
        currentTick += safeNiceTick
        if (safeNiceTick <= 0) break // Safety break
    }
    // Ensure axisMax is explicitly included if not already close to the last tick
    if (ticks.isEmpty() || (ticks.last() < axisMax && abs(ticks.last() - axisMax) > safeNiceTick * 0.1)) {
        ticks.add(axisMax)
    } else if (ticks.last() > axisMax) {
        // Adjust last tick if it overshot axisMax significantly
        if (ticks.size > 1 && ticks.last() > ticks[ticks.lastIndex - 1] + safeNiceTick * 0.5) {
            ticks[ticks.lastIndex] = axisMax
        } else if (ticks.size == 1) {
            ticks[ticks.lastIndex] = axisMax // Ensure single tick is axisMax if > 0
        }
    }


    // Ensure 0.0 is included
    if (ticks.firstOrNull() != 0.0) {
        ticks.add(0, 0.0)
    }

    // Reduce ticks if too many, preserving 0, max, and evenly spaced points
    var finalTicks = ticks.distinct().sorted()
    if (finalTicks.size > maxTicks + 1) { // Allow one extra tick before reducing
        val keepIndices = mutableSetOf(0, finalTicks.lastIndex) // Always keep start and end
        val step =
            finalTicks.size.toDouble() / (maxTicks - 1).coerceAtLeast(1) // Calculate step for intermediate ticks
        for (i in 1 until maxTicks) { // Add intermediate indices based on step
            keepIndices.add((i * step).roundToInt().coerceIn(0, finalTicks.lastIndex))
        }
        finalTicks = finalTicks.filterIndexed { index, _ -> index in keepIndices }
    }

    // Final check for empty/single tick list
    if (finalTicks.isEmpty()) {
        finalTicks = listOf(0.0, axisMax.coerceAtLeast(1.0)) // Fallback
    } else if (finalTicks.size == 1 && finalTicks[0] == 0.0) {
        finalTicks = listOf(0.0, axisMax.coerceAtLeast(1.0)) // Ensure at least two ticks if max > 0
    }

    return Pair(axisMax, finalTicks.distinct().sorted()) // Return distinct sorted ticks
}


// --- Data Structures for Processed Chart Data ---
sealed class TimePointKey {
    data class Hour(val date: Date) : TimePointKey() // Key for Today
    data class DayOfWeek(val dayIndex: Int) : TimePointKey() // Key for Week (0=Mon, 6=Sun)
    data class WeekOfMonth(val intervalIndex: Int) : TimePointKey() // Key for Month (0="1-7", ...)
    data class MonthYear(val date: Date) : TimePointKey() // Key for All Time (first day of month)

    // Provides a stable comparable value for sorting
    fun getComparableValue(): Long = when (this) {
        is Hour -> date.time
        is DayOfWeek -> dayIndex.toLong() // Order Mon-Sun
        is WeekOfMonth -> intervalIndex.toLong()
        is MonthYear -> date.time
    }
}

data class TimePointInfo(
    val key: TimePointKey,
    val label: String
)

data class ProcessedChartData(
    val timePoints: List<TimePointInfo> = emptyList(), // X-axis points and labels
    // Data: Category -> TimePointKey -> Amount
    val dataMap: Map<String, Map<TimePointKey, Double>> = emptyMap(),
    val yAxisMax: Double = 100.0, // Calculated nice max for Y-axis
    val yAxisTicks: List<Double> = listOf(0.0, 25.0, 50.0, 75.0, 100.0) // Ticks for Y-axis
)

// --- Helper Functions for Data Processing ---
private fun getTimePointKey(date: Date, period: StatisticsViewModel.TimePeriod): TimePointKey {
    val calendar = Calendar.getInstance().apply { time = date }
    return when (period) {
        StatisticsViewModel.TimePeriod.TODAY -> {
            calendar.clear(Calendar.MINUTE)
            calendar.clear(Calendar.SECOND)
            calendar.clear(Calendar.MILLISECOND)
            TimePointKey.Hour(calendar.time)
        }

        StatisticsViewModel.TimePeriod.WEEK -> {
            // Calendar.DAY_OF_WEEK: Sunday=1, Monday=2, ..., Saturday=7
            // We want Mon=0, Tue=1, ..., Sun=6
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val keyIndex = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - 2
            TimePointKey.DayOfWeek(keyIndex.coerceIn(0, 6)) // Ensure index is valid
        }

        StatisticsViewModel.TimePeriod.MONTH -> {
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            val keyIndex = when {
                dayOfMonth <= 7 -> 0
                dayOfMonth <= 14 -> 1
                dayOfMonth <= 21 -> 2
                dayOfMonth <= 28 -> 3
                else -> 4 // Days 29, 30, 31
            }
            TimePointKey.WeekOfMonth(keyIndex)
        }

        StatisticsViewModel.TimePeriod.ALL -> {
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.clear(Calendar.HOUR_OF_DAY)
            calendar.clear(Calendar.MINUTE)
            calendar.clear(Calendar.SECOND)
            calendar.clear(Calendar.MILLISECOND)
            TimePointKey.MonthYear(calendar.time)
        }
    }
}

private fun generateTimePoints(
    data: List<StatisticsViewModel.TimePointSpending>,
    period: StatisticsViewModel.TimePeriod
): List<TimePointInfo> {
    val locale = Locale.getDefault()
    return when (period) {
        StatisticsViewModel.TimePeriod.TODAY -> {
            if (data.isEmpty()) {
                Log.d(SCREEN_TAG, "generateTimePoints(TODAY): No data, returning empty list")
                return emptyList()
            }

            val datesInData = data.map { it.timePointDate }
            val minDate = datesInData.minByOrNull { it.time } ?: return emptyList()
            val maxDate = datesInData.maxByOrNull { it.time } ?: return emptyList()

            Log.d(SCREEN_TAG, "generateTimePoints(TODAY): MinDate=${Date(minDate.time)}, MaxDate=${Date(maxDate.time)}")

            val calendar = Calendar.getInstance()
            calendar.time = minDate
            val startHourMillis = calendar.timeInMillis

            calendar.time = maxDate
            val endHourMillis = calendar.timeInMillis

            val timePoints = mutableListOf<TimePointInfo>()
            val currentHourCal = Calendar.getInstance().apply { timeInMillis = startHourMillis }
            val format = SimpleDateFormat("HH:mm", locale)

            Log.d(SCREEN_TAG, "generateTimePoints(TODAY): Generating hours from $startHourMillis to $endHourMillis")

            while (currentHourCal.timeInMillis <= endHourMillis) {
                val currentHourDate = currentHourCal.time
                val key = getTimePointKey(currentHourDate, StatisticsViewModel.TimePeriod.TODAY)
                val label = format.format(currentHourDate) // Format the date directly
                timePoints.add(TimePointInfo(key, label))
                currentHourCal.add(Calendar.HOUR_OF_DAY, 1)
            }

            // --- CORRECTED Single Hour Case ---
            if (timePoints.isEmpty() && startHourMillis == endHourMillis) {
                Log.d(SCREEN_TAG, "generateTimePoints(TODAY): Handling single hour case")
                val key = getTimePointKey(Date(startHourMillis), StatisticsViewModel.TimePeriod.TODAY)
                // Safely check the type and access the date
                if (key is TimePointKey.Hour) {
                    val label = format.format(key.date) // Now safe to access .date
                    timePoints.add(TimePointInfo(key, label))
                } else {
                    // Fallback or error if the key type is unexpectedly different
                    Log.e(SCREEN_TAG, "generateTimePoints(TODAY): Expected Hour key but got $key for single hour")
                    // Optionally add a default point:
                    // timePoints.add(TimePointInfo(key, format.format(Date(startHourMillis))))
                }
            }
            // --- End Correction ---

            Log.d(SCREEN_TAG, "generateTimePoints(TODAY): Final generated labels: ${timePoints.joinToString { it.label }}")
            timePoints
        }
        // ... (WEEK, MONTH, ALL cases remain the same as before) ...
        StatisticsViewModel.TimePeriod.WEEK -> {
            val symbols = DateFormatSymbols.getInstance(locale)
            val shortWeekdays = symbols.shortWeekdays
            val dayIndexToName = (0..6).associateWith { index ->
                val calendarDayConstant = when (index) {
                    0 -> Calendar.MONDAY; 1 -> Calendar.TUESDAY; 2 -> Calendar.WEDNESDAY;
                    3 -> Calendar.THURSDAY; 4 -> Calendar.FRIDAY; 5 -> Calendar.SATURDAY;
                    6 -> Calendar.SUNDAY; else -> Calendar.MONDAY
                }
                if (calendarDayConstant >= 0 && calendarDayConstant < shortWeekdays.size) {
                    shortWeekdays[calendarDayConstant]?.takeIf { it.isNotEmpty() } ?: "??"
                } else { "??" }
            }
            (0..6).map { index ->
                TimePointInfo(TimePointKey.DayOfWeek(index), dayIndexToName[index] ?: "??")
            }
        }
        StatisticsViewModel.TimePeriod.MONTH -> {
            if (data.isEmpty()) {
                val labels = listOf("1","7", "14", "21", "28", "End")
                return (0..4).map { index ->
                    TimePointInfo(TimePointKey.WeekOfMonth(index), labels[index])
                }
            }
            val calendar = Calendar.getInstance().apply {
                time = data.minByOrNull { it.timePointDate.time }?.timePointDate ?: data.first().timePointDate
            }
            calendar.set(Calendar.DAY_OF_MONTH, 1); calendar.add(Calendar.MONTH, 1); calendar.add(Calendar.DAY_OF_MONTH, -1)
            val lastDayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            val labels = listOf("1", "7", "14", "21", "28", lastDayOfMonth.toString())
            (0..4).map { index ->
                TimePointInfo(TimePointKey.WeekOfMonth(index), labels[index])
            }
        }
        StatisticsViewModel.TimePeriod.ALL -> {
            data.map { getTimePointKey(it.timePointDate, period) }
                .distinctBy { (it as TimePointKey.MonthYear).date.time }
                .sortedBy { it.getComparableValue() }
                .map { key ->
                    val format = SimpleDateFormat("MMM yy", locale)
                    TimePointInfo(key, format.format((key as TimePointKey.MonthYear).date))
                }
        }
    }
}

@Composable
private fun rememberProcessedChartData(
    data: List<StatisticsViewModel.TimePointSpending>,
    selectedPeriod: StatisticsViewModel.TimePeriod
): ProcessedChartData {

    return remember(data, selectedPeriod) {
        // Use the raw spending data list directly
        if (data.isEmpty()) {
            val timePoints = generateTimePoints(emptyList(), selectedPeriod)
            val (niceAxisMax, yAxisTicks) = calculateNiceAxisValues(0.0, 5)
            return@remember ProcessedChartData(timePoints, emptyMap(), niceAxisMax, yAxisTicks)
        }

        // 1. Generate Time Points based on the input data's time range
        val timePoints = generateTimePoints(data, selectedPeriod)
        if (timePoints.isEmpty()) {
            val (niceAxisMax, yAxisTicks) = calculateNiceAxisValues(0.0, 5)
            return@remember ProcessedChartData(emptyList(), emptyMap(), niceAxisMax, yAxisTicks)
        }

        // 2. Re-group data by Category -> TimePointKey -> Summed Amount using the keys from timePoints
        // This aggregates data that might span multiple original transactions into the correct time point
        val dataMap = mutableMapOf<String, MutableMap<TimePointKey, Double>>()
        data.forEach { spending ->
            // Find the correct TimePointKey based on the period
            val key = getTimePointKey(spending.timePointDate, selectedPeriod)
            val categoryMap = dataMap.getOrPut(spending.category) { mutableMapOf() }
            categoryMap[key] = categoryMap.getOrDefault(key, 0.0) + spending.amount
        }

        // Ensure all defined time points exist for each category (with 0 if no data)
        val finalDataMap = dataMap.mapValues { (_, categoryTimeMap) ->
            timePoints.associate { timePointInfo ->
                timePointInfo.key to (categoryTimeMap[timePointInfo.key] ?: 0.0)
            }
        }


        // 3. Calculate Max Y value (use line chart logic)
        val actualMaxAmount = finalDataMap.values.flatMap { it.values }.maxOrNull() ?: 0.0
        val (niceAxisMax, yAxisTicks) = calculateNiceAxisValues(actualMaxAmount, 5)

        ProcessedChartData(timePoints, finalDataMap, niceAxisMax, yAxisTicks)
    }
}


// --- Main Statistics Screen Composable ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel) { // Pass your ViewModel instance
    // --- State Collection ---
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val transactionStatistics by viewModel.transactionStatistics.collectAsStateWithLifecycle()
    val dailySpendingData by viewModel.spendingDataByTimePoint.collectAsStateWithLifecycle()
    val transactionCount by viewModel.transactionCount.collectAsStateWithLifecycle()
    // Assuming ViewModel provides this map for user-assigned colors eventually
    val categoryColorsMap by viewModel.categoryColorsMap.collectAsStateWithLifecycle()

    // --- Theme ---
    val customColors = darkColorScheme(
        primary = Color(0xFF2196F3), // Example Blue
        onPrimary = Color.White,
        primaryContainer = Color(0xFF1565C0),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFF03A9F4), // Example Light Blue
        onSecondary = Color.White,
        secondaryContainer = Color(0xFF0288D1),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFF29B6F6),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFF0277BD),
        onTertiaryContainer = Color.White,
        error = Color(0xFFCF6679), // Example Error Red
        onError = Color.Black,
        errorContainer = Color(0xFFB00020),
        onErrorContainer = Color.White,
        background = Color(0xFF121212), // Dark background
        onBackground = Color.White, // Text on dark background
        surface = Color(0xFF1E1E1E), // Card backgrounds, etc.
        onSurface = Color.White, // Text on surface
        surfaceVariant = Color(0xFF272727), // Slightly different surface
        onSurfaceVariant = Color(0xB3FFFFFF), // Subdued text (e.g., labels)
        outline = Color(0xFF404040) // Outlines, dividers
    )

    MaterialTheme(
        colorScheme = customColors
    ) {
        // --- Main Layout: Column ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {

            // --- Fixed Content Area ---
            Text(
                text = "Financial Statistics",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )

            val currentDateTime = remember { Date() }
            Text(
                text = SimpleDateFormat(
                    "MMMM dd, yyyy", // Corrected Pattern
                    Locale.getDefault()
                ).format(currentDateTime),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val timePeriods = StatisticsViewModel.TimePeriod.entries.toList()
                timePeriods.forEach { period ->
                    val labelText = when (period) {
                        StatisticsViewModel.TimePeriod.TODAY -> "Daily"
                        StatisticsViewModel.TimePeriod.WEEK -> "Weekly"
                        StatisticsViewModel.TimePeriod.MONTH -> "Monthly"
                        StatisticsViewModel.TimePeriod.ALL -> "All Time"
                    }
                    FilterChip(
                        selected = selectedPeriod == period,
                        onClick = { viewModel.updatePeriod(period) },
                        label = {
                            Text(
                                labelText,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryCard(
                    title = "Total Expenses",
                    value = "₹${transactionStatistics.totalExpense.roundToInt()}",
                    modifier = Modifier.weight(1f)
                )
                SummaryCard(
                    title = "Transactions",
                    value = transactionCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            // --- Horizontal Pager for Graphs ---
            val pagerState =
                rememberPagerState(pageCount = { 2 })

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(360.dp)
            ) { pageIndex ->
                when (pageIndex) {
                    // Page 0: Pie Chart
                    0 -> Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxSize()
                        ) {
                            Text(
                                "Expense Distribution",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            ExpenseDistributionChartMP(
                                data = transactionStatistics.categoryStats.map { (cat, stats) ->
                                    ExpenseCategory(cat, stats.totalExpense)
                                },
                                categoryColors = categoryColorsMap,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                    // Page 1: Bar/Line Chart
                    1 -> Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxHeight()
                        ) {
                            val chartTitle = when (selectedPeriod) {
                                StatisticsViewModel.TimePeriod.TODAY -> "Hourly Spending by Category"
                                StatisticsViewModel.TimePeriod.WEEK -> "Spending by Day of Week"
                                StatisticsViewModel.TimePeriod.MONTH -> "Spending by Week"
                                StatisticsViewModel.TimePeriod.ALL -> "Monthly Spending Trend"
                            }
                            Text(
                                chartTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            DailySpendingChartMP(
                                data = dailySpendingData,
                                selectedPeriod = selectedPeriod,
                                categoryColors = categoryColorsMap,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                }
            } // --- End HorizontalPager ---

            // --- Pager Indicators ---
            Row(
                Modifier
                    .height(20.dp)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pagerState.pageCount) { iteration ->
                    val color =
                        if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(CircleShape)
                            .background(color)
                            .size(8.dp)
                    )
                }
            } // --- End Pager Indicators ---

            Spacer(modifier = Modifier.height(16.dp))

        } // End Main Column
    } // End MaterialTheme
}

// --- SummaryCard composable ---
@Composable
fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// --- ExpenseCategory data class ---
data class ExpenseCategory(val name: String, val amount: Double)

// --- ExpenseDistributionChartMP ---
@Composable
fun ExpenseDistributionChartMP(
    data: List<ExpenseCategory>,
    categoryColors: Map<String, Color>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sortedData = remember(data) {
        data.filter { it.amount > 0 }.sortedByDescending { it.amount }
    }
    val chartDataAvailable = sortedData.isNotEmpty()

    val pieColors = remember(sortedData, categoryColors) {
        sortedData.map { categoryData ->
            (categoryColors[categoryData.name]
                ?: ChartColors.getDefaultColorByName(categoryData.name))
                .toArgb()
        }
    }

    Row(modifier = modifier.fillMaxSize()) {

        AndroidView(
            factory = { ctx ->
                PieChart(ctx).apply {
                    description.isEnabled = false
                    isRotationEnabled = true
                    isHighlightPerTapEnabled = true
                    setUsePercentValues(false)
                    isDrawHoleEnabled = true
                    setHoleColor(android.graphics.Color.TRANSPARENT)
                    holeRadius = 55f
                    transparentCircleRadius = 58f
                    setTransparentCircleColor(android.graphics.Color.TRANSPARENT)
                    setDrawCenterText(false)
                    legend.isEnabled = false
                    setDrawEntryLabels(false)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { pieChart ->
                if (chartDataAvailable) {
                    val entries = sortedData.map {
                        PieEntry(it.amount.toFloat(), it.name)
                    }
                    val dataSet = PieDataSet(entries, "Expenses").apply {
                        sliceSpace = 2f
                        colors = pieColors
                        setDrawValues(false)
                    }
                    pieChart.data = PieData(dataSet)
                } else {
                    pieChart.clear()
                    pieChart.data = null
                }
                pieChart.invalidate()
                pieChart.animateY(1000, Easing.EaseInOutQuad)
            },
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .padding(end = 8.dp)
        )

        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 4.dp)
        ) {
            if (chartDataAvailable) {
                sortedData.forEach { categoryData ->
                    val color =
                        categoryColors[categoryData.name] ?: ChartColors.getDefaultColorByName(
                            categoryData.name
                        )
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(color, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                categoryData.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "₹${categoryData.amount.roundToInt()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                Text(
                    "No expense data for this period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


// --- Helper functions for DailySpendingChartMP Setup ---
private fun setupCommonChartProperties(
    chartView: Chart<*>, // Use base Chart class
    axisTextColorArgb: Int
) {
    chartView.description.isEnabled = false
    chartView.legend.isEnabled = false // Disable initially, enable specifically for LineChart later

    chartView.setTouchEnabled(true)


    val bottomOffset = if (chartView is LineChart) 40f else 25f
    chartView.setExtraOffsets(10f, 15f, 10f, bottomOffset)
    chartView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    chartView.setNoDataText("No data available for this period.")
    chartView.setNoDataTextColor(axisTextColorArgb)
}

private fun setupXAxis(
    xAxis: XAxis,
    formatter: ValueFormatter,
    timePointsCount: Int,
    selectedPeriod: StatisticsViewModel.TimePeriod,
    axisColorArgb: Int,
    textColorArgb: Int,
    // Parameter to control label centering
) {
    xAxis.position = XAxis.XAxisPosition.BOTTOM
    xAxis.valueFormatter = formatter
    xAxis.granularity = 1f
    xAxis.isGranularityEnabled = true
    xAxis.textColor = textColorArgb
    xAxis.axisLineColor = axisColorArgb
    xAxis.gridColor = axisColorArgb
    xAxis.setDrawGridLines(false)
    xAxis.labelRotationAngle = -45f
    xAxis.setCenterAxisLabels(false)

    val labelCount = when (selectedPeriod) {
        StatisticsViewModel.TimePeriod.WEEK -> timePointsCount.coerceAtLeast(1)
        StatisticsViewModel.TimePeriod.MONTH -> timePointsCount.coerceAtLeast(1)
        else -> timePointsCount.coerceIn(2, 8)
    }
    xAxis.setLabelCount(labelCount, false)

    xAxis.axisMinimum = 0f - 0.5f
    xAxis.axisMaximum = timePointsCount.toFloat() - 0.5f
}

private fun setupLeftYAxis(
    leftAxis: YAxis,
    formatter: ValueFormatter,
    axisMaximum: Float,
    ticks: List<Double>,
    axisColorArgb: Int,
    textColorArgb: Int
) {
    leftAxis.axisMinimum = 0f
    leftAxis.axisMaximum = axisMaximum
    leftAxis.valueFormatter = formatter
    leftAxis.setLabelCount(ticks.size.coerceAtLeast(2).coerceAtMost(6), true)
    leftAxis.textColor = textColorArgb
    leftAxis.axisLineColor = axisColorArgb
    leftAxis.gridColor = axisColorArgb
    leftAxis.gridLineWidth = 0.5f
    leftAxis.setDrawGridLines(true)
    leftAxis.setDrawZeroLine(true)
    leftAxis.zeroLineColor = axisColorArgb
}


// --- DailySpendingChartMP (Final Version) ---
@Composable
fun DailySpendingChartMP(
    data: List<StatisticsViewModel.TimePointSpending>, // Raw data
    selectedPeriod: StatisticsViewModel.TimePeriod,
    categoryColors: Map<String, Color>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val processedData = rememberProcessedChartData(data, selectedPeriod)
    val timePoints = processedData.timePoints
    val dataMap = processedData.dataMap
    // *** Use the correct Y max calculation for line charts for ALL periods ***
    val actualMaxAmount = remember(dataMap) {
        dataMap.values.flatMap { it.values }.maxOrNull() ?: 0.0
    }
    val (niceAxisMax, yAxisTicks) = remember(actualMaxAmount) {
        calculateNiceAxisValues(actualMaxAmount, 5)
    }
    // *** End Y Max adjustment ***

    val timePointKeyToIndexMap = remember(timePoints) {
        timePoints.withIndex().associate { it.value.key to it.index }
    }
    val xAxisFormatter = remember(timePoints) { /* ... as before ... */
        object : ValueFormatter() {
            override fun getAxisLabel(
                value: Float, axis: AxisBase?
            ): String {
                val index = value.roundToInt().coerceIn(0, timePoints.size - 1)
                return timePoints.getOrNull(index)?.label ?: ""
            }
        }
    }
    val yAxisFormatter = remember { /* ... as before ... */
        object : ValueFormatter() {
            override fun getAxisLabel(
                value: Float, axis: AxisBase?
            ): String {
                return "₹${value.roundToInt()}"
            }
        }
    }
    val gridLineColorArgb = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f).toArgb()
    val axisTextColorArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    // --- Display LineChart (Always) ---
    AndroidView(
        // Factory always creates LineChart
        factory = { ctx -> LineChart(ctx) },
        modifier = modifier,
        update = { lineChart ->
            // Setup common properties
            setupCommonChartProperties(lineChart, axisTextColorArgb)

            // Apply interactions (specific to BarLineChartBase)
            lineChart.setTouchEnabled(true)
            lineChart.isDragEnabled = true
            lineChart.setScaleEnabled(true)
            lineChart.setPinchZoom(true)
            lineChart.isDoubleTapToZoomEnabled = true

            // Enable and configure legend
            lineChart.legend.apply {
                isEnabled = true
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                orientation = Legend.LegendOrientation.HORIZONTAL
                setDrawInside(false)
                textColor = axisTextColorArgb
                isWordWrapEnabled = true
                yOffset = 5f
                xEntrySpace = 10f
            }

            // Setup Axes (centerLabels is always false now)
            setupXAxis(
                lineChart.xAxis,
                xAxisFormatter,
                timePoints.size,
                selectedPeriod,
                gridLineColorArgb,
                axisTextColorArgb,
                // centerLabels parameter removed, defaults to false inside setupXAxis now
            )
            setupLeftYAxis(
                lineChart.axisLeft,
                yAxisFormatter,
                niceAxisMax.toFloat(), // Use the recalculated niceAxisMax for line charts
                yAxisTicks,
                gridLineColorArgb,
                axisTextColorArgb
            )
            lineChart.axisRight.isEnabled = false

            // --- LineChart Specific Data & Setup ---
            if (timePoints.isEmpty() || dataMap.isEmpty() || dataMap.values.all { it.values.all { amount -> amount == 0.0 } }) {
                lineChart.clear()
                lineChart.data = null
                lineChart.invalidate()
            } else {
                val lineDataSets = mutableListOf<ILineDataSet>()
                val categories = dataMap.keys.toList().sorted() // Sort categories for consistent legend order

                categories.forEach { category ->
                    val timeMap = dataMap[category] ?: emptyMap()
                    val entries = mutableListOf<Entry>()
                    // Create entries for ALL time points using the index map
                    timePoints.forEachIndexed { index, timePointInfo ->
                        val amount = timeMap[timePointInfo.key] ?: 0.0
                        entries.add(Entry(index.toFloat(), amount.toFloat()))
                    }

                    val categoryColor =
                        categoryColors[category] ?: ChartColors.getDefaultColorByName(category)
                    val categoryColorArgb = categoryColor.toArgb()

                    val dataSet = LineDataSet(entries, category).apply {
                        color = categoryColorArgb
                        lineWidth = 1.8f
                        circleRadius = 3.5f
                        setCircleColor(categoryColorArgb)
                        setDrawCircleHole(false)
                        valueTextColor = axisTextColorArgb
                        setDrawValues(false)
                        mode = LineDataSet.Mode.CUBIC_BEZIER
                        setDrawFilled(false)
                        highLightColor = categoryColorArgb
                        isHighlightEnabled = true
                        setDrawHorizontalHighlightIndicator(false)
                        highlightLineWidth = 1.5f
                    }
                    lineDataSets.add(dataSet)
                }

                if (lineDataSets.isNotEmpty()) {
                    lineChart.data = LineData(lineDataSets)
                    lineChart.invalidate() // Refresh chart
                    lineChart.animateX(600) // Animate lines
                } else {
                    lineChart.clear()
                    lineChart.data = null
                    lineChart.invalidate()
                }
            }
        } // End update lambda
    ) // End AndroidView
}