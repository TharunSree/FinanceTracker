package com.example.financetracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
@Entity(tableName = "transaction_table")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,  // Changed from val to var
    var name: String = "",
    val amount: Double = 0.0,
    val date: Long = 0L,
    var category: String = "",
    val merchant: String = "",
    val description: String = "",
    var documentId: String = "",
    var userId: String = ""
) {
    // Required no-argument constructor for Firestore
    constructor() : this(
        id = 0,
        name = "",
        amount = 0.0,
        date = 0L,
        category = "",
        merchant = "",
        description = "",
        documentId = "",
        userId = ""
    )
}