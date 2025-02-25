package com.example.financetracker.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.financetracker.database.entity.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction)

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)

    @Query("DELETE FROM transaction_table")
    suspend fun clearTransactions()

    @Query("SELECT * FROM transaction_table ORDER BY date DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    // New query for uncategorized transactions
    @Query("SELECT * FROM transaction_table WHERE category IS NULL OR category = '' ORDER BY date DESC")
    suspend fun getUncategorizedTransactions(): List<Transaction>

    @Query("SELECT * FROM transaction_table WHERE date BETWEEN :startTime AND :endTime ORDER BY date DESC")
    suspend fun getTransactionsByDateRange(startTime: Long, endTime: Long): List<Transaction>
}