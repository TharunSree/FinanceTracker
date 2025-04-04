package com.example.financetracker.ui.screens // Or your desired package

// Import Material 3 components
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
// Foundational imports
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
// Accompanist Flow Layout
import com.google.accompanist.flowlayout.FlowCrossAxisAlignment
import com.google.accompanist.flowlayout.FlowRow
import com.google.accompanist.flowlayout.MainAxisAlignment
import java.time.LocalDate

private const val YEARS_PER_PAGE = 12 // 4x3 grid = 12 years

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearPicker( // Renamed to avoid confusion if you keep the old one
    visible: Boolean,
    currentYear: Int,
    minYear: Int = 2000,
    maxYear: Int = LocalDate.now().year, // Constraint: No future years
    confirmButtonClicked: (Int) -> Unit,
    cancelClicked: () -> Unit
) {
    // State for the year actually selected by the user
    var selectedYear by remember(currentYear) {
        mutableStateOf(currentYear.coerceIn(minYear, maxYear)) // Ensure initial selection is valid
    }

    // State for the starting year of the currently displayed 12-year page
    // Calculate initial page start year based on the currentYear
    val initialPageStartYear = remember(currentYear, minYear) {
        val yearsFromMin = currentYear - minYear
        (minYear + (yearsFromMin / YEARS_PER_PAGE) * YEARS_PER_PAGE).coerceAtLeast(minYear)
    }
    var pageStartYear by remember(initialPageStartYear) {
        mutableStateOf(initialPageStartYear)
    }

    val interactionSource = remember { MutableInteractionSource() }

    // Determine the end year of the current page for display and navigation logic
    val pageEndYear = pageStartYear + YEARS_PER_PAGE - 1

    if (visible) {
        AlertDialog(
            onDismissRequest = { cancelClicked() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
            // Removed title for more space, or add a simpler one like "Select Year"
            // title = { ... }
            text = {
                Column {
                    // --- Year Page Navigation ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween, // Pushes arrows to edges
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous Page Button
                        IconButton(
                            onClick = { pageStartYear = (pageStartYear - YEARS_PER_PAGE).coerceAtLeast(minYear) },
                            // Disable if the previous page would start before minYear
                            enabled = pageStartYear > minYear
                        ) {
                            val arrowColor = if (pageStartYear > minYear) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowLeft,
                                contentDescription = "Previous Years",
                                tint = arrowColor
                            )
                        }

                        // Display current year range
                        Text(
                            text = "$pageStartYear - $pageEndYear",
                            style = MaterialTheme.typography.titleMedium, // Or bodyLarge
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Next Page Button
                        IconButton(
                            onClick = { pageStartYear = (pageStartYear + YEARS_PER_PAGE).coerceAtMost(maxYear - YEARS_PER_PAGE + 1) },
                            // Disable if the current page already includes the maxYear
                            enabled = pageEndYear < maxYear
                        ) {
                            val arrowColor = if (pageEndYear < maxYear) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = "Next Years",
                                tint = arrowColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp)) // Space between navigation and grid

                    // --- Year Grid ---
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        mainAxisSpacing = 4.dp, // Adjust spacing for 4 items across
                        crossAxisSpacing = 8.dp,
                        mainAxisAlignment = MainAxisAlignment.Center, // Center items
                        crossAxisAlignment = FlowCrossAxisAlignment.Center
                    ) {
                        // Display 12 years for the current page
                        for (i in 0 until YEARS_PER_PAGE) {
                            val yearItem = pageStartYear + i
                            val isSelected = (yearItem == selectedYear)
                            // Determine if the year is outside the allowed min/max range
                            val isDisabled = yearItem < minYear || yearItem > maxYear
                            val itemShape = RoundedCornerShape(12.dp) // Slightly more rounded

                            // Determine colors based on selected/disabled state
                            val textColor = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                isDisabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                else -> MaterialTheme.colorScheme.onSurface // Regular text color
                            }
                            val backgroundColor = when {
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent // No background if not selected
                            }

                            Box(
                                modifier = Modifier
                                    // Adjust width to fit 4 items comfortably, leave height flexible or fixed
                                    .width(65.dp) // Adjust width as needed
                                    .height(48.dp) // Adjust height as needed
                                    .padding(horizontal = 2.dp) // Add slight horizontal padding between items if needed
                                    .clip(itemShape)
                                    .background(backgroundColor, itemShape)
                                    .clickable(
                                        enabled = !isDisabled, // Disable click if outside range
                                        interactionSource = interactionSource,
                                        indication = null,
                                        onClick = { if (!isDisabled) selectedYear = yearItem }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = yearItem.toString(),
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyMedium, // Can adjust style
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            },
            // --- Buttons remain the same ---
            confirmButton = {
                TextButton(onClick = { confirmButtonClicked(selectedYear) }) {
                    Text("OK", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelClicked() }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}