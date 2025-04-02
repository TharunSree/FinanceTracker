package com.example.financetracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.financetracker.database.entity.Merchant

@Dao
interface MerchantDao {
    @Insert
    suspend fun insertMerchant(merchant: Merchant)

    @Query("SELECT * FROM merchant_table WHERE name = :name LIMIT 1")
    suspend fun getMerchantByName(name: String): Merchant?

    // --- ADD THIS QUERY ---
    /**
     * Retrieves the category associated with a specific merchant name for a user.
     * Returns null if the merchant or category mapping doesn't exist.
     */
    @Query("SELECT category FROM merchant_table WHERE name = :merchantName AND userId = :userId LIMIT 1")
    suspend fun getCategoryForMerchant(merchantName: String, userId: String?): String?
}
