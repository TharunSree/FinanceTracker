package com.example.financetracker.ui.screens

import android.graphics.Paint // Import Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll // Keep if needed for chips
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState // Keep if needed for chips
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas // Import nativeCanvas
import androidx.compose.ui.platform.LocalDensity // Import LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp // Import sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.financetracker.viewmodel.StatisticsViewModel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit // For date checks
import kotlin.math.roundToInt

// Default colors used as fallback
object ChartColors {
    // You can keep these if used elsewhere, or remove if only defaults are needed
    val Primary = Color(0xFF1976D2)
    val Secondary = Color(0xFF03A9F4)
    val Error = Color(0xFFE57373)

    // --- REVISED Default Category Colors List ---
    // A larger list with more distinct colors
    val DefaultCategoryColors = listOf(
        Color(0xFFE6194B), // Red
        Color(0xFF3CB44B), // Green
        Color(0xFFFFE119), // Yellow
        Color(0xFF4363D8), // Blue
        Color(0xFFF58231), // Orange
        Color(0xFF911EB4), // Purple
        Color(0xFF46F0F0), // Cyan
        Color(0xFFF032E6), // Magenta
        Color(0xFFBCF60C), // Lime
        Color(0xFFFABEBE), // Pink
        Color(0xFF008080), // Teal
        Color(0xFFE6BEFF), // Lavender
        Color(0xFF9A6324), // Brown
        Color(0xFFFFFAC8), // Beige (Might be too light on light backgrounds)
        Color(0xFF800000), // Maroon
        Color(0xFFAAFFC3), // Mint
        Color(0xFF808000), // Olive
        Color(0xFFFFD8B1), // Apricot
        Color(0xFF000075), // Navy
        Color(0xFF808080)  // Gray
        // Add more distinct colors if needed
    )
    // Helper to get a default color based on index, cycling through the list
    fun getDefaultColor(index: Int): Color {
        if (DefaultCategoryColors.isEmpty()) return Color.Gray // Avoid errors if list is empty
        return DefaultCategoryColors[index % DefaultCategoryColors.size]
    }
    // Helper to get a default color based on category name hash code (more stable)
    fun getDefaultColorByName(categoryName: String): Color {
        if (DefaultCategoryColors.isEmpty()) return Color.Gray
        val hashCode = categoryName.hashCode()
        val index = (hashCode and 0x7FFFFFFF) % DefaultCategoryColors.size // Ensure positive index
        return DefaultCategoryColors[index]
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel) {
    // Collect state
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val transactionStatistics by viewModel.transactionStatistics.collectAsStateWithLifecycle()
    val dailySpendingData by viewModel.dailySpendingData.collectAsStateWithLifecycle()
    val transactionCount by viewModel.transactionCount.collectAsStateWithLifecycle()
    // --- ASSUMPTION: ViewModel provides category colors ---
    // You'll need to implement the fetching of this map in your ViewModel
    // Example: val categoryColorsMap by viewModel.categoryColors.collectAsStateWithLifecycle()
    // For now, using an empty map for demonstration
    val categoryColorsMap = remember { mutableStateOf<Map<String, Color>>(emptyMap()) }
    // --- End Assumption ---


    // ColorScheme definition
    val customColors = darkColorScheme( /* ... Your color definitions ... */ )

    MaterialTheme(
        colorScheme = customColors
    )
    {
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                // Removed .weight(1f)
            ) {
                // Header
                item {
                    Text(
                        text = "Financial Statistics",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Current Date
                item {
                    Text(
                        text = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                // Period Selection Chips
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                                        text = labelText,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }
                }


                // Summary Cards
                item {
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
                }

                // Expense Distribution Chart
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Expense Distribution",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            ExpenseDistributionChart(
                                data = transactionStatistics.categoryStats.map { (category, stats) ->
                                    ExpenseCategory(category, stats.totalExpense)
                                },
                                // Pass the category colors map
                                categoryColors = categoryColorsMap.value,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        }
                    }
                }

                // Spending Trend Chart
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            val chartTitle = when(selectedPeriod) {
                                StatisticsViewModel.TimePeriod.TODAY -> "Today's Spending"
                                StatisticsViewModel.TimePeriod.WEEK -> "Weekly Spending Trend"
                                StatisticsViewModel.TimePeriod.MONTH -> "Monthly Spending Trend"
                                StatisticsViewModel.TimePeriod.ALL -> "Spending Trend (All Time)"
                            }
                            Text(
                                text = chartTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            DailySpendingChart(
                                data = dailySpendingData,
                                selectedPeriod = selectedPeriod,
                                // Pass the category colors map
                                categoryColors = categoryColorsMap.value,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(250.dp)
                            )
                        }
                    }
                }
            } // End LazyColumn
        } // End Column
    } // End MaterialTheme
}

