package com.example.financetracker.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.LiveData
import com.example.financetracker.database.dao.TransactionDao
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.utils.GuestUserManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class TransactionRepository(
    private val transactionDao: TransactionDao,
    private val context: Context
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    val transactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun addTransaction(transaction: Transaction) {
        // Make sure transaction has a userId
        if (transaction.userId.isEmpty()) {
            transaction.userId = getCurrentUserId()
        }

        // Always save to Room first
        transactionDao.insertTransaction(transaction)

        // Only sync with Firestore if online and not in guest mode
        if (isNetworkAvailable() && !GuestUserManager.isGuestMode(transaction.userId)) {
            try {
                syncTransactionToFirestore(transaction)
            } catch (e: Exception) {
                // If Firestore sync fails, transaction is still in Room
                // Will be synced later when online
            }
        }
    }

    suspend fun getAllTransactions(): List<Transaction> = withContext(Dispatchers.IO) {
        transactionDao.getAllTransactions().first()
    }

    suspend fun getTransactionsByDateRange(startTime: Long, endTime: Long): List<Transaction> = withContext(Dispatchers.IO) {
        val userId = getCurrentUserId()
        transactionDao.getUserTransactionsByDateRange(startTime, endTime, userId)
    }

    suspend fun getTransactionsByCategory(category: String): List<Transaction> = withContext(Dispatchers.IO) {
        val userId = getCurrentUserId()
        if (category == "All Categories") {
            transactionDao.getTransactionsByUserId(userId)
        } else {
            transactionDao.getTransactionsByCategory(category)
        }
    }

    suspend fun getAllCategories(): List<String> = withContext(Dispatchers.IO) {
        transactionDao.getAllCategories()
    }

    private suspend fun syncTransactionToFirestore(transaction: Transaction) {
        withContext(Dispatchers.IO) {
            val userId = getCurrentUserId()
            if (GuestUserManager.isGuestMode(userId)) return@withContext

            // Create or get document reference
            val docRef = if (transaction.documentId.isEmpty()) {
                firestore.collection("users").document(userId)
                    .collection("transactions").document()
            } else {
                firestore.collection("users").document(userId)
                    .collection("transactions").document(transaction.documentId)
            }

            // Update document ID if this is a new transaction
            if (transaction.documentId.isEmpty()) {
                transaction.documentId = docRef.id
                // Update Room with the new document ID
                transactionDao.updateTransaction(transaction)
            }

            // Store in Firestore
            docRef.set(transaction).await()
        }
    }

    suspend fun syncPendingTransactions() {
        if (!isNetworkAvailable()) return

        val userId = getCurrentUserId()
        if (GuestUserManager.isGuestMode(userId)) return

        val transactions = transactionDao.getAllTransactions().first()
        transactions.forEach { transaction ->
            if (transaction.documentId.isEmpty()) {
                try {
                    syncTransactionToFirestore(transaction)
                } catch (e: Exception) {
                    // Log error but continue with next transaction
                }
            }
        }
    }

    suspend fun loadFromFirestore() {
        if (!isNetworkAvailable()) return

        val userId = getCurrentUserId()
        if (GuestUserManager.isGuestMode(userId)) return

        withContext(Dispatchers.IO) {
            try {
                val result = firestore.collection("users").document(userId)
                    .collection("transactions").get().await()

                val transactions = result.toObjects(Transaction::class.java)

                // Smart merge with existing Room data
                transactions.forEach { firestoreTransaction ->
                    val existingTransaction = transactionDao.getTransactionByDocId(
                        firestoreTransaction.documentId,
                        userId
                    )

                    if (existingTransaction == null) {
                        transactionDao.insertTransaction(firestoreTransaction)
                    } else if (existingTransaction.date < firestoreTransaction.date) {
                        // Firestore has newer version
                        transactionDao.updateTransaction(firestoreTransaction)
                    }
                }
            } catch (e: Exception) {
                // Handle error but don't crash
            }
        }
    }

    private fun getCurrentUserId(): String {
        return auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(context)
    }

    // Other repository methods...
}