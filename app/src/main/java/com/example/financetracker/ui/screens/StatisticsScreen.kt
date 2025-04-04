package com.example.financetracker.ui.screens

// Import Pager related components
// import android.app.ProgressDialog.show // Often unused in modern Compose, consider removing if not needed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.DateRange // Or DateRange, EditCalendar etc.
// Import IconButton and Icon from M3 if not already done
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
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
import androidx.compose.material.icons.Icons
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
import com.github.mikephil.charting.charts.BarChart // Keep if you plan BarChart variants
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.charts.Chart // Base chart class
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.components.Legend // Added import
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet // Keep if using BarChart
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.utils.ColorTemplate // Keep if needed
import com.github.mikephil.charting.formatter.PercentFormatter // Keep if needed for PieChart
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.listener.OnChartValueSelectedListener // Keep if adding interaction
import com.github.mikephil.charting.highlight.Highlight // Keep if adding interaction


import androidx.compose.ui.graphics.toArgb // Needed for converting Compose Color to Android Color Int
import androidx.compose.ui.platform.LocalContext
import com.github.mikephil.charting.components.AxisBase
import java.text.DateFormatSymbols // Corrected import

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
// import java.util.concurrent.TimeUnit // Added import (potentially useful, but maybe unused now)

// --- Custom Picker Imports (Ensure these exist in your project) ---


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

// --- Helper Function for Nice Numbers (Unchanged) ---
fun calculateNiceAxisValues(actualMax: Double, maxTicks: Int = 5): Pair<Double, List<Double>> {
    if (actualMax <= 0) {
        val defaultMax = 100.0; val safeMaxTicks = maxTicks.coerceAtLeast(1)
        val defaultTick = defaultMax / safeMaxTicks
        val defaultTicks = List(safeMaxTicks + 1) { i -> (i * defaultTick).coerceAtMost(defaultMax) }
        return Pair(defaultMax, defaultTicks.distinct())
    }
    val exponent = floor(log10(actualMax)); val fraction = actualMax / (10.0).pow(exponent)
    val niceFraction = when {
        fraction <= 1.0 -> 1.0; fraction <= 2.0 -> 2.0; fraction <= 2.5 -> 2.5
        fraction <= 5.0 -> 5.0; else -> 10.0
    }
    val niceTick = niceFraction * (10.0).pow(exponent)
    var axisMax = ceil(actualMax / niceTick) * niceTick
    if (axisMax <= actualMax) { axisMax += niceTick }
    if (abs(axisMax - actualMax) < niceTick * 0.01) { axisMax += niceTick }
    if (axisMax == 0.0 && actualMax > 0) { axisMax = niceTick }
    axisMax = axisMax.coerceAtLeast(actualMax)
    if (axisMax > actualMax + niceTick * 1.1) {
        val potentialLowerMax = floor(actualMax / niceTick) * niceTick + niceTick
        if (potentialLowerMax >= actualMax) { axisMax = potentialLowerMax }
    }
    val ticks = mutableListOf<Double>(); var currentTick = 0.0
    val safeNiceTick = if (niceTick <= 0) (10.0).pow(exponent).coerceAtLeast(1.0) else niceTick
    while (currentTick <= axisMax * 1.001 && ticks.size < maxTicks * 3) {
        ticks.add(currentTick); currentTick += safeNiceTick; if (safeNiceTick <= 0) break
    }
    if (ticks.isEmpty() || (ticks.last() < axisMax && abs(ticks.last() - axisMax) > safeNiceTick * 0.1)) {
        ticks.add(axisMax)
    } else if (ticks.last() > axisMax) {
        if (ticks.size > 1 && ticks.last() > ticks[ticks.lastIndex - 1] + safeNiceTick * 0.5) {
            ticks[ticks.lastIndex] = axisMax
        } else if (ticks.size == 1) { ticks[ticks.lastIndex] = axisMax }
    }
    if (ticks.firstOrNull() != 0.0) { ticks.add(0, 0.0) }
    var finalTicks = ticks.distinct().sorted()
    if (finalTicks.size > maxTicks + 1) {
        val keepIndices = mutableSetOf(0, finalTicks.lastIndex)
        val step = finalTicks.size.toDouble() / (maxTicks - 1).coerceAtLeast(1)
        for (i in 1 until maxTicks) { keepIndices.add((i * step).roundToInt().coerceIn(0, finalTicks.lastIndex)) }
        finalTicks = finalTicks.filterIndexed { index, _ -> index in keepIndices }
    }
    if (finalTicks.isEmpty()) { finalTicks = listOf(0.0, axisMax.coerceAtLeast(1.0)) }
    else if (finalTicks.size == 1 && finalTicks[0] == 0.0) { finalTicks = listOf(0.0, axisMax.coerceAtLeast(1.0)) }
    return Pair(axisMax, finalTicks.distinct().sorted())
}


