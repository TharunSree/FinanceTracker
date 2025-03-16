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

    suspend fun loadCategoriesToSpinner(
        context: Context,
        spinner: Spinner,
        userId: String,
        selectedCategory: String? = null
    ) {
        try {
            Log.d(TAG, "Loading categories to spinner for userId: $userId")

            val database = TransactionDatabase.getDatabase(context)

            // Get categories from database
            var categories = withContext(Dispatchers.IO) {
                database.categoryDao().getAllCategories(userId).first()
            }

            // Only perform initialization if no categories exist
            if (categories.isEmpty()) {
                Log.d(TAG, "No categories found in local DB, initializing categories")
                initializeCategories(context) // This will handle both Firestore sync and defaults

                // Get categories again after initialization
                categories = withContext(Dispatchers.IO) {
                    database.categoryDao().getAllCategories(userId).first()
                }
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

            // Fallback to default categories from strings.xml
            withContext(Dispatchers.Main) {
                val categoriesArray = context.resources.getStringArray(R.array.transaction_categories)
                val adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_item,
                    categoriesArray
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
            }
        }
    }

    suspend fun syncCategoriesFromFirestore(context: Context, userId: String) {
        try {
            Log.d(TAG, "Syncing categories from Firestore for userId: $userId")
            val firestore = FirebaseFirestore.getInstance()
            val database = TransactionDatabase.getDatabase(context)

            // Skip if guest user
            if (userId == "guest_user" || FirebaseAuth.getInstance().currentUser == null) {
                Log.d(TAG, "Skipping Firestore sync for guest user")
                return
            }

            // Get categories from Firestore
            val snapshot = withContext(Dispatchers.IO) {
                firestore.collection("users")
                    .document(userId)
                    .collection("categories")
                    .get()
                    .await()
            }

            if (!snapshot.isEmpty) {
                // Process Firestore categories
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
                        database.categoryDao().insertCategory(category)
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

            // Get categories from strings.xml
            val categoriesArray = context.resources.getStringArray(R.array.transaction_categories)

            withContext(Dispatchers.IO) {
                // First, check for existing categories
                val existingCategories = database.categoryDao().getAllCategories(userId).first()
                val existingNames = existingCategories.map { it.name }.toSet()

                for (name in categoriesArray) {
                    // Only add if the category doesn't already exist
                    if (!existingNames.contains(name)) {
                        val category = Category(
                            name = name,
                            userId = userId,
                            isDefault = true
                        )
                        database.categoryDao().insertCategory(category)

                        // Also save to Firestore if user is logged in
                        if (userId != "guest_user" && FirebaseAuth.getInstance().currentUser != null) {
                            try {
                                // Check if category exists in Firestore first
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
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving default category to Firestore: $name", e)
                            }
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
            // Save to local database
            database.categoryDao().insertCategory(category)

            // Also save to Firestore if user is logged in
            category.userId?.let { userId ->
                if (userId != "guest_user" && FirebaseAuth.getInstance().currentUser != null) {
                    try {
                        firestore.collection("users")
                            .document(userId)
                            .collection("categories")
                            .add(mapOf(
                                "name" to category.name,
                                "isDefault" to category.isDefault
                            ))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving category to Firestore", e)
                    }
                }
            }
        }
    }

    suspend fun updateCategory(context: Context, category: Category) {
        val database = TransactionDatabase.getDatabase(context)
        val firestore = FirebaseFirestore.getInstance()

        withContext(Dispatchers.IO) {
            database.categoryDao().updateCategory(category)

            // Update in Firestore if user is logged in
            category.userId?.let { userId ->
                if (userId != "guest_user" && FirebaseAuth.getInstance().currentUser != null) {
                    try {
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
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating category in Firestore", e)
                    }
                }
            }
        }
    }

    suspend fun deleteCategory(context: Context, category: Category) {
        val database = TransactionDatabase.getDatabase(context)
        val firestore = FirebaseFirestore.getInstance()

        withContext(Dispatchers.IO) {
            database.categoryDao().deleteCategory(category)

            // Delete from Firestore if user is logged in
            category.userId?.let { userId ->
                if (userId != "guest_user" && FirebaseAuth.getInstance().currentUser != null) {
                    try {
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
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting category from Firestore", e)
                    }
                }
            }
        }
    }

    // Function to initialize categories if needed - call this from MainActivity or Application class
    // Function to initialize categories if needed
    fun initializeCategories(context: Context) {
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
                        addDefaultCategoriesForced(context, userId)
                    }
                } else {
                    Log.d(TAG, "Categories already exist, skipping initialization")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing categories", e)
            }
        }
    }

    // This function will add default categories regardless of existing categories
    // Use this as a fallback to ensure there are always categories available
    private suspend fun addDefaultCategoriesForced(context: Context, userId: String) {
        try {
            Log.d(TAG, "Force adding default categories for userId: $userId")
            val database = TransactionDatabase.getDatabase(context)

            // Fixed list of default categories
            val defaultCats = listOf(
                "Food", "Shopping", "Rent", "Utilities",
                "Transportation", "Entertainment", "Others"
            )

            withContext(Dispatchers.IO) {
                for (name in defaultCats) {
                    try {
                        // Check if category already exists
                        val existing = database.categoryDao().getCategoryByName(name, userId)

                        if (existing == null) {
                            // Only add if it doesn't exist
                            val category = Category(
                                name = name,
                                userId = userId,
                                isDefault = true
                            )
                            database.categoryDao().insertCategory(category)
                            Log.d(TAG, "Added default category: $name")
                        } else {
                            Log.d(TAG, "Category already exists: $name")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding default category: $name", e)
                    }
                }
            }

            // If user is logged in, also save to Firestore
            if (userId != "guest_user" && FirebaseAuth.getInstance().currentUser != null) {
                val firestore = FirebaseFirestore.getInstance()

                withContext(Dispatchers.IO) {
                    for (name in defaultCats) {
                        try {
                            // Check if category exists in Firestore
                            val snapshot = firestore.collection("users")
                                .document(userId)
                                .collection("categories")
                                .whereEqualTo("name", name)
                                .get()
                                .await()

                            // If category doesn't exist, add it
                            if (snapshot.isEmpty) {
                                firestore.collection("users")
                                    .document(userId)
                                    .collection("categories")
                                    .add(mapOf(
                                        "name" to name,
                                        "isDefault" to true
                                    ))
                                Log.d(TAG, "Added default category to Firestore: $name")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error adding default category to Firestore: $name", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding forced default categories", e)
        }
    }
}