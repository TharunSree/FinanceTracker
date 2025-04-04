package com.example.financetracker.ui.screens // Or your desired package

// Import Material 3 components
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton // Use IconButton for arrows
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons         // Keep standard icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
// Foundational imports
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape // Can use for clipping month items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color // Keep for potential specific use, but prefer theme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Keep accompanist flow layout
import com.google.accompanist.flowlayout.FlowCrossAxisAlignment
import com.google.accompanist.flowlayout.FlowRow
import com.google.accompanist.flowlayout.MainAxisAlignment
import java.time.LocalDate


@OptIn(ExperimentalMaterial3Api::class) // Still needed for Material3 AlertDialog
@Composable
fun MonthPicker(
    visible: Boolean,
    currentMonth: Int, // Expects 0-indexed month (0=JAN, 11=DEC)
    currentYear: Int,
    confirmButtonCLicked: (Int, Int) -> Unit, // Returns 1-indexed month, year
    cancelClicked: () -> Unit,
    minYear: Int = 2000, // Optional min year constraint
    maxYear: Int = LocalDate.now().year // Optional max year constraint
) {

    val months = remember { // Use remember for constant list
        listOf(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
        )
    }

    // State within the dialog, keyed to inputs for potential resets
    var year by remember(currentYear) { mutableStateOf(currentYear) }
    // Store month as 0-indexed internally
    var monthIndex by remember(currentMonth) { mutableStateOf(currentMonth) }

    val interactionSource = remember { MutableInteractionSource() }

    if (visible) {
        AlertDialog(
            onDismissRequest = { cancelClicked() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp), // Or MaterialTheme.shapes.medium

            // Title (Optional, could be simpler)
            title = {
                Text(
                    text = "Select Month & Year",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },

            // Content section with Year Selector + Month Grid
            text = {
                Column {
                    // --- Year Selector ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Use IconButton for accessibility
                        IconButton(onClick = { if (year > minYear) year-- }) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowLeft,
                                contentDescription = "Previous Year",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant // Theme color
                            )
                        }

                        Text(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            text = year.toString(),
                            style = MaterialTheme.typography.headlineSmall, // Prominent year display
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface // Theme color
                        )

                        IconButton(onClick = { if (year < maxYear) year++ }) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = "Next Year",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant // Theme color
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp)) // Space between year and months

                    // --- Month Selector ---
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        mainAxisSpacing = 8.dp, // Spacing between items horizontally
                        crossAxisSpacing = 12.dp, // Spacing between rows vertically
                        mainAxisAlignment = MainAxisAlignment.Center,
                        crossAxisAlignment = FlowCrossAxisAlignment.Center
                    ) {
                        months.forEachIndexed { index, monthAbbreviation ->
                            val isSelected = (index == monthIndex)
                            val monthShape = CircleShape // Use CircleShape for selection

                            Box(
                                modifier = Modifier
                                    .size(56.dp) // Fixed size for consistent layout
                                    .clip(monthShape) // Clip before background/click
                                    .background(
                                        // Use theme colors for selection background
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        shape = monthShape
                                    )
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null, // Optional: remove ripple
                                        onClick = { monthIndex = index } // Update 0-indexed state
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = monthAbbreviation,
                                    // Use theme colors, adjusting for selection
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium, // M3 Typography
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            },

            // Confirm Button
            confirmButton = {
                TextButton(
                    onClick = {
                        // IMPORTANT: Return 1-indexed month to the callback
                        confirmButtonCLicked(monthIndex + 1, year)
                    }
                ) {
                    Text(
                        "OK",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },

            // Dismiss Button
            dismissButton = {
                TextButton(onClick = { cancelClicked() }) {
                    Text(
                        "Cancel",
                        color = MaterialTheme.colorScheme.secondary, // Or onSurfaceVariant
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }
}