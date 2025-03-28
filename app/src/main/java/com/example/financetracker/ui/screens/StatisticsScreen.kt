package com.example.financetracker.ui.screens


import android.R.id.primary
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.financetracker.model.*
import com.example.financetracker.viewmodel.StatisticsViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt


object ChartColors {
    val Primary = Color(0xFF1976D2)
    val Secondary = Color(0xFF03A9F4)
    val Error = Color(0xFFE57373)
    val CategoryColors = listOf(
        Color(0xFF4CAF50), // Green
        Color(0xFF2196F3), // Blue
        Color(0xFFFFC107), // Amber
        Color(0xFFE91E63), // Pink
        Color(0xFF9C27B0), // Purple
        Color(0xFF00BCD4)  // Cyan
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel) {
    // Collect the period from ViewModel using collectAsStateWithLifecycle
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()

    val customColors = darkColorScheme(
        primary = Color(0xFF2196F3),      // Blue 500
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
        onSurfaceVariant = Color(0xB3FFFFFF),  // 70% white
        outline = Color(0xFF404040)
    )
    MaterialTheme(
        colorScheme = customColors
    )
    {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(850.dp),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
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
                        text = SimpleDateFormat(
                            "MMMM dd, yyyy",
                            Locale.getDefault()
                        ).format(Date()),
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
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatisticsViewModel.TimePeriod.entries.forEach { period ->
                            FilterChip(
                                selected = selectedPeriod == period,
                                onClick = { viewModel.updatePeriod(period) },
                                label = { Text(period.name) },
                                modifier = Modifier.weight(1f)
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
                            value = "₹25,000",
                            modifier = Modifier.weight(1f)
                        )
                        SummaryCard(
                            title = "Transactions",
                            value = "15",
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
                                data = listOf(
                                    ExpenseCategory("Food", 8000.0),
                                    ExpenseCategory("Transport", 5000.0),
                                    ExpenseCategory("Shopping", 7000.0),
                                    ExpenseCategory("Bills", 5000.0)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        }
                    }
                }

                // Daily Spending Chart
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Daily Spending",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )

                            DailySpendingChart(
                                data = generateSampleDailyData(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

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

data class ExpenseCategory(
    val name: String,
    val amount: Double
)

@Composable
fun ExpenseDistributionChart(
    data: List<ExpenseCategory>,
    modifier: Modifier = Modifier
) {
    val total = data.sumOf { it.amount }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 3
            val center = Offset(size.width / 2, size.height / 2)
            var startAngle = 0f

            data.forEachIndexed { index, category ->
                val sweepAngle = (category.amount / total * 360f).roundToInt().toFloat()
                drawArc(
                    color = ChartColors.CategoryColors[index % ChartColors.CategoryColors.size],
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
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
            data.forEachIndexed { index, category ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                ChartColors.CategoryColors[index % ChartColors.CategoryColors.size],
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "₹${category.amount.roundToInt()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

data class DailySpending(
    val date: Date,
    val amount: Double
)

@Composable
fun DailySpendingChart(
    data: List<DailySpending>,
    modifier: Modifier = Modifier
) {
    val dateFormat = SimpleDateFormat("dd/MM", Locale.getDefault())

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val padding = 32f

        // Find max amount for scaling
        val maxAmount = data.maxByOrNull { it.amount }?.amount ?: 0.0

        // Draw axes
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(padding, padding),
            end = Offset(padding, height - padding),
            strokeWidth = 2f
        )
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(padding, height - padding),
            end = Offset(width - padding, height - padding),
            strokeWidth = 2f
        )

        // Plot points and lines
        val points = data.mapIndexed { index, spending ->
            val x = padding + (index * (width - 2 * padding) / (data.size - 1))
            val y = height - padding - (spending.amount / maxAmount * (height - 2 * padding))
            Offset(x, y.toFloat())
        }

        // Draw connecting lines
        val path = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) {
                path.moveTo(point.x, point.y)
            } else {
                path.lineTo(point.x, point.y)
            }
        }

        drawPath(
            path = path,
            color = ChartColors.Primary,
            style = Stroke(
                width = 3f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Draw points
        points.forEach { point ->
            drawCircle(
                color = ChartColors.Primary,
                radius = 6f,
                center = point
            )
        }
    }
}

private fun generateSampleDailyData(): List<DailySpending> {
    val calendar = Calendar.getInstance()
    return (0..6).map { daysAgo ->
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        DailySpending(
            date = calendar.time,
            amount = (1000..5000).random().toDouble()
        )
    }.reversed()
}