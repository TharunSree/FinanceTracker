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

class SMSProcessingService : Service() {
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
                val transaction = Transaction(
                    id = 0,  // Room will generate this
                    name = transactionDetails.merchant ?: "Unknown Merchant",
                    amount = transactionDetails.amount,
                    date = transactionDetails.date ?: System.currentTimeMillis(),
                    category = transactionDetails.category ?: "Uncategorized",
                    merchant = transactionDetails.merchant ?: "Unknown",
                    description = transactionDetails.description ?: "",
                    userId = userId,
                    isCredit = transactionDetails.isCredit
                )

                // Check if we need more details from the user
                if (transactionDetails.requiresUserReview) {  // Changed from needsUserInput to requiresUserReview
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
            putExtra("TRANSACTION_IS_CREDIT", transaction.isCredit)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create notification channel if running on Android O or later
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MainActivity.DETAILS_REQUIRED_CHANNEL_ID,
                "Transaction Details Required",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for transactions that need user input"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Create the notification
        val notification = NotificationCompat.Builder(this, MainActivity.DETAILS_REQUIRED_CHANNEL_ID)
            .setContentTitle("New Transaction Detected")
            .setContentText("Tap to review and categorize: ₹${String.format("%.2f", transaction.amount)} at ${transaction.merchant}")
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
                val closeTime = Math.abs(existingTx.date - transaction.date) < 3600000 // Within 1 hour
                val sameMerchant = existingTx.merchant.equals(transaction.merchant, ignoreCase = true)

                sameAmount && closeTime && sameMerchant
            }
        }

        if (!isDuplicate) {
            // Insert into Room database
            val id = withContext(Dispatchers.IO) {
                database.transactionDao().insertTransaction(transaction)
            }

            Log.d(TAG, "Transaction saved to local database with ID: $id")

            // Sync to Firestore if user is authenticated
            auth.currentUser?.let {
                syncTransactionToFirestore(transaction.copy(id = id.toInt()))
            }

            // Show notification about the transaction
            showTransactionAddedNotification(transaction)
        } else {
            Log.e(TAG, "Skipping duplicate transaction: ${transaction.amount} at ${transaction.merchant}")
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

        // Update the documentId in the transaction
        val updatedTransaction = transaction.copy(documentId = docId)

        // Create a map with all transaction data
        val transactionMap = hashMapOf(
            "id" to updatedTransaction.id,
            "name" to updatedTransaction.name,
            "amount" to updatedTransaction.amount,
            "date" to updatedTransaction.date,
            "category" to updatedTransaction.category,
            "merchant" to updatedTransaction.merchant,
            "description" to updatedTransaction.description,
            "documentId" to docId,
            "userId" to userId,
            "isCredit" to updatedTransaction.isCredit
        )

        // Save to Firestore
        docRef.set(transactionMap)
            .addOnSuccessListener {
                Log.d(TAG, "Transaction added to Firestore with ID: $docId")

                // Update local database with document ID
                serviceScope.launch {
                    database.transactionDao().updateTransaction(updatedTransaction)
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

        // Create notification channel if running on Android O or later
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MainActivity.TRANSACTION_CHANNEL_ID,
                "Transaction Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for new transactions"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Format amount with currency
        val formattedAmount = String.format("%.2f", transaction.amount)
        val transactionType = if (transaction.isCredit) "received" else "spent"

        // Create the notification
        val notification = NotificationCompat.Builder(this, MainActivity.TRANSACTION_CHANNEL_ID)
            .setContentTitle("Transaction Recorded")
            .setContentText("${if (transaction.isCredit) "+" else "-"}₹$formattedAmount ${transaction.merchant}")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        // Show the notification with a unique ID based on the transaction details
        notificationManager.notify(transaction.hashCode(), notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}