// --- Data Structures for Processed Chart Data (Unchanged) ---
sealed class TimePointKey {
    data class Hour(val date: Date) : TimePointKey() // Key for Today
    data class DayOfWeek(val dayIndex: Int) : TimePointKey() // Key for Week (0=Mon, 6=Sun)
    data class WeekOfMonth(val intervalIndex: Int) : TimePointKey() // Key for Month (indices 0-5 now)
    data class MonthYear(val date: Date) : TimePointKey() // Key for All Time (first day of month)

    fun getComparableValue(): Long = when (this) {
        is Hour -> date.time
        is DayOfWeek -> dayIndex.toLong()
        is WeekOfMonth -> intervalIndex.toLong()
        is MonthYear -> date.time
    }
}

data class TimePointInfo(
    val key: TimePointKey,
    val label: String
)

data class ProcessedChartData(
    val timePoints: List<TimePointInfo> = emptyList(),
    val dataMap: Map<String, Map<TimePointKey, Double>> = emptyMap(),
    val yAxisMax: Double = 100.0,
    val yAxisTicks: List<Double> = listOf(0.0, 25.0, 50.0, 75.0, 100.0)
)

// --- Helper Functions for Data Processing ---

// ** Updated getTimePointKey **
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
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val keyIndex = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - 2
            TimePointKey.DayOfWeek(keyIndex.coerceIn(0, 6))
        }

        // --- MODIFIED MONTH Case ---
        StatisticsViewModel.TimePeriod.MONTH -> {
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            // Map day of month to 6 buckets (indices 0-5)
            val keyIndex = when {
                dayOfMonth == 1 -> 0    // Bucket 0: Day 1
                dayOfMonth <= 7 -> 1    // Bucket 1: Days 2-7
                dayOfMonth <= 14 -> 2   // Bucket 2: Days 8-14
                dayOfMonth <= 21 -> 3   // Bucket 3: Days 15-21
                dayOfMonth <= 28 -> 4   // Bucket 4: Days 22-28
                else -> 5               // Bucket 5: Days 29+
            }
            TimePointKey.WeekOfMonth(keyIndex)
        }
        // --- End Modification ---

        StatisticsViewModel.TimePeriod.ALL -> {
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.clear(Calendar.HOUR_OF_DAY); calendar.clear(Calendar.MINUTE)
            calendar.clear(Calendar.SECOND); calendar.clear(Calendar.MILLISECOND)
            TimePointKey.MonthYear(calendar.time)
        }
    }
}

