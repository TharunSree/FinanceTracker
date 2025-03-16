package com.example.financetracker.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.financetracker.database.dao.BudgetDao
import com.example.financetracker.database.dao.CategoryDao
import com.example.financetracker.database.dao.MerchantDao
import com.example.financetracker.database.dao.TransactionDao
import com.example.financetracker.database.entity.Budget
import com.example.financetracker.database.entity.Merchant
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.database.entity.Category


@Database(
    entities = [
        Transaction::class,
        Merchant::class,
        Category::class,
        Budget::class
    ],
    version = 7,
    exportSchema = false
)
abstract class TransactionDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun merchantDao(): MerchantDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        @Volatile
        private var INSTANCE: TransactionDatabase? = null

        fun getDatabase(context: Context): TransactionDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TransactionDatabase::class.java,
                    "transaction_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}