package com.example.financetracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.utils.MessageExtractor
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.abs

class SmsProcessingService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var messageExtractor: MessageExtractor
    private lateinit var database: TransactionDatabase
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "SMSProcessingService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "sms_processing_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SMSProcessingService created")
        messageExtractor = MessageExtractor(this)
        database = TransactionDatabase.getDatabase(this)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SMSProcessingService started")

        // Start as foreground service to avoid being killed
        startForeground(NOTIFICATION_ID, createNotification())

        intent?.let {
            val sender = it.getStringExtra("sender") ?: return@let
            val messageBody = it.getStringExtra("message") ?: return@let

            Log.d(TAG, "Processing SMS from $sender: $messageBody")

            serviceScope.launch {
                processMessage(sender, messageBody)
                // Stop the service once processing is complete
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotification(): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Processing Financial SMS")
            .setContentText("Looking for transaction details...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SMS Processing Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used while processing financial SMS messages"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private suspend fun processMessage(sender: String, messageBody: String) {
        // Get the current userId or create a guest ID
        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)

        try {
            val transactionDetails = messageExtractor.extractTransactionDetails(messageBody)

            if (transactionDetails != null) {
                Log.d(TAG, "Extracted transaction details: $transactionDetails")

                // Generate transaction from extracted details
                // In your processMessage function, update the Transaction creation:
                val transaction = Transaction(
                    name = transactionDetails.merchant ?: "Unknown Merchant",
                    amount = transactionDetails.amount,
                    date = transactionDetails.date ?: System.currentTimeMillis(),
                    category = transactionDetails.category ?: "Uncategorized",
                    merchant = transactionDetails.merchant ?: "Unknown",
                    description = transactionDetails.description ?: "",
                    isCredit = transactionDetails.isCredit ?: false, // Add this line
                    userId = userId
                )

                // Check if we need more details from the user
                if (transactionDetails.needsUserInput) {
                    // Show notification for user to classify transaction
                    showTransactionNotification(transaction, messageBody)
                } else {
                    // Save the transaction directly
                    saveTransaction(transaction)
                }
            } else {
                Log.d(TAG, "No transaction details found in the message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }

    private fun showTransactionNotification(transaction: Transaction, messageBody: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create a pending intent to open MainActivity with the transaction details
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_TRANSACTION_DIALOG", true)
            putExtra("TRANSACTION_MESSAGE", messageBody)
            putExtra("TRANSACTION_AMOUNT", transaction.amount)
            putExtra("TRANSACTION_DATE", transaction.date)
            putExtra("TRANSACTION_MERCHANT", transaction.merchant)
            putExtra("TRANSACTION_DESCRIPTION", transaction.description)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create the notification
        val notification = NotificationCompat.Builder(this, MainActivity.DETAILS_REQUIRED_CHANNEL_ID)
            .setContentTitle("New Transaction Detected")
            .setContentText("Tap to review and categorize: ${transaction.amount} at ${transaction.merchant}")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // Show the notification
        notificationManager.notify(transaction.hashCode(), notification)
    }

    private suspend fun saveTransaction(transaction: Transaction) {
        // First check if this transaction might be a duplicate
        val isDuplicate = withContext(Dispatchers.IO) {
            val allTransactions = database.transactionDao().getAllTransactionsOneTime()

            // Check for similar transactions in the last hour
            allTransactions.any { existingTx ->
                val sameAmount = existingTx.amount == transaction.amount
                val closeTime = abs(existingTx.date - transaction.date) < 3600000 // Within 1 hour
                val sameName = existingTx.name.equals(transaction.name, ignoreCase = true)

                sameAmount && closeTime && sameName
            }
        }

        if (!isDuplicate) {
            // Insert into Room database
            val id = withContext(Dispatchers.IO) {
                database.transactionDao().insertTransaction(transaction)
            }

            Log.d(TAG, "Transaction saved to local database with ID: $id")

            // Sync to Firestore if user is authenticated
            if (auth.currentUser != null) {
                syncTransactionToFirestore(transaction.copy(id = id))
            }

            // Show notification about the transaction
            showTransactionAddedNotification(transaction)
        } else {
            Log.d(TAG, "Skipping duplicate transaction: ${transaction.amount} at ${transaction.merchant}")
        }
    }

    private fun syncTransactionToFirestore(transaction: Transaction) {
        // Create a document reference first to get an ID
        val userId = transaction.userId ?: return
        val docRef = firestore.collection("users")
            .document(userId)
            .collection("transactions")
            .document()

        // Get the document ID
        val docId = docRef.id

        // Set the document ID in the transaction
        transaction.documentId = docId

        // Create a map with all transaction data
        // Create a map with all transaction data
        val transactionMap = hashMapOf(
            "id" to transaction.id,
            "name" to transaction.name,
            "amount" to transaction.amount,
            "date" to transaction.date,
            "category" to transaction.category,
            "merchant" to transaction.merchant,
            "description" to transaction.description,
            "isCredit" to transaction.isCredit, // Add this line
            "documentId" to docId,
            "userId" to userId
        )

        // Save to Firestore
        docRef.set(transactionMap)
            .addOnSuccessListener {
                Log.d(TAG, "Transaction added to Firestore with ID: $docId")

                // Update local database with document ID
                serviceScope.launch {
                    database.transactionDao().updateTransaction(transaction)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error adding transaction to Firestore", e)
            }
    }

    private fun showTransactionAddedNotification(transaction: Transaction) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create intent to open MainActivity
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Format amount with currency
        val formattedAmount = String.format("%.2f", transaction.amount)

        // Create the notification
        val notification = NotificationCompat.Builder(this, MainActivity.TRANSACTION_CHANNEL_ID)
            .setContentTitle("Transaction Recorded")
            .setContentText("${transaction.name}: ₹$formattedAmount")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        // Show the notification with a unique ID based on the transaction details
        notificationManager.notify(transaction.hashCode(), notification)
    }

    override fun onBind(p0: Intent?): IBinder? = null
}