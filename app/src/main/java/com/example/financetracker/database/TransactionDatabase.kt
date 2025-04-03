package com.example.financetracker.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
// import androidx.room.migration.AutoMigrationSpec // Needed for auto-migration
// import androidx.sqlite.db.SupportSQLiteDatabase // Needed for manual migration
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
        Category::class, // Now includes colorHex
        Budget::class
    ],
    version = 9, // <--- INCREMENT THE VERSION (e.g., from 8 to 9)
    exportSchema = true // Recommended to export schema for migrations
    // --- Add Auto-migration information if needed ---
    /*
    autoMigrations = [
        androidx.room.AutoMigration(from = 7, to = 8) // Specify versions
    ]
    */
    // --- OR handle manual migrations ---
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
                    // IMPORTANT: For production, implement proper migrations.
                    // Destructive migration deletes old data on version change.
                    .fallbackToDestructiveMigration()
                    // --- OR add migrations ---
                    // .addMigrations(MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // --- Example Manual Migration (if not using Auto or Destructive) ---
        /*
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add the new column to the existing table
                db.execSQL("ALTER TABLE category_table ADD COLUMN colorHex TEXT")
            }
        }
        */
    }
}