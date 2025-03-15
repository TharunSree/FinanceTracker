package com.example.financetracker.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.financetracker.database.entity.Merchant

@Dao
interface MerchantDao {
    @Insert
    suspend fun insertMerchant(merchant: Merchant)

    @Query("SELECT * FROM merchant_table WHERE name = :name AND (userId = :userId OR userId IS NULL) LIMIT 1")
    suspend fun getMerchantByName(name: String, userId: String?): Merchant?

    // Adding a method to get all merchants for a user
    @Query("SELECT * FROM merchant_table WHERE userId = :userId OR userId IS NULL")
    suspend fun getAllMerchants(userId: String?): List<Merchant>
}