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
import com.example.financetracker.utils.MessageExtractor
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

                // Update notification to show progress
                updateProcessingNotification("Extracting transaction details...")

                if (details != null) {
                    Log.d(TAG, "Extracted details: $details")

                    // Check for existing transaction
                    val database = TransactionDatabase.getDatabase(this@SmsProcessingService)
                    val existingTransaction = database.transactionDao()
                        .getTransactionsInTimeRange(
                            details.date - 60000, // 1 minute before
                            details.date + 60000  // 1 minute after
                        )
                        .firstOrNull { transaction ->
                            transaction.amount == details.amount &&
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
                        description = details.description
                    )

                    // Update notification
                    updateProcessingNotification("Saving transaction...")

                    // Save to Room database
                    database.transactionDao().insertTransaction(transaction)
                    Log.d(TAG, "Transaction successfully added to the Room database")

                    // Save to Firestore if needed
                    addTransactionToFirestore(transaction)

                    // Show appropriate notification
                    if (transaction.name == "Unknown Merchant" || transaction.category.isBlank()) {
                        showDetailsNeededNotification(transaction, messageBody)
                    } else {
                        showTransactionNotification(transaction)
                    }
                }

                // Stop the service
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
        firestore.collection("transactions")
            .add(transaction)
            .addOnSuccessListener {
                Log.d(TAG, "Transaction successfully added to Firestore")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error adding transaction to Firestore: ${e.message}")
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