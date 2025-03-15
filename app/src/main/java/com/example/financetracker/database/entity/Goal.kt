package com.example.financetracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "goal_table")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double = 0.0,
    val startDate: Long = System.currentTimeMillis(),
    val targetDate: Long,
    val category: String,
    val description: String = "",
    val userId: String,
    val documentId: String = "" // For Firestore sync
)