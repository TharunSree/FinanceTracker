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
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*

class SmsProcessingService : Service() {

    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "SmsProcessingService"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val SERVICE_CHANNEL_ID = "sms_processing_channel"
        private const val TRANSACTION_CHANNEL_ID = "transaction_channel"
        private const val DETAILS_CHANNEL_ID = "details_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "SMS Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background processing of SMS messages"
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            // Also create transaction channels
            NotificationChannel(
                TRANSACTION_CHANNEL_ID,
                "Transactions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Transaction notifications"
                enableLights(true)
                enableVibration(true)
                notificationManager.createNotificationChannel(this)
            }

            NotificationChannel(
                DETAILS_CHANNEL_ID,
                "Details Required",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications requiring user input"
                enableLights(true)
                enableVibration(true)
                notificationManager.createNotificationChannel(this)
            }
        }
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("Processing SMS")
            .setContentText("Analyzing message for transaction details")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val messageBody = intent?.getStringExtra("message")
        val sender = intent?.getStringExtra("sender")

        Log.d(TAG, "Service started with message from $sender")

        if (messageBody != null) {
            processMessage(messageBody, startId)
        } else {
            Log.e(TAG, "No message body provided in intent")
            stopSelf(startId)
        }

        // If service gets killed, restart it
        return START_REDELIVER_INTENT
    }

    private fun processMessage(messageBody: String, startId: Int) {
        coroutineScope.launch {
            try {
                Log.d(TAG, "Starting message processing with extractor")
                val messageExtractor = MessageExtractor(this@SmsProcessingService)
                val details = messageExtractor.extractTransactionDetails(messageBody)

                updateProcessingNotification("Extracting transaction details...")

                if (details != null) {
                    Log.d(TAG, "Extracted details: $details")

                    // Get user ID
                    val userId = FirebaseAuth.getInstance().currentUser?.uid
                        ?: GuestUserManager.getGuestUserId(applicationContext)

                    // Check for duplicates
                    val database = TransactionDatabase.getDatabase(this@SmsProcessingService)
                    val existingTransaction = database.transactionDao()
                        .getTransactionsInTimeRange(
                            details.date - 60000,
                            details.date + 60000
                        )
                        .firstOrNull { transaction ->
                            transaction.amount == details.amount &&
                                    transaction.userId == userId &&
                                    transaction.name.equals(details.merchant, ignoreCase = true)
                        }

                    if (existingTransaction != null) {
                        Log.d(TAG, "Duplicate transaction found, skipping")
                        stopSelf(startId)
                        return@launch
                    }

                    val transaction = Transaction(
                        id = 0,
                        name = details.merchant.ifBlank { "Unknown Merchant" },
                        amount = details.amount,
                        date = details.date,
                        category = if (details.category == "Uncategorized") "" else details.category,
                        merchant = details.merchant,
                        description = details.description,
                        userId = userId, // Add user ID
                        documentId = ""  // Will be set when added to Firestore
                    )

                    // Update notification
                    updateProcessingNotification("Saving transaction...")

                    // Save to Room database
                    database.transactionDao().insertTransaction(transaction)
                    Log.d(TAG, "Transaction successfully added to the Room database")

                    // Save to Firestore if not a guest user
                    if (!GuestUserManager.isGuestMode(userId)) {
                        addTransactionToFirestore(transaction)
                    }

                    // Show appropriate notification
                    if (transaction.name == "Unknown Merchant" || transaction.category.isBlank()) {
                        showDetailsNeededNotification(transaction, messageBody)
                    } else {
                        showTransactionNotification(transaction)
                    }
                }

                stopSelf(startId)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
                e.printStackTrace()
                showFailureNotification(messageBody)
                stopSelf(startId)
            }
        }
    }

    private fun updateProcessingNotification(statusText: String) {
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("Processing SMS")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun addTransactionToFirestore(transaction: Transaction) {
        // Get userId (either authenticated or guest)
        val userId = FirebaseAuth.getInstance().currentUser?.uid
            ?: GuestUserManager.getGuestUserId(applicationContext)

        // Skip for guest users
        if (GuestUserManager.isGuestMode(userId)) {
            return
        }

        // Set the userId in the transaction
        transaction.userId = userId

        // Create a document reference with a new ID
        val docRef = firestore.collection("users")
            .document(userId)
            .collection("transactions")
            .document()

        // Set the document ID in the transaction
        transaction.documentId = docRef.id

        // Create the transaction map
        val transactionMap = hashMapOf(
            "id" to transaction.id,
            "name" to transaction.name,
            "amount" to transaction.amount,
            "date" to transaction.date,
            "category" to transaction.category,
            "merchant" to transaction.merchant,
            "description" to transaction.description,
            "documentId" to transaction.documentId,
            "userId" to userId
        )

        // Save to Firestore
        docRef.set(transactionMap)
            .addOnSuccessListener {
                Log.d(TAG, "Transaction successfully added to Firestore")
                // Update the Room database with the document ID
                updateTransactionInRoom(transaction)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error adding transaction to Firestore: ${e.message}")
            }
    }

    private fun updateTransactionInRoom(transaction: Transaction) {
        coroutineScope.launch {
            try {
                val database = TransactionDatabase.getDatabase(applicationContext)
                database.transactionDao().updateTransaction(transaction)
                Log.d(TAG, "Updated transaction in Room with documentId: ${transaction.documentId}")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating transaction in Room", e)
            }
        }
    }

    private fun showDetailsNeededNotification(transaction: Transaction, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_TRANSACTION_DIALOG", true)
            putExtra("TRANSACTION_MESSAGE", message)
            putExtra("TRANSACTION_AMOUNT", transaction.amount)
            putExtra("TRANSACTION_DATE", transaction.date)
            putExtra("TRANSACTION_MERCHANT", transaction.merchant)
            putExtra("TRANSACTION_DESCRIPTION", transaction.description)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, DETAILS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transaction Details Needed")
            .setContentText("₹${transaction.amount} - Tap to add details")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun showTransactionNotification(transaction: Transaction) {
        val notification = NotificationCompat.Builder(this, TRANSACTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transaction Recorded")
            .setContentText("${transaction.name}: ₹${transaction.amount}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun showFailureNotification(message: String) {
        val notification = NotificationCompat.Builder(this, DETAILS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transaction Processing Failed")
            .setContentText("Unable to process transaction automatically")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}