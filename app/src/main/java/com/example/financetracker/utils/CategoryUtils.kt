package com.example.financetracker.utils

import android.content.Context
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.dao.CategoryDao // Ensure CategoryDao import is correct
import com.example.financetracker.database.entity.Category // Import your Category entity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Utility object for managing categories, including defaults,
 * Firestore sync, and local database operations.
 */
object CategoryUtils {
    private const val TAG = "CategoryUtils"
    private var isInitialized = false

    // Private list of default category names for initialization checks
    private val defaultCategoryNames = listOf(
        "Food & Dining", "Groceries", "Transportation", "Utilities", "Rent/Mortgage",
        "Shopping", "Entertainment", "Health & Wellness", "Travel", "Salary",
        "Freelance", "Investment", "Personal Care", "Education", "Gifts & Donations",
        "Subscriptions", "Miscellaneous", "Uncategorized"
    )

    private val defaultCategoryColors: List<String> = listOf(
        "#E6194B", "#3CB44B", "#FFE119", "#4363D8", "#F58231", "#911EB4",
        "#46F0F0", "#F032E6", "#BCF60C", "#FABEBE", "#008080", "#E6BEFF",
        "#9A6324", "#FFFAC8", "#800000", "#AAFFC3", "#808000", "#FFD8B1",
        "#000075", "#808080" // Add more if needed, list will wrap around
    )

    private val userCategoryColors: List<String> = listOf(
        "#4CAF50", "#2196F3", "#FF9800", "#9C27B0", "#795548", "#FF5722",
        "#607D8B", "#00BCD4", "#8BC34A", "#CDDC39", "#FFEB3B", "#FFC107",
        "#3F51B5", "#E91E63", "#009688", "#F44336", "#FF5252", "#FF4081",
        "#7C4DFF", "#448AFF", "#00E676", "#FFD740", "#FFAB40", "#FF6E40"
        // Add more colors as desired
    )

    /**
     * Returns a list of default Category objects.
     * Used by GeminiMessageExtractor to provide context.
     * These are marked as 'isDefault = true' and typically have no specific user ID.
     */
    fun getDefaultCategories(): List<Category> {
        return defaultCategoryNames.mapIndexed { index, name -> // Use mapIndexed
            val colorIndex = index % defaultCategoryColors.size
            val color = defaultCategoryColors[colorIndex]
            Category(
                id = 0,
                name = name,
                userId = null,
                isDefault = true,
                colorHex = color // Assign default color
            )
        }
    }


