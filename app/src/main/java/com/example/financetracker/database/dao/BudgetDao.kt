package com.example.financetracker.database.dao

import androidx.room.*
import com.example.financetracker.database.entity.Budget
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget_table WHERE userId = :userId")
    fun getAllBudgets(userId: String): Flow<List<Budget>>

    @Query("SELECT * FROM budget_table WHERE userId = :userId")
    suspend fun getAllBudgetsOneTime(userId: String): List<Budget>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudget(budget: Budget): Long

    @Update
    suspend fun updateBudget(budget: Budget)

    @Delete
    suspend fun deleteBudget(budget: Budget)

    @Query("DELETE FROM budget_table WHERE userId = :userId")
    suspend fun deleteAllBudgets(userId: String)
}