// ** Updated generateTimePoints **
private fun generateTimePoints(
    data: List<StatisticsViewModel.TimePointSpending>,
    period: StatisticsViewModel.TimePeriod,
    targetMonth: YearMonth? = null // Pass the specific month being viewed
    // Add other optional targets (LocalDate, Int year) if needed
): List<TimePointInfo> {
    val locale = Locale.getDefault()
    return when (period) {
        StatisticsViewModel.TimePeriod.TODAY -> {
            if (data.isEmpty()) { return emptyList() }
            val datesInData = data.map { it.timePointDate }
            val minDate = datesInData.minByOrNull { it.time } ?: return emptyList()
            val maxDate = datesInData.maxByOrNull { it.time } ?: return emptyList()
            val calendar = Calendar.getInstance(); calendar.time = minDate
            calendar.clear(Calendar.MINUTE); calendar.clear(Calendar.SECOND); calendar.clear(Calendar.MILLISECOND)
            val startHourMillis = calendar.timeInMillis
            calendar.time = maxDate
            calendar.clear(Calendar.MINUTE); calendar.clear(Calendar.SECOND); calendar.clear(Calendar.MILLISECOND)
            val endHourMillis = calendar.timeInMillis
            val timePoints = mutableListOf<TimePointInfo>()
            val currentHourCal = Calendar.getInstance().apply { timeInMillis = startHourMillis }
            val format = SimpleDateFormat("HH:mm", locale) // Consider "ha" for AM/PM
            while (currentHourCal.timeInMillis <= endHourMillis) {
                val currentHourDate = currentHourCal.time
                val key = TimePointKey.Hour(currentHourDate)
                val label = format.format(currentHourDate)
                timePoints.add(TimePointInfo(key, label))
                currentHourCal.add(Calendar.HOUR_OF_DAY, 1)
                if (timePoints.size > 100) { Log.e(SCREEN_TAG, "generateTimePoints(TODAY): Excessive loop"); break }
            }
            if (timePoints.isEmpty() && data.isNotEmpty()) { // Handle single hour case if loop didn't run
                val singleHourDate = Date(startHourMillis)
                val key = TimePointKey.Hour(singleHourDate)
                val label = format.format(singleHourDate)
                timePoints.add(TimePointInfo(key, label))
            }
            timePoints
        }
        StatisticsViewModel.TimePeriod.WEEK -> {
            val symbols = DateFormatSymbols.getInstance(locale)
            val shortWeekdays = symbols.shortWeekdays // Sunday=1, Monday=2,... Saturday=7
            val dayIndexToName = (0..6).associateWith { index -> // Our index: Mon=0..Sun=6
                val calendarDayConstant = when (index) {
                    0 -> Calendar.MONDAY; 1 -> Calendar.TUESDAY; 2 -> Calendar.WEDNESDAY;
                    3 -> Calendar.THURSDAY; 4 -> Calendar.FRIDAY; 5 -> Calendar.SATURDAY;
                    6 -> Calendar.SUNDAY; else -> Calendar.MONDAY // Fallback
                }
                // Check bounds for shortWeekdays array
                if (calendarDayConstant >= Calendar.SUNDAY && calendarDayConstant <= Calendar.SATURDAY) {
                    shortWeekdays[calendarDayConstant]?.takeIf { it.isNotEmpty() } ?: "??"
                } else { "??" }
            }
            (0..6).map { index -> TimePointInfo(TimePointKey.DayOfWeek(index), dayIndexToName[index] ?: "??") }
        }

        // --- MODIFIED MONTH Case ---
        StatisticsViewModel.TimePeriod.MONTH -> {
            // Determine the month to use: Passed target or fallback
            val monthToUse = targetMonth ?: run {
                data.firstOrNull()?.timePointDate?.let { date ->
                    val instant = date.toInstant(); val zone = ZoneId.systemDefault()
                    YearMonth.from(instant.atZone(zone).toLocalDate())
                } ?: YearMonth.now() // Fallback if targetMonth null AND data empty
            }

            val lastDay = monthToUse.lengthOfMonth()
            // Create the 6 specific labels requested: "1", "7", "14", "21", "28", "LastDay"
            val baseLabels = listOf("1", "7", "14", "21", "28", lastDay.toString())

            // Generate 6 TimePointInfo objects, using indices 0-5 for the keys
            val finalLabels: List<String>
            val numPoints: Int

            if (lastDay <= 28) { // Handles non-leap February
                finalLabels = baseLabels
                numPoints = 5
            } else { // Handles leap February and all other months
                finalLabels = baseLabels + lastDay.toString() // Add the actual last day label
                numPoints = 6
            }

            // Generate the correct number of TimePointInfo objects
            (0 until numPoints).map { index ->
                TimePointInfo(TimePointKey.WeekOfMonth(index), finalLabels[index])
            }
        }
        // --- End Modification ---

        StatisticsViewModel.TimePeriod.ALL -> {
            if (data.isEmpty()) return emptyList()
            val distinctMonths = data.map { getTimePointKey(it.timePointDate, period) }
                .filterIsInstance<TimePointKey.MonthYear>()
                .distinctBy { it.date.time }
                .sortedBy { it.getComparableValue() }
            val format = SimpleDateFormat("MMM yy", locale)
            distinctMonths.map { key -> TimePointInfo(key, format.format(key.date)) }
        }
    }
}


