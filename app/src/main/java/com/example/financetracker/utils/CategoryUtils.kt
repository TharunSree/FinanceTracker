package com.example.financetracker.utils

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CategoryUtils {
    /**
     * Load categories into a spinner from the database
     */
    suspend fun loadCategoriesToSpinner(
        context: Context,
        spinner: Spinner,
        userId: String,
        selectedCategory: String? = null
    ) {
        val database = TransactionDatabase.getDatabase(context)

        // Get categories from database
        val categories = withContext(Dispatchers.IO) {
            database.categoryDao().getAllCategoriesOneTime(userId)
        }

        // Extract category names
        val categoryNames = categories.map { it.name }

        // Create and set adapter
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            categoryNames
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        spinner.adapter = adapter

        // Set selected category if provided
        if (selectedCategory != null) {
            val position = categoryNames.indexOf(selectedCategory)
            if (position != -1) {
                spinner.setSelection(position)
            }
        }
    }

    /**
     * Initialize default categories for a new user
     */
    suspend fun initializeDefaultCategories(context: Context, userId: String) {
        val database = TransactionDatabase.getDatabase(context)
        val categoryDao = database.categoryDao()

        // Check if user already has categories
        val existingCategories = withContext(Dispatchers.IO) {
            categoryDao.getAllCategoriesOneTime(userId)
        }

        // If user already has categories, don't add default ones
        if (existingCategories.isNotEmpty()) {
            return
        }

        // Get default categories from resources
        val defaultCategories = context.resources.getStringArray(
            com.example.financetracker.R.array.default_categories
        )

        // Create category objects
        val categoryEntities = defaultCategories.map { categoryName ->
            Category(
                name = categoryName,
                userId = userId
            )
        }

        // Insert all categories
        withContext(Dispatchers.IO) {
            categoryDao.insertCategories(categoryEntities)
        }
    }
}