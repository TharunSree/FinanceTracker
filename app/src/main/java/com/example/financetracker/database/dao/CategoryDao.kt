// CategoryDao.kt
package com.example.financetracker.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.financetracker.database.entity.Category
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: Category)

    @Update
    suspend fun updateCategory(category: Category)

    @Delete
    suspend fun deleteCategory(category: Category)

    @Query("SELECT * FROM category_table WHERE userId = :userId OR userId IS NULL ORDER BY name ASC")
    fun getAllCategories(userId: String?): Flow<List<Category>>

    @Query("SELECT * FROM category_table WHERE id = :id")
    suspend fun getCategoryById(id: Int): Category?
}