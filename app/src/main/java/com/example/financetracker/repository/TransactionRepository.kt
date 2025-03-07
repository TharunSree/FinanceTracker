package com.example.financetracker.repository

import androidx.lifecycle.LiveData
import com.example.financetracker.database.dao.TransactionDao
import com.example.financetracker.database.entity.Transaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class TransactionRepository(private val transactionDao: TransactionDao) {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    val transactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()

    suspend fun addTransaction(transaction: Transaction) {
        transactionDao.insertTransaction(transaction)
        syncWithFirestore(transaction)
    }

    suspend fun updateTransaction(transaction: Transaction) {
        transactionDao.updateTransaction(transaction)
        syncWithFirestore(transaction)
    }

    suspend fun deleteTransaction(transaction: Transaction) {
        transactionDao.deleteTransaction(transaction)
        deleteFromFirestore(transaction)
    }

    private suspend fun syncWithFirestore(transaction: Transaction) {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            val userId = currentUser.uid
            withContext(Dispatchers.IO) {
                firestore.collection("users").document(userId).collection("transactions")
                    .document(transaction.id.toString()).set(transaction)
            }
        }
    }

    private suspend fun deleteFromFirestore(transaction: Transaction) {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            val userId = currentUser.uid
            withContext(Dispatchers.IO) {
                firestore.collection("users").document(userId).collection("transactions")
                    .document(transaction.id.toString()).delete()
            }
        }
    }

    suspend fun clearAllTransactions() {
        withContext(Dispatchers.IO) {
            transactionDao.clearTransactions()
        }
    }

    suspend fun loadTransactionsFromFirestore(userId: String) {
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