package com.example.financetracker.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
import java.util.*

class TransactionRepository(
    private val transactionDao: TransactionDao,
    private val context: Context
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    val transactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetworkInfo: NetworkInfo? = connectivityManager.activeNetworkInfo
        return activeNetworkInfo != null && activeNetworkInfo.isConnected
    }

    suspend fun addTransaction(transaction: Transaction) {
        withContext(Dispatchers.IO) {
            try {
                _loading.postValue(true)
                // Make sure transaction has a userId
                if (transaction.userId.isEmpty()) {
                    transaction.userId = getCurrentUserId()
                }

                // Always save to Room first
                transactionDao.insertTransaction(transaction)

                // Only sync with Firestore if online and not in guest mode
                if (isNetworkAvailable() && !GuestUserManager.isGuestMode(transaction.userId)) {
                    syncTransactionToFirestore(transaction)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding transaction", e)
                _error.postValue("Failed to add transaction: ${e.message}")
            } finally {
                _loading.postValue(false)
            }
        }
    }

    suspend fun updateTransaction(transaction: Transaction) {
        withContext(Dispatchers.IO) {
            try {
                _loading.postValue(true)
                // Always update Room first
                transactionDao.updateTransaction(transaction)

                // Only sync with Firestore if online and not in guest mode
                if (isNetworkAvailable() && !GuestUserManager.isGuestMode(transaction.userId)) {
                    syncTransactionToFirestore(transaction)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating transaction", e)
                _error.postValue("Failed to update transaction: ${e.message}")
            } finally {
                _loading.postValue(false)
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
            try {
                _loading.postValue(true)
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
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing transaction to Firestore", e)
                _error.postValue("Failed to sync transaction to Firestore: ${e.message}")
            } finally {
                _loading.postValue(false)
            }
        }
    }

    suspend fun syncPendingTransactions() {
        if (!isNetworkAvailable()) return

        val userId = getCurrentUserId()
        if (GuestUserManager.isGuestMode(userId)) return

        withContext(Dispatchers.IO) {
            try {
                _loading.postValue(true)
                val transactions = transactionDao.getAllTransactions().first()
                transactions.forEach { transaction ->
                    if (transaction.documentId.isEmpty()) {
                        try {
                            syncTransactionToFirestore(transaction)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error syncing pending transaction", e)
                            _error.postValue("Failed to sync pending transaction: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing pending transactions", e)
                _error.postValue("Failed to sync pending transactions: ${e.message}")
            } finally {
                _loading.postValue(false)
            }
        }
    }

    suspend fun loadFromFirestore() {
        if (!isNetworkAvailable()) return

        val userId = getCurrentUserId()
        if (GuestUserManager.isGuestMode(userId)) return

        withContext(Dispatchers.IO) {
            try {
                _loading.postValue(true)
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
                Log.e(TAG, "Error loading transactions from Firestore", e)
                _error.postValue("Failed to load transactions from Firestore: ${e.message}")
            } finally {
                _loading.postValue(false)
            }
        }
    }

    private fun getCurrentUserId(): String {
        return auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(context)
    }

    companion object {
        private const val TAG = "TransactionRepository"
    }

    suspend fun getUserTransactionsByDateRange(startTime: Long, endTime: Long, userId: String): List<Transaction> = withContext(Dispatchers.IO) {
        transactionDao.getUserTransactionsByDateRange(startTime, endTime, userId)
    }
}