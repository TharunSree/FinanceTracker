package com.example.financetracker.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.financetracker.database.entity.Category
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the Category entity.
 * Includes functions for CRUD, fetching lists/flows, and color updates.
 */
@Dao
interface CategoryDao {

    /**
     * Inserts a category. If a category conflicts (based on unique index), it's replaced.
     * Returns the row ID of the inserted/replaced item.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: Category): Long // Changed to return Long for row ID

    /**
     * Updates an existing category.
     */
    @Update
    suspend fun updateCategory(category: Category)

    /**
     * Deletes a category.
     */
    @Delete
    suspend fun deleteCategory(category: Category)

    @Query("DELETE FROM category_table WHERE id = :categoryId")
    suspend fun deleteCategoryById(categoryId: Int)

    /**
     * Gets all categories for a specific user OR global categories (where userId is NULL).
     * Returns a Flow for observing changes.
     * Used by UI components to display category lists.
     */
    @Query("SELECT * FROM category_table WHERE userId = :userId OR userId IS NULL ORDER BY name ASC")
    fun getAllCategories(userId: String?): Flow<List<Category>>

    /**
     * Gets all categories for a specific user OR global categories as a List.
     * Use this for one-time fetches where a Flow is not needed.
     * Required by CategoryUtils.
     */
    @Query("SELECT * FROM category_table WHERE userId = :userId OR userId IS NULL ORDER BY name ASC")
    suspend fun getAllCategoriesListForUser(userId: String?): List<Category> // Added suspend fun returning List

    /**
     * Gets ALL categories from the table, regardless of user, as a List.
     * Required by GeminiMessageExtractor to get all available category names.
     */
    @Query("SELECT * FROM category_table ORDER BY name ASC")
    suspend fun getAllCategoriesList(): List<Category> // Added suspend fun returning List

    /**
     * Gets a specific category by its ID and associated user ID (or null for global).
     * Ensures the correct user's category is retrieved.
     * Required by CategoryUtils deleteCategory check.
     */
    // **** UPDATED FUNCTION SIGNATURE AND QUERY ****
    @Query("SELECT * FROM category_table WHERE id = :id AND (userId = :userId OR (:userId IS NULL AND userId IS NULL))")
    suspend fun getCategoryById(id: Int, userId: String?): Category? // ID is Int, Added userId parameter

    /**
     * Gets a specific category by its name and associated user ID (or null for global).
     * Required by CategoryUtils.
     */
    @Query("SELECT * FROM category_table WHERE name = :name AND (userId = :userId OR (:userId IS NULL AND userId IS NULL)) LIMIT 1")
    suspend fun getCategoryByName(name: String, userId: String?): Category? // Updated query for null userId check

    /**
     * Updates only the colorHex field for a specific category ID.
     */
    @Query("UPDATE category_table SET colorHex = :colorHex WHERE id = :categoryId")
    suspend fun updateCategoryColor(categoryId: Int, colorHex: String?) // categoryId is Int
}
