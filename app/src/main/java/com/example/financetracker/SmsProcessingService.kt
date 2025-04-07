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
            processMessage(messageBody, startId) // Pass startId
        } else {
            Log.e(TAG, "No message body provided in intent")
            stopSelfResult(startId) // Use stopSelfResult
        }

        return START_REDELIVER_INTENT
    }

    // Replace the existing processMessage function in
// FinanceTracker-master/app/src/main/java/com/example/financetracker/SmsProcessingService.kt
// with this enhanced version.

    private fun processMessage(messageBody: String, startId: Int) {
        coroutineScope.launch {
            val shouldStopService = true // Flag to control stopping the service
            try {
                Log.d(TAG, "Starting message processing with extractor for message: \"$messageBody\"") // Log message body
                updateProcessingNotification("Extracting transaction details...")
                val messageExtractor = MessageExtractor(this@SmsProcessingService)
                val details = messageExtractor.extractTransactionDetails(messageBody)

                if (details != null) {
                    Log.d(TAG, "Extracted details: Amount=${details.amount}, Merchant='${details.merchant}', Date=${details.date}, Category='${details.category}', Ref='${details.referenceNumber}'")
                    updateProcessingNotification("Checking for duplicates...")

                    val userId = FirebaseAuth.getInstance().currentUser?.uid
                        ?: GuestUserManager.getGuestUserId(applicationContext)
                    val database = TransactionDatabase.getDatabase(this@SmsProcessingService)
                    val transactionDao = database.transactionDao()
                    val merchantDao = database.merchantDao()

                    // --- Enhanced Duplicate Transaction Check ---
                    val timeWindowMillis = 30000L // +/- 30 seconds (adjust as needed)
                    val startTime = details.date - timeWindowMillis
                    val endTime = details.date + timeWindowMillis

                    val potentialDuplicates = transactionDao.findPotentialDuplicates(
                        userId = userId,
                        amount = details.amount,
                        startTimeMillis = startTime,
                        endTimeMillis = endTime
                    )

                    var isDuplicate = false
                    if (potentialDuplicates.isNotEmpty()) {
                        Log.d(TAG, "Found ${potentialDuplicates.size} potential duplicates based on amount & time window.")
                        for (existing in potentialDuplicates) {
                            // Refine check: Compare merchant (case-insensitive)
                            // Optionally add referenceNumber check if available and reliable
                            val merchantMatch = existing.merchant.equals(details.merchant, ignoreCase = true)
                            // val refNumMatch = details.referenceNumber != null && existing.referenceNumber == details.referenceNumber // Optional

                            if (merchantMatch /* && refNumMatch (if using) */) {
                                isDuplicate = true
                                Log.w(TAG, "Duplicate transaction identified. Skipping insertion. Existing ID: ${existing.id}, Extracted Merchant: '${details.merchant}', Amount: ${details.amount}")
                                break // Stop checking once a duplicate is confirmed
                            } else {
                                Log.d(TAG, "Potential duplicate (ID: ${existing.id}) did not fully match criteria (Existing Merchant: '${existing.merchant}', Extracted Merchant: '${details.merchant}').")
                            }
                        }
                    } else {
                        Log.d(TAG, "No potential duplicates found based on amount & time window.")
                    }

                    if (isDuplicate) {
                        // Don't stop the service here, let finally handle it.
                        // Just return from the coroutine launch block.
                        return@launch
                    }
                    // --- End Enhanced Duplicate Check ---

                    // --- Continue with Merchant Category Check and Saving (Existing Logic) ---
                    updateProcessingNotification("Checking merchant category...")
                    var knownCategory: String? = null
                    if (details.merchant.isNotBlank()) {
                        Log.d(TAG, "Checking category for merchant: ${details.merchant}")
                        knownCategory = merchantDao.getCategoryForMerchant(details.merchant, userId)
                        Log.d(TAG, "Known category for merchant '${details.merchant}': $knownCategory")
                    } else {
                        Log.d(TAG, "Merchant is blank, skipping category check.")
                    }

                    val transaction = Transaction(
                        id = 0, // Room generates ID
                        name = details.merchant.ifBlank { "Unknown Merchant" },
                        amount = details.amount,
                        date = details.date,
                        category = knownCategory ?: "", // Use known category or blank
                        merchant = details.merchant,
                        description = details.description,
                        userId = userId,
                        documentId = "" // Firestore ID added later if needed
                    )

                    updateProcessingNotification("Saving transaction...")
                    transactionDao.insertTransaction(transaction)
                    Log.d(TAG, "Transaction added to Room. ID: ${transaction.id}, Category: '${transaction.category}'") // Log assigned ID

                    // Firestore sync (if needed)
                    if (!GuestUserManager.isGuestMode(userId)) {
                        // Consider moving Firestore sync to Repository/ViewModel if complex
                        addTransactionToFirestore(transaction) // Ensure this function exists and works
                    }

                    // Show appropriate notification
                    if (knownCategory != null) {
                        showTransactionNotification(transaction)
                    } else {
                        showDetailsNeededNotification(transaction, messageBody, transaction) // Pass original message
                    }
                    // --- End Saving Logic ---

                } else {
                    Log.e(TAG, "Could not extract transaction details from message.")
                    // Maybe notify user extraction failed? Or just log.
                    // showFailureNotification(messageBody) // Optional
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing message in Service", e)
                // showFailureNotification(messageBody) // Optional
            } finally {
                if (shouldStopService) {
                    Log.d(TAG, "Stopping service with startId: $startId")
                    stopSelfResult(startId) // Use stopSelfResult
                }
            }
        }
    } // End CoroutineScope

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

    private fun showDetailsNeededNotification(transaction: Transaction, message: String, fullTransaction: Transaction) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_TRANSACTION_DIALOG", true)
            putExtra("TRANSACTION_MESSAGE", message)
            putExtra("TRANSACTION_AMOUNT", fullTransaction.amount)
            putExtra("TRANSACTION_DATE", fullTransaction.date)
            putExtra("TRANSACTION_MERCHANT", fullTransaction.merchant)
            putExtra("TRANSACTION_DESCRIPTION", fullTransaction.description)
            putExtra("TRANSACTION_ID", fullTransaction.id) // Pass the transaction ID
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
            .setContentText("₹${fullTransaction.amount} - Tap to add details")
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

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel() // Cancel the coroutine job
    }
}