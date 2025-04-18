package com.example.financetracker.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.financetracker.database.entity.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Transaction entity.
 * Last updated: 2025-03-07
 * @author TharunSree
 */
@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactionAndGetId(transaction: Transaction): Long

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)

    @Query("DELETE FROM transactions")
    suspend fun clearTransactions()

    @Query("SELECT * FROM transactions WHERE userId = :userId ORDER BY date DESC")
    fun getAllUserTransactions(userId: String): List<Transaction>


    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    // Query for uncategorized transactions
    @Query("SELECT * FROM transactions WHERE category IS NULL OR category = '' ORDER BY date DESC")
    suspend fun getUncategorizedTransactions(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE date BETWEEN :startTime AND :endTime ORDER BY date DESC")
    suspend fun getTransactionsByDateRange(startTime: Long, endTime: Long): List<Transaction>

    /**
     * Get a transaction by its ID.
     */
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Int): Transaction?

    @Query("UPDATE transactions SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun updateUserIdForGuest(
        oldUserId: String,
        newUserId: String
    ): Int // Returns number of rows updated

    /**
     * Get all transactions for a specific category.
     */
    @Query("SELECT * FROM transactions WHERE category = :category ORDER BY date DESC")
    suspend fun getTransactionsByCategory(category: String): List<Transaction>

    /**
     * Get all transactions for a specific user.
     * Used for Firebase/multi-user functionality.
     */
    @Query("SELECT * FROM transactions WHERE userId = :userId ORDER BY date DESC")
    suspend fun getTransactionsByUserId(userId: String): List<Transaction>

    /**
     * Get transactions for a specific date range and user.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE userId = :userId
        AND date BETWEEN :startTime AND :endTime
        ORDER BY date DESC
    """
    )
    suspend fun getTransactionsByDateRange(
        startTime: Long,
        endTime: Long,
        userId: String
    ): List<Transaction> // Ensure userId is parameter

    /**
     * Search for transactions containing the provided search term.
     */
    @Query("SELECT * FROM transactions WHERE name LIKE '%' || :searchTerm || '%' OR merchant LIKE '%' || :searchTerm || '%' OR description LIKE '%' || :searchTerm || '%' ORDER BY date DESC")
    suspend fun searchTransactions(searchTerm: String): List<Transaction>

    /**
     * Get the max expense amount.
     * Used for statistics.
     */
    @Query("SELECT MAX(amount) FROM transactions")
    suspend fun getMaxExpense(): Double?

    /**
     * Get the min expense amount.
     * Used for statistics.
     */
    @Query("SELECT MIN(amount) FROM transactions")
    suspend fun getMinExpense(): Double?

    /**
     * Get all unique categories from transactions.
     * Used for category statistics.
     */
    @Query("SELECT DISTINCT category FROM transactions WHERE category IS NOT NULL AND category != ''")
    suspend fun getAllCategories(): List<String>

    /**
     * Get total spent amount for all transactions.
     */
    @Query("SELECT SUM(amount) FROM transactions")
    suspend fun getTotalExpense(): Double?

    /**
     * Get total spent amount for a specific category.
     */
    @Query("SELECT SUM(amount) FROM transactions WHERE category = :category")
    suspend fun getTotalExpenseByCategory(category: String): Double?

    /**
     * Get count of transactions for a specific category.
     */
    @Query("SELECT COUNT(*) FROM transactions WHERE category = :category")
    suspend fun getTransactionCountByCategory(category: String): Int

    // Add this query to TransactionDao:
    @Query("SELECT * FROM transactions WHERE documentId = :docId AND userId = :userId LIMIT 1")
    suspend fun getTransactionByDocId(docId: String, userId: String): Transaction?

    @Query("SELECT * FROM transactions WHERE date BETWEEN :startTime AND :endTime")
    fun getTransactionsInTimeRange(startTime: Long, endTime: Long): List<Transaction>

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    suspend fun getAllTransactionsList(): List<Transaction> // Ensure this exists

    @Query(
        """
        SELECT * FROM transactions
        WHERE userId = :userId
        AND amount = :amount
        AND date BETWEEN :startTimeMillis AND :endTimeMillis
    """
    )
    suspend fun findPotentialDuplicates(
        userId: String,
        amount: Double,
        startTimeMillis: Long,
        endTimeMillis: Long
    ): List<Transaction>
}