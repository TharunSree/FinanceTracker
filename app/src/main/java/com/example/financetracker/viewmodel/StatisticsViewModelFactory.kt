package com.example.financetracker.viewmodel // Or your appropriate package

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.financetracker.repository.TransactionRepository
// Make sure the import path for StatisticsViewModel is correct
import com.example.financetracker.viewmodel.StatisticsViewModel

class StatisticsViewModelFactory(
    private val repository: TransactionRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        // Check if the requested ViewModel class is StatisticsViewModel
        if (modelClass.isAssignableFrom(StatisticsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            // Create and return an instance, passing the repository
            return StatisticsViewModel(repository) as T
        }
        // If it's a different ViewModel class, throw an exception
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}