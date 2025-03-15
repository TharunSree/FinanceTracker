package com.example.financetracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "merchant_table")
data class Merchant(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val category: String,
    val userId: String? = null // Added userId field with default null
)