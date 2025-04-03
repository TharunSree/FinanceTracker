package com.example.financetracker.database.entity

// Optional: Import if you store Color Int directly, but storing Hex String is usually better for DB compatibility
// import androidx.compose.ui.graphics.Color
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Represents a category entity in the Room database.
 * Includes name, user association, default status, and an optional color.
 */
@Entity(
    tableName = "category_table", // Using the table name provided by the user
    // Index for faster lookups by name and userId
    indices = [Index(value = ["name", "userId"], unique = true)] // Name should be unique per user
)
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0, // Primary key is Int, default 0 for Room auto-generation

    val name: String, // Name of the category (e.g., "Food", "Shopping")

    // ID of the user this category belongs to.
    // Null might indicate a global/default category or guest user.
    val userId: String?,

    // Flag indicating if this is a system-defined default category.
    val isDefault: Boolean = false,

    // Stores the color associated with the category as a Hex string (e.g., "#FF5733").
    // Nullable to support optional colors or backward compatibility.
    val colorHex: String? = null
)