// ** Updated rememberProcessedChartData **
@Composable
private fun rememberProcessedChartData(
    data: List<StatisticsViewModel.TimePointSpending>,
    selectedPeriod: StatisticsViewModel.TimePeriod,
    // *** ADD selectedSpecificMonthYear parameter ***
    selectedSpecificMonthYear: YearMonth
    // Add others (selectedSpecificDate, selectedSpecificYear) if needed
): ProcessedChartData {

    // Determine the target month IF the period is MONTH
    val targetMonth = if (selectedPeriod == StatisticsViewModel.TimePeriod.MONTH) {
        selectedSpecificMonthYear
    } else {
        null
    }

    // *** ADD targetMonth (derived from selectedSpecificMonthYear) to the remember key ***
    return remember(data, selectedPeriod, targetMonth) {

        // *** Pass the determined targetMonth to generateTimePoints ***
        val timePoints = generateTimePoints(data, selectedPeriod, targetMonth)

        // Handle cases where timePoints might still be empty
        if (timePoints.isEmpty()) {
            Log.w(SCREEN_TAG, "rememberProcessedChartData: generateTimePoints returned empty for $selectedPeriod. Data size: ${data.size}. TargetMonth: $targetMonth")
            val (niceAxisMax, yAxisTicks) = calculateNiceAxisValues(0.0, 5)
            return@remember ProcessedChartData(emptyList(), emptyMap(), niceAxisMax, yAxisTicks)
        }

        // --- Data processing logic ---
        // 2. Re-group data
        val dataMap = mutableMapOf<String, MutableMap<TimePointKey, Double>>()
        data.forEach { spending ->
            val key = getTimePointKey(spending.timePointDate, selectedPeriod)
            if (timePoints.any { it.key == key }) { // Check if key is valid for generated points
                val categoryMap = dataMap.getOrPut(spending.category) { mutableMapOf() }
                categoryMap[key] = categoryMap.getOrDefault(key, 0.0) + spending.amount
            } else {
                Log.w(SCREEN_TAG, "Data point key $key for $selectedPeriod not in generated timePoints: ${timePoints.map { it.key }}")
            }
        }

        // Ensure all defined time points exist for each category
        val finalDataMap = dataMap.mapValues { (_, categoryTimeMap) ->
            timePoints.associate { timePointInfo ->
                timePointInfo.key to (categoryTimeMap[timePointInfo.key] ?: 0.0)
            }
        }

        // 3. Calculate Max Y value
        val actualMaxAmount = finalDataMap.values.flatMap { it.values }.maxOrNull() ?: 0.0
        val (niceAxisMax, yAxisTicks) = calculateNiceAxisValues(actualMaxAmount, 5) // Use 5 ticks for Y axis

        ProcessedChartData(timePoints, finalDataMap, niceAxisMax, yAxisTicks)
    }
}


