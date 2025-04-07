package com.example.financetracker.model

import java.util.Date // Keep Date import if needed elsewhere, but class uses Long

/**
 * Data class to hold transaction details extracted from messages before saving.
 * This structure is used by the message extractors.
 */
data class TransactionDetails(
    val name: String, // Name of the sender or merchant
    val amount: Double,
    val merchant: String, // Name of the merchant or sender
    val date: Long,       // Transaction date as epoch milliseconds (Long)
    val category: String, // Raw category name extracted
    val currency: String, // Currency code (e.g., "INR", "USD")
    val referenceNumber: String? = null, // Optional transaction reference number
    val description: String = "" // Transaction description
)
