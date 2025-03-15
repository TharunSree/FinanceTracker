package com.example.financetracker.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.financetracker.R
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DefaultDataInitializer {
    private const val PREFS_NAME = "finance_tracker_prefs"
    private const val KEY_DEFAULT_CATEGORIES_INITIALIZED = "default_categories_initialized"

    fun initializeDefaultData(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!prefs.getBoolean(KEY_DEFAULT_CATEGORIES_INITIALIZED, false)) {
            insertDefaultCategories(context, prefs)
        }
    }

    private fun insertDefaultCategories(context: Context, prefs: SharedPreferences) {
        val database = TransactionDatabase.getDatabase(context)
        val categoryDao = database.categoryDao()

        // Retrieve default categories from strings.xml
        val defaultCategories = context.resources.getStringArray(R.array.transaction_categories)

        CoroutineScope(Dispatchers.IO).launch {
            // Insert each default category
            defaultCategories.forEach { categoryName ->
                val category = Category(
                    name = categoryName,
                    userId = null,  // null userId means it's available to all users
                    isDefault = true
                )

                // Check if category already exists
                val existingCategory = categoryDao.getCategoryByName(categoryName, null)
                if (existingCategory == null) {
                    categoryDao.insertCategory(category)
                }
            }

            // Mark as initialized
            prefs.edit().putBoolean(KEY_DEFAULT_CATEGORIES_INITIALIZED, true).apply()
        }
    }
}