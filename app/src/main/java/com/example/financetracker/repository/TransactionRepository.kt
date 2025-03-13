package com.example.financetracker.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.example.financetracker.database.dao.TransactionDao
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.utils.GuestUserManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class TransactionRepository(
    private val transactionDao: TransactionDao,
    private val context: Context
) {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    val transactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()

    suspend fun addTransaction(transaction: Transaction) {
        // Make sure transaction has a userId
        if (transaction.userId.isNullOrEmpty()) {
            transaction.userId = getCurrentUserId()
        }

        transactionDao.insertTransaction(transaction)
        syncWithFirestore(transaction)
    }

    suspend fun updateTransaction(transaction: Transaction) {
        // Make sure transaction has a userId
        if (transaction.userId.isNullOrEmpty()) {
            transaction.userId = getCurrentUserId()
        }

        transactionDao.updateTransaction(transaction)
        syncWithFirestore(transaction)
    }

    suspend fun deleteTransaction(transaction: Transaction) {
        transactionDao.deleteTransaction(transaction)
        deleteFromFirestore(transaction)
    }

    private fun getCurrentUserId(): String {
        val currentUser = auth.currentUser
        return if (currentUser != null) {
            currentUser.uid
        } else {
            // Use guest user ID if not logged in
            GuestUserManager.getGuestUserId(context)
        }
    }

    private suspend fun syncWithFirestore(transaction: Transaction) {
        val userId = getCurrentUserId()
        val isGuest = GuestUserManager.isGuestMode(userId)

        // Only sync to Firestore for authenticated users
        if (!isGuest) {
            withContext(Dispatchers.IO) {
                // Create a document reference to get an ID if it doesn't already have one
                val docRef = if (transaction.documentId.isEmpty()) {
                    firestore.collection("users").document(userId).collection("transactions").document()
                } else {
                    firestore.collection("users").document(userId).collection("transactions")
                        .document(transaction.documentId)
                }

                // If this is a new transaction, update its document ID
                if (transaction.documentId.isEmpty()) {
                    transaction.documentId = docRef.id
                    // Update Room with the new document ID
                    transactionDao.updateTransaction(transaction)
                }

                // Store in Firestore
                docRef.set(transaction)
            }
        }
    }

    private suspend fun deleteFromFirestore(transaction: Transaction) {
        val userId = getCurrentUserId()
        val isGuest = GuestUserManager.isGuestMode(userId)

        // Only delete from Firestore for authenticated users
        if (!isGuest && transaction.documentId.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                firestore.collection("users").document(userId).collection("transactions")
                    .document(transaction.documentId).delete()
            }
        }
    }

    suspend fun clearAllTransactions() {
        withContext(Dispatchers.IO) {
            transactionDao.clearTransactions()
        }
    }

    suspend fun loadTransactionsFromFirestore(userId: String) {
        val isGuest = GuestUserManager.isGuestMode(userId)

        // Only load from Firestore for authenticated users
        if (!isGuest) {
            withContext(Dispatchers.IO) {
                try {
                    val result = firestore.collection("users").document(userId)
                        .collection("transactions").get().await()

                    val transactions = result.toObjects(Transaction::class.java)

                    // Clear existing transactions and insert new ones from Firestore
                    transactionDao.clearTransactions()
                    transactions.forEach {
                        transactionDao.insertTransaction(it)
                    }
                } catch (e: Exception) {
                    // Handle errors
                }
            }
        }
    }
}