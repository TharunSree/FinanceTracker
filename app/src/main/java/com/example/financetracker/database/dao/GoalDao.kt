package com.example.financetracker.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.financetracker.database.entity.Goal
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(goal: Goal): Long

    @Update
    suspend fun updateGoal(goal: Goal)

    @Delete
    suspend fun deleteGoal(goal: Goal)

    @Query("SELECT * FROM goal_table WHERE userId = :userId ORDER BY targetDate ASC")
    fun getAllGoals(userId: String): Flow<List<Goal>>

    @Query("SELECT * FROM goal_table WHERE id = :goalId")
    suspend fun getGoalById(goalId: Int): Goal?

    @Query("SELECT * FROM goal_table WHERE userId = :userId")
    suspend fun getAllGoalsOneTime(userId: String): List<Goal>

    @Query("UPDATE goal_table SET currentAmount = :amount WHERE id = :goalId")
    suspend fun updateGoalProgress(goalId: Int, amount: Double)
}