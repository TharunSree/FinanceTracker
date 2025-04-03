package com.example.financetracker.database.dao

import androidx.room.*
import com.example.financetracker.database.entity.Merchant
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the Merchant entity.
 */
@Dao
interface MerchantDao {

    /**
     * Inserts a merchant into the table. If a merchant with the same name and userId
     * already exists (based on index), the insertion is ignored.
     * Returns the row ID of the newly inserted merchant, or -1 if ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(merchant: Merchant): Long

    /**
     * Finds a merchant by its name. Note: This might return multiple merchants if
     * the name is not unique across different users. Use findByNameAndUser for specifics.
     */
    @Query("SELECT * FROM merchant_table WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Merchant?

    /**
     * Finds a specific merchant entry by name and associated user ID.
     */
    @Query("SELECT * FROM merchant_table WHERE name = :name AND userId = :userId LIMIT 1")
    suspend fun findByNameAndUser(name: String, userId: String): Merchant?

    /**
     * Retrieves the category associated with a specific merchant name for a given user.
     * Returns the category name string or null if no matching merchant/user entry is found.
     * Required by SmsProcessingService.
     */
    @Query("""
        SELECT category FROM merchant_table
        WHERE name = :merchantName AND userId = :userId
        LIMIT 1
    """)
    suspend fun getCategoryForMerchant(merchantName: String, userId: String): String?

    /**
     * Gets all merchants (potentially across all users). Useful for admin or global views.
     */
    @Query("SELECT * FROM merchant_table ORDER BY name ASC")
    fun getAllMerchants(): Flow<List<Merchant>>

    /**
     * Gets all merchants associated with a specific user.
     */
    @Query("SELECT * FROM merchant_table WHERE userId = :userId ORDER BY name ASC")
    fun getAllMerchantsForUser(userId: String): Flow<List<Merchant>>

    // Add Update and Delete functions if needed
    @Update
    suspend fun update(merchant: Merchant)

    @Delete
    suspend fun delete(merchant: Merchant)
}
