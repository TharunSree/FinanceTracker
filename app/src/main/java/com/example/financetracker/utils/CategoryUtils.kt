package com.example.financetracker.utils

import android.content.Context
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.example.financetracker.R
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Category
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object CategoryUtils {
    private const val TAG = "CategoryUtils"
    private var isInitialized = false

    private val defaultCategories = listOf(
        "Food",
        "Shopping",
        "Transportation",
        "Bills",
        "Entertainment",
        "Healthcare",
        "Education",
        "Others"
    )

    fun initializeCategories(context: Context) {
        // If already initialized, return early
        if (isInitialized) {
            Log.d(TAG, "Categories already initialized, skipping")
            return
        }

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: "guest_user"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if we have any categories in the database
                val database = TransactionDatabase.getDatabase(context)
                var categories = database.categoryDao().getAllCategories(userId).first()

                // Only proceed if there are no categories at all
                if (categories.isEmpty()) {
                    Log.d(TAG, "No categories in DB, syncing from Firestore")
                    syncCategoriesFromFirestore(context, userId)

                    // Check again after sync
                    categories = database.categoryDao().getAllCategories(userId).first()

                    // If still empty after sync, add default categories
                    if (categories.isEmpty()) {
                        Log.d(TAG, "No categories after Firestore sync, adding defaults")
                        addDefaultCategories(context, userId)
                    }
                }

                isInitialized = true
                Log.d(TAG, "Category initialization complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing categories", e)
            }
        }
    }

    suspend fun loadCategoriesToSpinner(
        context: Context,
        spinner: Spinner,
        userId: String,
        selectedCategory: String? = null
    ) {
        try {
            Log.d(TAG, "Loading categories to spinner for userId: $userId")
            val database = TransactionDatabase.getDatabase(context)

            // Simply load existing categories
            val categories = withContext(Dispatchers.IO) {
                database.categoryDao().getAllCategories(userId).first()
            }

            val categoryNames = categories.map { it.name }.toMutableList()
            Log.d(TAG, "Loaded ${categoryNames.size} categories: $categoryNames")

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
        } catch (e: Exception) {
            Log.e(TAG, "Error loading categories to spinner", e)
            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, listOf<String>())
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
            }
        }
    }

    suspend fun syncCategoriesFromFirestore(context: Context, userId: String) {
        try {
            Log.d(TAG, "Syncing categories from Firestore for userId: $userId")

            // Skip if guest user or not logged in
            if (userId == "guest_user" || FirebaseAuth.getInstance().currentUser == null) {
                Log.d(TAG, "Skipping Firestore sync for guest user")
                return
            }

            val firestore = FirebaseFirestore.getInstance()
            val database = TransactionDatabase.getDatabase(context)

            // Get categories from Firestore
            val snapshot = withContext(Dispatchers.IO) {
                firestore.collection("users")
                    .document(userId)
                    .collection("categories")
                    .get()
                    .await()
            }

            if (!snapshot.isEmpty) {
                val categories = snapshot.documents.mapNotNull { doc ->
                    try {
                        val name = doc.getString("name") ?: return@mapNotNull null
                        val isDefault = doc.getBoolean("isDefault") ?: false

                        Category(
                            name = name,
                            userId = userId,
                            isDefault = isDefault
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing category document", e)
                        null
                    }
                }

                Log.d(TAG, "Found ${categories.size} categories in Firestore")

                // Save to local database
                withContext(Dispatchers.IO) {
                    categories.forEach { category ->
                        try {
                            // Check if category already exists
                            val existing = database.categoryDao().getCategoryByName(category.name, userId)
                            if (existing == null) {
                                database.categoryDao().insertCategory(category)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving category to local DB: ${category.name}", e)
                        }
                    }
                }
            } else {
                Log.d(TAG, "No categories found in Firestore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing categories from Firestore", e)
        }
    }

    suspend fun addDefaultCategories(context: Context, userId: String) {
        try {
            Log.d(TAG, "Adding default categories for userId: $userId")
            val database = TransactionDatabase.getDatabase(context)
            val firestore = FirebaseFirestore.getInstance()

            withContext(Dispatchers.IO) {
                // Check existing categories first
                val existingCategories = database.categoryDao().getAllCategories(userId).first()
                val existingNames = existingCategories.map { it.name }.toSet()

                for (name in defaultCategories) {
                    if (!existingNames.contains(name)) {
                        val category = Category(
                            name = name,
                            userId = userId,
                            isDefault = true
                        )

                        try {
                            // Add to local database
                            database.categoryDao().insertCategory(category)

                            // Add to Firestore if logged in
                            if (userId != "guest_user" && FirebaseAuth.getInstance().currentUser != null) {
                                val query = firestore.collection("users")
                                    .document(userId)
                                    .collection("categories")
                                    .whereEqualTo("name", name)
                                    .get()
                                    .await()

                                if (query.isEmpty) {
                                    firestore.collection("users")
                                        .document(userId)
                                        .collection("categories")
                                        .add(mapOf(
                                            "name" to name,
                                            "isDefault" to true
                                        ))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error adding default category: $name", e)
                        }
                    }
                }
            }
            Log.d(TAG, "Finished adding default categories")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding default categories", e)
        }
    }

    suspend fun addCategory(context: Context, category: Category) {
        val database = TransactionDatabase.getDatabase(context)
        val firestore = FirebaseFirestore.getInstance()

        withContext(Dispatchers.IO) {
            try {
                // Check if category already exists
                val existing = database.categoryDao().getCategoryByName(category.name, category.userId)
                if (existing == null) {
                    // Save to local database
                    database.categoryDao().insertCategory(category)

                    // Save to Firestore if logged in
                    category.userId?.let { userId ->
                        if (userId != "guest_user" && FirebaseAuth.getInstance().currentUser != null) {
                            firestore.collection("users")
                                .document(userId)
                                .collection("categories")
                                .add(mapOf(
                                    "name" to category.name,
                                    "isDefault" to category.isDefault
                                ))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding category", e)
                throw e
            }
        }
    }

    suspend fun updateCategory(context: Context, category: Category) {
        val database = TransactionDatabase.getDatabase(context)
        val firestore = FirebaseFirestore.getInstance()

        withContext(Dispatchers.IO) {
            try {
                database.categoryDao().updateCategory(category)

                category.userId?.let { userId ->
                    if (userId != "guest_user" && FirebaseAuth.getInstance().currentUser != null) {
                        firestore.collection("users")
                            .document(userId)
                            .collection("categories")
                            .whereEqualTo("name", category.name)
                            .get()
                            .await()
                            .documents
                            .forEach { doc ->
                                doc.reference.update("name", category.name)
                            }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating category", e)
                throw e
            }
        }
    }

    suspend fun deleteCategory(context: Context, category: Category) {
        if (category.isDefault) {
            Log.d(TAG, "Attempted to delete default category: ${category.name}")
            throw IllegalArgumentException("Cannot delete default category")
        }

        val database = TransactionDatabase.getDatabase(context)
        val firestore = FirebaseFirestore.getInstance()

        withContext(Dispatchers.IO) {
            try {
                database.categoryDao().deleteCategory(category)

                category.userId?.let { userId ->
                    if (userId != "guest_user" && FirebaseAuth.getInstance().currentUser != null) {
                        firestore.collection("users")
                            .document(userId)
                            .collection("categories")
                            .whereEqualTo("name", category.name)
                            .get()
                            .await()
                            .documents
                            .forEach { doc ->
                                doc.reference.delete()
                            }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting category", e)
                throw e
            }
        }
    }

    fun resetInitialization() {
        isInitialized = false
    }
}