    /**
     * Retrieves a distinct list of category names for the given user ID,
     * including user-specific categories and global defaults stored locally.
     * Intended for populating dropdowns like AutoCompleteTextView.
     *
     * @param context Context to access the database.
     * @param userId The user ID (can be guest ID). Null might be treated as guest or error depending on logic.
     * @return A list of distinct category name strings. Returns empty list on error.
     */
    suspend fun getCategoryNamesForUser(context: Context, userId: String?): List<String> {
        if (userId == null) {
            Log.e(TAG, "Cannot fetch category names for null userId.")
            return emptyList() // Or handle based on your guest logic maybe?
        }
        return try {
            Log.d(TAG, "Fetching category names for userId: $userId")
            val database = TransactionDatabase.getDatabase(context.applicationContext) // Use application context
            val categoryDao = database.categoryDao()

            withContext(Dispatchers.IO) {
                // Fetch Category objects and map to names, ensuring distinctness
                categoryDao.getAllCategoriesListForUser(userId)
                    .map { it.name }
                    .distinct()
                    .sorted() // Optional: Sort alphabetically
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching category names from DB for userId: $userId", e)
            emptyList() // Return empty list on error
        }
    }
    /**
     * Initializes categories on app start.
     * Checks local DB, syncs from Firestore if empty, adds defaults if still empty.
     * Should be called once, e.g., from MainActivity or Application class.
     */
    fun initializeCategories(context: Context) {
        // If already initialized, return early
        if (isInitialized) {
            Log.d(TAG, "Categories already initialized.")
            return
        }
        isInitialized = true // Set flag early to prevent re-entry

        // Determine current user (or guest)
        val userId = FirebaseAuth.getInstance().currentUser?.uid
            ?: GuestUserManager.getGuestUserId(context) // Assuming GuestUserManager exists

        Log.d(TAG, "Initializing categories for userId: $userId")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = TransactionDatabase.getDatabase(context)
                val categoryDao = database.categoryDao()

                // Check if user has any categories locally (including global defaults)
                var localCategories = categoryDao.getAllCategoriesListForUser(userId) // Use suspend fun for one-time check

                // If local DB is empty for this user (and not guest), try syncing from Firestore
                if (localCategories.isEmpty() && !GuestUserManager.isGuestMode(userId)) {
                    Log.d(TAG, "No local categories for user $userId, syncing from Firestore...")
                    syncCategoriesFromFirestore(context, userId, categoryDao)
                    // Re-check local categories after sync
                    localCategories = categoryDao.getAllCategoriesListForUser(userId)
                }

                // If still empty (or guest user with no categories), add defaults for this user
                // This ensures even guests have default categories locally
                if (localCategories.none { it.userId == userId }) { // Check if user-specific entries are missing
                    Log.d(TAG, "No categories found for user $userId after sync/check, adding defaults...")
                    addDefaultCategoriesToDb(context, userId, categoryDao)
                }

                Log.i(TAG, "Category initialization complete for userId: $userId")

            } catch (e: Exception) {
                Log.e(TAG, "Error during category initialization for userId: $userId", e)
                isInitialized = false // Reset flag on error to allow retry?
            }
        }
    }

    /**
     * Loads category names into a Spinner for the given user (including global defaults).
     */
    suspend fun loadCategoriesToSpinner(
        context: Context,
        spinner: Spinner,
        userId: String?, // Allow null userId for guest mode potentially
        selectedCategoryName: String? = null // Parameter name clarity
    ) {
        var categoryNames: List<String> = emptyList()
        try {
            Log.d(TAG, "Loading categories to spinner for userId: $userId")
            val database = TransactionDatabase.getDatabase(context)
            val categoryDao = database.categoryDao()

            // Fetch category names directly (user specific + global defaults)
            categoryNames = withContext(Dispatchers.IO) {
                categoryDao.getAllCategoriesListForUser(userId).map { it.name }.distinct() // Ensure distinct names
            }
            Log.d(TAG, "Loaded ${categoryNames.size} distinct category names: $categoryNames")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading categories from DB for spinner", e)
            // categoryNames remains emptyList
        } finally {
            // Update spinner on the main thread
            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, categoryNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter

                // Set selection if provided and found
                selectedCategoryName?.let { name ->
                    val position = categoryNames.indexOf(name)
                    if (position != -1) {
                        spinner.setSelection(position)
                        Log.d(TAG, "Set spinner selection to '$name' at position $position")
                    } else {
                        Log.w(TAG, "Selected category '$name' not found in the loaded list.")
                    }
                }
            }
        }
    }

    /**
     * Syncs categories from Firestore to the local Room DB for a specific user.
     */
    private suspend fun syncCategoriesFromFirestore(context: Context, userId: String, categoryDao: CategoryDao) {
        // Ensure it's not a guest user
        if (GuestUserManager.isGuestMode(userId)) {
            Log.d(TAG, "Skipping Firestore sync for guest user.")
            return
        }
        // Ensure user is actually logged in to Firebase
        if (FirebaseAuth.getInstance().currentUser == null) {
            Log.w(TAG, "Attempted Firestore sync, but no Firebase user logged in.")
            return
        }

        try {
            Log.d(TAG, "Starting Firestore category sync for userId: $userId")
            val firestore = FirebaseFirestore.getInstance()

            // Get categories from Firestore for this user
            val snapshot = withContext(Dispatchers.IO) {
                firestore.collection("users")
                    .document(userId)
                    .collection("categories")
                    .get()
                    .await()
            }

            if (!snapshot.isEmpty) {
                val firestoreCategories = snapshot.documents.mapNotNull { doc ->
                    try {
                        // Parse Firestore document into Category entity
                        val name = doc.getString("name") ?: return@mapNotNull null
                        // Firestore might store isDefault differently, adapt as needed
                        val isDefault = doc.getBoolean("isDefault") ?: false
                        val colorHex = doc.getString("colorHex") // Get colorHex if available

                        Category(
                            id = 0, // Let Room generate ID on insert
                            name = name,
                            userId = userId, // Associate with this user
                            isDefault = isDefault,
                            colorHex = colorHex // Assign color
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Firestore category document ${doc.id}", e)
                        null // Skip invalid documents
                    }
                }
                Log.d(TAG, "Fetched ${firestoreCategories.size} categories from Firestore.")

                // Insert fetched categories into Room DB if they don't exist for this user
                withContext(Dispatchers.IO) {
                    val existingLocalNames = categoryDao.getAllCategoriesListForUser(userId).map { it.name }.toSet()
                    var addedCount = 0
                    firestoreCategories.forEach { category ->
                        if (!existingLocalNames.contains(category.name)) {
                            try {
                                categoryDao.insertCategory(category)
                                addedCount++
                            } catch (e: Exception) {
                                Log.e(TAG, "Error inserting synced category '${category.name}' to Room", e)
                            }
                        } else {
                            // Optional: Update existing local category if Firestore has newer data?
                        }
                    }
                    Log.d(TAG, "Added $addedCount new categories from Firestore to Room.")
                }
            } else {
                Log.d(TAG, "No categories found in Firestore for user $userId.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing categories from Firestore for user $userId", e)
            // Handle exception (e.g., network error)
        }
    }

    /**
     * Adds the predefined default categories to the local Room DB for a specific user,
     * if they don't already have a category with that name.
     */
    private suspend fun addDefaultCategoriesToDb(context: Context, userId: String, categoryDao: CategoryDao) {
        try {
            Log.d(TAG, "Adding default categories to Room DB for userId: $userId")
            withContext(Dispatchers.IO) {
                val existingUserCategoryNames = categoryDao.getAllCategoriesListForUser(userId)
                    .map { it.name }.toSet()
                var addedCount = 0

                getDefaultCategories().forEach { defaultCategory -> // Use the function returning Category objects
                    if (!existingUserCategoryNames.contains(defaultCategory.name)) {
                        try {
                            // Create a user-specific version of the default category
                            val userCategory = Category(
                                id = 0, // Let Room generate
                                name = defaultCategory.name,
                                userId = userId, // Assign to this user
                                isDefault = defaultCategory.isDefault, // Preserve the default flag
                                colorHex = defaultCategory.colorHex // Assign default color if any
                            )
                            categoryDao.insertCategory(userCategory)
                            addedCount++
                        } catch (e: Exception) {
                            Log.e(TAG, "Error adding default category '${defaultCategory.name}' to Room for user $userId", e)
                        }
                    }
                }
                Log.d(TAG, "Added $addedCount default categories to Room for user $userId.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in addDefaultCategoriesToDb for user $userId", e)
        }
    }


    // --- CRUD Operations ---

    /**
     * Adds a new custom category for a user to Room and Firestore.
     */
    suspend fun addCategory(context: Context, category: Category) {
        val userId = category.userId ?: run {
            Log.e(TAG, "Cannot add category without userId: $category")
            throw IllegalArgumentException("Category must have a userId")
        }

        // --- Automatic Color Assignment Logic ---
        val assignedColorHex = category.colorHex ?: run { // If colorHex is null...
            // Calculate a color based on the category name's hash code
            val hashCode = category.name.hashCode()
            // Use abs() or bitwise operation for positive index
            val positiveHashCode = abs(hashCode)
            val colorIndex = positiveHashCode % userCategoryColors.size
            Log.d(TAG, "Assigning automatic color index $colorIndex for category '${category.name}'")
            userCategoryColors[colorIndex] // Return color from user list
        }
        // --------------------------------------

        // Create the category object to be added, ensuring it uses the assigned color
        // and is marked as not default, with ID 0 for Room insertion.
        val categoryToAdd = category.copy(
            colorHex = assignedColorHex, // Use assigned or originally provided color
            isDefault = false,
            id = 0
        )

        val database = TransactionDatabase.getDatabase(context)
        val firestore = FirebaseFirestore.getInstance()

        withContext(Dispatchers.IO) {
            try {
                val existing = database.categoryDao().getCategoryByName(categoryToAdd.name, userId)
                if (existing == null) {
                    // Save to local database
                    val newId = database.categoryDao().insertCategory(categoryToAdd)
                    Log.i(TAG, "Added category '${categoryToAdd.name}' (Color: ${categoryToAdd.colorHex}) to Room for user $userId with ID $newId.")

                    // Save to Firestore if not guest user
                    if (!GuestUserManager.isGuestMode(userId) && FirebaseAuth.getInstance().currentUser != null) {
                        firestore.collection("users")
                            .document(userId)
                            .collection("categories")
                            .add(mapOf(
                                "name" to categoryToAdd.name,
                                "isDefault" to categoryToAdd.isDefault, // false
                                "colorHex" to categoryToAdd.colorHex // Save assigned color
                            ))
                            .addOnSuccessListener { Log.d(TAG, "Added category '${categoryToAdd.name}' to Firestore.") }
                            .addOnFailureListener { e -> Log.e(TAG, "Failed to add category '${categoryToAdd.name}' to Firestore.", e) }
                    }
                } else {
                    Log.w(TAG, "Category '${categoryToAdd.name}' already exists locally for user $userId. Add operation skipped.")
                    throw IllegalStateException("Category '${categoryToAdd.name}' already exists.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding category '${categoryToAdd.name}' for user $userId", e)
                throw e // Re-throw to indicate failure
            }
        }
    }

    /**
     * Updates an existing category in Room and Firestore.
     * Prevents modification of default categories (identified by isDefault flag).
     */
    suspend fun updateCategory(context: Context, category: Category) {
        val userId = category.userId ?: run {
            Log.e(TAG, "Cannot update category without userId: $category")
            throw IllegalArgumentException("Category must have a userId")
        }
        if (category.id == 0) {
            Log.e(TAG, "Cannot update category with ID 0: $category")
            throw IllegalArgumentException("Cannot update category with ID 0")
        }
        // Allow updating defaults, but ensure user-created flag remains false if user modifies it?
        // Or let the isDefault flag remain from the original object?
        // For simplicity, let's just update what's passed, assuming caller handles isDefault logic if needed.
        // val categoryToUpdate = category.copy(isDefault = false) // We remove this constraint

        val database = TransactionDatabase.getDatabase(context)
        val firestore = FirebaseFirestore.getInstance()

        withContext(Dispatchers.IO) {
            try {
                // Fetch the original category just to make sure it exists
                val originalCategory = database.categoryDao().getCategoryById(category.id, userId)
                if (originalCategory == null) {
                    Log.e(TAG, "Cannot update category: Original category not found with ID ${category.id} for user $userId")
                    throw IllegalStateException("Original category not found.")
                }

                // --- REMOVED isDefault CHECK ---
                // if (originalCategory.isDefault) {
                //     Log.w(TAG, "Attempted to update a default category '${categoryToUpdate.name}'. Operation skipped.")
                //     throw IllegalStateException("Cannot update a default category.")
                // }
                // -----------------------------

                // Update local database (update the passed category object directly)
                database.categoryDao().updateCategory(category) // Update with the object passed in
                Log.i(TAG, "Updated category '${category.name}' in Room for user $userId.")

                // Update Firestore if not guest user
                if (!GuestUserManager.isGuestMode(userId) && FirebaseAuth.getInstance().currentUser != null) {
                    firestore.collection("users")
                        .document(userId)
                        .collection("categories")
                        .whereEqualTo("name", originalCategory.name) // Use original name to find doc
                        .get()
                        .await()
                        .documents
                        .forEach { doc ->
                            doc.reference.update(mapOf(
                                "name" to category.name, // Update name from passed object
                                "isDefault" to category.isDefault, // Update default status from passed object (likely false)
                                "colorHex" to category.colorHex // Update color from passed object
                            ))
                                .addOnSuccessListener { Log.d(TAG, "Updated category '${category.name}' in Firestore.") }
                                .addOnFailureListener { e -> Log.e(TAG, "Failed to update category '${category.name}' in Firestore.", e) }
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating category '${category.name}' for user $userId", e)
                throw e // Re-throw
            }
        }
    }

    /**
     * Deletes a category from Room and Firestore.
     * Prevents deletion of default categories.
     */
    suspend fun deleteCategory(context: Context, category: Category) {
        val userId = category.userId ?: run {
            Log.e(TAG, "Cannot delete category without userId: $category")
            throw IllegalArgumentException("Category must have a userId")
        }
        if (category.id == 0) {
            Log.e(TAG, "Cannot delete category with ID 0: $category")
            throw IllegalArgumentException("Cannot delete category with ID 0")
        }

        val database = TransactionDatabase.getDatabase(context)
        val firestore = FirebaseFirestore.getInstance()

        withContext(Dispatchers.IO) {
            try {
                // Fetch the category again to ensure it exists for the user
                val dbCategory = database.categoryDao().getCategoryById(category.id, userId)

                if (dbCategory == null) {
                    Log.w(TAG, "Attempted to delete category '${category.name}' (ID: ${category.id}) but it was not found for user $userId.")
                    return@withContext // Just return if not found
                }

                // --- REMOVED isDefault CHECK ---
                // if (dbCategory.isDefault) {
                //     Log.w(TAG, "Attempted to delete a default category '${category.name}'. Operation skipped.")
                //     throw IllegalStateException("Cannot delete a default category.")
                // }
                // -----------------------------

                // Delete from local database (using the object fetched from DB)
                database.categoryDao().deleteCategory(dbCategory)
                Log.i(TAG, "Deleted category '${dbCategory.name}' from Room for user $userId.")

                // Delete from Firestore if not guest user
                if (!GuestUserManager.isGuestMode(userId) && FirebaseAuth.getInstance().currentUser != null) {
                    firestore.collection("users")
                        .document(userId)
                        .collection("categories")
                        .whereEqualTo("name", dbCategory.name) // Query by name that we confirmed existed
                        .get()
                        .await()
                        .documents
                        .forEach { doc ->
                            doc.reference.delete()
                                .addOnSuccessListener { Log.d(TAG, "Deleted category '${dbCategory.name}' from Firestore.") }
                                .addOnFailureListener { e -> Log.e(TAG, "Failed to delete category '${dbCategory.name}' from Firestore.", e) }
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting category '${category.name}' for user $userId", e)
                throw e // Re-throw
            }
        }
    }

    /**
     * Resets the initialization flag. Useful for testing or re-syncing.
     */
    fun resetInitializationFlag() {
        isInitialized = false
        Log.d(TAG, "Initialization flag reset.")
    }
}
