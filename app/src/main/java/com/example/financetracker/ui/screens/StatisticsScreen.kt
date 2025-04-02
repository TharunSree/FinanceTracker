package com.example.financetracker.ui.screens

// Import Pager related components
import androidx.compose.foundation.ExperimentalFoundationApi // Needed for Pager
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
// Other necessary imports
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.financetracker.viewmodel.StatisticsViewModel
import com.example.financetracker.viewmodel.parseColor // Keep if using user colors
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// --- ChartColors object definition (keep your updated version) ---
object ChartColors {
    val Primary = Color(0xFF1976D2)
    val Secondary = Color(0xFF03A9F4)
    val Error = Color(0xFFE57373)
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
        val index = (hashCode and 0x7FFFFFFF) % DefaultCategoryColors.size
        return DefaultCategoryColors[index]
    }
}

// Add OptIn for Pager
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel) {
    // --- State Collection ---
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val transactionStatistics by viewModel.transactionStatistics.collectAsStateWithLifecycle()
    val dailySpendingData by viewModel.dailySpendingData.collectAsStateWithLifecycle()
    val transactionCount by viewModel.transactionCount.collectAsStateWithLifecycle()
    // Assume ViewModel provides this map for user-assigned colors eventually
    val categoryColorsMap by viewModel.categoryColorsMap.collectAsStateWithLifecycle()

    // --- Theme ---
    // Assuming customColors is defined correctly based on your app's theme setup
    val customColors = darkColorScheme(
        primary = Color(0xFF2196F3),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF1565C0),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFF03A9F4),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFF0288D1),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFF29B6F6),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFF0277BD),
        onTertiaryContainer = Color.White,
        error = Color(0xFFCF6679),
        onError = Color.Black,
        errorContainer = Color(0xFFB00020),
        onErrorContainer = Color.White,
        background = Color(0xFF121212),
        onBackground = Color.White,
        surface = Color(0xFF1E1E1E),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF272727),
        onSurfaceVariant = Color(0xB3FFFFFF),
        outline = Color(0xFF404040)
    )

    MaterialTheme(
        colorScheme = customColors
    ) {
        // --- Main Layout: Column ---
        Column(
            modifier = Modifier
                .fillMaxSize() // Fill the space provided by ComposeView
                // Apply horizontal padding for overall content inset
                // Apply vertical padding if needed, or let elements space themselves
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {

            // --- Fixed Content Area ---

            // Header (Explicitly White)
            Text(
                text = "Financial Statistics",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground, // Use theme color (should be white)
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp) // Adjusted padding
            )

            // Current Date
            Text(
                text = SimpleDateFormat(
                    "MMMM dd, yyyy",
                    Locale.getDefault()
                ).format(Date()), // Changed format slightly
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Period Selection Chips (Keep horizontal scroll)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp) // Slightly more space
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
                        }
                    )
                }
            }

            // Summary Cards
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
            val pagerState = rememberPagerState(pageCount = { 2 }) // State for 2 pages

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    // Set a fixed height for the Pager area, adjust as needed
                    // This ensures the content below it (indicators) has space
                    .height(320.dp)
            ) { pageIndex ->
                // Content for each page
                when (pageIndex) {
                    // Page 0: Expense Distribution (Pie Chart)
                    0 -> Card(
                        modifier = Modifier
                            .fillMaxSize() // Card fills the pager page
                            .padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Expense Distribution",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            ExpenseDistributionChart(
                                data = transactionStatistics.categoryStats.map { (cat, stats) ->
                                    ExpenseCategory(
                                        cat,
                                        stats.totalExpense
                                    )
                                },
                                categoryColors = categoryColorsMap,
                                // Allow chart to take remaining space in card
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                    // Page 1: Spending Trend (Line Chart)
                    1 -> Card(
                        modifier = Modifier
                            .fillMaxSize() // Card fills the pager page
                            .padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val chartTitle = when (selectedPeriod) {
                                StatisticsViewModel.TimePeriod.TODAY -> "Today's Spending"
                                StatisticsViewModel.TimePeriod.WEEK -> "Weekly Spending Trend"
                                StatisticsViewModel.TimePeriod.MONTH -> "Monthly Spending Trend"
                                StatisticsViewModel.TimePeriod.ALL -> "Spending Trend (All Time)"
                            }
                            Text(
                                chartTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            DailySpendingChart(
                                data = dailySpendingData,
                                selectedPeriod = selectedPeriod,
                                categoryColors = categoryColorsMap,
                                // Allow chart to take remaining space in card
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f) // Chart itself will manage internal height/padding for axes
                            )
                        }
                    }
                }
            } // --- End HorizontalPager ---

            // --- Pager Indicators ---
            Row(
                Modifier
                    .height(20.dp) // Fixed height for indicators row
                    .fillMaxWidth()
                    .padding(top = 4.dp), // Space above indicators
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pagerState.pageCount) { iteration ->
                    val color =
                        if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                            alpha = 0.4f
                        )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(CircleShape)
                            .background(color)
                            .size(8.dp) // Size of dots
                    )
                }
            } // --- End Pager Indicators ---

            Spacer(modifier = Modifier.height(16.dp)) // Space at the very bottom

        } // End Main Column
    } // End MaterialTheme
}

