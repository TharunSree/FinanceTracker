// BudgetDao.kt
package com.example.financetracker.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.financetracker.database.entity.Budget
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudget(budget: Budget)

    @Update
    suspend fun updateBudget(budget: Budget)

    @Delete
    suspend fun deleteBudget(budget: Budget)

    @Query("SELECT * FROM budget_table WHERE userId = :userId")
    fun getAllBudgets(userId: String?): Flow<List<Budget>>

    @Query("SELECT * FROM budget_table WHERE category = :category AND userId = :userId")
    suspend fun getBudgetForCategory(category: String, userId: String?): Budget?
}