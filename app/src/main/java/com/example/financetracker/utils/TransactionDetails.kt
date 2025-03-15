package com.example.financetracker.utils

data class TransactionDetails(
    val amount: Double,
    val description: String?,
    val merchant: String?,
    val category: String?,
    val date: Long?,
    val isCredit: Boolean?,
    val needsUserInput: Boolean = false
)