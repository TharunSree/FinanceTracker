package com.example.financetracker.database.entity

import androidx.compose.ui.graphics.Color // Optional: Import if you store Color Int
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "category_table")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val userId: String?,
    val isDefault: Boolean = false,
    // Add a field to store the color hex string (e.g., "#FF0000" for red)
    // Make it nullable for backward compatibility and default categories
    val colorHex: String? = null
)