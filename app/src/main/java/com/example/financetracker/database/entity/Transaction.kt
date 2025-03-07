package com.example.financetracker.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
@Entity(tableName = "transaction_table")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    var name: String = "",
    val amount: Double = 0.0,
    val date: Long = 0L,
    var category: String = "",
    val merchant: String = "",
    val description: String = "",
    // Add documentId field for Firestore integration
    var documentId: String = "",
    // This field is used by both Room and Firestore
    @field:JvmField
    var userId: String? = null
) {
    // Required no-argument constructor for Firestore
    constructor() : this(
        id = 0,
        name = "",
        amount = 0.0,
        date = 0L,
        category = "",
        merchant = "",
        description = ""
    )
}