// --- SummaryCard composable remains the same ---
@Composable
fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// --- ExpenseCategory data class remains the same ---
data class ExpenseCategory(
    val name: String,
    val amount: Double
)

// --- ExpenseDistributionChart composable modified for specific colors ---
@Composable
fun ExpenseDistributionChart(
    data: List<ExpenseCategory>,
    categoryColors: Map<String, Color>, // Accept map
    modifier: Modifier = Modifier
) {
    val total = data.sumOf { it.amount }.takeIf { it > 0 } ?: 1.0

    // Get a sorted list of categories for consistent default color assignment if needed
    val sortedCategories = data.map { it.name }.distinct().sorted()

    Box(modifier = modifier.padding(bottom = 8.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 3
            val center = Offset(size.width / 2, size.height / 2)
            var startAngle = -90f

            data.forEach { categoryData ->
                // --- Color Logic ---
                val color = categoryColors[categoryData.name]
                    ?: ChartColors.getDefaultColorByName(categoryData.name)
                // --- End Color Logic ---

                val sweepAngle = (categoryData.amount / total * 360f).toFloat()
                drawArc(
                    color = color, // Use the determined color
                    startAngle = startAngle,
                    sweepAngle = sweepAngle.coerceAtLeast(0.1f),
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2)
                )
                startAngle += sweepAngle
            }
        }

        // Legend
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(start = 16.dp)
        ) {
            data.take(5).forEachIndexed { index, categoryData ->
                val color = categoryColors[categoryData.name]
                    ?: ChartColors.getDefaultColorByName(categoryData.name)
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background( color, RoundedCornerShape(2.dp) )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text( text = categoryData.name, style = MaterialTheme.typography.bodySmall )
                        Text(
                            text = "₹${categoryData.amount.roundToInt()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (data.size > 5) {
                Text( text = "...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp) )
            }
        }
    }
}