// --- SummaryCard composable (Added height modifier) ---
@Composable
fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(IntrinsicSize.Min), // Ensure cards have same height
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// --- ExpenseCategory data class (no changes needed) ---
data class ExpenseCategory(val name: String, val amount: Double)

// --- ExpenseDistributionChart composable (Added scrollable legend) ---
@Composable
fun ExpenseDistributionChart(
    data: List<ExpenseCategory>,
    categoryColors: Map<String, Color>,
    modifier: Modifier = Modifier
) {
    val total = data.sumOf { it.amount }.takeIf { it > 0 } ?: 1.0
    val sortedData =
        data.filter { it.amount > 0 }.sortedByDescending { it.amount } // Filter empty and sort

    Row(modifier = modifier.height(IntrinsicSize.Min)) { // Use Row to place chart and legend side-by-side
        // Chart Canvas Area
        Canvas(modifier = Modifier
            .weight(0.6f)
            .fillMaxHeight()
            .padding(8.dp)) { // Take 60% width
            val radius = size.minDimension / 2.2f // Adjusted radius
            val center = Offset(size.width / 2, size.height / 2)
            var startAngle = -90f

            sortedData.forEach { categoryData ->
                val color = categoryColors[categoryData.name] ?: ChartColors.getDefaultColorByName(
                    categoryData.name
                )
                val sweepAngle = (categoryData.amount / total * 360f).toFloat()
                if (sweepAngle > 0.1f) {
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = true,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2)
                    )
                }
                startAngle += sweepAngle
            }
        }

        // Legend Area (Scrollable)
        Column(
            modifier = Modifier
                .weight(0.4f) // Take 40% width
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 4.dp) // Adjust padding
        ) {
            sortedData.forEach { categoryData ->
                val color = categoryColors[categoryData.name] ?: ChartColors.getDefaultColorByName(
                    categoryData.name
                )
                Row(
                    modifier = Modifier.padding(vertical = 3.dp), // Slightly more space
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier
                        .size(10.dp)
                        .background(color, RoundedCornerShape(2.dp)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            categoryData.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            "₹${categoryData.amount.roundToInt()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}


// --- DailySpendingChart composable (Added explicit height) ---
@Composable
fun DailySpendingChart(
    data: List<StatisticsViewModel.DailySpending>,
    selectedPeriod: StatisticsViewModel.TimePeriod,
    categoryColors: Map<String, Color>,
    modifier: Modifier = Modifier
) {
    val categoriesInData = data.map { it.category }.distinct().sorted()
    // Date formatting logic (remains same)
    val (xAxisFormat, timeUnitPoints) = remember(data, selectedPeriod) {
        if (data.isEmpty()) {
            Pair(SimpleDateFormat("dd/MM", Locale.getDefault()), emptyList<Date>())
        } else { /* ... Logic to determine format and points based on range ... */
            val sortedDates = data.map { it.date }.distinct().sorted();
            val firstDate = sortedDates.first();
            val lastDate = sortedDates.last()
            val timeDiffDays = TimeUnit.MILLISECONDS.toDays(lastDate.time - firstDate.time).toInt()
            val format = when (selectedPeriod) {
                StatisticsViewModel.TimePeriod.TODAY, StatisticsViewModel.TimePeriod.WEEK -> SimpleDateFormat(
                    "dd/MM",
                    Locale.getDefault()
                )

                StatisticsViewModel.TimePeriod.MONTH -> if (timeDiffDays > 50) SimpleDateFormat(
                    "MMM",
                    Locale.getDefault()
                ) else SimpleDateFormat("dd/MM", Locale.getDefault())

                StatisticsViewModel.TimePeriod.ALL -> if (timeDiffDays > 360) SimpleDateFormat(
                    "MMM yy",
                    Locale.getDefault()
                ) else SimpleDateFormat("MMM", Locale.getDefault())
            }
            Pair(format, sortedDates)
        }
    }

    // Paint setup (remains same)
    val density = LocalDensity.current
    val labelTextSizePx = with(density) { 10.sp.toPx() }
    val axisLabelPaint = remember {
        Paint().apply {
            isAntiAlias = true; color = android.graphics.Color.DKGRAY; textAlign =
            Paint.Align.CENTER; textSize = labelTextSizePx
        }
    }
    val yAxisLabelPaint = remember {
        Paint().apply {
            isAntiAlias = true; color = android.graphics.Color.DKGRAY; textAlign =
            Paint.Align.RIGHT; textSize = labelTextSizePx
        }
    }

    // Layout: Column containing Canvas and Legend Row
    Column(modifier = modifier.fillMaxSize()) { // Allow Column to fill Card space
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 8.dp)
        ) { // Canvas takes most space
            // Dimension and Max Amount Calculation
            val axisColor = Color.Gray.copy(alpha = 0.5f)
            val width = size.width;
            val height = size.height
            val yPadding = 40f;
            val xPaddingStart = 55f;
            val xPaddingEnd = 16f // Adjusted padding slightly
            val availableHeight = (height - (2 * yPadding)).coerceAtLeast(0f) // Ensure non-negative
            val availableWidth =
                (width - xPaddingStart - xPaddingEnd).coerceAtLeast(0f) // Ensure non-negative
            val maxAmount = data.maxByOrNull { it.amount }?.amount?.takeIf { it > 0.0 } ?: 1.0

            // Draw Axes and Y-axis labels
            drawLine(
                color = axisColor,
                start = Offset(xPaddingStart, yPadding),
                end = Offset(xPaddingStart, height - yPadding),
                strokeWidth = 2f
            )
            drawLine(
                color = axisColor,
                start = Offset(xPaddingStart, height - yPadding),
                end = Offset(width - xPaddingEnd, height - yPadding),
                strokeWidth = 2f
            )
            val yLabelCount = 4 // Reduced label count for space
            (0..yLabelCount).forEach { i ->
                val value = maxAmount * (i.toFloat() / yLabelCount)
                val yPos = height - yPadding - (i.toFloat() / yLabelCount * availableHeight)
                drawContext.canvas.nativeCanvas.drawText(
                    "₹${value.roundToInt()}",
                    xPaddingStart - 8f,
                    yPos + (labelTextSizePx / 3),
                    yAxisLabelPaint
                )
                // Optional grid lines
                drawLine(
                    color = axisColor.copy(alpha = 0.2f),
                    start = Offset(xPaddingStart, yPos),
                    end = Offset(width - xPaddingEnd, yPos),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                )
            }

            // Draw X-axis labels
            val datePointsCount = timeUnitPoints.size
            if (datePointsCount > 0 && availableWidth > 0) {
                val maxLabels = (availableWidth / (labelTextSizePx * 4.5f)).toInt()
                    .coerceAtLeast(2) // Adjusted spacing factor
                val labelStep =
                    ((datePointsCount - 1f) / (maxLabels - 1f)).coerceAtLeast(1f).roundToInt()
                timeUnitPoints.forEachIndexed { _, _ -> /* ... draw X labels ... */ }
            }

            // Plot points and lines
            if (availableWidth > 0 && availableHeight > 0) { // Only draw if space exists
                categoriesInData.forEachIndexed { _, categoryName ->
                    val color = categoryColors[categoryName] ?: ChartColors.getDefaultColorByName(
                        categoryName
                    )
                    val categoryData =
                        data.filter { it.category == categoryName }.sortedBy { it.date }
                    if (categoryData.isNotEmpty() && timeUnitPoints.isNotEmpty()) {
                        val points =
                            categoryData.mapNotNull { spending -> // Start of mapNotNull lambda
                                val dateIndex = timeUnitPoints.indexOf(spending.date)
                                if (dateIndex == -1) {
                                    null // Explicitly return null
                                } else {
                                    val x =
                                        xPaddingStart + (dateIndex.toFloat() / (timeUnitPoints.size - 1).coerceAtLeast(
                                            1
                                        ) * availableWidth)
                                    val y =
                                        height - yPadding - ((spending.amount / maxAmount).toFloat() * availableHeight)
                                    // Explicitly return the Offset
                                    return@mapNotNull Offset(
                                        x.coerceIn(
                                            xPaddingStart,
                                            width - xPaddingEnd
                                        ), y.coerceIn(yPadding, height - yPadding)
                                    )
                                } // Smaller radius
                            }
                    }
                }
            }
        } // End Canvas

        // Legend (Scrollable horizontally)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp) // Reduced padding
                .horizontalScroll(rememberScrollState()), // Make legend scrollable if needed
            horizontalArrangement = Arrangement.spacedBy(
                8.dp,
                Alignment.CenterHorizontally
            ), // Center items
            verticalAlignment = Alignment.CenterVertically
        ) {
            categoriesInData.forEachIndexed { _, categoryName ->
                val color =
                    categoryColors[categoryName] ?: ChartColors.getDefaultColorByName(categoryName)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color, RoundedCornerShape(2.dp))
                    ) // Smaller box
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = categoryName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } // End Legend Row
    } // End Column wrapper
}