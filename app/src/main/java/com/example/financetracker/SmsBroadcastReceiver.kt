package com.example.financetracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.utils.MessageExtractor
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsBroadcastReceiver : BroadcastReceiver() {

    // Use SupervisorJob to prevent cancellation of all coroutines if one fails
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "SmsBroadcastReceiver"
        private const val TRANSACTION_CHANNEL_ID = "transaction_channel"
        private const val DETAILS_CHANNEL_ID = "details_channel"

        // Add a static property to track receiver initialization
        var isInitialized = false
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.e(TAG, "Null context or intent")
            return
        }

        // Create a debug notification to confirm receiver is working
        debugNotification(context, "SMS Receiver triggered: ${intent.action}")
        Log.d(TAG, "SMS BroadcastReceiver activated with action: ${intent.action}")
        isInitialized = true

        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            messages?.forEach { smsMessage ->
                val sender = smsMessage.displayOriginatingAddress
                val messageBody = smsMessage.messageBody

                Log.d(TAG, "SMS from: $sender")
                Log.d(TAG, "Message body: $messageBody")

                // Always create a notification with basic SMS info for debugging
                debugNotification(context, "SMS from $sender: ${messageBody.take(20)}...")

                if (isFinancialMessage(sender, messageBody)) {
                    Log.d(TAG, "Financial message detected from sender: $sender")
                    createNotificationChannels(context)

                    // Start a foreground service to process the message reliably
                    val serviceIntent = Intent(context, SmsProcessingService::class.java).apply {
                        putExtra("sender", sender)
                        putExtra("message", messageBody)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    // Also try processing directly as a backup
                    processMessage(context, messageBody)
                } else {
                    Log.d(TAG, "Not a financial message from: $sender")
                }
            }
        } else {
            Log.d(TAG, "Ignoring non-SMS intent: ${intent.action}")
        }
    }

    // In SmsBroadcastReceiver.kt
    private fun processMessage(context: Context, messageBody: String) {
        // REMOVE THIS ENTIRE METHOD OR MAKE IT JUST LOG INSTEAD OF SAVING TRANSACTIONS
        // Let the SmsProcessingService handle everything
        Log.d(TAG, "Forwarding message to SmsProcessingService for processing")
        // No further processing here
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

    private fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Transaction channel
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

            // Details needed channel
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

            // Debug channel
            NotificationChannel(
                "debug_channel",
                "Debug Information",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Debug notifications for app troubleshooting"
                notificationManager.createNotificationChannel(this)
            }
        }
    }

    // Create a debug notification to help troubleshoot background processing
    private fun debugNotification(context: Context, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            NotificationChannel(
                "debug_channel",
                "Debug Information",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Debug notifications for app troubleshooting"
                notificationManager.createNotificationChannel(this)
            }
        }

        val notification = NotificationCompat.Builder(context, "debug_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS Debug")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun showDetailsNeededNotification(
        context: Context,
        transaction: Transaction,
        message: String
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_TRANSACTION_DIALOG", true)
            putExtra("TRANSACTION_MESSAGE", message)
            putExtra("TRANSACTION_AMOUNT", transaction.amount)
            putExtra("TRANSACTION_DATE", transaction.date)
            putExtra("TRANSACTION_MERCHANT", transaction.merchant)
            putExtra("TRANSACTION_DESCRIPTION", transaction.description)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, DETAILS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transaction Details Needed")
            .setContentText("₹${transaction.amount} - Tap to add details")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun showTransactionNotification(context: Context, transaction: Transaction) {
        val notification = NotificationCompat.Builder(context, TRANSACTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transaction Recorded")
            .setContentText("${transaction.name}: ₹${transaction.amount}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun showFailureNotification(context: Context, message: String) {
        val notification = NotificationCompat.Builder(context, DETAILS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transaction Processing Failed")
            .setContentText("Unable to process transaction automatically")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun isFinancialMessage(sender: String, message: String): Boolean {
        // Expanded list of financial senders
        val financialSenders = listOf(
            "SBIUPI", "SBI", "SBIPSG", "HDFCBK", "ICICI", "AXISBK", "PAYTM",
            "GPAY", "PHONEPE", "-SBIINB", "-HDFCBK", "-ICICI", "-AXISBK",
            "CENTBK", "BOIIND", "PNBSMS", "CANBNK", "UNIONB", "8301967659",
            "JUSPAY", "APAY", "VNPAY", "KOTAKB", "INDUSB", "YESBNK",
            "BARODBNK", "IDFC", "IDBI", "ALLBANK", "FM-BOBSMS", "JM-BOBMBS",
            "VM-CENTBN", "JD-INDUSB", "VM-BOBTXN"
        )

        // More comprehensive detection - check sender and keywords in message
        val financialKeywords = listOf(
            "debited", "credited", "transaction", "txn", "a/c", "account",
            "payment", "received", "sent", "transfer", "upi", "debit card",
            "credit card", "balance", "spent", "paid"
        )

        val isSenderFinancial = financialSenders.any { sender.contains(it, ignoreCase = true) }
        val containsFinancialKeywords = financialKeywords.any { message.contains(it, ignoreCase = true) }

        Log.d(TAG, "Is sender financial: $isSenderFinancial, Contains financial keywords: $containsFinancialKeywords")

        // Return true if either condition is met - this makes detection more robust
        return isSenderFinancial || containsFinancialKeywords
    }
}