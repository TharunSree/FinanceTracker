package com.example.financetracker.viewmodel

import android.app.Application // Import Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.financetracker.database.TransactionDatabase // Import Database
import com.example.financetracker.repository.TransactionRepository

// Factory now needs Database and Application to provide all dependencies
class StatisticsViewModelFactory(
    private val repository: TransactionRepository,
    // Add database instance to get DAOs
    private val database: TransactionDatabase,
    // Add application instance
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StatisticsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            // Provide Repository, CategoryDao, and Application
            return StatisticsViewModel(
                repository,
                database.categoryDao(), // Get CategoryDao from database instance
                application
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}