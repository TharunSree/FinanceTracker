package com.example.financetracker.model

import java.util.Date

data class TransactionSummary(
    val totalAmount: Double = 0.0,
    val transactionCount: Int = 0,
    val startDate: Date = Date(),
    val endDate: Date = Date()
)

data class CategorySummary(
    val name: String,
    val amount: Double,
    val percentage: Float,
    val transactionCount: Int
)

data class DailySummary(
    val date: Date,
    val amount: Double
)