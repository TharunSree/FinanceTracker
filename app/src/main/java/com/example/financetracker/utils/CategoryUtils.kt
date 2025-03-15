package com.example.financetracker.utils

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.example.financetracker.database.TransactionDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CategoryUtils {

    /**
     * Loads categories into the provided spinner
     * @param context The activity context
     * @param spinner The spinner to populate
     * @param userId The current user ID
     * @param selectedCategory Optional pre-selected category
     */
    suspend fun loadCategoriesToSpinner(
        context: Context,
        spinner: Spinner,
        userId: String?,
        selectedCategory: String? = null
    ) {
        val database = TransactionDatabase.getDatabase(context)

        // Get categories from Room database
        val categories = database.categoryDao().getAllCategoriesOneTime(userId)

        // Create array of category names
        val categoryNames = categories.map { it.name }.toTypedArray()

        // Create adapter for spinner
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            categoryNames
        )

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        // Set adapter to spinner on the main thread
        withContext(Dispatchers.Main) {
            spinner.adapter = adapter

            // Set default selection if category was provided
            if (!selectedCategory.isNullOrEmpty()) {
                val position = categoryNames.indexOf(selectedCategory)
                if (position >= 0) {
                    spinner.setSelection(position)
                }
            }
        }
    }
}