// --- DailySpendingChart composable modified for specific colors and adaptive axes ---
@Composable
fun DailySpendingChart(
    data: List<StatisticsViewModel.DailySpending>,
    selectedPeriod: StatisticsViewModel.TimePeriod, // Accept selectedPeriod
    categoryColors: Map<String, Color>, // Accept map
    modifier: Modifier = Modifier
) {
    // Group data and get categories (remains the same)
    val groupedData = data.groupBy { it.category }
    val categoriesInData = data.map { it.category }.distinct().sorted()

    // --- Date Formatting Logic (Corrected) ---
    // This block now correctly returns Pair instead of Triple
    val (xAxisFormat, timeUnitPoints) = remember(data, selectedPeriod) {
        if (data.isEmpty()) {
            // Handle empty data case - return default format and empty list
            Pair(SimpleDateFormat("dd/MM", Locale.getDefault()), emptyList<Date>())
        } else {
            val sortedDates = data.map { it.date }.distinct().sorted()
            val firstDate = sortedDates.first()
            val lastDate = sortedDates.last()
            val timeDiffMillis = lastDate.time - firstDate.time
            val timeDiffDays = TimeUnit.MILLISECONDS.toDays(timeDiffMillis).toInt()

            // Determine format based on period and date range
            val format = when (selectedPeriod) {
                StatisticsViewModel.TimePeriod.TODAY, StatisticsViewModel.TimePeriod.WEEK -> {
                    SimpleDateFormat("dd/MM", Locale.getDefault())
                }
                StatisticsViewModel.TimePeriod.MONTH -> {
                    if (timeDiffDays > 50) SimpleDateFormat("MMM", Locale.getDefault())
                    else SimpleDateFormat("dd/MM", Locale.getDefault())
                }
                StatisticsViewModel.TimePeriod.ALL -> {
                    if (timeDiffDays > 360) SimpleDateFormat("MMM yy", Locale.getDefault())
                    else SimpleDateFormat("MMM", Locale.getDefault())
                }
            }
            // Return the format and the sorted unique dates
            Pair(format, sortedDates)
        }
    } // End remember

    // --- Paint setup (remains the same) ---
    val density = LocalDensity.current
    val labelTextSizePx = with(density) { 10.sp.toPx() }
    val axisLabelPaint = remember { Paint().apply { isAntiAlias = true; color = android.graphics.Color.GRAY; textAlign = Paint.Align.CENTER; textSize = labelTextSizePx } }
    val yAxisLabelPaint = remember { Paint().apply { isAntiAlias = true; color = android.graphics.Color.GRAY; textAlign = Paint.Align.RIGHT; textSize = labelTextSizePx } }


    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp)) {
            // --- Dimension and Max Amount Calculation (remains the same) ---
            val axisColor = Color.Gray.copy(alpha = 0.5f)
            val width = size.width
            val height = size.height
            val yPadding = 40f
            val xPaddingStart = 50f
            val xPaddingEnd = 16f
            val availableHeight = height - (2 * yPadding)
            val availableWidth = width - xPaddingStart - xPaddingEnd
            val maxAmount = data.maxByOrNull { it.amount }?.amount?.takeIf { it > 0.0 } ?: 1.0

            // --- Draw Y-axis and labels (remains the same) ---
            drawLine( color = axisColor, start = Offset(xPaddingStart, yPadding), end = Offset(xPaddingStart, height - yPadding), strokeWidth = 2f ) // Y-axis
            drawLine( color = axisColor, start = Offset(xPaddingStart, height - yPadding), end = Offset(width - xPaddingEnd, height - yPadding), strokeWidth = 2f ) // X-axis
            val yLabelCount = 5
            (0..yLabelCount).forEach { i ->
                val value = maxAmount * (i.toFloat() / yLabelCount)
                val yPos = height - yPadding - (i.toFloat() / yLabelCount * availableHeight)
                drawContext.canvas.nativeCanvas.drawText( "₹${value.roundToInt()}", xPaddingStart - 8f, yPos + (labelTextSizePx / 3), yAxisLabelPaint )
                drawLine( color = axisColor.copy(alpha = 0.3f), start = Offset(xPaddingStart, yPos), end = Offset(width - xPaddingEnd, yPos), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) )
            }

            // --- Draw X-axis labels (Corrected logic using Pair result) ---
            val datePointsCount = timeUnitPoints.size // Use the points from the Pair
            if (datePointsCount > 0) {
                val maxLabels = (availableWidth / (labelTextSizePx * 5)).toInt().coerceAtLeast(2)
                val labelStep = ((datePointsCount - 1f) / (maxLabels - 1f)).coerceAtLeast(1f).roundToInt()
                timeUnitPoints.forEachIndexed { index, date ->
                    if (index == 0 || index == datePointsCount -1 || (datePointsCount > 1 && index % labelStep == 0) ) {
                        val xPos = xPaddingStart + (index.toFloat() / (datePointsCount - 1).coerceAtLeast(1) * availableWidth)
                        val dateString = xAxisFormat.format(date) // Use format from the Pair
                        drawContext.canvas.nativeCanvas.drawText( dateString, xPos, height - yPadding + labelTextSizePx + 8f, axisLabelPaint )
                    }
                }
            }


            // --- Plot points and lines (Corrected logic using Pair result and colors) ---
            categoriesInData.forEachIndexed { index, categoryName ->
                val color = categoryColors[categoryName]
                    ?: ChartColors.getDefaultColorByName(categoryName) // Fallback color

                val categoryData = data.filter { it.category == categoryName }.sortedBy { it.date }

                if (categoryData.isNotEmpty() && timeUnitPoints.isNotEmpty()) {
                    val points = categoryData.mapNotNull { spending ->
                        val dateIndex = timeUnitPoints.indexOf(spending.date) // Use points from Pair
                        if (dateIndex == -1) null else {
                            val x = xPaddingStart + (dateIndex.toFloat() / (timeUnitPoints.size - 1).coerceAtLeast(1) * availableWidth)
                            val y = height - yPadding - ((spending.amount / maxAmount).toFloat() * availableHeight)
                            Offset(x.coerceIn(xPaddingStart, width - xPaddingEnd), y.coerceIn(yPadding, height - yPadding))
                        }
                    }

                    // Draw lines and points using the determined 'color'
                    if (points.size > 1) {
                        val path = Path()
                        points.forEachIndexed { i, point -> if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y) }
                        drawPath(path = path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round)) // Use color
                    }
                    points.forEach { point -> drawCircle(color = color, radius = 6f, center = point) } // Use color
                }
            }
        } // End Canvas
        // --- Legend using specific category colors (remains the same) ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            categoriesInData.forEachIndexed { index, categoryName ->
                val color = categoryColors[categoryName]
                    ?: ChartColors.getDefaultColorByName(categoryName)
                Row( verticalAlignment = Alignment.CenterVertically ) {
                    Box( modifier = Modifier.size(10.dp).background( color, RoundedCornerShape(2.dp) ) )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text( text = categoryName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface )
                }
            }
        } // End Legend Row
    } // End Column wrapper
}