// --- Main Statistics Screen Composable ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel) {
    // --- State Collection ---
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val selectedSpecificDate by viewModel.selectedSpecificDate.collectAsStateWithLifecycle()
    val selectedSpecificMonthYear by viewModel.selectedSpecificMonthYear.collectAsStateWithLifecycle() // Used for MONTH target
    val selectedSpecificYear by viewModel.selectedSpecificYear.collectAsStateWithLifecycle()       // Used for ALL target (if needed)
    val transactionStatistics by viewModel.transactionStatistics.collectAsStateWithLifecycle()
    val dailySpendingData by viewModel.spendingDataByTimePoint.collectAsStateWithLifecycle() // Raw data from VM
    val transactionCount by viewModel.transactionCount.collectAsStateWithLifecycle()
    val categoryColorsMap by viewModel.categoryColorsMap.collectAsStateWithLifecycle()

    var showDatePickerDialog by remember { mutableStateOf(false) }
    var showMonthYearPickerDialog by remember { mutableStateOf(false) }
    var showYearPickerDialog by remember { mutableStateOf(false) }

    // --- Theme --- (Using your defined customColors)
    val customColors = darkColorScheme(
        primary = Color(0xFF2196F3), onPrimary = Color.White, primaryContainer = Color(0xFF1565C0), onPrimaryContainer = Color.White,
        secondary = Color(0xFF03A9F4), onSecondary = Color.White, secondaryContainer = Color(0xFF0288D1), onSecondaryContainer = Color.White,
        tertiary = Color(0xFF29B6F6), onTertiary = Color.White, tertiaryContainer = Color(0xFF0277BD), onTertiaryContainer = Color.White,
        error = Color(0xFFCF6679), onError = Color.Black, errorContainer = Color(0xFFB00020), onErrorContainer = Color.White,
        background = Color(0xFF121212), onBackground = Color.White,
        surface = Color(0xFF1E1E1E), onSurface = Color.White,
        surfaceVariant = Color(0xFF272727), onSurfaceVariant = Color(0xB3FFFFFF),
        outline = Color(0xFF404040)
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

            val currentDateTime = remember { Date() } // Consider using Clock.System.now() for testability
            Text(
                text = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(currentDateTime),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // --- Time Period Filter Chips ---
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
                        label = { Text(labelText, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            // --- Date/Month/Year Selection Display and Picker Trigger ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val selectionText = when (selectedPeriod) {
                    StatisticsViewModel.TimePeriod.TODAY -> "Date: ${selectedSpecificDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
                    StatisticsViewModel.TimePeriod.WEEK -> "Period: Last 7 Days" // Or specific week range
                    StatisticsViewModel.TimePeriod.MONTH -> "Month: ${selectedSpecificMonthYear.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))}"
                    StatisticsViewModel.TimePeriod.ALL -> "Year: $selectedSpecificYear"
                }
                Text(
                    text = selectionText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (selectedPeriod != StatisticsViewModel.TimePeriod.WEEK) { // No picker for WEEK currently
                    IconButton(
                        onClick = {
                            when (selectedPeriod) {
                                StatisticsViewModel.TimePeriod.TODAY -> showDatePickerDialog = true
                                StatisticsViewModel.TimePeriod.MONTH -> showMonthYearPickerDialog = true
                                StatisticsViewModel.TimePeriod.ALL -> showYearPickerDialog = true
                                StatisticsViewModel.TimePeriod.WEEK -> {} // Should not happen
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = when (selectedPeriod) { // Accessibility
                                StatisticsViewModel.TimePeriod.TODAY -> "Change Date"
                                StatisticsViewModel.TimePeriod.MONTH -> "Change Month"
                                StatisticsViewModel.TimePeriod.ALL -> "Change Year"
                                else -> null
                            },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp)) // Placeholder for alignment
                }
            }

            // --- Summary Cards ---
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
            val pagerState = rememberPagerState(pageCount = { 2 }) // 0: Pie, 1: Line

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    // Consider adjusting height or making it dynamic/weighted
                    .height(360.dp)
            ) { pageIndex ->
                when (pageIndex) {
                    // Page 0: Pie Chart
                    0 -> Card(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                            Text("Expense Distribution", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                            ExpenseDistributionChartMP(
                                data = transactionStatistics.categoryStats.map { (cat, stats) ->
                                    ExpenseCategory(cat, stats.totalExpense)
                                },
                                categoryColors = categoryColorsMap,
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        }
                    }
                    // Page 1: Line Chart
                    1 -> Card(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxHeight()) {
                            val chartTitle = when (selectedPeriod) {
                                StatisticsViewModel.TimePeriod.TODAY -> "Hourly Spending"
                                StatisticsViewModel.TimePeriod.WEEK -> "Daily Spending"
                                StatisticsViewModel.TimePeriod.MONTH -> "Spending Over Month" // Updated Title
                                StatisticsViewModel.TimePeriod.ALL -> "Monthly Spending Trend"
                            }
                            Text(chartTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

                            // *** Call rememberProcessedChartData HERE ***
                            val processedSpendingData = rememberProcessedChartData(
                                data = dailySpendingData, // Raw data list from VM
                                selectedPeriod = selectedPeriod,
                                selectedSpecificMonthYear = selectedSpecificMonthYear // Pass context
                                // Pass selectedSpecificDate/Year if needed for other periods
                            )

                            // *** Pass processed data to DailySpendingChartMP ***
                            DailySpendingChartMP(
                                processedChartData = processedSpendingData,
                                selectedPeriod = selectedPeriod, // Keep for axis setup etc. if needed
                                categoryColors = categoryColorsMap,
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        }
                    }
                }
            } // --- End HorizontalPager ---

            // --- Pager Indicators ---
            Row(
                Modifier.height(20.dp).fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pagerState.pageCount) { iteration ->
                    val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    Box(
                        modifier = Modifier.padding(horizontal = 4.dp).clip(CircleShape).background(color).size(8.dp)
                    )
                }
            } // --- End Pager Indicators ---

            Spacer(modifier = Modifier.height(16.dp))

        } // End Main Column

        // --- Date/Month/Year Picker Dialogs ---
        val context = LocalContext.current

        // Date Picker
        if (showDatePickerDialog) {
            val currentSelection = selectedSpecificDate
            val nowCalendar = Calendar.getInstance()
            android.app.DatePickerDialog(
                context,
                { _, year, month, dayOfMonth -> // month is 0-indexed
                    viewModel.updateSelectedDate(LocalDate.of(year, month + 1, dayOfMonth))
                    showDatePickerDialog = false
                },
                currentSelection.year,
                currentSelection.monthValue - 1, // Adjust month for dialog
                currentSelection.dayOfMonth
            ).apply {
                datePicker.maxDate = nowCalendar.timeInMillis // Prevent future dates
                setOnDismissListener { showDatePickerDialog = false }
                show()
            }
            LaunchedEffect(Unit) { /* Keep dialog showing on recomposition */ }
        }

        // MonthYear Picker (Your Custom Composable)
        MonthPicker( // Ensure this composable exists and is imported
            visible = showMonthYearPickerDialog,
            currentMonth = selectedSpecificMonthYear.monthValue - 1, // Adjust for 0-indexed picker
            currentYear = selectedSpecificMonthYear.year,
            confirmButtonCLicked = { month, year -> // month is 1-indexed from picker
                viewModel.updateSelectedMonthYear(YearMonth.of(year, month))
                showMonthYearPickerDialog = false
            },
            cancelClicked = { showMonthYearPickerDialog = false }
        )

        // Year Picker (Your Custom Composable)
        YearPicker( // Ensure this composable exists and is imported
            visible = showYearPickerDialog,
            currentYear = selectedSpecificYear,
            // minYear = ..., maxYear = ..., // Optional constraints
            confirmButtonClicked = { year ->
                viewModel.updateSelectedYear(year)
                showYearPickerDialog = false
            },
            cancelClicked = { showYearPickerDialog = false }
        )

    } // End MaterialTheme
}

// --- SummaryCard composable (Unchanged) ---
@Composable
fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier, shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// --- ExpenseCategory data class (Unchanged) ---
data class ExpenseCategory(val name: String, val amount: Double)

// --- ExpenseDistributionChartMP (Unchanged) ---
@Composable
fun ExpenseDistributionChartMP(
    data: List<ExpenseCategory>,
    categoryColors: Map<String, Color>,
    modifier: Modifier = Modifier
) {
    // val context = LocalContext.current // Context not strictly needed here unless for resources
    val sortedData = remember(data) { data.filter { it.amount > 0 }.sortedByDescending { it.amount } }
    val chartDataAvailable = sortedData.isNotEmpty()
    val pieColors = remember(sortedData, categoryColors) {
        sortedData.map { (categoryColors[it.name] ?: ChartColors.getDefaultColorByName(it.name)).toArgb() }
    }

    Row(modifier = modifier.fillMaxSize()) {
        // Pie Chart View
        AndroidView(
            factory = { ctx ->
                PieChart(ctx).apply {
                    description.isEnabled = false; isRotationEnabled = true; isHighlightPerTapEnabled = true
                    setUsePercentValues(false); isDrawHoleEnabled = true; setHoleColor(android.graphics.Color.TRANSPARENT)
                    holeRadius = 55f; transparentCircleRadius = 58f; setTransparentCircleColor(android.graphics.Color.TRANSPARENT)
                    setDrawCenterText(false); legend.isEnabled = false; setDrawEntryLabels(false)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { pieChart ->
                if (chartDataAvailable) {
                    val entries = sortedData.map { PieEntry(it.amount.toFloat(), it.name) }
                    val dataSet = PieDataSet(entries, "Expenses").apply {
                        sliceSpace = 2f; colors = pieColors; setDrawValues(false)
                    }
                    pieChart.data = PieData(dataSet)
                } else {
                    pieChart.clear(); pieChart.data = null
                }
                pieChart.invalidate(); pieChart.animateY(1000, Easing.EaseInOutQuad)
            },
            modifier = Modifier.weight(0.6f).fillMaxHeight().padding(end = 8.dp)
        )

        // Legend Column
        Column(
            modifier = Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 4.dp)
        ) {
            if (chartDataAvailable) {
                sortedData.forEach { categoryData ->
                    val color = categoryColors[categoryData.name] ?: ChartColors.getDefaultColorByName(categoryData.name)
                    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(categoryData.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("₹${categoryData.amount.roundToInt()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Text("No expense data for this period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


// --- Helper functions for Chart Setup (Unchanged, but check setupXAxis for label count) ---
private fun setupCommonChartProperties(chartView: Chart<*>, axisTextColorArgb: Int) {
    chartView.description.isEnabled = false
    chartView.legend.isEnabled = false // Keep disabled here, enable specifically in LineChart update
    chartView.setTouchEnabled(true)
    val bottomOffset = if (chartView is LineChart) 40f else 25f // Allow space for legend/labels
    chartView.setExtraOffsets(10f, 15f, 10f, bottomOffset)
    chartView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    chartView.setNoDataText("No data available for this period.")
    chartView.setNoDataTextColor(axisTextColorArgb)
}

private fun setupXAxis(
    xAxis: XAxis, formatter: ValueFormatter, timePointsCount: Int,
    selectedPeriod: StatisticsViewModel.TimePeriod, axisColorArgb: Int, textColorArgb: Int
) {
    xAxis.position = XAxis.XAxisPosition.BOTTOM
    xAxis.valueFormatter = formatter
    xAxis.granularity = 1f
    xAxis.isGranularityEnabled = true
    xAxis.textColor = textColorArgb
    xAxis.axisLineColor = axisColorArgb
    xAxis.gridColor = axisColorArgb // Usually grid lines are off for XAxis or very subtle
    xAxis.setDrawGridLines(false) // Common preference
    xAxis.labelRotationAngle = -45f
    xAxis.setCenterAxisLabels(false) // Keep false for typical time series

    // Adjust label count logic - maybe just rely on granularity? Or set explicitly?
    // If timePointsCount is now 6 for MONTH, ensure this displays reasonably.
    // Setting count forcefully might skip labels. Relying on granularity might be better.
    // Let's keep the previous logic for now, but be aware it might need tuning for 6 labels.
    val labelCount = when (selectedPeriod) {
        // Explicitly handle 6 points for month, or let library decide?
        StatisticsViewModel.TimePeriod.MONTH -> timePointsCount.coerceAtLeast(1) // Show all 6 if possible
        StatisticsViewModel.TimePeriod.WEEK -> timePointsCount.coerceAtLeast(1)
        else -> timePointsCount.coerceIn(2, 8) // Default range
    }
    xAxis.setLabelCount(labelCount, false) // 'false' means it's not strict

    xAxis.axisMinimum = 0f - 0.5f // Add padding at start/end
    xAxis.axisMaximum = timePointsCount.toFloat() - 0.5f
}

private fun setupLeftYAxis(
    leftAxis: YAxis, formatter: ValueFormatter, axisMaximum: Float, ticks: List<Double>,
    axisColorArgb: Int, textColorArgb: Int
) {
    leftAxis.axisMinimum = 0f
    leftAxis.axisMaximum = axisMaximum // Use calculated nice max
    leftAxis.valueFormatter = formatter
    // Use the number of generated nice ticks, ensuring at least 2, max around 6
    leftAxis.setLabelCount(ticks.size.coerceIn(2, 6), true) // 'true' tries to force count
    leftAxis.textColor = textColorArgb
    leftAxis.axisLineColor = axisColorArgb
    leftAxis.gridColor = axisColorArgb // Grid lines for Y axis are common
    leftAxis.gridLineWidth = 0.5f
    leftAxis.setDrawGridLines(true)
    leftAxis.setDrawZeroLine(true)
    leftAxis.zeroLineColor = axisColorArgb // Emphasize zero line
}


// ** Updated DailySpendingChartMP - Accepts ProcessedChartData **
@Composable
fun DailySpendingChartMP(
    // *** CHANGE parameter from raw data to ProcessedChartData ***
    processedChartData: ProcessedChartData,
    selectedPeriod: StatisticsViewModel.TimePeriod, // Keep if needed
    categoryColors: Map<String, Color>,
    modifier: Modifier = Modifier
) {
    // val context = LocalContext.current // Context usually not needed here

    // *** Directly use the passed-in processedChartData ***
    val timePoints = processedChartData.timePoints
    val dataMap = processedChartData.dataMap
    val niceAxisMax = processedChartData.yAxisMax
    val yAxisTicks = processedChartData.yAxisTicks

    // Determine if there's effectively no data to plot
    val isDataEffectivelyEmpty = timePoints.isEmpty() // Primary check

    // Remember formatters based on the final timePoints
    val xAxisFormatter = remember(timePoints) {
        object : ValueFormatter() {
            override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                val index = value.roundToInt().coerceIn(0, timePoints.size - 1)
                return timePoints.getOrNull(index)?.label ?: "" // Uses new labels
            }
        }
    }
    val yAxisFormatter = remember {
        object : ValueFormatter() {
            override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                return "₹${value.roundToInt()}"
            }
        }
    }
    val gridLineColorArgb = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f).toArgb()
    val axisTextColorArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()


    // --- AndroidView for LineChart ---
    AndroidView(
        factory = { ctx -> LineChart(ctx) },
        modifier = modifier,
        update = { lineChart ->
            // Setup common properties
            setupCommonChartProperties(lineChart, axisTextColorArgb)

            // Interactions
            lineChart.setTouchEnabled(true); lineChart.isDragEnabled = true; lineChart.setScaleEnabled(true)
            lineChart.setPinchZoom(true); lineChart.isDoubleTapToZoomEnabled = true

            // Legend setup
            lineChart.legend.apply {
                isEnabled = true; verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                orientation = Legend.LegendOrientation.HORIZONTAL; setDrawInside(false)
                textColor = axisTextColorArgb; isWordWrapEnabled = true; yOffset = 5f; xEntrySpace = 10f
            }

            // Setup Axes using processed data and formatters
            setupXAxis(
                lineChart.xAxis, xAxisFormatter, timePoints.size, selectedPeriod,
                gridLineColorArgb, axisTextColorArgb
            )
            setupLeftYAxis(
                lineChart.axisLeft, yAxisFormatter, niceAxisMax.toFloat(), yAxisTicks,
                gridLineColorArgb, axisTextColorArgb
            )
            lineChart.axisRight.isEnabled = false

            // --- LineChart Data Setup ---
            if (isDataEffectivelyEmpty || dataMap.isEmpty() || dataMap.values.all { it.values.all { amount -> amount == 0.0 } }) {
                lineChart.clear()
                lineChart.data = null
            } else {
                val lineDataSets = mutableListOf<ILineDataSet>()
                val categories = dataMap.keys.toList().sorted() // Consistent legend order

                categories.forEach { category ->
                    val timeMap = dataMap[category] ?: emptyMap()
                    val entries = mutableListOf<Entry>()
                    // Create entries for ALL time points using the index
                    timePoints.forEachIndexed { index, timePointInfo ->
                        val amount = timeMap[timePointInfo.key] ?: 0.0
                        // Use index for X value, amount for Y value
                        entries.add(Entry(index.toFloat(), amount.toFloat()))
                    }

                    // Create dataset for this category
                    val categoryColor = categoryColors[category] ?: ChartColors.getDefaultColorByName(category)
                    val dataSet = LineDataSet(entries, category).apply {
                        val colorArgb = categoryColor.toArgb()
                        color = colorArgb; lineWidth = 1.8f; circleRadius = 3.5f
                        setCircleColor(colorArgb); setDrawCircleHole(false)
                        valueTextColor = axisTextColorArgb; setDrawValues(false) // Hide values on points
                        mode = LineDataSet.Mode.CUBIC_BEZIER // Smooth lines
                        setDrawFilled(false) // Optional: Fill area under line
                        highLightColor = colorArgb; isHighlightEnabled = true
                        setDrawHorizontalHighlightIndicator(false); highlightLineWidth = 1.5f
                    }
                    lineDataSets.add(dataSet)
                }

                // Set data if datasets were created
                if (lineDataSets.isNotEmpty()) {
                    lineChart.data = LineData(lineDataSets)
                    lineChart.animateX(600) // Animate lines drawing
                } else { // Should not happen if !isDataEffectivelyEmpty, but safety check
                    lineChart.clear()
                    lineChart.data = null
                }
            }
            lineChart.invalidate() // Refresh the chart view
        } // End update lambda
    ) // End AndroidView
}