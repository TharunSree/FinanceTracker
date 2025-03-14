// Budget.kt
package com.example.financetracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "budget_table")
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val category: String,
    val amount: Double,
    val period: String, // "DAILY", "WEEKLY", "MONTHLY", "YEARLY"
    val userId: String?
)