package com.example.financetracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Represents a transaction entity in the Room database.
 * Matches the structure used in the latest SmsProcessingService.
 */
@Entity(
    tableName = "transactions",
    // Add indices for frequently queried columns
    indices = [Index(value = ["userId"]), Index(value = ["date"]), Index(value = ["merchant"])]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0, // Auto-generated primary key

    var name: String, // Transaction name, often derived from merchant
    var amount: Double,
    var date: Long, // Transaction date as epoch milliseconds
    var category: String, // Category name (might be blank if needs details)
    var merchant: String, // Original merchant name extracted
    var description: String?, // Optional description extracted

    // Foreign key or identifier for the user this transaction belongs to
    var userId: String,

    // Optional: Firestore document ID if synced
    var documentId: String? = null,

    // --- Fields from previous version (if needed, uncomment and adapt service) ---
    // var type: String, // "Income" or "Expense" - Currently not populated by the service
    // var categoryId: Long?, // Foreign key to Category table - Currently not populated by the service
    // var merchantId: Long?, // Foreign key to Merchant table - Currently not populated by the service
    // var smsSender: String?, // Original SMS sender - Currently not populated by the service
    // var smsTimestamp: Long? // Original SMS timestamp - Currently not populated by the service
) {
    // You might add a secondary constructor or helper methods if needed
}

