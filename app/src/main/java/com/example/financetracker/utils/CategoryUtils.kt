package com.example.financetracker.utils

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

object CategoryUtils {

    suspend fun loadCategoriesToSpinner(
        context: Context,
        spinner: Spinner,
        userId: String,
        selectedCategory: String? = null
    ) {
        val database = TransactionDatabase.getDatabase(context)
        val categories = withContext(Dispatchers.IO) {
            database.categoryDao().getAllCategories(userId).first() // Use .first() to collect the Flow
        }

        val categoryNames = categories.map { it.name }.toMutableList()

        withContext(Dispatchers.Main) {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, categoryNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter

            selectedCategory?.let {
                val position = categoryNames.indexOf(it)
                if (position != -1) {
                    spinner.setSelection(position)
                }
            }
        }
    }

    suspend fun addCategory(context: Context, category: Category) {
        val database = TransactionDatabase.getDatabase(context)
        withContext(Dispatchers.IO) {
            database.categoryDao().insertCategory(category)
        }
    }

    suspend fun updateCategory(context: Context, category: Category) {
        val database = TransactionDatabase.getDatabase(context)
        withContext(Dispatchers.IO) {
            database.categoryDao().updateCategory(category)
        }
    }

    suspend fun deleteCategory(context: Context, category: Category) {
        val database = TransactionDatabase.getDatabase(context)
        withContext(Dispatchers.IO) {
            database.categoryDao().deleteCategory(category)
        }
    }
}