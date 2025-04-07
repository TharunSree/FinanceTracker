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
import com.example.financetracker.model.TransactionDetails
import com.example.financetracker.utils.GeminiMessageExtractor
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.utils.MessageExtractor
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import java.util.Date

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
        val smsTimestamp = intent?.getLongExtra(SmsBroadcastReceiver.EXTRA_TIMESTAMP, System.currentTimeMillis())
            ?: System.currentTimeMillis()

        Log.d(TAG, "Service started with message from $sender")

        if (messageBody != null && sender != null) {
            processMessage(messageBody, sender,smsTimestamp, startId) // Pass startId
        } else {
            Log.e(TAG, "No message body provided in intent")
            stopSelfResult(startId) // Use stopSelfResult
        }

        return START_REDELIVER_INTENT
    }

    // Replace the existing processMessage function in
// FinanceTracker-master/app/src/main/java/com/example/financetracker/SmsProcessingService.kt
// with this enhanced version.

    private fun processMessage(messageBody: String, sender: String, smsTimestamp: Long, startId: Int) {
        coroutineScope.launch {
            var shouldStopService = true
            try {
                Log.d(TAG, "Processing message from $sender received at ${Date(smsTimestamp)}")
                updateProcessingNotification("Extracting details...")
                val geminiExtractor = GeminiMessageExtractor(this@SmsProcessingService) // Ensure it's initialized

                // *** FIX: Avoid destructuring, use .first/.second ***
                val result = geminiExtractor.extractTransactionDetails(messageBody, sender)
                val details: TransactionDetails? = result.first
                val type: String? = result.second // 'type' is less used here, but capture it

                if (details != null) { // <<< *** Check if details are not null ***
                    Log.i(TAG, "Gemini extraction successful. Details: $details")
                    val userId = FirebaseAuth.getInstance().currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)
                    val database = TransactionDatabase.getDatabase(this@SmsProcessingService)
                    val transactionDao = database.transactionDao()
                    val merchantDao = database.merchantDao()

                    // --- Duplicate Check (using SMS timestamp) ---
                    val checkDate = smsTimestamp
                    val timeWindowMillis = 60000L
                    val startTime = checkDate - timeWindowMillis
                    val endTime = checkDate + timeWindowMillis
                    val potentialDuplicates = transactionDao.findPotentialDuplicates(userId, details.amount, startTime, endTime) // <<< Use details.amount

                    val isDuplicate = potentialDuplicates.any {
                        it.merchant.equals(details.merchant, ignoreCase = true) || it.name.equals(details.name, ignoreCase = true) // <<< Use details.merchant / details.name
                    }

                    if (isDuplicate) {
                        Log.w(TAG, "Duplicate transaction detected. Skipping insertion. Details: $details")
                        return@launch
                    }
                    // --- End Duplicate Check ---

                    // Create the transaction object
                    val transaction = Transaction(
                        id = 0,
                        name = details.name, // <<< Use details.name
                        amount = details.amount, // <<< Use details.amount
                        date = smsTimestamp,
                        category = details.category, // <<< Use details.category
                        merchant = details.merchant, // <<< Use details.merchant
                        description = details.description, // <<< Use details.description
                        userId = userId,
                        documentId = null
                    )

                    // Check if confirmation is needed
                    val needsConfirmation = details.category.equals("Uncategorized", ignoreCase = true) // <<< Use details.category

                    Log.i(TAG, "Saving transaction to Room: $transaction")
                    val insertedId = transactionDao.insertTransactionAndGetId(transaction)
                    transaction.id = insertedId

                    if (insertedId > 0) {
                        Log.i(TAG, "Transaction saved to Room with ID: $insertedId")
                        if (!GuestUserManager.isGuestMode(userId)) { addTransactionToFirestore(transaction) }

                        if (needsConfirmation) {
                            Log.d(TAG, "Category is 'Uncategorized', showing details needed notification.")
                            showDetailsNeededNotification(transaction, messageBody, transaction)
                            shouldStopService = false
                        } else {
                            Log.d(TAG, "Category '${details.category}' assigned, showing standard notification.") // <<< Use details.category
                            showTransactionNotification(transaction)
                        }
                    } else {
                        Log.e(TAG, "Failed to insert transaction into Room.")
                    }

                } else {
                    Log.i(TAG, "Gemini determined message is not transactional or failed extraction.")
                }

            } catch (e: Exception) { /* ... error handling ... */ }
            finally { /* ... stop service logic ... */ }
        }
    } // End processMessage


// Make sure the addTransactionToFirestore, showTransactionNotification,
// showDetailsNeededNotification, and showFailureNotification functions exist
// within SmsProcessingService or are accessible to it. // End processMessage

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

    // In FinanceTracker-master/app/src/main/java/com/example/financetracker/SmsProcessingService.kt

    // Inside showDetailsNeededNotification function:
    private fun showDetailsNeededNotification(transaction: Transaction, message: String, fullTransaction: Transaction) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_TRANSACTION_DIALOG", true)
            putExtra("TRANSACTION_MESSAGE", message)
            putExtra("TRANSACTION_AMOUNT", fullTransaction.amount)
            putExtra("TRANSACTION_DATE", fullTransaction.date) // Pass the original date (might be 0L)
            putExtra("TRANSACTION_MERCHANT", fullTransaction.merchant)
            putExtra("TRANSACTION_DESCRIPTION", fullTransaction.description)
            // *** ADD TRANSACTION ID ***
            putExtra("TRANSACTION_ID", fullTransaction.id) // Pass the ID assigned by Room
            // *** ------------------ ***
        }

        // Unique request code prevents PendingIntents for different transactions from cancelling each other
        val requestCode = System.currentTimeMillis().toInt()

        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode, // Use unique request code
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ... (rest of notification building)
        val notification = NotificationCompat.Builder(this, DETAILS_CHANNEL_ID)
            // ... (set icon, title, text, priority, etc.) ...
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Use a unique notification ID based on transaction ID or time to allow multiple notifications
        val notificationId = transaction.id.toInt() // Use transaction ID as notification ID
        notificationManager.notify(notificationId, notification)
        Log.d(TAG, "Details Needed Notification sent for Transaction ID: ${transaction.id}")
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

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel() // Cancel the coroutine job
    }
}