package com.example.financetracker.database

data class TransactionPattern(
    val messagePattern: String,
    val merchant: String,
    val category: String
)