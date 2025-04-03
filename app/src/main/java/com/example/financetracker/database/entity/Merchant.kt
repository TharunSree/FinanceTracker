package com.example.financetracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Represents a merchant entity in the Room database.
 * Stores the merchant name and associated category, potentially per user.
 */
@Entity(
    tableName = "merchant_table",
    // Index on name and userId for faster lookups by getCategoryForMerchant
    indices = [Index(value = ["name", "userId"], unique = true)] // Assuming combination should be unique
)
data class Merchant(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0, // Primary key is Int, default to 0 for Room auto-generation

    val name: String, // Merchant name

    val category: String, // Category name directly associated with this merchant

    // User ID this merchant entry belongs to.
    // Null might indicate a global merchant, or handle based on app logic.
    val userId: String? = null
)
